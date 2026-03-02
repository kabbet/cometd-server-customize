package com.jpm.gateway.bayeux;

import com.jpm.gateway.auth.PlatformAuthClient;
import com.jpm.gateway.auth.PlatformAuthClient.AuthResult;
import com.jpm.gateway.config.GatewayConfig;
import org.cometd.bayeux.server.*;
import org.cometd.annotation.Service;
import org.cometd.annotation.Session;
import org.cometd.server.DefaultSecurityPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import java.util.HashMap;
import java.util.Map;

/**
 * CometD Bayeux 服务
 *
 * AnnotationCometDServlet 通过反射无参构造实例化此类，
 * 所以 authClient 不能通过构造函数注入，改为静态持有。
 * GatewayMain 在 server.start() 之前调用 setAuthClient() 完成注入。
 */
@Service("BayeuxService")
public class BayeuxService {

    private static final Logger LOG = LoggerFactory.getLogger(BayeuxService.class);

    // ✅ 静态持有，GatewayMain 在启动时注入
    private static volatile PlatformAuthClient authClient;

    public static void setAuthClient(PlatformAuthClient client) {
        authClient = client;
    }

    @Inject
    private BayeuxServer bayeuxServer;

    @Session
    private LocalSession sender;

    // ✅ 无参构造，供 AnnotationCometDServlet 反射使用
    public BayeuxService() {}

    @PostConstruct
    public void init() {
        if (authClient == null) {
            throw new IllegalStateException(
                    "BayeuxService: authClient not set. Call BayeuxService.setAuthClient() before server.start()");
        }
        bayeuxServer.setSecurityPolicy(new HandshakeSecurityPolicy());
        bayeuxServer.addListener(new SessionLifecycleListener());
        bayeuxServer.setOption("timeout", GatewayConfig.SESSION_TIMEOUT_SECONDS * 1000L);
        LOG.info("BayeuxService initialized");
    }

    // ── 握手安全策略 ─────────────────────────────────
    private static class HandshakeSecurityPolicy extends DefaultSecurityPolicy {

        @Override
        public boolean canHandshake(BayeuxServer server,
                                    ServerSession session,
                                    ServerMessage message) {
            Map<String, Object> ext = message.getExt();
            String accountToken = ext != null ? (String) ext.get("account_token") : null;
            String ssoCookie    = (String) session.getAttribute("SSO_COOKIE_KEY");

            if (accountToken == null || ssoCookie == null) {
                LOG.warn("Handshake rejected: missing credentials, clientId={}", session.getId());
                return false;
            }

            AuthResult result = authClient.validate(accountToken, ssoCookie);
            if (!result.valid) {
                LOG.warn("Handshake rejected: auth failed, clientId={}", session.getId());
                return false;
            }

            session.setAttribute("domain_id", result.domainId);
            session.setAttribute("username",  result.username);

            // 回复中写入 domain_id，终端用它构造订阅通道名
            Map<String, Object> replyExt = new HashMap<>();
            replyExt.put("user_domain_moid", result.domainId);
            replyExt.put("ack", true);
            message.getAssociated().getExt(true).putAll(replyExt);

            LOG.info("Handshake OK: username={}, domainId={}, clientId={}",
                    result.username, result.domainId, session.getId());
            return true;
        }

        @Override
        public boolean canSubscribe(BayeuxServer server,
                                    ServerSession session,
                                    ServerChannel channel,
                                    ServerMessage message) {
            String domainId  = (String) session.getAttribute("domain_id");
            String channelId = channel.getId();

            if (domainId == null) {
                LOG.warn("Subscribe rejected: no domain_id, clientId={}", session.getId());
                return false;
            }
            if (!channelId.startsWith("/userdomains/" + domainId)) {
                LOG.warn("Subscribe rejected: channel={} not in domain={}", channelId, domainId);
                return false;
            }

            LOG.info("Subscribe OK: channel={}, clientId={}", channelId, session.getId());
            return true;
        }
    }

    // ── 会话生命周期日志 ──────────────────────────────
    private static class SessionLifecycleListener implements BayeuxServer.SessionListener {

        @Override
        public void sessionAdded(ServerSession session, ServerMessage message) {
            LOG.info("Session connected: clientId={}", session.getId());
        }

        @Override
        public void sessionRemoved(ServerSession session, ServerMessage message, boolean timeout) {
            LOG.info("Session disconnected: clientId={}, username={}, timeout={}",
                    session.getId(), session.getAttribute("username"), timeout);
        }
    }
}
package com.jpm.gateway;

import com.jpm.common.util.HttpUtils;
import com.jpm.gateway.auth.PlatformAuthClient;
import com.jpm.gateway.bayeux.BayeuxService;
import com.jpm.gateway.bayeux.CookieExtractFilter;
import com.jpm.gateway.config.GatewayConfig;
import com.jpm.gateway.publisher.EventPublishServlet;
import org.cometd.annotation.server.AnnotationCometDServlet;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.DispatcherType;
import java.util.EnumSet;

public class GatewayMain {

    private static final Logger LOG = LoggerFactory.getLogger(GatewayMain.class);

    public static void main(String[] args) throws Exception {

        // ── 1. HTTP 客户端（调用 C++ 平台用）────────────
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        HttpUtils          httpUtils  = new HttpUtils(httpClient);
        PlatformAuthClient authClient = new PlatformAuthClient(httpUtils);

        // 将 authClient 共享给 BayeuxService（通过静态持有，避免复杂 DI）
        BayeuxService.setAuthClient(authClient);

        // ── 2. Jetty Server ──────────────────────────────
        Server server = new Server(GatewayConfig.GATEWAY_PORT);

        // ── 3. Servlet 上下文 ────────────────────────────
        ServletContextHandler context = new ServletContextHandler(
                ServletContextHandler.SESSIONS
        );
        context.setContextPath("/");
        server.setHandler(context);

        // ✅ 修复核心：embedded 模式下必须用此方法初始化 WebSocket ServerContainer
        //    configure() 会在 context 启动阶段正确注册 javax.websocket.server.ServerContainer
        //    到 ServletContext attribute，CometD 的 WebSocketTransport 依赖这个 attribute
        WebSocketServerContainerInitializer.configure(context, null);

        // ── 4. Cookie 提取 Filter ────────────────────────
        context.addFilter(
                new FilterHolder(new CookieExtractFilter()),
                GatewayConfig.COMETD_PATH + "/*",
                EnumSet.of(DispatcherType.REQUEST)
        );

        // ── 5. CometD Servlet ─────────────────────────────
        AnnotationCometDServlet cometdServlet = new AnnotationCometDServlet();
        ServletHolder cometdHolder = new ServletHolder(cometdServlet);

        cometdHolder.setInitParameter("cometdURLMapping",  GatewayConfig.COMETD_PATH);
        cometdHolder.setInitParameter("timeout",
                String.valueOf(GatewayConfig.SESSION_TIMEOUT_SECONDS * 1000L));
        cometdHolder.setInitParameter("maxInterval",       "15000");
        cometdHolder.setInitParameter("logLevel",          "1");
        // BayeuxService 通过静态 authClient 获取依赖，无需构造参数
        cometdHolder.setInitParameter("services",          BayeuxService.class.getName());

        cometdHolder.setInitOrder(1);  // CometD 最先初始化
        context.addServlet(cometdHolder, GatewayConfig.COMETD_PATH + "/*");

        // ── 6. 事件接收 Servlet ──────────────────────────
        ServletHolder publishHolder = new ServletHolder(new EventPublishServlet());
        publishHolder.setInitOrder(2); // 在 CometD 之后初始化，此时 BayeuxServer 已就绪
        context.addServlet(publishHolder, GatewayConfig.INTERNAL_PUBLISH_PATH);

        // ── 7. 启动 ──────────────────────────────────────
        server.start();

        LOG.info("================================================");
        LOG.info("  CometD Gateway started on port {}", GatewayConfig.GATEWAY_PORT);
        LOG.info("  CometD : http://0.0.0.0:{}{}", GatewayConfig.GATEWAY_PORT, GatewayConfig.COMETD_PATH);
        LOG.info("  Publish: http://0.0.0.0:{}{}", GatewayConfig.GATEWAY_PORT, GatewayConfig.INTERNAL_PUBLISH_PATH);
        LOG.info("  C++ platform: {}", GatewayConfig.CPP_PLATFORM_URL);
        LOG.info("================================================");

        server.join();
    }
}
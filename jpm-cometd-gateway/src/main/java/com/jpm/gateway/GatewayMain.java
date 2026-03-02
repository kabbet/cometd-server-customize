package com.jpm.gateway;

import com.jpm.common.util.HttpUtils;
import com.jpm.gateway.auth.PlatformAuthClient;
import com.jpm.gateway.bayeux.BayeuxService;
import com.jpm.gateway.bayeux.CookieExtractFilter;
import com.jpm.gateway.config.GatewayConfig;
import com.jpm.gateway.publisher.EventPublishServlet;
import org.cometd.annotation.Service;
import org.cometd.annotation.server.AnnotationCometDServlet;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.DispatcherType;
import java.util.EnumSet;

public class GatewayMain {

    private static final Logger LOG = LoggerFactory.getLogger(GatewayMain.class);

    public static void main(String[] args) throws Exception {

        // ── 1. 初始化 HTTP 客户端（调用 C++ 平台用）────
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        HttpUtils         httpUtils  = new HttpUtils(httpClient);
        PlatformAuthClient authClient = new PlatformAuthClient(httpUtils);

        // ── 2. 创建 Jetty Server ──────────────────────
        Server server = new Server(GatewayConfig.GATEWAY_PORT);

        // ── 3. Servlet 上下文 ─────────────────────────
        ServletContextHandler context = new ServletContextHandler(
                ServletContextHandler.SESSIONS
        );
        context.setContextPath("/");
        server.setHandler(context);

        // 启用 WebSocket 支持
        // 使用websocketServlet 注册 websocket支持
        context.addServlet(new ServletHolder(new WebSocketServlet() {
            @Override
            public void configure(WebSocketServletFactory factory) {
                factory.register(Websockethandler.class);
            }
        }), "/websocket");

        // ── 4. Cookie 提取 Filter ─────────────────────
        context.addFilter(
            new FilterHolder(new CookieExtractFilter()),
            GatewayConfig.COMETD_PATH + "/*",
            EnumSet.of(DispatcherType.REQUEST)
        );

        // ── 5. CometD Servlet ─────────────────────────
        AnnotationCometDServlet cometdServlet = new AnnotationCometDServlet();
        ServletHolder cometdHolder = new ServletHolder(cometdServlet);

        cometdHolder.setInitParameter("cometdURLMapping", GatewayConfig.COMETD_PATH);

        cometdHolder.setInitParameter("timeout",
                String.valueOf(GatewayConfig.SESSION_TIMEOUT_SECONDS * 1000L));
        cometdHolder.setInitParameter("maxInterval", "15000");
        cometdHolder.setInitParameter("logLevel", "1");
        cometdHolder.setInitParameter("services",
                BayeuxService.class.getName());

        cometdHolder.setInitOrder(1);
        context.addServlet(cometdHolder, GatewayConfig.COMETD_PATH + "/*");

        // ── 6. 事件接收 Servlet（供 C++ 平台调用）─────
        ServletHolder publishHolder = new ServletHolder(new EventPublishServlet());
        publishHolder.setInitOrder(2);
        context.addServlet(publishHolder, GatewayConfig.INTERNAL_PUBLISH_PATH);

        // ── 7. 启动 ────────────────────────────────────
        server.start();

        LOG.info("================================================");
        LOG.info("  CometD Gateway started on port {}",       GatewayConfig.GATEWAY_PORT);
        LOG.info("  CometD : http://0.0.0.0:{}{}", GatewayConfig.GATEWAY_PORT, GatewayConfig.COMETD_PATH);
        LOG.info("  Publish: http://0.0.0.0:{}{}", GatewayConfig.GATEWAY_PORT, GatewayConfig.INTERNAL_PUBLISH_PATH);
        LOG.info("  C++ platform: {}",              GatewayConfig.CPP_PLATFORM_URL);
        LOG.info("================================================");

        server.join();
    }

    // Websocket handlers
    public static class Websockethandler {
        public void onWebSocketConnect(Session session) {
            LOG.info("Connected to websocket: {}", session.getRemoteAddress());
        }
        public void onWebsocketText(String message) {
            LOG.info("Receive message {}", message);
        }
        public void onWebsocketClose(int statusCode, String reason) {
            LOG.info("WebSocket closed, errorCode = {}, reason = {}", statusCode, reason);
        }
        public void onWebsocketError(Throwable cause) {
            cause.printStackTrace();
        }
    }
}

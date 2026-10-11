package io.github.mustafanazeer.spaceflux.query.live;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.graphql.server.WebGraphQlHandler;
import org.springframework.graphql.server.webmvc.GraphQlWebSocketHandler;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import org.springframework.web.socket.server.HandshakeHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.support.WebSocketHttpRequestHandler;

import tools.jackson.databind.json.JsonMapper;

/**
 * The GraphQL WebSocket endpoint with the controls of ADR 0014: the same JSON writer as HTTP, an exact origin check
 * and the connection caps before the upgrade, an anonymous security context, the read idle and send timeouts, a
 * maximum lifetime and a message budget per connection. Every way a connection ends gives its slot back.
 */
final class AlertSocketHandler extends GraphQlWebSocketHandler {

    /** The Tomcat session properties that close on read idleness alone and bound a blocking send. */
    static final String READ_IDLE_TIMEOUT_MS = "org.apache.tomcat.websocket.READ_IDLE_TIMEOUT_MS";
    static final String BLOCKING_SEND_TIMEOUT = "org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT";

    static final CloseStatus LIFETIME = new CloseStatus(CloseStatus.GOING_AWAY.getCode(), "Connection lifetime.");
    static final CloseStatus TOO_MANY_MESSAGES = new CloseStatus(CloseStatus.POLICY_VIOLATION.getCode(),
            "Too many messages.");
    static final CloseStatus NO_SLOT = new CloseStatus(CloseStatus.SERVICE_OVERLOAD.getCode(), "Try again later.");

    /** Every limit the handler applies, from configuration. */
    record Limits(String allowedOrigin, Duration retryAfter, Duration initTimeout, Duration readIdleTimeout,
            Duration sendTimeout, Duration maxLifetime, int messagesPerWindow, Duration messageWindow) {
    }

    private final Connections connections;
    private final Limits limits;
    private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "websocket-timer");
        t.setDaemon(true);
        return t;
    });

    AlertSocketHandler(WebGraphQlHandler graphQlHandler, Duration keepAlive, Connections connections,
            Limits limits) {
        // A null CORS configuration leaves out Spring's same origin check; the exact check below replaces it.
        super(graphQlHandler, new JacksonJsonHttpMessageConverter(JsonMapper.builder().build()), limits.initTimeout(),
                keepAlive, null);
        this.connections = connections;
        this.limits = limits;
        // A cancelled lifetime task would otherwise stay queued for the whole lifetime, holding its connection.
        timer.setRemoveOnCancelPolicy(true);
    }

    @Override
    public WebSocketHttpRequestHandler initWebSocketHttpRequestHandler(HandshakeHandler handshakeHandler) {
        WebSocketHttpRequestHandler handler = super.initWebSocketHttpRequestHandler(handshakeHandler);
        List<HandshakeInterceptor> interceptors = new ArrayList<>(List.of(new OriginCheck(), new Reservation(),
                new AnonymousContext()));
        interceptors.addAll(handler.getHandshakeInterceptors());
        handler.setHandshakeInterceptors(interceptors);
        return handler;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Connection connection = (Connection) session.getAttributes().get(Connection.KEY);
        if (connection == null || !connection.opened()) {
            close(session, NO_SLOT);
            return;
        }
        if (session instanceof NativeWebSocketSession nativeSession) {
            Map<String, Object> properties = nativeSession.getNativeSession(jakarta.websocket.Session.class)
                    .getUserProperties();
            properties.put(READ_IDLE_TIMEOUT_MS, limits.readIdleTimeout().toMillis());
            properties.put(BLOCKING_SEND_TIMEOUT, limits.sendTimeout().toMillis());
        }
        ScheduledFuture<?> end = timer.schedule(() -> connection.close(LIFETIME), limits.maxLifetime().toMillis(),
                TimeUnit.MILLISECONDS);
        if (!connection.attach(session, new MessageBudget(limits.messagesPerWindow(), limits.messageWindow()), end)) {
            // Closed by the client while it was opening; attach cancelled the lifetime task.
            return;
        }
        super.afterConnectionEstablished(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Connection connection = (Connection) session.getAttributes().get(Connection.KEY);
        if (connection == null) {
            close(session, NO_SLOT);
            return;
        }
        if (!connection.allowMessage(System.nanoTime())) {
            connection.close(TOO_MANY_MESSAGES);
            return;
        }
        super.handleTextMessage(session, message);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) {
        try {
            super.afterConnectionClosed(session, closeStatus);
        } finally {
            Connection connection = (Connection) session.getAttributes().get(Connection.KEY);
            if (connection != null) {
                connection.release();
            }
        }
    }

    /** Tasks waiting on the timer. */
    int scheduledTasks() {
        return timer.getQueue().size();
    }

    /** Stops the lifetime timer when the application closes. */
    void shutdown() {
        timer.shutdownNow();
    }

    private static void close(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException e) {
            // The connection is gone either way.
        }
    }

    /** Refuses through the container's error path, so the refusal has the API's problem body. */
    private static void refuse(ServerHttpResponse response, HttpStatus status, Duration retryAfter) {
        HttpServletResponse servlet = ((ServletServerHttpResponse) response).getServletResponse();
        if (retryAfter != null) {
            servlet.setHeader("Retry-After", Long.toString(retryAfter.toSeconds()));
        }
        try {
            servlet.sendError(status.value());
        } catch (IOException e) {
            // The client went away; there is nobody to answer.
        }
    }

    private static HttpServletRequest servlet(ServerHttpRequest request) {
        return ((ServletServerHttpRequest) request).getServletRequest();
    }

    /** Exactly one Origin header, equal to the one configured origin; with none configured, nothing passes. */
    private final class OriginCheck implements HandshakeInterceptor {

        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                WebSocketHandler wsHandler, Map<String, Object> attributes) {
            List<String> origins = request.getHeaders().getValuesAsList("Origin");
            String allowed = limits.allowedOrigin();
            if (allowed == null || allowed.isEmpty() || origins.size() != 1 || !allowed.equals(origins.get(0))) {
                refuse(response, HttpStatus.FORBIDDEN, null);
                return false;
            }
            return true;
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
                Exception exception) {
        }
    }

    /** Takes a slot before the upgrade, and gives it back when the upgrade did not happen. */
    private final class Reservation implements HandshakeInterceptor {

        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                WebSocketHandler wsHandler, Map<String, Object> attributes) {
            Connections.Reservation r = connections.reserve(servlet(request).getRemoteAddr());
            if (r.connection() == null) {
                refuse(response, r.refusal() == Connections.Refusal.ADDRESS ? HttpStatus.TOO_MANY_REQUESTS
                        : HttpStatus.SERVICE_UNAVAILABLE, limits.retryAfter());
                return false;
            }
            attributes.put(Connection.KEY, r.connection());
            servlet(request).setAttribute(Connection.KEY, r.connection());
            return true;
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
                Exception exception) {
            Connection connection = (Connection) servlet(request).getAttribute(Connection.KEY);
            if (connection == null) {
                return;
            }
            int status = ((ServletServerHttpResponse) response).getServletResponse().getStatus();
            if (exception != null || status != HttpStatus.SWITCHING_PROTOCOLS.value()) {
                connection.releaseIfNotOpened();
            } else {
                // Upgraded: the connection opens next. Should it never open, the slot is given back.
                timer.schedule(connection::releaseIfNotOpened, limits.initTimeout().toMillis(),
                        TimeUnit.MILLISECONDS);
            }
        }
    }

    /**
     * Spring for GraphQL captures the thread's context at the handshake and restores it for every later message
     * (ADR 0014, fact 5). Running that capture with an empty security context serves the whole socket anonymously,
     * whatever session the handshake carried (decision 4).
     */
    private static final class AnonymousContext implements HandshakeInterceptor {

        private static final String HELD = AnonymousContext.class.getName();

        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                WebSocketHandler wsHandler, Map<String, Object> attributes) {
            servlet(request).setAttribute(HELD, SecurityContextHolder.getContext());
            SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
            return true;
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
                Exception exception) {
            Object held = servlet(request).getAttribute(HELD);
            if (held instanceof SecurityContext context) {
                SecurityContextHolder.setContext(context);
            }
        }
    }
}

package io.github.mustafanazeer.spaceflux.query.live;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.graphql.autoconfigure.GraphQlProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.graphql.server.WebGraphQlHandler;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.webmvc.GraphQlSseHandler;
import org.springframework.graphql.server.webmvc.GraphQlWebSocketHandler;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import graphql.execution.instrumentation.Instrumentation;
import io.github.mustafanazeer.spaceflux.query.read.ListedAlerts;

/** The live alert subscription over WebSocket (ADR 0014, docs/api/graphql.md). */
@Configuration(proxyBeanMethods = false)
class LiveConfig {

    @Bean(destroyMethod = "shutdown")
    AlertNotices alertNotices(ListedAlerts listed, @Value("${spaceflux.websocket.subscriber-buffer}") int buffer,
            @Value("${spaceflux.websocket.notice-queue}") int queue) {
        return new AlertNotices(listed::notice, buffer, queue);
    }

    @Bean
    Connections connections(@Value("${spaceflux.websocket.connections-per-address}") int perAddress,
            @Value("${spaceflux.websocket.connections}") int total) {
        return new Connections(perAddress, total);
    }

    /** Replaces the handler Spring Boot would build; Boot still maps it at spring.graphql.websocket.path. */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    GraphQlWebSocketHandler graphQlWebSocketHandler(WebGraphQlHandler handler, GraphQlProperties properties,
            Connections connections,
            @Value("${spaceflux.websocket.allowed-origin:}") String allowedOrigin,
            @Value("${spaceflux.websocket.retry-after}") Duration retryAfter,
            @Value("${spaceflux.websocket.read-idle-timeout}") Duration readIdleTimeout,
            @Value("${spaceflux.websocket.send-timeout}") Duration sendTimeout,
            @Value("${spaceflux.websocket.max-lifetime}") Duration maxLifetime,
            @Value("${spaceflux.websocket.messages-per-window}") int messagesPerWindow,
            @Value("${spaceflux.websocket.message-window}") Duration messageWindow) {
        return new AlertSocketHandler(handler, properties.getWebsocket().getKeepAlive(), connections,
                new AlertSocketHandler.Limits(allowedOrigin, retryAfter,
                        properties.getWebsocket().getConnectionInitTimeout(), readIdleTimeout, sendTimeout,
                        maxLifetime, messagesPerWindow, messageWindow));
    }

    /** The container's text message limit, counted in characters; a larger message closes with 1009. */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    ServletServerContainerFactoryBean webSocketContainer(
            @Value("${spaceflux.websocket.max-text-message-chars}") int maxText) {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(maxText);
        return container;
    }

    /**
     * Spring Boot routes a POST to the GraphQL path that accepts text/event-stream to this handler. Refusing every such
     * request keeps any long lived HTTP stream from existing outside the WebSocket's caps.
     */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    GraphQlSseHandler graphQlSseHandler(WebGraphQlHandler handler) {
        return new GraphQlSseHandler(handler) {
            @Override
            public ServerResponse handleRequest(ServerRequest request) {
                return ServerResponse.status(HttpStatus.NOT_ACCEPTABLE).build((servletRequest, response) -> {
                    response.sendError(HttpStatus.NOT_ACCEPTABLE.value());
                    return null;
                });
            }
        };
    }

    @Bean
    @Order(0)
    WebGraphQlInterceptor transportMarker() {
        return Transports.marker();
    }

    @Bean
    @Order(0)
    Instrumentation transportRule() {
        return Transports.rule();
    }
}

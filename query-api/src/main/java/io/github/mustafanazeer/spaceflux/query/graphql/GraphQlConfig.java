package io.github.mustafanazeer.spaceflux.query.graphql;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.server.WebGraphQlHandler;
import org.springframework.graphql.server.webmvc.GraphQlHttpHandler;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;

import tools.jackson.databind.json.JsonMapper;

/** The GraphQL endpoint's own settings (docs/api/graphql.md, ADR 0012). */
@Configuration(proxyBeanMethods = false)
class GraphQlConfig {

    /**
     * GraphQL answers through its own JSON writer. The REST writer leaves out absent fields, but a GraphQL response
     * must return a nullable field that was asked for as null.
     */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    GraphQlHttpHandler graphQlHttpHandler(WebGraphQlHandler handler) {
        return new GraphQlHttpHandler(handler, new JacksonJsonHttpMessageConverter(JsonMapper.builder().build()));
    }
}

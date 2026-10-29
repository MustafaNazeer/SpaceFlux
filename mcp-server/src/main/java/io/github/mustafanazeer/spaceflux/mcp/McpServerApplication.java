package io.github.mustafanazeer.spaceflux.mcp;

import java.nio.file.Path;
import java.time.Clock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

@SpringBootApplication
public class McpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Ahead of every other filter, on every path. */
    @Bean
    FilterRegistrationBean<BearerTokenFilter> bearerToken(@Value("${spaceflux.mcp.token-file}") Path file) {
        FilterRegistrationBean<BearerTokenFilter> registration = new FilterRegistrationBean<>(
                new BearerTokenFilter(file));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }
}

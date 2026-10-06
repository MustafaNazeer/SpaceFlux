package io.github.mustafanazeer.spaceflux.query.auth;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/** With no operator configured, every acknowledgement is forbidden before any other check (ADR 0009, decision 2). */
final class NoOperatorFilter extends OncePerRequestFilter {

    private final boolean configured;

    NoOperatorFilter(OperatorAccount operator) {
        this.configured = operator.user().isPresent();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!configured && SecurityConfig.ACKNOWLEDGEMENT_PATH.matches(request)
                && !"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod())) {
            response.sendError(HttpStatus.FORBIDDEN.value());
            return;
        }
        chain.doFilter(request, response);
    }
}

package io.github.mustafanazeer.spaceflux.query.web;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a random correlation ID, returned in the X-Correlation-Id header and in any problem body, and
 * written with the log line of an error. An incoming header of that name is ignored, so a client cannot choose a
 * value that appears in the log (ADR 0010, amendment).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    static final String ATTRIBUTE = CorrelationIdFilter.class.getName();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, id);
        response.setHeader(HEADER, id);
        chain.doFilter(request, response);
    }

    /** The ID of the request, or null outside one. */
    public static String of(HttpServletRequest request) {
        return (String) request.getAttribute(ATTRIBUTE);
    }

    /**
     * The request's ID, giving it one first when the container sent it to the error path before this filter ran, as it
     * does for a method it refuses itself.
     */
    public static String ensure(HttpServletRequest request, HttpServletResponse response) {
        String id = of(request);
        if (id == null) {
            id = UUID.randomUUID().toString();
            request.setAttribute(ATTRIBUTE, id);
        }
        response.setHeader(HEADER, id);
        return id;
    }
}

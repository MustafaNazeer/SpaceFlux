package io.github.mustafanazeer.spaceflux.query.auth;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Answers a login 429 with Retry-After while its address or the whole service is backing off, or while two password
 * checks are already running, so a refused attempt costs no password check. It runs after the CSRF check, so a login
 * refused for its token is never counted.
 */
final class LoginThrottleFilter extends OncePerRequestFilter {

    private final LoginThrottle throttle;

    LoginThrottleFilter(LoginThrottle throttle) {
        this.throttle = throttle;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !SecurityConfig.LOGIN_REQUEST.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Credentials belong in the body; in a query string they would reach access logs.
        if (request.getQueryString() != null) {
            response.sendError(HttpStatus.BAD_REQUEST.value());
            return;
        }
        Optional<Duration> wait = throttle.blocked(request.getRemoteAddr());
        if (wait.isPresent()) {
            refuse(response, throttle.retryAfterSeconds(wait.get()));
            return;
        }
        Optional<LoginThrottle.Slot> slot = throttle.check();
        if (slot.isEmpty()) {
            refuse(response, 1);
            return;
        }
        try (LoginThrottle.Slot running = slot.get()) {
            chain.doFilter(request, response);
        }
    }

    private static void refuse(HttpServletResponse response, long seconds) throws IOException {
        response.setHeader("Retry-After", Long.toString(seconds));
        response.sendError(HttpStatus.TOO_MANY_REQUESTS.value());
    }
}

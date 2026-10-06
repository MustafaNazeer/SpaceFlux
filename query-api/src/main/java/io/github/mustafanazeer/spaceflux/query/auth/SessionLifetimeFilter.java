package io.github.mustafanazeer.spaceflux.query.auth;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Ends a session 8 hours after its login, which neither the container nor Spring Security does (ADR 0009, decision
 * 1). The request then goes on as one without a session, as a session a newer login replaced does.
 */
final class SessionLifetimeFilter extends OncePerRequestFilter {

    static final String SIGNED_IN_AT = SessionLifetimeFilter.class.getName() + ".SIGNED_IN_AT";
    static final Duration LIFETIME = Duration.ofHours(8);

    private final Clock clock;

    SessionLifetimeFilter(Clock clock) {
        this.clock = clock;
    }

    /** Called on a successful login, after the session ID has changed. */
    static void recordLogin(HttpServletRequest request, Clock clock) {
        request.getSession().setAttribute(SIGNED_IN_AT, clock.instant());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null) {
            // Only a login creates a session, so one without a login time is ended too.
            Object signedInAt = session.getAttribute(SIGNED_IN_AT);
            if (!(signedInAt instanceof Instant at) || !clock.instant().isBefore(at.plus(LIFETIME))) {
                session.invalidate();
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}

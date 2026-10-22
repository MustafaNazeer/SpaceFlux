package io.github.mustafanazeer.spaceflux.query.auth;

import java.security.Principal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.mustafanazeer.spaceflux.query.read.ApiTimes;
import io.github.mustafanazeer.spaceflux.query.web.ApiErrors;

/** {@code GET /api/auth/session} (docs/api/rest.md, section 7). */
@RestController
class SessionController {

    /** {@code expiresAt} is the 8 hour limit only; the idle limit moves with every request, so it is not given. */
    record Session(String username, String expiresAt) {
    }

    /** Reading the token makes the repository write the XSRF-TOKEN cookie when the request has none. */
    @GetMapping("/api/auth/session")
    Session session(Principal principal, CsrfToken csrf, HttpServletRequest request) {
        csrf.getToken();
        HttpSession session = request.getSession(false);
        // SessionLifetimeFilter ends a session with no login time, so a missing end means no one is signed in.
        Instant end = session == null ? null : SessionLifetimeFilter.endOf(session);
        if (principal == null || end == null) {
            throw new ApiErrors.Refused(HttpStatus.UNAUTHORIZED, "No operator is signed in.");
        }
        return new Session(principal.getName(), ApiTimes.format(LocalDateTime.ofInstant(end, ZoneOffset.UTC)));
    }
}

package io.github.mustafanazeer.spaceflux.query.auth;

import java.security.Principal;

import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.mustafanazeer.spaceflux.query.web.ApiErrors;

/** {@code GET /api/auth/session} (docs/api/rest.md, section 7). */
@RestController
class SessionController {

    record Session(String username) {
    }

    /** Reading the token makes the repository write the XSRF-TOKEN cookie when the request has none. */
    @GetMapping("/api/auth/session")
    Session session(Principal principal, CsrfToken csrf) {
        csrf.getToken();
        if (principal == null) {
            throw new ApiErrors.Refused(HttpStatus.UNAUTHORIZED, "No operator is signed in.");
        }
        return new Session(principal.getName());
    }
}

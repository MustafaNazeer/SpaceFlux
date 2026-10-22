package io.github.mustafanazeer.spaceflux.query.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.Principal;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.csrf.DefaultCsrfToken;

import io.github.mustafanazeer.spaceflux.query.web.ApiErrors;

class SessionControllerTest {

    static final Principal OPERATOR = () -> "operator";
    static final DefaultCsrfToken CSRF = new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "token");

    static MockHttpServletRequest signedInAt(Object login) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/session");
        MockHttpSession session = new MockHttpSession();
        if (login != null) {
            session.setAttribute(SessionLifetimeFilter.SIGNED_IN_AT, login);
        }
        request.setSession(session);
        return request;
    }

    static void assertNotSignedIn(Principal principal, MockHttpServletRequest request) {
        assertThatThrownBy(() -> new SessionController().session(principal, CSRF, request))
                .isInstanceOfSatisfying(ApiErrors.Refused.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(e.getMessage()).isEqualTo("No operator is signed in.");
                });
    }

    @Test
    void theSessionEndsEightHoursAfterTheLoginWrittenToTheMicrosecond() {
        SessionController.Session s = new SessionController()
                .session(OPERATOR, CSRF, signedInAt(Instant.parse("2026-10-05T23:30:00.123456789Z")));

        assertThat(s).isEqualTo(new SessionController.Session("operator", "2026-10-06T07:30:00.123456Z"));
    }

    @Test
    void aWholeSecondLoginStillGetsSixFractionDigits() {
        assertThat(new SessionController().session(OPERATOR, CSRF, signedInAt(Instant.parse("2026-10-05T12:00:00Z")))
                .expiresAt()).isEqualTo("2026-10-05T20:00:00.000000Z");
    }

    @Test
    void noPrincipalIsNotSignedIn() {
        assertNotSignedIn(null, signedInAt(Instant.parse("2026-10-05T12:00:00Z")));
    }

    /** The lifetime filter ends such a session before this runs; the endpoint agrees with it rather than guess. */
    @Test
    void aSessionWithNoRecordedLoginTimeIsNotSignedIn() {
        assertNotSignedIn(OPERATOR, signedInAt(null));
        assertNotSignedIn(OPERATOR, new MockHttpServletRequest("GET", "/api/auth/session"));
    }
}

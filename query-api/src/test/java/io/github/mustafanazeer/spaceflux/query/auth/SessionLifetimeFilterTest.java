package io.github.mustafanazeer.spaceflux.query.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

class SessionLifetimeFilterTest {

    static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    /** Runs the filter on a request whose session was signed in at {@code login}; null records no login time. */
    static MockHttpServletRequest run(Instant login) throws Exception {
        MockHttpSession session = new MockHttpSession();
        if (login != null) {
            session.setAttribute(SessionLifetimeFilter.SIGNED_IN_AT, login);
        }
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/session");
        request.setSession(session);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "operator", null, AuthorityUtils.createAuthorityList("OPERATOR")));
        MockFilterChain chain = new MockFilterChain();
        new SessionLifetimeFilter(CLOCK).doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).as("the request always goes on down the chain").isNotNull();
        return request;
    }

    static boolean ended(MockHttpServletRequest request) {
        return ((MockHttpSession) request.getSession(false) == null
                || ((MockHttpSession) request.getSession(false)).isInvalid())
                && SecurityContextHolder.getContext().getAuthentication() == null;
    }

    @Test
    void aSessionYoungerThanEightHoursGoesOn() throws Exception {
        assertThat(ended(run(NOW.minus(Duration.ofHours(8)).plusSeconds(1)))).isFalse();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    void aSessionEightHoursOldOrOlderEndsAndTheRequestGoesOnWithoutIt() throws Exception {
        assertThat(ended(run(NOW.minus(Duration.ofHours(8))))).isTrue();
        assertThat(ended(run(NOW.minus(Duration.ofDays(3))))).isTrue();
    }

    @Test
    void aSessionWithNoRecordedLoginTimeEnds() throws Exception {
        assertThat(ended(run(null))).isTrue();
    }

    @Test
    void aRequestWithoutASessionGoesOnUntouched() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/watchlist");
        MockFilterChain chain = new MockFilterChain();

        new SessionLifetimeFilter(CLOCK).doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(request.getSession(false)).isNull();
    }

    @Test
    void theLoginTimeIsRecordedFromTheClock() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession());

        SessionLifetimeFilter.recordLogin(request, CLOCK);

        assertThat(request.getSession(false).getAttribute(SessionLifetimeFilter.SIGNED_IN_AT)).isEqualTo(NOW);
    }
}

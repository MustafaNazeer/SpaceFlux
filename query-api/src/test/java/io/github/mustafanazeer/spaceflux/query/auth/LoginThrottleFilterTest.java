package io.github.mustafanazeer.spaceflux.query.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LoginThrottleFilterTest {

    @Test
    void aThirdConcurrentLoginIsRefusedAtOnceWithoutCheckingItsPassword() throws Exception {
        LoginThrottle throttle = new LoginThrottle(Clock.systemUTC());
        var first = throttle.check().orElseThrow();
        var second = throttle.check().orElseThrow();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new LoginThrottleFilter(throttle).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
        assertThat(chain.getRequest()).isNull();
        first.close();
        second.close();
    }
}

package io.github.mustafanazeer.spaceflux.query.auth;

import java.time.Clock;

import jakarta.servlet.DispatcherType;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.HeaderWriterLogoutHandler;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.header.writers.ClearSiteDataHeaderWriter;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.session.ConcurrentSessionFilter;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * The API's one filter chain (ADR 0009). Reads stay open to anonymous viewers. Every refusal goes through the
 * container's error path, so it gets the same problem body and correlation ID as any other error.
 */
@Configuration
public class SecurityConfig {

    static final String LOGIN = "/api/auth/login";
    static final String LOGOUT = "/api/auth/logout";
    public static final String ACKNOWLEDGEMENTS = "/api/alerts/acknowledgements";
    /**
     * Matched as form login and authorization match, on the decoded path: comparing getRequestURI() instead would
     * let a percent encoded path such as /api/auth/%6Cogin skip a filter that the login itself still reaches.
     */
    static final RequestMatcher LOGIN_REQUEST =
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, LOGIN);
    static final RequestMatcher ACKNOWLEDGEMENT_PATH =
            PathPatternRequestMatcher.withDefaults().matcher(ACKNOWLEDGEMENTS);

    /**
     * Default deny: reads are open, the three POST endpoints of docs/api/rest.md are the only unsafe requests let
     * through, and the error path keeps whatever status the request already had.
     */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    SecurityFilterChain api(HttpSecurity http, OperatorAccount operator, Clock clock, LoginThrottle throttle) {
        http.authorizeHttpRequests(a -> a
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST, ACKNOWLEDGEMENTS).hasAuthority(Operator.AUTHORITY)
                        .requestMatchers(HttpMethod.POST, LOGIN, LOGOUT).permitAll()
                        .requestMatchers(HttpMethod.GET).permitAll()
                        .requestMatchers(HttpMethod.HEAD).permitAll()
                        .anyRequest().denyAll())
                // A filter, not an authorization rule, since a rule would answer an anonymous caller 401.
                .addFilterBefore(new NoOperatorFilter(operator), CsrfFilter.class)
                .addFilterAfter(new SessionLifetimeFilter(clock), SecurityContextHolderFilter.class)
                .addFilterBefore(new LoginThrottleFilter(throttle), UsernamePasswordAuthenticationFilter.class)
                .csrf(c -> c.spa().csrfTokenRepository(xsrfCookie()))
                // The login page is the dashboard's form; naming the processing URL as the page keeps Spring
                // Security from generating a page of its own.
                .formLogin(f -> f.loginPage(LOGIN).loginProcessingUrl(LOGIN)
                        .successHandler((request, response, authentication) -> {
                            throttle.success(request.getRemoteAddr());
                            SessionLifetimeFilter.recordLogin(request, clock);
                            response.setStatus(HttpStatus.NO_CONTENT.value());
                        })
                        .failureHandler((request, response, e) -> {
                            throttle.failure(request.getRemoteAddr());
                            response.sendError(HttpStatus.UNAUTHORIZED.value());
                        }))
                .logout(l -> l.logoutUrl(LOGOUT)
                        .addLogoutHandler(new HeaderWriterLogoutHandler(
                                new ClearSiteDataHeaderWriter(ClearSiteDataHeaderWriter.Directive.COOKIES)))
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((request, response, ex) ->
                                response.sendError(HttpStatus.UNAUTHORIZED.value()))
                        .accessDeniedHandler((request, response, ex) ->
                                response.sendError(HttpStatus.FORBIDDEN.value())))
                .requestCache(r -> r.requestCache(new NullRequestCache()))
                // A session a newer login replaced has already been logged out when this runs; going on down the
                // chain answers the request as one without a session, instead of the default plain text 200.
                .sessionManagement(s -> s.withObjectPostProcessor(onlyEndTheSession())
                        .maximumSessions(1).expiredSessionStrategy(expired -> expired
                                .getFilterChain().doFilter(expired.getRequest(), expired.getResponse())));
        return http.build();
    }

    /**
     * By default a replaced session runs every logout handler, which would also expire the XSRF-TOKEN cookie and send
     * Clear-Site-Data; ending the session alone answers it exactly as a request without one.
     */
    private static ObjectPostProcessor<ConcurrentSessionFilter> onlyEndTheSession() {
        return new ObjectPostProcessor<>() {
            @Override
            public <O extends ConcurrentSessionFilter> O postProcess(O filter) {
                filter.setLogoutHandlers(new LogoutHandler[] {new SecurityContextLogoutHandler()});
                return filter;
            }
        };
    }

    /** The repository csrf.spa() uses, with the cookie made Secure and SameSite=Strict like the session cookie. */
    private static CookieCsrfTokenRepository xsrfCookie() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> cookie.secure(true).sameSite("Strict"));
        return repository;
    }

    /** Tells the session registry when a session ends, so the one session limit counts only live sessions. */
    @Bean
    HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    @Bean
    LoginThrottle loginThrottle(Clock clock) {
        return new LoginThrottle(clock);
    }

    @Bean
    OperatorAccount operatorAccount(@Value("${spaceflux.operator.username:}") String username,
            @Value("${spaceflux.operator.password-hash:}") String passwordHash,
            @Value("${spaceflux.operator.bcrypt-cost}") int cost) {
        return new OperatorAccount(Operator.from(username, passwordHash, cost));
    }

    /** The one operator, or nobody (ADR 0009, decision 2). Defining it keeps Spring Boot from making a default user. */
    @Bean
    InMemoryUserDetailsManager operator(OperatorAccount account) {
        return account.user().map(InMemoryUserDetailsManager::new).orElseGet(InMemoryUserDetailsManager::new);
    }

    @Bean
    PasswordEncoder passwordEncoder(@Value("${spaceflux.operator.bcrypt-cost}") int cost) {
        return Operator.encoder(cost);
    }
}

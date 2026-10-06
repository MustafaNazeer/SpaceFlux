# ADR 0009: Authentication for alert acknowledgement

* **Status:** accepted
* **Date:** 2026-10-02

## Context

`query-api` is a Spring Boot 4.1.1 service on Java 21 that owns MySQL and serves the dashboard and, later, the read endpoints the assistant's tools call. Everything it serves is read only except one path: acknowledging an alert, which the [threat model](../security/threat-model.md) lists as asset A10, the only user writable data in the system. Section 8 of the threat model left open how that path is authenticated (session cookie or bearer token, where the credential comes from, and what a demo viewer sees). This ADR settles it before the endpoint is built (`SEC-ACK-01`).

Facts that shape the choice:

* **One operator, many anonymous viewers.** I am the only person who acknowledges alerts. Anyone who opens the dashboard while the cloud environment is up is an anonymous viewer and must never be able to write.
* **Two short lived environments.** Locally the stack runs under Docker Compose with secrets from a git ignored `.env`. In the cloud it runs on demand on EKS with secrets from AWS Secrets Manager, and is destroyed after each demo. There is no identity provider, and adding one only for a single account is not justified.
* **The dashboard renders untrusted text.** Feed text and, later, assistant answers reach the page (T1.2, T7.3). If any of it ever escapes sanitizing, script in the page can read whatever the page itself can read. A credential that page script cannot read survives that failure; one held in script does not.
* **A tool identity is coming.** The tool server will call `query-api` and must be refused by the acknowledgement endpoint (T8.7, `SEC-ACK-07`).

The requirements every option was checked against:

| ID | Requirement |
|---|---|
| T6.1 | Authentication required; unauthenticated requests rejected before any database access |
| T6.2 | Cookie sessions need CSRF protection and `SameSite`; a bearer token is never stored where other origins can read it |
| T6.3 | Login rate limiting and lockout or backoff; passwords hashed with a slow hash |
| T6.4 | Each acknowledgement stores principal, timestamp, and alert ID; rows append only |
| T6.5 | Allowlisted body (alert ID and note); the server sets principal and timestamp |
| T6.6 | Credentials created per environment from the secret store; never in the repo or the recorded walkthrough |
| T5.4 | Explicit CORS allowlist for the dashboard origin only; no wildcard with credentials |

### What I verified about the framework

Spring Boot 4.1.1 manages Spring Security 7.1.1, Spring Session 4.1.1, and Spring Framework 7.0.9 (read from the `spring-boot-dependencies` 4.1.1 POM). Every class and method named below was checked in the 7.1.1 jars with `javap`, and every behavior against the 7.1.1 reference documentation linked beside it.

1. **Boot's default user must be switched off.** With Spring Security on the classpath and no user configured, Boot creates an in memory user named `user` with a random password and prints it in the startup log ("Using generated security password: ..."). It backs off when the application defines a `UserDetailsService`, `AuthenticationProvider`, or `AuthenticationManager` bean, or when `spring-security-oauth2-resource-server` is on the classpath ([Boot reference, Spring Security](https://docs.spring.io/spring-boot/reference/web/spring-security.html)). A password in a log line conflicts with T9.3, so the service defines its own authentication beans and a test asserts the generated password line never appears (threat T6.7).
2. **CSRF.** Protection is on by default for unsafe methods such as `POST`, with an `HttpSessionCsrfTokenRepository` and the `XorCsrfTokenRequestAttributeHandler`. For a single page application the reference recommends `csrf((csrf) -> csrf.spa())`, which uses `CookieCsrfTokenRepository` with a cookie named `XSRF-TOKEN` that page script can read, a header named `X-XSRF-TOKEN`, and token refresh after login and logout ([servlet CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)). Angular's `HttpClient` reads that same cookie name and sends that same header on mutating requests to relative and same origin URLs only ([Angular security guide](https://angular.dev/best-practices/security)). The reference also states that applications using HTTP Basic are vulnerable to CSRF because the browser attaches the credentials automatically, and recommends `SameSite` as defense in depth rather than as the only protection ([CSRF concepts](https://docs.spring.io/spring-security/reference/features/exploits/csrf.html)).
3. **Passwords.** `PasswordEncoderFactories.createDelegatingPasswordEncoder()` stores `{id}encodedPassword` and uses `bcrypt` as its default id. The reference recommends tuning an adaptive function to about one second per verification ([password storage](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html)). `BCryptPasswordEncoder(int)` takes the strength. `Argon2PasswordEncoder` calls Bouncy Castle's `Argon2BytesGenerator` (seen in its bytecode), so it needs a dependency the service does not have.
4. **No login throttling is built in.** The 7.1.1 jars contain `LockedException` and `AuthenticationFailureLockedEvent`, which report an account the application has already marked locked, but no class that counts failures, throttles, or locks.
5. **Sessions.** On a Servlet 3.1 or newer container the default session fixation strategy is `changeSessionId`; `maximumSessions(1)` limits concurrent sessions; logout can send `Clear-Site-Data` through `HeaderWriterLogoutHandler` and `ClearSiteDataHeaderWriter`; `SessionCreationPolicy.STATELESS` creates no session ([session management](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html)). In Boot 4.1.1, `server.servlet.session.timeout` defaults to `30m`, and `server.servlet.session.cookie.secure`, `.http-only`, `.same-site`, and `.name` have no Boot default, so they are left to the container unless set (configuration metadata in `spring-boot-web-server` 4.1.1; `same-site` takes `none`, `lax`, `strict`, or `omitted`). The idle timeout is the only lifetime the container and Spring Security provide: no class in the Spring Security 7.1.1 or Spring Session 4.1.1 jars sets an absolute session lifetime, so that check is written in the service. Sessions live in the Tomcat process; Spring Session JDBC (`spring-boot-starter-session-jdbc`, table `SPRING_SESSION`) moves them into the database if `query-api` ever runs more than one replica.
6. **Bearer tokens.** `oauth2ResourceServer((o) -> o.jwt(...))` validates JWTs through `BearerTokenAuthenticationFilter`; `NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256)` validates an HMAC signed token and `NimbusJwtEncoder.withSecretKey(key)` can issue one; the `scope` claim maps to `SCOPE_` authorities ([resource server JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)). The resource server validates tokens; it does not issue them or check a password. For an opaque token, `AuthenticationFilter(AuthenticationManager, AuthenticationConverter)` is the generic hook.
7. **HTTP Basic.** `httpBasic(withDefaults())` adds `BasicAuthenticationFilter`; its entry point sends `WWW-Authenticate`, and suppresses it when the request carries `X-Requested-With: XMLHttpRequest` ([Basic](https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/basic.html)).
8. **CORS.** `http.cors(...)` uses an explicit `CorsConfigurationSource`, else a `UrlBasedCorsConfigurationSource` bean, else Spring MVC's CORS configuration; CORS must run before authentication because a preflight carries no cookies ([CORS](https://docs.spring.io/spring-security/reference/servlet/integrations/cors.html)). If none is configured, no `Access-Control-Allow-*` header is sent and a browser blocks cross origin reads.
9. **Spring Security 7 DSL.** `HttpSecurity` in 7.1.1 has `authorizeHttpRequests` and no `authorizeRequests`; configuration is written in the lambda style throughout.

## Decision

### 1. Server side session cookie with form login

The operator signs in from a small form in the dashboard, which posts the username and password as form parameters (the shape `UsernamePasswordAuthenticationFilter` reads). Spring Security checks the password against a bcrypt hash, creates a server side session, and sets the session cookie.

* **Login and logout.** `formLogin` with a `loginProcessingUrl` under `/api/auth/login`, an `AuthenticationSuccessHandler` and an `AuthenticationFailureHandler` that answer with status codes instead of redirects, `HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)` for unauthenticated API calls, and `logout` with `HttpStatusReturningLogoutSuccessHandler` and `Clear-Site-Data`.
* **CSRF.** `csrf((c) -> c.spa())`, read by Angular's `HttpClient` from the `XSRF-TOKEN` cookie.
* **Cookie.** `server.servlet.session.cookie.secure=true`, `http-only=true`, `same-site=strict`, and a fixed name.
* **Session.** At most one session (`maximumSessions(1)`), a new session ID at login (the `changeSessionId` default), a 30 minute idle timeout (`server.servlet.session.timeout=30m`, which is also Boot's default, set explicitly so it is visible), and an 8 hour absolute lifetime. Since nothing in the framework provides the absolute lifetime, the service records the instant of a successful login in the session and a filter invalidates any session older than 8 hours from that instant, answering as if unauthenticated.
* **No session for viewers.** The request cache is `NullRequestCache`, so a refused anonymous request never creates a session, and anonymous viewers browse without one. The CSRF cookie set for viewers is not a session: it is a random token held only in the browser.

### 2. The credential

One operator account. The secret is a bcrypt hash of one password, generated offline per environment and stored as `ACK_OPERATOR_USERNAME` and `ACK_OPERATOR_PASSWORD_HASH`: in `.env` locally and in AWS Secrets Manager on EKS. The plain password lives only in my password manager and never in an environment variable, a file in the repository, or the recorded walkthrough. At startup the service builds an `InMemoryUserDetailsManager` with that one user behind a `DaoAuthenticationProvider` that uses the delegating encoder (superseded by the 2026-10-05 amendment: bcrypt only, at the measured strength). The bcrypt strength is set with the login code from a measurement on the target, following the reference's guidance of about one second per verification.

**Fail closed.** If either value is missing, the service starts, every read works, and the acknowledgement endpoint answers `403` for everyone. Nothing falls back to Boot's default user or to any generated credential. A value that is not `{bcrypt}` followed by a `$2a$`, `$2b$` or `$2y$` hash at the configured strength counts as missing; the service then logs a warning that names the variable, never its value.

### 3. What can be acknowledged, and how it is stored

* **Kinds.** Only `alerts` events of kind `close_approach` and `space_weather_level` ([ADR 0007](0007-alerts-topic.md)) can be acknowledged, and of the space weather events only those whose state is `level`, as the [MySQL schema](../data/mysql-schema.md#alert_acknowledgement) sets out. A `screening_run` summary, a refresh, a "no data" event, and an unknown alert ID are refused, with a test for each. This is a rule of the API, not of the schema.
* **Request.** `POST /api/alerts/{alertId}/acknowledgements` with a body whose only field is an optional `note`. The note limit is 500 Unicode code points, the same count MySQL applies to the `VARCHAR(500)` `note` column, so it is counted with `String.codePointCount` and not with `String.length()`, which counts UTF-16 units; a longer note is refused with `400`, never cut. Unknown fields, including the stored columns `principal` and `acted_at`, are refused with `400` by an explicit setting on the request type, not by relying on the JSON library's default, with a test. `principal` comes from the security context and `acted_at` is set by the database at insert (T6.5). (Superseded by the 2026-10-05 amendment: query parameter `event_id`, body `action` and `note`.)
* **Rows.** One append only table, `alert_acknowledgement`: `event_id` (the alert), `action` (`acknowledge` or `unacknowledge`, held to those two by a check constraint), `principal`, `acted_at`, and `note`. Unacknowledging writes a new row with `action` `unacknowledge`; no row is ever updated or deleted. The current state of an alert is its newest row (T6.4). The table is defined in the [MySQL schema](../data/mysql-schema.md#alert_acknowledgement).

### 4. Database users

Append only is enforced with MySQL grants, not triggers. There are three separate users, as listed in the [MySQL schema's database users](../data/mysql-schema.md#database-users): the migration user (DDL, used by Flyway at deploy time only), the consumer user (the Kafka consumers, with no privilege on `alert_acknowledgement`), and the API user. The API user keeps `SELECT` on the tables it serves, and its only write privilege anywhere is `INSERT` on `alert_acknowledgement`, limited to the columns `event_id`, `action`, `principal`, and `note`, so it cannot set `acted_at`. It has no `UPDATE` or `DELETE` on any table. A leaked API credential therefore cannot rewrite or erase an acknowledgement, and cannot change alerts or the catalog.

### 5. What a viewer sees

Every surface that returns alerts follows one rule, REST today and any later one too:

* **Anonymous viewers** see only the newest row's `action` and `acted_at`, read only: no note, no principal, and no display name in its place. The acknowledge control is not shown.
* **The signed in operator** additionally sees the note and the principal, and gets the acknowledge and unacknowledge controls.

The response for an anonymous request leaves `note` and `principal` out entirely rather than sending them for the page to hide, with a test. The note is rendered as text only.

### 6. Authorization and order of checks

Only the operator's authority reaches the endpoint. Every read endpoint stays open to anonymous viewers, so the tool server needs no credential for reads, and a request with no credential is refused by the acknowledgement endpoint (T8.7, with a test). The security filter chain refuses an unauthenticated request before any controller, repository, or database call, and a test asserts that such a request causes no query (T6.1).

### 7. Same origin

The dashboard and the API share one origin in every environment: behind one ingress in the cloud, and through a development proxy locally. No CORS configuration exists, so T5.4 holds by construction. If a cross origin setup is ever needed, its allowlist names exactly one origin per environment and is reviewed against T5.4 first.

### 8. Login throttling

Spring Security has no throttling of its own (fact 4), and T6.3 needs one.

**Mechanism: custom code in `query-api`, no new dependency.** A filter in front of `/api/auth/login` and the form login's failure and success handlers share in memory counters. This fits a service that runs as one replica in a short lived environment, and avoids bringing in and reviewing a rate limiting library (T11.3) for one endpoint. A library such as Bucket4j stays the alternative if `query-api` ever runs several replicas, since in memory counters are per process; it would get its own dependency review then.

**Limits:**

| Rule | Value |
|---|---|
| Free failures per client address | 3 consecutive failures |
| Backoff per client address after that | blocked for 2 seconds after the 4th failure, doubling with each further failure, capped at 15 minutes |
| Reset per client address | a successful login, or 1 hour with no failure |
| Limit across all addresses | 30 failures in any 10 minute window; above it, every login attempt is refused until the window has fewer |
| While blocked | answer `429` with `Retry-After`, without running the bcrypt check, so a blocked client costs no CPU |
| Concurrent bcrypt checks | at most 2 at a time; a third concurrent attempt gets `429` with `Retry-After: 1` at once, and no request thread waits for a slot |
| Counter memory | at most 10,000 tracked addresses, oldest evicted first |

This is backoff, never lockout: nothing an attacker does locks the account. Failed attempts from many addresses can still hold the global window full, and while they keep it full I cannot sign in either; that is the price of bounding guesses across addresses, accepted for a demo with one operator. With a check tuned to about one second and these limits, about 30 guesses per 10 minutes reach the hash (a check already running when the window fills can add one or two).

**Client address.** Locally it is the socket address. In the cloud it has to come from the ingress's forwarded header, which is trusted only from the ingress: `server.forward-headers-strategy` (values `native`, `framework`, `none`) is set accordingly in the cloud profile and left unset locally. How the ingress sets the header is decided with the cloud environment.

### Amendment, 2026-10-05: request shape, repeated actions, and the session endpoint

Settled before the endpoint was built; the [REST API](../api/rest.md) page, sections 7 to 9, holds the full contract.

* **The alert is named by a query parameter**, `POST /api/alerts/acknowledgements?event_id=...`, not by a path segment. An `event_id` holds `/` characters, which Tomcat refuses in a path when encoded as `%2F`, and allowing them would relax a container default for one endpoint. The read endpoint for one alert already takes `event_id` the same way.
* **One endpoint for both actions.** The body is `{"action": "acknowledge" | "unacknowledge", "note": ...}`: `action` is required, `note` stays optional with the 500 code point limit. These two are the whole allowlist; every other field, `principal` and `acted_at` included, is refused with `400` as decision 3 says. One write path mirrors the one row it appends.
* **A repeated action is refused.** When the requested action equals the alert's current state (acknowledging an acknowledged alert, or unacknowledging one that has no row or whose newest row is `unacknowledge`), the answer is `409` and no row is written, so the history records only changes of state. The check and the insert run under one lock held by the service, which holds while `query-api` is one replica (Consequences). The lock is taken only after authentication, CSRF, validation, and the `404` and kind checks, and the state is read inside it.
* **`GET /api/auth/session`** returns the signed in username, or `401` when there is none, and creates no session. It also gives the dashboard the `XSRF-TOKEN` cookie before its first unsafe request, the login form included.
* **A second login replaces the first session.** `maximumSessions(1)` keeps its default of expiring the older session, so a forgotten tab can never lock me out; the alternative, refusing the new login, would leave a lost session blocking sign in until it times out. A replaced or expired session is answered exactly as a request without one: a configured expired session strategy continues the request without the session, where Spring Security's default writes a plain text body with status 200, and only the security context is cleared, where by default every logout handler runs, which would also expire the `XSRF-TOKEN` cookie and send `Clear-Site-Data`. A test covers both.
* **Acknowledgement history is public in the same reduced form.** Decision 5's anonymous view extends to every row of one alert's history (`action` and `acted_at` only); the operator also gets `principal` and `note`.
* **No operator configured.** The acknowledgement endpoint is then refused by a filter placed before the CSRF check, because an authorization rule would answer an anonymous caller `401`. Login answers `401` for every attempt, the same body as a wrong password.
* **Credentials only in the body.** A login whose URL has a query string is refused with `400` before the password is checked, since a password there would reach access logs.
* **Paths are matched decoded.** The throttle and the no operator filter match the login and acknowledgement paths as form login and authorization do, on the decoded path, so a percent encoded path cannot reach the password check or the endpoint past them.
* **The cookie is `Secure` in every profile**, the local one included, until a browser is shown to refuse it from `http://localhost` (Consequences); tests handle cookies themselves.
* **The bcrypt strength** is measured on the local machine and set to the cost nearest one second per check; the measurement is repeated on the cloud environment before acknowledgement is enabled there. The password encoder is bcrypt only, at that same strength, so the check run for an unknown username, which hashes with the encoder's own strength, costs as much as a wrong password; a test shows the encoder hashes at the configured strength, and a hash at any other strength is refused.

## Options considered

The requirement check that led to the choice.

### Option A: server side session cookie with form login (chosen)

| Requirement | Met by |
|---|---|
| T6.1 | Anonymous requests to the endpoint get `401` from the filter chain, before any query |
| T6.2 | CSRF token in cookie plus header (`csrf.spa()`), and `SameSite=Strict` on the session cookie as defense in depth |
| T6.3 | bcrypt hash; throttling written in the service (decision 8) |
| T6.4, T6.5 | Decisions 3 and 4 |
| T6.6 | Hash per environment from `.env` or Secrets Manager; the walkthrough shows the login form, never the password |
| T5.4 | Same origin, no CORS configuration |

Chosen because the session cookie cannot be read by page script, so an escape of feed or assistant text into the page still cannot carry the credential away; logout and expiry are enforced by the server; and CSRF and session fixation handling come from the framework in the configuration its own reference recommends for single page applications. The cost, session state in one process, matches a service that runs as a single replica in a short lived environment.

### Option B: short lived bearer JWT issued by `query-api` (rejected)

The login endpoint would answer with an HMAC signed JWT (`NimbusJwtEncoder.withSecretKey`), validated by `oauth2ResourceServer` with `NimbusJwtDecoder.withSecretKey`, stateless, with CSRF off because no credential is sent automatically. It meets T6.1 to T6.6 if the token is kept in memory only, and scales to several replicas unchanged. Rejected because the token is readable by page script for its whole lifetime, exactly the failure the dashboard's untrusted text makes possible; a stolen token cannot be revoked before expiry except by rotating the signing key; and the issuing endpoint would be code I write. It becomes the better choice if `query-api` needs several replicas without shared session storage.

### Option C: a per environment operator token with no login (rejected)

A random 256 bit token per environment, its SHA-256 hash stored by the service, pasted into the dashboard and sent as a bearer header through a custom `AuthenticationFilter`. Guessing is not practical, so no password hash is needed. Rejected because the token sits in script memory like B's and stays valid for the whole life of an environment, the authentication path is custom code, and it has to be pasted on every page load.

### Option D: HTTP Basic over TLS (rejected)

`httpBasic(withDefaults())`. Rejected: once the browser stores Basic credentials it attaches them automatically, which the reference names as a CSRF exposure, so CSRF protection is needed anyway; if the dashboard sets the header from script instead, the password itself sits in script memory. The password crosses the wire on every write, there is no real logout, and with bcrypt tuned to about one second every request costs a second of CPU that any visitor can spend with failing requests.

### Sub choices rejected

* **Storing the plain password in the secret store and hashing it at startup.** Simpler to set up, but the plain password would sit in `.env`, Secrets Manager, and the container environment.
* **A sandbox account for demo viewers** whose acknowledgements are discarded. It would add an anonymous write path, which this ADR exists to prevent.
* **Hiding acknowledgement state from viewers entirely.** Whether and when an alert was acknowledged reveals nothing sensitive and is part of what the demo shows.
* **Triggers to enforce append only rows.** Grants are enough and keep the rule in one place a reviewer can read.
* **A hard lockout after repeated failures.** With one account, any visitor could lock me out during a demo.

## Hardening checklist additions

The `ack` section of the [hardening checklist](../security/hardening-checklist.md) carries these as `SEC-ACK-09` to `SEC-ACK-18`.

## Consequences

* The cloud environment needs TLS at the ingress before acknowledgement is enabled there, because the session cookie is `Secure` and the login form sends a password. How the ingress gets a certificate belongs to the cloud environment's own decision.
* The local stack serves plain HTTP on loopback. Whether every browser I use accepts a `Secure` cookie from `http://localhost` is not yet checked; if not, the local profile sets `secure=false` while staying loopback only, and the cloud profile keeps it `true`.
* Running `query-api` as more than one replica would need Spring Session JDBC for sessions, a shared store for the throttling counters, and a database level guard for the repeated action rule, and is a new decision.
* The acknowledgement table, its migration, and its grants follow decisions 3 and 4.

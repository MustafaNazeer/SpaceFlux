# Hardening checklist

Every pull request is checked against the items whose tags match the parts of the system it touches. Items tagged `all` apply to every pull request. Each item is written so it can be answered yes or no by reading the diff, running a command, or inspecting configuration. The reasoning behind each item is in the [threat model](threat-model.md); the IDs in brackets point to the threat it addresses.

Items marked **(to be verified)** depend on a tool, library, or provider behavior that I have not yet confirmed against its documentation. The exact tool or setting is chosen and linked when the component it belongs to is built, and the mark is removed then. Known gaps in the code built so far, and when each is handled, are listed in the threat model's [deferred and open items](threat-model.md#deferred-and-open-items-in-the-built-code).

## Tags

| Tag | Applies to |
|---|---|
| `all` | Every change |
| `ingest` | Go pollers and anything that calls a third party feed |
| `kafka` | Topics, producers, consumers, broker configuration |
| `risk-engine` | Propagation, screening, storm rules, alert emission |
| `query-api` | REST and GraphQL endpoints, including subscriptions |
| `ack` | The alert acknowledgement write path and its authentication |
| `dashboard` | The Angular app |
| `assistant` | Retrieval, prompt assembly, LLM calls, output handling |
| `mcp` | The MCP tool server and every tool definition |
| `data` | MySQL, MongoDB Atlas, migrations, vector index |
| `secrets` | Any credential, key, token, or password |
| `aws` | Terraform, EKS, IAM, networking, Kubernetes manifests |
| `ci` | GitHub Actions workflows, container builds, dependency tooling |
| `observability` | Logging, tracing, metrics, dashboards |
| `repo` | Anything committed or published publicly |

## all

- [ ] `SEC-ALL-01` No secret, key, token, password, or connection string appears in the diff, including tests, fixtures, docs, and examples. [T9.1, T12.1]
- [ ] `SEC-ALL-02` Every new dependency is named in the pull request description with the reason it is needed, and its lockfile entry is committed. [T11.3]
- [ ] `SEC-ALL-03` Every new network endpoint, topic, secret, or third party call is reflected in the threat model in the same pull request. [section 9]
- [ ] `SEC-ALL-04` Input from outside the process is validated against an explicit schema or type before use. [T1.1, T4.1, T8.2]
- [ ] `SEC-ALL-05` Error responses and log lines added in the diff contain no credential, full request URL with query string, or stack trace sent to a client. [T1.5, T5.5]

## ingest

- [ ] `SEC-ING-01` Every HTTP client has connect and total request timeouts set. [T1.1]
- [ ] `SEC-ING-02` Response bodies are read through a size limit, with the limit per feed stated in code. [T1.1]
- [ ] `SEC-ING-03` TLS certificate verification is never disabled, including in tests that hit real endpoints. [T1.7]
- [ ] `SEC-ING-04` Redirects are either disabled or restricted to a per feed host allowlist. [T1.7]
- [ ] `SEC-ING-05` Each poller has its own interval and exponential backoff with jitter, and its interval is linked to the provider's published guidance in `docs/source/`. CelesTrak's guidance is verified in [docs/source/celestrak.md](../source/celestrak.md) and applied by [ADR 0004](../adr/0004-celestrak-polling-and-error-handling.md); SWPC, DONKI, and Space-Track guidance **(to be verified)** when each poller is added. [T1.4]
- [ ] `SEC-ING-06` Requests send the identifying header and contact information the provider asks for, if it asks. CelesTrak's documentation does not ask for one ([docs/source/celestrak.md](../source/celestrak.md)), and ingest sends a descriptive `User-Agent` regardless; other providers **(to be verified)** when each poller is added. [T1.4]
- [ ] `SEC-ING-07` Payloads failing validation go to the matching `.dlq` topic with a reason, and are never silently dropped. [T1.1]
- [ ] `SEC-ING-08` Numeric fields are range checked (epochs, orbital elements, Kp, flux values) before publish, with bounds cited in `docs/risk/`, `docs/source/`, or `docs/data/`. A check the producer cannot make from a cited bound is named, with the consumer that makes it (for GP data, positive mean motion and plausible epochs are checked in the risk engine under `SEC-RSK-01`). [T1.3]
- [ ] `SEC-ING-09` Feed credentials (api.nasa.gov key, Space-Track login) are read from the environment at startup and never logged, including in failed request logs, and a key sent as a query parameter is redacted from `source_url` and dead letter reasons, with a test. [T1.5, T1.6]
- [ ] `SEC-ING-10` No Space-Track code lands before the ADR summarizing its user agreement exists. [T1.6]
- [ ] `SEC-ING-11` A provider base URL from configuration is checked at startup against the provider's one allowed scheme and host, and the service refuses to start otherwise. [T1.7]
- [ ] `SEC-ING-12` Every record and every dead letter published has a stated byte cap that holds after encoding and stays under the Kafka client's record limit, with a test using a payload that expands when encoded. [T1.1, T2.2]
- [ ] `SEC-ING-13` Dead letters are validated against the dead letter schema before publish. [T1.1]
- [ ] `SEC-ING-14` Health and readiness endpoints listen on loopback by default, are published by Compose only on `127.0.0.1`, set header read, write, and idle timeouts and a header size cap, and send `X-Content-Type-Options: nosniff`. [T1.8]
- [ ] `SEC-ING-15` Before the first cloud deployment, readiness responses carry status words only, with error detail kept in logs, and health ports have no public Service or ingress route. [T1.8, T5.5]

## kafka

- [ ] `SEC-KFK-01` Compose publishes broker ports only on `127.0.0.1`, never on all interfaces. [T2.1]
- [ ] `SEC-KFK-02` In Kubernetes the broker has no `LoadBalancer` or `NodePort` Service and no ingress route. [T2.1]
- [ ] `SEC-KFK-03` A NetworkPolicy limits which pods can reach the broker, and the cluster's network plugin is confirmed to enforce it **(to be verified)**. [T2.1]
- [ ] `SEC-KFK-04` Every consumer is idempotent on the feed's epoch and ID fields, with a test that delivers the same event twice. [T2.3]
- [ ] `SEC-KFK-05` Every consumer has bounded retries followed by a `.dlq` publish. [T2.2]
- [ ] `SEC-KFK-06` Kafka headers are used only for trace context and schema version, never for authorization or query input. [T2.4]

## risk-engine

- [ ] `SEC-RSK-01` Orbital elements are validated before propagation, including a positive mean motion and an epoch within a plausible window of the current time, and a test covers rejection of out of range elements. [T3.1, T1.3]
- [ ] `SEC-RSK-02` Propagation and screening per event have a time budget, and exceeding it is logged and counted. [T3.1]
- [ ] `SEC-RSK-03` Every alert carries the IDs and epochs of the events that produced it. [T3.2]

## query-api

- [ ] `SEC-API-01` All SQL uses parameter binding; no query text is built by concatenating request values. [T4.1]
- [ ] `SEC-API-02` Mongo filters are built from typed values; request values that are objects or start with an operator character are rejected. [T4.2]
- [ ] `SEC-API-03` GraphQL enforces a maximum query depth and a maximum complexity, each with a test that sends a query over the limit. How these limits are configured in Spring for GraphQL **(to be verified)**. [T5.1]
- [ ] `SEC-API-04` Every list field and endpoint has a maximum page size enforced server side. [T5.1]
- [ ] `SEC-API-05` The GraphQL introspection setting for the cloud environment matches the recorded ADR decision. [T5.2]
- [ ] `SEC-API-06` Subscriptions have a per client connection cap, an idle timeout, and an origin check on the handshake. [T5.3]
- [ ] `SEC-API-07` CORS allows only the dashboard origin, and never combines a wildcard origin with credentials. [T5.4]
- [ ] `SEC-API-08` Error handlers return a generic body with a correlation ID; stack traces are logged, not returned. [T5.5]
- [ ] `SEC-API-09` A per client rate limit applies in the cloud environment, at the ingress or in the service. [T5.6]
- [ ] `SEC-API-10` No endpoint returns raw CDM content; CDM derived responses expose flags and derived fields only, with a test asserting it. [T4.5]
- [ ] `SEC-API-11` Management and health endpoints that expose environment, configuration, or heap data are disabled or unreachable from outside the cluster. [T9.3]

## ack

- [ ] `SEC-ACK-01` The authentication design is recorded in an ADR before the endpoint is merged. [section 8]
- [ ] `SEC-ACK-02` Unauthenticated requests are rejected before any database access, with a test. [T6.1]
- [ ] `SEC-ACK-03` If a cookie carries the session: CSRF protection is on, and the cookie is `Secure`, `HttpOnly`, and `SameSite`. If a bearer token is used: it is not stored in `localStorage`. [T6.2]
- [ ] `SEC-ACK-04` Login attempts are rate limited, and any stored password uses a slow password hashing function. [T6.3]
- [ ] `SEC-ACK-05` The request body is allowlisted to the alert ID and an optional bounded length note; the principal and timestamp are set by the server. [T6.5]
- [ ] `SEC-ACK-06` Acknowledgement rows are append only and record principal, timestamp, and alert ID. [T6.4]
- [ ] `SEC-ACK-07` The identity used by the MCP tool server is rejected by this endpoint, with a test. [T8.7]
- [ ] `SEC-ACK-08` Demo credentials are created per environment from the secret store and never appear in the repo or in recordings. [T6.6]

## dashboard

- [ ] `SEC-DSH-01` No use of Angular's sanitizer bypass APIs, and no `innerHTML` binding of feed or assistant text. [T7.3]
- [ ] `SEC-DSH-02` Assistant answers render as plain text or sanitized markdown with images disabled and links restricted to an allowlist of source domains. [T7.3]
- [ ] `SEC-DSH-03` A Content Security Policy is served that disallows inline script and limits `connect-src` to the API origin. [T7.3, T5.4]
- [ ] `SEC-DSH-04` The demonstration disclaimer is visible on every view that shows an alert. [T1.3, T3.2]
- [ ] `SEC-DSH-05` No API key or credential is present in the built bundle; checked by searching the build output. [T9.1]

## assistant

- [ ] `SEC-AST-01` System instructions, user question, retrieved documents, and tool results are assembled in separate, clearly delimited sections; retrieved and tool text is labelled as quoted source material. [T7.1, T7.2]
- [ ] `SEC-AST-02` The eval set contains direct and indirect injection cases (including a retrieved document with embedded instructions), and they pass before merge. [T7.1, T7.2]
- [ ] `SEC-AST-03` Every citation in an answer resolves to a document retrieved for that request; unresolved citations are removed and counted. [T7.4]
- [ ] `SEC-AST-04` The user question has a maximum length, the model call has a maximum output token count, and each request has a maximum number of tool call rounds. [T7.5, T8.6]
- [ ] `SEC-AST-05` A per client request rate limit applies to the assistant endpoint. [T7.5]
- [ ] `SEC-AST-06` The OpenAI account has a spending limit set, and its behavior on reaching the limit is confirmed from OpenAI's documentation **(to be verified)**. [T7.5]
- [ ] `SEC-AST-07` No secret, connection string, or internal hostname is placed in any prompt, checked by reading the prompt templates. [T7.6]
- [ ] `SEC-AST-08` Only documents from the configured feeds are embedded, and each chunk stores its source ID and fetch time. [T7.7]
- [ ] `SEC-AST-09` Model output is never executed, evaluated, or interpolated into a query; it reaches tools only through validated parameters. [T7.8]
- [ ] `SEC-AST-10` No raw CDM content reaches a prompt or the vector index. [T7.9]

## mcp

- [ ] `SEC-MCP-01` Every tool is read only and maps to exactly one read endpoint on `query-api`; the list of tools and their endpoints is documented next to the tool server code. [T8.1]
- [ ] `SEC-MCP-02` No tool accepts a URL, host name, file path, or free form query string. [T8.3]
- [ ] `SEC-MCP-03` Every tool parameter has a type and bounds (ID format, maximum time window, maximum result count) enforced server side, with a test that sends an out of bounds value. [T8.2]
- [ ] `SEC-MCP-04` Tool names and descriptions are static in source; nothing about a tool is loaded from data at runtime. [T8.5]
- [ ] `SEC-MCP-05` Each tool has a rate limit, and the tool server rejects calls over it. [T8.6]
- [ ] `SEC-MCP-06` The tool server has no public Service or ingress route, and callers are authenticated if it uses a network transport. The supported transports and their security options in Spring AI **(to be verified)**. [T8.4]
- [ ] `SEC-MCP-07` The tool server calls `query-api` with its own read scoped identity. [T8.7]

## data

- [ ] `SEC-DAT-01` Separate database users exist for migrations (DDL) and for the application (no DDL). [T4.3]
- [ ] `SEC-DAT-02` The archiver's Mongo user can write only the raw document collections; the retrieval user can only read the vector collection. How Atlas free tier roles can be scoped **(to be verified)**. [T4.3]
- [ ] `SEC-DAT-03` The Atlas network access list does not include an allow all entry. [T4.4]
- [ ] `SEC-DAT-04` Raw CDM documents live in a collection that no public endpoint, tool, or embedding job reads. [T4.5, T7.9]
- [ ] `SEC-DAT-05` Database connections use TLS where the server supports it (Atlas requires it; local Compose MySQL is loopback only). [T4.4]

## secrets

- [ ] `SEC-SEC-01` `.env` and its variants are git ignored, `.env.example` contains placeholders only, and `.dockerignore` excludes `.env`, `.env.*`, and private key files at any depth, not only at the root. [T9.1]
- [ ] `SEC-SEC-02` No secret is passed as a Docker build argument or copied into an image layer. [T9.2]
- [ ] `SEC-SEC-03` In EKS, secrets come from AWS Secrets Manager through the chosen sync mechanism, not from values in manifests or Helm values files **(mechanism to be verified)**. [T9.4]
- [ ] `SEC-SEC-04` Each service has its own Kubernetes service account, and RBAC lets it read only its own secrets. [T9.4]
- [ ] `SEC-SEC-05` Encryption at rest for Kubernetes Secrets is confirmed for the cluster configuration in use **(to be verified)**. [T9.4]
- [ ] `SEC-SEC-06` Every secret has written rotation and revocation steps in `docs/security/`. [T9.6]
- [ ] `SEC-SEC-07` The OpenAI key used by CI is separate from the one used by the running assistant, so either can be revoked alone. [T9.6, T11.1]

## aws

- [ ] `SEC-AWS-01` CI authenticates to AWS through GitHub OIDC with a role whose trust policy is restricted to this repository and to the branches allowed to deploy; no IAM user access keys exist for CI. [T10.1]
- [ ] `SEC-AWS-02` Pods get AWS permissions only through per service roles bound to service accounts (IRSA or EKS Pod Identity, **to be verified**), never through the node role. [T10.2]
- [ ] `SEC-AWS-03` The EKS API endpoint is restricted to known address ranges or is private **(options to be verified)**. [T10.3]
- [ ] `SEC-AWS-04` Every resource is created by Terraform and tagged with the project name; nothing is created by hand. [T10.4]
- [ ] `SEC-AWS-05` Terraform state is not committed; if remote state is used, its bucket blocks public access, is encrypted, and is versioned. [T9.5]
- [ ] `SEC-AWS-06` Secret values are not stored as plain Terraform variables or outputs; any secret that passes through Terraform is marked sensitive, and what that marking does and does not protect is checked **(to be verified)**. [T9.5]
- [ ] `SEC-AWS-07` Budget alerts are configured on the account, and after each `terraform destroy` the billing console is checked for remaining resources. [T10.4]
- [ ] `SEC-AWS-08` Containers run as a non root user, drop all Linux capabilities not needed, set resource requests and limits, and use a read only root filesystem where the runtime allows. The same applies to local Compose services, with memory and process limits added once the local memory budget is measured. [T10.5]
- [ ] `SEC-AWS-09` Only the dashboard, `query-api`, and (if the ADR allows it) the assistant are reachable through ingress; Kafka, databases, the MCP tool server, Prometheus, and Grafana are not. [T2.1, T8.4, T13.2]

## ci

- [ ] `SEC-CI-01` Every third party action is pinned to a full commit SHA. [T11.2]
- [ ] `SEC-CI-02` Each workflow sets `permissions` explicitly, at the lowest level its jobs need. [T11.2]
- [ ] `SEC-CI-03` No workflow uses `pull_request_target` together with a checkout of the pull request's code. [T11.1]
- [ ] `SEC-CI-04` Jobs that use secrets (eval run against OpenAI, image push, Terraform) run only on pushes to protected branches or on manual runs started by the repo owner, not on pull requests from forks. [T11.1]
- [ ] `SEC-CI-05` A secret scanner runs on every push and pull request (tool choice **to be verified**). [T9.1, T12.1]
- [ ] `SEC-CI-06` Dependency vulnerability alerts and update pull requests are enabled for Go modules, the Java build, and npm. [T11.3]
- [ ] `SEC-CI-07` Container images are built from pinned, minimal base images and scanned for known vulnerabilities and embedded secrets before push to ECR (scanner choice **to be verified**). [T9.2, T11.4]

## observability

- [ ] `SEC-OBS-01` Logging configuration redacts credential headers, cookies, and URL query strings. [T1.5, T13.1]
- [ ] `SEC-OBS-02` Span attributes follow an allowlist; request bodies, prompts, and raw CDM content are not recorded. [T13.1, T7.9]
- [ ] `SEC-OBS-03` Prompt and answer logging is off by default outside local runs. [T13.1]
- [ ] `SEC-OBS-04` Grafana's default admin credentials are replaced from the secret store, and neither Grafana nor Prometheus is publicly exposed. [T13.2]

## repo

- [ ] `SEC-REP-01` GitHub secret scanning is enabled on the repository, and push protection is enabled if it is available for this repository type **(to be verified)**. [T12.1]
- [ ] `SEC-REP-02` A local pre push secret scan is installed and passes. [T12.1]
- [ ] `SEC-REP-03` `main` is protected and requires passing status checks before merge. [T12.3]
- [ ] `SEC-REP-04` No Space-Track payload, raw or partially redacted, exists anywhere in the repository, including test fixtures. [T12.2, T4.5]
- [ ] `SEC-REP-05` Screenshots, recordings, and docs added in the diff show no key, credential, internal host name, or raw CDM content. [T12.5, T6.6]
- [ ] `SEC-REP-06` A `SECURITY.md` explains how to report a vulnerability privately, once the private reporting option is confirmed **(to be verified)**. [T12.4]

## If a secret leaks

1. Revoke or rotate the secret at its provider immediately. This comes first, because removing it from history does not undo exposure.
2. Check the provider's usage and billing for activity since the leak.
3. Remove the secret from the working tree and add a scanner rule or ignore entry that would have caught it.
4. Decide separately whether rewriting history is worth it; a revoked secret in history is harmless, a live one is not.

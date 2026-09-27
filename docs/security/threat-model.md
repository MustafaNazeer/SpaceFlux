# Threat model

This threat model covers the planned SpaceFlux design. Nothing is built yet, so every threat and mitigation below is written against the architecture in [docs/architecture.md](../architecture.md) and the README. It will be revised as each component lands, and the [hardening checklist](hardening-checklist.md) turns the mitigations here into items every pull request is checked against.

SpaceFlux is a demonstration of streaming system design on public data. It is not an operational collision avoidance or space weather warning service. That scoping matters for security too: the worst realistic outcomes are a leaked credential, a surprise cloud or LLM bill, a misleading alert shown to a viewer, or a breach of a data provider's terms, not harm to a spacecraft.

Items marked **(to be verified)** depend on a fact about a library, provider, or cloud service that I have not yet confirmed against its own documentation. They stay marked until that check is done and linked.

## 1. System summary

```
CelesTrak, SWPC, DONKI, Space-Track
        |  (HTTPS, third party, untrusted content)
        v
     ingest (Go) ---> Kafka raw.* topics (+ .dlq)
                         |                 |
                         v                 v
                risk-engine (Java)     archiver (inside query-api)
                         |                 |
                         v                 v
                     alerts topic      MongoDB Atlas (raw docs, vector index)
                         |
                         v
                  query-api (REST + GraphQL) <---> MySQL (catalog, alerts, acks)
                         |            ^
                         v            |
                 Angular dashboard    MCP tool server (read only)
                                          ^
                                          |
                                  assistant (Spring AI) ---> OpenAI API
                                          |
                                          +--> Atlas Vector Search (DONKI and SWPC text)
```

Runtime environments:

* **Local:** Docker Compose with profiles on a single developer machine. Secrets come from a git ignored `.env` file.
* **Cloud:** an on demand AWS environment (VPC, EKS, ECR, Secrets Manager) created by Terraform for demos and destroyed afterwards. Secrets come from AWS Secrets Manager.
* **CI:** GitHub Actions on a public repository.

## 2. Assets

| ID | Asset | Why it matters |
|---|---|---|
| A1 | OpenAI API key | Direct financial exposure; abuse runs up spend |
| A2 | Space-Track account credentials | Account is personal and bound by a user agreement; misuse can cost access |
| A3 | api.nasa.gov key | Rate limited per key; leakage lets others burn the quota |
| A4 | MongoDB Atlas and MySQL credentials | Read or overwrite of stored data, including raw conjunction data that must not be republished |
| A5 | AWS credentials and IAM roles | Billable resource creation, data access, account takeover |
| A6 | Raw Space-Track conjunction data messages | Must never appear on a public surface |
| A7 | Integrity of alerts and published numbers | A forged or corrupted alert, or a tampered benchmark, undermines the project's honesty |
| A8 | The public repository and its history | Anything committed is effectively permanent and world readable |
| A9 | Availability of the demo environment | A demo that is down or throttled during an interview is a real cost |
| A10 | Alert acknowledgement records | The only user writable data in the system |

## 3. Actors

* **Anonymous internet visitor** who reaches the dashboard, the API, or the assistant while the cloud environment is up.
* **Compromised or spoofed upstream feed**, or an attacker who can influence text that a feed carries (for example free text fields in space weather notifications). This is the main route for indirect prompt injection.
* **Malicious pull request author** targeting CI secrets or the supply chain through the public repo.
* **Compromised dependency** (library, container base image, GitHub Action).
* **Opportunistic scanner** hunting for leaked keys in public commits, images, and logs.
* **The LLM itself**, treated as an untrusted component whose output can be steered by its inputs.

## 4. Trust boundaries

| ID | Boundary | Crossing |
|---|---|---|
| TB1 | Internet feeds to `ingest` | Untrusted third party payloads enter the system |
| TB2 | Services to Kafka | Internal, but every consumer still validates |
| TB3 | Services to MongoDB Atlas | Traffic leaves the cluster or machine to a managed service over the internet |
| TB4 | Browser to `query-api` | Untrusted client requests, including the single write path |
| TB5 | `assistant` to OpenAI | Prompt content leaves the system; model output comes back untrusted |
| TB6 | `assistant` to MCP tool server to `query-api` | Model chosen tool calls become real API requests |
| TB7 | GitHub Actions to AWS and to the OpenAI API | CI holds or obtains credentials |
| TB8 | Developer machine to public GitHub | Anything pushed becomes public |

## 5. Assumptions

1. The upstream providers serve over HTTPS and their TLS certificates validate with the standard system trust store.
2. Only I have write access to the repository and the AWS account.
3. The cloud environment is short lived and is torn down after each demo, which limits exposure time but does not replace any control below.
4. Kafka, MySQL, and the MCP tool server are never exposed to the internet; only the dashboard, `query-api`, and possibly the assistant endpoint are.
5. The OpenAI account has a hard spending limit configured before the first call. Whether that limit hard stops requests or only notifies **(to be verified)** in the OpenAI console documentation before it is relied on.

## 6. Threats and mitigations

Threats are grouped by component and labelled with STRIDE categories (Spoofing, Tampering, Repudiation, Information disclosure, Denial of service, Elevation of privilege). Each mitigation names the checklist tag that carries it.

### 6.1 Third party feeds and `ingest`

| ID | Threat | STRIDE | Mitigation | Checklist |
|---|---|---|---|---|
| T1.1 | A malformed, oversized, or hostile payload crashes a poller or a consumer, or exhausts memory | D | Response body size cap, request timeouts, strict schema validation at the producer, malformed payloads routed to the `.dlq` topic with the reason attached | `ingest` |
| T1.2 | Feed free text carries instructions aimed at the assistant (indirect prompt injection that is stored and later retrieved) | T, E | Feed text is stored as data, never interpreted; the assistant treats it as untrusted (see 6.7) | `ingest`, `assistant` |
| T1.3 | Plausible but wrong values (spoofed feed, upstream error) produce false alerts | T | Range and consistency checks on physical quantities, epoch sanity checks, stale data banners; the demonstration disclaimer on every surface | `ingest`, `risk-engine`, `dashboard` |
| T1.4 | Polling faster than a provider allows gets the project blocked or breaches its terms | D | Per feed rate limiter and exponential backoff with jitter, cadences taken from each provider's own guidance **(to be verified per provider)**, an identifying request header with a contact address where the provider asks for one **(to be verified)** | `ingest` |
| T1.5 | API keys sent as URL query parameters leak through logs, traces, or error messages. api.nasa.gov documents its key as an `api_key` URL query parameter (https://api.nasa.gov/); whether it also accepts the key in a header **(to be verified)** | I | Redact query strings and credential headers in every log line, span attribute, and DLQ reason | `ingest`, `observability` |
| T1.6 | Space-Track session credentials or cookies leak, or the account is used outside its user agreement | I, S | Credentials only from the secret store; session reuse to limit logins; terms summarized in an ADR before any Space-Track code lands | `ingest`, `secrets` |
| T1.7 | Redirect or DNS tampering sends a poller to an unexpected host | S | TLS verification never disabled; redirects to hosts outside a per feed allowlist rejected | `ingest` |

### 6.2 Kafka

| ID | Threat | STRIDE | Mitigation | Checklist |
|---|---|---|---|---|
| T2.1 | Broker port exposed beyond the host or cluster lets anyone publish forged events to `alerts` or `raw.*` | S, T | Local Compose binds broker ports to `127.0.0.1` only; in EKS the broker has no public Service and a NetworkPolicy limits producers and consumers **(NetworkPolicy enforcement depends on the cluster's network plugin, to be verified)** | `kafka`, `aws` |
| T2.2 | A poison message stalls a consumer group | D | Bounded retries then `.dlq`; consumer lag alerting | `kafka`, `observability` |
| T2.3 | Replayed or duplicated events create duplicate alerts | T | Idempotent consumers deduplicating on feed epoch and ID fields | `kafka`, `risk-engine` |
| T2.4 | Trace context headers are trusted as authentication or copied into queries | T | Headers used only for tracing, never for authorization or data | `kafka`, `observability` |

Broker authentication and TLS inside the cluster are an open decision (section 8).

### 6.3 `risk-engine`

| ID | Threat | STRIDE | Mitigation | Checklist |
|---|---|---|---|---|
| T3.1 | Crafted orbital elements trigger pathological propagation cost (CPU exhaustion) | D | Validation of element ranges before propagation; per event time budget; screening limited to the watchlist | `risk-engine` |
| T3.2 | An alert is emitted from unvalidated input and presented as authoritative | T | Every alert carries its source event IDs and epochs so it can be traced; wording reviewed so no alert implies operational use | `risk-engine`, `dashboard` |

### 6.4 Data stores: MySQL and MongoDB Atlas

| ID | Threat | STRIDE | Mitigation | Checklist |
|---|---|---|---|---|
| T4.1 | SQL injection through API parameters | T, I | Parameterized queries only; no string built SQL | `query-api`, `data` |
| T4.2 | NoSQL operator injection (for example a JSON object where a string was expected) | T, I | Typed request binding; reject maps and operators in user supplied filter values | `query-api`, `data` |
| T4.3 | One leaked database credential grants full control | E | Separate database users per service and purpose: migration user with DDL, application user without it, archiver writer, retrieval reader | `data`, `secrets` |
| T4.4 | Atlas cluster reachable from anywhere | I | Network access list restricted to known egress addresses rather than open to all. What the free tier supports for network access control **(to be verified)** | `data`, `aws` |
| T4.5 | Raw conjunction data is exported through an API response, a log, a fixture, or a backup | I | Raw CDM documents stored in a collection that no public endpoint reads; API returns derived flags only | `data`, `query-api`, `repo` |

### 6.5 `query-api` (REST and GraphQL)

| ID | Threat | STRIDE | Mitigation | Checklist |
|---|---|---|---|---|
| T5.1 | Deeply nested or aliased GraphQL queries exhaust CPU or database connections | D | Query depth and complexity limits, result page size caps, request timeouts | `query-api` |
| T5.2 | Schema introspection helps an attacker map the API | I | Decision recorded on whether introspection stays on in the demo environment; the schema is public in the repo anyway, so the limits in T5.1 are the real control | `query-api` |
| T5.3 | The live alert subscription is abused to hold many open connections | D | Cap concurrent subscriptions per client, idle timeouts, origin check on the WebSocket handshake | `query-api` |
| T5.4 | Cross site requests from another origin read or write data | I, T | Explicit CORS allowlist for the dashboard origin only; no wildcard with credentials | `query-api` |
| T5.5 | Stack traces or internal hostnames leak in error responses | I | Generic error bodies; details only in server logs | `query-api` |
| T5.6 | Request flooding while the demo environment is up | D | Per client rate limiting at the ingress or in the service | `query-api`, `aws` |

### 6.6 Alert acknowledgement write path

This is the only endpoint that changes state. Its authentication design is decided in an ADR before it is implemented. Whatever design is chosen must satisfy these requirements:

| ID | Threat | STRIDE | Requirement | Checklist |
|---|---|---|---|---|
| T6.1 | An anonymous visitor acknowledges or unacknowledges alerts | S, T | Authentication required; unauthenticated requests rejected before any database access | `ack` |
| T6.2 | Cross site request forgery from a page the operator visits | T | If a cookie carries the session, CSRF protection and `SameSite` cookies; if a bearer token is used, it is never stored where other origins can read it | `ack` |
| T6.3 | Credential guessing | S | Login rate limiting and lockout or backoff; passwords, if any, hashed with a slow password hash | `ack` |
| T6.4 | No record of who acknowledged what | R | Each acknowledgement stores the principal, timestamp, and alert ID; rows are append only | `ack`, `data` |
| T6.5 | The acknowledgement endpoint accepts fields beyond the alert ID and note | T, E | Allowlisted request body; server sets principal and timestamp | `ack` |
| T6.6 | A demo credential committed to the repo or shown in the recorded walkthrough | I | Credentials created per environment from the secret store; walkthrough reviewed before publishing | `ack`, `repo` |

### 6.7 `assistant` (retrieval and LLM)

The assistant reads three kinds of untrusted text: the user's question, retrieved DONKI and SWPC documents, and tool results derived from feed data. Any of them can contain instructions. The design goal is that even a fully successful injection can only change the wording of an answer, because the assistant holds no write capability and no secrets worth stealing. This follows the prompt injection guidance in the OWASP Top 10 for LLM Applications; the edition and item numbering I cite will be pinned when that document is linked **(to be verified)**.

| ID | Threat | STRIDE | Mitigation | Checklist |
|---|---|---|---|---|
| T7.1 | Direct prompt injection from the user's question | T, E | System instructions kept separate from user content; out of scope requests refused; the eval set includes injection attempts | `assistant` |
| T7.2 | Indirect prompt injection through retrieved documents or tool results | T, E | Retrieved and tool text placed in clearly delimited data sections and described to the model as quoted source material, never as instructions; the model has no tool whose effect matters if steered (see 6.8) | `assistant`, `mcp` |
| T7.3 | Data exfiltration through rendered output (for example markdown images or links that encode data in a URL) | I | The dashboard renders assistant output as plain text or sanitized markdown with images disabled and links limited to an allowlist of source domains | `assistant`, `dashboard` |
| T7.4 | Fabricated or spoofed citations | T | Citations must resolve to IDs of documents actually retrieved for that request; unresolved citations are stripped and counted in evals | `assistant` |
| T7.5 | Cost exhaustion by long or repeated prompts | D | Input length cap, output token cap, per client request rate limit, bounded tool call loop count per request, provider spending limit | `assistant`, `secrets` |
| T7.6 | Secrets or internal details leak through the prompt | I | No secret, connection string, or internal hostname is ever placed in a prompt; system prompt contains nothing I would mind being printed | `assistant` |
| T7.7 | Poisoned documents in the vector index | T | Only documents from the configured feeds are embedded; each chunk keeps its source ID and fetch time so a bad document can be traced and removed | `assistant`, `data` |
| T7.8 | Model output is trusted by code (parsed and executed, used in queries) | E | Model output only reaches the user as text or reaches tools through schema validated parameters | `assistant`, `mcp` |
| T7.9 | Prompts and answers sent to OpenAI contain data that must not leave the system (raw CDMs) | I | Raw CDM content never enters prompts or the vector index; only derived flags reach the assistant | `assistant`, `data` |

### 6.8 MCP tool surface

| ID | Threat | STRIDE | Mitigation | Checklist |
|---|---|---|---|---|
| T8.1 | A steered model calls a tool that changes state | T, E | Every tool is read only and maps to a single read endpoint on `query-api`; acknowledgement is never exposed as a tool | `mcp` |
| T8.2 | Tool parameters are used for injection or unbounded queries | T, D | Each parameter has a typed schema with bounds (ID format, maximum time window, maximum result count); validation happens server side in the tool server, not only in the model's schema | `mcp` |
| T8.3 | Server side request forgery through a tool that fetches a URL | I, E | No tool accepts a URL, host, file path, or free form query string | `mcp` |
| T8.4 | The tool server is reachable from outside and used as a free API | S, D | Internal only exposure; if it runs over a network transport, no public Service or ingress, and callers authenticated. Which transports the Spring AI MCP server supports and how each is secured **(to be verified)** | `mcp`, `aws` |
| T8.5 | Tool descriptions or names are altered to steer the model (tool poisoning) | T | Tool definitions are static, in source, and reviewed like code; no tool metadata is loaded at runtime from data | `mcp` |
| T8.6 | Tool call floods from a looping model | D | Per request cap on tool calls and per tool rate limits | `mcp`, `assistant` |
| T8.7 | The tool server's credential to `query-api` is broader than needed | E | Tool server calls `query-api` with a read scoped identity that the acknowledgement endpoint rejects | `mcp`, `ack` |

### 6.9 Secrets handling

| ID | Threat | STRIDE | Mitigation | Checklist |
|---|---|---|---|---|
| T9.1 | `.env` committed or copied into an image | I | `.env` and variants git ignored, `.env.example` holds placeholders only, `.dockerignore` excludes `.env`, secret scanning before push and in CI | `secrets`, `repo`, `ci` |
| T9.2 | Secrets baked into container images through build args or layers | I | No secrets at build time; image scan for secrets before push to ECR | `secrets`, `ci` |
| T9.3 | Secrets printed in logs, startup banners, config dumps, or actuator style endpoints | I | Redaction in logging config; management endpoints that expose environment or config disabled or kept internal | `secrets`, `observability` |
| T9.4 | Kubernetes Secrets readable by any pod or stored unencrypted | I, E | Per service service accounts and RBAC; secret values synced from AWS Secrets Manager rather than written into manifests. The sync mechanism (Secrets Store CSI driver or External Secrets Operator) and whether EKS encrypts Secrets at rest with a KMS key by default **(to be verified)** | `secrets`, `aws` |
| T9.5 | Terraform state contains secret values and leaks | I | State never committed (already git ignored); if remote state is used, the bucket is private, encrypted, and versioned; secret values created outside Terraform or marked sensitive, with the limits of that marking checked **(to be verified)** | `secrets`, `aws` |
| T9.6 | A leaked key stays valid | I | Written rotation steps for every secret; a leaked key is revoked first, history cleanup second | `secrets` |

### 6.10 AWS, EKS, and Terraform

| ID | Threat | STRIDE | Mitigation | Checklist |
|---|---|---|---|---|
| T10.1 | Long lived IAM user keys leak | S, E | No long lived access keys in CI; GitHub Actions assumes a role through OIDC federation restricted to this repository and branch | `aws`, `ci` |
| T10.2 | Pods run with node level AWS permissions | E | Per service IAM roles bound to Kubernetes service accounts (IRSA or EKS Pod Identity, choice **(to be verified)** against current EKS docs) | `aws` |
| T10.3 | The EKS API endpoint is open to the internet | S | Endpoint access restricted to my address ranges or private access **(options to be verified)** | `aws` |
| T10.4 | Orphaned resources keep billing after a demo | D | Everything created by Terraform, tagged, and removed by `terraform destroy`; a billing check after each teardown; budget alerts on the account | `aws` |
| T10.5 | Containers run as root or with writable root filesystems | E | Non root users, read only root filesystem where the runtime allows, dropped capabilities, resource limits | `aws` |

### 6.11 CI and supply chain

| ID | Threat | STRIDE | Mitigation | Checklist |
|---|---|---|---|---|
| T11.1 | A pull request from a fork reads secrets or spends the OpenAI key through the eval job | I, D | Secret using jobs run only on trusted events; no `pull_request_target` workflow checks out untrusted code | `ci` |
| T11.2 | A compromised third party GitHub Action | T, E | Actions pinned to a full commit SHA; workflow token permissions set to least privilege | `ci` |
| T11.3 | A vulnerable or malicious dependency | T, E | Dependency update and vulnerability alerts enabled for Go modules, Maven or Gradle, and npm; lockfiles committed; new dependencies reviewed before merge | `ci`, `all` |
| T11.4 | A vulnerable base image | T, E | Minimal, pinned base images scanned in CI before push | `ci` |

### 6.12 The public repository

| ID | Threat | STRIDE | Mitigation | Checklist |
|---|---|---|---|---|
| T12.1 | A secret committed and pushed | I | Pre push secret scan locally and in CI; GitHub secret scanning enabled. Push protection availability for public personal repositories **(to be verified)** | `repo` |
| T12.2 | Raw conjunction data committed as a test fixture or sample | I | No Space-Track payload under the repo; tests for CDM handling use fixtures that carry no raw CDM content. How to reconcile this with the rule that fixtures are real recorded payloads is an open decision (section 8) | `repo` |
| T12.3 | Unreviewed changes land on the default branch | T | Branch protection on `main` requiring passing checks | `repo` |
| T12.4 | Vulnerabilities reported in public issues | I | A `SECURITY.md` describing private reporting, if private vulnerability reporting is enabled on the repo **(to be verified)** | `repo` |
| T12.5 | Screenshots, recordings, or docs show keys, internal hosts, or acknowledgement credentials | I | Every published image and recording reviewed before commit | `repo` |

### 6.13 Observability

| ID | Threat | STRIDE | Mitigation | Checklist |
|---|---|---|---|---|
| T13.1 | Logs or spans capture secrets, full prompts, or raw CDMs | I | Attribute and log allowlists; redaction of credential headers and query strings; prompt logging off by default outside local runs | `observability` |
| T13.2 | Grafana or Prometheus exposed with default credentials | S, I | Not exposed publicly; default admin credentials replaced from the secret store | `observability`, `aws` |

## 7. Residual risks accepted

1. Upstream data can be wrong without being malicious. The system flags it where checks can detect it, and the demonstration disclaimer covers the rest.
2. Prompt injection cannot be fully prevented. It is contained by giving the assistant only read only tools and no secrets, and measured by injection cases in the eval set.
3. While the demo environment is up, public endpoints can be probed. Exposure is limited by the short lifetime of the environment and by the controls above.

## 8. Open decisions

These are decided in ADRs before the affected component is built.

1. **Alert acknowledgement authentication** (session cookie versus bearer token, where credentials come from, how a demo viewer is handled).
2. **Whether the assistant endpoint is public** during demos, or requires the same authentication as acknowledgement to protect LLM spend.
3. **Kafka authentication and TLS inside the cluster**, versus relying on network isolation for a short lived demo environment.
4. **Secrets sync mechanism on EKS** and the IAM binding model for pods.
5. **Atlas network access** for a cluster whose egress addresses change each time it is recreated.
6. **CDM test fixtures**: how CDM handling is tested without committing raw CDM content.
7. **GraphQL introspection** in the demo environment.

## 9. When this document changes

This model is updated whenever a new service, endpoint, topic, secret, third party call, dependency with network access, or assistant tool is added, and at a full hardening review before load tests are published.

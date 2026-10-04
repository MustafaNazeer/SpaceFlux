# ADR 0011: A shared module for the event contracts

* **Status:** accepted
* **Date:** 2026-10-04

## Context

`query-api` is about to become the second Java service that reads Kafka. [ADR 0002](0002-event-schemas-and-serialization.md) requires every consumer to validate each event against its topic's JSON Schema file and to dead letter what fails, and [ADR 0008](0008-java-kafka-client-and-schema-validator.md) fixes how the Java services do it: networknt json-schema-validator 3.0.8 on Jackson 3, schemas loaded once from the repository's files and never from the network, each record parsed once as a tree with duplicate keys rejected and a 1 MiB document limit, and any failure inside validation returned as a failed check rather than thrown.

`risk-engine` already does all of this in two classes, `TopicSchemas` (the check) and `DeadLetters` (dlq v1 records with ingest's payload limits), and the threat model's T2.5 to T2.8 controls were reviewed against them. The only service specific detail is the `service` field of a dead letter, a constant set to `risk-engine`. [ADR 0001](0001-repo-layout-and-build-tool.md) lists one Maven module per deployable and does not say where code shared between Java services lives.

## Decision

1. **A new Maven module, `kafka-contracts`**, added to the parent POM next to the services. It is a plain library: no Spring Boot repackaging, no main class, no image of its own. Its package is `io.github.mustafanazeer.spaceflux.contracts`.
2. **`TopicSchemas` and `DeadLetters` move into it unchanged in behavior**, with their tests. The one change is that `DeadLetters` takes the service name in its constructor instead of a constant, so `risk-engine` passes `risk-engine` and `query-api` passes `query-api`.
3. **The module packages the schema files.** The build step that copies `schemas/<topic>/v<n>.schema.json` into the jar moves from `risk-engine`'s POM to this module's, so every service that depends on it carries the same schema files the repository holds, and `TopicSchemas` keeps failing at startup when one is missing.
4. **`risk-engine` and `query-api` depend on it** at compile scope. Each service's image is still built from the repository root, so its Dockerfile copies the module's POM and source and the `schemas/` directory.
5. **Only contract code goes in.** Validation, dead letter construction, and the constants that come from the topic contracts belong here. Listener configuration, retry policy, and anything that differs between services stay in the services.

## Alternatives considered

* **Copy the two classes into `query-api`.** No change to `risk-engine`, but two copies of the same hardening, reviewed once and free to drift; a fix to one, such as a tighter document limit, would have to be found and repeated in the other.
* **`query-api` depends on `risk-engine`.** Reuses the classes without a new module, but `risk-engine` is a repackaged Spring Boot application, not a library, and the dependency would pull Orekit and the screening code into the query service.
* **One module for every piece of shared Java code.** Broader than needed today; a shared module that collects unrelated helpers becomes a place where service boundaries blur. A second shared module can be added when there is a second kind of shared code.

## Consequences

* A change to validation or dead lettering is made once and covered by one set of tests; both services pick it up in the same build.
* Building either service also builds `kafka-contracts`, and every Java Dockerfile copies its POM, as the reactor already requires for every module.
* `assistant`, when it is built, uses the same module if it reads Kafka.

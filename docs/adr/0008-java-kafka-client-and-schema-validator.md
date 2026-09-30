# ADR 0008: Java Kafka client and JSON Schema validator

* **Status:** accepted
* **Date:** 2026-09-30

## Context

`risk-engine` is the first Java service to read and write Kafka. It consumes `raw.swpc` and `raw.gp` and publishes `alerts` ([ADR 0007](0007-alerts-topic.md)). [ADR 0002](0002-event-schemas-and-serialization.md) requires every consumer to validate each event against its topic's JSON Schema file and to dead letter what fails, and [ADR 0003](0003-go-kafka-client-and-schema-validator.md) left the Java validator open, on the condition that it supports draft 2020-12, the draft every schema file declares.

The service is a Spring Boot 4.1.1 application and already uses Jackson 3 (`tools.jackson`) to read feed records. Versions below were checked against Maven Central and the Spring Boot 4.1.1 dependency management on 2026-09-30.

## Decision

1. **Kafka client: Spring for Apache Kafka 4.1.1**, the version the Spring Boot 4.1.1 parent manages, on `kafka-clients` 4.2.2. The parent manages 4.2.1; the build overrides it to the 4.2.2 patch release, which brings `lz4-java` 1.11.2 instead of 1.10.1 and its advisory ([threat model](../security/threat-model.md), T11.3). Its listener containers handle polling, rebalances, offset commits and shutdown inside the Spring lifecycle the service already has, and its error handlers route a failed record to a dead letter topic without hand written poll loops.
2. **JSON Schema validator: [networknt json-schema-validator](https://github.com/networknt/json-schema-validator) 3.0.8.** It supports draft 2020-12, reports its compliance on the Bowtie report, and its 3.x line is built on Jackson 3, the same JSON library the service already uses, so a record is parsed once. Jackson itself is pinned to 3.1.7: the parent manages 3.1.5, which has advisories that 3.1.7 fixes, and the validator's own request, 3.2.1, is affected too. The validator is set up so that it never loads a schema from the network, loads each topic's schema from the repository's files once at startup, asserts formats as `ingest` does, and turns any failure inside validation, including a stack overflow on a hostile string, into a failed validation that is dead lettered.
3. **Delivery is at least once.** The `alerts` producer has idempotence enabled, and a consumer commits a record's offset only after every event it produced from that record has been acknowledged. A crash between the two republishes those events; consumers of `alerts` drop them by `event_id` (ADR 0007). Kafka transactions are not used.
4. **Records with a duplicated key are rejected.** Each record is parsed once, as a tree, with strict duplicate detection and a 1 MiB document limit, so a record that names a field twice fails as a schema failure and is dead lettered rather than read by its last occurrence.
5. **Series state is held in memory.** The state each space weather series needs (its last published state, the Kp values seen per interval, the edge window for restatements) is rebuilt from new records after a restart, as ADR 0007 already allows: the first event of a series after a restart has no previous state. A batch whose events could not be written is different: the state is restored to what it was before that batch, so the redelivered batch is read from the same state and produces the same events with the same identities. After a real restart the state is gone, so the first Kp record read can be a revision of an older interval; it then sets the G state, and if that interval is already past the age limit a "no data" follows until the next new interval arrives. That is a visible gap, never a level shown from stale data, and it is accepted.
6. **The screening consumer rebuilds from the start of `raw.gp` instead of committing offsets.** A screening run starts about 30 seconds after the last element set of a batch arrived, on a clock, not while the batch is being read, so committing a batch's offsets after its events are written, as for `raw.swpc`, would tie the commit to a different thread and a later moment. Instead the `raw.gp` consumer never commits: on every start it reads the topic from the beginning, keeps the newest element set per object, and screens only once the newest batch has been quiet. A run's window starts at the newest `fetched_at` among the element sets it uses, so a restart reproduces the same run and the same event identities, and consumers of `alerts` drop the repeats. The cost is a replay of `raw.gp` on every start, small while the catalog is one CelesTrak group; it is revisited when the catalog grows or retention is set.
7. **Integration tests use Testcontainers 2.0.5** (`testcontainers-kafka`, managed by Spring Boot 4.1.1) with the same `apache/kafka:4.3.1` image as `ingest`.

## Alternatives considered

* **Plain `kafka-clients`.** One framework layer fewer, but the poll loop, rebalance handling, commit ordering and shutdown would be written and tested by hand, which Spring Kafka already does inside the service's lifecycle.
* **json-sKema 0.32.0.** Focused on draft 2020-12, but still before 1.0 and built on its own JSON parser, so each record would be parsed twice.
* **Kafka transactions** (read, process, write exactly once). They would remove duplicate `alerts` events, but every consumer already deduplicates by `event_id`, and transactions add broker and client configuration for no reader visible gain.
* **Committing `raw.gp` offsets after the run that used them** (manual acknowledgement held until the run is written, from the clock thread). It avoids the replay on start, but splits one batch's commit across the listener and the clock thread, with more states to get wrong than a rebuild that always gives the same result.
* **Rebuilding series state from `raw.swpc` on start.** It would keep `previous_state` across restarts, but needs a replay phase that publishes nothing and a retention setting that is not decided yet. Worth revisiting if restarts turn out to be frequent.

## Consequences

* A Kafka upgrade in the Java service follows the Spring Boot parent version; the test broker image stays shared with `ingest`.
* A risk-engine restart can publish one extra event per series that repeats a state a consumer already holds.

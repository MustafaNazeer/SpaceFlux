# ADR 0003: Go Kafka client and JSON Schema validator

* **Status:** accepted
* **Date:** 2026-09-27

## Context

The Go `ingest` service publishes every feed to Kafka and, per [ADR 0002](0002-event-schemas-and-serialization.md), validates each event against a JSON Schema file before publishing. ADR 0002 left two choices open until the first producer was written: the Kafka client library and the JSON Schema draft and validator.

Versions below were checked against proxy.golang.org, the Apache Kafka downloads index, and Docker Hub on 2026-09-27.

## Decision

1. **Kafka client: [franz-go](https://github.com/twmb/franz-go) v1.22.0.** It is written in pure Go, so `ingest` builds as a static binary with `CGO_ENABLED=0` and ships in a minimal image. It supports idempotent producers, which fits the at least once delivery model, and it is actively maintained.
2. **Schemas declare JSON Schema draft 2020-12**, validated in Go with [santhosh-tekuri/jsonschema](https://github.com/santhosh-tekuri/jsonschema) v6.0.3, a pure Go validator whose README reports 2020-12 compliance on the Bowtie report.
3. **Local and test broker: `apache/kafka:4.3.1`**, a single node in KRaft mode. It is the newest stable release; 4.4.0 was still a release candidate on the decision date.

## Alternatives considered

* **confluent-kafka-go v2.15.1.** Wraps librdkafka, which is the reference client behavior. Rejected because it needs cgo, which complicates cross compilation and the container image for no feature `ingest` needs.
* **segmentio/kafka-go v0.4.51.** Pure Go with a small API. Rejected because it is still before 1.0 and updated less often than franz-go.
* **JSON Schema draft-07.** More widely supported by older validators. Rejected because 2020-12 is the current draft and the Go validator supports it fully; the Java consumers must pick a validator that supports 2020-12 too.

## Consequences

* The Java consumer validator is constrained to one that supports draft 2020-12. That is checked when the first Java consumer is written, and this ADR is revisited if no suitable library exists.
* Kafka upgrades change one image tag in Compose and in the integration tests together.

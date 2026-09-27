# ADR 0002: Event schemas and serialization

* **Status:** accepted
* **Date:** 2026-09-27

## Context

Every Kafka topic (`raw.gp`, `raw.swpc`, `raw.donki`, `raw.cdm`, `alerts`, and their `.dlq` topics) carries events written by one language and read by another: the Go `ingest` service produces the raw topics and Java services consume them. A contract that both sides check is what keeps a field rename in the producer from silently breaking a consumer.

The contract has to be enforced somewhere. The two common options are a schema registry service that producers and consumers consult at runtime, or schema files kept in the repository and checked at build and run time. The development machine has about 7 GiB of RAM, and every additional always on service reduces what is left for Kafka and the service under work.

## Decision

1. **Events are JSON, described by JSON Schema files kept in the repository** under `schemas/`, one file per topic and major version.
2. **Every event carries its schema version** in the payload, so a consumer can tell which contract an event was written against.
3. **The producer validates every event before publishing**, and **every consumer validates on read**. An event that fails validation goes to the topic's `.dlq` topic with the validation error attached; nothing is dropped silently.
4. **Compatibility is checked in CI.** A change to an existing schema version must stay backward compatible for consumers (new optional fields only). Anything else is a new major version file, and consumers support both versions until the old one is retired.
5. The validation libraries for Go and Java are chosen when the first producer and consumer are written, after checking they support the JSON Schema draft the files declare.

## Alternatives considered

* **A schema registry (for example Confluent Schema Registry or Apicurio).** Enforces compatibility centrally at runtime and is what many production Kafka deployments use. Rejected for now because it is one more service to run locally and in the cloud environment, and with a single repository the schemas and every producer and consumer already change in the same pull request, where CI can check compatibility before anything is deployed. Worth revisiting if services ever move to separate repositories or separate release cycles.
* **Avro or Protobuf with a registry.** Smaller messages and generated types. Rejected because readable JSON makes the raw feed payloads easy to inspect while debugging, message volume is modest, and the choice would add both a registry and a code generation step.

## Consequences

* Schemas are reviewed like code, with history in git.
* Nothing at runtime stops a misconfigured producer from publishing an unregistered shape; the producer side validation and the consumer side DLQ are the safeguards, so both are required, not optional.
* JSON is larger on the wire than a binary format. If load testing shows serialization cost or topic size matters, this decision is revisited with measurements.

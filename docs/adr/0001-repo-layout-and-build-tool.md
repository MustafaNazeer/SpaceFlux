# ADR 0001: Repository layout and build tool

* **Status:** accepted
* **Date:** 2026-09-27

## Context

SpaceFlux has one Go service (`ingest`), three Java services (`risk-engine`, `query-api`, `assistant`), an Angular dashboard, infrastructure code, and shared event schemas. The services change together often in the early stages (a new topic touches the producer, the schemas, and at least one consumer), so keeping them in one repository keeps those changes in one reviewable pull request.

The development machine has about 7 GiB of RAM. Only the services under active work run locally at once, and anything else that holds memory, including build tooling, competes with Kafka and MySQL for the same budget.

## Decision

1. **One repository (monorepo)** with a top level directory per deployable: `ingest/`, `risk-engine/`, `query-api/`, `assistant/`, `dashboard/`, plus `infra/`, `deploy/`, `schemas/`, and `docs/`.
2. **Maven multi-module build for the Java services.** A parent `pom.xml` at the repository root declares `risk-engine`, `query-api`, and `assistant` as modules and centralizes dependency and plugin versions. Each service keeps its own module POM and produces its own artifact and image.
3. **The Go service is its own Go module** in `ingest/`, built with the standard Go toolchain. The Angular dashboard uses the Angular CLI in `dashboard/`. Neither is wrapped in the Maven build.
4. Versions of Java, Spring Boot, Maven plugins, and Go are pinned when each component is introduced, after checking the current stable releases.

## Alternatives considered

* **Gradle multi-project.** Faster incremental builds and a more flexible build language. Rejected mainly because Gradle's default workflow keeps a long lived daemon JVM running between builds, which is memory I would rather give to Kafka and the service under test. It remains a reasonable choice if build times become the bottleneck.
* **A separate build per service with no parent.** Maximum independence, but every service would repeat dependency management, and version drift between services sharing libraries would be easy to introduce.
* **A repository per service.** Closer to how large organizations split ownership, but a single developer gains nothing from it, and cross service changes would span several pull requests.

## Consequences

* A change to a shared dependency version is made once in the parent POM.
* CI can build and test only the modules a pull request touches, using Maven's reactor options and path filters; the exact setup is decided with CI.
* The Maven build and the Go, Angular, and Terraform toolchains run side by side, so CI has one job per toolchain rather than one universal build command.

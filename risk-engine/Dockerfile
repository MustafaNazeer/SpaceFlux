# Build from the repository root so the parent pom and the schemas are in the context:
#   docker build -f risk-engine/Dockerfile -t spaceflux-risk-engine .
# Tests are not run here; ./mvnw verify runs them, including the Kafka integration test.
FROM maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS build
WORKDIR /src
COPY pom.xml ./
COPY risk-engine/pom.xml risk-engine/
COPY db-migrate/pom.xml db-migrate/
COPY query-api/pom.xml query-api/
RUN mvn -B -q -pl risk-engine -am dependency:go-offline
COPY schemas/ schemas/
COPY risk-engine/src/main/ risk-engine/src/main/
RUN mvn -B -q -pl risk-engine -am -DskipTests package \
 && cp risk-engine/target/risk-engine-*.jar /src/risk-engine.jar

FROM gcr.io/distroless/java21-debian13:nonroot@sha256:0a1f5a75661918de9c0813f287f651c3bf2d6dd752eada5f084eb0c1f14ced9e
COPY --from=build /src/risk-engine.jar /app/risk-engine.jar
USER nonroot:nonroot
# The base image's entrypoint is java -jar.
CMD ["/app/risk-engine.jar"]

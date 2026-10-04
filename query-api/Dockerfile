# Build from the repository root so the parent pom is in the context:
#   docker build -f query-api/Dockerfile -t spaceflux-query-api .
# Tests are not run here; ./mvnw verify runs them, including the MySQL integration test.
FROM maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS build
WORKDIR /src
COPY pom.xml ./
COPY risk-engine/pom.xml risk-engine/
COPY db-migrate/pom.xml db-migrate/
COPY query-api/pom.xml query-api/
RUN mvn -B -q -pl query-api -am dependency:go-offline
COPY query-api/src/main/ query-api/src/main/
RUN mvn -B -q -pl query-api -am -DskipTests package \
 && cp query-api/target/query-api-*.jar /src/query-api.jar

FROM gcr.io/distroless/java21-debian13:nonroot@sha256:0a1f5a75661918de9c0813f287f651c3bf2d6dd752eada5f084eb0c1f14ced9e
COPY --from=build /src/query-api.jar /app/query-api.jar
USER nonroot:nonroot
# The base image's entrypoint is java -jar.
CMD ["/app/query-api.jar"]

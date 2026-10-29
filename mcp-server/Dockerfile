# Build from the repository root so the parent pom is in the context:
#   docker build -f mcp-server/Dockerfile -t spaceflux-mcp-server .
# Tests are not run here; ./mvnw verify runs them.
FROM maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS build
WORKDIR /src
COPY pom.xml ./
COPY kafka-contracts/pom.xml kafka-contracts/
COPY orbit-core/pom.xml orbit-core/
COPY risk-engine/pom.xml risk-engine/
COPY db-migrate/pom.xml db-migrate/
COPY query-api/pom.xml query-api/
COPY mcp-server/pom.xml mcp-server/
RUN mvn -B -q -pl mcp-server -am dependency:go-offline
COPY mcp-server/src/main/ mcp-server/src/main/
RUN mvn -B -q -pl mcp-server -am -DskipTests package \
 && cp mcp-server/target/mcp-server-*.jar /src/mcp-server.jar

FROM gcr.io/distroless/java21-debian13:nonroot@sha256:0a1f5a75661918de9c0813f287f651c3bf2d6dd752eada5f084eb0c1f14ced9e
COPY --from=build /src/mcp-server.jar /app/mcp-server.jar
USER nonroot:nonroot
# The base image's entrypoint is java -jar.
CMD ["/app/mcp-server.jar"]

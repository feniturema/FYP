# syntax=docker/dockerfile:1.7
FROM maven:3.9.11-eclipse-temurin-21-noble AS build
WORKDIR /src
COPY pom.xml ./
COPY catalog-core/pom.xml catalog-core/
# The root reactor lists every module, so each module pom must exist even when only mcp-server is built.
COPY backend/pom.xml backend/
COPY mcp-server/pom.xml mcp-server/
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -pl mcp-server -am dependency:go-offline || true
COPY catalog-core/src catalog-core/src
COPY mcp-server/src mcp-server/src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -pl mcp-server -am -DskipTests package

FROM eclipse-temurin:21.0.12.1_1-jre-noble
# curl: compose healthcheck (GET /actuator/health)
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/mcp-server/target/mcp-server-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8081
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]

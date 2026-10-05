# syntax=docker/dockerfile:1.7
FROM maven:3.9.11-eclipse-temurin-21-noble AS build
WORKDIR /src
COPY pom.xml ./
COPY catalog-core/pom.xml catalog-core/
COPY backend/pom.xml backend/
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -pl backend -am dependency:go-offline || true
COPY catalog-core/src catalog-core/src
COPY backend/src backend/src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -pl backend -am -DskipTests package

FROM eclipse-temurin:21.0.12.1_1-jre-noble
WORKDIR /app
COPY --from=build /src/backend/target/ecommerce-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]

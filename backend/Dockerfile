# ---- build stage ----
# JDK 21 is required (see CHANGELOG v0.6.1 note & pom.xml).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -e dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

# ---- runtime stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
# Constrain JVM heap so backend + MySQL + Redis fit on a 2–4GB server.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50.0", "-jar", "app.jar"]

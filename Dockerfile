# Multi-stage build for Spring Boot application
# Stage 1: Build the application using Maven
FROM maven:3-eclipse-temurin-24 AS build
WORKDIR /app
COPY pom.xml .
# Pre-download dependencies (cache layer)
RUN mvn dependency:go-offline -B
COPY src src
RUN mvn clean package -DskipTests

# Stage 2: Run the application with a lightweight JRE (Alpine includes wget for the healthcheck)
FROM eclipse-temurin:21-jre-alpine
# Upgrade OS packages: base-image tags lag behind alpine security fixes (libexpat, p11-kit, ...)
RUN apk upgrade --no-cache
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s CMD wget -qO- http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]

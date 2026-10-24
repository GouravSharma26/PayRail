# Multi-stage Dockerfile for PayRail backend
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

# Copy build wrappers and descriptors
COPY mvnw mvnw.cmd pom.xml ./
COPY .mvn .mvn
COPY backend/pom.xml backend/

# Copy sources and package JAR (skipping tests since tests run in CI/pre-commit)
COPY backend/src backend/src
RUN chmod +x mvnw && ./mvnw clean package -DskipTests -pl backend

# Runtime container using minimal JRE 21
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Run as non-root user for security
RUN addgroup -S payrail && adduser -S payrail -G payrail
USER payrail

COPY --from=builder /workspace/backend/target/payrail-backend-*.jar app.jar

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=5s --start-period=25s --retries=3 \
  CMD wget -q -O /dev/null http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]

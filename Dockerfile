# =============================================================================
# Multi-stage Dockerfile for ResortsLite (Spring Boot 2.7.x / Java 8)
# Builder : maven:3.9.4-eclipse-temurin-8
# Runtime : amazoncorretto:8  (explicit base image)
# =============================================================================

# ---------------------------------------------------------------------------
# Stage 1 – Build
# ---------------------------------------------------------------------------
FROM maven:3.9.4-eclipse-temurin-8 AS builder

WORKDIR /workspace

# Copy dependency manifests first for layer-cache efficiency
COPY pom.xml .

# Pre-download all dependencies (cached unless pom.xml changes)
RUN mvn dependency:go-offline -B

# Copy the rest of the source tree
COPY src ./src

# Build the fat JAR, skip tests (tests run in CI pipeline)
RUN mvn clean package -DskipTests -B

# ---------------------------------------------------------------------------
# Stage 2 – Runtime
# ---------------------------------------------------------------------------
FROM amazoncorretto:8

# Metadata
LABEL maintainer="ResortsLite Team" \
      application="resortsLite" \
      version="1.0.0" \
      description="ResortsLite Spring Boot application"

# Timezone
ENV TZ=UTC

# JVM tuning – container-aware heap sizing
ENV JAVA_OPTS="-Xms256m -Xmx512m \
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+UseG1GC \
  -Djava.security.egd=file:/dev/./urandom \
  -Dfile.encoding=UTF-8 \
  -Duser.timezone=UTC"

# Spring profile
ENV SPRING_PROFILES_ACTIVE=docker

# Application port
EXPOSE 8080

# Create a non-root user for security
RUN groupadd -r appgroup && useradd -r -g appgroup -d /app -s /sbin/nologin appuser

WORKDIR /app

# Copy the executable JAR from the builder stage
COPY --from=builder /workspace/target/*.jar app.jar

# Ensure the non-root user owns the application directory
RUN chown -R appuser:appgroup /app

USER appuser

# Graceful shutdown via SIGTERM
STOPSIGNAL SIGTERM

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

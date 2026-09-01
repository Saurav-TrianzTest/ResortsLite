# =============================================================================
# Multi-stage Dockerfile for ResortsLite Spring Boot Application
# Java 8 / Spring Boot 2.7.x / Maven
# =============================================================================

# -----------------------------------------------------------------------------
# Stage 1: Builder
# -----------------------------------------------------------------------------
FROM maven:3.9.4-eclipse-temurin-8 AS builder

WORKDIR /workspace

# Copy dependency descriptor first for layer caching
COPY pom.xml .

# Download all dependencies offline (cached layer unless pom.xml changes)
RUN mvn dependency:go-offline -B

# Copy the full source tree
COPY src ./src

# Build the fat JAR (skip tests in Docker build)
RUN mvn clean package -DskipTests -B

# -----------------------------------------------------------------------------
# Stage 2: Runtime
# Explicit base image provided: eclipse-temurin:8-jdk
# -----------------------------------------------------------------------------
FROM eclipse-temurin:8-jdk

# Metadata
LABEL maintainer="ResortsLite Team" \
      application="resortslite" \
      version="1.0.0" \
      description="ResortsLite Spring Boot Application"

# Timezone configuration
ENV TZ=UTC

# Create a non-root user for security
RUN groupadd --system appgroup && \
    useradd --system --gid appgroup --home /app --shell /bin/false appuser

WORKDIR /app

# Copy the built JAR from the builder stage
COPY --from=builder /workspace/target/*.jar app.jar

# Set ownership
RUN chown -R appuser:appgroup /app

# Switch to non-root user
USER appuser

# Application port (from application.properties: server.port=8080)
EXPOSE 8080

# JVM options optimised for containerised environments
ENV JAVA_OPTS="-Xmx512m -Xms256m \
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+UnlockExperimentalVMOptions \
  -Djava.security.egd=file:/dev/./urandom \
  -Dfile.encoding=UTF-8 \
  -Duser.timezone=UTC"

# Spring profile for containerised deployments
ENV SPRING_PROFILES_ACTIVE=docker

# Graceful shutdown support (Spring Boot 2.3+)
ENV SERVER_SHUTDOWN=graceful

# Entrypoint — use exec form for proper signal handling (SIGTERM → graceful shutdown)
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

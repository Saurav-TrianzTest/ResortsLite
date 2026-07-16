# =============================================================================
# ResortsLite - Multi-Stage Dockerfile
# Framework : Spring Boot 2.7.18
# Java      : 8
# Build Tool: Maven
# =============================================================================

# ---------------------------------------------------------------------------
# Stage 1: Builder
# ---------------------------------------------------------------------------
FROM maven:3.9.4-eclipse-temurin-8 AS builder

WORKDIR /workspace

# Copy dependency descriptor first for layer caching
COPY pom.xml .

# Download all dependencies (cached layer unless pom.xml changes)
RUN mvn dependency:go-offline -B

# Copy the full source tree
COPY src ./src

# Build the application JAR (skip tests for Docker build)
RUN mvn clean package -DskipTests -B

# ---------------------------------------------------------------------------
# Stage 2: Runtime
# ---------------------------------------------------------------------------
FROM eclipse-temurin:8-jdk

# Metadata
LABEL maintainer="ResortsLite Team" \
      application="resortslite" \
      version="1.0.0" \
      framework="spring-boot-2.7.18"

# Timezone configuration
ENV TZ=UTC

# JVM tuning for containerised environments
ENV JAVA_OPTS="-Xmx512m -Xms256m \
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+UseG1GC \
  -Djava.security.egd=file:/dev/./urandom \
  -Dfile.encoding=UTF-8 \
  -Duser.timezone=UTC"

# Spring profile
ENV SPRING_PROFILES_ACTIVE=docker

# Application environment variables (override at runtime)
ENV SERVER_PORT=8080
ENV REPORT_BASE_PATH=/tmp/reports
ENV BACKUP_PATH=/tmp/backups
ENV PAYMENT_API_URL=http://payment-service:9090/payments/charge
ENV CACHE_ENABLED=false

WORKDIR /app

# Create non-root user for security
RUN groupadd -r appgroup && useradd -r -g appgroup -s /bin/false appuser

# Copy the fat JAR from builder stage
COPY --from=builder /workspace/target/*.jar app.jar

# Create directories for reports and logs
RUN mkdir -p /tmp/reports /tmp/backups /app/logs \
    && chown -R appuser:appgroup /app /tmp/reports /tmp/backups

# Switch to non-root user
USER appuser

# Expose application port
EXPOSE 8080

# Graceful shutdown support (Spring Boot handles SIGTERM)
STOPSIGNAL SIGTERM

# Start the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]

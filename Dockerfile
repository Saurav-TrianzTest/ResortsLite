# ============================================================
# Stage 1: Builder
# ============================================================
FROM maven:3.8.6-openjdk-8-slim AS builder

WORKDIR /workspace

# Copy dependency descriptor first for layer caching
COPY pom.xml .

# Download all dependencies (cached layer unless pom.xml changes)
RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Build the application JAR (skip tests for Docker build)
RUN mvn clean package -DskipTests -B

# ============================================================
# Stage 2: Runtime
# ============================================================
FROM amazoncorretto:8

# Set working directory
WORKDIR /app

# Create non-root user for security
RUN groupadd -r appgroup && useradd -r -g appgroup -s /bin/false appuser

# Set timezone
ENV TZ=UTC

# JVM tuning for containerized environments
ENV JAVA_OPTS="-Xmx512m -Xms256m \
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -Djava.security.egd=file:/dev/./urandom \
  -Dfile.encoding=UTF-8 \
  -Duser.timezone=UTC"

# Spring Boot profile
ENV SPRING_PROFILES_ACTIVE=docker

# Application environment variables (override at runtime)
ENV SERVER_PORT=8080
ENV REDIS_HOST=localhost
ENV REDIS_PORT=6379
ENV REPORT_BASE_PATH=/var/reports
ENV BACKUP_PATH=/var/backups
ENV CACHE_TTL_SECONDS=3600
ENV PAYMENT_API_URL=http://payment-svc.payments.svc.cluster.local:9090/payments/charge

# Create directories for reports and backups
RUN mkdir -p /var/reports /var/backups && \
    chown -R appuser:appgroup /app /var/reports /var/backups

# Copy the built JAR from builder stage
COPY --from=builder /workspace/target/*.jar app.jar

# Set ownership
RUN chown appuser:appgroup app.jar

# Switch to non-root user
USER appuser

# Expose application port
EXPOSE 8080

# Entry point with JVM options
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]

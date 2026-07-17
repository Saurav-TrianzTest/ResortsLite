# ============================================================
# Stage 1: Builder
# ============================================================
FROM maven:3.8.6-openjdk-8-slim AS builder

WORKDIR /workspace

# Copy dependency descriptor first for layer caching
COPY pom.xml .

# Download all dependencies (cached layer)
RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Build the application JAR (skip tests for Docker build)
RUN mvn clean package -DskipTests -B

# ============================================================
# Stage 2: Runtime
# ============================================================
FROM eclipse-temurin:8-jdk

WORKDIR /app

# Create non-root user for security
RUN groupadd --system appgroup && useradd --system --gid appgroup appuser

# Copy the built JAR from builder stage
COPY --from=builder /workspace/target/*.jar app.jar

# Set ownership
RUN chown -R appuser:appgroup /app

# Create directories for reports and backups (mapped via volumes/env vars)
RUN mkdir -p /reports /backups/nightly && chown -R appuser:appgroup /reports /backups/nightly

USER appuser

# Application port
EXPOSE 8080

# JVM environment variables
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UnlockExperimentalVMOptions -Dfile.encoding=UTF-8 -Duser.timezone=UTC"
ENV SPRING_PROFILES_ACTIVE=docker
ENV SERVER_PORT=8080
ENV REPORT_BASE_PATH=/reports
ENV BACKUP_PATH=/backups/nightly

# Start the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

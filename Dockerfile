# Multi-stage Dockerfile for ResortsLite Spring Boot Application
# Stage 1: Build stage using Maven and Java 8
FROM maven:3.9.4-eclipse-temurin-8 AS builder

# Set working directory
WORKDIR /workspace

# Copy pom.xml first for dependency caching
COPY pom.xml .

# Download dependencies (cached layer)
RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Build the application (skip tests for faster builds)
RUN mvn clean package -DskipTests

# Stage 2: Runtime stage using Java 8 JRE
FROM eclipse-temurin:8-jre

# Set working directory
WORKDIR /app

# Create non-root user for security
RUN groupadd -r appuser && useradd -r -g appuser appuser

# Copy the built JAR from builder stage
COPY --from=builder /workspace/target/*.jar app.jar

# Change ownership to non-root user
RUN chown -R appuser:appuser /app

# Switch to non-root user
USER appuser

# Set JVM options for containerized environment
ENV JAVA_OPTS="-Xmx512m -Xms256m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

# Expose application port
EXPOSE 8080

# Set Spring profile for Docker environment
ENV SPRING_PROFILES_ACTIVE=docker

# Configure timezone
ENV TZ=UTC

# Run the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

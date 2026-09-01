# ResortsLite — AWS ECS Fargate Deployment Guide

## Table of Contents
1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Project Structure](#project-structure)
4. [Local Development with Docker Compose](#local-development-with-docker-compose)
5. [Build and Push Docker Image](#build-and-push-docker-image)
6. [AWS ECS Fargate Prerequisites](#aws-ecs-fargate-prerequisites)
7. [ECS Task Definition Explained](#ecs-task-definition-explained)
8. [ECS Service Configuration](#ecs-service-configuration)
9. [ECS Fargate Deployment Walkthrough](#ecs-fargate-deployment-walkthrough)
10. [ECS-Specific Troubleshooting](#ecs-specific-troubleshooting)
11. [ECS Fargate Scaling and Management](#ecs-fargate-scaling-and-management)
12. [Configuration Management](#configuration-management)
13. [Security Considerations](#security-considerations)
14. [Java-Specific Notes](#java-specific-notes)

---

## Overview

**Application**: ResortsLite  
**Framework**: Spring Boot 2.7.18  
**Java Version**: 8  
**Build Tool**: Maven  
**Package Type**: JAR (executable fat JAR)  
**Application Port**: 8080  
**Health Endpoint**: `/actuator/health`  
**Target Platform**: AWS ECS Fargate  

ResortsLite is a Spring Boot REST API for resort booking management. It uses stateless JWT authentication, Spring Boot Actuator for health monitoring, and is designed for horizontal scaling on AWS ECS Fargate.

---

## Prerequisites

### Local Development
- Docker Desktop 24.x or later
- Docker Compose v2.x or later
- Java 8 JDK (for local builds outside Docker)
- Maven 3.9.x (for local builds outside Docker)

### AWS Deployment
- AWS CLI v2 configured with appropriate credentials (`aws configure`)
- IAM permissions for: ECS, ECR, IAM, CloudWatch Logs, ELBv2, EC2
- An existing AWS VPC with at least 2 subnets (for high availability)
- Security group allowing inbound TCP on port 8080 (or 80 via ALB)

---

## Project Structure

```
MContMonoJavaApp/
├── Dockerfile                    # Multi-stage Docker build
├── .dockerignore                 # Files excluded from build context
├── docker-compose.yml            # Local development compose file
├── pom.xml                       # Maven build descriptor
├── src/
│   └── main/
│       ├── java/com/demo/resortslite/
│       │   ├── ResortsLiteApplication.java
│       │   ├── BookingController.java
│       │   ├── BookingService.java
│       │   ├── JwtUtil.java
│       │   └── ReportService.java
│       └── resources/
│           └── application.properties
├── ecs/
│   ├── task-definition.json      # ECS Fargate task definition
│   └── service-definition.json   # ECS service definition
├── scripts/
│   ├── build-push.sh             # Linux/macOS build & push script
│   ├── build-push.bat            # Windows build & push script
│   ├── deploy-image.sh           # Linux/macOS ECS deploy script
│   └── deploy-image.bat          # Windows ECS deploy script
└── docs/
    └── DEPLOYMENT.md             # This file
```

---

## Local Development with Docker Compose

### 1. Build and start the application

```bash
# From the project root
docker compose up --build
```

### 2. Verify the application is running

```bash
# Health check
curl http://localhost:8080/actuator/health

# Create a booking
curl -X POST "http://localhost:8080/api/bookings/create?guestName=John&roomType=STANDARD&checkIn=2024-06-01&checkOut=2024-06-05"

# Check availability
curl "http://localhost:8080/api/bookings/availability?roomType=DELUXE"
```

### 3. View logs

```bash
docker compose logs -f resortslite
```

### 4. Stop the application

```bash
docker compose down
```

### Environment Variables (docker-compose.yml)

| Variable | Default | Description |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `docker` | Active Spring profile |
| `JAVA_OPTS` | `-Xmx512m -Xms256m ...` | JVM options |
| `JWT_SECRET` | `change-me-in-production` | JWT signing secret |
| `PAYMENT_SERVICE_URL` | `http://payment-service:9090` | Payment service URL |
| `INVENTORY_SERVICE_URL` | `http://inventory-service:8081` | Inventory service URL |
| `MEMCACHED_ENDPOINT` | `localhost:11211` | Memcached endpoint |

---

## Build and Push Docker Image

### Linux/macOS

```bash
chmod +x scripts/build-push.sh
./scripts/build-push.sh
```

### Windows

```cmd
scripts\build-push.bat
```

The script will prompt you to:
1. Select registry type (AWS ECR or Docker Hub)
2. Enter registry credentials and details
3. Specify an image tag (defaults to `latest`)

The script automatically:
- Sanitizes the image name (lowercase, hyphens)
- Creates the ECR repository if it doesn't exist
- Builds the Docker image from the project root
- Pushes the image to the selected registry

### Manual Build

```bash
# Build
docker build -t resortslite:latest .

# Tag for ECR
docker tag resortslite:latest 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest

# Push to ECR
aws ecr get-login-password --region us-east-1 | \
  docker login --username AWS --password-stdin 123456789.dkr.ecr.us-east-1.amazonaws.com
docker push 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest
```

---

## AWS ECS Fargate Prerequisites

### 1. AWS CLI Configuration

```bash
aws configure
# Enter: AWS Access Key ID, Secret Access Key, Region, Output format
```

### 2. VPC and Networking

Ensure you have:
- A VPC with DNS resolution enabled
- At least **2 public or private subnets** in different Availability Zones
- A security group with inbound rules:
  - TCP port 8080 from ALB security group (or 0.0.0.0/0 for testing)
  - TCP port 80 on ALB security group from 0.0.0.0/0

### 3. IAM Roles

#### ECS Task Execution Role (`ecsTaskExecutionRole`)
This role allows ECS to pull images from ECR and write logs to CloudWatch.

```bash
# Create the role
aws iam create-role \
  --role-name ecsTaskExecutionRole \
  --assume-role-policy-document '{
    "Version": "2012-10-17",
    "Statement": [{
      "Effect": "Allow",
      "Principal": {"Service": "ecs-tasks.amazonaws.com"},
      "Action": "sts:AssumeRole"
    }]
  }'

# Attach the managed policy
aws iam attach-role-policy \
  --role-name ecsTaskExecutionRole \
  --policy-arn arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy

# Add Secrets Manager access (for JWT_SECRET)
aws iam attach-role-policy \
  --role-name ecsTaskExecutionRole \
  --policy-arn arn:aws:iam::aws:policy/SecretsManagerReadWrite
```

#### ECS Task Role (`ecsTaskRole`)
This role grants the application container permissions to call AWS services.

```bash
aws iam create-role \
  --role-name ecsTaskRole \
  --assume-role-policy-document '{
    "Version": "2012-10-17",
    "Statement": [{
      "Effect": "Allow",
      "Principal": {"Service": "ecs-tasks.amazonaws.com"},
      "Action": "sts:AssumeRole"
    }]
  }'
```

### 4. AWS Secrets Manager — JWT Secret

```bash
aws secretsmanager create-secret \
  --name resortslite/jwt-secret \
  --secret-string "your-strong-jwt-secret-at-least-32-chars" \
  --region us-east-1
```

### 5. CloudWatch Log Group

```bash
aws logs create-log-group \
  --log-group-name /ecs/resortslite \
  --region us-east-1
```

---

## ECS Task Definition Explained

The task definition (`ecs/task-definition.json`) configures how the container runs on Fargate.

### Key Fields

| Field | Value | Description |
|---|---|---|
| `family` | `resortslite-task` | Task definition family name |
| `requiresCompatibilities` | `["FARGATE"]` | Must be FARGATE for serverless containers |
| `networkMode` | `awsvpc` | Required for Fargate; each task gets its own ENI |
| `cpu` | `"512"` | 0.5 vCPU |
| `memory` | `"1024"` | 1 GB RAM |
| `executionRoleArn` | `ecsTaskExecutionRole` | Allows ECS to pull images and write logs |
| `taskRoleArn` | `ecsTaskRole` | Grants the app container AWS permissions |

### Valid Fargate CPU/Memory Combinations

| CPU | Valid Memory Values |
|---|---|
| 256 (.25 vCPU) | 512, 1024, 2048 MB |
| **512 (.5 vCPU)** | **1024, 2048, 3072, 4096 MB** ← Used |
| 1024 (1 vCPU) | 2048–8192 MB |
| 2048 (2 vCPU) | 4096–16384 MB |
| 4096 (4 vCPU) | 8192–30720 MB |

### Container Definition

- **Port mapping**: Container port 8080 (TCP)
- **Environment variables**: Spring profile, JVM options, service URLs
- **Secrets**: `JWT_SECRET` sourced from AWS Secrets Manager
- **Logging**: CloudWatch Logs via `awslogs` driver → `/ecs/resortslite`

---

## ECS Service Configuration

The service definition (`ecs/service-definition.json`) controls how tasks are scheduled and networked.

### Key Fields

| Field | Value | Description |
|---|---|---|
| `launchType` | `FARGATE` | Serverless container execution |
| `desiredCount` | `2` | Number of running task replicas |
| `networkMode` | `awsvpc` | Each task gets its own private IP |
| `assignPublicIp` | `ENABLED` | Required for public subnet tasks to pull images |
| `maximumPercent` | `200` | Allow 2x tasks during rolling deployment |
| `minimumHealthyPercent` | `50` | Keep at least 1 task running during deployment |

### Networking

Tasks run in `awsvpc` mode — each task gets its own Elastic Network Interface (ENI) with a private IP. For internet access (ECR image pull, external APIs), tasks need either:
- Public subnets with `assignPublicIp: ENABLED`, OR
- Private subnets with a NAT Gateway

---

## ECS Fargate Deployment Walkthrough

### Step 1: Build and push the image

```bash
./scripts/build-push.sh
# Note the full image URI output (e.g., 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest)
```

### Step 2: Run the deployment script

```bash
chmod +x scripts/deploy-image.sh
./scripts/deploy-image.sh
```

The script will prompt for:
- AWS Region
- ECS Cluster name
- ECR Image URI
- VPC ID
- Subnet IDs (comma-separated)
- Security Group ID
- Whether to create an Application Load Balancer

### Step 3: Verify the deployment

```bash
# Check service status
aws ecs describe-services \
  --cluster resortslite-cluster \
  --services resortslite-service \
  --region us-east-1

# List running tasks
aws ecs list-tasks \
  --cluster resortslite-cluster \
  --service-name resortslite-service \
  --region us-east-1

# View application logs
aws logs tail /ecs/resortslite --follow --region us-east-1
```

### Step 4: Test the application

```bash
# If using ALB
curl http://<ALB_DNS>/actuator/health

# If accessing task directly (requires public IP)
TASK_ARN=$(aws ecs list-tasks --cluster resortslite-cluster --service-name resortslite-service --query "taskArns[0]" --output text)
TASK_IP=$(aws ecs describe-tasks --cluster resortslite-cluster --tasks $TASK_ARN --query "tasks[0].attachments[0].details[?name=='privateIPv4Address'].value" --output text)
curl http://$TASK_IP:8080/actuator/health
```

### Step 5: Update the deployment (rolling update)

```bash
# After building and pushing a new image
./scripts/deploy-image.sh
# The script detects the existing service and performs a rolling update
```

---

## ECS-Specific Troubleshooting

### Task fails to start

```bash
# Check stopped task reason
aws ecs describe-tasks \
  --cluster resortslite-cluster \
  --tasks <TASK_ARN> \
  --region us-east-1 \
  --query "tasks[0].{Status:lastStatus,StopCode:stopCode,StopReason:stoppedReason}"
```

**Common causes:**
- `CannotPullContainerError`: ECR login failed or image URI incorrect → verify `executionRoleArn` has ECR permissions
- `OutOfMemoryError`: Increase task memory in `task-definition.json`
- `PortBindingError`: Port 8080 already in use → check security group rules

### Container exits immediately

```bash
# View CloudWatch logs
aws logs get-log-events \
  --log-group-name /ecs/resortslite \
  --log-stream-name ecs/resortslite/<TASK_ID> \
  --region us-east-1
```

**Common causes:**
- Missing environment variables (e.g., `JWT_SECRET` not in Secrets Manager)
- JVM out of memory → increase `memory` in task definition or reduce `-Xmx`
- Application startup failure → check Spring Boot logs for bean creation errors

### Service stuck in PENDING

```bash
# Check service events
aws ecs describe-services \
  --cluster resortslite-cluster \
  --services resortslite-service \
  --region us-east-1 \
  --query "services[0].events[:5]"
```

**Common causes:**
- No available ENIs in subnet → use subnets with available IP addresses
- Security group blocking traffic → verify inbound rules on port 8080
- Insufficient Fargate capacity → try a different Availability Zone

### Health check failures

```bash
# Check ALB target health
aws elbv2 describe-target-health \
  --target-group-arn <TARGET_GROUP_ARN> \
  --region us-east-1
```

**Common causes:**
- JVM startup time exceeds health check grace period → increase `healthCheckGracePeriodSeconds` to 300+
- `/actuator/health` returns non-200 → check application logs for dependency failures
- Security group blocks ALB → ensure ALB security group can reach task on port 8080

### Invalid CPU/Memory combination

```
ClientException: The CPU value must be one of [256, 512, 1024, 2048, 4096]
```

Edit `ecs/task-definition.json` and use a valid combination (see table above).

---

## ECS Fargate Scaling and Management

### Manual scaling

```bash
aws ecs update-service \
  --cluster resortslite-cluster \
  --service resortslite-service \
  --desired-count 4 \
  --region us-east-1
```

### Auto Scaling

```bash
# Register scalable target
aws application-autoscaling register-scalable-target \
  --service-namespace ecs \
  --resource-id service/resortslite-cluster/resortslite-service \
  --scalable-dimension ecs:service:DesiredCount \
  --min-capacity 2 \
  --max-capacity 10

# Create CPU-based scaling policy
aws application-autoscaling put-scaling-policy \
  --service-namespace ecs \
  --resource-id service/resortslite-cluster/resortslite-service \
  --scalable-dimension ecs:service:DesiredCount \
  --policy-name resortslite-cpu-scaling \
  --policy-type TargetTrackingScaling \
  --target-tracking-scaling-policy-configuration '{
    "TargetValue": 70.0,
    "PredefinedMetricSpecification": {
      "PredefinedMetricType": "ECSServiceAverageCPUUtilization"
    },
    "ScaleInCooldown": 300,
    "ScaleOutCooldown": 60
  }'
```

### Blue/Green Deployment with CodeDeploy

For zero-downtime deployments, configure AWS CodeDeploy with ECS:

1. Create a CodeDeploy application and deployment group for ECS
2. Set `deploymentController.type: CODE_DEPLOY` in the service definition
3. Use `appspec.yaml` to define the deployment lifecycle
4. CodeDeploy will shift traffic from blue (current) to green (new) tasks

---

## Configuration Management

### Environment Variables Priority

Spring Boot resolves configuration in this order (highest to lowest):
1. Environment variables (set in ECS task definition)
2. `application-docker.properties` (if profile `docker` is active)
3. `application.properties`

### Adding New Environment Variables

1. Add to `ecs/task-definition.json` under `environment` or `secrets`
2. For sensitive values, use AWS Secrets Manager:
   ```json
   {
     "name": "MY_SECRET",
     "valueFrom": "arn:aws:secretsmanager:us-east-1:123456789:secret:resortslite/my-secret"
   }
   ```
3. For non-sensitive config, use AWS SSM Parameter Store:
   ```json
   {
     "name": "MY_PARAM",
     "valueFrom": "arn:aws:ssm:us-east-1:123456789:parameter/resortslite/my-param"
   }
   ```

### Spring Profiles

The application uses `SPRING_PROFILES_ACTIVE=docker` in containers. To add a Docker-specific configuration:

```properties
# src/main/resources/application-docker.properties
logging.level.com.demo.resortslite=INFO
spring.h2.console.enabled=false
```

---

## Security Considerations

### Container Security
- The container runs as a **non-root user** (`appuser`) — never run as root in production
- No unnecessary packages are installed in the runtime image
- The runtime image uses `eclipse-temurin:8-jdk` — consider switching to `eclipse-temurin:8-jre` for a smaller attack surface

### Secrets Management
- **JWT_SECRET** must be stored in AWS Secrets Manager, never hardcoded
- Database credentials should use AWS Secrets Manager or RDS IAM authentication
- Rotate secrets regularly using Secrets Manager rotation policies

### Network Security
- Use private subnets with NAT Gateway for production workloads
- Restrict security group inbound rules to ALB only (not 0.0.0.0/0)
- Enable VPC Flow Logs for network traffic auditing
- Use AWS WAF with the ALB to protect against common web attacks

### Image Security
- Scan ECR images with Amazon Inspector or Trivy
- Enable ECR image scanning on push:
  ```bash
  aws ecr put-image-scanning-configuration \
    --repository-name resortslite \
    --image-scanning-configuration scanOnPush=true
  ```
- Use immutable image tags in production (avoid `latest`)

### IAM Least Privilege
- The `ecsTaskRole` should only have permissions the application actually needs
- Avoid using `AdministratorAccess` or wildcard resource policies
- Use resource-based policies to restrict Secrets Manager access to specific secrets

---

## Java-Specific Notes

### JVM Memory Configuration

The container is configured with:
```
-Xmx512m          # Maximum heap size (matches ~50% of 1024MB task memory)
-Xms256m          # Initial heap size
-XX:+UseContainerSupport    # JVM respects cgroup memory limits
-XX:MaxRAMPercentage=75.0   # Use up to 75% of container RAM for heap
```

For a 1024 MB Fargate task:
- JVM heap: up to 768 MB (75%)
- Non-heap (Metaspace, threads, etc.): ~256 MB
- If you see `OutOfMemoryError`, increase task memory to 2048 MB

### Spring Boot Startup Time

Java 8 + Spring Boot 2.7 typically starts in 5–15 seconds. The ECS health check grace period is set to 60 seconds in docker-compose and 300 seconds for ALB target groups to accommodate JVM warm-up.

### Graceful Shutdown

The application is configured with `SERVER_SHUTDOWN=graceful`. When ECS sends `SIGTERM` (during task replacement), Spring Boot will:
1. Stop accepting new requests
2. Complete in-flight requests (up to 30 seconds)
3. Shut down cleanly

The Dockerfile uses `exec java ...` to ensure the JVM receives signals directly.

### H2 In-Memory Database

The current configuration uses H2 in-memory database (`jdbc:h2:mem:resortdb`). This is suitable for development but **not for production**. For production:
1. Replace with Amazon RDS (PostgreSQL or MySQL)
2. Store credentials in AWS Secrets Manager
3. Update `spring.datasource.*` environment variables in the task definition

### Log4j Security Note

The `pom.xml` includes `log4j-core:2.14.1` which has the critical Log4Shell vulnerability (CVE-2021-44228). **Upgrade to log4j-core:2.17.2 or later** before deploying to production.

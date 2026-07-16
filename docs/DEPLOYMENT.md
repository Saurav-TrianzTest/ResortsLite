# ResortsLite — AWS ECS Fargate Deployment Guide

## Table of Contents
1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Local Development with Docker Compose](#local-development-with-docker-compose)
4. [Build and Push Docker Image](#build-and-push-docker-image)
5. [AWS ECS Fargate Prerequisites](#aws-ecs-fargate-prerequisites)
6. [ECS Task Definition Explained](#ecs-task-definition-explained)
7. [ECS Service Configuration](#ecs-service-configuration)
8. [ECS Fargate Deployment Walkthrough](#ecs-fargate-deployment-walkthrough)
9. [ECS-Specific Troubleshooting](#ecs-specific-troubleshooting)
10. [ECS Fargate Scaling and Management](#ecs-fargate-scaling-and-management)
11. [Configuration Management](#configuration-management)
12. [Security Considerations](#security-considerations)
13. [Java-Specific Notes](#java-specific-notes)

---

## Overview

**Application**: ResortsLite  
**Framework**: Spring Boot 2.7.18  
**Java Version**: 8  
**Build Tool**: Maven  
**Application Port**: 8080  
**Health Endpoint**: `/actuator/health`  
**Target Platform**: AWS ECS Fargate  

ResortsLite is a resort booking REST API built with Spring Boot. This guide covers containerising the application and deploying it to AWS ECS Fargate.

---

## Prerequisites

### Local Development
| Tool | Version | Purpose |
|------|---------|---------|
| Docker Desktop | 24.x+ | Build and run containers |
| Docker Compose | 2.x+ | Local multi-container orchestration |
| Java JDK | 8+ | Local development (optional) |
| Maven | 3.9.x+ | Local builds (optional) |

### AWS Deployment
| Tool | Version | Purpose |
|------|---------|---------|
| AWS CLI | 2.x | Interact with AWS services |
| Docker | 24.x+ | Build and push images |
| Python 3 | 3.8+ | Used by deploy-image.sh for JSON manipulation |

---

## Local Development with Docker Compose

### 1. Build and start the application

```bash
# From the repository root
docker compose up --build
```

### 2. Verify the application is running

```bash
curl http://localhost:8080/actuator/health
```

Expected response:
```json
{"status":"UP"}
```

### 3. Test the booking API

```bash
# Create a booking
curl -X POST "http://localhost:8080/api/bookings/create?guestName=John&roomType=SUITE&checkIn=2024-06-01&checkOut=2024-06-05"

# Check availability
curl "http://localhost:8080/api/bookings/availability?roomType=DELUXE"
```

### 4. Override environment variables

Create a `.env` file in the project root:

```env
PAYMENT_API_URL=http://your-payment-service:9090/payments/charge
APP_INVENTORY_ENDPOINT=http://your-inventory-service:8081/rooms
REPORT_BASE_PATH=/data/reports
```

Then run:
```bash
docker compose --env-file .env up
```

### 5. Stop the application

```bash
docker compose down
```

---

## Build and Push Docker Image

### Linux / macOS

```bash
chmod +x scripts/build-push.sh
bash scripts/build-push.sh
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
- Sanitises the image name (lowercase, hyphens)
- Creates the ECR repository if it does not exist
- Builds the Docker image from the repository root
- Pushes the image to the selected registry

---

## AWS ECS Fargate Prerequisites

### 1. AWS CLI Configuration

```bash
aws configure
# Enter: AWS Access Key ID, Secret Access Key, Region, Output format
```

### 2. VPC and Networking

Ensure you have:
- A VPC with at least **2 public or private subnets** in different Availability Zones
- A **Security Group** that allows:
  - Inbound TCP on port **8080** (from ALB or direct access)
  - Outbound TCP on port **443** (for ECR image pull)
  - Outbound TCP on port **443** (for CloudWatch Logs)

```bash
# Example: Create a security group
aws ec2 create-security-group \
  --group-name resortslite-sg \
  --description "ResortsLite ECS Security Group" \
  --vpc-id vpc-xxxxxxxx

# Allow inbound on port 8080
aws ec2 authorize-security-group-ingress \
  --group-id sg-xxxxxxxx \
  --protocol tcp \
  --port 8080 \
  --cidr 0.0.0.0/0
```

### 3. IAM Roles

#### ECS Task Execution Role (required)
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
```

#### ECS Task Role (optional, for application AWS SDK calls)
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

### 4. CloudWatch Log Group

```bash
aws logs create-log-group \
  --log-group-name /ecs/resortslite \
  --region us-east-1
```

---

## ECS Task Definition Explained

The task definition (`ecs/task-definition.json`) configures how ECS runs the container.

### Key Fields

| Field | Value | Description |
|-------|-------|-------------|
| `family` | `resortslite-task` | Task definition family name |
| `requiresCompatibilities` | `["FARGATE"]` | Fargate launch type |
| `networkMode` | `awsvpc` | Required for Fargate |
| `cpu` | `"512"` | 0.5 vCPU |
| `memory` | `"1024"` | 1 GB RAM |
| `executionRoleArn` | `ecsTaskExecutionRole` | Allows ECR pull + CloudWatch logs |

### Valid Fargate CPU/Memory Combinations

| CPU | Valid Memory Values |
|-----|-------------------|
| 256 (.25 vCPU) | 512, 1024, 2048 MB |
| **512 (.5 vCPU)** | **1024, 2048, 3072, 4096 MB** ← Used |
| 1024 (1 vCPU) | 2048–8192 MB |
| 2048 (2 vCPU) | 4096–16384 MB |
| 4096 (4 vCPU) | 8192–30720 MB |

### Container Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `SPRING_PROFILES_ACTIVE` | `docker` | Spring profile |
| `SERVER_PORT` | `8080` | Application port |
| `JAVA_OPTS` | `-Xmx512m -Xms256m ...` | JVM settings |
| `REPORT_BASE_PATH` | `/tmp/reports` | Report output directory |
| `BACKUP_PATH` | `/tmp/backups` | Backup directory |
| `PAYMENT_API_URL` | `http://payment-service:9090/...` | Payment service URL |
| `CACHE_ENABLED` | `false` | Enable distributed cache |

---

## ECS Service Configuration

The service definition (`ecs/service-definition.json`) controls how ECS manages running tasks.

### Key Fields

| Field | Value | Description |
|-------|-------|-------------|
| `launchType` | `FARGATE` | Serverless compute |
| `desiredCount` | `2` | Number of running tasks |
| `assignPublicIp` | `ENABLED` | Required for public subnet access |
| `maximumPercent` | `200` | Allow 2x tasks during rolling deploy |
| `minimumHealthyPercent` | `50` | Keep at least 1 task running |

---

## ECS Fargate Deployment Walkthrough

### Step 1: Build and push the image

```bash
bash scripts/build-push.sh
# Select AWS ECR, enter your region and account ID
# Note the full image URI printed at the end
```

### Step 2: Run the deployment script

```bash
chmod +x scripts/deploy-image.sh
bash scripts/deploy-image.sh
```

You will be prompted for:
- AWS Region
- ECS Cluster name
- ECR Image URI (from Step 1)
- Subnet IDs (at least 2)
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

# View logs
aws logs tail /ecs/resortslite --follow --region us-east-1
```

### Step 4: Test the application

If using an ALB:
```bash
curl http://<ALB_DNS_NAME>/actuator/health
```

If using direct task IP (development only):
```bash
# Get task ENI IP
TASK_ARN=$(aws ecs list-tasks --cluster resortslite-cluster --service-name resortslite-service --query "taskArns[0]" --output text --region us-east-1)
aws ecs describe-tasks --cluster resortslite-cluster --tasks $TASK_ARN --region us-east-1 --query "tasks[0].attachments[0].details"
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
  --query "tasks[0].{StopCode:stopCode,StoppedReason:stoppedReason,Containers:containers[0].reason}"
```

Common causes:
- **ImagePullBackOff**: ECR permissions missing on `ecsTaskExecutionRole`
- **ResourceInitializationError**: VPC endpoints missing for ECR/CloudWatch
- **OutOfMemoryError**: Increase `memory` in task definition

### Container exits immediately

```bash
# View container logs
aws logs get-log-events \
  --log-group-name /ecs/resortslite \
  --log-stream-name "ecs/resortslite/<TASK_ID>" \
  --region us-east-1
```

Common causes:
- Missing required environment variables
- Database connection failure at startup
- Port conflict (check `SERVER_PORT`)

### Network connectivity issues

- Ensure Security Group allows **outbound** HTTPS (443) for ECR image pull
- For private subnets, create VPC endpoints for:
  - `com.amazonaws.<region>.ecr.api`
  - `com.amazonaws.<region>.ecr.dkr`
  - `com.amazonaws.<region>.logs`
  - `com.amazonaws.<region>.s3` (for ECR layer storage)

### CPU/Memory errors

```
INVALID: The provided CPU value is not valid
```
Use only valid Fargate combinations. Default: `cpu: "512"`, `memory: "1024"`.

### Service not stabilising

```bash
# Check service events
aws ecs describe-services \
  --cluster resortslite-cluster \
  --services resortslite-service \
  --region us-east-1 \
  --query "services[0].events[:5]"
```

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

1. Install the CodeDeploy agent (not required for ECS)
2. Create a CodeDeploy application and deployment group targeting the ECS service
3. Use `appspec.yaml` with `TaskDefinition` and `LoadBalancerInfo` sections
4. Trigger deployments via CodePipeline or CLI

### Rolling Update (default)

The service definition already configures rolling updates:
- `maximumPercent: 200` — allows double capacity during deployment
- `minimumHealthyPercent: 50` — keeps at least 1 task healthy

---

## Configuration Management

### Using AWS Systems Manager Parameter Store

Store sensitive values securely:

```bash
# Store payment API URL
aws ssm put-parameter \
  --name "/resortslite/prod/PAYMENT_API_URL" \
  --value "https://payment.example.com/charge" \
  --type SecureString \
  --region us-east-1
```

Reference in task definition:
```json
"secrets": [
  {
    "name": "PAYMENT_API_URL",
    "valueFrom": "arn:aws:ssm:us-east-1:ACCOUNT_ID:parameter/resortslite/prod/PAYMENT_API_URL"
  }
]
```

Add `ssm:GetParameters` permission to `ecsTaskExecutionRole`.

### Using AWS Secrets Manager

```bash
aws secretsmanager create-secret \
  --name "resortslite/prod/db-credentials" \
  --secret-string '{"username":"admin","password":"your-password"}' \
  --region us-east-1
```

---

## Security Considerations

1. **Non-root container**: The Dockerfile creates and uses a non-root `appuser` account
2. **No hardcoded secrets**: All credentials must be injected via environment variables or SSM/Secrets Manager
3. **Minimal base image**: Uses `eclipse-temurin:8-jdk` — consider switching to `eclipse-temurin:8-jre-alpine` for smaller attack surface in production
4. **Security Group**: Restrict inbound access to only required ports and CIDR ranges
5. **ECR image scanning**: Enable ECR image scanning on push:
   ```bash
   aws ecr put-image-scanning-configuration \
     --repository-name resortslite \
     --image-scanning-configuration scanOnPush=true \
     --region us-east-1
   ```
6. **VPC isolation**: Deploy tasks in private subnets with NAT Gateway for outbound traffic
7. **Dependency vulnerabilities**: The project contains known vulnerable dependencies (log4j 2.14.1 CVE-2021-44228, commons-collections 3.2.1 CVE-2015-6420). **Update these before production deployment.**

---

## Java-Specific Notes

### JVM Memory Tuning

The default JVM settings in the Dockerfile are:
```
-Xmx512m -Xms256m
-XX:+UseContainerSupport
-XX:MaxRAMPercentage=75.0
-XX:+UseG1GC
```

`UseContainerSupport` (available since Java 8u191) ensures the JVM respects container memory limits. With 1024 MB task memory and `MaxRAMPercentage=75.0`, the JVM heap will be capped at ~768 MB.

### Spring Boot Actuator

The following endpoints are exposed:
- `GET /actuator/health` — liveness and readiness
- `GET /actuator/info` — application information

### Spring Profiles

| Profile | Usage |
|---------|-------|
| `docker` | Container deployments (set via `SPRING_PROFILES_ACTIVE=docker`) |
| `default` | Local development |

### Graceful Shutdown

Spring Boot 2.7.x supports graceful shutdown. To enable it, add to `application.properties`:
```properties
server.shutdown=graceful
spring.lifecycle.timeout-per-shutdown-phase=30s
```

The Dockerfile uses `STOPSIGNAL SIGTERM` to trigger Spring Boot's graceful shutdown handler.

### H2 In-Memory Database

The application currently uses H2 in-memory database. For production:
- Replace with Amazon RDS (PostgreSQL/MySQL)
- Update `spring.datasource.*` properties via environment variables or SSM
- Remove `spring.h2.console.enabled=true` for security

### Log4j Security

⚠️ **CRITICAL**: The project includes `log4j-core:2.14.1` which has the Log4Shell vulnerability (CVE-2021-44228). **Upgrade to log4j 2.17.1+ or remove the dependency before deploying to production.**

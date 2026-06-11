# ResortsLite – AWS ECS Fargate Deployment Guide

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
**Java Version**: 8 (Amazon Corretto 8 runtime)  
**Build Tool**: Maven 3.9.x  
**Target Platform**: AWS ECS Fargate  
**Application Port**: 8080  
**Health Endpoint**: `/actuator/health`

ResortsLite is a resort booking REST API that uses Spring Session backed by Amazon ElastiCache for Redis for distributed session management, and Amazon S3 for report and backup storage.

---

## Prerequisites

### Local Development
| Tool | Version | Purpose |
|------|---------|---------|
| Docker Desktop | 24.x+ | Build and run containers locally |
| Docker Compose | 2.x+ | Local multi-container orchestration |
| Java JDK | 8+ | Local development (optional) |
| Maven | 3.9.x | Local builds (optional) |

### AWS Deployment
| Tool | Version | Purpose |
|------|---------|---------|
| AWS CLI | v2.x | AWS resource management |
| Docker | 24.x+ | Image build and push |
| Python 3 | 3.8+ | Used by deploy-image.sh for JSON manipulation |

---

## Project Structure

```
Compel/
├── Dockerfile                    # Multi-stage build (builder + runtime)
├── docker-compose.yml            # Local development compose file
├── .dockerignore                 # Files excluded from Docker build context
├── pom.xml                       # Maven build descriptor
├── src/
│   └── main/
│       ├── java/com/demo/resortslite/
│       │   ├── ResortsLiteApplication.java
│       │   ├── BookingController.java
│       │   ├── BookingService.java
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

### 1. Create a local `.env` file

```bash
# .env (do NOT commit to source control)
REDIS_HOST=your-elasticache-endpoint
REDIS_PORT=6379
REDIS_PASSWORD=your-redis-password
S3_REPORTS_BUCKET=resort-reports-bucket
S3_BACKUP_BUCKET=resort-backup-bucket
PAYMENT_API_ENDPOINT=http://payment-service/payments/charge
```

### 2. Start the application

```bash
# From the project root
docker compose up --build
```

### 3. Verify the application is running

```bash
# Health check
curl http://localhost:8080/actuator/health

# Test booking endpoint
curl -X POST "http://localhost:8080/api/bookings/create?guestName=John&roomType=STANDARD&checkIn=2024-06-01&checkOut=2024-06-05"
```

### 4. Stop the application

```bash
docker compose down
```

---

## Build and Push Docker Image

### Linux / macOS

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
- Sanitises the image name (lowercase, hyphens)
- Creates the ECR repository if it does not exist (ECR only)
- Builds the Docker image from the project root
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
  - Outbound TCP on port **443** (for ECR image pull, CloudWatch logs)
  - Outbound TCP on port **6379** (for Redis/ElastiCache)

```bash
# Example: Create a security group
aws ec2 create-security-group \
  --group-name resortsLite-sg \
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

#### ECS Task Role (optional – for S3 and other AWS service access)

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

# Attach S3 access policy
aws iam attach-role-policy \
  --role-name ecsTaskRole \
  --policy-arn arn:aws:iam::aws:policy/AmazonS3FullAccess
```

### 4. Amazon ElastiCache for Redis

ResortsLite uses Spring Session backed by Redis. Provision an ElastiCache Redis cluster:

```bash
aws elasticache create-cache-cluster \
  --cache-cluster-id resortsLite-redis \
  --cache-node-type cache.t3.micro \
  --engine redis \
  --num-cache-nodes 1 \
  --security-group-ids sg-xxxxxxxx \
  --cache-subnet-group-name your-subnet-group
```

Note the **Primary Endpoint** – you will pass it as `REDIS_HOST` environment variable.

### 5. CloudWatch Log Group

```bash
aws logs create-log-group --log-group-name /ecs/resortsLite --region us-east-1
```

---

## ECS Task Definition Explained

File: `ecs/task-definition.json`

| Field | Value | Notes |
|-------|-------|-------|
| `family` | `resortsLite-task` | Task definition family name |
| `requiresCompatibilities` | `["FARGATE"]` | Fargate launch type |
| `networkMode` | `awsvpc` | Required for Fargate |
| `cpu` | `"512"` | 0.5 vCPU |
| `memory` | `"1024"` | 1 GB RAM |
| `executionRoleArn` | `ecsTaskExecutionRole` | ECR pull + CloudWatch logs |
| `taskRoleArn` | `ecsTaskRole` | S3 and other AWS service access |

### Container Definition

| Field | Value | Notes |
|-------|-------|-------|
| `name` | `resortsLite` | Container name |
| `image` | `{{IMAGE_URI}}` | Replaced by deploy script |
| `containerPort` | `8080` | Application port |
| `logDriver` | `awslogs` | CloudWatch Logs |

### Valid Fargate CPU/Memory Combinations

| CPU | Memory Options |
|-----|---------------|
| 256 | 512, 1024, 2048 MB |
| **512** | **1024**, 2048, 3072, 4096 MB |
| 1024 | 2048–8192 MB |
| 2048 | 4096–16384 MB |
| 4096 | 8192–30720 MB |

---

## ECS Service Configuration

File: `ecs/service-definition.json`

| Field | Value | Notes |
|-------|-------|-------|
| `serviceName` | `resortsLite-service` | ECS service name |
| `launchType` | `FARGATE` | Serverless compute |
| `desiredCount` | `2` | Two tasks for HA |
| `maximumPercent` | `200` | Rolling deploy headroom |
| `minimumHealthyPercent` | `50` | Minimum healthy tasks during deploy |
| `assignPublicIp` | `ENABLED` | Required for public subnet Fargate tasks |

---

## ECS Fargate Deployment Walkthrough

### Step 1: Build and push the image

```bash
./scripts/build-push.sh
# Select ECR, enter region, account ID, repo name, tag
```

### Step 2: Run the deployment script

```bash
chmod +x scripts/deploy-image.sh
./scripts/deploy-image.sh
```

The script will prompt for:
- AWS Region
- ECS Cluster name (created automatically if it doesn't exist)
- ECR Image URI
- VPC ID
- Subnet IDs (comma-separated)
- Security Group ID
- Whether to create an Application Load Balancer

### Step 3: Verify the deployment

```bash
# Check service status
aws ecs describe-services \
  --cluster your-cluster \
  --services resortsLite-service \
  --region us-east-1

# View running tasks
aws ecs list-tasks \
  --cluster your-cluster \
  --service-name resortsLite-service \
  --region us-east-1

# Tail application logs
aws logs tail /ecs/resortsLite --follow --region us-east-1
```

### Step 4: Test the application

```bash
# If using ALB
curl http://<alb-dns-name>/actuator/health

# Expected response
{"status":"UP","components":{"db":{"status":"UP"},"redis":{"status":"UP"}}}
```

---

## ECS-Specific Troubleshooting

### Task fails to start

```bash
# Check stopped task reason
aws ecs describe-tasks \
  --cluster your-cluster \
  --tasks <task-arn> \
  --region us-east-1 \
  --query "tasks[0].{Status:lastStatus,StopCode:stopCode,StopReason:stoppedReason}"
```

**Common causes:**
- `CannotPullContainerError`: ECR permissions issue – verify `ecsTaskExecutionRole` has ECR pull permissions
- `ResourceInitializationError`: VPC endpoint or NAT Gateway missing for private subnets
- `OutOfMemoryError`: Increase task memory (e.g., from 1024 to 2048 MB)

### Network connectivity issues

```bash
# Verify security group allows outbound to Redis (port 6379)
aws ec2 describe-security-groups --group-ids sg-xxxxxxxx

# Check VPC endpoints for ECR (required for private subnets without NAT)
aws ec2 describe-vpc-endpoints --filters "Name=vpc-id,Values=vpc-xxxxxxxx"
```

### Application not healthy

```bash
# Check application logs
aws logs get-log-events \
  --log-group-name /ecs/resortsLite \
  --log-stream-name ecs/resortsLite/<task-id> \
  --region us-east-1

# Common Spring Boot startup issues:
# - Redis connection refused: Check REDIS_HOST and security groups
# - Port already in use: Ensure SERVER_PORT=8080 is set correctly
```

### CPU/Memory errors

```bash
# Valid Fargate combinations – if you see "Invalid CPU or Memory" error:
# cpu: "512" requires memory: 1024, 2048, 3072, or 4096
# Update task-definition.json and re-register
```

### Service not stabilising

```bash
# Check service events
aws ecs describe-services \
  --cluster your-cluster \
  --services resortsLite-service \
  --region us-east-1 \
  --query "services[0].events[:5]"
```

---

## ECS Fargate Scaling and Management

### Manual scaling

```bash
# Scale up
aws ecs update-service \
  --cluster your-cluster \
  --service resortsLite-service \
  --desired-count 4 \
  --region us-east-1

# Scale down
aws ecs update-service \
  --cluster your-cluster \
  --service resortsLite-service \
  --desired-count 1 \
  --region us-east-1
```

### Auto Scaling

```bash
# Register scalable target
aws application-autoscaling register-scalable-target \
  --service-namespace ecs \
  --resource-id service/your-cluster/resortsLite-service \
  --scalable-dimension ecs:service:DesiredCount \
  --min-capacity 2 \
  --max-capacity 10

# Create CPU-based scaling policy
aws application-autoscaling put-scaling-policy \
  --service-namespace ecs \
  --resource-id service/your-cluster/resortsLite-service \
  --scalable-dimension ecs:service:DesiredCount \
  --policy-name resortsLite-cpu-scaling \
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

1. Create a CodeDeploy application and deployment group targeting the ECS service
2. Use `deploymentController: CODE_DEPLOY` in the service definition
3. Configure two target groups (blue and green) on the ALB
4. Trigger deployments via CodeDeploy with the new task definition ARN

---

## Configuration Management

### Environment Variables Reference

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8080` | Application HTTP port |
| `SPRING_PROFILES_ACTIVE` | `docker` | Active Spring profile |
| `REDIS_HOST` | `localhost` | ElastiCache Redis endpoint |
| `REDIS_PORT` | `6379` | Redis port |
| `REDIS_PASSWORD` | _(empty)_ | Redis auth token |
| `S3_REPORTS_BUCKET` | `resort-reports-bucket` | S3 bucket for reports |
| `S3_BACKUP_BUCKET` | `resort-backup-bucket` | S3 bucket for backups |
| `PAYMENT_API_ENDPOINT` | `http://payment-service/payments/charge` | Payment service URL |
| `JAVA_OPTS` | See Dockerfile | JVM tuning flags |
| `TZ` | `UTC` | Container timezone |

### Using AWS Secrets Manager for sensitive values

```bash
# Store Redis password in Secrets Manager
aws secretsmanager create-secret \
  --name /resortsLite/redis-password \
  --secret-string "your-redis-password"

# Reference in task definition (add to containerDefinitions.secrets)
{
  "secrets": [
    {
      "name": "REDIS_PASSWORD",
      "valueFrom": "arn:aws:secretsmanager:us-east-1:123456789:secret:/resortsLite/redis-password"
    }
  ]
}
```

---

## Security Considerations

1. **Non-root container user**: The Dockerfile creates and uses `appuser` (non-root) for runtime
2. **No sensitive data in images**: All credentials are injected via environment variables or Secrets Manager
3. **Minimal runtime image**: Uses Amazon Corretto 8 JRE – no build tools in the runtime image
4. **Security Group least privilege**: Only open required ports (8080 inbound, 6379 outbound to Redis)
5. **IAM least privilege**: Use separate task execution role and task role with minimal permissions
6. **Secrets Manager**: Store Redis passwords, API keys in AWS Secrets Manager – never in environment variables for production
7. **VPC isolation**: Deploy in private subnets with NAT Gateway for production workloads
8. **Log retention**: Set CloudWatch log retention policy to avoid unbounded log storage costs

```bash
# Set log retention to 30 days
aws logs put-retention-policy \
  --log-group-name /ecs/resortsLite \
  --retention-in-days 30 \
  --region us-east-1
```

---

## Java-Specific Notes

### JVM Configuration for Containers

The Dockerfile sets the following JVM flags via `JAVA_OPTS`:

```
-Xms256m                    # Initial heap size
-Xmx512m                    # Maximum heap size
-XX:+UseContainerSupport    # Enable container-aware memory limits (Java 8u191+)
-XX:MaxRAMPercentage=75.0   # Use 75% of container memory for heap
-XX:+UseG1GC                # G1 garbage collector (recommended for containers)
-Djava.security.egd=file:/dev/./urandom  # Faster SecureRandom initialisation
```

### Spring Boot Actuator Endpoints

| Endpoint | URL | Purpose |
|----------|-----|---------|
| Health | `GET /actuator/health` | Liveness/readiness probe |
| Info | `GET /actuator/info` | Application metadata |

### Spring Session with Redis

ResortsLite uses Spring Session backed by Amazon ElastiCache for Redis. Ensure:
- `REDIS_HOST` points to the ElastiCache Primary Endpoint
- The ECS task security group allows outbound TCP on port 6379 to the ElastiCache security group
- ElastiCache is in the same VPC as the ECS tasks

### Graceful Shutdown

The container is configured with `STOPSIGNAL SIGTERM`. Spring Boot 2.7.x handles SIGTERM gracefully by default, completing in-flight requests before shutting down. ECS sends SIGTERM before SIGKILL (default 30-second grace period).

To increase the ECS stop timeout:
```json
// In task-definition.json containerDefinitions
"stopTimeout": 60
```

### H2 In-Memory Database

The application currently uses H2 in-memory database for development. For production:
- Replace with Amazon RDS (PostgreSQL or MySQL)
- Update `spring.datasource.*` properties
- Add the appropriate JDBC driver dependency to `pom.xml`
- Store database credentials in AWS Secrets Manager

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
12. [Configuration Management and Environment Variables](#configuration-management-and-environment-variables)
13. [Security Considerations](#security-considerations)
14. [Java-Specific Notes](#java-specific-notes)

---

## Overview

**ResortsLite** is a Spring Boot 2.7.x / Java 8 resort booking REST API. It uses:
- **Spring Boot Actuator** for health checks (`/actuator/health`)
- **H2 in-memory database** (development) / external DB via `DB_HOST` env var
- **Amazon ElastiCache for Memcached** for distributed booking cache
- **JWT** for stateless authentication
- **ECS Service Connect** for inter-service communication (payment, inventory)
- **Amazon EFS** for persistent report and backup storage

Target deployment: **AWS ECS Fargate** with Application Load Balancer.

---

## Prerequisites

### Local Development
| Tool | Version | Purpose |
|------|---------|---------|
| Docker Desktop | 24+ | Build and run containers |
| Docker Compose | 2.x | Local multi-container orchestration |
| Java JDK | 8 | Local development (optional) |
| Maven | 3.9+ | Local builds (optional) |

### AWS Deployment
| Tool | Version | Purpose |
|------|---------|---------|
| AWS CLI | 2.x | AWS resource management |
| Docker | 24+ | Image build and push |
| Python 3 | 3.8+ | Deploy script JSON manipulation |

---

## Project Structure

```
ResortsLite/
├── Dockerfile                  # Multi-stage build (Java 8 / Spring Boot)
├── docker-compose.yml          # Local development stack
├── .dockerignore               # Excludes build artefacts and wrapper files
├── pom.xml                     # Maven build descriptor
├── src/
│   └── main/
│       ├── java/com/demo/resortslite/
│       │   ├── ResortsLiteApplication.java
│       │   ├── BookingController.java
│       │   ├── BookingService.java
│       │   ├── ReportService.java
│       │   └── JwtTokenUtil.java
│       └── resources/
│           └── application.properties
├── ecs/
│   ├── task-definition.json    # ECS Fargate task definition
│   └── service-definition.json # ECS service definition
├── scripts/
│   ├── build-push.sh           # Linux/macOS: build & push to ECR or Docker Hub
│   ├── build-push.bat          # Windows: build & push to ECR or Docker Hub
│   ├── deploy-image.sh         # Linux/macOS: deploy to ECS Fargate
│   └── deploy-image.bat        # Windows: deploy to ECS Fargate
└── docs/
    └── DEPLOYMENT.md           # This file
```

---

## Local Development with Docker Compose

### 1. Create a local `.env` file (never commit this)

```bash
cat > .env <<'EOF'
JWT_SECRET=local-dev-secret-change-me
MEMCACHED_ENDPOINT=localhost:11211
PAYMENT_SERVICE_ENDPOINT=http://payment-service:9090
INVENTORY_SERVICE_ENDPOINT=http://inventory-service:8081
DB_HOST=db-service
APP_REPORT_BASE_PATH=/tmp/reports
APP_BACKUP_PATH=/tmp/backups
EOF
```

### 2. Start the application

```bash
docker compose up --build
```

### 3. Verify health

```bash
curl http://localhost:8080/actuator/health
```

Expected response:
```json
{"status":"UP"}
```

### 4. Test the booking API

```bash
# Create a booking
curl -X POST "http://localhost:8080/api/bookings/create?guestName=Alice&roomType=DELUXE&checkIn=2025-01-10&checkOut=2025-01-15"

# Check availability
curl "http://localhost:8080/api/bookings/availability?roomType=SUITE"
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
./scripts/build-push.sh
```

### Windows

```cmd
scripts\build-push.bat
```

The script will prompt you to:
1. Enter an image tag (default: `latest`)
2. Select registry: **1) AWS ECR** or **2) Docker Hub**
3. Provide registry credentials / region

**ECR flow:**
- Retrieves your AWS Account ID automatically
- Creates the ECR repository if it does not exist
- Authenticates via `aws ecr get-login-password`
- Builds and pushes the image

**Docker Hub flow:**
- Prompts for username, password/token, and namespace
- Authenticates and pushes

---

## AWS ECS Fargate Prerequisites

### 1. AWS CLI Configuration

```bash
aws configure
# Enter: Access Key ID, Secret Access Key, Region, Output format
```

Verify:
```bash
aws sts get-caller-identity
```

### 2. VPC and Networking

Ensure you have:
- A **VPC** with at least **2 public or private subnets** in different AZs
- A **Security Group** that allows:
  - Inbound TCP 8080 from the ALB security group (or `0.0.0.0/0` for testing)
  - Outbound all traffic (for ECR image pull, CloudWatch logs, ElastiCache)

```bash
# List VPCs
aws ec2 describe-vpcs --query "Vpcs[*].{ID:VpcId,CIDR:CidrBlock}" --output table

# List subnets
aws ec2 describe-subnets --query "Subnets[*].{ID:SubnetId,AZ:AvailabilityZone,CIDR:CidrBlock}" --output table
```

### 3. IAM Roles

#### ecsTaskExecutionRole (required)
This role allows ECS to pull images from ECR and write logs to CloudWatch.

```bash
# Create the role
aws iam create-role \
  --role-name ecsTaskExecutionRole \
  --assume-role-policy-document '{
    "Version":"2012-10-17",
    "Statement":[{
      "Effect":"Allow",
      "Principal":{"Service":"ecs-tasks.amazonaws.com"},
      "Action":"sts:AssumeRole"
    }]
  }'

# Attach the managed policy
aws iam attach-role-policy \
  --role-name ecsTaskExecutionRole \
  --policy-arn arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy

# Add Secrets Manager and SSM access (for JWT_SECRET and MEMCACHED_ENDPOINT)
aws iam attach-role-policy \
  --role-name ecsTaskExecutionRole \
  --policy-arn arn:aws:iam::aws:policy/SecretsManagerReadWrite

aws iam attach-role-policy \
  --role-name ecsTaskExecutionRole \
  --policy-arn arn:aws:iam::aws:policy/AmazonSSMReadOnlyAccess
```

#### ecsTaskRole (optional, for task-level AWS API access)

```bash
aws iam create-role \
  --role-name ecsTaskRole \
  --assume-role-policy-document '{
    "Version":"2012-10-17",
    "Statement":[{
      "Effect":"Allow",
      "Principal":{"Service":"ecs-tasks.amazonaws.com"},
      "Action":"sts:AssumeRole"
    }]
  }'
```

### 4. AWS Secrets Manager — JWT Secret

```bash
aws secretsmanager create-secret \
  --name "resortsLite/jwt-secret" \
  --secret-string "$(openssl rand -base64 64)" \
  --region us-east-1
```

### 5. AWS SSM Parameter Store — Memcached Endpoint

```bash
aws ssm put-parameter \
  --name "/resortsLite/cache/endpoint" \
  --value "my-cluster.abc123.cfg.use1.cache.amazonaws.com:11211" \
  --type "String" \
  --region us-east-1
```

### 6. CloudWatch Log Group

```bash
aws logs create-log-group \
  --log-group-name "/ecs/resortsLite" \
  --region us-east-1
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
| `taskRoleArn` | `ecsTaskRole` | Task-level AWS API access |

### Valid Fargate CPU/Memory Combinations

| CPU | Memory Options |
|-----|---------------|
| 256 (.25 vCPU) | 512, 1024, 2048 MB |
| **512 (.5 vCPU)** | **1024, 2048, 3072, 4096 MB** ← used |
| 1024 (1 vCPU) | 2048–8192 MB |
| 2048 (2 vCPU) | 4096–16384 MB |
| 4096 (4 vCPU) | 8192–30720 MB |

### Container Definition Highlights

- **Port mapping**: `containerPort: 8080` (no `hostPort` for Fargate)
- **Secrets**: `JWT_SECRET` from Secrets Manager, `MEMCACHED_ENDPOINT` from SSM
- **Logging**: `awslogs` driver → `/ecs/resortsLite` CloudWatch log group
- **EFS volumes**: `/mnt/efs/reports` and `/mnt/efs/backups` for persistent storage

---

## ECS Service Configuration

File: `ecs/service-definition.json`

| Field | Value | Notes |
|-------|-------|-------|
| `launchType` | `FARGATE` | Serverless compute |
| `desiredCount` | `2` | Two tasks for HA |
| `networkMode` | `awsvpc` | Each task gets its own ENI |
| `assignPublicIp` | `ENABLED` | Required for public subnet ECR pull |
| `maximumPercent` | `200` | Rolling deploy: up to 4 tasks |
| `minimumHealthyPercent` | `50` | At least 1 task always running |

---

## ECS Fargate Deployment Walkthrough

### Step 1: Build and push the image

```bash
./scripts/build-push.sh
# Select: 1 (AWS ECR)
# Region: us-east-1
# Repo: resortsLite
# Tag: 1.0.0
```

Note the full image URI output, e.g.:
```
123456789012.dkr.ecr.us-east-1.amazonaws.com/resortsLite:1.0.0
```

### Step 2: Run the deployment script

```bash
chmod +x scripts/deploy-image.sh
./scripts/deploy-image.sh
```

Provide when prompted:
- **AWS Region**: `us-east-1`
- **ECS Cluster name**: `resortsLite-cluster`
- **ECR Image URI**: `123456789012.dkr.ecr.us-east-1.amazonaws.com/resortsLite:1.0.0`
- **VPC ID**: `vpc-0abc12345`
- **Subnet IDs**: `subnet-aaa111,subnet-bbb222`
- **Security Group**: `sg-0xyz9876`
- **Load Balancer**: `y` (recommended for production)

### Step 3: Verify the deployment

```bash
# Check service status
aws ecs describe-services \
  --cluster resortsLite-cluster \
  --services resortsLite-service \
  --region us-east-1 \
  --query "services[0].{Status:status,Running:runningCount,Desired:desiredCount}"

# Tail application logs
aws logs tail /ecs/resortsLite --follow --region us-east-1

# Test health endpoint (replace with ALB DNS)
curl http://<ALB-DNS>/actuator/health
```

### Step 4: Update the deployment (rolling update)

```bash
# Build and push new image with new tag
./scripts/build-push.sh   # tag: 1.0.1

# Re-run deploy script with new image URI
./scripts/deploy-image.sh
```

---

## ECS-Specific Troubleshooting

### Task fails to start

```bash
# List stopped tasks
aws ecs list-tasks \
  --cluster resortsLite-cluster \
  --desired-status STOPPED \
  --region us-east-1

# Describe stopped task for stop reason
aws ecs describe-tasks \
  --cluster resortsLite-cluster \
  --tasks <TASK_ARN> \
  --region us-east-1 \
  --query "tasks[0].{StopCode:stopCode,StopReason:stoppedReason,Containers:containers[*].{Name:name,Reason:reason,ExitCode:exitCode}}"
```

### Common errors and fixes

| Error | Cause | Fix |
|-------|-------|-----|
| `CannotPullContainerError` | ECR auth failure or no internet | Check `assignPublicIp: ENABLED` and security group outbound rules |
| `ResourceInitializationError` | Secrets Manager/SSM access denied | Verify `ecsTaskExecutionRole` has Secrets Manager and SSM policies |
| `OutOfMemoryError` in logs | JVM heap exceeds container memory | Increase task memory or reduce `-Xmx` in `JAVA_OPTS` |
| `Connection refused` to Memcached | ElastiCache not reachable | Check VPC security group allows port 11211 from Fargate tasks |
| `InvalidParameterException: cpu/memory` | Invalid Fargate combination | Use valid combinations (see table above) |
| Service stuck in `DRAINING` | Old tasks not stopping | Check `minimumHealthyPercent` and ALB deregistration delay |

### View container logs

```bash
aws logs tail /ecs/resortsLite --follow --region us-east-1
```

### SSH-equivalent: ECS Exec

```bash
# Enable ECS Exec on the service
aws ecs update-service \
  --cluster resortsLite-cluster \
  --service resortsLite-service \
  --enable-execute-command \
  --region us-east-1

# Execute a shell in a running task
aws ecs execute-command \
  --cluster resortsLite-cluster \
  --task <TASK_ARN> \
  --container resortsLite \
  --interactive \
  --command "/bin/sh" \
  --region us-east-1
```

---

## ECS Fargate Scaling and Management

### Manual scaling

```bash
# Scale up to 4 tasks
aws ecs update-service \
  --cluster resortsLite-cluster \
  --service resortsLite-service \
  --desired-count 4 \
  --region us-east-1

# Scale down to 0 (stop all tasks)
aws ecs update-service \
  --cluster resortsLite-cluster \
  --service resortsLite-service \
  --desired-count 0 \
  --region us-east-1
```

### Auto Scaling (Application Auto Scaling)

```bash
# Register scalable target
aws application-autoscaling register-scalable-target \
  --service-namespace ecs \
  --resource-id service/resortsLite-cluster/resortsLite-service \
  --scalable-dimension ecs:service:DesiredCount \
  --min-capacity 2 \
  --max-capacity 10 \
  --region us-east-1

# Create CPU-based scaling policy
aws application-autoscaling put-scaling-policy \
  --service-namespace ecs \
  --resource-id service/resortsLite-cluster/resortsLite-service \
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
  }' \
  --region us-east-1
```

### Blue/Green Deployment with CodeDeploy

For zero-downtime deployments, configure AWS CodeDeploy with ECS:

1. Create a CodeDeploy application and deployment group targeting the ECS service
2. Set `deploymentController: { type: CODE_DEPLOY }` in the service definition
3. Use `appspec.yaml` to define the blue/green task set swap

---

## Configuration Management and Environment Variables

### Environment Variables Reference

| Variable | Source | Description |
|----------|--------|-------------|
| `JWT_SECRET` | Secrets Manager | JWT signing secret (never hardcode) |
| `MEMCACHED_ENDPOINT` | SSM Parameter Store | ElastiCache cluster endpoint |
| `PAYMENT_SERVICE_ENDPOINT` | Task definition env | ECS Service Connect DNS for payment svc |
| `INVENTORY_SERVICE_ENDPOINT` | Task definition env | ECS Service Connect DNS for inventory svc |
| `DB_HOST` | Task definition env | Database service hostname |
| `APP_REPORT_BASE_PATH` | Task definition env | EFS mount path for reports |
| `APP_BACKUP_PATH` | Task definition env | EFS mount path for backups |
| `CACHE_TTL_SECONDS` | Task definition env | Memcached entry TTL (default: 3600) |
| `ALB_STICKINESS_ENABLED` | Task definition env | ALB sticky session toggle |
| `ALB_STICKINESS_DURATION_SECONDS` | Task definition env | Sticky session cookie TTL |
| `SPRING_PROFILES_ACTIVE` | Task definition env | Spring profile (`docker`) |
| `JAVA_OPTS` | Task definition env | JVM flags |
| `TZ` | Task definition env | Timezone (UTC) |

### Updating secrets

```bash
# Rotate JWT secret
aws secretsmanager put-secret-value \
  --secret-id "resortsLite/jwt-secret" \
  --secret-string "$(openssl rand -base64 64)" \
  --region us-east-1

# Force new task deployment to pick up new secret
aws ecs update-service \
  --cluster resortsLite-cluster \
  --service resortsLite-service \
  --force-new-deployment \
  --region us-east-1
```

---

## Security Considerations

1. **Never hardcode secrets** — use AWS Secrets Manager for `JWT_SECRET` and SSM for `MEMCACHED_ENDPOINT`
2. **Non-root container user** — the Dockerfile creates and uses `appuser` (non-root)
3. **Minimal runtime image** — uses `eclipse-temurin:8-jdk` (explicit base image as specified)
4. **Security group least privilege** — only allow inbound 8080 from ALB security group, not `0.0.0.0/0`
5. **EFS encryption in transit** — `transitEncryption: ENABLED` in task definition volumes
6. **VPC isolation** — deploy Fargate tasks in private subnets with NAT Gateway for production
7. **IAM least privilege** — scope `ecsTaskRole` to only the AWS services the application needs
8. **Image scanning** — enable ECR image scanning on push:
   ```bash
   aws ecr put-image-scanning-configuration \
     --repository-name resortsLite \
     --image-scanning-configuration scanOnPush=true \
     --region us-east-1
   ```
9. **Log retention** — set CloudWatch log retention to avoid unbounded storage costs:
   ```bash
   aws logs put-retention-policy \
     --log-group-name /ecs/resortsLite \
     --retention-in-days 30 \
     --region us-east-1
   ```

---

## Java-Specific Notes

### JVM Configuration for Containers

The `JAVA_OPTS` environment variable is set to:
```
-Xmx512m -Xms256m
-XX:+UseContainerSupport
-XX:MaxRAMPercentage=75.0
-Djava.security.egd=file:/dev/./urandom
-Dfile.encoding=UTF-8
-Duser.timezone=UTC
```

- **`UseContainerSupport`**: Enables JVM to respect cgroup memory limits (Java 8u191+)
- **`MaxRAMPercentage=75.0`**: JVM uses up to 75% of container memory for heap
- **`java.security.egd`**: Faster random number generation in containers
- **`-Xmx512m`**: Hard cap on heap; adjust if task memory is increased

### Spring Boot Actuator

Health endpoint: `GET /actuator/health`

The task definition exposes this endpoint for ALB health checks. The management endpoints are configured in `application.properties`:
```properties
management.endpoints.web.exposure.include=health
management.endpoint.health.show-details=always
```

### Spring Boot Startup Time

Java 8 + Spring Boot 2.7 typically takes 15–30 seconds to start. The ALB target group is configured with:
- `healthCheckGracePeriodSeconds: 300` — gives the JVM time to start before health checks begin
- `startPeriod: 60s` in docker-compose healthcheck

### Dependency Notes

- **log4j-core 2.14.1**: Contains CVE-2021-44228 (Log4Shell). Upgrade to 2.17.2+ in production.
- **commons-collections 3.2.1**: Contains CVE-2015-6420. Upgrade to 3.2.2+ in production.
- **jjwt 0.9.1**: Consider upgrading to `io.jsonwebtoken:jjwt-api:0.11.5` for better security.

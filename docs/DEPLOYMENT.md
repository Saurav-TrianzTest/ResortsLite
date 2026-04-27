# ResortsLite - AWS ECS Fargate Deployment Guide

## Table of Contents
1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Local Development Setup](#local-development-setup)
4. [Building and Pushing Docker Images](#building-and-pushing-docker-images)
5. [AWS ECS Fargate Prerequisites](#aws-ecs-fargate-prerequisites)
6. [ECS Task Definition Explained](#ecs-task-definition-explained)
7. [ECS Service Configuration](#ecs-service-configuration)
8. [Deployment to AWS ECS Fargate](#deployment-to-aws-ecs-fargate)
9. [Configuration Management](#configuration-management)
10. [Monitoring and Logging](#monitoring-and-logging)
11. [Troubleshooting](#troubleshooting)
12. [Security Considerations](#security-considerations)
13. [Scaling and Management](#scaling-and-management)

---

## Overview

ResortsLite is a Spring Boot 2.7.x application built with Java 8, designed for containerized deployment on AWS ECS Fargate. This guide provides comprehensive instructions for building, deploying, and managing the application in a production environment.

**Application Details:**
- **Framework:** Spring Boot 2.7.18
- **Java Version:** Java 8 (1.8)
- **Build Tool:** Maven
- **Application Port:** 8080
- **Health Check Endpoint:** `/actuator/health`
- **Target Platform:** AWS ECS Fargate

---

## Prerequisites

### Required Software
- **Docker:** Version 20.10 or higher
- **Docker Compose:** Version 2.0 or higher
- **AWS CLI:** Version 2.x
- **Java:** JDK 8 or higher (for local development)
- **Maven:** Version 3.6 or higher (for local development)
- **Git:** For version control

### AWS Account Requirements
- Active AWS account with appropriate permissions
- IAM user with permissions for:
  - ECS (Elastic Container Service)
  - ECR (Elastic Container Registry)
  - VPC and networking resources
  - CloudWatch Logs
  - IAM role creation
  - Application Load Balancer (optional)

### AWS CLI Configuration
```bash
# Configure AWS CLI with your credentials
aws configure

# Verify configuration
aws sts get-caller-identity
```

---

## Local Development Setup

### 1. Clone the Repository
```bash
git clone <repository-url>
cd "ModResorts Mono"
```

### 2. Build the Application Locally
```bash
# Using Maven
mvn clean package -DskipTests

# The JAR file will be created in target/resortsLite-1.0.0.jar
```

### 3. Run Locally with Docker Compose
```bash
# Build and start the application
docker-compose up --build

# Access the application
# Application: http://localhost:8080
# Health Check: http://localhost:8080/actuator/health
# H2 Console: http://localhost:8080/h2-console

# Stop the application
docker-compose down
```

### 4. Environment Variables for Local Development
Create a `.env` file in the project root:
```env
# Database Configuration
DB_URL=jdbc:h2:mem:resortdb;DB_CLOSE_DELAY=-1
DB_USERNAME=sa
DB_PASSWORD=

# Redis Configuration
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=

# AWS S3 Configuration
S3_BUCKET_NAME=resortslite-reports
AWS_REGION=us-east-1
AWS_ACCESS_KEY_ID=your-access-key
AWS_SECRET_ACCESS_KEY=your-secret-key

# External Service Endpoints
PAYMENT_ENDPOINT=http://payment-svc.internal:9090/charge
INVENTORY_ENDPOINT=http://inventory-svc.internal:8081/rooms
NOTIFICATION_ENDPOINT=http://notify.internal:7070/send
```

---

## Building and Pushing Docker Images

### Option 1: Using build-push.sh (Linux/macOS)
```bash
cd scripts
chmod +x build-push.sh
./build-push.sh
```

The script will prompt you for:
1. **Registry Type:** AWS ECR or Docker Hub
2. **Registry Details:** Region, Account ID, Repository Name (for ECR) or Username/Password (for Docker Hub)
3. **Image Tag:** Version tag (default: latest)

### Option 2: Using build-push.bat (Windows)
```cmd
cd scripts
build-push.bat
```

### Manual Docker Build and Push

#### For AWS ECR:
```bash
# Set variables
AWS_REGION=us-east-1
AWS_ACCOUNT_ID=123456789012
ECR_REPO=resortslite
IMAGE_TAG=latest

# Authenticate with ECR
aws ecr get-login-password --region $AWS_REGION | \
  docker login --username AWS --password-stdin \
  $AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com

# Create ECR repository (if not exists)
aws ecr create-repository --repository-name $ECR_REPO --region $AWS_REGION

# Build image
docker build -t resortslite:$IMAGE_TAG .

# Tag image
docker tag resortslite:$IMAGE_TAG \
  $AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/$ECR_REPO:$IMAGE_TAG

# Push image
docker push $AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/$ECR_REPO:$IMAGE_TAG
```

#### For Docker Hub:
```bash
# Login to Docker Hub
docker login

# Build and tag
docker build -t your-username/resortslite:latest .

# Push
docker push your-username/resortslite:latest
```

---

## AWS ECS Fargate Prerequisites

### 1. VPC and Networking Setup

#### Create VPC (if not exists)
```bash
# Create VPC
aws ec2 create-vpc --cidr-block 10.0.0.0/16 --region us-east-1

# Create subnets in different availability zones
aws ec2 create-subnet --vpc-id vpc-xxxxx --cidr-block 10.0.1.0/24 --availability-zone us-east-1a
aws ec2 create-subnet --vpc-id vpc-xxxxx --cidr-block 10.0.2.0/24 --availability-zone us-east-1b

# Create Internet Gateway
aws ec2 create-internet-gateway
aws ec2 attach-internet-gateway --vpc-id vpc-xxxxx --internet-gateway-id igw-xxxxx

# Create route table and associate with subnets
aws ec2 create-route-table --vpc-id vpc-xxxxx
aws ec2 create-route --route-table-id rtb-xxxxx --destination-cidr-block 0.0.0.0/0 --gateway-id igw-xxxxx
```

### 2. Security Group Configuration

Create a security group that allows:
- **Inbound:** Port 8080 (application) from ALB or 0.0.0.0/0
- **Inbound:** Port 80/443 (if using ALB)
- **Outbound:** All traffic

```bash
# Create security group
aws ec2 create-security-group \
  --group-name resortslite-sg \
  --description "Security group for ResortsLite ECS tasks" \
  --vpc-id vpc-xxxxx

# Add inbound rule for application port
aws ec2 authorize-security-group-ingress \
  --group-id sg-xxxxx \
  --protocol tcp \
  --port 8080 \
  --cidr 0.0.0.0/0
```

### 3. IAM Roles Setup

#### ECS Task Execution Role
This role allows ECS to pull images from ECR and write logs to CloudWatch.

```bash
# Create trust policy file: ecs-task-execution-trust-policy.json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Service": "ecs-tasks.amazonaws.com"
      },
      "Action": "sts:AssumeRole"
    }
  ]
}

# Create role
aws iam create-role \
  --role-name ecsTaskExecutionRole \
  --assume-role-policy-document file://ecs-task-execution-trust-policy.json

# Attach AWS managed policy
aws iam attach-role-policy \
  --role-name ecsTaskExecutionRole \
  --policy-arn arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy
```

#### ECS Task Role (Optional)
This role grants permissions to the application (e.g., S3 access, DynamoDB).

```bash
# Create task role
aws iam create-role \
  --role-name ecsTaskRole \
  --assume-role-policy-document file://ecs-task-execution-trust-policy.json

# Attach policies for S3 access
aws iam attach-role-policy \
  --role-name ecsTaskRole \
  --policy-arn arn:aws:iam::aws:policy/AmazonS3FullAccess
```

### 4. CloudWatch Log Group

```bash
# Create log group
aws logs create-log-group --log-group-name /ecs/resortslite --region us-east-1

# Set retention policy (optional)
aws logs put-retention-policy \
  --log-group-name /ecs/resortslite \
  --retention-in-days 7
```

---

## ECS Task Definition Explained

The task definition (`ecs/task-definition.json`) defines how your container runs on ECS Fargate.

### Key Components:

#### 1. Launch Type Configuration
```json
{
  "requiresCompatibilities": ["FARGATE"],
  "networkMode": "awsvpc"
}
```
- **FARGATE:** Serverless compute engine
- **awsvpc:** Each task gets its own ENI with private IP

#### 2. CPU and Memory
```json
{
  "cpu": "512",
  "memory": "1024"
}
```

**Valid Fargate CPU/Memory Combinations:**
| CPU (vCPU) | Memory (MB) |
|------------|-------------|
| 256 (.25)  | 512, 1024, 2048 |
| 512 (.5)   | 1024, 2048, 3072, 4096 |
| 1024 (1)   | 2048-8192 (increments of 1024) |
| 2048 (2)   | 4096-16384 (increments of 1024) |
| 4096 (4)   | 8192-30720 (increments of 1024) |

#### 3. Container Definition
```json
{
  "containerDefinitions": [
    {
      "name": "resortslite",
      "image": "{{IMAGE_URI}}",
      "essential": true,
      "portMappings": [
        {
          "containerPort": 8080,
          "protocol": "tcp"
        }
      ],
      "environment": [...],
      "logConfiguration": {...}
    }
  ]
}
```

#### 4. Environment Variables
Configure application settings via environment variables:
- **SERVER_PORT:** Application port (8080)
- **SPRING_PROFILES_ACTIVE:** Spring profile (docker)
- **JAVA_OPTS:** JVM memory settings
- **DB_URL, DB_USERNAME, DB_PASSWORD:** Database configuration
- **REDIS_HOST, REDIS_PORT, REDIS_PASSWORD:** Redis configuration
- **S3_BUCKET_NAME, AWS_REGION:** AWS S3 configuration

#### 5. Logging Configuration
```json
{
  "logConfiguration": {
    "logDriver": "awslogs",
    "options": {
      "awslogs-group": "/ecs/resortslite",
      "awslogs-region": "us-east-1",
      "awslogs-stream-prefix": "ecs"
    }
  }
}
```

---

## ECS Service Configuration

The service definition (`ecs/service-definition.json`) manages task deployment and scaling.

### Key Components:

#### 1. Service Configuration
```json
{
  "serviceName": "resortslite-service",
  "cluster": "{{CLUSTER_NAME}}",
  "taskDefinition": "resortslite-task",
  "desiredCount": 2,
  "launchType": "FARGATE"
}
```

#### 2. Network Configuration
```json
{
  "networkConfiguration": {
    "awsvpcConfiguration": {
      "subnets": ["subnet-xxxxx", "subnet-yyyyy"],
      "securityGroups": ["sg-xxxxx"],
      "assignPublicIp": "ENABLED"
    }
  }
}
```

#### 3. Load Balancer Integration (Optional)
```json
{
  "loadBalancers": [
    {
      "targetGroupArn": "arn:aws:elasticloadbalancing:...",
      "containerName": "resortslite",
      "containerPort": 8080
    }
  ],
  "healthCheckGracePeriodSeconds": 300
}
```

#### 4. Deployment Configuration
```json
{
  "deploymentConfiguration": {
    "maximumPercent": 200,
    "minimumHealthyPercent": 50,
    "deploymentCircuitBreaker": {
      "enable": true,
      "rollback": true
    }
  }
}
```

---

## Deployment to AWS ECS Fargate

### Automated Deployment

#### Using deploy-image.sh (Linux/macOS)
```bash
cd scripts
chmod +x deploy-image.sh
./deploy-image.sh
```

#### Using deploy-image.bat (Windows)
```cmd
cd scripts
deploy-image.bat
```

The deployment script will:
1. Retrieve AWS Account ID
2. Prompt for AWS Region and ECS Cluster Name
3. Create ECS cluster if it doesn't exist
4. Prompt for VPC, Subnets, and Security Group
5. Prompt for Docker Image URI
6. Prompt for Redis configuration
7. Ask if you need a load balancer (creates ALB and Target Group automatically)
8. Create CloudWatch Log Group
9. Register ECS Task Definition
10. Create or update ECS Service
11. Wait for service to stabilize
12. Display deployment summary

### Manual Deployment Steps

#### 1. Register Task Definition
```bash
# Replace placeholders in task-definition.json
sed -i 's|{{IMAGE_URI}}|123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest|g' ecs/task-definition.json
sed -i 's|{{AWS_REGION}}|us-east-1|g' ecs/task-definition.json
sed -i 's|{{ACCOUNT_ID}}|123456789012|g' ecs/task-definition.json

# Register task definition
aws ecs register-task-definition \
  --cli-input-json file://ecs/task-definition.json \
  --region us-east-1
```

#### 2. Create ECS Cluster
```bash
aws ecs create-cluster --cluster-name resortslite-cluster --region us-east-1
```

#### 3. Create ECS Service
```bash
# Update service-definition.json with your values
aws ecs create-service \
  --cli-input-json file://ecs/service-definition.json \
  --region us-east-1
```

#### 4. Verify Deployment
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
```

---

## Configuration Management

### Environment-Specific Configuration

#### Development Environment
```bash
# Use H2 in-memory database
DB_URL=jdbc:h2:mem:resortdb;DB_CLOSE_DELAY=-1
SPRING_PROFILES_ACTIVE=dev
```

#### Production Environment
```bash
# Use external database (RDS)
DB_URL=jdbc:postgresql://rds-endpoint:5432/resortdb
DB_USERNAME=admin
DB_PASSWORD=secure-password
SPRING_PROFILES_ACTIVE=prod

# Use ElastiCache Redis
REDIS_HOST=redis.cache.amazonaws.com
REDIS_PORT=6379
REDIS_PASSWORD=redis-password
```

### Secrets Management

Use AWS Secrets Manager or Parameter Store for sensitive data:

```bash
# Store secret in Secrets Manager
aws secretsmanager create-secret \
  --name resortslite/db-password \
  --secret-string "your-secure-password"

# Reference in task definition
{
  "secrets": [
    {
      "name": "DB_PASSWORD",
      "valueFrom": "arn:aws:secretsmanager:us-east-1:123456789012:secret:resortslite/db-password"
    }
  ]
}
```

---

## Monitoring and Logging

### CloudWatch Logs

#### View Logs
```bash
# Tail logs in real-time
aws logs tail /ecs/resortslite --follow --region us-east-1

# Filter logs by pattern
aws logs filter-log-events \
  --log-group-name /ecs/resortslite \
  --filter-pattern "ERROR" \
  --region us-east-1
```

#### Log Insights Queries
```sql
-- Find errors in the last hour
fields @timestamp, @message
| filter @message like /ERROR/
| sort @timestamp desc
| limit 100

-- Count requests by endpoint
fields @timestamp, @message
| filter @message like /GET|POST/
| stats count() by bin(5m)
```

### CloudWatch Metrics

Monitor ECS service metrics:
- **CPUUtilization:** CPU usage percentage
- **MemoryUtilization:** Memory usage percentage
- **TargetResponseTime:** ALB response time
- **HealthyHostCount:** Number of healthy targets

### Application Performance Monitoring

#### Spring Boot Actuator Endpoints
- **Health:** `/actuator/health`
- **Metrics:** `/actuator/metrics`
- **Info:** `/actuator/info`

#### Custom Metrics with Micrometer
```java
// Add to pom.xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-cloudwatch2</artifactId>
</dependency>

// Configure in application.properties
management.metrics.export.cloudwatch.namespace=ResortsLite
management.metrics.export.cloudwatch.enabled=true
```

---

## Troubleshooting

### Common Issues and Solutions

#### 1. Task Fails to Start

**Symptoms:** Tasks transition from PENDING to STOPPED immediately

**Possible Causes:**
- Invalid CPU/memory combination
- Image pull errors (ECR permissions)
- Missing IAM roles
- Network configuration issues

**Solutions:**
```bash
# Check task stopped reason
aws ecs describe-tasks \
  --cluster resortslite-cluster \
  --tasks <task-id> \
  --region us-east-1 \
  --query 'tasks[0].stoppedReason'

# Check CloudWatch logs for errors
aws logs tail /ecs/resortslite --follow --region us-east-1

# Verify IAM roles
aws iam get-role --role-name ecsTaskExecutionRole
```

#### 2. Health Check Failures

**Symptoms:** Tasks fail health checks and are replaced continuously

**Solutions:**
- Verify application is listening on port 8080
- Check `/actuator/health` endpoint returns 200 OK
- Increase `healthCheckGracePeriodSeconds` to allow longer startup time
- Review application logs for startup errors

```bash
# Test health endpoint locally
curl http://localhost:8080/actuator/health

# Check ALB target health
aws elbv2 describe-target-health \
  --target-group-arn <target-group-arn> \
  --region us-east-1
```

#### 3. Network Connectivity Issues

**Symptoms:** Cannot access external services (Redis, RDS, S3)

**Solutions:**
- Verify security group allows outbound traffic
- Check subnet route tables have internet gateway route
- Verify DNS resolution works
- Test connectivity from within container

```bash
# Execute command in running task
aws ecs execute-command \
  --cluster resortslite-cluster \
  --task <task-id> \
  --container resortslite \
  --interactive \
  --command "/bin/sh"

# Inside container, test connectivity
ping redis-host
curl http://external-service
```

#### 4. Out of Memory Errors

**Symptoms:** Tasks crash with OOMKilled status

**Solutions:**
- Increase task memory allocation
- Adjust JVM heap size in JAVA_OPTS
- Monitor memory usage with CloudWatch

```bash
# Update JAVA_OPTS for larger heap
JAVA_OPTS="-Xmx768m -Xms384m -XX:MaxRAMPercentage=75.0"

# Increase task memory to 2048 MB
# Update task definition cpu: "1024", memory: "2048"
```

#### 5. Slow Application Startup

**Symptoms:** Tasks take too long to become healthy

**Solutions:**
- Increase `startPeriod` in health check configuration
- Optimize Spring Boot startup time
- Use Spring Boot lazy initialization

```properties
# application.properties
spring.main.lazy-initialization=true
spring.jmx.enabled=false
```

---

## Security Considerations

### 1. Container Security

#### Use Non-Root User
The Dockerfile already creates and uses a non-root user:
```dockerfile
RUN groupadd -r appuser && useradd -r -g appuser appuser
USER appuser
```

#### Scan Images for Vulnerabilities
```bash
# Using AWS ECR image scanning
aws ecr start-image-scan \
  --repository-name resortslite \
  --image-id imageTag=latest \
  --region us-east-1

# View scan results
aws ecr describe-image-scan-findings \
  --repository-name resortslite \
  --image-id imageTag=latest \
  --region us-east-1
```

### 2. Network Security

#### Use Private Subnets
- Deploy tasks in private subnets
- Use NAT Gateway for outbound internet access
- Restrict security group rules to minimum required

#### Enable VPC Flow Logs
```bash
aws ec2 create-flow-logs \
  --resource-type VPC \
  --resource-ids vpc-xxxxx \
  --traffic-type ALL \
  --log-destination-type cloud-watch-logs \
  --log-group-name /aws/vpc/flowlogs
```

### 3. Secrets Management

#### Use AWS Secrets Manager
```bash
# Store database password
aws secretsmanager create-secret \
  --name resortslite/db-password \
  --secret-string "secure-password"

# Reference in task definition
{
  "secrets": [
    {
      "name": "DB_PASSWORD",
      "valueFrom": "arn:aws:secretsmanager:region:account:secret:resortslite/db-password"
    }
  ]
}
```

### 4. IAM Best Practices

- Use least privilege principle for IAM roles
- Separate task execution role from task role
- Enable CloudTrail for audit logging
- Rotate credentials regularly

### 5. Application Security

#### Update Dependencies
The application has known vulnerabilities:
- **log4j-core 2.14.1:** CVE-2021-44228 (Log4Shell) - CRITICAL
- **commons-collections 3.2.1:** CVE-2015-6420 - HIGH

**Update pom.xml:**
```xml
<dependency>
    <groupId>org.apache.logging.log4j</groupId>
    <artifactId>log4j-core</artifactId>
    <version>2.17.1</version>
</dependency>
<dependency>
    <groupId>org.apache.commons</groupId>
    <artifactId>commons-collections4</artifactId>
    <version>4.4</version>
</dependency>
```

---

## Scaling and Management

### Auto Scaling

#### Configure Service Auto Scaling
```bash
# Register scalable target
aws application-autoscaling register-scalable-target \
  --service-namespace ecs \
  --resource-id service/resortslite-cluster/resortslite-service \
  --scalable-dimension ecs:service:DesiredCount \
  --min-capacity 2 \
  --max-capacity 10

# Create scaling policy based on CPU
aws application-autoscaling put-scaling-policy \
  --service-namespace ecs \
  --resource-id service/resortslite-cluster/resortslite-service \
  --scalable-dimension ecs:service:DesiredCount \
  --policy-name cpu-scaling-policy \
  --policy-type TargetTrackingScaling \
  --target-tracking-scaling-policy-configuration file://scaling-policy.json
```

**scaling-policy.json:**
```json
{
  "TargetValue": 70.0,
  "PredefinedMetricSpecification": {
    "PredefinedMetricType": "ECSServiceAverageCPUUtilization"
  },
  "ScaleInCooldown": 300,
  "ScaleOutCooldown": 60
}
```

### Blue/Green Deployments

Use AWS CodeDeploy for blue/green deployments:

```bash
# Create CodeDeploy application
aws deploy create-application \
  --application-name resortslite-app \
  --compute-platform ECS

# Create deployment group
aws deploy create-deployment-group \
  --application-name resortslite-app \
  --deployment-group-name resortslite-dg \
  --service-role-arn arn:aws:iam::account:role/CodeDeployServiceRole \
  --ecs-services clusterName=resortslite-cluster,serviceName=resortslite-service \
  --load-balancer-info targetGroupInfoList=[{name=resortslite-tg}] \
  --blue-green-deployment-configuration file://blue-green-config.json
```

### Rolling Updates

Update service with new task definition:
```bash
# Register new task definition
aws ecs register-task-definition --cli-input-json file://ecs/task-definition.json

# Update service
aws ecs update-service \
  --cluster resortslite-cluster \
  --service resortslite-service \
  --task-definition resortslite-task:2 \
  --force-new-deployment
```

### Capacity Provider Strategy

Use Fargate Spot for cost optimization:
```bash
# Create capacity provider
aws ecs put-cluster-capacity-providers \
  --cluster resortslite-cluster \
  --capacity-providers FARGATE FARGATE_SPOT \
  --default-capacity-provider-strategy \
    capacityProvider=FARGATE,weight=1,base=2 \
    capacityProvider=FARGATE_SPOT,weight=4
```

---

## Additional Resources

### AWS Documentation
- [ECS Fargate Documentation](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/AWS_Fargate.html)
- [ECS Task Definitions](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task_definitions.html)
- [ECS Service Auto Scaling](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/service-auto-scaling.html)

### Spring Boot Resources
- [Spring Boot Docker Guide](https://spring.io/guides/gs/spring-boot-docker/)
- [Spring Boot Actuator](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html)
- [Spring Boot on AWS](https://aws.amazon.com/blogs/opensource/spring-boot-on-aws/)

### Best Practices
- [AWS ECS Best Practices](https://docs.aws.amazon.com/AmazonECS/latest/bestpracticesguide/intro.html)
- [Docker Best Practices](https://docs.docker.com/develop/dev-best-practices/)
- [Java Container Best Practices](https://developers.redhat.com/blog/2017/03/14/java-inside-docker)

---

## Support and Maintenance

### Monitoring Checklist
- [ ] CloudWatch alarms configured for CPU/Memory
- [ ] Log aggregation and retention policies set
- [ ] Health check endpoints monitored
- [ ] Auto-scaling policies tested
- [ ] Backup and disaster recovery plan in place

### Regular Maintenance Tasks
- Update base images monthly
- Review and update dependencies quarterly
- Rotate credentials every 90 days
- Review CloudWatch logs for errors weekly
- Test disaster recovery procedures quarterly

---

## Conclusion

This deployment guide provides comprehensive instructions for deploying ResortsLite to AWS ECS Fargate. Follow the steps carefully, and refer to the troubleshooting section for common issues. For production deployments, ensure all security best practices are implemented and monitoring is properly configured.

For questions or issues, consult the AWS documentation or contact your DevOps team.

---

**Document Version:** 1.0  
**Last Updated:** 2024  
**Maintained By:** DevOps Team

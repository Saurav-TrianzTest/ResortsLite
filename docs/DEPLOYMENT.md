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

ResortsLite is a Spring Boot 2.7.x application built with Java 8 that provides resort booking functionality. This guide covers containerization and deployment to AWS ECS Fargate.

**Application Details:**
- **Framework**: Spring Boot 2.7.18
- **Java Version**: 1.8
- **Build Tool**: Maven
- **Application Port**: 8080
- **Health Endpoint**: `/actuator/health`
- **Management Endpoints**: `/actuator/health`, `/actuator/info`

**Key Features:**
- RESTful API for resort bookings
- Redis-based distributed session management
- AWS S3 integration for report storage
- H2 in-memory database (configurable for external databases)
- Spring Boot Actuator for health monitoring

---

## Prerequisites

### Required Software
- **Docker**: Version 20.10 or higher
- **Docker Compose**: Version 1.29 or higher (for local development)
- **AWS CLI**: Version 2.x
- **Java**: JDK 8 or higher (for local development)
- **Maven**: Version 3.6 or higher (for local builds)

### AWS Account Requirements
- Active AWS account with appropriate permissions
- IAM user with permissions for:
  - ECS (Elastic Container Service)
  - ECR (Elastic Container Registry)
  - VPC and networking
  - CloudWatch Logs
  - IAM role creation
  - Application Load Balancer (optional)

### AWS CLI Configuration
```bash
# Configure AWS CLI
aws configure

# Verify configuration
aws sts get-caller-identity
```

---

## Local Development Setup

### 1. Clone and Build Locally

```bash
# Navigate to project directory
cd Newresortslitecheck

# Build with Maven
mvn clean package -DskipTests

# Run locally
java -jar target/resortsLite-1.0.0.jar
```

### 2. Run with Docker Compose

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

### 3. Environment Variables for Local Development

Create a `.env` file in the project root:

```env
# Redis Configuration
REDIS_HOST=your-redis-host
REDIS_PORT=6379
REDIS_PASSWORD=your-redis-password

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
# Make script executable
chmod +x scripts/build-push.sh

# Run the script
./scripts/build-push.sh
```

The script will prompt you to:
1. Select registry type (AWS ECR or Docker Hub)
2. Enter registry credentials and details
3. Enter image tag (default: latest)

### Option 2: Using build-push.bat (Windows)

```cmd
# Run the script
scripts\build-push.bat
```

### Manual Docker Build

```bash
# Build the image
docker build -t resortslite:latest .

# Tag for ECR
docker tag resortslite:latest 123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest

# Login to ECR
aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin 123456789012.dkr.ecr.us-east-1.amazonaws.com

# Push to ECR
docker push 123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest
```

---

## AWS ECS Fargate Prerequisites

### 1. VPC and Networking Setup

You need a VPC with at least 2 subnets in different availability zones:

```bash
# Create VPC (if needed)
aws ec2 create-vpc --cidr-block 10.0.0.0/16 --region us-east-1

# Create subnets
aws ec2 create-subnet --vpc-id vpc-xxxxx --cidr-block 10.0.1.0/24 --availability-zone us-east-1a
aws ec2 create-subnet --vpc-id vpc-xxxxx --cidr-block 10.0.2.0/24 --availability-zone us-east-1b

# Create Internet Gateway
aws ec2 create-internet-gateway
aws ec2 attach-internet-gateway --vpc-id vpc-xxxxx --internet-gateway-id igw-xxxxx
```

### 2. Security Group Configuration

Create a security group that allows:
- Inbound: Port 8080 (application)
- Inbound: Port 80 (if using ALB)
- Outbound: All traffic

```bash
# Create security group
aws ec2 create-security-group \
  --group-name resortslite-sg \
  --description "Security group for ResortsLite ECS tasks" \
  --vpc-id vpc-xxxxx

# Add inbound rules
aws ec2 authorize-security-group-ingress \
  --group-id sg-xxxxx \
  --protocol tcp \
  --port 8080 \
  --cidr 0.0.0.0/0

aws ec2 authorize-security-group-ingress \
  --group-id sg-xxxxx \
  --protocol tcp \
  --port 80 \
  --cidr 0.0.0.0/0
```

### 3. IAM Roles Setup

#### ECS Task Execution Role

This role allows ECS to pull images from ECR and write logs to CloudWatch:

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

# Create the role
aws iam create-role \
  --role-name ecsTaskExecutionRole \
  --assume-role-policy-document file://ecs-task-execution-trust-policy.json

# Attach AWS managed policy
aws iam attach-role-policy \
  --role-name ecsTaskExecutionRole \
  --policy-arn arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy
```

#### ECS Task Role (Optional)

This role grants permissions to the application (e.g., S3 access):

```bash
# Create task role
aws iam create-role \
  --role-name ecsTaskRole \
  --assume-role-policy-document file://ecs-task-execution-trust-policy.json

# Create and attach S3 policy
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "s3:GetObject",
        "s3:PutObject",
        "s3:ListBucket"
      ],
      "Resource": [
        "arn:aws:s3:::resortslite-reports",
        "arn:aws:s3:::resortslite-reports/*"
      ]
    }
  ]
}

aws iam put-role-policy \
  --role-name ecsTaskRole \
  --policy-name S3AccessPolicy \
  --policy-document file://s3-policy.json
```

### 4. CloudWatch Log Group

```bash
# Create log group
aws logs create-log-group --log-group-name /ecs/resortslite --region us-east-1

# Set retention policy (optional)
aws logs put-retention-policy \
  --log-group-name /ecs/resortslite \
  --retention-in-days 7 \
  --region us-east-1
```

---

## ECS Task Definition Explained

The task definition (`ecs/task-definition.json`) defines how your container runs on Fargate.

### Key Components

#### 1. Fargate Configuration
```json
{
  "requiresCompatibilities": ["FARGATE"],
  "networkMode": "awsvpc",
  "cpu": "512",
  "memory": "1024"
}
```

**Valid Fargate CPU/Memory Combinations:**
- CPU: 256 (.25 vCPU) → Memory: 512, 1024, 2048 MB
- CPU: 512 (.5 vCPU) → Memory: 1024, 2048, 3072, 4096 MB
- CPU: 1024 (1 vCPU) → Memory: 2048-8192 MB (1GB increments)
- CPU: 2048 (2 vCPU) → Memory: 4096-16384 MB (1GB increments)
- CPU: 4096 (4 vCPU) → Memory: 8192-30720 MB (1GB increments)

#### 2. IAM Roles
```json
{
  "executionRoleArn": "arn:aws:iam::ACCOUNT_ID:role/ecsTaskExecutionRole",
  "taskRoleArn": "arn:aws:iam::ACCOUNT_ID:role/ecsTaskRole"
}
```

- **executionRoleArn**: Required for Fargate to pull images and write logs
- **taskRoleArn**: Optional, grants permissions to your application

#### 3. Container Definition
```json
{
  "name": "resortslite",
  "image": "IMAGE_URI",
  "essential": true,
  "portMappings": [
    {
      "containerPort": 8080,
      "protocol": "tcp"
    }
  ]
}
```

#### 4. Environment Variables
Configure application settings via environment variables:
- `SERVER_PORT`: Application port (8080)
- `SPRING_PROFILES_ACTIVE`: Spring profile (docker)
- `JAVA_OPTS`: JVM options
- `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`: Redis configuration
- `S3_BUCKET_NAME`, `AWS_REGION`: AWS S3 configuration

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

The service definition (`ecs/service-definition.json`) manages how tasks are deployed and scaled.

### Key Components

#### 1. Launch Type and Networking
```json
{
  "launchType": "FARGATE",
  "networkConfiguration": {
    "awsvpcConfiguration": {
      "subnets": ["subnet-xxxxx", "subnet-yyyyy"],
      "securityGroups": ["sg-xxxxx"],
      "assignPublicIp": "ENABLED"
    }
  }
}
```

- **awsvpc**: Required network mode for Fargate
- **subnets**: At least 2 subnets in different AZs for high availability
- **assignPublicIp**: ENABLED if tasks need internet access

#### 2. Deployment Configuration
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

- **maximumPercent**: Maximum tasks during deployment (200% = double capacity)
- **minimumHealthyPercent**: Minimum healthy tasks (50% = half capacity)
- **deploymentCircuitBreaker**: Automatic rollback on failure

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

---

## Deployment to AWS ECS Fargate

### Automated Deployment

#### Linux/macOS
```bash
# Make script executable
chmod +x scripts/deploy-image.sh

# Run deployment
./scripts/deploy-image.sh
```

#### Windows
```cmd
scripts\deploy-image.bat
```

### Deployment Steps

The deployment script will:

1. **Prompt for Configuration**:
   - AWS Region
   - ECS Cluster Name
   - Docker Image URI
   - VPC and Subnet IDs
   - Security Group ID
   - Redis configuration
   - S3 bucket name

2. **Create/Verify Infrastructure**:
   - Check if ECS cluster exists (create if needed)
   - Create CloudWatch log group
   - Optionally create Application Load Balancer and Target Group

3. **Register Task Definition**:
   - Replace placeholders with actual values
   - Register new task definition revision

4. **Create/Update Service**:
   - Create new service if it doesn't exist
   - Update existing service with new task definition

5. **Wait for Stability**:
   - Wait for service to reach stable state
   - Display service details and access information

### Manual Deployment

```bash
# 1. Register task definition
aws ecs register-task-definition \
  --cli-input-json file://ecs/task-definition.json \
  --region us-east-1

# 2. Create ECS cluster
aws ecs create-cluster \
  --cluster-name resortslite-cluster \
  --region us-east-1

# 3. Create service
aws ecs create-service \
  --cli-input-json file://ecs/service-definition.json \
  --region us-east-1

# 4. Wait for service to stabilize
aws ecs wait services-stable \
  --cluster resortslite-cluster \
  --services resortslite-service \
  --region us-east-1
```

---

## Configuration Management

### Environment-Specific Configuration

#### Development
```bash
# Use H2 in-memory database
DB_URL=jdbc:h2:mem:resortdb
REDIS_HOST=localhost
S3_BUCKET_NAME=resortslite-dev-reports
```

#### Production
```bash
# Use external database
DB_URL=jdbc:postgresql://prod-db.example.com:5432/resortdb
DB_USERNAME=prod_user
DB_PASSWORD=secure_password
REDIS_HOST=prod-redis.example.com
S3_BUCKET_NAME=resortslite-prod-reports
```

### AWS Systems Manager Parameter Store

Store sensitive configuration in Parameter Store:

```bash
# Store Redis password
aws ssm put-parameter \
  --name /resortslite/prod/redis-password \
  --value "your-secure-password" \
  --type SecureString \
  --region us-east-1

# Store database password
aws ssm put-parameter \
  --name /resortslite/prod/db-password \
  --value "your-db-password" \
  --type SecureString \
  --region us-east-1
```

Update task definition to use secrets:
```json
{
  "secrets": [
    {
      "name": "REDIS_PASSWORD",
      "valueFrom": "arn:aws:ssm:us-east-1:123456789012:parameter/resortslite/prod/redis-password"
    }
  ]
}
```

---

## Monitoring and Logging

### CloudWatch Logs

View application logs:
```bash
# Tail logs in real-time
aws logs tail /ecs/resortslite --follow --region us-east-1

# Filter logs
aws logs filter-log-events \
  --log-group-name /ecs/resortslite \
  --filter-pattern "ERROR" \
  --region us-east-1

# Get logs for specific time range
aws logs filter-log-events \
  --log-group-name /ecs/resortslite \
  --start-time 1609459200000 \
  --end-time 1609545600000 \
  --region us-east-1
```

### CloudWatch Metrics

Monitor ECS service metrics:
- CPUUtilization
- MemoryUtilization
- TargetResponseTime (if using ALB)
- RequestCount (if using ALB)

### Application Health Monitoring

Access health endpoints:
```bash
# Health check
curl http://your-alb-dns/actuator/health

# Application info
curl http://your-alb-dns/actuator/info
```

### CloudWatch Alarms

Create alarms for critical metrics:

```bash
# CPU utilization alarm
aws cloudwatch put-metric-alarm \
  --alarm-name resortslite-high-cpu \
  --alarm-description "Alert when CPU exceeds 80%" \
  --metric-name CPUUtilization \
  --namespace AWS/ECS \
  --statistic Average \
  --period 300 \
  --threshold 80 \
  --comparison-operator GreaterThanThreshold \
  --evaluation-periods 2 \
  --dimensions Name=ServiceName,Value=resortslite-service Name=ClusterName,Value=resortslite-cluster

# Memory utilization alarm
aws cloudwatch put-metric-alarm \
  --alarm-name resortslite-high-memory \
  --alarm-description "Alert when memory exceeds 80%" \
  --metric-name MemoryUtilization \
  --namespace AWS/ECS \
  --statistic Average \
  --period 300 \
  --threshold 80 \
  --comparison-operator GreaterThanThreshold \
  --evaluation-periods 2 \
  --dimensions Name=ServiceName,Value=resortslite-service Name=ClusterName,Value=resortslite-cluster
```

---

## Troubleshooting

### Common Issues

#### 1. Task Fails to Start

**Symptoms**: Tasks transition from PENDING to STOPPED immediately

**Possible Causes**:
- Invalid CPU/memory combination
- Image pull errors (ECR permissions)
- Missing IAM roles
- Invalid environment variables

**Solutions**:
```bash
# Check task stopped reason
aws ecs describe-tasks \
  --cluster resortslite-cluster \
  --tasks TASK_ARN \
  --region us-east-1 \
  --query 'tasks[0].stoppedReason'

# Check CloudWatch logs for errors
aws logs tail /ecs/resortslite --follow --region us-east-1

# Verify IAM roles
aws iam get-role --role-name ecsTaskExecutionRole
aws iam get-role --role-name ecsTaskRole
```

#### 2. Network Connectivity Issues

**Symptoms**: Tasks can't reach external services (Redis, S3, etc.)

**Solutions**:
- Verify security group allows outbound traffic
- Check subnet route tables have internet gateway route
- Verify NAT gateway if using private subnets
- Ensure `assignPublicIp: ENABLED` if using public subnets

```bash
# Check security group rules
aws ec2 describe-security-groups --group-ids sg-xxxxx

# Check route tables
aws ec2 describe-route-tables --filters "Name=vpc-id,Values=vpc-xxxxx"
```

#### 3. Health Check Failures

**Symptoms**: Tasks fail health checks and are replaced continuously

**Solutions**:
- Verify application is listening on correct port (8080)
- Check health endpoint is accessible: `/actuator/health`
- Increase `healthCheckGracePeriodSeconds` for slow startup
- Review application logs for startup errors

```bash
# Test health endpoint from within VPC
curl http://TASK_PRIVATE_IP:8080/actuator/health

# Check task health status
aws ecs describe-tasks \
  --cluster resortslite-cluster \
  --tasks TASK_ARN \
  --region us-east-1 \
  --query 'tasks[0].healthStatus'
```

#### 4. Out of Memory Errors

**Symptoms**: Tasks crash with OOM errors in logs

**Solutions**:
- Increase task memory allocation
- Adjust JVM heap size in JAVA_OPTS
- Review application memory usage patterns

```bash
# Update task definition with more memory
# Change "memory": "1024" to "memory": "2048"
# And adjust JAVA_OPTS: "-Xmx1536m -Xms768m"
```

#### 5. Image Pull Errors

**Symptoms**: "CannotPullContainerError" in task stopped reason

**Solutions**:
```bash
# Verify ECR repository exists
aws ecr describe-repositories --repository-names resortslite --region us-east-1

# Check ECR permissions
aws ecr get-repository-policy --repository-name resortslite --region us-east-1

# Verify executionRoleArn has ECR permissions
aws iam list-attached-role-policies --role-name ecsTaskExecutionRole
```

### Debugging Commands

```bash
# List running tasks
aws ecs list-tasks \
  --cluster resortslite-cluster \
  --service-name resortslite-service \
  --region us-east-1

# Describe task details
aws ecs describe-tasks \
  --cluster resortslite-cluster \
  --tasks TASK_ARN \
  --region us-east-1

# Get service events
aws ecs describe-services \
  --cluster resortslite-cluster \
  --services resortslite-service \
  --region us-east-1 \
  --query 'services[0].events[0:10]'

# Check task definition
aws ecs describe-task-definition \
  --task-definition resortslite-task \
  --region us-east-1

# View CloudWatch logs
aws logs get-log-events \
  --log-group-name /ecs/resortslite \
  --log-stream-name ecs/resortslite/TASK_ID \
  --region us-east-1
```

---

## Security Considerations

### 1. Network Security

- **Use Private Subnets**: Deploy tasks in private subnets with NAT gateway
- **Security Groups**: Restrict inbound traffic to necessary ports only
- **VPC Endpoints**: Use VPC endpoints for AWS services (ECR, S3, CloudWatch)

```bash
# Create VPC endpoint for ECR
aws ec2 create-vpc-endpoint \
  --vpc-id vpc-xxxxx \
  --service-name com.amazonaws.us-east-1.ecr.dkr \
  --route-table-ids rtb-xxxxx \
  --region us-east-1
```

### 2. IAM Best Practices

- **Least Privilege**: Grant only necessary permissions
- **Separate Roles**: Use different roles for execution and task
- **Rotate Credentials**: Regularly rotate AWS access keys

### 3. Secrets Management

- **Never Hardcode Secrets**: Use Parameter Store or Secrets Manager
- **Encrypt at Rest**: Use KMS encryption for sensitive data
- **Audit Access**: Enable CloudTrail for secrets access logging

### 4. Container Security

- **Non-Root User**: Application runs as non-root user (appuser)
- **Read-Only Root**: Consider making root filesystem read-only
- **Scan Images**: Regularly scan images for vulnerabilities

```bash
# Scan ECR image for vulnerabilities
aws ecr start-image-scan \
  --repository-name resortslite \
  --image-id imageTag=latest \
  --region us-east-1

# Get scan results
aws ecr describe-image-scan-findings \
  --repository-name resortslite \
  --image-id imageTag=latest \
  --region us-east-1
```

### 5. Application Security

- **Update Dependencies**: Keep Spring Boot and dependencies updated
- **Enable HTTPS**: Use ALB with SSL/TLS certificate
- **Input Validation**: Validate all user inputs
- **Rate Limiting**: Implement rate limiting at ALB level

---

## Scaling and Management

### Auto Scaling

#### Service Auto Scaling

Scale based on CPU or memory utilization:

```bash
# Register scalable target
aws application-autoscaling register-scalable-target \
  --service-namespace ecs \
  --resource-id service/resortslite-cluster/resortslite-service \
  --scalable-dimension ecs:service:DesiredCount \
  --min-capacity 2 \
  --max-capacity 10 \
  --region us-east-1

# Create scaling policy (CPU-based)
aws application-autoscaling put-scaling-policy \
  --service-namespace ecs \
  --resource-id service/resortslite-cluster/resortslite-service \
  --scalable-dimension ecs:service:DesiredCount \
  --policy-name cpu-scaling-policy \
  --policy-type TargetTrackingScaling \
  --target-tracking-scaling-policy-configuration file://scaling-policy.json \
  --region us-east-1
```

scaling-policy.json:
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

### Manual Scaling

```bash
# Scale up
aws ecs update-service \
  --cluster resortslite-cluster \
  --service resortslite-service \
  --desired-count 5 \
  --region us-east-1

# Scale down
aws ecs update-service \
  --cluster resortslite-cluster \
  --service resortslite-service \
  --desired-count 2 \
  --region us-east-1
```

### Blue/Green Deployments

Use AWS CodeDeploy for blue/green deployments:

```bash
# Create CodeDeploy application
aws deploy create-application \
  --application-name resortslite-app \
  --compute-platform ECS \
  --region us-east-1

# Create deployment group
aws deploy create-deployment-group \
  --application-name resortslite-app \
  --deployment-group-name resortslite-dg \
  --service-role-arn arn:aws:iam::ACCOUNT_ID:role/CodeDeployServiceRole \
  --ecs-services clusterName=resortslite-cluster,serviceName=resortslite-service \
  --load-balancer-info targetGroupPairInfoList=[...] \
  --region us-east-1
```

### Rolling Updates

Update service with new task definition:

```bash
# Register new task definition
aws ecs register-task-definition \
  --cli-input-json file://ecs/task-definition.json \
  --region us-east-1

# Update service
aws ecs update-service \
  --cluster resortslite-cluster \
  --service resortslite-service \
  --task-definition resortslite-task:NEW_REVISION \
  --region us-east-1

# Monitor deployment
aws ecs describe-services \
  --cluster resortslite-cluster \
  --services resortslite-service \
  --region us-east-1 \
  --query 'services[0].deployments'
```

---

## Additional Resources

### AWS Documentation
- [Amazon ECS Developer Guide](https://docs.aws.amazon.com/ecs/)
- [AWS Fargate Documentation](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/AWS_Fargate.html)
- [ECS Task Definitions](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task_definitions.html)

### Spring Boot Resources
- [Spring Boot Actuator](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html)
- [Spring Boot Docker](https://spring.io/guides/gs/spring-boot-docker/)
- [Spring Session with Redis](https://docs.spring.io/spring-session/docs/current/reference/html5/)

### Best Practices
- [ECS Best Practices Guide](https://docs.aws.amazon.com/AmazonECS/latest/bestpracticesguide/intro.html)
- [Docker Best Practices](https://docs.docker.com/develop/dev-best-practices/)
- [Java in Containers](https://developers.redhat.com/blog/2017/03/14/java-inside-docker/)

---

## Support and Maintenance

### Regular Maintenance Tasks

1. **Update Dependencies**: Regularly update Spring Boot and Java dependencies
2. **Patch Security Vulnerabilities**: Monitor and patch CVEs
3. **Review Logs**: Regularly review CloudWatch logs for errors
4. **Monitor Costs**: Track ECS and related AWS service costs
5. **Backup Configuration**: Version control all configuration files

### Cost Optimization

- Use Fargate Spot for non-critical workloads
- Right-size CPU and memory allocations
- Implement auto-scaling to match demand
- Use CloudWatch Logs retention policies
- Clean up unused ECR images

```bash
# List old ECR images
aws ecr list-images \
  --repository-name resortslite \
  --region us-east-1

# Delete old images
aws ecr batch-delete-image \
  --repository-name resortslite \
  --image-ids imageTag=old-tag \
  --region us-east-1
```

---

## Conclusion

This guide provides comprehensive instructions for deploying ResortsLite to AWS ECS Fargate. Follow the steps carefully, and refer to the troubleshooting section if you encounter issues.

For questions or issues, consult the AWS documentation or contact your DevOps team.

**Happy Deploying! 🚀**

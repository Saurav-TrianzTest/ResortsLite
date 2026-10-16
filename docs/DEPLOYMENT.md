# ResortsLite — Deployment Guide

## Table of Contents
1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Project Structure](#project-structure)
4. [Local Development with Docker Compose](#local-development-with-docker-compose)
5. [Building and Pushing the Docker Image](#building-and-pushing-the-docker-image)
6. [AWS EKS Deployment](#aws-eks-deployment)
7. [Configuration Management](#configuration-management)
8. [Scaling and Management](#scaling-and-management)
9. [Troubleshooting](#troubleshooting)
10. [Security Considerations](#security-considerations)

---

## Overview

**Application**: ResortsLite  
**Framework**: Spring Boot 2.7.x  
**Java Version**: Java 8  
**Build Tool**: Maven  
**Target Platform**: AWS EKS (Elastic Kubernetes Service)  
**Application Port**: 8080  
**Health Endpoint**: `/actuator/health`

ResortsLite is a legacy resort booking application modernized for cloud-native deployment on AWS EKS. It uses Spring Session backed by Amazon ElastiCache (Redis) for distributed session management and a Redis-backed distributed cache for booking data.

---

## Prerequisites

### Local Development
- Docker Desktop 24.x or later
- Docker Compose v2.x or later
- Java 8 JDK (for local builds outside Docker)
- Maven 3.8.x or later (for local builds outside Docker)

### AWS EKS Deployment
- AWS CLI v2 configured with appropriate IAM permissions
- `kubectl` v1.27 or later
- `eksctl` v0.160 or later (for cluster creation)
- An existing AWS EKS cluster (or permissions to create one)
- AWS ECR repository (auto-created by `build-push.sh`)
- Amazon ElastiCache for Redis cluster (for session and cache storage)

### Required IAM Permissions
```
ecr:GetAuthorizationToken
ecr:BatchCheckLayerAvailability
ecr:GetDownloadUrlForLayer
ecr:BatchGetImage
ecr:PutImage
ecr:InitiateLayerUpload
ecr:UploadLayerPart
ecr:CompleteLayerUpload
ecr:CreateRepository
ecr:DescribeRepositories
eks:DescribeCluster
eks:ListClusters
```

---

## Project Structure

```
IDEJAVAContainer/
├── Dockerfile                    # Multi-stage Docker build
├── docker-compose.yml            # Local development compose file
├── .dockerignore                 # Docker build exclusions
├── pom.xml                       # Maven build descriptor
├── src/
│   └── main/
│       ├── java/com/demo/resortslite/
│       │   ├── ResortsLiteApplication.java
│       │   ├── BookingController.java
│       │   ├── BookingService.java
│       │   ├── ReportService.java
│       │   └── RedisSessionConfig.java
│       └── resources/
│           └── application.properties
├── kubernetes/
│   ├── namespace.yaml            # Kubernetes namespace
│   ├── deployment.yaml           # Application deployment
│   ├── service.yaml              # ClusterIP service
│   └── ingress.yaml              # AWS ALB ingress
├── scripts/
│   ├── build-push.sh             # Linux/macOS build & push
│   ├── build-push.bat            # Windows build & push
│   ├── deploy-image.sh           # Linux/macOS EKS deploy
│   └── deploy-image.bat          # Windows EKS deploy
└── docs/
    └── DEPLOYMENT.md             # This file
```

---

## Local Development with Docker Compose

### Step 1: Configure Environment Variables

Create a `.env` file in the project root (never commit this file):

```bash
REDIS_HOST=your-elasticache-endpoint.cache.amazonaws.com
REDIS_PORT=6379
REPORT_BASE_PATH=/var/reports
BACKUP_PATH=/var/backups
CACHE_TTL_SECONDS=3600
PAYMENT_API_URL=http://payment-svc.payments.svc.cluster.local:9090/payments/charge
```

> **Note**: For local development without ElastiCache, you can run a local Redis container separately and set `REDIS_HOST=host.docker.internal`.

### Step 2: Build and Start the Application

```bash
# Build and start the application container
docker-compose up --build

# Run in detached mode
docker-compose up --build -d

# View logs
docker-compose logs -f resortslite

# Stop the application
docker-compose down
```

### Step 3: Verify the Application

```bash
# Health check
curl http://localhost:8080/actuator/health

# Test booking creation
curl -X POST "http://localhost:8080/api/bookings/create?guestName=John&roomType=SUITE&checkIn=2024-06-01&checkOut=2024-06-05"

# Check availability
curl "http://localhost:8080/api/bookings/availability?roomType=SUITE"
```

---

## Building and Pushing the Docker Image

### Linux / macOS

```bash
# Make the script executable
chmod +x scripts/build-push.sh

# Run the build and push script
./scripts/build-push.sh
```

The script will prompt you to:
1. Enter an image tag (defaults to `latest`)
2. Select a registry (AWS ECR or Docker Hub)
3. Provide registry credentials

### Windows

```cmd
scripts\build-push.bat
```

### Manual Docker Build

```bash
# Build the image
docker build -t resortslite:latest .

# Tag for ECR
docker tag resortslite:latest 123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest

# Authenticate with ECR
aws ecr get-login-password --region us-east-1 | \
  docker login --username AWS --password-stdin 123456789012.dkr.ecr.us-east-1.amazonaws.com

# Push to ECR
docker push 123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest
```

---

## AWS EKS Deployment

### Step 1: Set Up EKS Cluster (if not already created)

```bash
# Create EKS cluster using eksctl
eksctl create cluster \
  --name resortslite-cluster \
  --region us-east-1 \
  --nodegroup-name standard-workers \
  --node-type t3.medium \
  --nodes 2 \
  --nodes-min 1 \
  --nodes-max 4 \
  --managed

# Configure kubectl
aws eks update-kubeconfig --region us-east-1 --name resortslite-cluster
```

### Step 2: Install AWS Load Balancer Controller

The ingress manifest uses the AWS Load Balancer Controller for ALB provisioning.

```bash
# Add the EKS chart repository
helm repo add eks https://aws.github.io/eks-charts
helm repo update

# Install the AWS Load Balancer Controller
helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
  -n kube-system \
  --set clusterName=resortslite-cluster \
  --set serviceAccount.create=false \
  --set serviceAccount.name=aws-load-balancer-controller
```

### Step 3: Set Up Amazon ElastiCache for Redis

```bash
# Create ElastiCache subnet group (replace with your VPC subnet IDs)
aws elasticache create-cache-subnet-group \
  --cache-subnet-group-name resortslite-redis-subnet \
  --cache-subnet-group-description "ResortsLite Redis subnet group" \
  --subnet-ids subnet-xxxxxxxx subnet-yyyyyyyy

# Create Redis cluster
aws elasticache create-cache-cluster \
  --cache-cluster-id resortslite-redis \
  --cache-node-type cache.t3.micro \
  --engine redis \
  --num-cache-nodes 1 \
  --cache-subnet-group-name resortslite-redis-subnet
```

### Step 4: Deploy to EKS

#### Linux / macOS

```bash
chmod +x scripts/deploy-image.sh
./scripts/deploy-image.sh
```

#### Windows

```cmd
scripts\deploy-image.bat
```

The script will prompt for:
- AWS region
- EKS cluster name
- Full Docker image URI (with tag)
- Redis host (ElastiCache endpoint)
- Redis port
- Cache TTL seconds
- Payment API URL

### Step 5: Verify Deployment

```bash
# Check all resources in the namespace
kubectl get all -n resortslite

# Check pod logs
kubectl logs -l app=resortslite -n resortslite --tail=100

# Check ingress and get ALB hostname
kubectl get ingress -n resortslite

# Test health endpoint
curl http://<ALB_HOSTNAME>/actuator/health
```

### Manual Kubernetes Deployment

If you prefer to apply manifests manually:

```bash
# Apply in order
kubectl apply -f kubernetes/namespace.yaml
kubectl apply -f kubernetes/deployment.yaml
kubectl apply -f kubernetes/service.yaml
kubectl apply -f kubernetes/ingress.yaml

# Wait for rollout
kubectl rollout status deployment/resortslite -n resortslite

# Verify
kubectl get pods,svc,ingress -n resortslite
```

---

## Configuration Management

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `SERVER_PORT` | Application HTTP port | `8080` |
| `SPRING_PROFILES_ACTIVE` | Active Spring profile | `docker` |
| `REDIS_HOST` | ElastiCache Redis endpoint | `localhost` |
| `REDIS_PORT` | Redis port | `6379` |
| `CACHE_TTL_SECONDS` | Booking cache TTL in seconds | `3600` |
| `PAYMENT_API_URL` | Payment service URL | `http://payment-svc.payments.svc.cluster.local:9090/payments/charge` |
| `REPORT_BASE_PATH` | Directory for generated reports | `/var/reports` |
| `BACKUP_PATH` | Directory for backup files | `/var/backups` |
| `JAVA_OPTS` | JVM options | `-Xmx512m -Xms256m -XX:+UseContainerSupport` |

### Using Kubernetes ConfigMap

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: resortslite-config
  namespace: resortslite
data:
  REDIS_HOST: "your-elasticache-endpoint.cache.amazonaws.com"
  REDIS_PORT: "6379"
  CACHE_TTL_SECONDS: "3600"
  PAYMENT_API_URL: "http://payment-svc.payments.svc.cluster.local:9090/payments/charge"
```

Apply with: `kubectl apply -f configmap.yaml`

### Using Kubernetes Secrets (for sensitive values)

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: resortslite-secrets
  namespace: resortslite
type: Opaque
stringData:
  REDIS_AUTH_TOKEN: "your-redis-auth-token"
```

Apply with: `kubectl apply -f secret.yaml`

---

## Scaling and Management

### Horizontal Pod Autoscaling

```bash
# Create HPA based on CPU utilization
kubectl autoscale deployment resortslite \
  --namespace resortslite \
  --cpu-percent=70 \
  --min=2 \
  --max=10

# Check HPA status
kubectl get hpa -n resortslite
```

### Manual Scaling

```bash
# Scale to 4 replicas
kubectl scale deployment resortslite --replicas=4 -n resortslite
```

### Rolling Updates

```bash
# Update the image
kubectl set image deployment/resortslite \
  resortslite=123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:v2.0.0 \
  -n resortslite

# Monitor rollout
kubectl rollout status deployment/resortslite -n resortslite
```

### Rollback

```bash
# Rollback to previous version
kubectl rollout undo deployment/resortslite -n resortslite

# Rollback to a specific revision
kubectl rollout history deployment/resortslite -n resortslite
kubectl rollout undo deployment/resortslite --to-revision=2 -n resortslite
```

---

## Troubleshooting

### Pod Not Starting

```bash
# Check pod status and events
kubectl describe pod -l app=resortslite -n resortslite

# Check pod logs
kubectl logs -l app=resortslite -n resortslite --previous

# Check resource constraints
kubectl top pods -n resortslite
```

### Application Health Check Failing

```bash
# Check if the actuator endpoint is accessible
kubectl exec -it <pod-name> -n resortslite -- \
  wget -qO- http://localhost:8080/actuator/health

# Check Spring Boot startup logs
kubectl logs <pod-name> -n resortslite | grep -E "Started|ERROR|WARN"
```

### Redis Connection Issues

```bash
# Verify Redis environment variables in the pod
kubectl exec -it <pod-name> -n resortslite -- env | grep REDIS

# Check if the pod can reach the Redis endpoint
kubectl exec -it <pod-name> -n resortslite -- \
  wget -qO- telnet://${REDIS_HOST}:${REDIS_PORT} || echo "Connection test"
```

### Ingress / ALB Issues

```bash
# Check ingress status
kubectl describe ingress resortslite-ingress -n resortslite

# Check AWS Load Balancer Controller logs
kubectl logs -n kube-system -l app.kubernetes.io/name=aws-load-balancer-controller

# Verify ALB target group health
aws elbv2 describe-target-health \
  --target-group-arn <target-group-arn>
```

### JVM Memory Issues

If pods are OOMKilled, increase memory limits in `kubernetes/deployment.yaml`:

```yaml
resources:
  requests:
    cpu: "500m"
    memory: "768Mi"
  limits:
    cpu: "1000m"
    memory: "1536Mi"
```

Also adjust `JAVA_OPTS`:
```
-Xmx1g -Xms512m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0
```

---

## Security Considerations

1. **Non-root Container**: The application runs as a non-root user (`appuser`) inside the container.

2. **Secrets Management**: Never hardcode credentials. Use:
   - AWS Secrets Manager for database passwords and API keys
   - Kubernetes Secrets for Redis auth tokens
   - AWS Parameter Store for configuration values

3. **Network Policies**: Consider adding Kubernetes NetworkPolicies to restrict pod-to-pod communication.

4. **Image Scanning**: Enable ECR image scanning to detect vulnerabilities:
   ```bash
   aws ecr put-image-scanning-configuration \
     --repository-name resortslite \
     --image-scanning-configuration scanOnPush=true
   ```

5. **IRSA (IAM Roles for Service Accounts)**: Use IRSA to grant the application pod access to AWS services without static credentials.

6. **TLS/HTTPS**: The ingress is configured to redirect HTTP to HTTPS. Ensure you have an ACM certificate associated with your ALB.

7. **Dependency Vulnerabilities**: The current `pom.xml` includes known vulnerable dependencies (`log4j-core:2.14.1` with CVE-2021-44228 and `commons-collections:3.2.1` with CVE-2015-6420). These **must** be updated before production deployment:
   - Update `log4j-core` to `2.17.2` or later
   - Update `commons-collections` to `3.2.2` or later

---

## Java-Specific Notes

### JVM Container Awareness

The Dockerfile sets the following JVM flags for proper container resource detection:
- `-XX:+UseContainerSupport`: Enables JVM to detect container CPU/memory limits
- `-XX:MaxRAMPercentage=75.0`: Limits heap to 75% of container memory
- `-Xmx512m -Xms256m`: Explicit heap bounds as a safety net

### Spring Boot Actuator

The application exposes health endpoints at:
- **Liveness**: `GET /actuator/health`
- **Readiness**: `GET /actuator/health`

These are used by Kubernetes liveness and readiness probes with appropriate delays to account for JVM startup time (60s initial delay for liveness, 30s for readiness).

### Spring Session with Redis

Session data is stored in Amazon ElastiCache (Redis) via Spring Session. This enables:
- Stateless pods (no sticky sessions required on the ALB)
- Session persistence across pod restarts
- Horizontal scaling without session loss

Configure the ElastiCache endpoint via `REDIS_HOST` and `REDIS_PORT` environment variables.

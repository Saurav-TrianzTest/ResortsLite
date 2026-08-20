# BookingComp — Deployment Guide

## Overview

**Application**: BookingComp (ResortsLite)  
**Framework**: Spring Boot 2.7.x  
**Java Version**: 8  
**Build Tool**: Maven  
**Target Platform**: AWS EKS (Elastic Kubernetes Service)  
**Application Port**: 8080  
**Health Endpoint**: `/actuator/health`

---

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Project Structure](#project-structure)
3. [Local Development with Docker Compose](#local-development-with-docker-compose)
4. [Build and Push Docker Image](#build-and-push-docker-image)
5. [AWS EKS Prerequisites](#aws-eks-prerequisites)
6. [EKS Cluster Setup](#eks-cluster-setup)
7. [Kubernetes Deployment](#kubernetes-deployment)
8. [Environment Variables Reference](#environment-variables-reference)
9. [Scaling and Management](#scaling-and-management)
10. [Troubleshooting](#troubleshooting)
11. [Security Considerations](#security-considerations)

---

## Prerequisites

### Local Development
- Docker Desktop 24.x or later
- Docker Compose v2.x or later
- Java 8 JDK (for local builds outside Docker)
- Maven 3.8.x or later (for local builds outside Docker)

### AWS EKS Deployment
- AWS CLI v2 (`aws --version`)
- `kubectl` v1.27+ (`kubectl version --client`)
- `eksctl` v0.160+ (optional, for cluster creation)
- AWS IAM permissions:
  - `eks:DescribeCluster`, `eks:UpdateKubeconfig`
  - `ecr:GetAuthorizationToken`, `ecr:BatchCheckLayerAvailability`, `ecr:PutImage`
  - `ec2:DescribeVpcs`, `ec2:DescribeSubnets`

---

## Project Structure

```
BookingComp/
├── Dockerfile                  # Multi-stage Docker build
├── docker-compose.yml          # Local development compose file
├── .dockerignore               # Docker build exclusions
├── pom.xml                     # Maven build descriptor
├── src/                        # Java source code
│   └── main/
│       ├── java/com/demo/resortslite/
│       └── resources/
│           └── application.properties
├── kubernetes/
│   ├── namespace.yaml          # Kubernetes namespace
│   ├── deployment.yaml         # Application deployment
│   ├── service.yaml            # ClusterIP service
│   └── ingress.yaml            # AWS ALB ingress
├── scripts/
│   ├── build-push.sh           # Linux/macOS build & push
│   ├── build-push.bat          # Windows build & push
│   ├── deploy-image.sh         # Linux/macOS EKS deploy
│   └── deploy-image.bat        # Windows EKS deploy
└── docs/
    └── DEPLOYMENT.md           # This file
```

---

## Local Development with Docker Compose

### 1. Configure Environment Variables

Create a `.env` file in the project root (never commit this file):

```bash
# Redis / ElastiCache
SPRING_REDIS_HOST=localhost
SPRING_REDIS_PORT=6379

# External service endpoints
PAYMENT_API_URL=http://payment-service/payments/charge
REPORT_SERVICE_URL=http://report-service/api/reports

# S3 buckets
S3_REPORTS_BUCKET=resorts-reports-bucket
S3_BACKUP_BUCKET=resorts-backup-bucket

# Server
SERVER_PORT=8080
```

### 2. Start the Application

```bash
# Build and start
docker compose up --build

# Start in background
docker compose up -d --build

# View logs
docker compose logs -f bookingcomp

# Stop
docker compose down
```

### 3. Verify the Application

```bash
# Health check
curl http://localhost:8080/actuator/health

# Test booking endpoint
curl -X POST "http://localhost:8080/api/bookings/create?guestName=John&roomType=SUITE&checkIn=2024-06-01&checkOut=2024-06-05"

# Check availability
curl "http://localhost:8080/api/bookings/availability?roomType=DELUXE"
```

---

## Build and Push Docker Image

### Linux / macOS

```bash
chmod +x scripts/build-push.sh
./scripts/build-push.sh
```

The script will prompt you to:
1. Enter an image tag (default: `latest`)
2. Select registry type (AWS ECR or Docker Hub)
3. Provide registry credentials

### Windows

```cmd
scripts\build-push.bat
```

### Manual Build (AWS ECR)

```bash
# Set variables
AWS_REGION=us-east-1
AWS_ACCOUNT_ID=123456789012
IMAGE_TAG=1.0.0

# Authenticate to ECR
aws ecr get-login-password --region $AWS_REGION | \
  docker login --username AWS --password-stdin \
  ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com

# Create repository (first time only)
aws ecr create-repository --repository-name bookingcomp --region $AWS_REGION

# Build and push
docker build -t ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/bookingcomp:${IMAGE_TAG} .
docker push ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/bookingcomp:${IMAGE_TAG}
```

---

## AWS EKS Prerequisites

### 1. Install Required Tools

```bash
# AWS CLI
curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
unzip awscliv2.zip && sudo ./aws/install

# kubectl
curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
chmod +x kubectl && sudo mv kubectl /usr/local/bin/

# eksctl (optional)
curl --silent --location "https://github.com/eksctl-io/eksctl/releases/latest/download/eksctl_$(uname -s)_amd64.tar.gz" | tar xz -C /tmp
sudo mv /tmp/eksctl /usr/local/bin
```

### 2. Configure AWS CLI

```bash
aws configure
# Enter: AWS Access Key ID, Secret Access Key, Region, Output format
```

### 3. Install AWS Load Balancer Controller

The ingress manifest uses the AWS Load Balancer Controller. Install it on your EKS cluster:

```bash
# Add IAM policy for ALB controller
curl -O https://raw.githubusercontent.com/kubernetes-sigs/aws-load-balancer-controller/v2.7.0/docs/install/iam_policy.json
aws iam create-policy --policy-name AWSLoadBalancerControllerIAMPolicy --policy-document file://iam_policy.json

# Install via Helm
helm repo add eks https://aws.github.io/eks-charts
helm repo update
helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
  -n kube-system \
  --set clusterName=<YOUR_CLUSTER_NAME> \
  --set serviceAccount.create=false \
  --set serviceAccount.name=aws-load-balancer-controller
```

---

## EKS Cluster Setup

### Create a New EKS Cluster (if needed)

```bash
eksctl create cluster \
  --name bookingcomp-cluster \
  --region us-east-1 \
  --nodegroup-name standard-workers \
  --node-type t3.medium \
  --nodes 2 \
  --nodes-min 1 \
  --nodes-max 4 \
  --managed
```

### Configure kubectl

```bash
aws eks update-kubeconfig --region us-east-1 --name bookingcomp-cluster
kubectl cluster-info
kubectl get nodes
```

---

## Kubernetes Deployment

### Automated Deployment

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
- AWS Region
- EKS Cluster Name
- Full Docker image URI
- Optional environment variable values (Redis host/port, service URLs, S3 buckets)

### Manual Deployment

```bash
# Set your image URI
IMAGE_URI="123456789012.dkr.ecr.us-east-1.amazonaws.com/bookingcomp:1.0.0"

# Substitute image placeholder
sed -i 's|{{IMAGE_URI}}|'"$IMAGE_URI"'|g' kubernetes/deployment.yaml

# Substitute environment variable placeholders
sed -i 's|{{SPRING_REDIS_HOST}}|my-elasticache.abc123.ng.0001.use1.cache.amazonaws.com|g' kubernetes/deployment.yaml
sed -i 's|{{SPRING_REDIS_PORT}}|6379|g' kubernetes/deployment.yaml
sed -i 's|{{PAYMENT_API_URL}}|http://payment-service/payments/charge|g' kubernetes/deployment.yaml
sed -i 's|{{REPORT_SERVICE_URL}}|http://report-service/api/reports|g' kubernetes/deployment.yaml
sed -i 's|{{S3_REPORTS_BUCKET}}|my-resorts-reports|g' kubernetes/deployment.yaml
sed -i 's|{{S3_BACKUP_BUCKET}}|my-resorts-backup|g' kubernetes/deployment.yaml

# Apply manifests in order
kubectl apply -f kubernetes/namespace.yaml
kubectl apply -f kubernetes/deployment.yaml
kubectl apply -f kubernetes/service.yaml
kubectl apply -f kubernetes/ingress.yaml

# Wait for rollout
kubectl rollout status deployment/bookingcomp -n bookingcomp

# Verify
kubectl get pods,svc,ingress -n bookingcomp
```

### Verify Deployment

```bash
# Check pod status
kubectl get pods -n bookingcomp

# View pod logs
kubectl logs -l app=bookingcomp -n bookingcomp --tail=100

# Describe deployment
kubectl describe deployment bookingcomp -n bookingcomp

# Get ingress URL
kubectl get ingress bookingcomp-ingress -n bookingcomp
```

---

## Environment Variables Reference

| Variable | Default | Description |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `docker` | Active Spring profile |
| `SERVER_PORT` | `8080` | Application HTTP port |
| `SPRING_REDIS_HOST` | `localhost` | Redis / ElastiCache hostname |
| `SPRING_REDIS_PORT` | `6379` | Redis / ElastiCache port |
| `PAYMENT_API_URL` | `http://payment-service/payments/charge` | Payment microservice URL |
| `REPORT_SERVICE_URL` | `http://report-service/api/reports` | Report microservice URL |
| `S3_REPORTS_BUCKET` | `resorts-reports-bucket` | S3 bucket for reports |
| `S3_BACKUP_BUCKET` | `resorts-backup-bucket` | S3 bucket for backups |
| `JAVA_OPTS` | `-Xms256m -Xmx512m ...` | JVM startup options |
| `TZ` | `UTC` | Container timezone |

---

## Scaling and Management

### Horizontal Pod Autoscaler (HPA)

```bash
kubectl autoscale deployment bookingcomp \
  --cpu-percent=70 \
  --min=2 \
  --max=10 \
  -n bookingcomp

kubectl get hpa -n bookingcomp
```

### Manual Scaling

```bash
kubectl scale deployment bookingcomp --replicas=4 -n bookingcomp
```

### Rolling Update

```bash
# Update image
kubectl set image deployment/bookingcomp \
  bookingcomp=123456789012.dkr.ecr.us-east-1.amazonaws.com/bookingcomp:2.0.0 \
  -n bookingcomp

# Monitor rollout
kubectl rollout status deployment/bookingcomp -n bookingcomp
```

### Rollback

```bash
# Rollback to previous version
kubectl rollout undo deployment/bookingcomp -n bookingcomp

# Rollback to specific revision
kubectl rollout history deployment/bookingcomp -n bookingcomp
kubectl rollout undo deployment/bookingcomp --to-revision=2 -n bookingcomp
```

---

## Troubleshooting

### Pod Not Starting

```bash
# Check pod events
kubectl describe pod -l app=bookingcomp -n bookingcomp

# Check pod logs
kubectl logs -l app=bookingcomp -n bookingcomp --previous

# Check resource constraints
kubectl top pods -n bookingcomp
```

### Application Health Check Failing

```bash
# Verify health endpoint is accessible
kubectl exec -it $(kubectl get pod -l app=bookingcomp -n bookingcomp -o jsonpath='{.items[0].metadata.name}') \
  -n bookingcomp -- wget -qO- http://localhost:8080/actuator/health

# Check Spring Boot Actuator configuration
kubectl exec -it <pod-name> -n bookingcomp -- env | grep SPRING
```

### Redis Connection Issues

```bash
# Verify Redis environment variables
kubectl exec -it <pod-name> -n bookingcomp -- env | grep REDIS

# Test Redis connectivity from pod
kubectl exec -it <pod-name> -n bookingcomp -- \
  sh -c 'echo "PING" | nc -w 2 $SPRING_REDIS_HOST $SPRING_REDIS_PORT'
```

### Ingress / ALB Not Provisioning

```bash
# Check ALB controller logs
kubectl logs -n kube-system -l app.kubernetes.io/name=aws-load-balancer-controller

# Verify ingress annotations
kubectl describe ingress bookingcomp-ingress -n bookingcomp

# Check ingress events
kubectl get events -n bookingcomp --sort-by='.lastTimestamp'
```

### OOMKilled Pods

If pods are being killed due to memory pressure, increase the memory limits in `kubernetes/deployment.yaml`:

```yaml
resources:
  requests:
    memory: "768Mi"
  limits:
    memory: "1536Mi"
```

Also adjust JVM heap in the `JAVA_OPTS` environment variable:

```yaml
- name: JAVA_OPTS
  value: "-Xms512m -Xmx1024m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
```

---

## Security Considerations

1. **Secrets Management**: Store sensitive values (Redis passwords, API keys) in AWS Secrets Manager or Kubernetes Secrets — never in plain environment variables or ConfigMaps.

   ```bash
   kubectl create secret generic bookingcomp-secrets \
     --from-literal=redis-password=<password> \
     -n bookingcomp
   ```

2. **Non-Root Container**: The Dockerfile runs the application as a non-root user (`appuser`) for security.

3. **Network Policies**: Consider adding Kubernetes NetworkPolicies to restrict pod-to-pod communication.

4. **Image Scanning**: Enable ECR image scanning to detect vulnerabilities:
   ```bash
   aws ecr put-image-scanning-configuration \
     --repository-name bookingcomp \
     --image-scanning-configuration scanOnPush=true \
     --region us-east-1
   ```

5. **IRSA (IAM Roles for Service Accounts)**: Use IRSA to grant the application pod access to S3 and other AWS services without embedding credentials.

6. **TLS/HTTPS**: Update the ingress to use HTTPS with ACM certificates:
   ```yaml
   annotations:
     alb.ingress.kubernetes.io/listen-ports: '[{"HTTPS": 443}]'
     alb.ingress.kubernetes.io/certificate-arn: arn:aws:acm:us-east-1:123456789012:certificate/xxx
   ```

---

## Java-Specific Notes

- **JVM Container Support**: The `JAVA_OPTS` includes `-XX:+UseContainerSupport` and `-XX:MaxRAMPercentage=75.0` to ensure the JVM respects container memory limits (Java 8u191+ and Java 11+).
- **Startup Time**: Spring Boot applications on Java 8 may take 30–60 seconds to start. The liveness probe has a 60-second `initialDelaySeconds` to accommodate this.
- **Graceful Shutdown**: The deployment includes `terminationGracePeriodSeconds: 30` to allow in-flight requests to complete before pod termination.
- **H2 Database**: The application uses an in-memory H2 database. For production, replace with an Amazon RDS instance and update `spring.datasource.*` environment variables accordingly.
- **Spring Session Redis**: The application uses Spring Session backed by Redis (Amazon ElastiCache). Ensure `SPRING_REDIS_HOST` and `SPRING_REDIS_PORT` point to a valid ElastiCache endpoint in production.

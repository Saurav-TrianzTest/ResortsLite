# ResortsLite - Deployment Guide

## Table of Contents
1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Local Development with Docker](#local-development-with-docker)
4. [Building and Pushing Docker Images](#building-and-pushing-docker-images)
5. [AWS EKS Deployment](#aws-eks-deployment)
6. [Configuration Management](#configuration-management)
7. [Monitoring and Health Checks](#monitoring-and-health-checks)
8. [Troubleshooting](#troubleshooting)
9. [Security Considerations](#security-considerations)
10. [Scaling and Performance](#scaling-and-performance)

---

## Overview

ResortsLite is a Spring Boot 2.7.x application built with Java 8, designed for containerized deployment on AWS EKS (Elastic Kubernetes Service). This guide provides comprehensive instructions for building, deploying, and managing the application in both local and production environments.

### Technology Stack
- **Framework**: Spring Boot 2.7.18
- **Java Version**: Java 8 (1.8)
- **Build Tool**: Maven 3.x
- **Container Runtime**: Docker
- **Orchestration**: Kubernetes (AWS EKS)
- **Dependencies**: Redis (sessions/caching), AWS S3 (file storage), H2/External Database

---

## Prerequisites

### Required Software

#### For Local Development
- **Docker**: Version 20.10 or higher
  - Installation: https://docs.docker.com/get-docker/
- **Docker Compose**: Version 1.29 or higher
  - Installation: https://docs.docker.com/compose/install/

#### For AWS EKS Deployment
- **AWS CLI**: Version 2.x
  ```bash
  # Install AWS CLI
  curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
  unzip awscliv2.zip
  sudo ./aws/install
  
  # Verify installation
  aws --version
  ```

- **kubectl**: Kubernetes command-line tool
  ```bash
  # Install kubectl
  curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
  chmod +x kubectl
  sudo mv kubectl /usr/local/bin/
  
  # Verify installation
  kubectl version --client
  ```

- **eksctl** (Optional, for cluster creation)
  ```bash
  # Install eksctl
  curl --silent --location "https://github.com/weaveworks/eksctl/releases/latest/download/eksctl_$(uname -s)_amd64.tar.gz" | tar xz -C /tmp
  sudo mv /tmp/eksctl /usr/local/bin
  
  # Verify installation
  eksctl version
  ```

### AWS IAM Permissions

Ensure your AWS IAM user/role has the following permissions:
- **ECR**: `ecr:GetAuthorizationToken`, `ecr:BatchCheckLayerAvailability`, `ecr:GetDownloadUrlForLayer`, `ecr:PutImage`, `ecr:InitiateLayerUpload`, `ecr:UploadLayerPart`, `ecr:CompleteLayerUpload`, `ecr:CreateRepository`
- **EKS**: `eks:DescribeCluster`, `eks:ListClusters`, `eks:UpdateKubeconfig`
- **EC2**: `ec2:DescribeSecurityGroups`, `ec2:DescribeSubnets`, `ec2:DescribeVpcs`
- **IAM**: `iam:GetRole`, `iam:ListAttachedRolePolicies`

### External Services

The application requires the following external services:
- **Redis**: For distributed session management and caching
- **AWS S3**: For file storage (reports)
- **Database**: H2 (in-memory for dev) or external database (production)
- **External APIs** (optional): Payment, Inventory, Notification services

---

## Local Development with Docker

### Step 1: Clone the Repository
```bash
cd /path/to/fullcomp
```

### Step 2: Review Configuration
Edit `src/main/resources/application.properties` to configure local settings:
```properties
server.port=8080
spring.redis.host=localhost
spring.redis.port=6379
```

### Step 3: Build and Run with Docker Compose
```bash
# Build and start the application
docker-compose up --build

# Run in detached mode
docker-compose up -d

# View logs
docker-compose logs -f

# Stop the application
docker-compose down
```

### Step 4: Access the Application
- **Application**: http://localhost:8080
- **Health Check**: http://localhost:8080/actuator/health
- **H2 Console**: http://localhost:8080/h2-console

### Step 5: Local Testing
```bash
# Test health endpoint
curl http://localhost:8080/actuator/health

# Test application endpoints
curl http://localhost:8080/api/bookings
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

**Script Workflow:**
1. Prompts for image tag (default: `latest`)
2. Prompts for registry selection:
   - **AWS ECR**: Requires AWS region, account ID, repository name
   - **Docker Hub**: Requires username and password/token
3. Authenticates with selected registry
4. Builds Docker image using multi-stage Dockerfile
5. Pushes image to registry

**Example: AWS ECR**
```
Enter image tag (default: latest): v1.0.0
Select Docker Registry:
1. AWS ECR (Elastic Container Registry)
2. Docker Hub
Enter choice (1 or 2): 1

Enter AWS Region (e.g., us-east-1): us-east-1
Enter AWS Account ID: 123456789012
Enter ECR Repository Name (default: resortslite): resortslite

Authenticating with AWS ECR...
Building Docker image...
Pushing Docker image to registry...
```

### Option 2: Using build-push.bat (Windows)

```cmd
# Run the script
scripts\build-push.bat
```

Follow the same prompts as the Linux/macOS version.

### Manual Build and Push

#### AWS ECR
```bash
# Authenticate with ECR
aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin 123456789012.dkr.ecr.us-east-1.amazonaws.com

# Create ECR repository (if not exists)
aws ecr create-repository --repository-name resortslite --region us-east-1

# Build image
docker build -t resortslite:v1.0.0 .

# Tag image
docker tag resortslite:v1.0.0 123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:v1.0.0

# Push image
docker push 123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:v1.0.0
```

#### Docker Hub
```bash
# Login to Docker Hub
docker login -u your-username

# Build image
docker build -t your-username/resortslite:v1.0.0 .

# Push image
docker push your-username/resortslite:v1.0.0
```

---

## AWS EKS Deployment

### Prerequisites

#### 1. Create EKS Cluster (if not exists)
```bash
# Using eksctl
eksctl create cluster \
  --name resortslite-cluster \
  --region us-east-1 \
  --nodegroup-name standard-workers \
  --node-type t3.medium \
  --nodes 2 \
  --nodes-min 1 \
  --nodes-max 4 \
  --managed

# Verify cluster
aws eks describe-cluster --name resortslite-cluster --region us-east-1
```

#### 2. Install AWS Load Balancer Controller
```bash
# Create IAM policy
curl -o iam_policy.json https://raw.githubusercontent.com/kubernetes-sigs/aws-load-balancer-controller/v2.4.7/docs/install/iam_policy.json
aws iam create-policy \
  --policy-name AWSLoadBalancerControllerIAMPolicy \
  --policy-document file://iam_policy.json

# Create service account
eksctl create iamserviceaccount \
  --cluster=resortslite-cluster \
  --namespace=kube-system \
  --name=aws-load-balancer-controller \
  --attach-policy-arn=arn:aws:iam::123456789012:policy/AWSLoadBalancerControllerIAMPolicy \
  --override-existing-serviceaccounts \
  --approve

# Install controller using Helm
helm repo add eks https://aws.github.io/eks-charts
helm repo update
helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
  -n kube-system \
  --set clusterName=resortslite-cluster \
  --set serviceAccount.create=false \
  --set serviceAccount.name=aws-load-balancer-controller
```

### Deployment Steps

#### Option 1: Using deploy-image.sh (Linux/macOS)

```bash
# Make script executable
chmod +x scripts/deploy-image.sh

# Run the script
./scripts/deploy-image.sh
```

**Script Workflow:**
1. Prompts for AWS region and EKS cluster name
2. Prompts for Docker image URI (from build-push step)
3. Prompts for environment-specific configuration:
   - Database URL, username, password
   - Redis host, port, password
   - S3 bucket name and region
   - External service endpoints
4. Configures kubectl for EKS cluster
5. Updates Kubernetes manifests with provided values
6. Applies manifests in order: namespace → deployment → service → ingress
7. Waits for deployment rollout
8. Displays application access information

**Example:**
```
Enter AWS Region (e.g., us-east-1): us-east-1
Enter EKS Cluster Name: resortslite-cluster
Enter Docker Image URI (with tag): 123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:v1.0.0

Database URL (default: jdbc:h2:mem:resortdb): jdbc:postgresql://db.internal:5432/resorts
Database Username (default: sa): dbuser
Database Password (default: empty): ********
Redis Host (default: redis.internal): redis.resortslite.internal
Redis Port (default: 6379): 6379
...
```

#### Option 2: Using deploy-image.bat (Windows)

```cmd
# Run the script
scripts\deploy-image.bat
```

Follow the same prompts as the Linux/macOS version.

#### Option 3: Manual Deployment

```bash
# Configure kubectl
aws eks update-kubeconfig --region us-east-1 --name resortslite-cluster

# Verify connectivity
kubectl cluster-info

# Update deployment.yaml with image URI
sed -i 's|{{IMAGE_URI}}|123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:v1.0.0|g' kubernetes/deployment.yaml

# Update environment variables in deployment.yaml
# Edit kubernetes/deployment.yaml and replace {{PLACEHOLDER}} values

# Apply manifests
kubectl apply -f kubernetes/namespace.yaml
kubectl apply -f kubernetes/deployment.yaml
kubectl apply -f kubernetes/service.yaml
kubectl apply -f kubernetes/ingress.yaml

# Wait for rollout
kubectl rollout status deployment/resortslite -n resortslite

# Verify deployment
kubectl get pods,svc,ingress -n resortslite
```

### Verify Deployment

```bash
# Check pod status
kubectl get pods -n resortslite

# View pod logs
kubectl logs -f deployment/resortslite -n resortslite

# Check service
kubectl get svc -n resortslite

# Check ingress
kubectl get ingress -n resortslite

# Get ingress URL
kubectl get ingress resortslite-ingress -n resortslite -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'
```

### Access the Application

```bash
# Get ingress hostname
INGRESS_URL=$(kubectl get ingress resortslite-ingress -n resortslite -o jsonpath='{.status.loadBalancer.ingress[0].hostname}')

# Test health endpoint
curl http://$INGRESS_URL/actuator/health

# Access application
echo "Application URL: http://$INGRESS_URL"
```

---

## Configuration Management

### Environment Variables

The application uses environment variables for configuration. Key variables include:

#### Server Configuration
- `SERVER_PORT`: Application port (default: 8080)
- `SPRING_PROFILES_ACTIVE`: Active Spring profile (e.g., production)

#### Database Configuration
- `DB_URL`: JDBC connection URL
- `DB_USERNAME`: Database username
- `DB_PASSWORD`: Database password

#### Redis Configuration
- `REDIS_HOST`: Redis server hostname
- `REDIS_PORT`: Redis server port (default: 6379)
- `REDIS_PASSWORD`: Redis password (if required)

#### AWS S3 Configuration
- `S3_BUCKET_NAME`: S3 bucket for file storage
- `AWS_REGION`: AWS region for S3
- `AWS_ACCESS_KEY_ID`: AWS access key (if not using IAM roles)
- `AWS_SECRET_ACCESS_KEY`: AWS secret key (if not using IAM roles)

#### External Services
- `PAYMENT_ENDPOINT`: Payment service URL
- `INVENTORY_ENDPOINT`: Inventory service URL
- `NOTIFICATION_ENDPOINT`: Notification service URL

### Kubernetes ConfigMaps and Secrets

For production deployments, use ConfigMaps and Secrets instead of hardcoding values:

#### Create ConfigMap
```bash
kubectl create configmap resortslite-config \
  --from-literal=SERVER_PORT=8080 \
  --from-literal=REDIS_HOST=redis.internal \
  --from-literal=REDIS_PORT=6379 \
  --from-literal=S3_BUCKET_NAME=resortslite-reports \
  -n resortslite
```

#### Create Secret
```bash
kubectl create secret generic resortslite-secrets \
  --from-literal=DB_PASSWORD=your-db-password \
  --from-literal=REDIS_PASSWORD=your-redis-password \
  --from-literal=AWS_ACCESS_KEY_ID=your-access-key \
  --from-literal=AWS_SECRET_ACCESS_KEY=your-secret-key \
  -n resortslite
```

#### Update Deployment to Use ConfigMap/Secret
```yaml
env:
- name: SERVER_PORT
  valueFrom:
    configMapKeyRef:
      name: resortslite-config
      key: SERVER_PORT
- name: DB_PASSWORD
  valueFrom:
    secretKeyRef:
      name: resortslite-secrets
      key: DB_PASSWORD
```

---

## Monitoring and Health Checks

### Spring Boot Actuator Endpoints

The application exposes the following actuator endpoints:

- **Health**: `/actuator/health` - Application health status
- **Info**: `/actuator/info` - Application information

### Kubernetes Health Probes

#### Liveness Probe
Checks if the application is running. If it fails, Kubernetes restarts the pod.
```yaml
livenessProbe:
  httpGet:
    path: /actuator/health
    port: 8080
  initialDelaySeconds: 90
  periodSeconds: 30
  timeoutSeconds: 10
  failureThreshold: 3
```

#### Readiness Probe
Checks if the application is ready to receive traffic. If it fails, Kubernetes removes the pod from service endpoints.
```yaml
readinessProbe:
  httpGet:
    path: /actuator/health
    port: 8080
  initialDelaySeconds: 60
  periodSeconds: 10
  timeoutSeconds: 5
  failureThreshold: 3
```

### Monitoring Commands

```bash
# View pod status
kubectl get pods -n resortslite

# View pod logs
kubectl logs -f deployment/resortslite -n resortslite

# View pod events
kubectl describe pod <pod-name> -n resortslite

# View resource usage
kubectl top pods -n resortslite

# View deployment status
kubectl rollout status deployment/resortslite -n resortslite
```

---

## Troubleshooting

### Common Issues

#### 1. Pod Not Starting

**Symptoms**: Pod stuck in `Pending`, `CrashLoopBackOff`, or `ImagePullBackOff` state

**Diagnosis**:
```bash
# Check pod status
kubectl get pods -n resortslite

# Describe pod for events
kubectl describe pod <pod-name> -n resortslite

# View pod logs
kubectl logs <pod-name> -n resortslite
```

**Solutions**:
- **ImagePullBackOff**: Verify image URI is correct and ECR authentication is configured
- **CrashLoopBackOff**: Check application logs for startup errors
- **Pending**: Check node resources and pod resource requests

#### 2. Application Not Accessible

**Symptoms**: Cannot access application via ingress URL

**Diagnosis**:
```bash
# Check ingress status
kubectl get ingress -n resortslite

# Describe ingress
kubectl describe ingress resortslite-ingress -n resortslite

# Check service
kubectl get svc -n resortslite

# Check ALB controller logs
kubectl logs -n kube-system deployment/aws-load-balancer-controller
```

**Solutions**:
- Verify AWS Load Balancer Controller is installed and running
- Check security groups allow traffic on port 80/443
- Verify ingress annotations are correct
- Check DNS resolution for ingress hostname

#### 3. Database Connection Issues

**Symptoms**: Application logs show database connection errors

**Diagnosis**:
```bash
# Check application logs
kubectl logs -f deployment/resortslite -n resortslite | grep -i database

# Check environment variables
kubectl exec -it <pod-name> -n resortslite -- env | grep DB_
```

**Solutions**:
- Verify database URL, username, and password are correct
- Check network connectivity from pod to database
- Verify database security groups allow traffic from EKS nodes

#### 4. Redis Connection Issues

**Symptoms**: Session management or caching not working

**Diagnosis**:
```bash
# Check application logs
kubectl logs -f deployment/resortslite -n resortslite | grep -i redis

# Test Redis connectivity from pod
kubectl exec -it <pod-name> -n resortslite -- sh
# Inside pod:
# telnet redis.internal 6379
```

**Solutions**:
- Verify Redis host and port are correct
- Check Redis password if authentication is enabled
- Verify network connectivity from pod to Redis

#### 5. High Memory Usage

**Symptoms**: Pod OOMKilled or high memory consumption

**Diagnosis**:
```bash
# Check resource usage
kubectl top pods -n resortslite

# Check pod events
kubectl describe pod <pod-name> -n resortslite
```

**Solutions**:
- Adjust JVM heap size: `-Xmx512m -Xms256m`
- Increase pod memory limits in deployment.yaml
- Review application for memory leaks

### Debugging Commands

```bash
# Execute shell in pod
kubectl exec -it <pod-name> -n resortslite -- sh

# Port forward to local machine
kubectl port-forward deployment/resortslite 8080:8080 -n resortslite

# View deployment history
kubectl rollout history deployment/resortslite -n resortslite

# Rollback deployment
kubectl rollout undo deployment/resortslite -n resortslite

# Scale deployment
kubectl scale deployment/resortslite --replicas=3 -n resortslite

# Delete and recreate pod
kubectl delete pod <pod-name> -n resortslite
```

---

## Security Considerations

### 1. Container Security

- **Non-root User**: Application runs as non-root user `appuser`
- **Read-only Filesystem**: Consider mounting volumes as read-only
- **Security Context**: Add security context to pod spec:
  ```yaml
  securityContext:
    runAsNonRoot: true
    runAsUser: 1000
    fsGroup: 1000
    capabilities:
      drop:
        - ALL
  ```

### 2. Network Security

- **Network Policies**: Implement Kubernetes network policies to restrict pod-to-pod communication
- **Security Groups**: Configure AWS security groups to allow only necessary traffic
- **TLS/SSL**: Enable HTTPS on ingress with ACM certificates:
  ```yaml
  annotations:
    alb.ingress.kubernetes.io/certificate-arn: arn:aws:acm:region:account:certificate/id
    alb.ingress.kubernetes.io/listen-ports: '[{"HTTP": 80}, {"HTTPS": 443}]'
    alb.ingress.kubernetes.io/ssl-redirect: '443'
  ```

### 3. Secrets Management

- **AWS Secrets Manager**: Use AWS Secrets Manager for sensitive data
- **Kubernetes Secrets**: Store credentials in Kubernetes secrets (encrypted at rest)
- **IAM Roles**: Use IAM roles for service accounts (IRSA) instead of access keys
- **Rotate Credentials**: Regularly rotate database passwords and API keys

### 4. Image Security

- **Vulnerability Scanning**: Scan images with AWS ECR image scanning or Trivy
- **Minimal Base Images**: Use minimal base images (eclipse-temurin:8-jre-alpine)
- **Image Signing**: Sign images with Docker Content Trust
- **Private Registry**: Store images in private ECR repositories

### 5. Application Security

- **Dependency Updates**: Regularly update dependencies to patch vulnerabilities
- **Security Headers**: Configure security headers in Spring Boot
- **Input Validation**: Validate all user inputs
- **Authentication/Authorization**: Implement proper authentication and authorization

---

## Scaling and Performance

### Horizontal Pod Autoscaling (HPA)

Create HPA to automatically scale based on CPU/memory usage:

```bash
# Create HPA
kubectl autoscale deployment resortslite \
  --cpu-percent=70 \
  --min=2 \
  --max=10 \
  -n resortslite

# View HPA status
kubectl get hpa -n resortslite

# Describe HPA
kubectl describe hpa resortslite -n resortslite
```

**HPA YAML**:
```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: resortslite-hpa
  namespace: resortslite
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: resortslite
  minReplicas: 2
  maxReplicas: 10
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70
  - type: Resource
    resource:
      name: memory
      target:
        type: Utilization
        averageUtilization: 80
```

### Cluster Autoscaling

Enable cluster autoscaler to add/remove nodes based on demand:

```bash
# Using eksctl
eksctl create nodegroup \
  --cluster=resortslite-cluster \
  --name=autoscaling-workers \
  --node-type=t3.medium \
  --nodes=2 \
  --nodes-min=1 \
  --nodes-max=10 \
  --asg-access
```

### Performance Tuning

#### JVM Tuning
```yaml
env:
- name: JAVA_OPTS
  value: "-Xmx512m -Xms256m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
```

#### Resource Limits
```yaml
resources:
  requests:
    cpu: "250m"
    memory: "512Mi"
  limits:
    cpu: "1000m"
    memory: "1Gi"
```

#### Connection Pooling
Configure database connection pooling in `application.properties`:
```properties
spring.datasource.hikari.maximum-pool-size=20
spring.datasource.hikari.minimum-idle=5
spring.datasource.hikari.connection-timeout=30000
```

### Rolling Updates

Update deployment with zero downtime:

```bash
# Update image
kubectl set image deployment/resortslite \
  resortslite=123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:v1.1.0 \
  -n resortslite

# Monitor rollout
kubectl rollout status deployment/resortslite -n resortslite

# Pause rollout
kubectl rollout pause deployment/resortslite -n resortslite

# Resume rollout
kubectl rollout resume deployment/resortslite -n resortslite

# Rollback
kubectl rollout undo deployment/resortslite -n resortslite
```

---

## Additional Resources

### Documentation
- [Spring Boot Documentation](https://docs.spring.io/spring-boot/docs/2.7.x/reference/html/)
- [AWS EKS Documentation](https://docs.aws.amazon.com/eks/)
- [Kubernetes Documentation](https://kubernetes.io/docs/)
- [Docker Documentation](https://docs.docker.com/)

### Tools
- [kubectl Cheat Sheet](https://kubernetes.io/docs/reference/kubectl/cheatsheet/)
- [AWS CLI Reference](https://docs.aws.amazon.com/cli/latest/reference/)
- [eksctl Documentation](https://eksctl.io/)

### Support
For issues or questions, contact the DevOps team or refer to the project repository.

---

## Appendix

### A. Complete Deployment Checklist

- [ ] Prerequisites installed (Docker, AWS CLI, kubectl)
- [ ] AWS credentials configured
- [ ] EKS cluster created and accessible
- [ ] AWS Load Balancer Controller installed
- [ ] External services (Redis, Database, S3) provisioned
- [ ] Docker image built and pushed to registry
- [ ] Kubernetes manifests updated with correct values
- [ ] Namespace created
- [ ] Deployment applied
- [ ] Service created
- [ ] Ingress created
- [ ] Health checks passing
- [ ] Application accessible via ingress URL
- [ ] Monitoring configured
- [ ] Backup and disaster recovery plan in place

### B. Environment-Specific Configurations

#### Development
- Use H2 in-memory database
- Single replica
- Minimal resource limits
- Debug logging enabled

#### Staging
- Use external database
- 2 replicas
- Moderate resource limits
- Info logging level

#### Production
- Use external database with replication
- 3+ replicas with HPA
- Production resource limits
- Warn/Error logging level
- TLS/SSL enabled
- Monitoring and alerting configured

---

**Document Version**: 1.0  
**Last Updated**: 2024  
**Maintained By**: DevOps Team

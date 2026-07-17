# ResortsLite — Deployment Guide

## Table of Contents
1. [Prerequisites](#prerequisites)
2. [Project Overview](#project-overview)
3. [Local Development with Docker Compose](#local-development-with-docker-compose)
4. [Building and Pushing the Docker Image](#building-and-pushing-the-docker-image)
5. [Azure AKS Deployment](#azure-aks-deployment)
6. [Configuration Management](#configuration-management)
7. [Scaling and Management](#scaling-and-management)
8. [Troubleshooting](#troubleshooting)
9. [Security Considerations](#security-considerations)

---

## Prerequisites

### Local Development
| Tool | Version | Purpose |
|------|---------|---------|
| Docker | 20.10+ | Container build and run |
| Docker Compose | 2.x | Local multi-container orchestration |
| Java JDK | 8 | Local development (optional) |
| Maven | 3.8+ | Local build (optional) |

### Azure AKS Deployment
| Tool | Version | Purpose |
|------|---------|---------|
| Azure CLI | 2.50+ | Azure resource management |
| kubectl | 1.27+ | Kubernetes cluster management |
| Docker | 20.10+ | Image build and push |
| Azure Subscription | — | AKS cluster hosting |

Install Azure CLI:
```bash
# macOS
brew install azure-cli

# Linux
curl -sL https://aka.ms/InstallAzureCLIDeb | sudo bash

# Windows
winget install Microsoft.AzureCLI
```

Install kubectl:
```bash
# macOS
brew install kubectl

# Linux
az aks install-cli

# Windows
az aks install-cli
```

---

## Project Overview

**Application**: ResortsLite  
**Framework**: Spring Boot 2.7.18  
**Java Version**: 8  
**Build Tool**: Maven  
**Application Port**: 8080  
**Health Endpoint**: `/actuator/health`  
**Management Endpoints**: `/actuator/health`, `/actuator/info`

### Key Dependencies
- Spring Boot Web (REST API)
- Spring Boot JDBC (database access)
- Spring Boot Actuator (health checks)
- Spring Session with Redis (distributed session storage)
- Spring Data Redis (Redis integration)
- H2 Database (in-memory, runtime)

### Environment Variables
| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8080` | Application HTTP port |
| `SPRING_PROFILES_ACTIVE` | `docker` | Active Spring profile |
| `REDIS_HOST` | `localhost` | Redis server hostname |
| `REDIS_PORT` | `6379` | Redis server port |
| `REDIS_PASSWORD` | _(empty)_ | Redis authentication password |
| `PAYMENT_API_URL` | `http://payment-service/payments/charge` | Payment service endpoint |
| `REPORT_BASE_PATH` | `/reports` | Directory for generated reports |
| `BACKUP_PATH` | `/backups/nightly` | Directory for nightly backups |
| `JAVA_OPTS` | `-Xms256m -Xmx512m ...` | JVM startup options |

---

## Local Development with Docker Compose

### 1. Clone the Repository
```bash
git clone <repository-url>
cd fullcomp
```

### 2. Configure Environment Variables
Create a `.env` file in the project root:
```env
REDIS_HOST=your-redis-host
REDIS_PORT=6379
REDIS_PASSWORD=your-redis-password
PAYMENT_API_URL=http://payment-service/payments/charge
```

### 3. Start the Application
```bash
docker compose up --build
```

### 4. Verify the Application
```bash
# Health check
curl http://localhost:8080/actuator/health

# Test booking endpoint
curl -X POST "http://localhost:8080/api/bookings/create?guestName=John&roomType=SUITE&checkIn=2024-06-01&checkOut=2024-06-05"
```

### 5. Stop the Application
```bash
docker compose down
```

---

## Building and Pushing the Docker Image

### Linux / macOS
```bash
chmod +x scripts/build-push.sh
./scripts/build-push.sh
```

The script will prompt you to:
1. Select registry type (Azure ACR or Docker Hub)
2. Enter registry credentials
3. Enter an image tag (defaults to `latest`)

### Windows
```cmd
scripts\build-push.bat
```

### Manual Build (Advanced)
```bash
# Build image
docker build -t resortslite:latest .

# Tag for ACR
docker tag resortslite:latest <acr-name>.azurecr.io/resortslite:latest

# Login to ACR
az acr login --name <acr-name>

# Push
docker push <acr-name>.azurecr.io/resortslite:latest
```

---

## Azure AKS Deployment

### Step 1: Create Azure Resources

```bash
# Login to Azure
az login

# Create resource group (if not existing)
az group create --name resortslite-rg --location eastus

# Create AKS cluster (if not existing)
az aks create \
  --resource-group resortslite-rg \
  --name resortslite-aks \
  --node-count 2 \
  --node-vm-size Standard_DS2_v2 \
  --enable-addons monitoring \
  --generate-ssh-keys

# Create Azure Container Registry (if not existing)
az acr create \
  --resource-group resortslite-rg \
  --name resortsliteacr \
  --sku Basic

# Attach ACR to AKS
az aks update \
  --resource-group resortslite-rg \
  --name resortslite-aks \
  --attach-acr resortsliteacr
```

### Step 2: Build and Push the Image
```bash
./scripts/build-push.sh
# Select ACR, enter: resortsliteacr
# Enter tag: v1.0.0
```

### Step 3: Deploy to AKS

#### Linux / macOS
```bash
chmod +x scripts/deploy-image.sh
./scripts/deploy-image.sh
```

#### Windows
```cmd
scripts\deploy-image.bat
```

The deploy script will prompt for:
- Azure Resource Group name
- AKS Cluster name
- Full Docker image URI (e.g., `resortsliteacr.azurecr.io/resortslite:v1.0.0`)
- Optional environment variable values (Redis, Payment API, etc.)

### Step 4: Verify Deployment
```bash
# Get kubectl credentials
az aks get-credentials --resource-group resortslite-rg --name resortslite-aks

# Check pods
kubectl get pods -n resortslite

# Check services
kubectl get svc -n resortslite

# Check ingress
kubectl get ingress -n resortslite

# View pod logs
kubectl logs -l app=resortslite -n resortslite --tail=100
```

### Step 5: Access the Application
```bash
# Get ingress IP
kubectl get ingress resortslite-ingress -n resortslite

# Test health endpoint
curl http://<INGRESS_IP>/actuator/health
```

### Manual Kubernetes Deployment
```bash
# Apply manifests in order
kubectl apply -f kubernetes/namespace.yaml
kubectl apply -f kubernetes/deployment.yaml
kubectl apply -f kubernetes/service.yaml
kubectl apply -f kubernetes/ingress.yaml

# Wait for rollout
kubectl rollout status deployment/resortslite -n resortslite
```

---

## Configuration Management

### Using Kubernetes ConfigMap
```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: resortslite-config
  namespace: resortslite
data:
  REDIS_HOST: "my-redis.redis.cache.windows.net"
  REDIS_PORT: "6380"
  PAYMENT_API_URL: "http://payment-service.resortslite.svc.cluster.local/payments/charge"
  REPORT_BASE_PATH: "/reports"
  BACKUP_PATH: "/backups/nightly"
```

### Using Kubernetes Secrets (for sensitive values)
```yaml
apiVersion: v1
kind: Secret
metadata:
  name: resortslite-secrets
  namespace: resortslite
type: Opaque
stringData:
  REDIS_PASSWORD: "your-redis-password"
```

Reference in deployment.yaml:
```yaml
env:
  - name: REDIS_PASSWORD
    valueFrom:
      secretKeyRef:
        name: resortslite-secrets
        key: REDIS_PASSWORD
```

### Azure Key Vault Integration (Recommended for Production)
```bash
# Enable Key Vault CSI driver
az aks enable-addons \
  --addons azure-keyvault-secrets-provider \
  --name resortslite-aks \
  --resource-group resortslite-rg
```

---

## Scaling and Management

### Manual Scaling
```bash
# Scale to 3 replicas
kubectl scale deployment resortslite --replicas=3 -n resortslite
```

### Horizontal Pod Autoscaler (HPA)
```bash
kubectl autoscale deployment resortslite \
  --cpu-percent=70 \
  --min=2 \
  --max=10 \
  -n resortslite

# Check HPA status
kubectl get hpa -n resortslite
```

### Rolling Update
```bash
# Update image
kubectl set image deployment/resortslite \
  resortslite=resortsliteacr.azurecr.io/resortslite:v1.1.0 \
  -n resortslite

# Monitor rollout
kubectl rollout status deployment/resortslite -n resortslite
```

### Rollback
```bash
# Rollback to previous version
kubectl rollout undo deployment/resortslite -n resortslite

# Rollback to specific revision
kubectl rollout history deployment/resortslite -n resortslite
kubectl rollout undo deployment/resortslite --to-revision=2 -n resortslite
```

---

## Troubleshooting

### Pod Not Starting
```bash
# Describe pod for events
kubectl describe pod -l app=resortslite -n resortslite

# Check pod logs
kubectl logs -l app=resortslite -n resortslite --previous

# Check resource constraints
kubectl top pods -n resortslite
```

### Application Health Check Failing
```bash
# Check actuator health directly
kubectl exec -it <pod-name> -n resortslite -- wget -qO- http://localhost:8080/actuator/health

# Check if Redis is reachable
kubectl exec -it <pod-name> -n resortslite -- env | grep REDIS
```

### Redis Connection Issues
```bash
# Verify Redis environment variables
kubectl exec -it <pod-name> -n resortslite -- env | grep REDIS

# Test Redis connectivity from pod
kubectl exec -it <pod-name> -n resortslite -- sh -c "nc -zv $REDIS_HOST $REDIS_PORT"
```

### Ingress Not Accessible
```bash
# Check ingress status
kubectl describe ingress resortslite-ingress -n resortslite

# Verify Application Gateway Ingress Controller is running
kubectl get pods -n kube-system | grep ingress

# Check service endpoints
kubectl get endpoints resortslite-service -n resortslite
```

### OOMKilled (Out of Memory)
```bash
# Check memory usage
kubectl top pods -n resortslite

# Increase memory limits in deployment.yaml
# resources.limits.memory: "2Gi"
# Also increase JAVA_OPTS: -Xmx1g
```

### JVM Startup Slow
- Increase `initialDelaySeconds` in liveness/readiness probes (default: 60s)
- Consider using Spring Boot lazy initialization: `spring.main.lazy-initialization=true`
- Monitor startup with: `kubectl logs -f <pod-name> -n resortslite`

---

## Security Considerations

1. **Non-root Container**: The application runs as a non-root user (`appuser`) inside the container.
2. **Secrets Management**: Use Kubernetes Secrets or Azure Key Vault for sensitive values (Redis password, API keys). Never hardcode credentials.
3. **Network Policies**: Consider adding Kubernetes NetworkPolicy to restrict pod-to-pod communication.
4. **Image Scanning**: Scan Docker images with Azure Defender for Containers or Trivy before deployment.
5. **RBAC**: Apply least-privilege RBAC policies for the AKS service account.
6. **TLS/HTTPS**: Configure TLS termination at the Application Gateway Ingress Controller level.
7. **Dependency Updates**: The project currently includes vulnerable dependencies (log4j 2.14.1, commons-collections 3.2.1). **Update these immediately before production deployment**:
   - `log4j-core`: upgrade to 2.17.2+ (fixes CVE-2021-44228 Log4Shell)
   - `commons-collections`: upgrade to 3.2.2+ (fixes CVE-2015-6420)

---

## Java-Specific Notes

### JVM Memory Configuration
The default `JAVA_OPTS` are configured for container-aware memory management:
```
-Xms256m -Xmx512m
-XX:+UseContainerSupport
-XX:MaxRAMPercentage=75.0
-XX:+UnlockExperimentalVMOptions
```

Adjust these values based on your container memory limits. For a 1Gi memory limit, `-Xmx512m` is appropriate.

### Spring Boot Actuator Endpoints
- **Liveness**: `GET /actuator/health` — returns `{"status":"UP"}` when healthy
- **Readiness**: `GET /actuator/health` — same endpoint used for readiness
- **Info**: `GET /actuator/info` — application metadata

### Spring Profiles
Set `SPRING_PROFILES_ACTIVE=docker` for containerized deployments. Create `application-docker.properties` for Docker-specific overrides if needed.

### Graceful Shutdown
The deployment is configured with `terminationGracePeriodSeconds: 30`. Add the following to `application.properties` for graceful shutdown support:
```properties
server.shutdown=graceful
spring.lifecycle.timeout-per-shutdown-phase=20s
```

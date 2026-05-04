#!/bin/bash

# Deploy ResortsLite to AWS EKS
# This script configures kubectl and deploys the application to EKS

set -e
set -o pipefail

echo "=========================================="
echo "  ResortsLite - AWS EKS Deployment"
echo "=========================================="
echo ""

# Prompt for AWS EKS configuration
read -p "Enter AWS Region (e.g., us-east-1): " AWS_REGION
read -p "Enter EKS Cluster Name: " CLUSTER_NAME
read -p "Enter Docker Image URI (with tag): " IMAGE_URI

echo ""
echo "Configuration:"
echo "  AWS Region: $AWS_REGION"
echo "  EKS Cluster: $CLUSTER_NAME"
echo "  Image URI: $IMAGE_URI"
echo ""

# Prompt for environment-specific configuration
echo "=========================================="
echo "  Environment Configuration"
echo "=========================================="
echo "Enter values for environment variables (press Enter to use defaults):"
echo ""

read -p "Database URL (default: jdbc:h2:mem:resortdb): " DB_URL
DB_URL=${DB_URL:-jdbc:h2:mem:resortdb;DB_CLOSE_DELAY=-1}

read -p "Database Username (default: sa): " DB_USERNAME
DB_USERNAME=${DB_USERNAME:-sa}

read -sp "Database Password (default: empty): " DB_PASSWORD
echo ""
DB_PASSWORD=${DB_PASSWORD:-}

read -p "Redis Host (default: redis.internal): " REDIS_HOST
REDIS_HOST=${REDIS_HOST:-redis.internal}

read -p "Redis Port (default: 6379): " REDIS_PORT
REDIS_PORT=${REDIS_PORT:-6379}

read -sp "Redis Password (default: empty): " REDIS_PASSWORD
echo ""
REDIS_PASSWORD=${REDIS_PASSWORD:-}

read -p "S3 Bucket Name (default: resortslite-reports): " S3_BUCKET_NAME
S3_BUCKET_NAME=${S3_BUCKET_NAME:-resortslite-reports}

read -p "AWS Region for S3 (default: $AWS_REGION): " S3_AWS_REGION
S3_AWS_REGION=${S3_AWS_REGION:-$AWS_REGION}

read -p "Payment Service Endpoint (default: http://payment-svc.internal:9090/charge): " PAYMENT_ENDPOINT
PAYMENT_ENDPOINT=${PAYMENT_ENDPOINT:-http://payment-svc.internal:9090/charge}

read -p "Inventory Service Endpoint (default: http://inventory-svc.internal:8081/rooms): " INVENTORY_ENDPOINT
INVENTORY_ENDPOINT=${INVENTORY_ENDPOINT:-http://inventory-svc.internal:8081/rooms}

read -p "Notification Service Endpoint (default: http://notify.internal:7070/send): " NOTIFICATION_ENDPOINT
NOTIFICATION_ENDPOINT=${NOTIFICATION_ENDPOINT:-http://notify.internal:7070/send}

echo ""
echo "=========================================="
echo "  Configuring kubectl for EKS"
echo "=========================================="

# Configure kubectl to use EKS cluster
aws eks update-kubeconfig --region "$AWS_REGION" --name "$CLUSTER_NAME"

if [ $? -ne 0 ]; then
    echo "ERROR: Failed to configure kubectl for EKS cluster"
    exit 1
fi

echo "kubectl configured successfully"
echo ""

# Verify cluster connectivity
echo "Verifying cluster connectivity..."
kubectl cluster-info || {
    echo "ERROR: Cannot connect to EKS cluster"
    exit 1
}
echo ""

# Update Kubernetes manifests with actual values
echo "=========================================="
echo "  Updating Kubernetes Manifests"
echo "=========================================="

# Create temporary directory for processed manifests
TEMP_DIR=$(mktemp -d)
cp -r kubernetes/* "$TEMP_DIR/"

# Replace placeholders in deployment.yaml
sed -i "s|{{IMAGE_URI}}|$IMAGE_URI|g" "$TEMP_DIR/deployment.yaml"
sed -i "s|{{DB_URL}}|$DB_URL|g" "$TEMP_DIR/deployment.yaml"
sed -i "s|{{DB_USERNAME}}|$DB_USERNAME|g" "$TEMP_DIR/deployment.yaml"
sed -i "s|{{DB_PASSWORD}}|$DB_PASSWORD|g" "$TEMP_DIR/deployment.yaml"
sed -i "s|{{REDIS_HOST}}|$REDIS_HOST|g" "$TEMP_DIR/deployment.yaml"
sed -i "s|{{REDIS_PORT}}|$REDIS_PORT|g" "$TEMP_DIR/deployment.yaml"
sed -i "s|{{REDIS_PASSWORD}}|$REDIS_PASSWORD|g" "$TEMP_DIR/deployment.yaml"
sed -i "s|{{S3_BUCKET_NAME}}|$S3_BUCKET_NAME|g" "$TEMP_DIR/deployment.yaml"
sed -i "s|{{AWS_REGION}}|$S3_AWS_REGION|g" "$TEMP_DIR/deployment.yaml"
sed -i "s|{{PAYMENT_ENDPOINT}}|$PAYMENT_ENDPOINT|g" "$TEMP_DIR/deployment.yaml"
sed -i "s|{{INVENTORY_ENDPOINT}}|$INVENTORY_ENDPOINT|g" "$TEMP_DIR/deployment.yaml"
sed -i "s|{{NOTIFICATION_ENDPOINT}}|$NOTIFICATION_ENDPOINT|g" "$TEMP_DIR/deployment.yaml"

echo "Manifests updated successfully"
echo ""

# Apply Kubernetes manifests
echo "=========================================="
echo "  Deploying to EKS"
echo "=========================================="

echo "Creating namespace..."
kubectl apply -f "$TEMP_DIR/namespace.yaml"
echo ""

echo "Deploying application..."
kubectl apply -f "$TEMP_DIR/deployment.yaml"
echo ""

echo "Creating service..."
kubectl apply -f "$TEMP_DIR/service.yaml"
echo ""

echo "Creating ingress..."
kubectl apply -f "$TEMP_DIR/ingress.yaml"
echo ""

# Wait for deployment to complete
echo "=========================================="
echo "  Waiting for Deployment Rollout"
echo "=========================================="
kubectl rollout status deployment/resortslite -n resortslite --timeout=5m

if [ $? -ne 0 ]; then
    echo "WARNING: Deployment rollout did not complete within timeout"
    echo "Check deployment status with: kubectl get pods -n resortslite"
fi
echo ""

# Verify deployment
echo "=========================================="
echo "  Deployment Status"
echo "=========================================="
kubectl get pods,svc,ingress -n resortslite
echo ""

# Get ingress URL
echo "=========================================="
echo "  Application Access Information"
echo "=========================================="
INGRESS_ADDRESS=$(kubectl get ingress resortslite-ingress -n resortslite -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || echo "Pending...")

echo "Ingress Address: $INGRESS_ADDRESS"
echo ""
echo "Application URL: http://$INGRESS_ADDRESS"
echo "Health Check: http://$INGRESS_ADDRESS/actuator/health"
echo ""

if [ "$INGRESS_ADDRESS" == "Pending..." ]; then
    echo "NOTE: Ingress is still being provisioned. Run the following command to check status:"
    echo "  kubectl get ingress resortslite-ingress -n resortslite"
fi

echo ""
echo "=========================================="
echo "  Deployment Completed Successfully!"
echo "=========================================="
echo ""
echo "Useful commands:"
echo "  View pods:        kubectl get pods -n resortslite"
echo "  View logs:        kubectl logs -f deployment/resortslite -n resortslite"
echo "  Describe pod:     kubectl describe pod <pod-name> -n resortslite"
echo "  Scale replicas:   kubectl scale deployment/resortslite --replicas=3 -n resortslite"
echo "  Delete deployment: kubectl delete namespace resortslite"
echo ""

# Cleanup temporary directory
rm -rf "$TEMP_DIR"

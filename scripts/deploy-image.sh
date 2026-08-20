#!/bin/bash
set -e
set -o pipefail

# ============================================================
# deploy-image.sh — Deploy BookingComp to AWS EKS
# ============================================================

APP_NAME="bookingcomp"
NAMESPACE="bookingcomp"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

echo "============================================"
echo "  BookingComp — AWS EKS Deployment"
echo "============================================"

# ---- Collect deployment inputs ----
read -rp "Enter AWS Region (e.g. us-east-1): " AWS_REGION
if [ -z "$AWS_REGION" ]; then
  echo "ERROR: AWS Region is required."
  exit 1
fi

read -rp "Enter EKS Cluster Name: " CLUSTER_NAME
if [ -z "$CLUSTER_NAME" ]; then
  echo "ERROR: EKS Cluster Name is required."
  exit 1
fi

read -rp "Enter full Docker image URI (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com/bookingcomp:latest): " IMAGE_URI
if [ -z "$IMAGE_URI" ]; then
  echo "ERROR: Docker image URI is required."
  exit 1
fi

echo ""
echo "--- Optional: Environment Variable Configuration ---"
echo "Press Enter to skip any variable and use the default."

read -rp "Enter SPRING_REDIS_HOST [localhost]: " SPRING_REDIS_HOST
SPRING_REDIS_HOST="${SPRING_REDIS_HOST:-localhost}"

read -rp "Enter SPRING_REDIS_PORT [6379]: " SPRING_REDIS_PORT
SPRING_REDIS_PORT="${SPRING_REDIS_PORT:-6379}"

read -rp "Enter PAYMENT_API_URL [http://payment-service/payments/charge]: " PAYMENT_API_URL
PAYMENT_API_URL="${PAYMENT_API_URL:-http://payment-service/payments/charge}"

read -rp "Enter REPORT_SERVICE_URL [http://report-service/api/reports]: " REPORT_SERVICE_URL
REPORT_SERVICE_URL="${REPORT_SERVICE_URL:-http://report-service/api/reports}"

read -rp "Enter S3_REPORTS_BUCKET [resorts-reports-bucket]: " S3_REPORTS_BUCKET
S3_REPORTS_BUCKET="${S3_REPORTS_BUCKET:-resorts-reports-bucket}"

read -rp "Enter S3_BACKUP_BUCKET [resorts-backup-bucket]: " S3_BACKUP_BUCKET
S3_BACKUP_BUCKET="${S3_BACKUP_BUCKET:-resorts-backup-bucket}"

# ---- Configure kubectl for EKS ----
echo ""
echo "Configuring kubectl for EKS cluster: $CLUSTER_NAME in $AWS_REGION ..."
aws eks update-kubeconfig --region "$AWS_REGION" --name "$CLUSTER_NAME"

echo "Verifying cluster connectivity..."
kubectl cluster-info || { echo "ERROR: Cannot connect to EKS cluster."; exit 1; }

# ---- Substitute placeholders in manifests ----
echo ""
echo "Updating Kubernetes manifests with deployment values..."

DEPLOY_YAML="$PROJECT_ROOT/kubernetes/deployment.yaml"
DEPLOY_YAML_TMP="$PROJECT_ROOT/kubernetes/deployment.yaml.tmp"

cp "$DEPLOY_YAML" "$DEPLOY_YAML_TMP"

sed -i 's|{{IMAGE_URI}}|'"$IMAGE_URI"'|g'                     "$DEPLOY_YAML_TMP"
sed -i 's|{{SPRING_REDIS_HOST}}|'"$SPRING_REDIS_HOST"'|g'     "$DEPLOY_YAML_TMP"
sed -i 's|{{SPRING_REDIS_PORT}}|'"$SPRING_REDIS_PORT"'|g'     "$DEPLOY_YAML_TMP"
sed -i 's|{{PAYMENT_API_URL}}|'"$PAYMENT_API_URL"'|g'         "$DEPLOY_YAML_TMP"
sed -i 's|{{REPORT_SERVICE_URL}}|'"$REPORT_SERVICE_URL"'|g'   "$DEPLOY_YAML_TMP"
sed -i 's|{{S3_REPORTS_BUCKET}}|'"$S3_REPORTS_BUCKET"'|g'     "$DEPLOY_YAML_TMP"
sed -i 's|{{S3_BACKUP_BUCKET}}|'"$S3_BACKUP_BUCKET"'|g'       "$DEPLOY_YAML_TMP"

# ---- Apply manifests ----
echo ""
echo "Applying Kubernetes manifests..."

echo "  [1/4] Applying namespace..."
kubectl apply -f "$PROJECT_ROOT/kubernetes/namespace.yaml"

echo "  [2/4] Applying deployment..."
kubectl apply -f "$DEPLOY_YAML_TMP"

echo "  [3/4] Applying service..."
kubectl apply -f "$PROJECT_ROOT/kubernetes/service.yaml"

echo "  [4/4] Applying ingress..."
kubectl apply -f "$PROJECT_ROOT/kubernetes/ingress.yaml"

# Clean up temp file
rm -f "$DEPLOY_YAML_TMP"

# ---- Wait for rollout ----
echo ""
echo "Waiting for deployment rollout..."
kubectl rollout status deployment/"$APP_NAME" -n "$NAMESPACE" --timeout=300s \
  || { echo "ERROR: Deployment rollout failed. Run: kubectl rollout undo deployment/$APP_NAME -n $NAMESPACE"; exit 1; }

# ---- Verify resources ----
echo ""
echo "Verifying deployed resources..."
kubectl get pods,svc,ingress -n "$NAMESPACE"

# ---- Display access URL ----
echo ""
echo "Fetching application ingress URL..."
INGRESS_HOST=$(kubectl get ingress "${APP_NAME}-ingress" -n "$NAMESPACE" \
  -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || echo "pending")

echo ""
echo "============================================"
echo "  Deployment Complete!"
echo "  Namespace : $NAMESPACE"
echo "  Image     : $IMAGE_URI"
if [ "$INGRESS_HOST" != "pending" ] && [ -n "$INGRESS_HOST" ]; then
  echo "  App URL   : http://$INGRESS_HOST"
else
  echo "  App URL   : (Ingress hostname pending — check 'kubectl get ingress -n $NAMESPACE')"
fi
echo ""
echo "  Rollback  : kubectl rollout undo deployment/$APP_NAME -n $NAMESPACE"
echo "============================================"

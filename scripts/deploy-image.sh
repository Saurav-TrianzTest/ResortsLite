#!/bin/bash
set -e
set -o pipefail

# ============================================================
# deploy-image.sh — Deploy ResortsLite to AWS EKS
# ============================================================

APP_NAME="resortslite"
NAMESPACE="resortslite"
K8S_DIR="kubernetes"

echo "=============================================="
echo "  ResortsLite — AWS EKS Deployment Script"
echo "=============================================="
echo ""

# ---- Collect deployment inputs ----
read -rp "Enter AWS region (e.g. us-east-1): " AWS_REGION
if [ -z "$AWS_REGION" ]; then
  echo "ERROR: AWS region is required."
  exit 1
fi

read -rp "Enter EKS cluster name: " CLUSTER_NAME
if [ -z "$CLUSTER_NAME" ]; then
  echo "ERROR: EKS cluster name is required."
  exit 1
fi

read -rp "Enter full Docker image URI (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest): " IMAGE_URI
if [ -z "$IMAGE_URI" ]; then
  echo "ERROR: Docker image URI is required."
  exit 1
fi

echo ""
echo "---- Optional: Application Environment Variables ----"
echo "Press Enter to skip any variable and use the default."
echo ""

read -rp "Enter REDIS_HOST (ElastiCache endpoint) [localhost]: " INPUT_REDIS_HOST
REDIS_HOST="${INPUT_REDIS_HOST:-localhost}"

read -rp "Enter REDIS_PORT [6379]: " INPUT_REDIS_PORT
REDIS_PORT="${INPUT_REDIS_PORT:-6379}"

read -rp "Enter CACHE_TTL_SECONDS [3600]: " INPUT_CACHE_TTL
CACHE_TTL_SECONDS="${INPUT_CACHE_TTL:-3600}"

read -rp "Enter PAYMENT_API_URL [http://payment-svc.payments.svc.cluster.local:9090/payments/charge]: " INPUT_PAYMENT_URL
PAYMENT_API_URL="${INPUT_PAYMENT_URL:-http://payment-svc.payments.svc.cluster.local:9090/payments/charge}"

echo ""
echo "---- Configuring kubectl for EKS ----"
aws eks update-kubeconfig --region "${AWS_REGION}" --name "${CLUSTER_NAME}"
echo "kubectl configured for cluster: ${CLUSTER_NAME}"

echo ""
echo "---- Verifying cluster connectivity ----"
kubectl cluster-info || { echo "ERROR: Cannot connect to EKS cluster."; exit 1; }

echo ""
echo "---- Updating Kubernetes manifests ----"

# Create working copies of manifests
cp "${K8S_DIR}/deployment.yaml" "${K8S_DIR}/deployment.yaml.bak"

# Replace all placeholders using pipe delimiter
sed -i 's|{{IMAGE_URI}}|'"${IMAGE_URI}"'|g'           "${K8S_DIR}/deployment.yaml"
sed -i 's|{{REDIS_HOST}}|'"${REDIS_HOST}"'|g'         "${K8S_DIR}/deployment.yaml"
sed -i 's|{{REDIS_PORT}}|'"${REDIS_PORT}"'|g'         "${K8S_DIR}/deployment.yaml"
sed -i 's|{{CACHE_TTL_SECONDS}}|'"${CACHE_TTL_SECONDS}"'|g' "${K8S_DIR}/deployment.yaml"
sed -i 's|{{PAYMENT_API_URL}}|'"${PAYMENT_API_URL}"'|g' "${K8S_DIR}/deployment.yaml"

echo "Manifests updated with deployment values."

echo ""
echo "---- Applying Kubernetes manifests ----"

echo "Applying namespace..."
kubectl apply -f "${K8S_DIR}/namespace.yaml"

echo "Applying deployment..."
kubectl apply -f "${K8S_DIR}/deployment.yaml"

echo "Applying service..."
kubectl apply -f "${K8S_DIR}/service.yaml"

echo "Applying ingress..."
kubectl apply -f "${K8S_DIR}/ingress.yaml"

echo ""
echo "---- Waiting for deployment rollout ----"
kubectl rollout status deployment/"${APP_NAME}" -n "${NAMESPACE}" --timeout=300s
echo "Deployment rollout complete."

echo ""
echo "---- Verifying deployed resources ----"
kubectl get pods,svc,ingress -n "${NAMESPACE}"

echo ""
echo "---- Application Access ----"
INGRESS_HOST=$(kubectl get ingress "${APP_NAME}-ingress" -n "${NAMESPACE}" \
  -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || echo "pending")
echo "Ingress hostname: ${INGRESS_HOST}"
echo "Application URL : http://${INGRESS_HOST}"
echo "Health check    : http://${INGRESS_HOST}/actuator/health"

echo ""
echo "---- Rollback Instructions ----"
echo "To rollback to the previous version, run:"
echo "  kubectl rollout undo deployment/${APP_NAME} -n ${NAMESPACE}"

echo ""
echo "=============================================="
echo "  Deployment Complete!"
echo "  Cluster : ${CLUSTER_NAME}"
echo "  Image   : ${IMAGE_URI}"
echo "  Namespace: ${NAMESPACE}"
echo "=============================================="

# Restore original deployment.yaml
mv "${K8S_DIR}/deployment.yaml.bak" "${K8S_DIR}/deployment.yaml"

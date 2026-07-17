#!/bin/bash
set -e
set -o pipefail

APP_NAME="resortslite"
NAMESPACE="resortslite"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

echo "============================================================"
echo "  ResortsLite - Deploy to Azure AKS"
echo "============================================================"
echo ""

# ---- Azure / AKS credentials ----
read -rp "Enter Azure Resource Group name: " RESOURCE_GROUP
if [ -z "$RESOURCE_GROUP" ]; then
  echo "ERROR: Resource group cannot be empty."
  exit 1
fi

read -rp "Enter AKS Cluster name: " CLUSTER_NAME
if [ -z "$CLUSTER_NAME" ]; then
  echo "ERROR: AKS cluster name cannot be empty."
  exit 1
fi

# ---- Docker image URI ----
read -rp "Enter full Docker image URI (e.g. myregistry.azurecr.io/resortslite:latest): " IMAGE_URI
if [ -z "$IMAGE_URI" ]; then
  echo "ERROR: Image URI cannot be empty."
  exit 1
fi

echo ""
echo "---- Optional: Environment Variable Configuration ----"
echo "Press Enter to skip any variable and keep the placeholder."
echo ""

read -rp "Enter REDIS_HOST (e.g. my-redis.redis.cache.windows.net): " REDIS_HOST_VAL
read -rp "Enter REDIS_PORT (default 6379): " REDIS_PORT_VAL
read -rsp "Enter REDIS_PASSWORD (leave blank if none): " REDIS_PASSWORD_VAL
echo ""
read -rp "Enter PAYMENT_API_URL (e.g. http://payment-service/payments/charge): " PAYMENT_API_URL_VAL

echo ""
echo "============================================================"
echo "  Configuring kubectl for AKS cluster: $CLUSTER_NAME"
echo "============================================================"
az aks get-credentials --resource-group "$RESOURCE_GROUP" --name "$CLUSTER_NAME" --overwrite-existing

echo ""
echo "Verifying cluster connectivity ..."
kubectl cluster-info || { echo "ERROR: Cannot connect to AKS cluster."; exit 1; }

echo ""
echo "============================================================"
echo "  Updating Kubernetes manifests ..."
echo "============================================================"

# Work on copies to avoid modifying originals
DEPLOY_DIR="$PROJECT_ROOT/kubernetes"
TMP_DIR=$(mktemp -d)
cp "$DEPLOY_DIR"/*.yaml "$TMP_DIR/"

# Replace IMAGE_URI placeholder
sed -i "s|{{IMAGE_URI}}|${IMAGE_URI}|g" "$TMP_DIR/deployment.yaml"

# Replace environment variable placeholders (only if values were provided)
if [ -n "$REDIS_HOST_VAL" ]; then
  sed -i "s|{{REDIS_HOST}}|${REDIS_HOST_VAL}|g" "$TMP_DIR/deployment.yaml"
fi
if [ -n "$REDIS_PORT_VAL" ]; then
  sed -i "s|{{REDIS_PORT}}|${REDIS_PORT_VAL}|g" "$TMP_DIR/deployment.yaml"
fi
if [ -n "$REDIS_PASSWORD_VAL" ]; then
  sed -i "s|{{REDIS_PASSWORD}}|${REDIS_PASSWORD_VAL}|g" "$TMP_DIR/deployment.yaml"
fi
if [ -n "$PAYMENT_API_URL_VAL" ]; then
  sed -i "s|{{PAYMENT_API_URL}}|${PAYMENT_API_URL_VAL}|g" "$TMP_DIR/deployment.yaml"
fi

echo ""
echo "============================================================"
echo "  Applying Kubernetes manifests ..."
echo "============================================================"

echo "  [1/4] Applying namespace ..."
kubectl apply -f "$TMP_DIR/namespace.yaml"

echo "  [2/4] Applying deployment ..."
kubectl apply -f "$TMP_DIR/deployment.yaml"

echo "  [3/4] Applying service ..."
kubectl apply -f "$TMP_DIR/service.yaml"

echo "  [4/4] Applying ingress ..."
kubectl apply -f "$TMP_DIR/ingress.yaml"

echo ""
echo "============================================================"
echo "  Waiting for deployment rollout ..."
echo "============================================================"
kubectl rollout status deployment/"$APP_NAME" -n "$NAMESPACE" --timeout=300s

echo ""
echo "============================================================"
echo "  Verifying deployed resources ..."
echo "============================================================"
kubectl get pods,svc,ingress -n "$NAMESPACE"

echo ""
echo "============================================================"
echo "  Retrieving application URL ..."
echo "============================================================"
INGRESS_IP=$(kubectl get ingress resortslite-ingress -n "$NAMESPACE" -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || echo "")
INGRESS_HOST=$(kubectl get ingress resortslite-ingress -n "$NAMESPACE" -o jsonpath='{.spec.rules[0].host}' 2>/dev/null || echo "resortslite.example.com")

if [ -n "$INGRESS_IP" ]; then
  echo "  Application URL: http://$INGRESS_IP"
else
  echo "  Ingress host: http://$INGRESS_HOST"
  echo "  (Ingress IP may take a few minutes to be assigned)"
fi

echo ""
echo "  Health check endpoint: /actuator/health"
echo ""
echo "  Rollback command (if needed):"
echo "    kubectl rollout undo deployment/$APP_NAME -n $NAMESPACE"
echo ""

# Cleanup temp directory
rm -rf "$TMP_DIR"

echo "============================================================"
echo "  Deployment Complete!"
echo "============================================================"

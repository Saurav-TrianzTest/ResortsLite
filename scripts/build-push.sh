#!/usr/bin/env bash
# =============================================================================
# build-push.sh — Build and push the ResortsLite Docker image
# Usage: ./scripts/build-push.sh
# Run from the repository root directory.
# =============================================================================
set -e
set -o pipefail

PROJECT_NAME="resortsLite"

# ── Sanitise image name (lowercase, hyphens only) ─────────────────────────────
IMAGE_NAME=$(echo "$PROJECT_NAME" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-*//;s/-*$//')

echo "============================================="
echo "  ResortsLite — Docker Build & Push"
echo "============================================="
echo ""

# ── Prompt for image tag ──────────────────────────────────────────────────────
read -rp "Enter image tag [latest]: " IMAGE_TAG_INPUT
IMAGE_TAG=$(echo "${IMAGE_TAG_INPUT:-latest}" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9._-' '-' | sed 's/^-*//;s/-*$//')
if [ -z "$IMAGE_TAG" ]; then
  IMAGE_TAG="latest"
fi
echo "Using tag: $IMAGE_TAG"
echo ""

# ── Registry selection ────────────────────────────────────────────────────────
echo "Select target registry:"
echo "  1) AWS ECR"
echo "  2) Docker Hub"
read -rp "Enter choice [1]: " REGISTRY_CHOICE
REGISTRY_CHOICE="${REGISTRY_CHOICE:-1}"

# =============================================================================
# AWS ECR
# =============================================================================
if [ "$REGISTRY_CHOICE" = "1" ]; then
  read -rp "Enter AWS Region [us-east-1]: " AWS_REGION
  AWS_REGION="${AWS_REGION:-us-east-1}"

  read -rp "Enter ECR repository name [$IMAGE_NAME]: " ECR_REPO_INPUT
  ECR_REPO="${ECR_REPO_INPUT:-$IMAGE_NAME}"

  # Derive account ID and registry URL
  echo ""
  echo "Retrieving AWS Account ID..."
  ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
  REGISTRY_URL="${ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
  FULL_IMAGE_NAME="${REGISTRY_URL}/${ECR_REPO}:${IMAGE_TAG}"

  echo "Registry : $REGISTRY_URL"
  echo "Image    : $FULL_IMAGE_NAME"
  echo ""

  # Authenticate to ECR
  echo "Authenticating to ECR..."
  aws ecr get-login-password --region "$AWS_REGION" \
    | docker login --username AWS --password-stdin "$REGISTRY_URL"

  # Create ECR repository if it does not exist
  echo "Checking ECR repository '$ECR_REPO'..."
  aws ecr describe-repositories --repository-names "$ECR_REPO" --region "$AWS_REGION" >/dev/null 2>&1 \
    || aws ecr create-repository --repository-name "$ECR_REPO" --region "$AWS_REGION"
  echo "ECR repository ready."

# =============================================================================
# Docker Hub
# =============================================================================
elif [ "$REGISTRY_CHOICE" = "2" ]; then
  read -rp "Enter Docker Hub username: " DOCKER_USERNAME
  read -rsp "Enter Docker Hub password/token: " DOCKER_PASSWORD
  echo ""
  read -rp "Enter Docker Hub namespace [$DOCKER_USERNAME]: " DOCKER_NAMESPACE_INPUT
  DOCKER_NAMESPACE="${DOCKER_NAMESPACE_INPUT:-$DOCKER_USERNAME}"

  FULL_IMAGE_NAME="${DOCKER_NAMESPACE}/${IMAGE_NAME}:${IMAGE_TAG}"
  echo "Image: $FULL_IMAGE_NAME"
  echo ""

  echo "Authenticating to Docker Hub..."
  echo "$DOCKER_PASSWORD" | docker login --username "$DOCKER_USERNAME" --password-stdin

else
  echo "ERROR: Invalid registry choice '$REGISTRY_CHOICE'. Exiting."
  exit 1
fi

# =============================================================================
# Build
# =============================================================================
echo ""
echo "Building Docker image..."
echo "  Context   : . (repository root)"
echo "  Dockerfile: Dockerfile"
echo "  Tag       : $FULL_IMAGE_NAME"
echo ""

docker build -f Dockerfile -t "$FULL_IMAGE_NAME" .

echo ""
echo "Build successful: $FULL_IMAGE_NAME"

# =============================================================================
# Push
# =============================================================================
echo ""
echo "Pushing image to registry..."
docker push "$FULL_IMAGE_NAME"

echo ""
echo "============================================="
echo "  Push complete!"
echo "  Image: $FULL_IMAGE_NAME"
echo "============================================="

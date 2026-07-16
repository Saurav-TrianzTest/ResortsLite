#!/usr/bin/env bash
# =============================================================================
# build-push.sh  —  Build and push the ResortsLite Docker image
# Supports: AWS ECR  |  Docker Hub
# Usage   : bash scripts/build-push.sh   (run from repository root)
# =============================================================================
set -e

PROJECT_NAME="resortslite"
DOCKERFILE_PATH="Dockerfile"
BUILD_CONTEXT="."

# ---------------------------------------------------------------------------
# Sanitise image name: lowercase, replace non-alphanumeric with hyphens,
# strip leading/trailing hyphens
# ---------------------------------------------------------------------------
IMAGE_NAME=$(echo "$PROJECT_NAME" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-*//;s/-*$//')

echo "=============================================="
echo "  ResortsLite — Docker Build & Push"
echo "=============================================="
echo ""

# ---------------------------------------------------------------------------
# Registry selection
# ---------------------------------------------------------------------------
echo "Select container registry:"
echo "  1) AWS ECR"
echo "  2) Docker Hub"
echo ""
read -rp "Enter choice [1 or 2]: " REGISTRY_CHOICE

# ---------------------------------------------------------------------------
# Image tag
# ---------------------------------------------------------------------------
read -rp "Enter image tag (default: latest): " IMAGE_TAG_INPUT
IMAGE_TAG=$(echo "${IMAGE_TAG_INPUT:-latest}" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9._-' '-' | sed 's/^-*//;s/-*$//')
if [[ -z "$IMAGE_TAG" ]]; then
  IMAGE_TAG="latest"
fi
echo "Using tag: $IMAGE_TAG"
echo ""

# ---------------------------------------------------------------------------
# Registry-specific configuration
# ---------------------------------------------------------------------------
if [[ "$REGISTRY_CHOICE" == "1" ]]; then
  # ---- AWS ECR ----
  read -rp "Enter AWS Region (e.g. us-east-1): " AWS_REGION
  read -rp "Enter AWS Account ID: " AWS_ACCOUNT_ID
  read -rp "Enter ECR repository name (default: ${IMAGE_NAME}): " ECR_REPO_INPUT
  ECR_REPO="${ECR_REPO_INPUT:-$IMAGE_NAME}"

  REGISTRY_URL="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
  FULL_IMAGE_NAME="${REGISTRY_URL}/${ECR_REPO}:${IMAGE_TAG}"

  echo ""
  echo "Authenticating with AWS ECR..."
  aws ecr get-login-password --region "$AWS_REGION" \
    | docker login --username AWS --password-stdin "$REGISTRY_URL"

  echo "Checking / creating ECR repository '${ECR_REPO}'..."
  aws ecr describe-repositories --repository-names "$ECR_REPO" --region "$AWS_REGION" >/dev/null 2>&1 \
    || aws ecr create-repository --repository-name "$ECR_REPO" --region "$AWS_REGION"

elif [[ "$REGISTRY_CHOICE" == "2" ]]; then
  # ---- Docker Hub ----
  read -rp "Enter Docker Hub username: " DOCKER_USERNAME
  read -rsp "Enter Docker Hub password/token: " DOCKER_PASSWORD
  echo ""
  read -rp "Enter Docker Hub repository name (default: ${DOCKER_USERNAME}/${IMAGE_NAME}): " DOCKERHUB_REPO_INPUT
  DOCKERHUB_REPO="${DOCKERHUB_REPO_INPUT:-${DOCKER_USERNAME}/${IMAGE_NAME}}"

  FULL_IMAGE_NAME="${DOCKERHUB_REPO}:${IMAGE_TAG}"

  echo ""
  echo "Authenticating with Docker Hub..."
  echo "$DOCKER_PASSWORD" | docker login --username "$DOCKER_USERNAME" --password-stdin

else
  echo "ERROR: Invalid choice '${REGISTRY_CHOICE}'. Please enter 1 or 2."
  exit 1
fi

# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------
echo ""
echo "Building Docker image: ${FULL_IMAGE_NAME}"
echo "  Dockerfile : ${DOCKERFILE_PATH}"
echo "  Context    : ${BUILD_CONTEXT}"
echo ""
docker build -f "${DOCKERFILE_PATH}" -t "${FULL_IMAGE_NAME}" "${BUILD_CONTEXT}"

if [[ $? -ne 0 ]]; then
  echo "ERROR: Docker build failed."
  exit 1
fi

# ---------------------------------------------------------------------------
# Push
# ---------------------------------------------------------------------------
echo ""
echo "Pushing image: ${FULL_IMAGE_NAME}"
docker push "${FULL_IMAGE_NAME}"

if [[ $? -ne 0 ]]; then
  echo "ERROR: Docker push failed."
  exit 1
fi

echo ""
echo "=============================================="
echo "  SUCCESS"
echo "  Image pushed: ${FULL_IMAGE_NAME}"
echo "=============================================="

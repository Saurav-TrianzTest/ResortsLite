#!/usr/bin/env bash
# =============================================================================
# build-push.sh — Build and push ResortsLite Docker image
# Supports: AWS ECR and Docker Hub
# Usage: ./scripts/build-push.sh
# Run from repository root (Docker build context = project root)
# =============================================================================
set -e
set -o pipefail

PROJECT_NAME="resortslite"
DOCKERFILE_PATH="Dockerfile"

echo "=============================================="
echo "  ResortsLite — Docker Build & Push Script"
echo "=============================================="
echo ""

# ------------------------------------------------------------------------------
# Sanitize image name: lowercase, replace non-alphanumeric with hyphens,
# trim leading/trailing hyphens
# ------------------------------------------------------------------------------
IMAGE_NAME=$(echo "$PROJECT_NAME" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-*//;s/-*$//')

# ------------------------------------------------------------------------------
# Prompt for image tag
# ------------------------------------------------------------------------------
read -rp "Enter image tag [latest]: " INPUT_TAG
INPUT_TAG=$(echo "${INPUT_TAG:-latest}" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9._-' '-' | sed 's/^-*//;s/-*$//')
IMAGE_TAG="${INPUT_TAG:-latest}"
echo "Using image tag: ${IMAGE_TAG}"
echo ""

# ------------------------------------------------------------------------------
# Registry selection
# ------------------------------------------------------------------------------
echo "Select container registry:"
echo "  1) AWS ECR"
echo "  2) Docker Hub"
read -rp "Enter choice [1]: " REGISTRY_CHOICE
REGISTRY_CHOICE="${REGISTRY_CHOICE:-1}"

if [ "$REGISTRY_CHOICE" = "1" ]; then
    # --------------------------------------------------------------------------
    # AWS ECR
    # --------------------------------------------------------------------------
    echo ""
    echo "--- AWS ECR Configuration ---"
    read -rp "Enter AWS Region [us-east-1]: " AWS_REGION
    AWS_REGION="${AWS_REGION:-us-east-1}"

    read -rp "Enter AWS Account ID: " ACCOUNT_ID
    if [ -z "$ACCOUNT_ID" ]; then
        echo "Fetching AWS Account ID from STS..."
        ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
        echo "Account ID: ${ACCOUNT_ID}"
    fi

    read -rp "Enter ECR repository name [${IMAGE_NAME}]: " ECR_REPO
    ECR_REPO="${ECR_REPO:-${IMAGE_NAME}}"

    REGISTRY_URL="${ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
    FULL_IMAGE_NAME="${REGISTRY_URL}/${ECR_REPO}:${IMAGE_TAG}"

    echo ""
    echo "Logging in to AWS ECR..."
    aws ecr get-login-password --region "${AWS_REGION}" | \
        docker login --username AWS --password-stdin "${REGISTRY_URL}"

    echo ""
    echo "Checking if ECR repository '${ECR_REPO}' exists..."
    aws ecr describe-repositories --repository-names "${ECR_REPO}" --region "${AWS_REGION}" >/dev/null 2>&1 || \
        aws ecr create-repository --repository-name "${ECR_REPO}" --region "${AWS_REGION}"
    echo "ECR repository ready."

elif [ "$REGISTRY_CHOICE" = "2" ]; then
    # --------------------------------------------------------------------------
    # Docker Hub
    # --------------------------------------------------------------------------
    echo ""
    echo "--- Docker Hub Configuration ---"
    read -rp "Enter Docker Hub username: " DOCKER_USERNAME
    read -rsp "Enter Docker Hub password/token: " DOCKER_PASSWORD
    echo ""

    read -rp "Enter Docker Hub repository name [${DOCKER_USERNAME}/${IMAGE_NAME}]: " DOCKER_REPO
    DOCKER_REPO="${DOCKER_REPO:-${DOCKER_USERNAME}/${IMAGE_NAME}}"

    FULL_IMAGE_NAME="${DOCKER_REPO}:${IMAGE_TAG}"

    echo ""
    echo "Logging in to Docker Hub..."
    echo "${DOCKER_PASSWORD}" | docker login --username "${DOCKER_USERNAME}" --password-stdin

else
    echo "Invalid choice. Exiting."
    exit 1
fi

# ------------------------------------------------------------------------------
# Build Docker image
# ------------------------------------------------------------------------------
echo ""
echo "Building Docker image: ${FULL_IMAGE_NAME}"
echo "Build context: . (repository root)"
echo "Dockerfile: ${DOCKERFILE_PATH}"
echo ""
docker build -f "${DOCKERFILE_PATH}" -t "${FULL_IMAGE_NAME}" .

echo ""
echo "Build successful: ${FULL_IMAGE_NAME}"

# ------------------------------------------------------------------------------
# Push Docker image
# ------------------------------------------------------------------------------
echo ""
echo "Pushing image to registry..."
docker push "${FULL_IMAGE_NAME}"

echo ""
echo "=============================================="
echo "  Image pushed successfully!"
echo "  ${FULL_IMAGE_NAME}"
echo "=============================================="

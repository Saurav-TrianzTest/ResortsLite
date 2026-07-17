#!/bin/bash
set -e

PROJECT_NAME="resortslite"
IMAGE_NAME=$(echo "$PROJECT_NAME" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-*//;s/-*$//')

echo "============================================"
echo "  ResortsLite - Docker Build & Push Script"
echo "============================================"
echo ""
echo "Select container registry:"
echo "  1. Azure Container Registry (ACR)"
echo "  2. Docker Hub"
echo ""
read -rp "Enter choice [1 or 2]: " REGISTRY_CHOICE

echo ""
read -rp "Enter image tag (press Enter for 'latest'): " IMAGE_TAG_INPUT
IMAGE_TAG=$(echo "$IMAGE_TAG_INPUT" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9._-' '-' | sed 's/^-*//;s/-*$//')
if [ -z "$IMAGE_TAG" ]; then
  IMAGE_TAG="latest"
fi

echo ""

if [ "$REGISTRY_CHOICE" = "1" ]; then
  # ---- Azure Container Registry ----
  read -rp "Enter ACR name (e.g. myregistry): " ACR_NAME
  ACR_NAME=$(echo "$ACR_NAME" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-*//;s/-*$//')
  FULL_IMAGE_NAME="${ACR_NAME}.azurecr.io/${IMAGE_NAME}:${IMAGE_TAG}"

  echo ""
  echo "Logging in to Azure Container Registry: $ACR_NAME ..."
  az acr login --name "$ACR_NAME"

elif [ "$REGISTRY_CHOICE" = "2" ]; then
  # ---- Docker Hub ----
  read -rp "Enter Docker Hub username: " DOCKER_USERNAME
  read -rsp "Enter Docker Hub password/token: " DOCKER_PASSWORD
  echo ""
  FULL_IMAGE_NAME="${DOCKER_USERNAME}/${IMAGE_NAME}:${IMAGE_TAG}"

  echo ""
  echo "Logging in to Docker Hub ..."
  echo "$DOCKER_PASSWORD" | docker login --username "$DOCKER_USERNAME" --password-stdin

else
  echo "ERROR: Invalid choice. Please enter 1 or 2."
  exit 1
fi

echo ""
echo "Building Docker image: $FULL_IMAGE_NAME"
echo "Build context: $(pwd)"
docker build -f Dockerfile -t "$FULL_IMAGE_NAME" .

echo ""
echo "Pushing image: $FULL_IMAGE_NAME ..."
docker push "$FULL_IMAGE_NAME"

echo ""
echo "============================================"
echo "  Build & Push Complete!"
echo "  Image: $FULL_IMAGE_NAME"
echo "============================================"

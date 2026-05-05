#!/bin/bash

# Build and Push Script for ResortsLite Application
# This script builds the Docker image and pushes it to the selected registry

set -e

echo "=========================================="
echo "ResortsLite - Docker Build & Push Script"
echo "=========================================="
echo ""

# Project configuration
PROJECT_NAME="resortslite"

# Sanitize project name for Docker tag (lowercase, hyphenate)
IMAGE_NAME=$(echo "$PROJECT_NAME" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-*//;s/-*$//')

# Prompt for image tag
echo "Enter image tag (default: latest):"
read -r IMAGE_TAG
IMAGE_TAG=${IMAGE_TAG:-latest}

# Sanitize tag
IMAGE_TAG=$(echo "$IMAGE_TAG" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9.-' '-' | sed 's/^-*//;s/-*$//')
IMAGE_TAG=${IMAGE_TAG:-latest}

echo ""
echo "Select container registry:"
echo "1. AWS ECR (Elastic Container Registry)"
echo "2. Docker Hub"
echo ""
read -p "Enter choice (1 or 2): " REGISTRY_CHOICE

if [ "$REGISTRY_CHOICE" = "1" ]; then
    # AWS ECR Configuration
    echo ""
    echo "=== AWS ECR Configuration ==="
    read -p "Enter AWS Region (e.g., us-east-1): " AWS_REGION
    read -p "Enter AWS Account ID: " AWS_ACCOUNT_ID
    read -p "Enter ECR Repository Name (default: $IMAGE_NAME): " ECR_REPO
    ECR_REPO=${ECR_REPO:-$IMAGE_NAME}
    
    REGISTRY_URL="$AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com"
    FULL_IMAGE_NAME="$REGISTRY_URL/$ECR_REPO:$IMAGE_TAG"
    
    echo ""
    echo "Authenticating with AWS ECR..."
    aws ecr get-login-password --region "$AWS_REGION" | docker login --username AWS --password-stdin "$REGISTRY_URL"
    
    if [ $? -ne 0 ]; then
        echo "ERROR: ECR authentication failed"
        exit 1
    fi
    
    echo "Checking if ECR repository exists..."
    aws ecr describe-repositories --repository-names "$ECR_REPO" --region "$AWS_REGION" >/dev/null 2>&1 || {
        echo "Creating ECR repository: $ECR_REPO"
        aws ecr create-repository --repository-name "$ECR_REPO" --region "$AWS_REGION"
    }
    
elif [ "$REGISTRY_CHOICE" = "2" ]; then
    # Docker Hub Configuration
    echo ""
    echo "=== Docker Hub Configuration ==="
    read -p "Enter Docker Hub username: " DOCKER_USERNAME
    read -sp "Enter Docker Hub password/token: " DOCKER_PASSWORD
    echo ""
    read -p "Enter repository name (default: $IMAGE_NAME): " DOCKER_REPO
    DOCKER_REPO=${DOCKER_REPO:-$IMAGE_NAME}
    
    FULL_IMAGE_NAME="$DOCKER_USERNAME/$DOCKER_REPO:$IMAGE_TAG"
    
    echo ""
    echo "Authenticating with Docker Hub..."
    echo "$DOCKER_PASSWORD" | docker login --username "$DOCKER_USERNAME" --password-stdin
    
    if [ $? -ne 0 ]; then
        echo "ERROR: Docker Hub authentication failed"
        exit 1
    fi
else
    echo "ERROR: Invalid choice. Please select 1 or 2."
    exit 1
fi

echo ""
echo "=========================================="
echo "Building Docker image..."
echo "Image: $FULL_IMAGE_NAME"
echo "=========================================="
echo ""

docker build -t "$FULL_IMAGE_NAME" .

if [ $? -ne 0 ]; then
    echo "ERROR: Docker build failed"
    exit 1
fi

echo ""
echo "=========================================="
echo "Pushing image to registry..."
echo "=========================================="
echo ""

docker push "$FULL_IMAGE_NAME"

if [ $? -ne 0 ]; then
    echo "ERROR: Docker push failed"
    exit 1
fi

echo ""
echo "=========================================="
echo "SUCCESS!"
echo "=========================================="
echo "Image: $FULL_IMAGE_NAME"
echo ""
echo "You can now deploy this image to AWS ECS using the deploy-image.sh script."
echo ""

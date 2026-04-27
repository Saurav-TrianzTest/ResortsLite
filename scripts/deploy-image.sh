#!/bin/bash

# ============================================
# Deploy to AWS ECS Fargate Script
# For ResortsLite Spring Boot Application
# ============================================

set -e
set -o pipefail

echo "=========================================="
echo "AWS ECS Fargate Deployment Script"
echo "=========================================="
echo ""

# Get AWS Account ID
echo "Retrieving AWS Account ID..."
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
if [ $? -ne 0 ]; then
    echo "ERROR: Failed to retrieve AWS Account ID. Ensure AWS CLI is configured."
    exit 1
fi
echo "AWS Account ID: $ACCOUNT_ID"
echo ""

# Prompt for AWS Region
read -p "Enter AWS Region (e.g., us-east-1): " AWS_REGION
echo ""

# Prompt for ECS Cluster Name
read -p "Enter ECS Cluster Name: " CLUSTER_NAME
echo ""

# Check if cluster exists, create if not
echo "Checking if ECS cluster exists..."
aws ecs describe-clusters --clusters "$CLUSTER_NAME" --region "$AWS_REGION" >/dev/null 2>&1 || {
    echo "Cluster not found. Creating ECS cluster: $CLUSTER_NAME"
    aws ecs create-cluster --cluster-name "$CLUSTER_NAME" --region "$AWS_REGION"
    echo "ECS cluster created successfully"
}
echo ""

# Prompt for VPC and Network Configuration
echo "=== Network Configuration ==="
read -p "Enter VPC ID: " VPC_ID
read -p "Enter Subnet IDs (comma-separated, at least 2): " SUBNETS_INPUT
read -p "Enter Security Group ID: " SECURITY_GROUP

# Parse subnets
IFS=',' read -ra SUBNET_ARRAY <<< "$SUBNETS_INPUT"
SUBNET_1=$(echo "${SUBNET_ARRAY[0]}" | xargs)
SUBNET_2=$(echo "${SUBNET_ARRAY[1]}" | xargs)

echo ""
echo "VPC ID: $VPC_ID"
echo "Subnet 1: $SUBNET_1"
echo "Subnet 2: $SUBNET_2"
echo "Security Group: $SECURITY_GROUP"
echo ""

# Prompt for Docker Image URI
read -p "Enter Docker Image URI (e.g., 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest): " IMAGE_URI
echo ""

# Prompt for Redis Configuration
echo "=== Redis Configuration ==="
read -p "Enter Redis Host (e.g., redis.example.com): " REDIS_HOST
read -sp "Enter Redis Password (leave empty if none): " REDIS_PASSWORD
echo ""
echo ""

# Load Balancer Configuration
read -p "Do you need a load balancer for this service? (y/n): " NEED_LB

if [[ "$NEED_LB" =~ ^[Yy]$ ]]; then
    echo ""
    echo "Creating Application Load Balancer and Target Group..."
    
    # Create ALB
    ALB_NAME="resortslite-alb"
    echo "Creating ALB: $ALB_NAME"
    ALB_ARN=$(aws elbv2 create-load-balancer \
        --name "$ALB_NAME" \
        --subnets "$SUBNET_1" "$SUBNET_2" \
        --security-groups "$SECURITY_GROUP" \
        --scheme internet-facing \
        --type application \
        --ip-address-type ipv4 \
        --region "$AWS_REGION" \
        --query 'LoadBalancers[0].LoadBalancerArn' \
        --output text)
    
    if [ $? -ne 0 ]; then
        echo "ERROR: Failed to create ALB"
        exit 1
    fi
    
    echo "ALB created: $ALB_ARN"
    
    # Get ALB DNS name
    ALB_DNS=$(aws elbv2 describe-load-balancers \
        --load-balancer-arns "$ALB_ARN" \
        --region "$AWS_REGION" \
        --query 'LoadBalancers[0].DNSName' \
        --output text)
    
    # Create Target Group with target-type ip (required for Fargate)
    TG_NAME="resortslite-tg"
    echo "Creating Target Group: $TG_NAME"
    TARGET_GROUP_ARN=$(aws elbv2 create-target-group \
        --name "$TG_NAME" \
        --protocol HTTP \
        --port 8080 \
        --vpc-id "$VPC_ID" \
        --target-type ip \
        --health-check-enabled \
        --health-check-protocol HTTP \
        --health-check-path "/actuator/health" \
        --health-check-interval-seconds 30 \
        --health-check-timeout-seconds 5 \
        --healthy-threshold-count 2 \
        --unhealthy-threshold-count 3 \
        --region "$AWS_REGION" \
        --query 'TargetGroups[0].TargetGroupArn' \
        --output text)
    
    if [ $? -ne 0 ]; then
        echo "ERROR: Failed to create Target Group"
        exit 1
    fi
    
    echo "Target Group created: $TARGET_GROUP_ARN"
    
    # Create Listener
    echo "Creating ALB Listener..."
    aws elbv2 create-listener \
        --load-balancer-arn "$ALB_ARN" \
        --protocol HTTP \
        --port 80 \
        --default-actions Type=forward,TargetGroupArn="$TARGET_GROUP_ARN" \
        --region "$AWS_REGION" >/dev/null
    
    echo "ALB Listener created"
    echo ""
else
    echo "Skipping load balancer creation"
    TARGET_GROUP_ARN=""
    echo ""
fi

# Create CloudWatch Log Group
echo "Creating CloudWatch Log Group..."
aws logs create-log-group --log-group-name "/ecs/resortslite" --region "$AWS_REGION" 2>/dev/null || echo "Log group already exists"
echo ""

# Replace placeholders in task definition
echo "Preparing ECS Task Definition..."
cp ecs/task-definition.json ecs/task-definition-temp.json

sed -i "s|{{IMAGE_URI}}|$IMAGE_URI|g" ecs/task-definition-temp.json
sed -i "s|{{AWS_REGION}}|$AWS_REGION|g" ecs/task-definition-temp.json
sed -i "s|{{ACCOUNT_ID}}|$ACCOUNT_ID|g" ecs/task-definition-temp.json
sed -i "s|{{REDIS_HOST}}|$REDIS_HOST|g" ecs/task-definition-temp.json
sed -i "s|{{REDIS_PASSWORD}}|$REDIS_PASSWORD|g" ecs/task-definition-temp.json

echo "Task definition prepared"
echo ""

# Register Task Definition
echo "Registering ECS Task Definition..."
TASK_DEF_ARN=$(aws ecs register-task-definition \
    --cli-input-json file://ecs/task-definition-temp.json \
    --region "$AWS_REGION" \
    --query 'taskDefinition.taskDefinitionArn' \
    --output text)

if [ $? -ne 0 ]; then
    echo "ERROR: Failed to register task definition"
    rm -f ecs/task-definition-temp.json
    exit 1
fi

echo "Task definition registered: $TASK_DEF_ARN"
echo ""

# Clean up temp file
rm -f ecs/task-definition-temp.json

# Prepare service definition
echo "Preparing ECS Service Definition..."
cp ecs/service-definition.json ecs/service-definition-temp.json

sed -i "s|{{CLUSTER_NAME}}|$CLUSTER_NAME|g" ecs/service-definition-temp.json
sed -i "s|{{SUBNET_1}}|$SUBNET_1|g" ecs/service-definition-temp.json
sed -i "s|{{SUBNET_2}}|$SUBNET_2|g" ecs/service-definition-temp.json
sed -i "s|{{SECURITY_GROUP}}|$SECURITY_GROUP|g" ecs/service-definition-temp.json

if [ -z "$TARGET_GROUP_ARN" ]; then
    # Remove loadBalancers section if no LB
    jq 'del(.loadBalancers, .healthCheckGracePeriodSeconds)' ecs/service-definition-temp.json > ecs/service-definition-temp2.json
    mv ecs/service-definition-temp2.json ecs/service-definition-temp.json
else
    sed -i "s|{{TARGET_GROUP_ARN}}|$TARGET_GROUP_ARN|g" ecs/service-definition-temp.json
fi

echo "Service definition prepared"
echo ""

# Check if service exists
SERVICE_NAME="resortslite-service"
echo "Checking if ECS service exists..."
EXISTING_SERVICE=$(aws ecs describe-services \
    --cluster "$CLUSTER_NAME" \
    --services "$SERVICE_NAME" \
    --region "$AWS_REGION" \
    --query 'services[?status==`ACTIVE`].serviceName' \
    --output text)

if [ -z "$EXISTING_SERVICE" ] || [ "$EXISTING_SERVICE" == "None" ]; then
    echo "Service does not exist. Creating new service..."
    aws ecs create-service \
        --cli-input-json file://ecs/service-definition-temp.json \
        --region "$AWS_REGION"
    
    if [ $? -ne 0 ]; then
        echo "ERROR: Failed to create service"
        rm -f ecs/service-definition-temp.json
        exit 1
    fi
    
    echo "ECS service created successfully"
else
    echo "Service exists. Updating service..."
    aws ecs update-service \
        --cluster "$CLUSTER_NAME" \
        --service "$SERVICE_NAME" \
        --task-definition "$TASK_DEF_ARN" \
        --desired-count 2 \
        --region "$AWS_REGION" \
        --force-new-deployment
    
    if [ $? -ne 0 ]; then
        echo "ERROR: Failed to update service"
        rm -f ecs/service-definition-temp.json
        exit 1
    fi
    
    echo "ECS service updated successfully"
fi

echo ""

# Clean up temp file
rm -f ecs/service-definition-temp.json

# Wait for service stability
echo "Waiting for service to become stable (this may take a few minutes)..."
aws ecs wait services-stable \
    --cluster "$CLUSTER_NAME" \
    --services "$SERVICE_NAME" \
    --region "$AWS_REGION"

if [ $? -ne 0 ]; then
    echo "WARNING: Service did not stabilize within timeout period"
    echo "Check ECS console for service status"
else
    echo "Service is stable"
fi

echo ""

# Verify deployment
echo "=========================================="
echo "Deployment Summary"
echo "=========================================="
aws ecs describe-services \
    --cluster "$CLUSTER_NAME" \
    --services "$SERVICE_NAME" \
    --region "$AWS_REGION" \
    --query 'services[0].[serviceName,status,runningCount,desiredCount]' \
    --output table

echo ""
echo "CloudWatch Logs: /ecs/resortslite"
echo "Region: $AWS_REGION"

if [ -n "$ALB_DNS" ]; then
    echo ""
    echo "Application Load Balancer DNS: $ALB_DNS"
    echo "Access your application at: http://$ALB_DNS"
fi

echo ""
echo "=========================================="
echo "Deployment Complete!"
echo "=========================================="
echo ""
echo "Troubleshooting:"
echo "- View logs: aws logs tail /ecs/resortslite --follow --region $AWS_REGION"
echo "- Check tasks: aws ecs list-tasks --cluster $CLUSTER_NAME --region $AWS_REGION"
echo "- Describe service: aws ecs describe-services --cluster $CLUSTER_NAME --services $SERVICE_NAME --region $AWS_REGION"
echo "=========================================="

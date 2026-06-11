#!/bin/bash
# =============================================================================
# deploy-image.sh – Deploy ResortsLite to AWS ECS Fargate
# Usage: ./scripts/deploy-image.sh
# Prerequisites: aws-cli v2 configured with appropriate IAM permissions
# =============================================================================
set -e
set -o pipefail

SERVICE_NAME="resortsLite-service"
TASK_FAMILY="resortsLite-task"
LOG_GROUP="/ecs/resortsLite"
CONTAINER_NAME="resortsLite"
CONTAINER_PORT=8080

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
TASK_DEF_FILE="$PROJECT_ROOT/ecs/task-definition.json"
SVC_DEF_FILE="$PROJECT_ROOT/ecs/service-definition.json"

echo "=============================================="
echo "  ResortsLite – ECS Fargate Deployment"
echo "=============================================="
echo ""

# ---------------------------------------------------------------------------
# Collect inputs
# ---------------------------------------------------------------------------
read -rp "Enter AWS Region (e.g. us-east-1): " AWS_REGION
read -rp "Enter ECS Cluster name: " CLUSTER_NAME
read -rp "Enter ECR Image URI (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com/resortsLite:latest): " IMAGE_URI
read -rp "Enter VPC ID: " VPC_ID
read -rp "Enter Subnet IDs (comma-separated, e.g. subnet-aaa,subnet-bbb): " SUBNETS_RAW
read -rp "Enter Security Group ID: " SECURITY_GROUP

# Parse subnets
SUBNET_1=$(echo "$SUBNETS_RAW" | cut -d',' -f1 | tr -d ' ')
SUBNET_2=$(echo "$SUBNETS_RAW" | cut -d',' -f2 | tr -d ' ')
if [ -z "$SUBNET_2" ]; then
  SUBNET_2="$SUBNET_1"
fi

# ---------------------------------------------------------------------------
# Resolve AWS Account ID
# ---------------------------------------------------------------------------
echo ""
echo "Resolving AWS Account ID..."
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
echo "Account ID: $ACCOUNT_ID"

# ---------------------------------------------------------------------------
# Ensure CloudWatch log group exists
# ---------------------------------------------------------------------------
echo "Ensuring CloudWatch log group '${LOG_GROUP}' exists..."
aws logs create-log-group --log-group-name "$LOG_GROUP" --region "$AWS_REGION" 2>/dev/null || true

# ---------------------------------------------------------------------------
# Ensure ECS cluster exists
# ---------------------------------------------------------------------------
echo "Checking ECS cluster '${CLUSTER_NAME}'..."
CLUSTER_STATUS=$(aws ecs describe-clusters --clusters "$CLUSTER_NAME" --region "$AWS_REGION" \
  --query "clusters[0].status" --output text 2>/dev/null || echo "MISSING")
if [ "$CLUSTER_STATUS" != "ACTIVE" ]; then
  echo "Creating ECS cluster '${CLUSTER_NAME}'..."
  aws ecs create-cluster --cluster-name "$CLUSTER_NAME" --region "$AWS_REGION"
fi

# ---------------------------------------------------------------------------
# Load balancer (optional)
# ---------------------------------------------------------------------------
echo ""
read -rp "Do you need an Application Load Balancer for this service? (y/n): " NEED_LB
USE_LB=false
TARGET_GROUP_ARN=""

if [[ "$NEED_LB" =~ ^[Yy]$ ]]; then
  USE_LB=true
  LB_NAME="resortsLite-alb"
  TG_NAME="resortsLite-tg"

  echo "Creating Application Load Balancer '${LB_NAME}'..."
  LB_ARN=$(aws elbv2 create-load-balancer \
    --name "$LB_NAME" \
    --subnets "$SUBNET_1" "$SUBNET_2" \
    --security-groups "$SECURITY_GROUP" \
    --scheme internet-facing \
    --type application \
    --region "$AWS_REGION" \
    --query "LoadBalancers[0].LoadBalancerArn" \
    --output text)
  echo "ALB ARN: $LB_ARN"

  echo "Creating Target Group '${TG_NAME}' (target-type: ip for Fargate awsvpc)..."
  TARGET_GROUP_ARN=$(aws elbv2 create-target-group \
    --name "$TG_NAME" \
    --protocol HTTP \
    --port "$CONTAINER_PORT" \
    --vpc-id "$VPC_ID" \
    --target-type ip \
    --health-check-path "/actuator/health" \
    --health-check-interval-seconds 30 \
    --healthy-threshold-count 2 \
    --unhealthy-threshold-count 3 \
    --region "$AWS_REGION" \
    --query "TargetGroups[0].TargetGroupArn" \
    --output text)
  echo "Target Group ARN: $TARGET_GROUP_ARN"

  echo "Creating ALB Listener on port 80..."
  aws elbv2 create-listener \
    --load-balancer-arn "$LB_ARN" \
    --protocol HTTP \
    --port 80 \
    --default-actions "Type=forward,TargetGroupArn=${TARGET_GROUP_ARN}" \
    --region "$AWS_REGION" >/dev/null

  LB_DNS=$(aws elbv2 describe-load-balancers \
    --load-balancer-arns "$LB_ARN" \
    --region "$AWS_REGION" \
    --query "LoadBalancers[0].DNSName" \
    --output text)
fi

# ---------------------------------------------------------------------------
# Prepare task-definition.json (replace placeholders)
# ---------------------------------------------------------------------------
echo ""
echo "Preparing task definition..."
TASK_DEF_TMP=$(mktemp)
sed \
  -e "s|{{ACCOUNT_ID}}|${ACCOUNT_ID}|g" \
  -e "s|{{AWS_REGION}}|${AWS_REGION}|g" \
  -e "s|{{IMAGE_URI}}|${IMAGE_URI}|g" \
  "$TASK_DEF_FILE" > "$TASK_DEF_TMP"

# ---------------------------------------------------------------------------
# Register task definition
# ---------------------------------------------------------------------------
echo "Registering task definition '${TASK_FAMILY}'..."
TASK_DEF_ARN=$(aws ecs register-task-definition \
  --cli-input-json "file://${TASK_DEF_TMP}" \
  --region "$AWS_REGION" \
  --query "taskDefinition.taskDefinitionArn" \
  --output text)
echo "Task Definition ARN: $TASK_DEF_ARN"
rm -f "$TASK_DEF_TMP"

# ---------------------------------------------------------------------------
# Prepare service-definition.json (replace placeholders)
# ---------------------------------------------------------------------------
echo "Preparing service definition..."
SVC_DEF_TMP=$(mktemp)
sed \
  -e "s|{{CLUSTER_NAME}}|${CLUSTER_NAME}|g" \
  -e "s|{{SUBNET_1}}|${SUBNET_1}|g" \
  -e "s|{{SUBNET_2}}|${SUBNET_2}|g" \
  -e "s|{{SECURITY_GROUP}}|${SECURITY_GROUP}|g" \
  "$SVC_DEF_FILE" > "$SVC_DEF_TMP"

# Inject load balancer section if requested
if [ "$USE_LB" = true ]; then
  python3 - <<PYEOF
import json, sys

with open('${SVC_DEF_TMP}') as f:
    svc = json.load(f)

svc['loadBalancers'] = [{
    'targetGroupArn': '${TARGET_GROUP_ARN}',
    'containerName': '${CONTAINER_NAME}',
    'containerPort': ${CONTAINER_PORT}
}]
svc['healthCheckGracePeriodSeconds'] = 300

with open('${SVC_DEF_TMP}', 'w') as f:
    json.dump(svc, f, indent=2)
PYEOF
fi

# ---------------------------------------------------------------------------
# Create or update ECS service
# ---------------------------------------------------------------------------
EXISTING_SERVICE=$(aws ecs describe-services \
  --cluster "$CLUSTER_NAME" \
  --services "$SERVICE_NAME" \
  --region "$AWS_REGION" \
  --query "services[?status=='ACTIVE'].serviceName" \
  --output text 2>/dev/null || echo "")

if [ -z "$EXISTING_SERVICE" ] || [ "$EXISTING_SERVICE" = "None" ]; then
  echo "Creating ECS service '${SERVICE_NAME}'..."
  # Inject task definition ARN into service definition
  python3 - <<PYEOF
import json
with open('${SVC_DEF_TMP}') as f:
    svc = json.load(f)
svc['taskDefinition'] = '${TASK_DEF_ARN}'
with open('${SVC_DEF_TMP}', 'w') as f:
    json.dump(svc, f, indent=2)
PYEOF
  aws ecs create-service \
    --cli-input-json "file://${SVC_DEF_TMP}" \
    --region "$AWS_REGION"
else
  echo "Updating existing ECS service '${SERVICE_NAME}'..."
  aws ecs update-service \
    --cluster "$CLUSTER_NAME" \
    --service "$SERVICE_NAME" \
    --task-definition "$TASK_DEF_ARN" \
    --region "$AWS_REGION"
fi

rm -f "$SVC_DEF_TMP"

# ---------------------------------------------------------------------------
# Wait for stability
# ---------------------------------------------------------------------------
echo ""
echo "Waiting for service to reach stable state (this may take a few minutes)..."
aws ecs wait services-stable \
  --cluster "$CLUSTER_NAME" \
  --services "$SERVICE_NAME" \
  --region "$AWS_REGION"

# ---------------------------------------------------------------------------
# Verify deployment
# ---------------------------------------------------------------------------
echo ""
echo "Deployment verification:"
aws ecs describe-services \
  --cluster "$CLUSTER_NAME" \
  --services "$SERVICE_NAME" \
  --region "$AWS_REGION" \
  --query "services[0].{ServiceName:serviceName,Status:status,DesiredCount:desiredCount,RunningCount:runningCount,PendingCount:pendingCount}" \
  --output table

echo ""
echo "=============================================="
echo "  Deployment Complete!"
echo "  Service : $SERVICE_NAME"
echo "  Cluster : $CLUSTER_NAME"
echo "  Region  : $AWS_REGION"
echo "  Logs    : $LOG_GROUP"
if [ "$USE_LB" = true ]; then
  echo "  App URL : http://$LB_DNS"
fi
echo "=============================================="
echo ""
echo "Troubleshooting tips:"
echo "  View logs  : aws logs tail $LOG_GROUP --follow --region $AWS_REGION"
echo "  List tasks : aws ecs list-tasks --cluster $CLUSTER_NAME --service-name $SERVICE_NAME --region $AWS_REGION"
echo "  Stop svc   : aws ecs update-service --cluster $CLUSTER_NAME --service $SERVICE_NAME --desired-count 0 --region $AWS_REGION"

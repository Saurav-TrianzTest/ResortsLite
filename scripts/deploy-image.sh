#!/usr/bin/env bash
# =============================================================================
# deploy-image.sh — Deploy ResortsLite to AWS ECS Fargate
# Usage: ./scripts/deploy-image.sh
# Run from the repository root directory.
# =============================================================================
set -e
set -o pipefail

SERVICE_NAME="resortsLite-service"
TASK_FAMILY="resortsLite-task"
LOG_GROUP="/ecs/resortsLite"
TASK_DEF_FILE="ecs/task-definition.json"
SVC_DEF_FILE="ecs/service-definition.json"

echo "============================================="
echo "  ResortsLite — ECS Fargate Deployment"
echo "============================================="
echo ""

# ── Collect configuration ─────────────────────────────────────────────────────
read -rp "Enter AWS Region [us-east-1]: " AWS_REGION
AWS_REGION="${AWS_REGION:-us-east-1}"

read -rp "Enter ECS Cluster name [resortsLite-cluster]: " CLUSTER_NAME
CLUSTER_NAME="${CLUSTER_NAME:-resortsLite-cluster}"

read -rp "Enter ECR Image URI (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com/resortsLite:latest): " IMAGE_URI
if [ -z "$IMAGE_URI" ]; then
  echo "ERROR: Image URI is required."
  exit 1
fi

read -rp "Enter VPC ID (e.g. vpc-0abc1234): " VPC_ID
if [ -z "$VPC_ID" ]; then
  echo "ERROR: VPC ID is required."
  exit 1
fi

read -rp "Enter Subnet IDs (comma-separated, e.g. subnet-aaa,subnet-bbb): " SUBNETS_INPUT
if [ -z "$SUBNETS_INPUT" ]; then
  echo "ERROR: At least one subnet ID is required."
  exit 1
fi
# Split into SUBNET_1 and SUBNET_2
SUBNET_1=$(echo "$SUBNETS_INPUT" | cut -d',' -f1 | tr -d ' ')
SUBNET_2=$(echo "$SUBNETS_INPUT" | cut -d',' -f2 | tr -d ' ')
if [ -z "$SUBNET_2" ]; then
  SUBNET_2="$SUBNET_1"
fi

read -rp "Enter Security Group ID (e.g. sg-0abc1234): " SECURITY_GROUP
if [ -z "$SECURITY_GROUP" ]; then
  echo "ERROR: Security Group ID is required."
  exit 1
fi

# ── Retrieve AWS Account ID ───────────────────────────────────────────────────
echo ""
echo "Retrieving AWS Account ID..."
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
echo "Account ID: $ACCOUNT_ID"

# ── Ensure CloudWatch log group exists ────────────────────────────────────────
echo ""
echo "Ensuring CloudWatch log group '$LOG_GROUP' exists..."
aws logs create-log-group --log-group-name "$LOG_GROUP" --region "$AWS_REGION" 2>/dev/null || true
echo "Log group ready."

# ── Ensure ECS cluster exists ─────────────────────────────────────────────────
echo ""
echo "Checking ECS cluster '$CLUSTER_NAME'..."
CLUSTER_STATUS=$(aws ecs describe-clusters --clusters "$CLUSTER_NAME" --region "$AWS_REGION" \
  --query "clusters[0].status" --output text 2>/dev/null || echo "MISSING")
if [ "$CLUSTER_STATUS" != "ACTIVE" ]; then
  echo "Creating ECS cluster '$CLUSTER_NAME'..."
  aws ecs create-cluster --cluster-name "$CLUSTER_NAME" --region "$AWS_REGION"
fi
echo "Cluster ready."

# ── Load balancer prompt ──────────────────────────────────────────────────────
echo ""
read -rp "Do you need an Application Load Balancer for this service? (y/n) [n]: " NEED_LB
NEED_LB="${NEED_LB:-n}"

TARGET_GROUP_ARN=""
ALB_DNS=""

if [[ "$NEED_LB" =~ ^[Yy]$ ]]; then
  echo ""
  echo "Creating Application Load Balancer..."

  # Create ALB
  ALB_ARN=$(aws elbv2 create-load-balancer \
    --name "resortsLite-alb" \
    --subnets "$SUBNET_1" "$SUBNET_2" \
    --security-groups "$SECURITY_GROUP" \
    --scheme internet-facing \
    --type application \
    --region "$AWS_REGION" \
    --query "LoadBalancers[0].LoadBalancerArn" \
    --output text)
  echo "ALB ARN: $ALB_ARN"

  ALB_DNS=$(aws elbv2 describe-load-balancers \
    --load-balancer-arns "$ALB_ARN" \
    --region "$AWS_REGION" \
    --query "LoadBalancers[0].DNSName" \
    --output text)

  # Create Target Group (target-type ip required for Fargate awsvpc)
  TARGET_GROUP_ARN=$(aws elbv2 create-target-group \
    --name "resortsLite-tg" \
    --protocol HTTP \
    --port 8080 \
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

  # Create listener
  aws elbv2 create-listener \
    --load-balancer-arn "$ALB_ARN" \
    --protocol HTTP \
    --port 80 \
    --default-actions "Type=forward,TargetGroupArn=$TARGET_GROUP_ARN" \
    --region "$AWS_REGION" >/dev/null
  echo "ALB listener created."
fi

# ── Prepare task definition ───────────────────────────────────────────────────
echo ""
echo "Preparing task definition..."
cp "$TASK_DEF_FILE" /tmp/task-definition-deploy.json

sed -i "s|{{ACCOUNT_ID}}|${ACCOUNT_ID}|g"       /tmp/task-definition-deploy.json
sed -i "s|{{AWS_REGION}}|${AWS_REGION}|g"        /tmp/task-definition-deploy.json
sed -i "s|{{IMAGE_URI}}|${IMAGE_URI}|g"          /tmp/task-definition-deploy.json
sed -i "s|{{EFS_FILE_SYSTEM_ID}}|fs-placeholder|g" /tmp/task-definition-deploy.json

# ── Register task definition ──────────────────────────────────────────────────
echo "Registering task definition..."
TASK_DEF_ARN=$(aws ecs register-task-definition \
  --cli-input-json file:///tmp/task-definition-deploy.json \
  --region "$AWS_REGION" \
  --query "taskDefinition.taskDefinitionArn" \
  --output text)
echo "Task Definition ARN: $TASK_DEF_ARN"

# ── Prepare service definition ────────────────────────────────────────────────
echo ""
echo "Preparing service definition..."
cp "$SVC_DEF_FILE" /tmp/service-definition-deploy.json

sed -i "s|{{CLUSTER_NAME}}|${CLUSTER_NAME}|g"     /tmp/service-definition-deploy.json
sed -i "s|{{SUBNET_1}}|${SUBNET_1}|g"             /tmp/service-definition-deploy.json
sed -i "s|{{SUBNET_2}}|${SUBNET_2}|g"             /tmp/service-definition-deploy.json
sed -i "s|{{SECURITY_GROUP}}|${SECURITY_GROUP}|g" /tmp/service-definition-deploy.json

# ── Add load balancer section if requested ────────────────────────────────────
if [[ "$NEED_LB" =~ ^[Yy]$ ]] && [ -n "$TARGET_GROUP_ARN" ]; then
  # Inject loadBalancers and healthCheckGracePeriodSeconds into service JSON using Python
  python3 - <<PYEOF
import json, sys
with open('/tmp/service-definition-deploy.json') as f:
    svc = json.load(f)
svc['loadBalancers'] = [{
    'targetGroupArn': '${TARGET_GROUP_ARN}',
    'containerName': 'resortsLite',
    'containerPort': 8080
}]
svc['healthCheckGracePeriodSeconds'] = 300
with open('/tmp/service-definition-deploy.json', 'w') as f:
    json.dump(svc, f, indent=2)
PYEOF
fi

# ── Create or update ECS service ──────────────────────────────────────────────
echo ""
echo "Checking if ECS service '$SERVICE_NAME' exists..."
EXISTING_SERVICE=$(aws ecs describe-services \
  --cluster "$CLUSTER_NAME" \
  --services "$SERVICE_NAME" \
  --region "$AWS_REGION" \
  --query "services[?status!='INACTIVE'].serviceName" \
  --output text 2>/dev/null || echo "")

if [ -z "$EXISTING_SERVICE" ] || [ "$EXISTING_SERVICE" = "None" ]; then
  echo "Creating ECS service '$SERVICE_NAME'..."
  aws ecs create-service \
    --cli-input-json file:///tmp/service-definition-deploy.json \
    --region "$AWS_REGION"
  echo "Service created."
else
  echo "Updating existing ECS service '$SERVICE_NAME'..."
  aws ecs update-service \
    --cluster "$CLUSTER_NAME" \
    --service "$SERVICE_NAME" \
    --task-definition "$TASK_DEF_ARN" \
    --region "$AWS_REGION"
  echo "Service updated."
fi

# ── Wait for stability ────────────────────────────────────────────────────────
echo ""
echo "Waiting for service to stabilise (this may take a few minutes)..."
aws ecs wait services-stable \
  --cluster "$CLUSTER_NAME" \
  --services "$SERVICE_NAME" \
  --region "$AWS_REGION"

# ── Verify deployment ─────────────────────────────────────────────────────────
echo ""
echo "Verifying deployment..."
aws ecs describe-services \
  --cluster "$CLUSTER_NAME" \
  --services "$SERVICE_NAME" \
  --region "$AWS_REGION" \
  --query "services[0].{Status:status,Running:runningCount,Desired:desiredCount,TaskDef:taskDefinition}" \
  --output table

echo ""
echo "============================================="
echo "  Deployment complete!"
echo "  Service  : $SERVICE_NAME"
echo "  Cluster  : $CLUSTER_NAME"
echo "  Region   : $AWS_REGION"
echo "  Log Group: $LOG_GROUP"
if [ -n "$ALB_DNS" ]; then
  echo "  ALB URL  : http://$ALB_DNS"
fi
echo "============================================="
echo ""
echo "Troubleshooting hints:"
echo "  View logs : aws logs tail $LOG_GROUP --follow --region $AWS_REGION"
echo "  List tasks: aws ecs list-tasks --cluster $CLUSTER_NAME --service-name $SERVICE_NAME --region $AWS_REGION"
echo "  Stop svc  : aws ecs update-service --cluster $CLUSTER_NAME --service $SERVICE_NAME --desired-count 0 --region $AWS_REGION"

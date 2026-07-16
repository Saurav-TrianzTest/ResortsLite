#!/usr/bin/env bash
# =============================================================================
# deploy-image.sh  —  Deploy ResortsLite to AWS ECS Fargate
# Usage: bash scripts/deploy-image.sh   (run from repository root)
# =============================================================================
set -e
set -o pipefail

SERVICE_NAME="resortslite-service"
TASK_FAMILY="resortslite-task"
CONTAINER_NAME="resortslite"
APP_PORT=8080
LOG_GROUP="/ecs/resortslite"
TASK_DEF_FILE="ecs/task-definition.json"
SERVICE_DEF_FILE="ecs/service-definition.json"

echo "=============================================="
echo "  ResortsLite — AWS ECS Fargate Deployment"
echo "=============================================="
echo ""

# ---------------------------------------------------------------------------
# Collect inputs
# ---------------------------------------------------------------------------
read -rp "Enter AWS Region (e.g. us-east-1): " AWS_REGION
read -rp "Enter ECS Cluster name (default: resortslite-cluster): " CLUSTER_INPUT
CLUSTER_NAME="${CLUSTER_INPUT:-resortslite-cluster}"

read -rp "Enter ECR Image URI (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest): " IMAGE_URI

read -rp "Enter Subnet ID 1 (e.g. subnet-xxxxxxxx): " SUBNET_1
read -rp "Enter Subnet ID 2 (e.g. subnet-yyyyyyyy): " SUBNET_2
read -rp "Enter Security Group ID (e.g. sg-xxxxxxxx): " SECURITY_GROUP

# ---------------------------------------------------------------------------
# Resolve AWS Account ID
# ---------------------------------------------------------------------------
echo ""
echo "Resolving AWS Account ID..."
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
echo "Account ID: ${ACCOUNT_ID}"

# ---------------------------------------------------------------------------
# Ensure CloudWatch log group exists
# ---------------------------------------------------------------------------
echo ""
echo "Ensuring CloudWatch log group '${LOG_GROUP}' exists..."
aws logs create-log-group --log-group-name "${LOG_GROUP}" --region "${AWS_REGION}" 2>/dev/null || true

# ---------------------------------------------------------------------------
# Ensure ECS cluster exists
# ---------------------------------------------------------------------------
echo ""
echo "Checking ECS cluster '${CLUSTER_NAME}'..."
CLUSTER_STATUS=$(aws ecs describe-clusters --clusters "${CLUSTER_NAME}" --region "${AWS_REGION}" \
  --query "clusters[0].status" --output text 2>/dev/null || echo "MISSING")

if [[ "$CLUSTER_STATUS" != "ACTIVE" ]]; then
  echo "Creating ECS cluster '${CLUSTER_NAME}'..."
  aws ecs create-cluster --cluster-name "${CLUSTER_NAME}" --region "${AWS_REGION}"
else
  echo "Cluster '${CLUSTER_NAME}' is ACTIVE."
fi

# ---------------------------------------------------------------------------
# Load balancer prompt
# ---------------------------------------------------------------------------
echo ""
read -rp "Do you need an Application Load Balancer for this service? (y/n): " NEED_LB

TARGET_GROUP_ARN=""
ALB_DNS=""

if [[ "$NEED_LB" =~ ^[Yy]$ ]]; then
  read -rp "Enter VPC ID for the ALB (e.g. vpc-xxxxxxxx): " VPC_ID

  echo ""
  echo "Creating Application Load Balancer..."
  ALB_ARN=$(aws elbv2 create-load-balancer \
    --name "resortslite-alb" \
    --subnets "${SUBNET_1}" "${SUBNET_2}" \
    --security-groups "${SECURITY_GROUP}" \
    --scheme internet-facing \
    --type application \
    --region "${AWS_REGION}" \
    --query "LoadBalancers[0].LoadBalancerArn" \
    --output text)
  echo "ALB ARN: ${ALB_ARN}"

  ALB_DNS=$(aws elbv2 describe-load-balancers \
    --load-balancer-arns "${ALB_ARN}" \
    --region "${AWS_REGION}" \
    --query "LoadBalancers[0].DNSName" \
    --output text)

  echo "Creating Target Group (type: ip for Fargate awsvpc)..."
  TARGET_GROUP_ARN=$(aws elbv2 create-target-group \
    --name "resortslite-tg" \
    --protocol HTTP \
    --port "${APP_PORT}" \
    --vpc-id "${VPC_ID}" \
    --target-type ip \
    --health-check-path "/actuator/health" \
    --health-check-interval-seconds 30 \
    --healthy-threshold-count 2 \
    --unhealthy-threshold-count 3 \
    --region "${AWS_REGION}" \
    --query "TargetGroups[0].TargetGroupArn" \
    --output text)
  echo "Target Group ARN: ${TARGET_GROUP_ARN}"

  echo "Creating ALB Listener on port 80..."
  aws elbv2 create-listener \
    --load-balancer-arn "${ALB_ARN}" \
    --protocol HTTP \
    --port 80 \
    --default-actions "Type=forward,TargetGroupArn=${TARGET_GROUP_ARN}" \
    --region "${AWS_REGION}" >/dev/null
fi

# ---------------------------------------------------------------------------
# Prepare task definition (replace placeholders in a temp copy)
# ---------------------------------------------------------------------------
echo ""
echo "Preparing task definition..."
TMP_TASK_DEF="/tmp/task-definition-deploy.json"
cp "${TASK_DEF_FILE}" "${TMP_TASK_DEF}"

sed -i "s|{{ACCOUNT_ID}}|${ACCOUNT_ID}|g"   "${TMP_TASK_DEF}"
sed -i "s|{{AWS_REGION}}|${AWS_REGION}|g"   "${TMP_TASK_DEF}"
sed -i "s|{{IMAGE_URI}}|${IMAGE_URI}|g"      "${TMP_TASK_DEF}"

# ---------------------------------------------------------------------------
# Register task definition
# ---------------------------------------------------------------------------
echo "Registering task definition '${TASK_FAMILY}'..."
TASK_DEF_ARN=$(aws ecs register-task-definition \
  --cli-input-json "file://${TMP_TASK_DEF}" \
  --region "${AWS_REGION}" \
  --query "taskDefinition.taskDefinitionArn" \
  --output text)
echo "Task Definition ARN: ${TASK_DEF_ARN}"

# ---------------------------------------------------------------------------
# Prepare service definition (replace placeholders in a temp copy)
# ---------------------------------------------------------------------------
echo ""
echo "Preparing service definition..."
TMP_SERVICE_DEF="/tmp/service-definition-deploy.json"
cp "${SERVICE_DEF_FILE}" "${TMP_SERVICE_DEF}"

sed -i "s|{{CLUSTER_NAME}}|${CLUSTER_NAME}|g"     "${TMP_SERVICE_DEF}"
sed -i "s|{{SUBNET_1}}|${SUBNET_1}|g"             "${TMP_SERVICE_DEF}"
sed -i "s|{{SUBNET_2}}|${SUBNET_2}|g"             "${TMP_SERVICE_DEF}"
sed -i "s|{{SECURITY_GROUP}}|${SECURITY_GROUP}|g" "${TMP_SERVICE_DEF}"

# Inject load balancer section if requested
if [[ "$NEED_LB" =~ ^[Yy]$ ]]; then
  # Add loadBalancers and healthCheckGracePeriodSeconds via Python (portable JSON edit)
  python3 - <<PYEOF
import json, sys

with open("${TMP_SERVICE_DEF}") as f:
    svc = json.load(f)

svc["loadBalancers"] = [{
    "targetGroupArn": "${TARGET_GROUP_ARN}",
    "containerName": "${CONTAINER_NAME}",
    "containerPort": ${APP_PORT}
}]
svc["healthCheckGracePeriodSeconds"] = 300

with open("${TMP_SERVICE_DEF}", "w") as f:
    json.dump(svc, f, indent=2)
PYEOF
fi

# ---------------------------------------------------------------------------
# Create or update ECS service
# ---------------------------------------------------------------------------
echo ""
EXISTING_SERVICE=$(aws ecs describe-services \
  --cluster "${CLUSTER_NAME}" \
  --services "${SERVICE_NAME}" \
  --region "${AWS_REGION}" \
  --query "services[?status=='ACTIVE'].serviceName" \
  --output text 2>/dev/null || echo "")

if [[ -z "$EXISTING_SERVICE" || "$EXISTING_SERVICE" == "None" ]]; then
  echo "Creating ECS service '${SERVICE_NAME}'..."
  aws ecs create-service \
    --cli-input-json "file://${TMP_SERVICE_DEF}" \
    --region "${AWS_REGION}"
else
  echo "Updating existing ECS service '${SERVICE_NAME}'..."
  aws ecs update-service \
    --cluster "${CLUSTER_NAME}" \
    --service "${SERVICE_NAME}" \
    --task-definition "${TASK_DEF_ARN}" \
    --desired-count 2 \
    --region "${AWS_REGION}"
fi

# ---------------------------------------------------------------------------
# Wait for stability
# ---------------------------------------------------------------------------
echo ""
echo "Waiting for service to reach stable state (this may take a few minutes)..."
aws ecs wait services-stable \
  --cluster "${CLUSTER_NAME}" \
  --services "${SERVICE_NAME}" \
  --region "${AWS_REGION}"

# ---------------------------------------------------------------------------
# Verify and summarise
# ---------------------------------------------------------------------------
echo ""
echo "=============================================="
echo "  DEPLOYMENT COMPLETE"
echo "=============================================="
aws ecs describe-services \
  --cluster "${CLUSTER_NAME}" \
  --services "${SERVICE_NAME}" \
  --region "${AWS_REGION}" \
  --query "services[0].{Status:status,Running:runningCount,Desired:desiredCount,TaskDef:taskDefinition}" \
  --output table

echo ""
echo "CloudWatch Logs : ${LOG_GROUP}"
echo "ECS Cluster     : ${CLUSTER_NAME}"
echo "ECS Service     : ${SERVICE_NAME}"
if [[ -n "$ALB_DNS" ]]; then
  echo "Load Balancer   : http://${ALB_DNS}"
  echo "Health Check    : http://${ALB_DNS}/actuator/health"
fi
echo ""
echo "Troubleshooting:"
echo "  aws ecs list-tasks --cluster ${CLUSTER_NAME} --service-name ${SERVICE_NAME} --region ${AWS_REGION}"
echo "  aws logs tail ${LOG_GROUP} --follow --region ${AWS_REGION}"

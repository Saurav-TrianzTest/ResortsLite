@echo off
setlocal enabledelayedexpansion

:: =============================================================================
:: deploy-image.bat — Deploy ResortsLite to AWS ECS Fargate (Windows)
:: Usage: scripts\deploy-image.bat
:: Run from the repository root directory.
:: =============================================================================

set "SERVICE_NAME=resortsLite-service"
set "TASK_FAMILY=resortsLite-task"
set "LOG_GROUP=/ecs/resortsLite"
set "TASK_DEF_FILE=ecs\task-definition.json"
set "SVC_DEF_FILE=ecs\service-definition.json"

echo =============================================
echo   ResortsLite — ECS Fargate Deployment
echo =============================================
echo.

:: ── Collect configuration ─────────────────────────────────────────────────────
set /p "AWS_REGION=Enter AWS Region [us-east-1]: "
if "!AWS_REGION!"=="" set "AWS_REGION=us-east-1"

set /p "CLUSTER_NAME=Enter ECS Cluster name [resortsLite-cluster]: "
if "!CLUSTER_NAME!"=="" set "CLUSTER_NAME=resortsLite-cluster"

set /p "IMAGE_URI=Enter ECR Image URI (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com/resortsLite:latest): "
if "!IMAGE_URI!"=="" (
    echo ERROR: Image URI is required.
    exit /b 1
)

set /p "VPC_ID=Enter VPC ID (e.g. vpc-0abc1234): "
if "!VPC_ID!"=="" (
    echo ERROR: VPC ID is required.
    exit /b 1
)

set /p "SUBNETS_INPUT=Enter Subnet IDs comma-separated (e.g. subnet-aaa,subnet-bbb): "
if "!SUBNETS_INPUT!"=="" (
    echo ERROR: At least one subnet ID is required.
    exit /b 1
)

:: Split subnets
for /f "tokens=1,2 delims=," %%a in ("!SUBNETS_INPUT!") do (
    set "SUBNET_1=%%a"
    set "SUBNET_2=%%b"
)
if "!SUBNET_2!"=="" set "SUBNET_2=!SUBNET_1!"

set /p "SECURITY_GROUP=Enter Security Group ID (e.g. sg-0abc1234): "
if "!SECURITY_GROUP!"=="" (
    echo ERROR: Security Group ID is required.
    exit /b 1
)

:: ── Retrieve AWS Account ID ───────────────────────────────────────────────────
echo.
echo Retrieving AWS Account ID...
for /f "delims=" %%a in ('aws sts get-caller-identity --query Account --output text') do set "ACCOUNT_ID=%%a"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to retrieve AWS Account ID. Check AWS CLI configuration.
    exit /b 1
)
echo Account ID: !ACCOUNT_ID!

:: ── Ensure CloudWatch log group exists ────────────────────────────────────────
echo.
echo Ensuring CloudWatch log group '!LOG_GROUP!' exists...
aws logs create-log-group --log-group-name "!LOG_GROUP!" --region "!AWS_REGION!" >nul 2>&1
echo Log group ready.

:: ── Ensure ECS cluster exists ─────────────────────────────────────────────────
echo.
echo Checking ECS cluster '!CLUSTER_NAME!'...
for /f "delims=" %%s in ('aws ecs describe-clusters --clusters "!CLUSTER_NAME!" --region "!AWS_REGION!" --query "clusters[0].status" --output text 2^>nul') do set "CLUSTER_STATUS=%%s"
if not "!CLUSTER_STATUS!"=="ACTIVE" (
    echo Creating ECS cluster '!CLUSTER_NAME!'...
    aws ecs create-cluster --cluster-name "!CLUSTER_NAME!" --region "!AWS_REGION!"
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to create ECS cluster.
        exit /b 1
    )
)
echo Cluster ready.

:: ── Load balancer prompt ──────────────────────────────────────────────────────
echo.
set /p "NEED_LB=Do you need an Application Load Balancer for this service? (y/n) [n]: "
if "!NEED_LB!"=="" set "NEED_LB=n"

set "TARGET_GROUP_ARN="
set "ALB_DNS="

if /i "!NEED_LB!"=="y" (
    echo.
    echo Creating Application Load Balancer...

    for /f "delims=" %%a in ('aws elbv2 create-load-balancer --name "resortsLite-alb" --subnets "!SUBNET_1!" "!SUBNET_2!" --security-groups "!SECURITY_GROUP!" --scheme internet-facing --type application --region "!AWS_REGION!" --query "LoadBalancers[0].LoadBalancerArn" --output text') do set "ALB_ARN=%%a"
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to create ALB.
        exit /b 1
    )
    echo ALB ARN: !ALB_ARN!

    for /f "delims=" %%d in ('aws elbv2 describe-load-balancers --load-balancer-arns "!ALB_ARN!" --region "!AWS_REGION!" --query "LoadBalancers[0].DNSName" --output text') do set "ALB_DNS=%%d"

    for /f "delims=" %%t in ('aws elbv2 create-target-group --name "resortsLite-tg" --protocol HTTP --port 8080 --vpc-id "!VPC_ID!" --target-type ip --health-check-path "/actuator/health" --health-check-interval-seconds 30 --healthy-threshold-count 2 --unhealthy-threshold-count 3 --region "!AWS_REGION!" --query "TargetGroups[0].TargetGroupArn" --output text') do set "TARGET_GROUP_ARN=%%t"
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to create Target Group.
        exit /b 1
    )
    echo Target Group ARN: !TARGET_GROUP_ARN!

    aws elbv2 create-listener --load-balancer-arn "!ALB_ARN!" --protocol HTTP --port 80 --default-actions "Type=forward,TargetGroupArn=!TARGET_GROUP_ARN!" --region "!AWS_REGION!" >nul
    echo ALB listener created.
)

:: ── Prepare task definition ───────────────────────────────────────────────────
echo.
echo Preparing task definition...
copy /y "!TASK_DEF_FILE!" "%TEMP%\task-definition-deploy.json" >nul

powershell -NoProfile -Command ^
  "(Get-Content '%TEMP%\task-definition-deploy.json') -replace '{{ACCOUNT_ID}}','!ACCOUNT_ID!' -replace '{{AWS_REGION}}','!AWS_REGION!' -replace '{{IMAGE_URI}}','!IMAGE_URI!' -replace '{{EFS_FILE_SYSTEM_ID}}','fs-placeholder' | Set-Content '%TEMP%\task-definition-deploy.json'"

:: ── Register task definition ──────────────────────────────────────────────────
echo Registering task definition...
for /f "delims=" %%r in ('aws ecs register-task-definition --cli-input-json "file://%TEMP%\task-definition-deploy.json" --region "!AWS_REGION!" --query "taskDefinition.taskDefinitionArn" --output text') do set "TASK_DEF_ARN=%%r"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to register task definition.
    exit /b 1
)
echo Task Definition ARN: !TASK_DEF_ARN!

:: ── Prepare service definition ────────────────────────────────────────────────
echo.
echo Preparing service definition...
copy /y "!SVC_DEF_FILE!" "%TEMP%\service-definition-deploy.json" >nul

powershell -NoProfile -Command ^
  "(Get-Content '%TEMP%\service-definition-deploy.json') -replace '{{CLUSTER_NAME}}','!CLUSTER_NAME!' -replace '{{SUBNET_1}}','!SUBNET_1!' -replace '{{SUBNET_2}}','!SUBNET_2!' -replace '{{SECURITY_GROUP}}','!SECURITY_GROUP!' | Set-Content '%TEMP%\service-definition-deploy.json'"

:: ── Inject load balancer if requested ────────────────────────────────────────
if /i "!NEED_LB!"=="y" (
    if not "!TARGET_GROUP_ARN!"=="" (
        powershell -NoProfile -Command ^
          "$svc = Get-Content '%TEMP%\service-definition-deploy.json' | ConvertFrom-Json; $svc | Add-Member -Force -NotePropertyName 'loadBalancers' -NotePropertyValue @(@{targetGroupArn='!TARGET_GROUP_ARN!';containerName='resortsLite';containerPort=8080}); $svc | Add-Member -Force -NotePropertyName 'healthCheckGracePeriodSeconds' -NotePropertyValue 300; $svc | ConvertTo-Json -Depth 10 | Set-Content '%TEMP%\service-definition-deploy.json'"
    )
)

:: ── Create or update ECS service ──────────────────────────────────────────────
echo.
echo Checking if ECS service '!SERVICE_NAME!' exists...
for /f "delims=" %%e in ('aws ecs describe-services --cluster "!CLUSTER_NAME!" --services "!SERVICE_NAME!" --region "!AWS_REGION!" --query "services[?status!='INACTIVE'].serviceName" --output text 2^>nul') do set "EXISTING_SERVICE=%%e"

if "!EXISTING_SERVICE!"=="" (
    echo Creating ECS service '!SERVICE_NAME!'...
    aws ecs create-service --cli-input-json "file://%TEMP%\service-definition-deploy.json" --region "!AWS_REGION!"
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to create ECS service.
        exit /b 1
    )
    echo Service created.
) else (
    echo Updating existing ECS service '!SERVICE_NAME!'...
    aws ecs update-service --cluster "!CLUSTER_NAME!" --service "!SERVICE_NAME!" --task-definition "!TASK_DEF_ARN!" --region "!AWS_REGION!"
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to update ECS service.
        exit /b 1
    )
    echo Service updated.
)

:: ── Wait for stability ────────────────────────────────────────────────────────
echo.
echo Waiting for service to stabilise (this may take a few minutes)...
aws ecs wait services-stable --cluster "!CLUSTER_NAME!" --services "!SERVICE_NAME!" --region "!AWS_REGION!"
if !ERRORLEVEL! neq 0 (
    echo WARNING: Service did not stabilise within the expected time. Check ECS console.
)

:: ── Verify deployment ─────────────────────────────────────────────────────────
echo.
echo Verifying deployment...
aws ecs describe-services --cluster "!CLUSTER_NAME!" --services "!SERVICE_NAME!" --region "!AWS_REGION!" --query "services[0].{Status:status,Running:runningCount,Desired:desiredCount}" --output table

echo.
echo =============================================
echo   Deployment complete!
echo   Service  : !SERVICE_NAME!
echo   Cluster  : !CLUSTER_NAME!
echo   Region   : !AWS_REGION!
echo   Log Group: !LOG_GROUP!
if not "!ALB_DNS!"=="" echo   ALB URL  : http://!ALB_DNS!
echo =============================================
echo.
echo Troubleshooting hints:
echo   View logs : aws logs tail !LOG_GROUP! --follow --region !AWS_REGION!
echo   List tasks: aws ecs list-tasks --cluster !CLUSTER_NAME! --service-name !SERVICE_NAME! --region !AWS_REGION!

endlocal

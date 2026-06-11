@echo off
setlocal enabledelayedexpansion

REM =============================================================================
REM deploy-image.bat - Deploy ResortsLite to AWS ECS Fargate (Windows)
REM Usage: scripts\deploy-image.bat
REM Prerequisites: aws-cli v2 configured with appropriate IAM permissions
REM =============================================================================

set "SERVICE_NAME=resortsLite-service"
set "TASK_FAMILY=resortsLite-task"
set "LOG_GROUP=/ecs/resortsLite"
set "CONTAINER_NAME=resortsLite"
set "CONTAINER_PORT=8080"

REM Resolve paths relative to this script
set "SCRIPT_DIR=%~dp0"
set "PROJECT_ROOT=%SCRIPT_DIR%.."
set "TASK_DEF_FILE=%PROJECT_ROOT%\ecs\task-definition.json"
set "SVC_DEF_FILE=%PROJECT_ROOT%\ecs\service-definition.json"
set "TASK_DEF_TMP=%TEMP%\task-def-tmp.json"
set "SVC_DEF_TMP=%TEMP%\svc-def-tmp.json"

echo ==============================================
echo   ResortsLite - ECS Fargate Deployment
echo ==============================================
echo.

REM ---------------------------------------------------------------------------
REM Collect inputs
REM ---------------------------------------------------------------------------
set /p "AWS_REGION=Enter AWS Region (e.g. us-east-1): "
set /p "CLUSTER_NAME=Enter ECS Cluster name: "
set /p "IMAGE_URI=Enter ECR Image URI: "
set /p "VPC_ID=Enter VPC ID: "
set /p "SUBNETS_RAW=Enter Subnet IDs (comma-separated, e.g. subnet-aaa,subnet-bbb): "
set /p "SECURITY_GROUP=Enter Security Group ID: "

REM Parse subnets
for /f "tokens=1,2 delims=," %%A in ("!SUBNETS_RAW!") do (
    set "SUBNET_1=%%A"
    set "SUBNET_2=%%B"
)
REM Trim spaces
for /f "tokens=*" %%A in ("!SUBNET_1!") do set "SUBNET_1=%%A"
for /f "tokens=*" %%A in ("!SUBNET_2!") do set "SUBNET_2=%%A"
if "!SUBNET_2!"=="" set "SUBNET_2=!SUBNET_1!"

REM ---------------------------------------------------------------------------
REM Resolve AWS Account ID
REM ---------------------------------------------------------------------------
echo.
echo Resolving AWS Account ID...
for /f "delims=" %%A in ('aws sts get-caller-identity --query Account --output text') do set "ACCOUNT_ID=%%A"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to get AWS Account ID. Check your AWS credentials.
    exit /b 1
)
echo Account ID: !ACCOUNT_ID!

REM ---------------------------------------------------------------------------
REM Ensure CloudWatch log group exists
REM ---------------------------------------------------------------------------
echo Ensuring CloudWatch log group '!LOG_GROUP!' exists...
aws logs create-log-group --log-group-name "!LOG_GROUP!" --region "!AWS_REGION!" >nul 2>&1

REM ---------------------------------------------------------------------------
REM Ensure ECS cluster exists
REM ---------------------------------------------------------------------------
echo Checking ECS cluster '!CLUSTER_NAME!'...
for /f "delims=" %%S in ('aws ecs describe-clusters --clusters "!CLUSTER_NAME!" --region "!AWS_REGION!" --query "clusters[0].status" --output text 2^>nul') do set "CLUSTER_STATUS=%%S"
if not "!CLUSTER_STATUS!"=="ACTIVE" (
    echo Creating ECS cluster '!CLUSTER_NAME!'...
    aws ecs create-cluster --cluster-name "!CLUSTER_NAME!" --region "!AWS_REGION!"
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to create ECS cluster.
        exit /b 1
    )
)

REM ---------------------------------------------------------------------------
REM Load balancer (optional)
REM ---------------------------------------------------------------------------
echo.
set /p "NEED_LB=Do you need an Application Load Balancer for this service? (y/n): "
set "USE_LB=false"
set "TARGET_GROUP_ARN="
set "LB_DNS="

if /i "!NEED_LB!"=="y" (
    set "USE_LB=true"
    set "LB_NAME=resortsLite-alb"
    set "TG_NAME=resortsLite-tg"

    echo Creating Application Load Balancer '!LB_NAME!'...
    for /f "delims=" %%A in ('aws elbv2 create-load-balancer --name "!LB_NAME!" --subnets "!SUBNET_1!" "!SUBNET_2!" --security-groups "!SECURITY_GROUP!" --scheme internet-facing --type application --region "!AWS_REGION!" --query "LoadBalancers[0].LoadBalancerArn" --output text') do set "LB_ARN=%%A"
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to create load balancer.
        exit /b 1
    )
    echo ALB ARN: !LB_ARN!

    echo Creating Target Group '!TG_NAME!'...
    for /f "delims=" %%A in ('aws elbv2 create-target-group --name "!TG_NAME!" --protocol HTTP --port !CONTAINER_PORT! --vpc-id "!VPC_ID!" --target-type ip --health-check-path "/actuator/health" --health-check-interval-seconds 30 --healthy-threshold-count 2 --unhealthy-threshold-count 3 --region "!AWS_REGION!" --query "TargetGroups[0].TargetGroupArn" --output text') do set "TARGET_GROUP_ARN=%%A"
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to create target group.
        exit /b 1
    )
    echo Target Group ARN: !TARGET_GROUP_ARN!

    echo Creating ALB Listener on port 80...
    aws elbv2 create-listener --load-balancer-arn "!LB_ARN!" --protocol HTTP --port 80 --default-actions "Type=forward,TargetGroupArn=!TARGET_GROUP_ARN!" --region "!AWS_REGION!" >nul
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to create ALB listener.
        exit /b 1
    )

    for /f "delims=" %%A in ('aws elbv2 describe-load-balancers --load-balancer-arns "!LB_ARN!" --region "!AWS_REGION!" --query "LoadBalancers[0].DNSName" --output text') do set "LB_DNS=%%A"
)

REM ---------------------------------------------------------------------------
REM Prepare task-definition.json (replace placeholders using PowerShell)
REM ---------------------------------------------------------------------------
echo.
echo Preparing task definition...
powershell -NoProfile -Command ^
  "(Get-Content '!TASK_DEF_FILE!') -replace '{{ACCOUNT_ID}}','!ACCOUNT_ID!' -replace '{{AWS_REGION}}','!AWS_REGION!' -replace '{{IMAGE_URI}}','!IMAGE_URI!' | Set-Content '!TASK_DEF_TMP!'"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to prepare task definition.
    exit /b 1
)

REM ---------------------------------------------------------------------------
REM Register task definition
REM ---------------------------------------------------------------------------
echo Registering task definition '!TASK_FAMILY!'...
for /f "delims=" %%A in ('aws ecs register-task-definition --cli-input-json "file://!TASK_DEF_TMP!" --region "!AWS_REGION!" --query "taskDefinition.taskDefinitionArn" --output text') do set "TASK_DEF_ARN=%%A"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to register task definition.
    exit /b 1
)
echo Task Definition ARN: !TASK_DEF_ARN!
del /f /q "!TASK_DEF_TMP!" >nul 2>&1

REM ---------------------------------------------------------------------------
REM Prepare service-definition.json
REM ---------------------------------------------------------------------------
echo Preparing service definition...
powershell -NoProfile -Command ^
  "(Get-Content '!SVC_DEF_FILE!') -replace '{{CLUSTER_NAME}}','!CLUSTER_NAME!' -replace '{{SUBNET_1}}','!SUBNET_1!' -replace '{{SUBNET_2}}','!SUBNET_2!' -replace '{{SECURITY_GROUP}}','!SECURITY_GROUP!' | Set-Content '!SVC_DEF_TMP!'"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to prepare service definition.
    exit /b 1
)

REM Inject load balancer and task definition ARN via PowerShell
if "!USE_LB!"=="true" (
    powershell -NoProfile -Command ^
      "$svc = Get-Content '!SVC_DEF_TMP!' | ConvertFrom-Json; $svc.taskDefinition = '!TASK_DEF_ARN!'; $lb = @{targetGroupArn='!TARGET_GROUP_ARN!'; containerName='!CONTAINER_NAME!'; containerPort=!CONTAINER_PORT!}; $svc | Add-Member -NotePropertyName loadBalancers -NotePropertyValue @($lb) -Force; $svc | Add-Member -NotePropertyName healthCheckGracePeriodSeconds -NotePropertyValue 300 -Force; $svc | ConvertTo-Json -Depth 10 | Set-Content '!SVC_DEF_TMP!'"
) else (
    powershell -NoProfile -Command ^
      "$svc = Get-Content '!SVC_DEF_TMP!' | ConvertFrom-Json; $svc.taskDefinition = '!TASK_DEF_ARN!'; $svc | ConvertTo-Json -Depth 10 | Set-Content '!SVC_DEF_TMP!'"
)
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to update service definition.
    exit /b 1
)

REM ---------------------------------------------------------------------------
REM Create or update ECS service
REM ---------------------------------------------------------------------------
for /f "delims=" %%A in ('aws ecs describe-services --cluster "!CLUSTER_NAME!" --services "!SERVICE_NAME!" --region "!AWS_REGION!" --query "services[?status==''ACTIVE''].serviceName" --output text 2^>nul') do set "EXISTING_SERVICE=%%A"

if "!EXISTING_SERVICE!"=="" (
    echo Creating ECS service '!SERVICE_NAME!'...
    aws ecs create-service --cli-input-json "file://!SVC_DEF_TMP!" --region "!AWS_REGION!"
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to create ECS service.
        exit /b 1
    )
) else (
    echo Updating existing ECS service '!SERVICE_NAME!'...
    aws ecs update-service --cluster "!CLUSTER_NAME!" --service "!SERVICE_NAME!" --task-definition "!TASK_DEF_ARN!" --region "!AWS_REGION!"
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to update ECS service.
        exit /b 1
    )
)
del /f /q "!SVC_DEF_TMP!" >nul 2>&1

REM ---------------------------------------------------------------------------
REM Wait for stability
REM ---------------------------------------------------------------------------
echo.
echo Waiting for service to reach stable state (this may take a few minutes)...
aws ecs wait services-stable --cluster "!CLUSTER_NAME!" --services "!SERVICE_NAME!" --region "!AWS_REGION!"
if !ERRORLEVEL! neq 0 (
    echo WARNING: Service did not reach stable state within the timeout period.
    echo Check the ECS console for details.
)

REM ---------------------------------------------------------------------------
REM Verify deployment
REM ---------------------------------------------------------------------------
echo.
echo Deployment verification:
aws ecs describe-services --cluster "!CLUSTER_NAME!" --services "!SERVICE_NAME!" --region "!AWS_REGION!" --query "services[0].{ServiceName:serviceName,Status:status,DesiredCount:desiredCount,RunningCount:runningCount,PendingCount:pendingCount}" --output table

echo.
echo ==============================================
echo   Deployment Complete!
echo   Service : !SERVICE_NAME!
echo   Cluster : !CLUSTER_NAME!
echo   Region  : !AWS_REGION!
echo   Logs    : !LOG_GROUP!
if "!USE_LB!"=="true" (
    echo   App URL : http://!LB_DNS!
)
echo ==============================================
echo.
echo Troubleshooting tips:
echo   View logs  : aws logs tail !LOG_GROUP! --follow --region !AWS_REGION!
echo   List tasks : aws ecs list-tasks --cluster !CLUSTER_NAME! --service-name !SERVICE_NAME! --region !AWS_REGION!
echo   Stop svc   : aws ecs update-service --cluster !CLUSTER_NAME! --service !SERVICE_NAME! --desired-count 0 --region !AWS_REGION!

endlocal

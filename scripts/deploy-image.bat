@echo off
setlocal enabledelayedexpansion

:: =============================================================================
:: deploy-image.bat — Deploy ResortsLite to AWS ECS Fargate (Windows)
:: Usage: scripts\deploy-image.bat
:: =============================================================================

set SERVICE_NAME=resortslite-service
set TASK_FAMILY=resortslite-task
set CONTAINER_NAME=resortslite
set APP_PORT=8080
set LOG_GROUP=/ecs/resortslite
set TASK_DEF_FILE=ecs\task-definition.json
set SERVICE_DEF_FILE=ecs\service-definition.json

echo ==============================================
echo   ResortsLite - ECS Fargate Deployment Script
echo ==============================================
echo.

:: ------------------------------------------------------------------------------
:: Collect deployment parameters
:: ------------------------------------------------------------------------------
set /p AWS_REGION="Enter AWS Region [us-east-1]: "
if "!AWS_REGION!"=="" set AWS_REGION=us-east-1

set /p CLUSTER_NAME="Enter ECS Cluster name [resortslite-cluster]: "
if "!CLUSTER_NAME!"=="" set CLUSTER_NAME=resortslite-cluster

set /p IMAGE_URI="Enter ECR Image URI (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest): "
if "!IMAGE_URI!"=="" (
    echo ERROR: Image URI is required.
    exit /b 1
)

set /p VPC_ID="Enter VPC ID: "
if "!VPC_ID!"=="" (
    echo ERROR: VPC ID is required.
    exit /b 1
)

set /p SUBNETS_INPUT="Enter Subnet IDs (comma-separated, e.g. subnet-aaa,subnet-bbb): "
if "!SUBNETS_INPUT!"=="" (
    echo ERROR: At least one subnet ID is required.
    exit /b 1
)

set /p SECURITY_GROUP="Enter Security Group ID: "
if "!SECURITY_GROUP!"=="" (
    echo ERROR: Security Group ID is required.
    exit /b 1
)

:: Parse subnets
for /f "tokens=1,2 delims=," %%a in ("!SUBNETS_INPUT!") do (
    set SUBNET_1=%%a
    set SUBNET_2=%%b
)
if "!SUBNET_2!"=="" set SUBNET_2=!SUBNET_1!

:: Trim spaces from subnets
for /f "tokens=*" %%a in ("!SUBNET_1!") do set SUBNET_1=%%a
for /f "tokens=*" %%a in ("!SUBNET_2!") do set SUBNET_2=%%a

:: ------------------------------------------------------------------------------
:: Retrieve AWS Account ID
:: ------------------------------------------------------------------------------
echo.
echo Retrieving AWS Account ID...
for /f "delims=" %%i in ('aws sts get-caller-identity --query Account --output text') do set ACCOUNT_ID=%%i
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to retrieve AWS Account ID. Check AWS CLI configuration.
    exit /b 1
)
echo Account ID: !ACCOUNT_ID!

:: ------------------------------------------------------------------------------
:: Ensure CloudWatch log group exists
:: ------------------------------------------------------------------------------
echo.
echo Ensuring CloudWatch log group '!LOG_GROUP!' exists...
aws logs create-log-group --log-group-name !LOG_GROUP! --region !AWS_REGION! >nul 2>&1
echo Log group ready.

:: ------------------------------------------------------------------------------
:: Ensure ECS cluster exists
:: ------------------------------------------------------------------------------
echo.
echo Checking ECS cluster '!CLUSTER_NAME!'...
for /f "delims=" %%i in ('aws ecs describe-clusters --clusters !CLUSTER_NAME! --region !AWS_REGION! --query "clusters[0].status" --output text 2^>nul') do set CLUSTER_STATUS=%%i
if "!CLUSTER_STATUS!" neq "ACTIVE" (
    echo Creating ECS cluster '!CLUSTER_NAME!'...
    aws ecs create-cluster --cluster-name !CLUSTER_NAME! --region !AWS_REGION!
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to create ECS cluster.
        exit /b 1
    )
)
echo Cluster ready.

:: ------------------------------------------------------------------------------
:: Load balancer prompt
:: ------------------------------------------------------------------------------
echo.
set /p NEED_LB="Do you need an Application Load Balancer for this service? (y/n) [n]: "
if "!NEED_LB!"=="" set NEED_LB=n

set TARGET_GROUP_ARN=
set ALB_DNS=

if /i "!NEED_LB!"=="y" (
    echo.
    echo Creating Application Load Balancer...

    for /f "delims=" %%i in ('aws elbv2 create-load-balancer --name resortslite-alb --subnets !SUBNET_1! !SUBNET_2! --security-groups !SECURITY_GROUP! --scheme internet-facing --type application --ip-address-type ipv4 --region !AWS_REGION! --query "LoadBalancers[0].LoadBalancerArn" --output text') do set ALB_ARN=%%i
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to create ALB.
        exit /b 1
    )
    echo ALB ARN: !ALB_ARN!

    for /f "delims=" %%i in ('aws elbv2 describe-load-balancers --load-balancer-arns !ALB_ARN! --region !AWS_REGION! --query "LoadBalancers[0].DNSName" --output text') do set ALB_DNS=%%i

    for /f "delims=" %%i in ('aws elbv2 create-target-group --name resortslite-tg --protocol HTTP --port !APP_PORT! --vpc-id !VPC_ID! --target-type ip --health-check-path /actuator/health --health-check-interval-seconds 30 --health-check-timeout-seconds 10 --healthy-threshold-count 2 --unhealthy-threshold-count 3 --region !AWS_REGION! --query "TargetGroups[0].TargetGroupArn" --output text') do set TARGET_GROUP_ARN=%%i
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to create Target Group.
        exit /b 1
    )
    echo Target Group ARN: !TARGET_GROUP_ARN!

    aws elbv2 create-listener --load-balancer-arn !ALB_ARN! --protocol HTTP --port 80 --default-actions "Type=forward,TargetGroupArn=!TARGET_GROUP_ARN!" --region !AWS_REGION! >nul
    echo ALB Listener created.
)

:: ------------------------------------------------------------------------------
:: Prepare task definition (replace placeholders using PowerShell)
:: ------------------------------------------------------------------------------
echo.
echo Preparing task definition...
set TASK_DEF_TMP=%TEMP%\task-def-tmp.json
powershell -NoProfile -Command ^
    "(Get-Content '%TASK_DEF_FILE%') -replace '{{IMAGE_URI}}','!IMAGE_URI!' -replace '{{AWS_REGION}}','!AWS_REGION!' -replace '{{ACCOUNT_ID}}','!ACCOUNT_ID!' | Set-Content '!TASK_DEF_TMP!'"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to prepare task definition.
    exit /b 1
)

:: ------------------------------------------------------------------------------
:: Register task definition
:: ------------------------------------------------------------------------------
echo Registering task definition...
for /f "delims=" %%i in ('aws ecs register-task-definition --cli-input-json file://!TASK_DEF_TMP! --region !AWS_REGION! --query "taskDefinition.taskDefinitionArn" --output text') do set TASK_DEF_ARN=%%i
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to register task definition.
    exit /b 1
)
echo Task Definition ARN: !TASK_DEF_ARN!
del /f /q "!TASK_DEF_TMP!" >nul 2>&1

:: ------------------------------------------------------------------------------
:: Prepare service definition (replace placeholders using PowerShell)
:: ------------------------------------------------------------------------------
echo.
echo Preparing service definition...
set SERVICE_DEF_TMP=%TEMP%\service-def-tmp.json
powershell -NoProfile -Command ^
    "(Get-Content '%SERVICE_DEF_FILE%') -replace '{{CLUSTER_NAME}}','!CLUSTER_NAME!' -replace '{{SUBNET_1}}','!SUBNET_1!' -replace '{{SUBNET_2}}','!SUBNET_2!' -replace '{{SECURITY_GROUP}}','!SECURITY_GROUP!' | Set-Content '!SERVICE_DEF_TMP!'"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to prepare service definition.
    exit /b 1
)

:: Add load balancer config if needed
if /i "!NEED_LB!"=="y" (
    if "!TARGET_GROUP_ARN!" neq "" (
        powershell -NoProfile -Command ^
            "$svc = Get-Content '!SERVICE_DEF_TMP!' | ConvertFrom-Json; $svc | Add-Member -NotePropertyName 'loadBalancers' -NotePropertyValue @(@{targetGroupArn='!TARGET_GROUP_ARN!';containerName='!CONTAINER_NAME!';containerPort=!APP_PORT!}) -Force; $svc | Add-Member -NotePropertyName 'healthCheckGracePeriodSeconds' -NotePropertyValue 300 -Force; $svc | ConvertTo-Json -Depth 10 | Set-Content '!SERVICE_DEF_TMP!'"
    )
)

:: ------------------------------------------------------------------------------
:: Create or update ECS service
:: ------------------------------------------------------------------------------
echo.
echo Checking if ECS service '!SERVICE_NAME!' exists...
for /f "delims=" %%i in ('aws ecs describe-services --cluster !CLUSTER_NAME! --services !SERVICE_NAME! --region !AWS_REGION! --query "services[?status==''ACTIVE''].serviceName" --output text 2^>nul') do set EXISTING_SERVICE=%%i

if "!EXISTING_SERVICE!"=="" (
    echo Creating new ECS service '!SERVICE_NAME!'...
    aws ecs create-service --cli-input-json file://!SERVICE_DEF_TMP! --region !AWS_REGION!
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to create ECS service.
        exit /b 1
    )
    echo Service created.
) else (
    echo Updating existing ECS service '!SERVICE_NAME!'...
    aws ecs update-service --cluster !CLUSTER_NAME! --service !SERVICE_NAME! --task-definition !TASK_DEF_ARN! --region !AWS_REGION! >nul
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to update ECS service.
        exit /b 1
    )
    echo Service updated.
)

del /f /q "!SERVICE_DEF_TMP!" >nul 2>&1

:: ------------------------------------------------------------------------------
:: Wait for service stability
:: ------------------------------------------------------------------------------
echo.
echo Waiting for service to become stable (this may take a few minutes)...
aws ecs wait services-stable --cluster !CLUSTER_NAME! --services !SERVICE_NAME! --region !AWS_REGION!
if !ERRORLEVEL! neq 0 (
    echo WARNING: Service did not stabilize within the expected time. Check ECS console.
)

:: ------------------------------------------------------------------------------
:: Verify deployment
:: ------------------------------------------------------------------------------
echo.
echo Verifying deployment...
aws ecs describe-services --cluster !CLUSTER_NAME! --services !SERVICE_NAME! --region !AWS_REGION! --query "services[0].{ServiceName:serviceName,Status:status,DesiredCount:desiredCount,RunningCount:runningCount,PendingCount:pendingCount}" --output table

echo.
echo ==============================================
echo   Deployment Complete!
echo ==============================================
echo   Cluster:      !CLUSTER_NAME!
echo   Service:      !SERVICE_NAME!
echo   Task Def ARN: !TASK_DEF_ARN!
echo   CloudWatch:   !LOG_GROUP!
if "!ALB_DNS!" neq "" (
    echo   ALB DNS:      http://!ALB_DNS!
    echo   Health Check: http://!ALB_DNS!/actuator/health
)
echo.
echo Troubleshooting:
echo   View logs:  aws logs tail !LOG_GROUP! --follow --region !AWS_REGION!
echo   List tasks: aws ecs list-tasks --cluster !CLUSTER_NAME! --service-name !SERVICE_NAME! --region !AWS_REGION!

endlocal

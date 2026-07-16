@echo off
setlocal enabledelayedexpansion

:: =============================================================================
:: deploy-image.bat  —  Deploy ResortsLite to AWS ECS Fargate (Windows)
:: Usage: scripts\deploy-image.bat   (run from repository root)
:: =============================================================================

set "SERVICE_NAME=resortslite-service"
set "TASK_FAMILY=resortslite-task"
set "CONTAINER_NAME=resortslite"
set "APP_PORT=8080"
set "LOG_GROUP=/ecs/resortslite"
set "TASK_DEF_FILE=ecs\task-definition.json"
set "SERVICE_DEF_FILE=ecs\service-definition.json"
set "TMP_TASK_DEF=%TEMP%\task-definition-deploy.json"
set "TMP_SERVICE_DEF=%TEMP%\service-definition-deploy.json"

echo ==============================================
echo   ResortsLite -- AWS ECS Fargate Deployment
echo ==============================================
echo.

:: ---------------------------------------------------------------------------
:: Collect inputs
:: ---------------------------------------------------------------------------
set /p "AWS_REGION=Enter AWS Region (e.g. us-east-1): "
set /p "CLUSTER_INPUT=Enter ECS Cluster name (default: resortslite-cluster): "
if "!CLUSTER_INPUT!"=="" (
    set "CLUSTER_NAME=resortslite-cluster"
) else (
    set "CLUSTER_NAME=!CLUSTER_INPUT!"
)

set /p "IMAGE_URI=Enter ECR Image URI (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest): "
set /p "SUBNET_1=Enter Subnet ID 1 (e.g. subnet-xxxxxxxx): "
set /p "SUBNET_2=Enter Subnet ID 2 (e.g. subnet-yyyyyyyy): "
set /p "SECURITY_GROUP=Enter Security Group ID (e.g. sg-xxxxxxxx): "

:: ---------------------------------------------------------------------------
:: Resolve AWS Account ID
:: ---------------------------------------------------------------------------
echo.
echo Resolving AWS Account ID...
for /f "tokens=*" %%i in ('aws sts get-caller-identity --query Account --output text') do set "ACCOUNT_ID=%%i"
if "!ACCOUNT_ID!"=="" (
    echo ERROR: Could not retrieve AWS Account ID. Check AWS CLI credentials.
    exit /b 1
)
echo Account ID: !ACCOUNT_ID!

:: ---------------------------------------------------------------------------
:: Ensure CloudWatch log group exists
:: ---------------------------------------------------------------------------
echo.
echo Ensuring CloudWatch log group '!LOG_GROUP!' exists...
aws logs create-log-group --log-group-name "!LOG_GROUP!" --region "!AWS_REGION!" >nul 2>&1

:: ---------------------------------------------------------------------------
:: Ensure ECS cluster exists
:: ---------------------------------------------------------------------------
echo.
echo Checking ECS cluster '!CLUSTER_NAME!'...
for /f "tokens=*" %%i in ('aws ecs describe-clusters --clusters !CLUSTER_NAME! --region !AWS_REGION! --query "clusters[0].status" --output text 2^>nul') do set "CLUSTER_STATUS=%%i"
if not "!CLUSTER_STATUS!"=="ACTIVE" (
    echo Creating ECS cluster '!CLUSTER_NAME!'...
    aws ecs create-cluster --cluster-name "!CLUSTER_NAME!" --region "!AWS_REGION!"
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to create ECS cluster.
        exit /b 1
    )
) else (
    echo Cluster '!CLUSTER_NAME!' is ACTIVE.
)

:: ---------------------------------------------------------------------------
:: Load balancer prompt
:: ---------------------------------------------------------------------------
echo.
set /p "NEED_LB=Do you need an Application Load Balancer for this service? (y/n): "

set "TARGET_GROUP_ARN="
set "ALB_DNS="

if /i "!NEED_LB!"=="y" (
    set /p "VPC_ID=Enter VPC ID for the ALB (e.g. vpc-xxxxxxxx): "

    echo.
    echo Creating Application Load Balancer...
    for /f "tokens=*" %%i in ('aws elbv2 create-load-balancer --name resortslite-alb --subnets !SUBNET_1! !SUBNET_2! --security-groups !SECURITY_GROUP! --scheme internet-facing --type application --region !AWS_REGION! --query "LoadBalancers[0].LoadBalancerArn" --output text') do set "ALB_ARN=%%i"
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to create ALB.
        exit /b 1
    )
    echo ALB ARN: !ALB_ARN!

    for /f "tokens=*" %%i in ('aws elbv2 describe-load-balancers --load-balancer-arns !ALB_ARN! --region !AWS_REGION! --query "LoadBalancers[0].DNSName" --output text') do set "ALB_DNS=%%i"

    echo Creating Target Group...
    for /f "tokens=*" %%i in ('aws elbv2 create-target-group --name resortslite-tg --protocol HTTP --port !APP_PORT! --vpc-id !VPC_ID! --target-type ip --health-check-path /actuator/health --health-check-interval-seconds 30 --healthy-threshold-count 2 --unhealthy-threshold-count 3 --region !AWS_REGION! --query "TargetGroups[0].TargetGroupArn" --output text') do set "TARGET_GROUP_ARN=%%i"
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to create Target Group.
        exit /b 1
    )
    echo Target Group ARN: !TARGET_GROUP_ARN!

    echo Creating ALB Listener on port 80...
    aws elbv2 create-listener --load-balancer-arn !ALB_ARN! --protocol HTTP --port 80 --default-actions "Type=forward,TargetGroupArn=!TARGET_GROUP_ARN!" --region !AWS_REGION! >nul
)

:: ---------------------------------------------------------------------------
:: Prepare task definition (copy and replace placeholders)
:: ---------------------------------------------------------------------------
echo.
echo Preparing task definition...
copy /Y "!TASK_DEF_FILE!" "!TMP_TASK_DEF!" >nul

powershell -Command "(Get-Content '!TMP_TASK_DEF!') -replace '{{ACCOUNT_ID}}','!ACCOUNT_ID!' -replace '{{AWS_REGION}}','!AWS_REGION!' -replace '{{IMAGE_URI}}','!IMAGE_URI!' | Set-Content '!TMP_TASK_DEF!'"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to prepare task definition.
    exit /b 1
)

:: ---------------------------------------------------------------------------
:: Register task definition
:: ---------------------------------------------------------------------------
echo Registering task definition '!TASK_FAMILY!'...
for /f "tokens=*" %%i in ('aws ecs register-task-definition --cli-input-json file://!TMP_TASK_DEF! --region !AWS_REGION! --query "taskDefinition.taskDefinitionArn" --output text') do set "TASK_DEF_ARN=%%i"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to register task definition.
    exit /b 1
)
echo Task Definition ARN: !TASK_DEF_ARN!

:: ---------------------------------------------------------------------------
:: Prepare service definition
:: ---------------------------------------------------------------------------
echo.
echo Preparing service definition...
copy /Y "!SERVICE_DEF_FILE!" "!TMP_SERVICE_DEF!" >nul

powershell -Command "(Get-Content '!TMP_SERVICE_DEF!') -replace '{{CLUSTER_NAME}}','!CLUSTER_NAME!' -replace '{{SUBNET_1}}','!SUBNET_1!' -replace '{{SUBNET_2}}','!SUBNET_2!' -replace '{{SECURITY_GROUP}}','!SECURITY_GROUP!' | Set-Content '!TMP_SERVICE_DEF!'"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to prepare service definition.
    exit /b 1
)

:: Add load balancer config if requested
if /i "!NEED_LB!"=="y" (
    powershell -Command "$svc = Get-Content '!TMP_SERVICE_DEF!' | ConvertFrom-Json; $lb = @{targetGroupArn='!TARGET_GROUP_ARN!'; containerName='!CONTAINER_NAME!'; containerPort=[int]!APP_PORT!}; $svc | Add-Member -NotePropertyName 'loadBalancers' -NotePropertyValue @($lb) -Force; $svc | Add-Member -NotePropertyName 'healthCheckGracePeriodSeconds' -NotePropertyValue 300 -Force; $svc | ConvertTo-Json -Depth 10 | Set-Content '!TMP_SERVICE_DEF!'"
)

:: ---------------------------------------------------------------------------
:: Create or update ECS service
:: ---------------------------------------------------------------------------
echo.
for /f "tokens=*" %%i in ('aws ecs describe-services --cluster !CLUSTER_NAME! --services !SERVICE_NAME! --region !AWS_REGION! --query "services[?status==''ACTIVE''].serviceName" --output text 2^>nul') do set "EXISTING_SERVICE=%%i"

if "!EXISTING_SERVICE!"=="" (
    echo Creating ECS service '!SERVICE_NAME!'...
    aws ecs create-service --cli-input-json file://!TMP_SERVICE_DEF! --region !AWS_REGION!
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to create ECS service.
        exit /b 1
    )
) else (
    echo Updating existing ECS service '!SERVICE_NAME!'...
    aws ecs update-service --cluster !CLUSTER_NAME! --service !SERVICE_NAME! --task-definition !TASK_DEF_ARN! --desired-count 2 --region !AWS_REGION!
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to update ECS service.
        exit /b 1
    )
)

:: ---------------------------------------------------------------------------
:: Wait for stability
:: ---------------------------------------------------------------------------
echo.
echo Waiting for service to reach stable state (this may take a few minutes)...
aws ecs wait services-stable --cluster !CLUSTER_NAME! --services !SERVICE_NAME! --region !AWS_REGION!
if !ERRORLEVEL! neq 0 (
    echo WARNING: Service did not reach stable state within timeout. Check ECS console.
)

:: ---------------------------------------------------------------------------
:: Verify and summarise
:: ---------------------------------------------------------------------------
echo.
echo ==============================================
echo   DEPLOYMENT COMPLETE
echo ==============================================
aws ecs describe-services --cluster !CLUSTER_NAME! --services !SERVICE_NAME! --region !AWS_REGION! --query "services[0].{Status:status,Running:runningCount,Desired:desiredCount}" --output table

echo.
echo CloudWatch Logs : !LOG_GROUP!
echo ECS Cluster     : !CLUSTER_NAME!
echo ECS Service     : !SERVICE_NAME!
if not "!ALB_DNS!"=="" (
    echo Load Balancer   : http://!ALB_DNS!
    echo Health Check    : http://!ALB_DNS!/actuator/health
)
echo.
echo Troubleshooting:
echo   aws ecs list-tasks --cluster !CLUSTER_NAME! --service-name !SERVICE_NAME! --region !AWS_REGION!
echo   aws logs tail !LOG_GROUP! --follow --region !AWS_REGION!

endlocal

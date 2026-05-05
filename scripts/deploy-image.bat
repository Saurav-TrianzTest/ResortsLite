@echo off
setlocal enabledelayedexpansion

REM AWS ECS Fargate Deployment Script for ResortsLite Application (Windows)
REM This script deploys the Docker image to AWS ECS Fargate

echo ==========================================
echo ResortsLite - AWS ECS Fargate Deployment
echo ==========================================
echo.

REM Prompt for AWS configuration
set /p AWS_REGION="Enter AWS Region (e.g., us-east-1): "
set /p CLUSTER_NAME="Enter ECS Cluster Name: "
set /p IMAGE_URI="Enter Docker Image URI: "

echo.
echo === Network Configuration ===
set /p VPC_ID="Enter VPC ID: "
set /p SUBNETS_INPUT="Enter Subnet IDs (comma-separated, at least 2): "
set /p SECURITY_GROUP="Enter Security Group ID: "

REM Parse subnets
for /f "tokens=1,2 delims=," %%a in ("!SUBNETS_INPUT!") do (
    set SUBNET_1=%%a
    set SUBNET_2=%%b
)

echo.
echo === Redis Configuration ===
set /p REDIS_HOST="Enter Redis Host (e.g., redis.example.com): "
set /p REDIS_PORT="Enter Redis Port (default: 6379): "
if "!REDIS_PORT!"=="" set REDIS_PORT=6379
set /p REDIS_PASSWORD="Enter Redis Password (leave empty if none): "

echo.
echo === S3 Configuration ===
set /p S3_BUCKET_NAME="Enter S3 Bucket Name: "

echo.
echo Getting AWS Account ID...
for /f "delims=" %%i in ('aws sts get-caller-identity --query Account --output text') do set ACCOUNT_ID=%%i
echo Account ID: !ACCOUNT_ID!

echo.
echo Checking if ECS cluster exists...
aws ecs describe-clusters --clusters "!CLUSTER_NAME!" --region "!AWS_REGION!" >nul 2>&1
if !ERRORLEVEL! neq 0 (
    echo Creating ECS cluster: !CLUSTER_NAME!
    aws ecs create-cluster --cluster-name "!CLUSTER_NAME!" --region "!AWS_REGION!"
)

echo.
echo Creating CloudWatch log group...
aws logs create-log-group --log-group-name "/ecs/resortslite" --region "!AWS_REGION!" 2>nul

echo.
set /p NEED_LB="Do you need a load balancer for this service? (y/n): "

if /i "!NEED_LB!"=="y" (
    echo.
    echo Creating Application Load Balancer and Target Group...
    
    set ALB_NAME=resortslite-alb
    echo Creating ALB: !ALB_NAME!
    
    REM Create ALB
    for /f "delims=" %%i in ('aws elbv2 create-load-balancer --name "!ALB_NAME!" --subnets !SUBNET_1! !SUBNET_2! --security-groups "!SECURITY_GROUP!" --scheme internet-facing --type application --ip-address-type ipv4 --region "!AWS_REGION!" --query "LoadBalancers[0].LoadBalancerArn" --output text 2^>nul') do set ALB_ARN=%%i
    
    if "!ALB_ARN!"=="" (
        for /f "delims=" %%i in ('aws elbv2 describe-load-balancers --names "!ALB_NAME!" --region "!AWS_REGION!" --query "LoadBalancers[0].LoadBalancerArn" --output text') do set ALB_ARN=%%i
    )
    
    echo ALB ARN: !ALB_ARN!
    
    REM Get ALB DNS name
    for /f "delims=" %%i in ('aws elbv2 describe-load-balancers --load-balancer-arns "!ALB_ARN!" --region "!AWS_REGION!" --query "LoadBalancers[0].DNSName" --output text') do set ALB_DNS=%%i
    
    REM Create Target Group
    set TG_NAME=resortslite-tg
    echo Creating Target Group: !TG_NAME!
    
    for /f "delims=" %%i in ('aws elbv2 create-target-group --name "!TG_NAME!" --protocol HTTP --port 8080 --vpc-id "!VPC_ID!" --target-type ip --health-check-enabled --health-check-protocol HTTP --health-check-path "/actuator/health" --health-check-interval-seconds 30 --health-check-timeout-seconds 5 --healthy-threshold-count 2 --unhealthy-threshold-count 3 --region "!AWS_REGION!" --query "TargetGroups[0].TargetGroupArn" --output text 2^>nul') do set TARGET_GROUP_ARN=%%i
    
    if "!TARGET_GROUP_ARN!"=="" (
        for /f "delims=" %%i in ('aws elbv2 describe-target-groups --names "!TG_NAME!" --region "!AWS_REGION!" --query "TargetGroups[0].TargetGroupArn" --output text') do set TARGET_GROUP_ARN=%%i
    )
    
    echo Target Group ARN: !TARGET_GROUP_ARN!
    
    REM Create Listener
    echo Creating ALB Listener...
    aws elbv2 create-listener --load-balancer-arn "!ALB_ARN!" --protocol HTTP --port 80 --default-actions Type=forward,TargetGroupArn="!TARGET_GROUP_ARN!" --region "!AWS_REGION!" 2>nul
    
    echo Load Balancer DNS: !ALB_DNS!
) else (
    echo Skipping load balancer creation
    set TARGET_GROUP_ARN=
)

echo.
echo Preparing task definition...
copy ecs\task-definition.json ecs\task-definition-temp.json >nul

REM Replace placeholders in task definition using PowerShell
powershell -Command "(Get-Content ecs\task-definition-temp.json) -replace '{{IMAGE_URI}}', '!IMAGE_URI!' | Set-Content ecs\task-definition-temp.json"
powershell -Command "(Get-Content ecs\task-definition-temp.json) -replace '{{AWS_REGION}}', '!AWS_REGION!' | Set-Content ecs\task-definition-temp.json"
powershell -Command "(Get-Content ecs\task-definition-temp.json) -replace '{{ACCOUNT_ID}}', '!ACCOUNT_ID!' | Set-Content ecs\task-definition-temp.json"
powershell -Command "(Get-Content ecs\task-definition-temp.json) -replace '{{REDIS_HOST}}', '!REDIS_HOST!' | Set-Content ecs\task-definition-temp.json"
powershell -Command "(Get-Content ecs\task-definition-temp.json) -replace '{{REDIS_PORT}}', '!REDIS_PORT!' | Set-Content ecs\task-definition-temp.json"
powershell -Command "(Get-Content ecs\task-definition-temp.json) -replace '{{REDIS_PASSWORD}}', '!REDIS_PASSWORD!' | Set-Content ecs\task-definition-temp.json"
powershell -Command "(Get-Content ecs\task-definition-temp.json) -replace '{{S3_BUCKET_NAME}}', '!S3_BUCKET_NAME!' | Set-Content ecs\task-definition-temp.json"

echo Registering task definition...
for /f "delims=" %%i in ('aws ecs register-task-definition --cli-input-json file://ecs/task-definition-temp.json --region "!AWS_REGION!" --query "taskDefinition.taskDefinitionArn" --output text') do set TASK_DEF_ARN=%%i

echo Task Definition ARN: !TASK_DEF_ARN!

REM Clean up temp file
del ecs\task-definition-temp.json

echo.
echo Preparing service definition...
copy ecs\service-definition.json ecs\service-definition-temp.json >nul

REM Replace placeholders in service definition
powershell -Command "(Get-Content ecs\service-definition-temp.json) -replace '{{CLUSTER_NAME}}', '!CLUSTER_NAME!' | Set-Content ecs\service-definition-temp.json"
powershell -Command "(Get-Content ecs\service-definition-temp.json) -replace '{{SUBNET_1}}', '!SUBNET_1!' | Set-Content ecs\service-definition-temp.json"
powershell -Command "(Get-Content ecs\service-definition-temp.json) -replace '{{SUBNET_2}}', '!SUBNET_2!' | Set-Content ecs\service-definition-temp.json"
powershell -Command "(Get-Content ecs\service-definition-temp.json) -replace '{{SECURITY_GROUP}}', '!SECURITY_GROUP!' | Set-Content ecs\service-definition-temp.json"

if /i "!NEED_LB!"=="y" (
    powershell -Command "(Get-Content ecs\service-definition-temp.json) -replace '{{TARGET_GROUP_ARN}}', '!TARGET_GROUP_ARN!' | Set-Content ecs\service-definition-temp.json"
) else (
    REM Remove loadBalancers section if no LB needed
    powershell -Command "$content = Get-Content ecs\service-definition-temp.json -Raw; $content = $content -replace '(?s)\"loadBalancers\":\s*\[.*?\],\s*', ''; $content = $content -replace '\"healthCheckGracePeriodSeconds\":\s*\d+,\s*', ''; $content | Set-Content ecs\service-definition-temp.json"
)

echo.
echo Checking if service exists...
for /f "delims=" %%i in ('aws ecs describe-services --cluster "!CLUSTER_NAME!" --services "resortslite-service" --region "!AWS_REGION!" --query "services[0].serviceName" --output text 2^>nul') do set SERVICE_EXISTS=%%i

if "!SERVICE_EXISTS!"=="None" (
    echo Creating new ECS service...
    aws ecs create-service --cli-input-json file://ecs/service-definition-temp.json --region "!AWS_REGION!"
) else (
    echo Updating existing ECS service...
    aws ecs update-service --cluster "!CLUSTER_NAME!" --service "resortslite-service" --task-definition "!TASK_DEF_ARN!" --desired-count 2 --region "!AWS_REGION!"
)

REM Clean up temp file
del ecs\service-definition-temp.json

echo.
echo Waiting for service to become stable...
aws ecs wait services-stable --cluster "!CLUSTER_NAME!" --services "resortslite-service" --region "!AWS_REGION!"

echo.
echo ==========================================
echo Deployment Complete!
echo ==========================================
echo.
echo Service Details:
aws ecs describe-services --cluster "!CLUSTER_NAME!" --services "resortslite-service" --region "!AWS_REGION!" --query "services[0].[serviceName,status,runningCount,desiredCount]" --output table

if /i "!NEED_LB!"=="y" (
    echo.
    echo Application URL: http://!ALB_DNS!
)

echo.
echo CloudWatch Logs: /ecs/resortslite
echo Region: !AWS_REGION!
echo.
echo To view logs:
echo aws logs tail /ecs/resortslite --follow --region !AWS_REGION!
echo.

endlocal

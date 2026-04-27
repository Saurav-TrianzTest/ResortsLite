@echo off
setlocal enabledelayedexpansion

REM ============================================
REM Deploy to AWS ECS Fargate Script (Windows)
REM For ResortsLite Spring Boot Application
REM ============================================

echo ==========================================
echo AWS ECS Fargate Deployment Script
echo ==========================================
echo.

REM Get AWS Account ID
echo Retrieving AWS Account ID...
for /f "delims=" %%i in ('aws sts get-caller-identity --query Account --output text') do set ACCOUNT_ID=%%i
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to retrieve AWS Account ID. Ensure AWS CLI is configured.
    exit /b 1
)
echo AWS Account ID: !ACCOUNT_ID!
echo.

REM Prompt for AWS Region
set /p AWS_REGION="Enter AWS Region (e.g., us-east-1): "
echo.

REM Prompt for ECS Cluster Name
set /p CLUSTER_NAME="Enter ECS Cluster Name: "
echo.

REM Check if cluster exists, create if not
echo Checking if ECS cluster exists...
aws ecs describe-clusters --clusters "!CLUSTER_NAME!" --region "!AWS_REGION!" >nul 2>&1
if !ERRORLEVEL! neq 0 (
    echo Cluster not found. Creating ECS cluster: !CLUSTER_NAME!
    aws ecs create-cluster --cluster-name "!CLUSTER_NAME!" --region "!AWS_REGION!"
    echo ECS cluster created successfully
)
echo.

REM Prompt for VPC and Network Configuration
echo === Network Configuration ===
set /p VPC_ID="Enter VPC ID: "
set /p SUBNETS_INPUT="Enter Subnet IDs (comma-separated, at least 2): "
set /p SECURITY_GROUP="Enter Security Group ID: "

REM Parse subnets
for /f "tokens=1,2 delims=," %%a in ("!SUBNETS_INPUT!") do (
    set SUBNET_1=%%a
    set SUBNET_2=%%b
)
set SUBNET_1=!SUBNET_1: =!
set SUBNET_2=!SUBNET_2: =!

echo.
echo VPC ID: !VPC_ID!
echo Subnet 1: !SUBNET_1!
echo Subnet 2: !SUBNET_2!
echo Security Group: !SECURITY_GROUP!
echo.

REM Prompt for Docker Image URI
set /p IMAGE_URI="Enter Docker Image URI (e.g., 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest): "
echo.

REM Prompt for Redis Configuration
echo === Redis Configuration ===
set /p REDIS_HOST="Enter Redis Host (e.g., redis.example.com): "
set /p REDIS_PASSWORD="Enter Redis Password (leave empty if none): "
echo.

REM Load Balancer Configuration
set /p NEED_LB="Do you need a load balancer for this service? (y/n): "

if /i "!NEED_LB!"=="y" (
    echo.
    echo Creating Application Load Balancer and Target Group...
    
    REM Create ALB
    set ALB_NAME=resortslite-alb
    echo Creating ALB: !ALB_NAME!
    for /f "delims=" %%i in ('aws elbv2 create-load-balancer --name "!ALB_NAME!" --subnets "!SUBNET_1!" "!SUBNET_2!" --security-groups "!SECURITY_GROUP!" --scheme internet-facing --type application --ip-address-type ipv4 --region "!AWS_REGION!" --query "LoadBalancers[0].LoadBalancerArn" --output text') do set ALB_ARN=%%i
    
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to create ALB
        exit /b 1
    )
    
    echo ALB created: !ALB_ARN!
    
    REM Get ALB DNS name
    for /f "delims=" %%i in ('aws elbv2 describe-load-balancers --load-balancer-arns "!ALB_ARN!" --region "!AWS_REGION!" --query "LoadBalancers[0].DNSName" --output text') do set ALB_DNS=%%i
    
    REM Create Target Group with target-type ip
    set TG_NAME=resortslite-tg
    echo Creating Target Group: !TG_NAME!
    for /f "delims=" %%i in ('aws elbv2 create-target-group --name "!TG_NAME!" --protocol HTTP --port 8080 --vpc-id "!VPC_ID!" --target-type ip --health-check-enabled --health-check-protocol HTTP --health-check-path "/actuator/health" --health-check-interval-seconds 30 --health-check-timeout-seconds 5 --healthy-threshold-count 2 --unhealthy-threshold-count 3 --region "!AWS_REGION!" --query "TargetGroups[0].TargetGroupArn" --output text') do set TARGET_GROUP_ARN=%%i
    
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to create Target Group
        exit /b 1
    )
    
    echo Target Group created: !TARGET_GROUP_ARN!
    
    REM Create Listener
    echo Creating ALB Listener...
    aws elbv2 create-listener --load-balancer-arn "!ALB_ARN!" --protocol HTTP --port 80 --default-actions Type=forward,TargetGroupArn="!TARGET_GROUP_ARN!" --region "!AWS_REGION!" >nul
    
    echo ALB Listener created
    echo.
) else (
    echo Skipping load balancer creation
    set TARGET_GROUP_ARN=
    echo.
)

REM Create CloudWatch Log Group
echo Creating CloudWatch Log Group...
aws logs create-log-group --log-group-name "/ecs/resortslite" --region "!AWS_REGION!" 2>nul
if !ERRORLEVEL! neq 0 (
    echo Log group already exists
)
echo.

REM Replace placeholders in task definition
echo Preparing ECS Task Definition...
copy ecs\task-definition.json ecs\task-definition-temp.json >nul

powershell -Command "(Get-Content ecs\task-definition-temp.json) -replace '{{IMAGE_URI}}', '!IMAGE_URI!' | Set-Content ecs\task-definition-temp.json"
powershell -Command "(Get-Content ecs\task-definition-temp.json) -replace '{{AWS_REGION}}', '!AWS_REGION!' | Set-Content ecs\task-definition-temp.json"
powershell -Command "(Get-Content ecs\task-definition-temp.json) -replace '{{ACCOUNT_ID}}', '!ACCOUNT_ID!' | Set-Content ecs\task-definition-temp.json"
powershell -Command "(Get-Content ecs\task-definition-temp.json) -replace '{{REDIS_HOST}}', '!REDIS_HOST!' | Set-Content ecs\task-definition-temp.json"
powershell -Command "(Get-Content ecs\task-definition-temp.json) -replace '{{REDIS_PASSWORD}}', '!REDIS_PASSWORD!' | Set-Content ecs\task-definition-temp.json"

echo Task definition prepared
echo.

REM Register Task Definition
echo Registering ECS Task Definition...
for /f "delims=" %%i in ('aws ecs register-task-definition --cli-input-json file://ecs/task-definition-temp.json --region "!AWS_REGION!" --query "taskDefinition.taskDefinitionArn" --output text') do set TASK_DEF_ARN=%%i

if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to register task definition
    del ecs\task-definition-temp.json
    exit /b 1
)

echo Task definition registered: !TASK_DEF_ARN!
echo.

REM Clean up temp file
del ecs\task-definition-temp.json

REM Prepare service definition
echo Preparing ECS Service Definition...
copy ecs\service-definition.json ecs\service-definition-temp.json >nul

powershell -Command "(Get-Content ecs\service-definition-temp.json) -replace '{{CLUSTER_NAME}}', '!CLUSTER_NAME!' | Set-Content ecs\service-definition-temp.json"
powershell -Command "(Get-Content ecs\service-definition-temp.json) -replace '{{SUBNET_1}}', '!SUBNET_1!' | Set-Content ecs\service-definition-temp.json"
powershell -Command "(Get-Content ecs\service-definition-temp.json) -replace '{{SUBNET_2}}', '!SUBNET_2!' | Set-Content ecs\service-definition-temp.json"
powershell -Command "(Get-Content ecs\service-definition-temp.json) -replace '{{SECURITY_GROUP}}', '!SECURITY_GROUP!' | Set-Content ecs\service-definition-temp.json"

if "!TARGET_GROUP_ARN!"=="" (
    REM Remove loadBalancers section if no LB
    powershell -Command "$json = Get-Content ecs\service-definition-temp.json | ConvertFrom-Json; $json.PSObject.Properties.Remove('loadBalancers'); $json.PSObject.Properties.Remove('healthCheckGracePeriodSeconds'); $json | ConvertTo-Json -Depth 10 | Set-Content ecs\service-definition-temp.json"
) else (
    powershell -Command "(Get-Content ecs\service-definition-temp.json) -replace '{{TARGET_GROUP_ARN}}', '!TARGET_GROUP_ARN!' | Set-Content ecs\service-definition-temp.json"
)

echo Service definition prepared
echo.

REM Check if service exists
set SERVICE_NAME=resortslite-service
echo Checking if ECS service exists...
for /f "delims=" %%i in ('aws ecs describe-services --cluster "!CLUSTER_NAME!" --services "!SERVICE_NAME!" --region "!AWS_REGION!" --query "services[?status==`ACTIVE`].serviceName" --output text') do set EXISTING_SERVICE=%%i

if "!EXISTING_SERVICE!"=="" (
    echo Service does not exist. Creating new service...
    aws ecs create-service --cli-input-json file://ecs/service-definition-temp.json --region "!AWS_REGION!"
    
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to create service
        del ecs\service-definition-temp.json
        exit /b 1
    )
    
    echo ECS service created successfully
) else (
    echo Service exists. Updating service...
    aws ecs update-service --cluster "!CLUSTER_NAME!" --service "!SERVICE_NAME!" --task-definition "!TASK_DEF_ARN!" --desired-count 2 --region "!AWS_REGION!" --force-new-deployment
    
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to update service
        del ecs\service-definition-temp.json
        exit /b 1
    )
    
    echo ECS service updated successfully
)

echo.

REM Clean up temp file
del ecs\service-definition-temp.json

REM Wait for service stability
echo Waiting for service to become stable (this may take a few minutes)...
aws ecs wait services-stable --cluster "!CLUSTER_NAME!" --services "!SERVICE_NAME!" --region "!AWS_REGION!"

if !ERRORLEVEL! neq 0 (
    echo WARNING: Service did not stabilize within timeout period
    echo Check ECS console for service status
) else (
    echo Service is stable
)

echo.

REM Verify deployment
echo ==========================================
echo Deployment Summary
echo ==========================================
aws ecs describe-services --cluster "!CLUSTER_NAME!" --services "!SERVICE_NAME!" --region "!AWS_REGION!" --query "services[0].[serviceName,status,runningCount,desiredCount]" --output table

echo.
echo CloudWatch Logs: /ecs/resortslite
echo Region: !AWS_REGION!

if not "!ALB_DNS!"=="" (
    echo.
    echo Application Load Balancer DNS: !ALB_DNS!
    echo Access your application at: http://!ALB_DNS!
)

echo.
echo ==========================================
echo Deployment Complete!
echo ==========================================
echo.
echo Troubleshooting:
echo - View logs: aws logs tail /ecs/resortslite --follow --region !AWS_REGION!
echo - Check tasks: aws ecs list-tasks --cluster !CLUSTER_NAME! --region !AWS_REGION!
echo - Describe service: aws ecs describe-services --cluster !CLUSTER_NAME! --services !SERVICE_NAME! --region !AWS_REGION!
echo ==========================================

endlocal

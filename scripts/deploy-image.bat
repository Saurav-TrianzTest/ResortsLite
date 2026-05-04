@echo off
setlocal enabledelayedexpansion

REM Deploy ResortsLite to AWS EKS (Windows)
REM This script configures kubectl and deploys the application to EKS

echo ==========================================
echo   ResortsLite - AWS EKS Deployment
echo ==========================================
echo.

REM Prompt for AWS EKS configuration
set /p AWS_REGION="Enter AWS Region (e.g., us-east-1): "
set /p CLUSTER_NAME="Enter EKS Cluster Name: "
set /p IMAGE_URI="Enter Docker Image URI (with tag): "

echo.
echo Configuration:
echo   AWS Region: !AWS_REGION!
echo   EKS Cluster: !CLUSTER_NAME!
echo   Image URI: !IMAGE_URI!
echo.

REM Prompt for environment-specific configuration
echo ==========================================
echo   Environment Configuration
echo ==========================================
echo Enter values for environment variables (press Enter to use defaults):
echo.

set /p DB_URL="Database URL (default: jdbc:h2:mem:resortdb): "
if "!DB_URL!"=="" set DB_URL=jdbc:h2:mem:resortdb;DB_CLOSE_DELAY=-1

set /p DB_USERNAME="Database Username (default: sa): "
if "!DB_USERNAME!"=="" set DB_USERNAME=sa

set /p DB_PASSWORD="Database Password (default: empty): "
if "!DB_PASSWORD!"=="" set DB_PASSWORD=

set /p REDIS_HOST="Redis Host (default: redis.internal): "
if "!REDIS_HOST!"=="" set REDIS_HOST=redis.internal

set /p REDIS_PORT="Redis Port (default: 6379): "
if "!REDIS_PORT!"=="" set REDIS_PORT=6379

set /p REDIS_PASSWORD="Redis Password (default: empty): "
if "!REDIS_PASSWORD!"=="" set REDIS_PASSWORD=

set /p S3_BUCKET_NAME="S3 Bucket Name (default: resortslite-reports): "
if "!S3_BUCKET_NAME!"=="" set S3_BUCKET_NAME=resortslite-reports

set /p S3_AWS_REGION="AWS Region for S3 (default: !AWS_REGION!): "
if "!S3_AWS_REGION!"=="" set S3_AWS_REGION=!AWS_REGION!

set /p PAYMENT_ENDPOINT="Payment Service Endpoint (default: http://payment-svc.internal:9090/charge): "
if "!PAYMENT_ENDPOINT!"=="" set PAYMENT_ENDPOINT=http://payment-svc.internal:9090/charge

set /p INVENTORY_ENDPOINT="Inventory Service Endpoint (default: http://inventory-svc.internal:8081/rooms): "
if "!INVENTORY_ENDPOINT!"=="" set INVENTORY_ENDPOINT=http://inventory-svc.internal:8081/rooms

set /p NOTIFICATION_ENDPOINT="Notification Service Endpoint (default: http://notify.internal:7070/send): "
if "!NOTIFICATION_ENDPOINT!"=="" set NOTIFICATION_ENDPOINT=http://notify.internal:7070/send

echo.
echo ==========================================
echo   Configuring kubectl for EKS
echo ==========================================

REM Configure kubectl to use EKS cluster
aws eks update-kubeconfig --region "!AWS_REGION!" --name "!CLUSTER_NAME!"

if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to configure kubectl for EKS cluster
    exit /b 1
)

echo kubectl configured successfully
echo.

REM Verify cluster connectivity
echo Verifying cluster connectivity...
kubectl cluster-info
if !ERRORLEVEL! neq 0 (
    echo ERROR: Cannot connect to EKS cluster
    exit /b 1
)
echo.

REM Update Kubernetes manifests with actual values
echo ==========================================
echo   Updating Kubernetes Manifests
echo ==========================================

REM Create temporary directory for processed manifests
set TEMP_DIR=%TEMP%\resortslite-deploy-%RANDOM%
mkdir "!TEMP_DIR!"
xcopy /E /I /Q kubernetes "!TEMP_DIR!" >nul

REM Replace placeholders in deployment.yaml using PowerShell
powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{IMAGE_URI}}', '!IMAGE_URI!' | Set-Content '!TEMP_DIR!\deployment.yaml'"
powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{DB_URL}}', '!DB_URL!' | Set-Content '!TEMP_DIR!\deployment.yaml'"
powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{DB_USERNAME}}', '!DB_USERNAME!' | Set-Content '!TEMP_DIR!\deployment.yaml'"
powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{DB_PASSWORD}}', '!DB_PASSWORD!' | Set-Content '!TEMP_DIR!\deployment.yaml'"
powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{REDIS_HOST}}', '!REDIS_HOST!' | Set-Content '!TEMP_DIR!\deployment.yaml'"
powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{REDIS_PORT}}', '!REDIS_PORT!' | Set-Content '!TEMP_DIR!\deployment.yaml'"
powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{REDIS_PASSWORD}}', '!REDIS_PASSWORD!' | Set-Content '!TEMP_DIR!\deployment.yaml'"
powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{S3_BUCKET_NAME}}', '!S3_BUCKET_NAME!' | Set-Content '!TEMP_DIR!\deployment.yaml'"
powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{AWS_REGION}}', '!S3_AWS_REGION!' | Set-Content '!TEMP_DIR!\deployment.yaml'"
powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{PAYMENT_ENDPOINT}}', '!PAYMENT_ENDPOINT!' | Set-Content '!TEMP_DIR!\deployment.yaml'"
powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{INVENTORY_ENDPOINT}}', '!INVENTORY_ENDPOINT!' | Set-Content '!TEMP_DIR!\deployment.yaml'"
powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{NOTIFICATION_ENDPOINT}}', '!NOTIFICATION_ENDPOINT!' | Set-Content '!TEMP_DIR!\deployment.yaml'"

echo Manifests updated successfully
echo.

REM Apply Kubernetes manifests
echo ==========================================
echo   Deploying to EKS
echo ==========================================

echo Creating namespace...
kubectl apply -f "!TEMP_DIR!\namespace.yaml"
echo.

echo Deploying application...
kubectl apply -f "!TEMP_DIR!\deployment.yaml"
echo.

echo Creating service...
kubectl apply -f "!TEMP_DIR!\service.yaml"
echo.

echo Creating ingress...
kubectl apply -f "!TEMP_DIR!\ingress.yaml"
echo.

REM Wait for deployment to complete
echo ==========================================
echo   Waiting for Deployment Rollout
echo ==========================================
kubectl rollout status deployment/resortslite -n resortslite --timeout=5m

if !ERRORLEVEL! neq 0 (
    echo WARNING: Deployment rollout did not complete within timeout
    echo Check deployment status with: kubectl get pods -n resortslite
)
echo.

REM Verify deployment
echo ==========================================
echo   Deployment Status
echo ==========================================
kubectl get pods,svc,ingress -n resortslite
echo.

REM Get ingress URL
echo ==========================================
echo   Application Access Information
echo ==========================================
for /f "delims=" %%i in ('kubectl get ingress resortslite-ingress -n resortslite -o jsonpath^="{.status.loadBalancer.ingress[0].hostname}" 2^>nul') do set INGRESS_ADDRESS=%%i
if "!INGRESS_ADDRESS!"=="" set INGRESS_ADDRESS=Pending...

echo Ingress Address: !INGRESS_ADDRESS!
echo.
echo Application URL: http://!INGRESS_ADDRESS!
echo Health Check: http://!INGRESS_ADDRESS!/actuator/health
echo.

if "!INGRESS_ADDRESS!"=="Pending..." (
    echo NOTE: Ingress is still being provisioned. Run the following command to check status:
    echo   kubectl get ingress resortslite-ingress -n resortslite
)

echo.
echo ==========================================
echo   Deployment Completed Successfully!
echo ==========================================
echo.
echo Useful commands:
echo   View pods:        kubectl get pods -n resortslite
echo   View logs:        kubectl logs -f deployment/resortslite -n resortslite
echo   Describe pod:     kubectl describe pod ^<pod-name^> -n resortslite
echo   Scale replicas:   kubectl scale deployment/resortslite --replicas=3 -n resortslite
echo   Delete deployment: kubectl delete namespace resortslite
echo.

REM Cleanup temporary directory
rmdir /S /Q "!TEMP_DIR!"

endlocal

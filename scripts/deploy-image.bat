@echo off
setlocal enabledelayedexpansion

:: ============================================================
:: deploy-image.bat — Deploy BookingComp to AWS EKS (Windows)
:: ============================================================

set "APP_NAME=bookingcomp"
set "NAMESPACE=bookingcomp"
set "SCRIPT_DIR=%~dp0"
set "PROJECT_ROOT=%SCRIPT_DIR%.."

echo ============================================
echo   BookingComp - AWS EKS Deployment
echo ============================================

:: ---- Collect deployment inputs ----
set /p AWS_REGION="Enter AWS Region (e.g. us-east-1): "
if "!AWS_REGION!"=="" (
    echo ERROR: AWS Region is required.
    exit /b 1
)

set /p CLUSTER_NAME="Enter EKS Cluster Name: "
if "!CLUSTER_NAME!"=="" (
    echo ERROR: EKS Cluster Name is required.
    exit /b 1
)

set /p IMAGE_URI="Enter full Docker image URI: "
if "!IMAGE_URI!"=="" (
    echo ERROR: Docker image URI is required.
    exit /b 1
)

echo.
echo --- Optional: Environment Variable Configuration ---
echo Press Enter to skip any variable and use the default.

set /p SPRING_REDIS_HOST="Enter SPRING_REDIS_HOST [localhost]: "
if "!SPRING_REDIS_HOST!"=="" set "SPRING_REDIS_HOST=localhost"

set /p SPRING_REDIS_PORT="Enter SPRING_REDIS_PORT [6379]: "
if "!SPRING_REDIS_PORT!"=="" set "SPRING_REDIS_PORT=6379"

set /p PAYMENT_API_URL="Enter PAYMENT_API_URL [http://payment-service/payments/charge]: "
if "!PAYMENT_API_URL!"=="" set "PAYMENT_API_URL=http://payment-service/payments/charge"

set /p REPORT_SERVICE_URL="Enter REPORT_SERVICE_URL [http://report-service/api/reports]: "
if "!REPORT_SERVICE_URL!"=="" set "REPORT_SERVICE_URL=http://report-service/api/reports"

set /p S3_REPORTS_BUCKET="Enter S3_REPORTS_BUCKET [resorts-reports-bucket]: "
if "!S3_REPORTS_BUCKET!"=="" set "S3_REPORTS_BUCKET=resorts-reports-bucket"

set /p S3_BACKUP_BUCKET="Enter S3_BACKUP_BUCKET [resorts-backup-bucket]: "
if "!S3_BACKUP_BUCKET!"=="" set "S3_BACKUP_BUCKET=resorts-backup-bucket"

:: ---- Configure kubectl for EKS ----
echo.
echo Configuring kubectl for EKS cluster: !CLUSTER_NAME! in !AWS_REGION! ...
aws eks update-kubeconfig --region !AWS_REGION! --name !CLUSTER_NAME!
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to configure kubectl.
    exit /b 1
)

echo Verifying cluster connectivity...
kubectl cluster-info
if !ERRORLEVEL! neq 0 (
    echo ERROR: Cannot connect to EKS cluster.
    exit /b 1
)

:: ---- Substitute placeholders using PowerShell ----
echo.
echo Updating Kubernetes manifests with deployment values...

set "DEPLOY_YAML=%PROJECT_ROOT%\kubernetes\deployment.yaml"
set "DEPLOY_YAML_TMP=%PROJECT_ROOT%\kubernetes\deployment.yaml.tmp"

copy "!DEPLOY_YAML!" "!DEPLOY_YAML_TMP!" >nul

powershell -Command "(Get-Content '!DEPLOY_YAML_TMP!') -replace '{{IMAGE_URI}}', '!IMAGE_URI!' | Set-Content '!DEPLOY_YAML_TMP!'"
powershell -Command "(Get-Content '!DEPLOY_YAML_TMP!') -replace '{{SPRING_REDIS_HOST}}', '!SPRING_REDIS_HOST!' | Set-Content '!DEPLOY_YAML_TMP!'"
powershell -Command "(Get-Content '!DEPLOY_YAML_TMP!') -replace '{{SPRING_REDIS_PORT}}', '!SPRING_REDIS_PORT!' | Set-Content '!DEPLOY_YAML_TMP!'"
powershell -Command "(Get-Content '!DEPLOY_YAML_TMP!') -replace '{{PAYMENT_API_URL}}', '!PAYMENT_API_URL!' | Set-Content '!DEPLOY_YAML_TMP!'"
powershell -Command "(Get-Content '!DEPLOY_YAML_TMP!') -replace '{{REPORT_SERVICE_URL}}', '!REPORT_SERVICE_URL!' | Set-Content '!DEPLOY_YAML_TMP!'"
powershell -Command "(Get-Content '!DEPLOY_YAML_TMP!') -replace '{{S3_REPORTS_BUCKET}}', '!S3_REPORTS_BUCKET!' | Set-Content '!DEPLOY_YAML_TMP!'"
powershell -Command "(Get-Content '!DEPLOY_YAML_TMP!') -replace '{{S3_BACKUP_BUCKET}}', '!S3_BACKUP_BUCKET!' | Set-Content '!DEPLOY_YAML_TMP!'"

:: ---- Apply manifests ----
echo.
echo Applying Kubernetes manifests...

echo   [1/4] Applying namespace...
kubectl apply -f "%PROJECT_ROOT%\kubernetes\namespace.yaml"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to apply namespace.
    exit /b 1
)

echo   [2/4] Applying deployment...
kubectl apply -f "!DEPLOY_YAML_TMP!"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to apply deployment.
    del "!DEPLOY_YAML_TMP!" >nul 2>&1
    exit /b 1
)

echo   [3/4] Applying service...
kubectl apply -f "%PROJECT_ROOT%\kubernetes\service.yaml"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to apply service.
    exit /b 1
)

echo   [4/4] Applying ingress...
kubectl apply -f "%PROJECT_ROOT%\kubernetes\ingress.yaml"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to apply ingress.
    exit /b 1
)

:: Clean up temp file
del "!DEPLOY_YAML_TMP!" >nul 2>&1

:: ---- Wait for rollout ----
echo.
echo Waiting for deployment rollout...
kubectl rollout status deployment/!APP_NAME! -n !NAMESPACE! --timeout=300s
if !ERRORLEVEL! neq 0 (
    echo ERROR: Deployment rollout failed.
    echo Rollback: kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
    exit /b 1
)

:: ---- Verify resources ----
echo.
echo Verifying deployed resources...
kubectl get pods,svc,ingress -n !NAMESPACE!

echo.
echo ============================================
echo   Deployment Complete!
echo   Namespace : !NAMESPACE!
echo   Image     : !IMAGE_URI!
echo   Rollback  : kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
echo ============================================

endlocal

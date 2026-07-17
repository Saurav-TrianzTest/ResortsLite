@echo off
setlocal enabledelayedexpansion

set APP_NAME=resortslite
set NAMESPACE=resortslite

echo ============================================================
echo   ResortsLite - Deploy to Azure AKS
echo ============================================================
echo.

REM ---- Azure / AKS credentials ----
set /p RESOURCE_GROUP="Enter Azure Resource Group name: "
if "!RESOURCE_GROUP!"=="" (
    echo ERROR: Resource group cannot be empty.
    exit /b 1
)

set /p CLUSTER_NAME="Enter AKS Cluster name: "
if "!CLUSTER_NAME!"=="" (
    echo ERROR: AKS cluster name cannot be empty.
    exit /b 1
)

REM ---- Docker image URI ----
set /p IMAGE_URI="Enter full Docker image URI (e.g. myregistry.azurecr.io/resortslite:latest): "
if "!IMAGE_URI!"=="" (
    echo ERROR: Image URI cannot be empty.
    exit /b 1
)

echo.
echo ---- Optional: Environment Variable Configuration ----
echo Press Enter to skip any variable and keep the placeholder.
echo.

set /p REDIS_HOST_VAL="Enter REDIS_HOST (e.g. my-redis.redis.cache.windows.net): "
set /p REDIS_PORT_VAL="Enter REDIS_PORT (default 6379): "
set /p REDIS_PASSWORD_VAL="Enter REDIS_PASSWORD (leave blank if none): "
set /p PAYMENT_API_URL_VAL="Enter PAYMENT_API_URL (e.g. http://payment-service/payments/charge): "

echo.
echo ============================================================
echo   Configuring kubectl for AKS cluster: !CLUSTER_NAME!
echo ============================================================
az aks get-credentials --resource-group !RESOURCE_GROUP! --name !CLUSTER_NAME! --overwrite-existing
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to get AKS credentials.
    exit /b 1
)

echo.
echo Verifying cluster connectivity ...
kubectl cluster-info
if !ERRORLEVEL! neq 0 (
    echo ERROR: Cannot connect to AKS cluster.
    exit /b 1
)

echo.
echo ============================================================
echo   Updating Kubernetes manifests ...
echo ============================================================

REM Create temp directory for modified manifests
set TEMP_DIR=%TEMP%\resortslite-deploy-%RANDOM%
mkdir "!TEMP_DIR!"

REM Copy manifests to temp directory
copy "kubernetes\namespace.yaml" "!TEMP_DIR!\namespace.yaml" >nul
copy "kubernetes\deployment.yaml" "!TEMP_DIR!\deployment.yaml" >nul
copy "kubernetes\service.yaml" "!TEMP_DIR!\service.yaml" >nul
copy "kubernetes\ingress.yaml" "!TEMP_DIR!\ingress.yaml" >nul

REM Replace IMAGE_URI placeholder using PowerShell
powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{IMAGE_URI}}', '!IMAGE_URI!' | Set-Content '!TEMP_DIR!\deployment.yaml'"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to update deployment manifest.
    exit /b 1
)

REM Replace environment variable placeholders if values were provided
if not "!REDIS_HOST_VAL!"=="" (
    powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{REDIS_HOST}}', '!REDIS_HOST_VAL!' | Set-Content '!TEMP_DIR!\deployment.yaml'"
)
if not "!REDIS_PORT_VAL!"=="" (
    powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{REDIS_PORT}}', '!REDIS_PORT_VAL!' | Set-Content '!TEMP_DIR!\deployment.yaml'"
)
if not "!REDIS_PASSWORD_VAL!"=="" (
    powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{REDIS_PASSWORD}}', '!REDIS_PASSWORD_VAL!' | Set-Content '!TEMP_DIR!\deployment.yaml'"
)
if not "!PAYMENT_API_URL_VAL!"=="" (
    powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{PAYMENT_API_URL}}', '!PAYMENT_API_URL_VAL!' | Set-Content '!TEMP_DIR!\deployment.yaml'"
)

echo.
echo ============================================================
echo   Applying Kubernetes manifests ...
echo ============================================================

echo   [1/4] Applying namespace ...
kubectl apply -f "!TEMP_DIR!\namespace.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply namespace. & exit /b 1 )

echo   [2/4] Applying deployment ...
kubectl apply -f "!TEMP_DIR!\deployment.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply deployment. & exit /b 1 )

echo   [3/4] Applying service ...
kubectl apply -f "!TEMP_DIR!\service.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply service. & exit /b 1 )

echo   [4/4] Applying ingress ...
kubectl apply -f "!TEMP_DIR!\ingress.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply ingress. & exit /b 1 )

echo.
echo ============================================================
echo   Waiting for deployment rollout ...
echo ============================================================
kubectl rollout status deployment/!APP_NAME! -n !NAMESPACE! --timeout=300s
if !ERRORLEVEL! neq 0 (
    echo ERROR: Deployment rollout failed.
    echo Rollback command: kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
    exit /b 1
)

echo.
echo ============================================================
echo   Verifying deployed resources ...
echo ============================================================
kubectl get pods,svc,ingress -n !NAMESPACE!

echo.
echo ============================================================
echo   Deployment Complete!
echo ============================================================
echo   Health check endpoint: /actuator/health
echo   Rollback command: kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
echo ============================================================

REM Cleanup temp directory
rmdir /s /q "!TEMP_DIR!"

endlocal

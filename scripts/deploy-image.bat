@echo off
setlocal enabledelayedexpansion

:: ============================================================
:: deploy-image.bat — Deploy ResortsLite to AWS EKS (Windows)
:: ============================================================

set "APP_NAME=resortslite"
set "NAMESPACE=resortslite"
set "K8S_DIR=kubernetes"

echo ==============================================
echo   ResortsLite - AWS EKS Deployment Script
echo ==============================================
echo.

:: ---- Collect deployment inputs ----
set /p "AWS_REGION=Enter AWS region (e.g. us-east-1): "
if "!AWS_REGION!"=="" (
    echo ERROR: AWS region is required.
    exit /b 1
)

set /p "CLUSTER_NAME=Enter EKS cluster name: "
if "!CLUSTER_NAME!"=="" (
    echo ERROR: EKS cluster name is required.
    exit /b 1
)

set /p "IMAGE_URI=Enter full Docker image URI (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest): "
if "!IMAGE_URI!"=="" (
    echo ERROR: Docker image URI is required.
    exit /b 1
)

echo.
echo ---- Optional: Application Environment Variables ----
echo Press Enter to skip any variable and use the default.
echo.

set /p "INPUT_REDIS_HOST=Enter REDIS_HOST (ElastiCache endpoint) [localhost]: "
if "!INPUT_REDIS_HOST!"=="" set "INPUT_REDIS_HOST=localhost"
set "REDIS_HOST=!INPUT_REDIS_HOST!"

set /p "INPUT_REDIS_PORT=Enter REDIS_PORT [6379]: "
if "!INPUT_REDIS_PORT!"=="" set "INPUT_REDIS_PORT=6379"
set "REDIS_PORT=!INPUT_REDIS_PORT!"

set /p "INPUT_CACHE_TTL=Enter CACHE_TTL_SECONDS [3600]: "
if "!INPUT_CACHE_TTL!"=="" set "INPUT_CACHE_TTL=3600"
set "CACHE_TTL_SECONDS=!INPUT_CACHE_TTL!"

set /p "INPUT_PAYMENT_URL=Enter PAYMENT_API_URL [http://payment-svc.payments.svc.cluster.local:9090/payments/charge]: "
if "!INPUT_PAYMENT_URL!"=="" set "INPUT_PAYMENT_URL=http://payment-svc.payments.svc.cluster.local:9090/payments/charge"
set "PAYMENT_API_URL=!INPUT_PAYMENT_URL!"

echo.
echo ---- Configuring kubectl for EKS ----
aws eks update-kubeconfig --region !AWS_REGION! --name !CLUSTER_NAME!
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to configure kubectl for EKS cluster.
    exit /b 1
)
echo kubectl configured for cluster: !CLUSTER_NAME!

echo.
echo ---- Verifying cluster connectivity ----
kubectl cluster-info
if !ERRORLEVEL! neq 0 (
    echo ERROR: Cannot connect to EKS cluster.
    exit /b 1
)

echo.
echo ---- Updating Kubernetes manifests ----

:: Create a working copy of deployment.yaml
copy /Y "!K8S_DIR!\deployment.yaml" "!K8S_DIR!\deployment.yaml.bak" >nul

:: Replace placeholders using PowerShell
powershell -NoProfile -Command ^
  "(Get-Content '!K8S_DIR!\deployment.yaml') ^
   -replace '\{\{IMAGE_URI\}\}', '!IMAGE_URI!' ^
   -replace '\{\{REDIS_HOST\}\}', '!REDIS_HOST!' ^
   -replace '\{\{REDIS_PORT\}\}', '!REDIS_PORT!' ^
   -replace '\{\{CACHE_TTL_SECONDS\}\}', '!CACHE_TTL_SECONDS!' ^
   -replace '\{\{PAYMENT_API_URL\}\}', '!PAYMENT_API_URL!' ^
   | Set-Content '!K8S_DIR!\deployment.yaml'"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to update deployment manifest.
    copy /Y "!K8S_DIR!\deployment.yaml.bak" "!K8S_DIR!\deployment.yaml" >nul
    exit /b 1
)
echo Manifests updated with deployment values.

echo.
echo ---- Applying Kubernetes manifests ----

echo Applying namespace...
kubectl apply -f "!K8S_DIR!\namespace.yaml"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to apply namespace.
    goto :restore_and_exit
)

echo Applying deployment...
kubectl apply -f "!K8S_DIR!\deployment.yaml"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to apply deployment.
    goto :restore_and_exit
)

echo Applying service...
kubectl apply -f "!K8S_DIR!\service.yaml"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to apply service.
    goto :restore_and_exit
)

echo Applying ingress...
kubectl apply -f "!K8S_DIR!\ingress.yaml"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to apply ingress.
    goto :restore_and_exit
)

echo.
echo ---- Waiting for deployment rollout ----
kubectl rollout status deployment/!APP_NAME! -n !NAMESPACE! --timeout=300s
if !ERRORLEVEL! neq 0 (
    echo ERROR: Deployment rollout did not complete successfully.
    goto :restore_and_exit
)
echo Deployment rollout complete.

echo.
echo ---- Verifying deployed resources ----
kubectl get pods,svc,ingress -n !NAMESPACE!

echo.
echo ---- Rollback Instructions ----
echo To rollback to the previous version, run:
echo   kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!

echo.
echo ==============================================
echo   Deployment Complete!
echo   Cluster  : !CLUSTER_NAME!
echo   Image    : !IMAGE_URI!
echo   Namespace: !NAMESPACE!
echo ==============================================

:: Restore original deployment.yaml
copy /Y "!K8S_DIR!\deployment.yaml.bak" "!K8S_DIR!\deployment.yaml" >nul
del /Q "!K8S_DIR!\deployment.yaml.bak" >nul 2>&1
endlocal
exit /b 0

:restore_and_exit
copy /Y "!K8S_DIR!\deployment.yaml.bak" "!K8S_DIR!\deployment.yaml" >nul
del /Q "!K8S_DIR!\deployment.yaml.bak" >nul 2>&1
endlocal
exit /b 1

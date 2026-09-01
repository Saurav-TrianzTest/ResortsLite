@echo off
setlocal enabledelayedexpansion

:: =============================================================================
:: build-push.bat — Build and push ResortsLite Docker image (Windows)
:: Supports: AWS ECR and Docker Hub
:: Usage: scripts\build-push.bat
:: Run from repository root (Docker build context = project root)
:: =============================================================================

set PROJECT_NAME=resortslite
set DOCKERFILE_PATH=Dockerfile

echo ==============================================
echo   ResortsLite - Docker Build and Push Script
echo ==============================================
echo.

:: ------------------------------------------------------------------------------
:: Sanitize image name using PowerShell
:: ------------------------------------------------------------------------------
for /f "delims=" %%i in ('powershell -NoProfile -Command "$n = '%PROJECT_NAME%'.ToLower() -replace '[^a-z0-9]+','-'; $n.Trim('-')"') do set IMAGE_NAME=%%i

:: ------------------------------------------------------------------------------
:: Prompt for image tag
:: ------------------------------------------------------------------------------
set /p INPUT_TAG="Enter image tag [latest]: "
if "!INPUT_TAG!"=="" set INPUT_TAG=latest
for /f "delims=" %%i in ('powershell -NoProfile -Command "$t = '!INPUT_TAG!'.ToLower() -replace '[^a-z0-9._-]+','-'; $t.Trim('-')"') do set IMAGE_TAG=%%i
if "!IMAGE_TAG!"=="" set IMAGE_TAG=latest
echo Using image tag: !IMAGE_TAG!
echo.

:: ------------------------------------------------------------------------------
:: Registry selection
:: ------------------------------------------------------------------------------
echo Select container registry:
echo   1) AWS ECR
echo   2) Docker Hub
set /p REGISTRY_CHOICE="Enter choice [1]: "
if "!REGISTRY_CHOICE!"=="" set REGISTRY_CHOICE=1

if "!REGISTRY_CHOICE!"=="1" goto ecr_setup
if "!REGISTRY_CHOICE!"=="2" goto dockerhub_setup
echo Invalid choice. Exiting.
exit /b 1

:: ------------------------------------------------------------------------------
:: AWS ECR
:: ------------------------------------------------------------------------------
:ecr_setup
echo.
echo --- AWS ECR Configuration ---
set /p AWS_REGION="Enter AWS Region [us-east-1]: "
if "!AWS_REGION!"=="" set AWS_REGION=us-east-1

set /p ACCOUNT_ID="Enter AWS Account ID (leave blank to auto-detect): "
if "!ACCOUNT_ID!"=="" (
    echo Fetching AWS Account ID from STS...
    for /f "delims=" %%i in ('aws sts get-caller-identity --query Account --output text') do set ACCOUNT_ID=%%i
    echo Account ID: !ACCOUNT_ID!
)

set /p ECR_REPO="Enter ECR repository name [!IMAGE_NAME!]: "
if "!ECR_REPO!"=="" set ECR_REPO=!IMAGE_NAME!

set REGISTRY_URL=!ACCOUNT_ID!.dkr.ecr.!AWS_REGION!.amazonaws.com
set FULL_IMAGE_NAME=!REGISTRY_URL!/!ECR_REPO!:!IMAGE_TAG!

echo.
echo Logging in to AWS ECR...
aws ecr get-login-password --region !AWS_REGION! | docker login --username AWS --password-stdin !REGISTRY_URL!
if !ERRORLEVEL! neq 0 (
    echo ECR login failed.
    exit /b 1
)

echo.
echo Checking if ECR repository '!ECR_REPO!' exists...
aws ecr describe-repositories --repository-names !ECR_REPO! --region !AWS_REGION! >nul 2>&1
if !ERRORLEVEL! neq 0 (
    echo Creating ECR repository...
    aws ecr create-repository --repository-name !ECR_REPO! --region !AWS_REGION!
    if !ERRORLEVEL! neq 0 (
        echo Failed to create ECR repository.
        exit /b 1
    )
)
echo ECR repository ready.
goto build_image

:: ------------------------------------------------------------------------------
:: Docker Hub
:: ------------------------------------------------------------------------------
:dockerhub_setup
echo.
echo --- Docker Hub Configuration ---
set /p DOCKER_USERNAME="Enter Docker Hub username: "
set /p DOCKER_PASSWORD="Enter Docker Hub password/token: "

set /p DOCKER_REPO="Enter Docker Hub repository name [!DOCKER_USERNAME!/!IMAGE_NAME!]: "
if "!DOCKER_REPO!"=="" set DOCKER_REPO=!DOCKER_USERNAME!/!IMAGE_NAME!

set FULL_IMAGE_NAME=!DOCKER_REPO!:!IMAGE_TAG!

echo.
echo Logging in to Docker Hub...
echo !DOCKER_PASSWORD! | docker login --username !DOCKER_USERNAME! --password-stdin
if !ERRORLEVEL! neq 0 (
    echo Docker Hub login failed.
    exit /b 1
)
goto build_image

:: ------------------------------------------------------------------------------
:: Build Docker image
:: ------------------------------------------------------------------------------
:build_image
echo.
echo Building Docker image: !FULL_IMAGE_NAME!
echo Build context: . (repository root)
echo Dockerfile: !DOCKERFILE_PATH!
echo.
docker build -f !DOCKERFILE_PATH! -t !FULL_IMAGE_NAME! .
if !ERRORLEVEL! neq 0 (
    echo Docker build failed.
    exit /b 1
)

echo.
echo Build successful: !FULL_IMAGE_NAME!

:: ------------------------------------------------------------------------------
:: Push Docker image
:: ------------------------------------------------------------------------------
echo.
echo Pushing image to registry...
docker push !FULL_IMAGE_NAME!
if !ERRORLEVEL! neq 0 (
    echo Docker push failed.
    exit /b 1
)

echo.
echo ==============================================
echo   Image pushed successfully!
echo   !FULL_IMAGE_NAME!
echo ==============================================

endlocal

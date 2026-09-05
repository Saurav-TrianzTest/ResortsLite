@echo off
setlocal enabledelayedexpansion

:: =============================================================================
:: build-push.bat — Build and push the ResortsLite Docker image (Windows)
:: Usage: scripts\build-push.bat
:: Run from the repository root directory.
:: =============================================================================

set "PROJECT_NAME=resortsLite"

echo =============================================
echo   ResortsLite — Docker Build ^& Push
echo =============================================
echo.

:: ── Prompt for image tag ──────────────────────────────────────────────────────
set /p "IMAGE_TAG_INPUT=Enter image tag [latest]: "
if "!IMAGE_TAG_INPUT!"=="" (
    set "IMAGE_TAG=latest"
) else (
    set "IMAGE_TAG=!IMAGE_TAG_INPUT!"
)
echo Using tag: !IMAGE_TAG!
echo.

:: ── Sanitise image name (lowercase via PowerShell) ───────────────────────────
for /f "delims=" %%i in ('powershell -NoProfile -Command "\"!PROJECT_NAME!\".ToLower() -replace '[^a-z0-9]','-' -replace '^-+','' -replace '-+$',''"') do set "IMAGE_NAME=%%i"

:: ── Registry selection ────────────────────────────────────────────────────────
echo Select target registry:
echo   1) AWS ECR
echo   2) Docker Hub
set /p "REGISTRY_CHOICE=Enter choice [1]: "
if "!REGISTRY_CHOICE!"=="" set "REGISTRY_CHOICE=1"

:: =============================================================================
:: AWS ECR
:: =============================================================================
if "!REGISTRY_CHOICE!"=="1" (
    set /p "AWS_REGION=Enter AWS Region [us-east-1]: "
    if "!AWS_REGION!"=="" set "AWS_REGION=us-east-1"

    set /p "ECR_REPO_INPUT=Enter ECR repository name [!IMAGE_NAME!]: "
    if "!ECR_REPO_INPUT!"=="" (
        set "ECR_REPO=!IMAGE_NAME!"
    ) else (
        set "ECR_REPO=!ECR_REPO_INPUT!"
    )

    echo.
    echo Retrieving AWS Account ID...
    for /f "delims=" %%a in ('aws sts get-caller-identity --query Account --output text') do set "ACCOUNT_ID=%%a"
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to retrieve AWS Account ID. Check AWS CLI configuration.
        exit /b 1
    )

    set "REGISTRY_URL=!ACCOUNT_ID!.dkr.ecr.!AWS_REGION!.amazonaws.com"
    set "FULL_IMAGE_NAME=!REGISTRY_URL!/!ECR_REPO!:!IMAGE_TAG!"

    echo Registry : !REGISTRY_URL!
    echo Image    : !FULL_IMAGE_NAME!
    echo.

    echo Authenticating to ECR...
    aws ecr get-login-password --region !AWS_REGION! | docker login --username AWS --password-stdin !REGISTRY_URL!
    if !ERRORLEVEL! neq 0 (
        echo ERROR: ECR login failed.
        exit /b 1
    )

    echo Checking ECR repository '!ECR_REPO!'...
    aws ecr describe-repositories --repository-names !ECR_REPO! --region !AWS_REGION! >nul 2>&1
    if !ERRORLEVEL! neq 0 (
        echo Creating ECR repository '!ECR_REPO!'...
        aws ecr create-repository --repository-name !ECR_REPO! --region !AWS_REGION!
        if !ERRORLEVEL! neq 0 (
            echo ERROR: Failed to create ECR repository.
            exit /b 1
        )
    )
    echo ECR repository ready.

:: =============================================================================
:: Docker Hub
:: =============================================================================
) else if "!REGISTRY_CHOICE!"=="2" (
    set /p "DOCKER_USERNAME=Enter Docker Hub username: "
    set /p "DOCKER_PASSWORD=Enter Docker Hub password/token: "
    set /p "DOCKER_NAMESPACE_INPUT=Enter Docker Hub namespace [!DOCKER_USERNAME!]: "
    if "!DOCKER_NAMESPACE_INPUT!"=="" (
        set "DOCKER_NAMESPACE=!DOCKER_USERNAME!"
    ) else (
        set "DOCKER_NAMESPACE=!DOCKER_NAMESPACE_INPUT!"
    )

    set "FULL_IMAGE_NAME=!DOCKER_NAMESPACE!/!IMAGE_NAME!:!IMAGE_TAG!"
    echo Image: !FULL_IMAGE_NAME!
    echo.

    echo Authenticating to Docker Hub...
    echo !DOCKER_PASSWORD! | docker login --username !DOCKER_USERNAME! --password-stdin
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Docker Hub login failed.
        exit /b 1
    )

) else (
    echo ERROR: Invalid registry choice '!REGISTRY_CHOICE!'. Exiting.
    exit /b 1
)

:: =============================================================================
:: Build
:: =============================================================================
echo.
echo Building Docker image...
echo   Context   : . (repository root)
echo   Dockerfile: Dockerfile
echo   Tag       : !FULL_IMAGE_NAME!
echo.

docker build -f Dockerfile -t "!FULL_IMAGE_NAME!" .
if !ERRORLEVEL! neq 0 (
    echo ERROR: Docker build failed.
    exit /b 1
)

echo.
echo Build successful: !FULL_IMAGE_NAME!

:: =============================================================================
:: Push
:: =============================================================================
echo.
echo Pushing image to registry...
docker push "!FULL_IMAGE_NAME!"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Docker push failed.
    exit /b 1
)

echo.
echo =============================================
echo   Push complete!
echo   Image: !FULL_IMAGE_NAME!
echo =============================================

endlocal

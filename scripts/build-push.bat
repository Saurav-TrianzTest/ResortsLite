@echo off
setlocal enabledelayedexpansion

:: =============================================================================
:: build-push.bat  —  Build and push the ResortsLite Docker image (Windows)
:: Supports: AWS ECR  |  Docker Hub
:: Usage   : scripts\build-push.bat   (run from repository root)
:: =============================================================================

set "PROJECT_NAME=resortslite"
set "DOCKERFILE_PATH=Dockerfile"
set "BUILD_CONTEXT=."

echo ==============================================
echo   ResortsLite -- Docker Build ^& Push
echo ==============================================
echo.

:: ---------------------------------------------------------------------------
:: Registry selection
:: ---------------------------------------------------------------------------
echo Select container registry:
echo   1) AWS ECR
echo   2) Docker Hub
echo.
set /p "REGISTRY_CHOICE=Enter choice [1 or 2]: "

:: ---------------------------------------------------------------------------
:: Image tag
:: ---------------------------------------------------------------------------
set /p "IMAGE_TAG_INPUT=Enter image tag (default: latest): "
if "!IMAGE_TAG_INPUT!"=="" (
    set "IMAGE_TAG=latest"
) else (
    set "IMAGE_TAG=!IMAGE_TAG_INPUT!"
)
echo Using tag: !IMAGE_TAG!
echo.

:: ---------------------------------------------------------------------------
:: Registry-specific configuration
:: ---------------------------------------------------------------------------
if "!REGISTRY_CHOICE!"=="1" (
    :: ---- AWS ECR ----
    set /p "AWS_REGION=Enter AWS Region (e.g. us-east-1): "
    set /p "AWS_ACCOUNT_ID=Enter AWS Account ID: "
    set /p "ECR_REPO_INPUT=Enter ECR repository name (default: %PROJECT_NAME%): "
    if "!ECR_REPO_INPUT!"=="" (
        set "ECR_REPO=%PROJECT_NAME%"
    ) else (
        set "ECR_REPO=!ECR_REPO_INPUT!"
    )

    set "REGISTRY_URL=!AWS_ACCOUNT_ID!.dkr.ecr.!AWS_REGION!.amazonaws.com"
    set "FULL_IMAGE_NAME=!REGISTRY_URL!/!ECR_REPO!:!IMAGE_TAG!"

    echo.
    echo Authenticating with AWS ECR...
    aws ecr get-login-password --region !AWS_REGION! | docker login --username AWS --password-stdin !REGISTRY_URL!
    if !ERRORLEVEL! neq 0 (
        echo ERROR: ECR login failed.
        exit /b 1
    )

    echo Checking / creating ECR repository '!ECR_REPO!'...
    aws ecr describe-repositories --repository-names !ECR_REPO! --region !AWS_REGION! >nul 2>&1
    if !ERRORLEVEL! neq 0 (
        echo Creating ECR repository...
        aws ecr create-repository --repository-name !ECR_REPO! --region !AWS_REGION!
        if !ERRORLEVEL! neq 0 (
            echo ERROR: Failed to create ECR repository.
            exit /b 1
        )
    )

) else if "!REGISTRY_CHOICE!"=="2" (
    :: ---- Docker Hub ----
    set /p "DOCKER_USERNAME=Enter Docker Hub username: "
    set /p "DOCKER_PASSWORD=Enter Docker Hub password/token: "
    set /p "DOCKERHUB_REPO_INPUT=Enter Docker Hub repository name (default: !DOCKER_USERNAME!/%PROJECT_NAME%): "
    if "!DOCKERHUB_REPO_INPUT!"=="" (
        set "DOCKERHUB_REPO=!DOCKER_USERNAME!/%PROJECT_NAME%"
    ) else (
        set "DOCKERHUB_REPO=!DOCKERHUB_REPO_INPUT!"
    )

    set "FULL_IMAGE_NAME=!DOCKERHUB_REPO!:!IMAGE_TAG!"

    echo.
    echo Authenticating with Docker Hub...
    echo !DOCKER_PASSWORD! | docker login --username !DOCKER_USERNAME! --password-stdin
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Docker Hub login failed.
        exit /b 1
    )

) else (
    echo ERROR: Invalid choice '!REGISTRY_CHOICE!'. Please enter 1 or 2.
    exit /b 1
)

:: ---------------------------------------------------------------------------
:: Build
:: ---------------------------------------------------------------------------
echo.
echo Building Docker image: !FULL_IMAGE_NAME!
echo   Dockerfile : !DOCKERFILE_PATH!
echo   Context    : !BUILD_CONTEXT!
echo.
docker build -f "!DOCKERFILE_PATH!" -t "!FULL_IMAGE_NAME!" "!BUILD_CONTEXT!"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Docker build failed.
    exit /b 1
)

:: ---------------------------------------------------------------------------
:: Push
:: ---------------------------------------------------------------------------
echo.
echo Pushing image: !FULL_IMAGE_NAME!
docker push "!FULL_IMAGE_NAME!"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Docker push failed.
    exit /b 1
)

echo.
echo ==============================================
echo   SUCCESS
echo   Image pushed: !FULL_IMAGE_NAME!
echo ==============================================

endlocal

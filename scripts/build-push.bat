@echo off
setlocal enabledelayedexpansion

REM =============================================================================
REM build-push.bat - Build and push the ResortsLite Docker image (Windows)
REM Usage: scripts\build-push.bat
REM Run from the repository root (project root is the Docker build context)
REM =============================================================================

set "PROJECT_NAME=resortsLite"
set "DOCKERFILE_PATH=Dockerfile"

REM Sanitise image name via PowerShell
for /f "delims=" %%I in ('powershell -NoProfile -Command "$n = 'resortsLite'.ToLower() -replace '[^a-z0-9]','-'; $n = $n.Trim('-'); Write-Output $n"') do set "IMAGE_NAME=%%I"

echo ==============================================
echo   ResortsLite - Docker Build and Push
echo ==============================================
echo.

REM ---------------------------------------------------------------------------
REM Registry selection
REM ---------------------------------------------------------------------------
echo Select container registry:
echo   1) AWS ECR
echo   2) Docker Hub
echo.
set /p "REGISTRY_CHOICE=Enter choice [1 or 2]: "

REM ---------------------------------------------------------------------------
REM Image tag
REM ---------------------------------------------------------------------------
set /p "RAW_TAG=Enter image tag (leave blank for 'latest'): "
if "!RAW_TAG!"=="" (
    set "IMAGE_TAG=latest"
) else (
    for /f "delims=" %%T in ('powershell -NoProfile -Command "$t = '!RAW_TAG!'.ToLower() -replace '[^a-z0-9._-]','-'; $t = $t.Trim('-'); if ($t -eq '') { 'latest' } else { $t }"') do set "IMAGE_TAG=%%T"
)
echo Using tag: !IMAGE_TAG!

REM ---------------------------------------------------------------------------
REM Registry-specific configuration
REM ---------------------------------------------------------------------------
if "!REGISTRY_CHOICE!"=="1" (
    REM ---- AWS ECR ----
    set /p "AWS_REGION=Enter AWS Region (e.g. us-east-1): "
    set /p "AWS_ACCOUNT_ID=Enter AWS Account ID: "
    set /p "ECR_REPO_INPUT=Enter ECR repository name [!IMAGE_NAME!]: "
    if "!ECR_REPO_INPUT!"=="" (
        set "ECR_REPO=!IMAGE_NAME!"
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
    REM ---- Docker Hub ----
    set /p "DOCKER_USERNAME=Enter Docker Hub username: "
    set /p "DOCKER_PASSWORD=Enter Docker Hub password/token: "
    set /p "DOCKER_REPO_INPUT=Enter Docker Hub repository name [!DOCKER_USERNAME!/!IMAGE_NAME!]: "
    if "!DOCKER_REPO_INPUT!"=="" (
        set "DOCKER_REPO=!DOCKER_USERNAME!/!IMAGE_NAME!"
    ) else (
        set "DOCKER_REPO=!DOCKER_REPO_INPUT!"
    )

    set "FULL_IMAGE_NAME=!DOCKER_REPO!:!IMAGE_TAG!"

    echo.
    echo Authenticating with Docker Hub...
    echo !DOCKER_PASSWORD! | docker login --username !DOCKER_USERNAME! --password-stdin
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Docker Hub login failed.
        exit /b 1
    )

) else (
    echo ERROR: Invalid choice. Please enter 1 or 2.
    exit /b 1
)

REM ---------------------------------------------------------------------------
REM Build
REM ---------------------------------------------------------------------------
echo.
echo Building Docker image: !FULL_IMAGE_NAME!
echo Build context: . (project root)
docker build -f "!DOCKERFILE_PATH!" -t "!FULL_IMAGE_NAME!" .
if !ERRORLEVEL! neq 0 (
    echo ERROR: Docker build failed.
    exit /b 1
)

echo.
echo Build successful.

REM ---------------------------------------------------------------------------
REM Push
REM ---------------------------------------------------------------------------
echo Pushing image: !FULL_IMAGE_NAME!
docker push "!FULL_IMAGE_NAME!"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Docker push failed.
    exit /b 1
)

echo.
echo ==============================================
echo   Image pushed successfully!
echo   !FULL_IMAGE_NAME!
echo ==============================================

endlocal

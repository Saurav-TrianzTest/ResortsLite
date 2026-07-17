@echo off
setlocal enabledelayedexpansion

echo ============================================
echo   ResortsLite - Docker Build ^& Push Script
echo ============================================
echo.
echo Select container registry:
echo   1. Azure Container Registry (ACR)
echo   2. Docker Hub
echo.
set /p REGISTRY_CHOICE="Enter choice [1 or 2]: "

echo.
set /p IMAGE_TAG_INPUT="Enter image tag (press Enter for 'latest'): "

REM Sanitize tag: lowercase via PowerShell, replace non-alphanumeric with hyphen, trim hyphens
if "!IMAGE_TAG_INPUT!"=="" (
    set IMAGE_TAG=latest
) else (
    for /f "delims=" %%i in ('powershell -Command "$t = '!IMAGE_TAG_INPUT!'.ToLower() -replace '[^a-z0-9._-]','-'; $t = $t.Trim('-'); if ($t -eq '') { 'latest' } else { $t }"') do set IMAGE_TAG=%%i
)

echo.

if "!REGISTRY_CHOICE!"=="1" (
    REM ---- Azure Container Registry ----
    set /p ACR_NAME="Enter ACR name (e.g. myregistry): "
    for /f "delims=" %%i in ('powershell -Command "$n = '!ACR_NAME!'.ToLower() -replace '[^a-z0-9]','-'; $n.Trim('-')"') do set ACR_NAME_CLEAN=%%i
    set FULL_IMAGE_NAME=!ACR_NAME_CLEAN!.azurecr.io/resortslite:!IMAGE_TAG!

    echo.
    echo Logging in to Azure Container Registry: !ACR_NAME_CLEAN! ...
    az acr login --name !ACR_NAME_CLEAN!
    if !ERRORLEVEL! neq 0 (
        echo ERROR: ACR login failed.
        exit /b 1
    )

) else if "!REGISTRY_CHOICE!"=="2" (
    REM ---- Docker Hub ----
    set /p DOCKER_USERNAME="Enter Docker Hub username: "
    set /p DOCKER_PASSWORD="Enter Docker Hub password/token: "
    set FULL_IMAGE_NAME=!DOCKER_USERNAME!/resortslite:!IMAGE_TAG!

    echo.
    echo Logging in to Docker Hub ...
    echo !DOCKER_PASSWORD! | docker login --username !DOCKER_USERNAME! --password-stdin
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Docker Hub login failed.
        exit /b 1
    )

) else (
    echo ERROR: Invalid choice. Please enter 1 or 2.
    exit /b 1
)

echo.
echo Building Docker image: !FULL_IMAGE_NAME!
docker build -f Dockerfile -t !FULL_IMAGE_NAME! .
if !ERRORLEVEL! neq 0 (
    echo ERROR: Docker build failed.
    exit /b 1
)

echo.
echo Pushing image: !FULL_IMAGE_NAME! ...
docker push !FULL_IMAGE_NAME!
if !ERRORLEVEL! neq 0 (
    echo ERROR: Docker push failed.
    exit /b 1
)

echo.
echo ============================================
echo   Build ^& Push Complete!
echo   Image: !FULL_IMAGE_NAME!
echo ============================================

endlocal

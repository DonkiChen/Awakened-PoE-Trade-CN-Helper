@echo off
chcp 65001 >nul
setlocal

for %%I in ("%~dp0..") do set "ROOT_DIR=%%~fI"
pushd "%ROOT_DIR%"
if errorlevel 1 (
    echo [ERROR] Failed to enter helper project directory: "%ROOT_DIR%"
    exit /b 1
)

call "%ROOT_DIR%\gradlew.bat" generateTranslations
set "EXIT_CODE=%ERRORLEVEL%"
popd

if not "%EXIT_CODE%"=="0" (
    echo [ERROR] Translation generation failed.
    exit /b %EXIT_CODE%
)

echo [INFO] Translation generation completed.
exit /b 0

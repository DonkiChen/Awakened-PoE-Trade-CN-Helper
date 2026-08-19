@echo off
chcp 65001 >nul
ver >nul
setlocal EnableExtensions DisableDelayedExpansion
set "CHECK_ONLY="
if /i "%~1"=="--check" set "CHECK_ONLY=1"

rem Keep local game paths here. The generated config.json files are not source files.
set "INTL_GAME_PATH=C:\Program Files\Epic Games\PathOfExile"
set "TENCENT_GAME_PATH=C:\Program Files (x86)\流放之路(511)"

for %%I in ("%~dp0..") do set "ROOT_DIR=%%~fI"
set "PATCH_DIR=%ROOT_DIR%\scripts\patch"
set "PATCHER_PUBLISH_SCRIPT=%ROOT_DIR%\PoePatcherCli\publish.bat"
set "PATCHER_EXE=%ROOT_DIR%\PoePatcherCli\publish\win-x64\PoePatcherCli.exe"
set "DAT_VIEWER_LIB=%ROOT_DIR%\poe-dat-viewer\lib"
set "DAT_RUNNER=%DAT_VIEWER_LIB%\dist\cli\run.js"
set "EXPORTED_DIR=%ROOT_DIR%\data_repo\exported"
set "INTL_TEMPLATE=%EXPORTED_DIR%\intl_config.template.json"
set "TENCENT_TEMPLATE=%EXPORTED_DIR%\tencent_config.template.json"

set "INTL_RESTORE_PATTERN=.*国际服\[EPIC\]还原包\.zip$"
set "INTL_PATCH_PATTERN=.*\[Poe1\]\[EPIC,解压覆盖同名文件\]国际服汉化\+功能补丁_\[方正准圆\]双切简体\(查价\)_v\d\.0\.zip$"
set "TENCENT_RESTORE_PATTERN=.*国服还原包\.zip$"
set "TENCENT_PATCH_PATTERN=.*国服功能补丁\+简改\(v2\)\+查价_V.*_(正式版|抢先版)\.zip$"

if not exist "%PATCH_DIR%" (
    echo [ERROR] Patch directory does not exist: "%PATCH_DIR%"
    exit /b 1
)
if not exist "%PATCHER_PUBLISH_SCRIPT%" (
    echo [ERROR] Patcher publish script does not exist: "%PATCHER_PUBLISH_SCRIPT%"
    exit /b 1
)
if not exist "%INTL_TEMPLATE%" (
    echo [ERROR] International config template does not exist: "%INTL_TEMPLATE%"
    exit /b 1
)
if not exist "%TENCENT_TEMPLATE%" (
    echo [ERROR] Tencent config template does not exist: "%TENCENT_TEMPLATE%"
    exit /b 1
)

echo [INFO] Publishing PoePatcherCli...
call "%PATCHER_PUBLISH_SCRIPT%"
if errorlevel 1 (
    echo [ERROR] Failed to publish PoePatcherCli.
    exit /b 1
)
if not exist "%PATCHER_EXE%" (
    echo [ERROR] Published patcher executable does not exist: "%PATCHER_EXE%"
    exit /b 1
)

echo [INFO] Finding patch packages...
call :find_zip "%INTL_RESTORE_PATTERN%" INTL_RESTORE_ZIP
set "INTL_RESTORE_STATUS=%ERRORLEVEL%"
call :find_zip "%INTL_PATCH_PATTERN%" INTL_PATCH_ZIP
set "INTL_PATCH_STATUS=%ERRORLEVEL%"
call :validate_pair "intl" "%INTL_RESTORE_STATUS%" "%INTL_PATCH_STATUS%"
if errorlevel 1 exit /b 1
if "%INTL_RESTORE_STATUS%"=="0" set "HAS_INTL=1"

call :find_zip "%TENCENT_RESTORE_PATTERN%" TENCENT_RESTORE_ZIP
set "TENCENT_RESTORE_STATUS=%ERRORLEVEL%"
call :find_zip "%TENCENT_PATCH_PATTERN%" TENCENT_PATCH_ZIP
set "TENCENT_PATCH_STATUS=%ERRORLEVEL%"
call :validate_pair "tencent" "%TENCENT_RESTORE_STATUS%" "%TENCENT_PATCH_STATUS%"
if errorlevel 1 exit /b 1
if "%TENCENT_RESTORE_STATUS%"=="0" set "HAS_TENCENT=1"

if not defined HAS_INTL if not defined HAS_TENCENT (
    echo [ERROR] No complete patch package group was found.
    exit /b 1
)
if defined CHECK_ONLY (
    echo [INFO] Validation passed. Skipping game data export.
    exit /b 0
)

echo [INFO] Building poe-dat-viewer...
pushd "%DAT_VIEWER_LIB%"
call npm install
if errorlevel 1 (
    popd
    echo [ERROR] npm install failed.
    exit /b 1
)
call npx tsc
set "TSC_STATUS=%ERRORLEVEL%"
popd
if not "%TSC_STATUS%"=="0" (
    echo [ERROR] TypeScript compilation failed.
    exit /b %TSC_STATUS%
)
if not exist "%DAT_RUNNER%" (
    echo [ERROR] poe-dat-viewer runner does not exist: "%DAT_RUNNER%"
    exit /b 1
)

if defined HAS_INTL (
    call :process_source "intl_amsco2" "intl" "%INTL_GAME_PATH%" "%INTL_TEMPLATE%" "%INTL_RESTORE_ZIP%" "amsco" "%INTL_PATCH_ZIP%"
    if errorlevel 1 exit /b 1

    call :process_source "intl_poedb" "intl" "%INTL_GAME_PATH%" "%INTL_TEMPLATE%" "%INTL_RESTORE_ZIP%" "poedb" ""
    if errorlevel 1 exit /b 1
)

if defined HAS_TENCENT (
    call :process_tencent_sources
    if errorlevel 1 exit /b 1
)

echo [INFO] All requested data sources were exported successfully.
exit /b 0

:find_zip
set "FIND_ZIP_RESULT="
set "FIND_ZIP_PATTERN=%~1"
for /f "delims=" %%F in ('powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -Command "$files = @(Get-ChildItem -LiteralPath $env:PATCH_DIR -File -Filter '*.zip' | Where-Object { $_.Name -match $env:FIND_ZIP_PATTERN }); if ($files.Count -gt 1) { '__MULTIPLE__' } elseif ($files.Count -eq 1) { $files[0].FullName }"') do set "FIND_ZIP_RESULT=%%F"
if "%FIND_ZIP_RESULT%"=="__MULTIPLE__" exit /b 2
if not defined FIND_ZIP_RESULT exit /b 1
set "%~2=%FIND_ZIP_RESULT%"
echo [INFO] Found %~2: "%FIND_ZIP_RESULT%"
exit /b 0

:validate_pair
set "PAIR_NAME=%~1"
set "PAIR_RESTORE_STATUS=%~2"
set "PAIR_PATCH_STATUS=%~3"
if "%PAIR_RESTORE_STATUS%"=="2" (
    echo [ERROR] Multiple %PAIR_NAME% restore packages matched the configured pattern.
    exit /b 1
)
if "%PAIR_PATCH_STATUS%"=="2" (
    echo [ERROR] Multiple %PAIR_NAME% patch packages matched the configured pattern.
    exit /b 1
)
if not "%PAIR_RESTORE_STATUS%"=="%PAIR_PATCH_STATUS%" (
    echo [ERROR] %PAIR_NAME% package group is incomplete. Restore and patch packages must be present together.
    exit /b 1
)
exit /b 0

:process_source
set "SOURCE_DIR=%~1"
set "SOURCE_GAME=%~2"
set "SOURCE_GAME_PATH=%~3"
set "SOURCE_TEMPLATE=%~4"
set "SOURCE_RESTORE_ZIP=%~5"
set "SOURCE_PATCH_SOURCE=%~6"
set "SOURCE_PATCH_ZIP=%~7"
set "SOURCE_STATUS=0"

echo [INFO] Preparing %SOURCE_DIR% config...
call :prepare_source_config "%SOURCE_DIR%" "%SOURCE_TEMPLATE%" "%SOURCE_GAME_PATH%"
if errorlevel 1 exit /b 1

echo [INFO] Restoring %SOURCE_GAME% game files before %SOURCE_DIR%...
call :apply_restore "%SOURCE_GAME%" "%SOURCE_GAME_PATH%" "%SOURCE_RESTORE_ZIP%"
set "SOURCE_STATUS=%ERRORLEVEL%"
if not "%SOURCE_STATUS%"=="0" goto :process_source_cleanup

if /i "%SOURCE_PATCH_SOURCE%"=="poedb" goto :process_source_poedb
echo [INFO] Applying %SOURCE_DIR% patch package...
"%PATCHER_EXE%" --game "%SOURCE_GAME%" --root "%SOURCE_GAME_PATH%" --source "%SOURCE_PATCH_SOURCE%" --zip "%SOURCE_PATCH_ZIP%"
goto :process_source_patch_status

:process_source_poedb
echo [INFO] Applying poedb patch to %SOURCE_DIR%...
"%PATCHER_EXE%" --game "%SOURCE_GAME%" --root "%SOURCE_GAME_PATH%" --source poedb

:process_source_patch_status
set "SOURCE_STATUS=%ERRORLEVEL%"
if not "%SOURCE_STATUS%"=="0" goto :process_source_cleanup

echo [INFO] Exporting %SOURCE_DIR%...
call :export_source "%SOURCE_DIR%"
set "SOURCE_STATUS=%ERRORLEVEL%"

:process_source_cleanup
echo [INFO] Restoring %SOURCE_GAME% game files after %SOURCE_DIR%...
call :apply_restore "%SOURCE_GAME%" "%SOURCE_GAME_PATH%" "%SOURCE_RESTORE_ZIP%"
if errorlevel 1 set "SOURCE_STATUS=1"
if not "%SOURCE_STATUS%"=="0" (
    echo [ERROR] Failed to process %SOURCE_DIR%.
    exit /b %SOURCE_STATUS%
)
echo [INFO] Finished %SOURCE_DIR%.
exit /b 0

:process_tencent_sources
set "SOURCE_GAME=tencent"
set "SOURCE_GAME_PATH=%TENCENT_GAME_PATH%"
set "SOURCE_RESTORE_ZIP=%TENCENT_RESTORE_ZIP%"
set "SOURCE_STATUS=0"

echo [INFO] Preparing tencent configs...
call :prepare_source_config "tencent" "%TENCENT_TEMPLATE%" "%SOURCE_GAME_PATH%"
if errorlevel 1 exit /b 1
call :prepare_source_config "tencent_amsco2" "%TENCENT_TEMPLATE%" "%SOURCE_GAME_PATH%"
if errorlevel 1 exit /b 1

echo [INFO] Restoring tencent game files before clean export...
call :apply_restore "%SOURCE_GAME%" "%SOURCE_GAME_PATH%" "%SOURCE_RESTORE_ZIP%"
set "SOURCE_STATUS=%ERRORLEVEL%"
if not "%SOURCE_STATUS%"=="0" goto :process_tencent_cleanup

echo [INFO] Exporting clean tencent data...
call :export_source "tencent"
set "SOURCE_STATUS=%ERRORLEVEL%"
if not "%SOURCE_STATUS%"=="0" goto :process_tencent_cleanup

echo [INFO] Applying tencent patch package...
"%PATCHER_EXE%" --game "%SOURCE_GAME%" --root "%SOURCE_GAME_PATH%" --source amsco --zip "%TENCENT_PATCH_ZIP%"
set "SOURCE_STATUS=%ERRORLEVEL%"
if not "%SOURCE_STATUS%"=="0" goto :process_tencent_cleanup

echo [INFO] Exporting patched tencent data...
call :export_source "tencent_amsco2"
set "SOURCE_STATUS=%ERRORLEVEL%"

:process_tencent_cleanup
echo [INFO] Restoring tencent game files after both exports...
call :apply_restore "%SOURCE_GAME%" "%SOURCE_GAME_PATH%" "%SOURCE_RESTORE_ZIP%"
if errorlevel 1 set "SOURCE_STATUS=1"
if not "%SOURCE_STATUS%"=="0" (
    echo [ERROR] Failed to process tencent and tencent_amsco2.
    exit /b %SOURCE_STATUS%
)
echo [INFO] Finished tencent and tencent_amsco2.
exit /b 0

:apply_restore
"%PATCHER_EXE%" --game "%~1" --root "%~2" --source restore --zip "%~3"
exit /b %ERRORLEVEL%

:prepare_source_config
set "PREPARE_SOURCE_DIR=%~1"
set "PREPARE_TEMPLATE=%~2"
set "PREPARE_GAME_PATH=%~3"
set "PREPARE_SOURCE_CONFIG=%EXPORTED_DIR%\%PREPARE_SOURCE_DIR%\config.json"
if not exist "%EXPORTED_DIR%\%PREPARE_SOURCE_DIR%" mkdir "%EXPORTED_DIR%\%PREPARE_SOURCE_DIR%"
if not exist "%EXPORTED_DIR%\%PREPARE_SOURCE_DIR%" exit /b 1
call :generate_config "%PREPARE_TEMPLATE%" "%PREPARE_SOURCE_CONFIG%" "%PREPARE_GAME_PATH%"
exit /b %ERRORLEVEL%

:export_source
set "EXPORT_SOURCE_DIR=%~1"
pushd "%EXPORTED_DIR%\%EXPORT_SOURCE_DIR%"
if errorlevel 1 exit /b 1
node "%DAT_RUNNER%"
set "EXPORT_STATUS=%ERRORLEVEL%"
popd
exit /b %EXPORT_STATUS%

:generate_config
set "CONFIG_TEMPLATE_PATH=%~1"
set "CONFIG_OUTPUT_PATH=%~2"
set "CONFIG_GAME_PATH=%~3"
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -Command "$template = [IO.File]::ReadAllText($env:CONFIG_TEMPLATE_PATH); $placeholder = [char]34 + '__GAME_PATH__' + [char]34; if (-not $template.Contains($placeholder)) { throw 'Config template does not contain the __GAME_PATH__ placeholder.' }; $jsonPath = ConvertTo-Json -InputObject $env:CONFIG_GAME_PATH -Compress; $content = $template.Replace($placeholder, $jsonPath); [IO.File]::WriteAllText($env:CONFIG_OUTPUT_PATH, $content, [Text.UTF8Encoding]::new($false))"
if errorlevel 1 (
    echo [ERROR] Failed to generate config: "%CONFIG_OUTPUT_PATH%"
    exit /b 1
)
exit /b 0

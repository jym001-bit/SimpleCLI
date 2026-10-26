@echo off
setlocal DisableDelayedExpansion

cd /d "%~dp0"
chcp 936 >nul

rem Windows Terminal can retain an old environment after user variables change.
rem Read only missing Langfuse settings from the current user's saved variables.
if not defined PAICLI_LANGFUSE_ENABLED for /f "usebackq delims=" %%V in (`powershell -NoProfile -Command "[Environment]::GetEnvironmentVariable('PAICLI_LANGFUSE_ENABLED','User')"`) do set "PAICLI_LANGFUSE_ENABLED=%%V"
if not defined LANGFUSE_PUBLIC_KEY for /f "usebackq delims=" %%V in (`powershell -NoProfile -Command "[Environment]::GetEnvironmentVariable('LANGFUSE_PUBLIC_KEY','User')"`) do set "LANGFUSE_PUBLIC_KEY=%%V"
if not defined LANGFUSE_SECRET_KEY for /f "usebackq delims=" %%V in (`powershell -NoProfile -Command "[Environment]::GetEnvironmentVariable('LANGFUSE_SECRET_KEY','User')"`) do set "LANGFUSE_SECRET_KEY=%%V"
if not defined LANGFUSE_BASE_URL for /f "usebackq delims=" %%V in (`powershell -NoProfile -Command "[Environment]::GetEnvironmentVariable('LANGFUSE_BASE_URL','User')"`) do set "LANGFUSE_BASE_URL=%%V"

set "LANGFUSE_READY=0"
if /I "%PAICLI_LANGFUSE_ENABLED%"=="true" if defined LANGFUSE_PUBLIC_KEY if defined LANGFUSE_SECRET_KEY set "LANGFUSE_READY=1"
if "%LANGFUSE_READY%"=="1" (echo [Simple CLI] Langfuse tracing: enabled) else (echo [Simple CLI] Langfuse tracing: disabled - check user environment variables)
if /I "%~1"=="langfuse-check" exit /b 0

set "JAR_PATH=target\paicli-1.0-SNAPSHOT.jar"

if /I "%~1"=="build" (
    echo [Simple CLI] Building jar...
    call mvn -q -DskipTests clean package
    if errorlevel 1 (
        echo [Simple CLI] Build failed.
        exit /b 1
    )
)

if not exist "%JAR_PATH%" (
    echo [Simple CLI] Jar not found, building first...
    call mvn -q -DskipTests clean package
    if errorlevel 1 (
        echo [Simple CLI] Build failed.
        exit /b 1
    )
)

echo [Simple CLI] Starting with GBK terminal encoding...
java --enable-native-access=ALL-UNNAMED "-Dorg.jline.terminal.stdin.encoding=GBK" "-Dorg.jline.terminal.stdout.encoding=GBK" "-Dorg.jline.terminal.stderr.encoding=GBK" -jar "%JAR_PATH%"

endlocal

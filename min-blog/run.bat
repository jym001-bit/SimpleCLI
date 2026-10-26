@echo off
rem MinBlog one-click launcher (Windows)
cd /d %~dp0

if not exist out mkdir out

echo [1/2] Compiling...
javac -encoding UTF-8 -d out src\com\minblog\*.java
if errorlevel 1 (
    echo.
    echo [FAILED] Compile error. Please make sure JDK 8+ is installed
    echo         and javac is in your PATH.
    pause
    exit /b 1
)

echo [2/2] Starting server...
echo Open http://localhost:8080 in your browser. Press Ctrl+C to stop.
echo.
java -Dfile.encoding=UTF-8 -cp out com.minblog.Main
pause

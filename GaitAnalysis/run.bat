@echo off
setlocal EnableDelayedExpansion
title Gait Analysis System

cd /d "%~dp0"

echo  Gait Analysis System Launcher
echo.

:: Check Java
where java >nul 2>&1
if errorlevel 1 (
    echo  [ERROR] Java not found on PATH.
    echo  Download the JDK from: https://adoptium.net/
    pause
    exit /b 1
)

where javac >nul 2>&1
if errorlevel 1 (
    echo  [ERROR] javac not found. Install the JDK, not just the JRE.
    echo  Download: https://adoptium.net/
    pause
    exit /b 1
)

:: Check required files
set MISSING=0
for %%F in (FootApp.java FootPanel.java Main.java) do (
    if not exist "%%F" (
        echo  [ERROR] Missing source file: %%F
        set MISSING=1
    )
)
if not exist "FootAppDrawing.png" (
    echo  [ERROR] Missing asset: FootAppDrawing.png
    set MISSING=1
)
if not exist "ble_collector.py" (
    echo  [WARNING] ble_collector.py not found. BLE features will not work.
)
if "!MISSING!"=="1" (
    echo  One or more required files are missing. Aborting.
    pause
    exit /b 1
)

:: Compile
echo  Compiling Java sources...
javac FootApp.java FootPanel.java Main.java 2>&1
if errorlevel 1 (
    echo  [ERROR] Compilation failed. See messages above.
    pause
    exit /b 1
)
echo  Compilation successful.
echo.

:: Check Python
where python >nul 2>&1
if errorlevel 1 (
    echo  [WARNING] Python not found. BLE features require Python 3 + bleak.
    echo.
)

:: Launch
echo  Launching Gait Analysis System...
echo.
java FootApp

echo.
echo  Application closed.
if exist "data.csv" echo  data.csv found - run train.bat to train the model.
echo.
pause
endlocal

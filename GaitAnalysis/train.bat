@echo off
setlocal EnableDelayedExpansion
title Gait Analysis - Model Training

:: -------------------------------------------------------------
::  train.bat  -  Run the training loop (Main.java)
:: -------------------------------------------------------------

cd /d "%~dp0"

:: -- 1. Check Java --------------------------------------------
where javac >nul 2>&1
if errorlevel 1 (
    echo  [ERROR] javac not found. Install the JDK and retry.
    pause
    exit /b 1
)

:: -- 2. Check data files exist --------------------------------
set FOUND=0
for %%F in (data1.csv data2.csv data3.csv data4.csv data5.csv
            data6.csv data7.csv data8.csv data9.csv data10.csv
            data11.csv data12.csv data13.csv data14.csv data15.csv
            data16.csv data17.csv data18.csv) do (
    if exist "%%F" set FOUND=1
)
if "!FOUND!"=="0" (
    echo  [WARNING] No dataN.csv files found in this folder.
    echo  Collect data with the main app first.
    echo.
)

:: -- 3. Compile (in case sources changed since last run) ------
echo  Compiling...
javac FootApp.java FootPanel.java Main.java 2>&1
if errorlevel 1 (
    echo  [ERROR] Compilation failed.
    pause
    exit /b 1
)

:: -- 4. Run training ------------------------------------------
echo.
echo  Running training loop (100 cycles)...
echo  This may take a minute. Do not close this window.
echo.

java Main

echo.
echo  Training complete.
echo  'trained' and 'trained2' tensor files have been saved.
echo  Launch run.bat and press Interpret to use the new model.
echo.
pause
endlocal

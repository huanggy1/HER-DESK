@echo off
chcp 65001 >nul
cd /d "%~dp0\.."
title Her Desk Relay

where java >nul 2>&1
if errorlevel 1 (
    echo 未找到 Java，请先安装 JDK 8 或更高版本。
    pause
    exit /b 1
)

set "JAR="
if exist "target\her-desk-relay.jar" (
    set "JAR=target\her-desk-relay.jar"
) else if exist "target\her-desk-1.0.0-relay.jar" (
    set "JAR=target\her-desk-1.0.0-relay.jar"
)

if "%JAR%"=="" (
    echo 未找到中继 JAR，请先执行: mvn clean package
    pause
    exit /b 1
)

set "PORT=9000"
if not "%~1"=="" set "PORT=%~1"

echo 启动 Her Desk Relay，端口 %PORT% ...
java -jar "%JAR%" %PORT%
pause

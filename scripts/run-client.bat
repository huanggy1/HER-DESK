@echo off
chcp 65001 >nul
cd /d "%~dp0\.."
title Her Desk - 控制端

where java >nul 2>&1
if errorlevel 1 (
    echo 未找到 Java，请先安装 JDK 8 或更高版本。
    pause
    exit /b 1
)

set "JAR="
if exist "target\her-desk-1.0.0.jar" (
    set "JAR=target\her-desk-1.0.0.jar"
) else (
    for %%f in (target\her-desk-*.jar) do (
        set "JAR=%%f"
        goto :found
    )
)
:found

if "%JAR%"=="" (
    echo 未找到 JAR 包，请先在项目根目录执行: mvn clean package
    pause
    exit /b 1
)

echo 启动 Her Desk 控制端 ...
java -jar "%JAR%" client
if errorlevel 1 (
    echo 程序异常退出。
    pause
)
exit /b 0

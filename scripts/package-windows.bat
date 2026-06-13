@echo off
chcp 65001 >nul
cd /d "%~dp0\.."
title Her Desk Windows 打包

echo ==^> 检查 JDK 17+ ...
where jpackage >nul 2>&1
if errorlevel 1 (
    echo 错误：未找到 jpackage。请安装 JDK 17 或更高版本，并加入 PATH。
    pause
    exit /b 1
)

echo ==^> 编译 JAR ...
call mvn compile -q
if errorlevel 1 (
    echo Maven 编译失败。
    pause
    exit /b 1
)

cd target\classes
jar cfm ..\her-desk-1.0.0.jar ..\MANIFEST.MF .
jar cfm ..\her-desk-relay.jar ..\RELAY-MANIFEST.MF com\herdesk\relay\ com\herdesk\common\
cd ..\..

if not exist "target\jpackage-input" mkdir "target\jpackage-input"
if not exist "target\dist" mkdir "target\dist"
copy /y "target\her-desk-1.0.0.jar" "target\jpackage-input\"
copy /y "target\her-desk-relay.jar" "target\jpackage-input\"

echo ==^> 打包主程序 EXE 安装包 ...
jpackage ^
  --input target\jpackage-input ^
  --name "Her Desk" ^
  --main-jar her-desk-1.0.0.jar ^
  --main-class com.herdesk.Launcher ^
  --type exe ^
  --app-version 1.0.0 ^
  --vendor "HerDesk" ^
  --description "内网 Java 远程桌面工具" ^
  --dest target\dist ^
  --win-console ^
  --java-options "-Xmx512m"

echo ==^> 打包中继 EXE 安装包 ...
jpackage ^
  --input target\jpackage-input ^
  --name "Her Desk Relay" ^
  --main-jar her-desk-relay.jar ^
  --main-class com.herdesk.relay.RelayMain ^
  --type exe ^
  --app-version 1.0.0 ^
  --vendor "HerDesk" ^
  --description "Her Desk 公网中继服务" ^
  --dest target\dist ^
  --win-console ^
  --java-options "-Xmx256m"

echo.
echo 完成。产物目录：target\dist\
dir /b target\dist\*.exe
pause

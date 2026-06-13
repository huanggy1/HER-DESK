#!/bin/bash
# Her Desk - 模式选择启动（macOS 双击运行）
set -e
cd "$(dirname "$0")/.."

if ! command -v java >/dev/null 2>&1; then
    echo "未找到 Java，请先安装 JDK 8 或更高版本。"
    read -r -p "按回车键退出..."
    exit 1
fi

JAR=""
if [ -f "target/her-desk-1.0.0.jar" ]; then
    JAR="target/her-desk-1.0.0.jar"
else
    JAR=$(find target -maxdepth 1 -name "her-desk-*.jar" 2>/dev/null | head -1)
fi

if [ -z "$JAR" ] || [ ! -f "$JAR" ]; then
    echo "未找到 JAR 包，请先在项目根目录执行: mvn clean package"
    read -r -p "按回车键退出..."
    exit 1
fi

echo "启动 Her Desk ..."
java -jar "$JAR"
EXIT_CODE=$?
if [ $EXIT_CODE -ne 0 ]; then
    echo "程序异常退出，代码: $EXIT_CODE"
    read -r -p "按回车键退出..."
fi
exit $EXIT_CODE

#!/bin/bash
# Her Desk Relay - 公网中继服务（部署在 VPS）
set -e
cd "$(dirname "$0")/.."

if ! command -v java >/dev/null 2>&1; then
    echo "未找到 Java，请先安装 JDK 8 或更高版本。"
    read -r -p "按回车键退出..."
    exit 1
fi

JAR=""
if [ -f "target/her-desk-relay.jar" ]; then
    JAR="target/her-desk-relay.jar"
elif [ -f "target/her-desk-1.0.0-relay.jar" ]; then
    JAR="target/her-desk-1.0.0-relay.jar"
fi

if [ -z "$JAR" ] || [ ! -f "$JAR" ]; then
    echo "未找到中继 JAR，请先执行: mvn clean package"
    read -r -p "按回车键退出..."
    exit 1
fi

PORT="${1:-9000}"
echo "启动 Her Desk Relay，端口 $PORT ..."
java -jar "$JAR" "$PORT"

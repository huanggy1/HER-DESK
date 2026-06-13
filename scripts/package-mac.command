#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."

echo "==> 编译 JAR ..."
mvn compile -q
cd target/classes
jar cfm ../her-desk-1.0.0.jar ../MANIFEST.MF .
jar cfm ../her-desk-relay.jar ../RELAY-MANIFEST.MF com/herdesk/relay/ com/herdesk/common/
cd ../..

if [ -z "${JAVA_HOME:-}" ] || ! command -v "$JAVA_HOME/bin/jpackage" >/dev/null 2>&1; then
  if [ -x "$HOME/.sdkman/candidates/java/17.0.8-tem/bin/jpackage" ]; then
    export JAVA_HOME="$HOME/.sdkman/candidates/java/17.0.8-tem"
  elif [ -x "$HOME/.sdkman/candidates/java/current/bin/jpackage" ]; then
    export JAVA_HOME="$HOME/.sdkman/candidates/java/current"
  else
    echo "错误：需要 JDK 17+（含 jpackage）。请安装后设置 JAVA_HOME。"
    exit 1
  fi
fi

JPACKAGE="$JAVA_HOME/bin/jpackage"
echo "==> 使用 JDK: $JAVA_HOME"

mkdir -p target/jpackage-input target/dist
cp target/her-desk-1.0.0.jar target/jpackage-input/
cp target/her-desk-relay.jar target/jpackage-input/

echo "==> 打包主程序 DMG ..."
"$JPACKAGE" \
  --input target/jpackage-input \
  --name "Her Desk" \
  --main-jar her-desk-1.0.0.jar \
  --main-class com.herdesk.Launcher \
  --type dmg \
  --app-version 1.0.0 \
  --vendor "HerDesk" \
  --description "内网 Java 远程桌面工具" \
  --dest target/dist \
  --java-options "-Xmx512m"

echo "==> 打包中继 DMG ..."
"$JPACKAGE" \
  --input target/jpackage-input \
  --name "Her Desk Relay" \
  --main-jar her-desk-relay.jar \
  --main-class com.herdesk.relay.RelayMain \
  --type dmg \
  --app-version 1.0.0 \
  --vendor "HerDesk" \
  --description "Her Desk 公网中继服务" \
  --dest target/dist \
  --java-options "-Xmx256m"

echo ""
echo "完成。产物目录：target/dist/"
ls -lah target/dist/*.dmg

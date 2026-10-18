#!/usr/bin/env bash
# 打包插件：build/comment-companion/lib/comment-companion-0.1.0.jar + 可安装 zip
# 安装到 GoLand（任选其一）：
#   1. 把 build/comment-companion 整个目录拷进
#      ~/Library/Application Support/JetBrains/GoLand2026.2/plugins/
#   2. GUI: Settings | Plugins | Install Plugin from Disk 选 build/comment-companion-0.1.0.zip
set -eo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/scripts/env.sh"

"$ROOT/scripts/compile-main.sh"

STAGE="$BUILD/$PLUGIN_NAME"
JAR_ROOT="$BUILD/jar-root"
rm -rf "$STAGE" "$JAR_ROOT" "$BUILD/$PLUGIN_NAME-$PLUGIN_VERSION.zip"
mkdir -p "$STAGE/lib" "$JAR_ROOT"

cp -R "$CLASSES/." "$JAR_ROOT/"
cp -R "$ROOT/src/main/resources/." "$JAR_ROOT/"

(cd "$JAR_ROOT" && zip -q -r "$STAGE/lib/$PLUGIN_NAME-$PLUGIN_VERSION.jar" .)

(cd "$BUILD" && zip -q -r "$PLUGIN_NAME-$PLUGIN_VERSION.zip" "$PLUGIN_NAME")

echo "插件 jar：$STAGE/lib/$PLUGIN_NAME-$PLUGIN_VERSION.jar"
echo "插件 zip：$BUILD/$PLUGIN_NAME-$PLUGIN_VERSION.zip"
unzip -l "$BUILD/$PLUGIN_NAME-$PLUGIN_VERSION.zip"

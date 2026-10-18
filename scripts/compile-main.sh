#!/usr/bin/env bash
# 编译 main 源码（Kotlin），产物在 build/classes。
set -eo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/scripts/env.sh"
require_kotlin
ensure_platform_cp

rm -rf "$CLASSES"
mkdir -p "$CLASSES"

SOURCES=()
while IFS= read -r f; do SOURCES+=("$f"); done < <(find "$ROOT/src/main/kotlin" -name '*.kt' | sort)
if [[ "${#SOURCES[@]}" -eq 0 ]]; then
  echo "没有找到 Kotlin 源码" >&2
  exit 2
fi

echo "编译 ${#SOURCES[@]} 个 Kotlin 文件（classpath: GoLand 平台 + Go 插件 + kotlin-stdlib）..."
kotlin_compile "$CLASSES" "$PLATFORM_CP:$KOTLIN_STDLIB" "${SOURCES[@]}"

if ! find "$CLASSES" -name '*.class' -print -quit | grep -q .; then
  echo "编译失败：没有生成 class 文件（日志见 build/kotlinc.log）" >&2
  exit 1
fi
echo "完成：${CLASSES}（$(find "$CLASSES" -name '*.class' | wc -l | tr -d ' ') 个 class）"

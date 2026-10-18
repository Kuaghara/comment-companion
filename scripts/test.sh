#!/usr/bin/env bash
# 编译 main + test，跑 JUnit。
set -eo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/scripts/env.sh"
require_kotlin

"$ROOT/scripts/check-architecture.sh"

"$ROOT/scripts/compile-main.sh"

rm -rf "$TEST_CLASSES"
mkdir -p "$TEST_CLASSES"

TEST_SOURCES=()
while IFS= read -r f; do TEST_SOURCES+=("$f"); done < <(find "$ROOT/src/test/kotlin" -name '*.kt' | sort)
if [[ "${#TEST_SOURCES[@]}" -eq 0 ]]; then
  echo "没有找到测试源码" >&2
  exit 2
fi

echo "编译 ${#TEST_SOURCES[@]} 个测试文件..."
kotlin_compile "$TEST_CLASSES" "$CLASSES:$PLATFORM_CP:$KOTLIN_STDLIB:$JUNIT_CONSOLE" "${TEST_SOURCES[@]}"

echo
"$JAVA" -jar "$JUNIT_CONSOLE" execute \
  --class-path "$CLASSES:$TEST_CLASSES:$KOTLIN_STDLIB" \
  --scan-class-path "$TEST_CLASSES" \
  --details=tree \
  --disable-ansi-colors

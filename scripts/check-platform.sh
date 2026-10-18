#!/usr/bin/env bash
# 平台兼容性检查：用 Kotlin 编译一个直接引用 GoLand 平台 API + Go PSI 的探针。
# 通过说明：Kotlin 编译器、GoLand 2026.2 的平台 jar、Go 插件 jar 三者能协同工作。
set -eo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/scripts/env.sh"
require_kotlin
ensure_platform_cp

OUT="$BUILD/platform-probe-classes"
rm -rf "$OUT"
mkdir -p "$OUT"

echo "编译 Kotlin 平台探针（InlayModel / GoFile / GoFunctionDeclaration / WriteCommandAction）..."
kotlin_compile "$OUT" "$PLATFORM_CP:$KOTLIN_STDLIB" "$ROOT/tools/platform-probe/PlatformProbe.kt"

COUNT="$(find "$OUT" -name '*.class' | wc -l | tr -d ' ')"
if [[ "$COUNT" -eq 0 ]]; then
  echo "平台探针编译失败（日志见 build/kotlinc.log）" >&2
  exit 1
fi
echo "通过：生成 ${COUNT} 个 class，GoLand 平台 + Go PSI 的 Kotlin 互操作正常。"

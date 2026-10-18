#!/usr/bin/env bash
# 架构守则检查：依赖方向必须是 core ← ports ← adapters/app
#   core   不得 import com.intellij / ide / engine / ports / app
#   ports  不得 import com.intellij / ide / engine / app
# 任何一条违反即退出非零。
set -eo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MAIN="$ROOT/src/main/kotlin/dev/commentcompanion"
FAILED=0

check_no_match() {
  local what="$1" pattern="$2" dir="$3"
  if grep -RInE "$pattern" "$dir" >/dev/null 2>&1; then
    echo "架构违规：$what" >&2
    grep -RInE "$pattern" "$dir" >&2
    FAILED=1
  fi
}

check_no_match "core 依赖了 IntelliJ 平台" \
  "^import com\.intellij" \
  "$MAIN/core"

check_no_match "core 依赖了上层包（ide/engine/ports/app）" \
  "^import dev\.commentcompanion\.(ide|engine|ports|app)\." \
  "$MAIN/core"

check_no_match "ports 依赖了 IntelliJ 平台" \
  "^import com\.intellij" \
  "$MAIN/ports"

check_no_match "ports 依赖了适配层/编排层（ide/engine/app）" \
  "^import dev\.commentcompanion\.(ide|engine|app)\." \
  "$MAIN/ports"

if [[ "$FAILED" -ne 0 ]]; then
  echo "架构检查失败" >&2
  exit 1
fi

echo "架构检查通过：core/ports 的依赖方向正确"

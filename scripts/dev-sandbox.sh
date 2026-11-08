#!/usr/bin/env bash
# 在隔离沙盒里启动 GoLand，加载本插件（不污染日常配置），并自动做一次加载/PSI 验证。
#
#   ./scripts/dev-sandbox.sh                    # 启动沙盒 GoLand（打开内置验证项目）
#   DEBUG_PORT=5005 ./scripts/dev-sandbox.sh    # 同时打开 JDWP，供远程调试 attach
#   SANDBOX_PROJECT=/path/to/proj ./...         # 指定要打开的项目
#
# 沙盒做法：
#   - idea.config.path 指向 .sandbox/config（首次从日常配置复制 goland.key 与 options/，保证许可与设置可用）
#   - idea.system.path / idea.plugins.path / idea.log.path 全部隔离
#   - 通过 -Dcommentcompanion.verifyTo 让插件的启动活动把验证结果写成 .sandbox/verify.json
set -eo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/scripts/env.sh"

"$ROOT/scripts/build.sh" >/dev/null

SANDBOX="$ROOT/.sandbox"
CONFIG="$SANDBOX/config"
SYSTEM="$SANDBOX/system"
PLUGINS="$SANDBOX/plugins"
LOG="$SANDBOX/log"
mkdir -p "$CONFIG" "$SYSTEM" "$PLUGINS" "$LOG"

# 首次播种：只复制许可证 key 与 options，避免整个 613MB 的插件目录。
SRC_CFG="$HOME/Library/Application Support/JetBrains/GoLand2026.2"
if [[ ! -f "$CONFIG/.seeded" ]]; then
  echo "首次播种沙盒配置（license + options）..."
  cp "$SRC_CFG/goland.key" "$CONFIG/" 2>/dev/null || true
  cp -R "$SRC_CFG/options" "$CONFIG/" 2>/dev/null || true
  touch "$CONFIG/.seeded"
fi

rm -rf "$PLUGINS/$PLUGIN_NAME"
cp -R "$BUILD/$PLUGIN_NAME" "$PLUGINS/$PLUGIN_NAME"

# 验证用的最小 Go 项目
PROJECT="${SANDBOX_PROJECT:-$SANDBOX/project}"
mkdir -p "$PROJECT"
if [[ ! -f "$PROJECT/go.mod" ]]; then
  printf 'module sandboxverify\n\ngo 1.27\n' > "$PROJECT/go.mod"
fi
if [[ ! -f "$PROJECT/verify.go" ]]; then
  cat > "$PROJECT/verify.go" <<'EOF'
package sandboxverify

// Add 返回两数之和。
// ai：实现加法，只处理 int。
func Add(a, b int) int {
	return 0
}

// Mul 返回两数之积。
// ai：实现乘法
func Mul(a, b int) int {
	return 0
}
EOF
fi

# 预授权沙盒项目，避免 "Trust project?" 模态框阻塞项目打开（否则启动活动不会执行）
mkdir -p "$CONFIG/options"
cat > "$CONFIG/options/trusted-paths.xml" <<EOF
<application>
  <component name="Trusted.Paths">
    <option name="TRUSTED_PROJECT_PATHS">
      <map>
        <entry key="$PROJECT" value="true" />
      </map>
    </option>
  </component>
</application>
EOF

rm -f "$SANDBOX/verify.json" "$SANDBOX/verify-rounds.jsonl"

VMOPTS="$SANDBOX/ide.vmoptions"
{
  echo "-Xmx1536m"
  echo "-Didea.config.path=$CONFIG"
  echo "-Didea.system.path=$SYSTEM"
  echo "-Didea.plugins.path=$PLUGINS"
  echo "-Didea.log.path=$LOG"
  echo "-Dcommentcompanion.verifyTo=$SANDBOX/verify.json"
  echo "-Dcommentcompanion.verifyRounds=$SANDBOX/verify-rounds.jsonl"
  if [[ "${DEBUG_PORT:-}" != "" ]]; then
    echo "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:$DEBUG_PORT"
  fi
} > "$VMOPTS"

echo "沙盒目录：$SANDBOX"
echo "验证项目：$PROJECT"
echo "验证输出：$SANDBOX/verify.json"
echo "IDE 日志：$LOG/idea.log"
export GOLAND_VM_OPTIONS="$VMOPTS"
exec "$GOLAND_HOME/Contents/MacOS/goland" "$PROJECT"

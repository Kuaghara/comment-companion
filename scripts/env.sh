#!/usr/bin/env bash
# 统一的环境变量与路径解析。所有脚本都先 source 这个文件。
# 注意：macOS 自带 bash 3.2，脚本里不要用 mapfile / 关联数组等 4.x 特性。
set -eo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export ROOT

# GoLand 安装位置：可用 GOLAND_HOME 覆盖。
export GOLAND_HOME="${GOLAND_HOME:-/Users/kaughara/Applications/GoLand.app}"
export JBR="${JBR:-$GOLAND_HOME/Contents/jbr/Contents/Home}"
export JAVA="$JBR/bin/java"
export JAVAC="$JBR/bin/javac"

if [[ ! -x "$JAVA" ]]; then
  echo "找不到可用的 JDK：$JAVA" >&2
  echo "请设置 GOLAND_HOME 指向 GoLand.app（例：GOLAND_HOME=/Applications/GoLand.app）" >&2
  exit 2
fi

# 工具链（首次运行 scripts/bootstrap-toolchain.sh 下载）
export TOOLCHAIN="${TOOLCHAIN:-$ROOT/.toolchain}"
export LIBS="$TOOLCHAIN/libs"

# 与 GoLand 2026.2.3 内置 Kotlin（2.4.0）对齐
export KOTLIN_VERSION="${KOTLIN_VERSION:-2.4.0}"
export COROUTINES_VERSION="${COROUTINES_VERSION:-1.10.2}"
export JUNIT_VERSION="${JUNIT_VERSION:-6.1.3}"
export ANNOTATIONS_VERSION="${ANNOTATIONS_VERSION:-26.0.2}"

# Kotlin 编译器运行所需的 classpath
export KOTLIN_COMPILER_CP="\
$LIBS/kotlin-compiler-embeddable-$KOTLIN_VERSION.jar:\
$LIBS/kotlin-stdlib-$KOTLIN_VERSION.jar:\
$LIBS/kotlin-script-runtime-$KOTLIN_VERSION.jar:\
$LIBS/kotlin-daemon-embeddable-$KOTLIN_VERSION.jar:\
$LIBS/kotlin-reflect-$KOTLIN_VERSION.jar:\
$LIBS/kotlinx-coroutines-core-jvm-$COROUTINES_VERSION.jar:\
$LIBS/annotations-$ANNOTATIONS_VERSION.jar"

export KOTLIN_STDLIB="$LIBS/kotlin-stdlib-$KOTLIN_VERSION.jar"
export JUNIT_CONSOLE="$LIBS/junit-platform-console-standalone-$JUNIT_VERSION.jar"

export BUILD="$ROOT/build"
export CLASSES="$BUILD/classes"
export TEST_CLASSES="$BUILD/test-classes"
export PLATFORM_CP_FILE="$BUILD/platform.cp"

export PLUGIN_NAME="comment-companion"
export PLUGIN_VERSION="0.1.0"

# GoLand 安装包内的平台 classpath（lib/*.jar + Go 插件 jar）。
# 结果缓存到 build/platform.cp，避免每次编译都重新遍历 400+ 个 jar。
ensure_platform_cp() {
  local marker="$BUILD/.platform-cp.stamp"
  local current
  current="$(stat -f '%m' "$GOLAND_HOME/Contents/Resources/build.txt" 2>/dev/null || echo 0)"
  if [[ -f "$PLATFORM_CP_FILE" && -f "$marker" && "$(cat "$marker" 2>/dev/null)" == "$current" ]]; then
    export PLATFORM_CP="$(cat "$PLATFORM_CP_FILE")"
    return
  fi
  mkdir -p "$BUILD"
  python3 - "$GOLAND_HOME" "$PLATFORM_CP_FILE" >/dev/null <<'PY'
import glob, sys

app, out = sys.argv[1], sys.argv[2]
jars = sorted(glob.glob(app + "/Contents/lib/*.jar"))
jars += sorted(glob.glob(app + "/Contents/plugins/go-plugin/lib/*.jar"))
with open(out, "w") as f:
    f.write(":".join(jars))
PY
  echo "$current" > "$marker"
  export PLATFORM_CP="$(cat "$PLATFORM_CP_FILE")"
}

# 检查工具链是否就绪
require_kotlin() {
  local missing=0
  for f in "$LIBS/kotlin-compiler-embeddable-$KOTLIN_VERSION.jar" "$KOTLIN_STDLIB" \
           "$LIBS/kotlin-reflect-$KOTLIN_VERSION.jar" \
           "$LIBS/kotlinx-coroutines-core-jvm-$COROUTINES_VERSION.jar" "$JUNIT_CONSOLE"; do
    [[ -f "$f" ]] || { echo "缺少工具链文件：$f" >&2; missing=1; }
  done
  if [[ "$missing" -ne 0 ]]; then
    echo "请先运行：scripts/bootstrap-toolchain.sh" >&2
    exit 2
  fi
}

# 用法：kotlin_compile <输出目录> <编译 classpath> <源文件...>
# 编译日志写到 build/kotlinc.log；失败时返回非零并让调用方退出。
kotlin_compile() {
  local out="$1"; shift
  local cp="$1"; shift
  local log="$BUILD/kotlinc.log"
  mkdir -p "$BUILD" "$out"
  set +e
  "$JAVA" -cp "$KOTLIN_COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect -jvm-target 21 \
    -classpath "$cp" -d "$out" "$@" >"$log" 2>&1
  local rc=$?
  set -e
  grep -vE '^(WARNING|warning:)' "$log" || true
  return "$rc"
}

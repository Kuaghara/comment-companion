#!/usr/bin/env bash
# 下载离线工具链：Kotlin 2.4.0 编译器 + JUnit 6 console standalone。
# 本机没有 Gradle / Maven Central 以外可用的包管理器，所以直接 curl Maven Central。
set -eo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/scripts/env.sh"

mkdir -p "$LIBS"
BASE="https://repo.maven.apache.org/maven2"

fetch() {
  local path="$1" name
  name="$LIBS/$(basename "$path")"
  if [[ -f "$name" && -s "$name" ]]; then
    echo "已存在：$(basename "$name")"
    return
  fi
  echo "下载：$(basename "$path")"
  curl -fsSL --retry 3 --max-time 600 -o "$name.part" "$BASE/$path"
  mv "$name.part" "$name"
}

fetch "org/jetbrains/kotlin/kotlin-compiler-embeddable/$KOTLIN_VERSION/kotlin-compiler-embeddable-$KOTLIN_VERSION.jar"
fetch "org/jetbrains/kotlin/kotlin-stdlib/$KOTLIN_VERSION/kotlin-stdlib-$KOTLIN_VERSION.jar"
fetch "org/jetbrains/kotlin/kotlin-script-runtime/$KOTLIN_VERSION/kotlin-script-runtime-$KOTLIN_VERSION.jar"
fetch "org/jetbrains/kotlin/kotlin-daemon-embeddable/$KOTLIN_VERSION/kotlin-daemon-embeddable-$KOTLIN_VERSION.jar"
fetch "org/jetbrains/kotlin/kotlin-reflect/$KOTLIN_VERSION/kotlin-reflect-$KOTLIN_VERSION.jar"
fetch "org/jetbrains/kotlin/kotlin-test/$KOTLIN_VERSION/kotlin-test-$KOTLIN_VERSION.jar"
fetch "org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/$COROUTINES_VERSION/kotlinx-coroutines-core-jvm-$COROUTINES_VERSION.jar"
fetch "org/jetbrains/annotations/$ANNOTATIONS_VERSION/annotations-$ANNOTATIONS_VERSION.jar"
fetch "org/junit/platform/junit-platform-console-standalone/$JUNIT_VERSION/junit-platform-console-standalone-$JUNIT_VERSION.jar"

echo
echo "工具链就绪：$LIBS"
ls -1 "$LIBS"

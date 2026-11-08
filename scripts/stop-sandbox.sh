#!/usr/bin/env bash
# 只停沙盒实例（匹配参数里的 .sandbox/ 路径），避免误杀日常 GoLand
set -eo pipefail
pids=$(ps -axo pid=,args= | grep '[.]sandbox/' | grep -v 'dev-sandbox' | awk '{print $1}')
for pid in $pids; do kill "$pid" 2>/dev/null || true; done

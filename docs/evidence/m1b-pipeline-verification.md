# M1b 端到端验证：空闲扫描 → PSI 锚定 → 串行队列 → 落笔

> 日期：2026-11-08
> 环境：GoLand 2026.2.3（GO-262.10968.67）+ JBR 25，沙盒模式
> 引擎：`FakeEngine`（确定性，不出网），真实引擎在 M2

## ① 结论

M1b 链路在真实 GoLand 里跑通：**打开项目 → 2 秒内发现 `ai：` → PSI 锚定到函数 → FakeEngine 生成 → 单命令落笔（body + 注释规范化）→ 账本记录 → 文档保存**。

验证项目（`.sandbox/project/verify.go`）处理前后：

```go
// 处理前
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
```

```go
// 处理后（磁盘）
// Add 返回两数之和。
// 实现加法，只处理 int。
func Add(a, b int) int {
	return 0
}

// Mul 返回两数之积。
// 实现乘法
func Mul(a, b int) int {
	return 0
}
```

- 描述行（`Add 返回两数之和。`）原样保留
- `ai：` 标记消失（规范化）
- 函数体被新 body 替换
- 两个函数各产生一条 `DONE` 记录：

```jsonl
{"function":"Add","stage":"DONE","detail":""}
{"function":"Mul","stage":"DONE","detail":""}
```

## ② 验证方式

`./scripts/dev-sandbox.sh` 启动隔离 GoLand，系统属性：

- `-Dcommentcompanion.verifyTo=.../verify.json`：插件加载 / Go PSI / Inlay 验证（M0 已有）
- `-Dcommentcompanion.verifyRounds=.../verify-rounds.jsonl`：每个 Round 结束时追加一行 JSON

## ③ 本轮修掉的 4 个真 bug

| # | Bug | 现象 | 修复 |
|---|---|---|---|
| 1 | `TextRange.endOffset` 是排他的，落笔时又 +1 | 注释与下一行函数被拼到同一行、body 多吃一个字符 | 替换区间用 `endOffset`（不 +1） |
| 2 | `CommentProtocol` 以注释块为单位解析，`Directive.markerLine` 是块内相对行号 | 描述行被吞掉（取成 0 行） | 锚定时换算成绝对行号，描述行用相对索引取 |
| 3 | 首次打开文档没有编辑事件 | 文件里已有的 `ai：` 永远不会被发现 | `GoFileTracker.attach` 首次见到 Go 文档即标记 dirty |
| 4 | 沙盒插件目录残留旧的 `Probe CC` 实验插件 | 日志大量 `Plugin to blame: Probe CC`，且会改坏验证文件 | 清理沙盒 plugins 目录后回归 |

## ④ 仍未覆盖（M2/M3）

- 真实模型调用（OpenAI Chat Completions / OpenAI Responses / Anthropic Messages）
- `go build ./...` 验证器与修复循环（M1b 跳过验证，`VERIFYING` 阶段未实现）
- 面板（底部三页导航）/ 状态栏 / 设置持久化 / 账本持久化
- 取消语义的完整回滚（当前 pause 只清队列）

## ⑤ 复现

```bash
cd ~/go/comment-companion
rm -rf .sandbox/project .sandbox/plugins .sandbox/log .sandbox/verify-rounds.jsonl
./scripts/dev-sandbox.sh        # 后台等待约 30 秒
cat .sandbox/verify-rounds.jsonl
cat .sandbox/project/verify.go
pkill -f "MacOS/goland .*/\.sandbox/project"
```

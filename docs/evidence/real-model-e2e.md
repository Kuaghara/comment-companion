# 真实模型端到端验证（配置来自 DSH）

> 日期：2026-11-08
> 模型：`step-5-preview`（provider `step`，协议 `anthropic-messages`，BaseURL `https://api.stepfun.com/step_plan`）
> 配置来源：`~/.dsh/profiles/desktop/cordis.patch.yml` + `~/.dsh/.credentials.yaml`

## ① 独立探针（不走 IDE）

用插件自己的 `DshConfig` + `HttpEngine` 直接调用：

```
DSH provider=step model=step-5-preview protocol=ANTHROPIC_MESSAGES key=yes
OK body={\n\treturn a + b\n} comment=[Add returns the sum of a and b.]
```

说明「AI 从 DSH 拿配置」在引擎层成立。

## ② 沙盒端到端（真插件 + 真模型）

命令：`SANDBOX_REAL_ENGINE=1 ./scripts/dev-sandbox.sh`

Round 结果（含文档快照）：

- Add DONE：body `return a + b`，注释规范化为 `// Add 返回两数之和。`
- Mul DONE：body `return a * b`，注释规范化为 `// Mul 返回两数之积。`

最终文档（DONE 时的 Document 快照）：

```go
package sandboxverify

// Add 返回两数之和。
func Add(a, b int) int {
	return a + b
}

// Mul 返回两数之积。
func Mul(a, b int) int {
	return a * b
}
```

- `ai：` 标记全部消失
- go build 验证通过（attempts=1）
- `Plugin to blame: Comment Companion` = 0

## ③ 本轮修的问题

| # | 问题 | 修复 |
|---|---|---|
| 1 | HTTP 400 `model is required` | 请求里用的是插件设置的空 model；改为 `EngineFactory.resolve()` 返回 DSH 的 effective config，请求使用它；HttpEngine 里再加 `request.model.ifBlank { config.model }` 兜底 |
| 2 | 模型把描述行重复写进 comment，导致注释重复两行 | 规范化时 `(description + modelComment).distinct()` |
| 3 | 磁盘上 Mul 注释看起来没更新（实际是 autosave 未触发） | 用 Document 快照验证；后续可考虑收尾后主动保存 |

## ④ 备注

- 沙盒默认仍强制 FakeEngine（`SANDBOX_REAL_ENGINE=1` 才走真实模型），避免测试误烧 token
- 真实 API 已有一次完整成功记录；后续 CI 不建议跑真实模型

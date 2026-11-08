# v0.1.0 — GoLand 注释驱动编码伴侣（MVP）

发布日期：2026-11-08
适用环境：GoLand 2026.2.x（开发与验证环境：2026.2.3 / build GO-262.10968.67），macOS

## 这是什么

在 Go 函数上方写注释，AI 直接改代码——没有聊天窗口，交流只发生在注释里：

```go
// Add 返回两数之和。
// ai：实现加法，只处理 int
func Add(a, b int) int {
	return 0
}
```

保存后 2 秒内，插件会：扫描 → 锚定函数 → 调模型生成函数体 → `go build` 验证（可切 daemon 模式）→ 失败自动修复（≤5 轮）→ 成功后把 `ai：` 注释规范化成普通 doc comment。

AI 配置**默认从 DeepSeek Harness（DSH）读取**（`~/.dsh` 的 profile + credentials），插件内无需另填 Key。

## 功能

- `ai：` / `ai？` 注释协议（全角/半角、块注释、续行、允许 0–1 空行）
- 2 秒空闲扫描 + 项目内串行队列；PSI 精确锚定函数；最小 diff 落笔
- 三种引擎协议：OpenAI Chat Completions / OpenAI Responses / Anthropic Messages
- 默认从 DSH 读模型配置；也支持插件内 BYOK（PasswordSafe）
- 验证：`go build ./...`（默认）或 GoLand daemon 诊断（可切换）
- 修复循环、反向 plan 回滚、崩溃 Journal
- `ai？` 提问：写成函数体后的独立注释块，面板/通知提示；用户改 `ai：` 后重新生成并自动清理问题注释
- 模型建议的 imports 自动补齐（PSI addImport）
- 侧边窗口：仪表盘（圆形运行/暂停 + 状态 + 待办）、详细情况（当前任务/待处理/队列/历史）、设置
- 状态栏：队列数量与暂停状态；设置与账本持久化；信任级别（SAFE 拒绝自动 import）

## 安装

1. 下载本 Release 的 `comment-companion-0.1.0.zip`
2. GoLand：`Settings | Plugins | Install Plugin from Disk…` 选择该 zip，重启
3. 确认 `~/.dsh` 已有模型配置（默认 `step / step-5-preview / anthropic-messages`）；或在插件设置里改为「引擎来源 = 插件配置」
4. 在 Go 函数上方写 `// ai：...`，保存后等待

## 从源码构建

```bash
./scripts/bootstrap-toolchain.sh   # 首次：下载离线 Kotlin/JUnit 工具链
./scripts/test.sh                  # 79 个单测
./scripts/build.sh                 # build/comment-companion-0.1.0.zip
./scripts/dev-sandbox.sh           # 隔离沙盒启动 GoLand（不碰日常配置）
```

`SANDBOX_REAL_ENGINE=1 ./scripts/dev-sandbox.sh` 可让沙盒走真实模型（会消耗 API 配额）。

## 验证情况

- 单测：79 通过
- 架构检查：core/ports 依赖方向通过
- 沙盒假引擎端到端：Add/Mul DONE、注释规范化、落盘、面板显示、0 插件异常
- 真实模型（DSH `step-5-preview`）：Add/Mul 生成 `a+b` / `a*b`，编译通过，注释规范化
- imports 真实模型：`Format` 自动补 `import "strconv"` 并编译通过
- daemon 验证模式：坏 body 被诊断捕获并自动修复

证据文档见 `docs/evidence/`，验收记录见 `docs/acceptance-mvp.md`。

## 已知限制

- 单文件 MVP：不支持跨文件编辑，明确拒绝修改函数签名
- 尚未接入 Ayane（`EnginePort` 已预留替换边界）
- 未在“日常 GoLand 实例”安装验证，只在隔离沙盒验证
- 真实模型只覆盖少量样例（复杂函数/长文件/极端输出需实际使用验证）
- 跨平台：开发环境为 macOS，Windows/Linux 未测试

## 许可

[MIT](../LICENSE) © 2026 Kuaghara

# Comment Companion（GoLand 插件）

[English](README.md) | **简体中文**

注释驱动的 Go 编码伴侣：在函数上方写 `// ai：...`，AI 读取注释、改函数体，完成后把指令注释规范化成普通 Go doc comment。没有聊天窗口，交流只发生在注释里。

最初作为 [Ayane](docs/comment-companion-mvp-design.md) 的编码 companion 能力打造，也可以独立使用。

---

## 当前状态

**v0.1.0 — MVP 完成**，面向 GoLand 2026.2.x 的单文件、函数级编辑。

| 模块 | 状态 |
|---|---|
| 注释协议（`ai：` / `ai？`、全角半角、块注释、续行、CRLF） | ✅ 79 个测试 |
| 2 秒空闲扫描 + 串行队列（坐标 + 指纹） | ✅ |
| PSI 锚定（注释 → 上方函数/方法，允许 0–1 空行） | ✅ |
| 最小 diff 落笔（body → 验证 → 收尾注释规范化） | ✅ |
| 三种引擎协议：OpenAI Chat Completions / OpenAI Responses / Anthropic Messages | ✅ |
| **默认从 DSH 读配置**（`~/.dsh` profile + credentials） | ✅ 真实模型验证 |
| 验证：`go build ./...`（默认）或 GoLand daemon 诊断 | ✅ 两种模式 |
| 修复循环（≤5 轮/60s）+ 反向 plan 回滚 + 崩溃 Journal | ✅ |
| `ai？` 问题流：独立注释块 → 面板/通知 → 回答 → 重新生成 → 清理 | ✅ |
| 模型建议的 imports 自动补齐（PSI `addImport`） | ✅ 真实模型验证 |
| 侧边窗口：仪表盘（运行/暂停 + 状态 + 待办）/ 详细情况 / 设置 | ✅ |
| 状态栏、设置持久化、账本持久化、信任级别 | ✅ |

细节与证据：[docs/acceptance-mvp.md](docs/acceptance-mvp.md) · [docs/evidence/](docs/evidence/)

---

## 安装

1. 从 [Releases](https://github.com/Kuaghara/comment-companion/releases) 下载 `comment-companion-0.1.0.zip`
2. GoLand：`Settings | Plugins | Install Plugin from Disk…` 选择 zip，重启
3. 默认读取 `~/.dsh`（DeepSeek Harness）里的模型配置；如果想在插件内配置，把设置页「引擎来源」改为 **插件配置**，再填协议 / Base URL / 模型 / API Key

环境要求：GoLand 2026.2.x（开发环境为 2026.2.3，build `GO-262.10968.67`），macOS（Windows/Linux 未测试）。

---

## 用法

```go
// Add 返回两数之和。
// ai：实现加法，只处理 int
func Add(a, b int) int {
	return 0
}
```

保存（IntelliJ 会自动保存）。约 2 秒内插件会：扫描 → 锚定 `Add` → 调模型生成函数体 → 最小 diff 落笔 → `go build ./...` 验证（失败自动修复）→ 把注释规范化：

```go
// Add 返回两数之和。
func Add(a, b int) int {
	return a + b
}
```

打开 **Comment Companion** 侧边窗口可以看到队列、当前任务、待处理项和设置。

---

## 注释协议

| 形式 | 含义 | 位置规则 |
|---|---|---|
| `// ai：...` / `// ai: ...` | 用户指令 | `//` 注释块里，标记必须在某行内容开头；标记行之上是方法描述（保留），之下是指令续行 |
| `// ai？...` / `// ai? ...` | AI 反问 | 同上 |
| `/* ... */` 块注释 | 指令 | 标记必须在块的第一个非空内容行开头 |

- 标记大小写不敏感，冒号/问号支持全角半角，`ai` 与标点之间允许空白，`//` 后允许没有空格
- 规范化后标记**必须完全消失**——这是防重复触发的结构性保证（Aider 的 `AI?` 残留、pi-watcher 的误删都源于标记清理不可靠）
- 指令段之外的注释行（方法描述）在规范化时原样保留

---

## 从源码构建

```bash
./scripts/bootstrap-toolchain.sh   # 首次：下载离线 Kotlin/JUnit 工具链（约 70MB）
./scripts/test.sh                  # 79 个单测 + 架构检查
./scripts/build.sh                 # -> build/comment-companion-0.1.0.zip
./scripts/dev-sandbox.sh           # 隔离沙盒启动 GoLand（不污染日常配置）
```

沙盒默认使用假引擎；`SANDBOX_REAL_ENGINE=1 ./scripts/dev-sandbox.sh` 会走 DSH 里的真实模型（消耗 API 配额）。

无需系统 JDK/Gradle/Maven：脚本使用 GoLand 自带 JBR，首次运行从 Maven Central 下载 Kotlin。

---

## 目录结构

```
comment-companion/
├── src/main/kotlin/dev/commentcompanion/
│   ├── core/          # 纯 Kotlin 领域层（protocol/model/plan/queue/policy/prompt）
│   ├── ports/         # 能力接口（workspace/engine/verifier/settings/ledger…）
│   ├── app/           # 编排层（扫描周期、Round 执行器、controller）
│   ├── ide/           # GoLand 适配（PSI、Inlay、面板、设置、验证器）
│   ├── engine/        # HTTP 引擎（OpenAI / Anthropic）与假引擎
│   └── plugin/        # 沙盒验证用旧入口
├── scripts/           # 离线构建/测试/沙盒脚本（兼容 macOS bash 3.2）
├── tools/             # 编译期平台探针（不打包）
├── docs/              # 设计、架构、证据、发布说明
└── build.gradle.kts   # 未来标准构建（本机未启用）
```

---

## 文档

- 设计：[docs/comment-companion-mvp-design.md](docs/comment-companion-mvp-design.md)
- 结构：[docs/architecture/structure-design.md](docs/architecture/structure-design.md)
- 验收：[docs/acceptance-mvp.md](docs/acceptance-mvp.md)
- 证据：[docs/evidence/](docs/evidence/) — GoLand 环境、真实模型端到端、imports、daemon 验证、问题流、同类项目审查
- 发布说明：[docs/releases/v0.1.0.md](docs/releases/v0.1.0.md)

---

## 已知限制

- 单文件 MVP：不支持跨文件编辑；修改函数签名会被明确拒绝
- Ayane 接入尚未完成（`EnginePort` 是替换边界）
- 只在隔离沙盒验证过，尚未安装进日常 GoLand 实例
- 真实模型只覆盖少量样例（简单 int/string 函数）；复杂代码、大文件、极端输出需要实际使用验证
- 目前仅支持 macOS，Windows/Linux 未测试

---

## 许可证

[MIT](LICENSE) © 2026 Kuaghara

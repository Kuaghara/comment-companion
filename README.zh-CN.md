# Comment Companion

> **当前状态：MVP（v0.1.0）** —— 面向 GoLand 2026.2.x 的**单文件、函数级**编辑。
> 跨文件编辑与 Ayane 接入尚未实现，见[路线图](#路线图)。

[English](README.md) | **简体中文**

注释驱动的 Go 编码伴侣：在函数上方写 `// ai：...`，AI 直接改函数体——没有聊天窗口，交流只发生在注释里。成功后会把指令注释规范化成普通 Go doc comment。

是 [Ayane](https://github.com/ayane-ai) 项目的一部分，也可以独立使用。

```go
// 处理前
// Add 返回两数之和。
// ai：实现加法，只处理 int
func Add(a, b int) int {
	return 0
}

// 约 2 秒后
// Add 返回两数之和。
func Add(a, b int) int {
	return a + b
}
```

**功能** · [安装](#安装) · [用法](#用法) · [配置](#配置) · [工作原理](#工作原理) · [路线图](#路线图) · [文档](#文档)

---

## 功能

- **注释即交互，没有聊天窗**：`ai：` 指令、`ai？` 提问都在代码里，编辑发生在你正在看的地方
- **自动、空闲触发**：每 2 秒扫描，只处理改动文件，且只在队列为空时动手
- **PSI 精确定位**：注释锚定到所属 Go 函数/方法，只改函数体，最小 diff
- **写完先验证**：`go build ./...`（或 GoLand daemon 诊断），失败自动修复（≤5 轮 / 60s）
- **失败可恢复**：反向 plan 回滚、崩溃 Journal、一次撤销本轮
- **默认用 DSH 的模型配置**：直接读 `~/.dsh`，不用把 Key 复制到插件；也支持插件内 BYOK
- **三种协议**：OpenAI Chat Completions / OpenAI Responses / Anthropic Messages
- **反问流程**：模型需要澄清时写一条函数体后的 `ai？` 注释；修改原 `ai：` 注释即可继续，问题注释会自动清理
- **自动补 import**：生成的函数体需要新包时，通过 PSI `addImport` 补上
- **侧边窗口**：仪表盘（运行/暂停、状态、待办）、详细情况（当前轮次、队列、历史）、设置；另有状态栏入口

---

## 安装

1. 从 [Releases](https://github.com/Kuaghara/comment-companion/releases) 下载 `comment-companion-0.1.0.zip`
2. GoLand：`Settings | Plugins | Install Plugin from Disk…` 选择 zip，重启
3. 默认读取 `~/.dsh`（DeepSeek Harness）的模型配置；想用自有服务时，在插件设置里把 **引擎来源** 改为 **插件配置**

环境要求：GoLand 2026.2.x（开发环境 2026.2.3，build `GO-262.10968.67`），macOS（Windows/Linux 未测试）。

---

## 用法

1. 在 Go 函数上方写注释：

   ```go
   // Add 返回两数之和。
   // ai：实现加法，只处理 int
   func Add(a, b int) int {
   	return 0
   }
   ```

2. 保存（IDE 自动保存）。约 2 秒内完成扫描、生成、验证和注释规范化
3. 打开 **Comment Companion** 侧边窗口查看队列、当前任务和待处理项
4. 如果 AI 反问（`ai？`），修改原来的 `ai：` 注释来回答，下一轮会继续

---

## 配置

| 配置 | 位置 |
|---|---|
| 引擎来源 | `DSH`（默认）或 `Plugin` —— 插件设置页的「引擎来源」 |
| 模型配置（DSH） | `~/.dsh/profiles/<profile>/cordis.patch.yml` + `~/.dsh/.credentials.yaml` |
| 模型配置（插件） | 设置页：协议 / Base URL / 模型 / API Key（存 PasswordSafe） |
| 验证方式 | `go build ./...`（默认）或 GoLand daemon 诊断 |
| 扫描间隔 / 重试 / 信任级别 | 设置页 |

DSH 配置是**只读**的：插件解析当前 profile 的 provider、协议、BaseURL 和模型，并从 credentials 取 Key。

---

## 工作原理

```
  每 2 秒（仅空闲）
        │
        ▼
  扫描改动文件 ──► 文本识别 `ai：` ──► 串行队列
                                         │
                          PSI 锚定 ◄─────┘
                               │
                      快照（版本 + 哈希）
                               │
                         engine.generate()
                               │
             落笔函数体 ──► go build ──► 修复循环
                               │
                    规范化注释（收尾阶段）
                               │
                         账本 + 面板
```

设计文档：[docs/architecture/structure-design.md](docs/architecture/structure-design.md)

---

## 从源码构建

```bash
./scripts/bootstrap-toolchain.sh   # 首次：下载离线 Kotlin + JUnit 工具链
./scripts/test.sh                  # 79 个单测 + 架构检查
./scripts/build.sh                 # -> build/comment-companion-0.1.0.zip
./scripts/dev-sandbox.sh           # 隔离沙盒启动 GoLand
```

沙盒默认使用确定性的假引擎；`SANDBOX_REAL_ENGINE=1 ./scripts/dev-sandbox.sh` 会走 DSH 里的真实模型（消耗 API 配额）。

无需系统 JDK / Gradle / Maven：脚本使用 GoLand 自带的 JBR。

---

## 注释协议

| 形式 | 含义 | 位置规则 |
|---|---|---|
| `// ai：...` / `// ai: ...` | 用户指令 | `//` 注释块里，标记必须在某行内容开头；标记行之上是方法描述（保留），之下是指令续行 |
| `// ai？...` / `// ai? ...` | AI 反问 | 同上 |
| `/* ... */` | 指令 | 标记必须在块的第一个非空内容行开头 |

- 标记大小写不敏感，冒号/问号支持全角半角，空白可选
- 规范化后标记**必须完全消失**——这是防重复触发的结构性保证
- 指令段之外的注释行（方法描述）在规范化时原样保留

---

## 目录结构

```
comment-companion/
├── src/main/kotlin/dev/commentcompanion/
│   ├── core/      # 纯 Kotlin：注释协议、领域模型、计划、队列、策略、prompt
│   ├── ports/     # 能力接口（workspace/engine/verifier/settings/ledger）
│   ├── app/       # 编排层：扫描周期、Round 执行器、controller
│   ├── ide/       # GoLand 适配：PSI、Inlay、面板、设置、验证器
│   ├── engine/    # HTTP 引擎（OpenAI/Anthropic）+ 假引擎
│   └── plugin/    # 沙盒验证用旧入口
├── scripts/       # 离线构建 / 测试 / 沙盒脚本
├── tools/         # 编译期平台探针（不打包）
└── docs/          # 设计、架构、证据、发布说明
```

---

## 路线图

- [x] 单文件 MVP：扫描 → 生成 → 验证 → 规范化
- [x] DSH 配置、三种协议、侧边窗口、问题流、imports
- [ ] 跨文件上下文（读取相关文件提升生成质量）
- [ ] 同包其他文件的声明（struct 字段、helper）
- [ ] 签名变更 + 调用点更新（带确认）
- [ ] Ayane 接入（`EnginePort` 后端切换）
- [ ] 信任级别面板细化、历史筛选

---

## 文档

- 设计：[docs/comment-companion-mvp-design.md](docs/comment-companion-mvp-design.md)
- 结构：[docs/architecture/structure-design.md](docs/architecture/structure-design.md)
- 验收：[docs/acceptance-mvp.md](docs/acceptance-mvp.md)
- 证据：[docs/evidence/](docs/evidence/) —— GoLand 环境、真实模型端到端、imports、daemon 验证、问题流、同类项目审查
- 发布说明：[docs/releases/v0.1.0.md](docs/releases/v0.1.0.md)

---

## 已知限制

- 单文件 MVP：不支持跨文件编辑；修改函数签名会被拒绝
- 只在隔离沙盒验证过，尚未安装进日常 GoLand 实例
- 真实模型只覆盖少量样例；复杂代码与大文件需要实际使用验证
- 目前仅支持 macOS

---

## 许可证

[MIT](LICENSE) © 2026 Kuaghara

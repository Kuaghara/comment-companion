# Comment Companion（GoLand 插件）

注释驱动的 Go 编码伴侣：在函数上方写 `// ai：...`，AI 读取注释、改函数体，完成后把注释规范化成普通 Go doc comment。没有聊天窗口，交流只发生在注释里。是 [Ayane](docs/comment-companion-mvp-design.md) 的编码 companion 能力的第一具身体。

## 当前状态

| 模块 | 状态 |
|---|---|
| Kotlin 离线工具链（JBR 25 + Kotlin 2.4.0 + JUnit 6） | ✅ 可用 |
| 注释协议解析（`ai：` / `ai？`、全角/半角、块注释、续行、CRLF） | ✅ 78 个测试通过 |
| 注释规范化（只改指令段、标记必然消失、防再次触发） | ✅ 已实现并测试 |
| 空闲窗口 `IdleGate`（1–1.5s debounce 的纯逻辑） | ✅ 已实现并测试 |
| 平台/Go PSI 的 Kotlin 互操作探针 | ✅ `scripts/check-platform.sh` 通过 |
| 插件加载（plugin.xml 被 GoLand 接受） | ✅ 2026-10-18 沙盒实测（`Loaded custom plugins: Comment Companion (0.1.0)`） |
| Go PSI 运行时（`GoFile.functions` / `getBlock()`） | ✅ 沙盒实测（Add/Mul 各带函数体） |
| Inlay 运行时（加 / 销毁） | ✅ 沙盒实测（`added-and-disposed`） |
| core 领域模型（FunctionKey/Snapshot/EditPlan/RoundStage/RoundRecord/Question） | ✅ 已实现并测试 |
| 串行工作队列（坐标+指纹、去重、空闲判定） | ✅ 已实现并测试 |
| 端口接口（Workspace/Feedback/Engine/Verifier/Settings/Ledger/Scheduler） | ✅ 已定义 |
| 架构守则检查（core/ports 不得依赖平台/上层） | ✅ 已接入 `scripts/test.sh` |
| 2 秒空闲扫描（dirty 追踪 + 只在空闲时审查） | ✅ M1b 沙盒验证 |
| PSI 锚定（注释 → 上方函数，允许 0–1 空行） | ✅ M1b 沙盒验证 |
| 串行队列 + FakeEngine 端到端（生成→落笔→规范化→记账） | ✅ M1b 沙盒验证 |
| 三种引擎协议（OpenAI Chat Completions / OpenAI Responses / Anthropic Messages） | ✅ M2a（HTTP 适配 + prompt/response 编解码） |
| **从 DSH 读模型配置（默认）**：provider/model/协议/BaseURL/Key | ✅ 沙盒验证（step / step-5-preview / anthropic） |
| 设置持久化（`comment-companion.xml`）+ API Key（PasswordSafe） | ✅ M2a |
| `go build ./...` 验证 + baseline 差集 | ✅ M2b 沙盒验证 |
| 修复循环（≤5 轮/60s，新错误回填） | ✅ M2b 沙盒验证（attempts=2 修复成功） |
| 注释规范化作为收尾阶段（第二次落笔） | ✅ M2b 沙盒验证 |
| 取消回滚（已落笔则反向 plan） | ✅ 已实现，待面板联调 |
| 侧边窗口：底部三页导航（仪表盘 / 详细情况 / 设置） | ✅ M3a 沙盒验证（toolWindow shown） |
| 状态栏 widget（队列数/暂停态） | ✅ M3a |
| 账本持久化（`.idea/comment-companion-ledger.xml`，JSON 存储） | ✅ M3a |
| `ai？` 问题状态机（WAITING_ANSWER / 面板待办 / 扫描去重） | ✅ M3b 沙盒验证（注释落盘暂缓） |
| `ai？` 注释写入文件（函数体后独立注释块） | ✅ 沙盒验证 |
| 回答闭环：改指令 → 重新生成 → 清理 `ai？` | ✅ 沙盒验证（FAKE_ANSWER） |
| 崩溃 Journal（落笔前记录 + 启动回滚） | ✅ 已实现（未做杀进程验证） |
| StartupActivity → ProjectActivity 迁移（去掉 deprecated 警告） | ✅ |
| daemon-only 验证模式（GoLand 诊断，可切换） | ✅ 沙盒验证（VERIFY_FAILED → 修复 DONE） |

## M0 真机验证（2026-10-18，已通过）

`./scripts/dev-sandbox.sh` 会在 `.sandbox/` 下起一个隔离的 GoLand（复用日常配置里的 license 与 options，插件目录/系统目录/日志目录全部隔离），打开内置的验证项目，插件的 `VerifyStartupActivity` 写入 `.sandbox/verify.json`：

```json
{
  "plugin": "loaded",
  "project": "project",
  "goFile": ".sandbox/project/verify.go",
  "psiClass": "com.goide.psi.GoFile",
  "textDirectives": ["实现加法，只处理 int。", "实现乘法"],
  "goFunctionNames": ["Add", "Mul"],
  "goFunctionsWithBody": 2,
  "goMethods": 0,
  "editor": "opened",
  "inlay": "added-and-disposed"
}
```

结论：plugin.xml 依赖声明被接受、Go PSI 在第三方插件里可用、注释协议在真实 PSI 文本上工作、编辑器与 Inlay 可用。
细节与踩坑见 [m0-sandbox-verification.md](docs/evidence/m0-sandbox-verification.md)。

## 环境要求

- macOS（本机为 GoLand 2026.2.3，build `GO-262.10968.67`，自带 JBR 25）
- 无需系统 JDK / Gradle / Maven：脚本直接用 GoLand 自带 JBR 的 `java`
- 首次运行会自动从 Maven Central 下载 Kotlin 工具链（约 70 MB）

## 常用命令

```bash
# 首次：下载离线工具链（写入 .toolchain/）
./scripts/bootstrap-toolchain.sh

# 跑全部单元测试（编译 main + test，JUnit console）
./scripts/test.sh

# 只编译 main
./scripts/compile-main.sh

# 检查 Kotlin 与 GoLand 平台 / Go PSI 的互操作
./scripts/check-platform.sh

# 打包插件（build/comment-companion-0.1.0.zip）
./scripts/build.sh

# 隔离沙盒启动 GoLand 并加载插件（DEBUG_PORT=5005 可开 JDWP）
./scripts/dev-sandbox.sh
```

安装到日常 GoLand：把 `build/comment-companion/` 整个目录拷进
`~/Library/Application Support/JetBrains/GoLand2026.2/plugins/`，或 `Settings | Plugins | Install Plugin from Disk` 选 zip，然后重启 GoLand。

## 目录结构

```
comment-companion/
├── scripts/                  # 离线构建/测试/沙盒脚本（bash，兼容 macOS bash 3.2）
├── src/main/kotlin/dev/commentcompanion/
│   ├── core/                 # 纯 Kotlin 领域层（protocol/model/queue/policy/prompt）
│   ├── ports/                # 能力接口（Workspace/Engine/Verifier/Settings/Ledger…）
│   └── plugin/               # 现有 GoLand 入口（M1b 将迁入 ide/）
├── src/main/resources/META-INF/plugin.xml
├── src/test/kotlin/          # JUnit 5 测试
├── tools/platform-probe/     # 只用于编译期平台兼容性检查，不打包
├── build.gradle.kts          # 未来标准构建（本机 Gradle 不可下载，暂未启用）
└── .toolchain/               # 下载的 Kotlin/JUnit jar（gitignore）
```

## 注释协议（v0）

| 形式 | 含义 | 位置规则 |
|---|---|---|
| `// ai：...` / `// ai: ...` | 用户指令 | `//` 注释块里，标记必须在某行内容开头；标记行之上是方法描述（保留），之下是指令续行 |
| `// ai？...` / `// ai? ...` | AI 反问 | 同上 |
| 斜杠星号块注释 | 指令 | 标记必须在块的第一个非空内容行开头 |

- 标记大小写不敏感，冒号/问号支持全角半角，`ai` 与标点之间允许空白，`//` 后允许没有空格。
- 规范化后标记必须完全消失——这是防重复触发的结构性保证（Aider 的 `AI?` 残留、pi-watcher 的误删都源于标记由模型/粗粒度删除收尾）。
- 指令段之外的注释行（方法描述）在规范化时原样保留。

## 证据与设计文档

- 设计：[comment-companion-mvp-design.md](docs/comment-companion-mvp-design.md)
- 结构（v2 已对齐）：[structure-design.md](docs/architecture/structure-design.md)
- M1b 端到端验证：[m1b-pipeline-verification.md](docs/evidence/m1b-pipeline-verification.md)
- M2b 验证/修复验证：[m2b-verification-repair.md](docs/evidence/m2b-verification-repair.md)
- M3a 面板/状态栏验证：[m3a-panel-verification.md](docs/evidence/m3a-panel-verification.md)
- M3b 问题状态机：[m3b-question-flow.md](docs/evidence/m3b-question-flow.md)
- daemon 验证模式：[daemon-verifier.md](docs/evidence/daemon-verifier.md)
- DSH 配置来源：[dsh-config-source.md](docs/evidence/dsh-config-source.md)
- GoLand 环境与可行性：[goland-2026.2-environment.md](docs/evidence/goland-2026.2-environment.md)
- Aider `--watch-files` 源码审查：[aider-watch-files.md](docs/evidence/aider-watch-files.md)
- pi-watcher 源码审查：[pi-watcher.md](docs/evidence/pi-watcher.md)
- GoLand 参考插件审查：[goland-plugin-refs.md](docs/evidence/goland-plugin-refs.md)

## 已知约束

- 本机 Gradle distribution 不可下载（`services.gradle.org` / `downloads.gradle.org` 均超时），所以 v1 采用 Kotlin + `javac` 风格直编脚本；`build.gradle.kts` 留作后续迁移。
- Kotlin 版本对齐 GoLand 内置 Kotlin 2.4.0；运行时 `kotlin-stdlib` 由平台提供，插件 jar 不重复打包。
- 插件尚未包含任何 action/extension，M0 会加第一个「扫描当前文件并高亮 `ai：`」的诊断入口来验证加载。

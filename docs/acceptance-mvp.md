# MVP 验收记录

> 日期：2026-11-08
> 目标：注释驱动的 GoLand 编码伴侣；AI 配置默认从 DSH（DeepSeek Harness）读取。

## ① 验收结论

**MVP 开发完成**。核心闭环、面板、配置、问题流、验证与恢复均已实现，并在沙盒与真实模型中验证。

## ② 功能清单

| 模块 | 状态 |
|---|---|
| 注释协议 `ai：`/`ai？`（全角/半角、块注释、续行、0–1 空行） | ✅ |
| 2 秒空闲扫描 + 串行队列（坐标+指纹） | ✅ |
| PSI 锚定 → 函数快照 → 生成 → 落笔（最小 diff） | ✅ |
| 真实模型：三种协议（OpenAI Chat/Responses、Anthropic） | ✅ |
| **配置默认从 DSH 读取**（profile + credentials，无需插件内填 Key） | ✅ |
| go build 验证 + 修复循环（≤5 轮/60s）+ 反向 plan 回滚 | ✅ |
| daemon-only 验证模式（可切换） | ✅ |
| 注释规范化（收尾阶段，`ai：` 结构性消失） | ✅ |
| `ai？` 提问 → 面板/通知 → 回答 → 重新生成 → 清理 | ✅ |
| imports 自动补齐（PSI addImport） | ✅ 真实模型验证 |
| 崩溃 Journal（落笔前意图 + 启动回滚） | ✅ |
| 侧边窗口三页导航 + 待处理跳转 + 历史展开 | ✅ |
| 状态栏（队列数/暂停态） | ✅ |
| 信任级别（SAFE 拒绝自动 import；BALANCED/TRUSTED 允许） | ✅ |
| 设置持久化（XML）+ API Key（PasswordSafe，仅 PLUGIN 模式） | ✅ |
| 账本持久化（`.idea/comment-companion-ledger.xml`） | ✅ |

## ③ 验收证据

| 证据 | 结果 |
|---|---|
| `./scripts/test.sh` | **79 tests / 0 failures** |
| `./scripts/check-architecture.sh` | core/ports 依赖方向通过 |
| 沙盒假引擎端到端 | Add/Mul DONE、注释规范化、磁盘已落盘、toolWindow shown、0 插件异常 |
| 真实模型（DSH step-5-preview） | Add/Mul 生成 `a+b`/`a*b`、go build 通过、注释规范化、0 插件异常（real-model-e2e.md） |
| imports 真实模型 | `Format` 自动补 `import "strconv"` 并编译通过（imports-and-guards.md） |
| 问题生命周期 | QUESTION → 回答 → DONE → `ai？` 清理（m3b-question-flow.md） |
| daemon 验证 | 坏 body 被 daemon 诊断捕获并修复（daemon-verifier.md） |
| DSH 配置解析 | provider/model/协议/BaseURL/Key 正确（dsh-config-source.md） |

## ④ 使用方式

1. `./scripts/build.sh` → `build/comment-companion-0.1.0.zip`
2. GoLand：Settings → Plugins → Install Plugin from Disk（或拷贝到 `plugins/`），重启
3. 默认从 `~/.dsh` 读模型配置（provider=step / model=step-5-preview / anthropic-messages）
4. 在函数上方写 `// ai：<指令>`，IDE 自动保存后 2 秒内空闲扫描 → 生成/验证/规范化
5. 侧边窗口「Comment Companion」：队列 / 当前任务 / 待处理 / 设置
6. 需要插件自配时：设置页「引擎来源 = 插件配置」，填协议/BaseURL/模型/Key

## ⑤ 已知边界（P1 后续）

- 跨文件调用点更新 / 签名变更（MVP 明确拒绝改签名）
- Ayane 接入（EnginePort 后端切换 + contract 映射）
- 大文件限制、多语言、面板历史筛选等细节
- 沙盒真实模型测试会消耗 API 配额（默认 fake，`SANDBOX_REAL_ENGINE=1` 才走真实）

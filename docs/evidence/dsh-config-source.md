# 从 DSH 读取模型配置

> 日期：2026-11-08
> 目标：插件默认不再要求用户单独填 API Key / Base URL / 模型，而是直接复用 DeepSeek Harness 的配置。

## ① 读取对象

| 文件 | 用途 |
|---|---|
| `~/.dsh/profiles/<profile>/cordis.patch.yml` | `agent-default-model`（provider/model）+ `llm-pi-ai.config.providers.<provider>`（api / baseURL / apiKeyEnv） |
| `~/.dsh/.credentials.yaml` | `refs.<ENV>` 里的密钥（例如 `STEP_API_KEY`） |

- 默认 profile 优先 `profiles/desktop/cordis.patch.yml`，否则取任意 profile
- 协议映射：`anthropic-messages` → Anthropic；`openai-completions` → Chat Completions；`openai-responses` → Responses
- 密钥只读进内存，不写日志、不落插件配置

## ② 结构

- `ide/DshConfig.kt`：`DshEngineConfig(provider, model, protocol, baseUrl, apiKeyEnv, apiKey)` + 纯函数解析
- `ports/EngineSource`：`DSH`（默认）/ `PLUGIN`
- `EngineFactory`：`DSH` → `DshConfig.resolve()` → `HttpEngine`；解析失败或未配置 → 回落到插件设置/FakeEngine
- 设置页新增「引擎来源」下拉；`CompanionSettings.State.engineSource` 持久化
- 沙盒默认注入 `-Dcommentcompanion.engine=fake`，避免误打真实 API（`SANDBOX_REAL_ENGINE=1` 可关闭）

## ③ 沙盒验证

`verify.json`：

```json
{
  "dshProvider": "step",
  "dshModel": "step-5-preview",
  "dshProtocol": "ANTHROPIC_MESSAGES",
  "dshBaseUrl": "https://api.stepfun.com/step_plan",
  "dshKeyConfigured": true
}
```

- 面板/状态栏正常，Add/Mul 两轮 DONE
- `Plugin to blame: Comment Companion` = 0
- 单测 77 全绿（含 DSH 解析 7 个用例）

## ④ 注意

- 真实 API 尚未联调：沙盒强制 fake；正式使用取消 fake 属性即可走 DSH 的 provider（当前是 `step` 的 `step-5-preview`，协议 anthropic-messages）
- DSH 配置格式若变化，解析会返回 null 并回落到插件设置，不会崩溃

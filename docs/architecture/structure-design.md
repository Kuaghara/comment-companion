# Comment Companion 结构设计（v2，已对齐版）

> 状态：与用户逐条对齐后的结构定稿；`core/`、`ports/` 已按此落地（56 个测试通过），`app/`、`ide/`、`engine/` 在 M1b/M2 实现。
> 依据：`docs/comment-companion-mvp-design.md`、`docs/evidence/*`、本轮 12+10+3 条批注的结论。

---

## 0. 决策记录（本稿与旧草案的差异）

| # | 决策 | 结论 |
|---|---|---|
| D1 | 触发方式 | **不再用文档事件/去抖**；每 2 秒 tick，仅维护 dirty 文件集合；**只在队列为空（空闲）时**做审查扫描 |
| D2 | 扫描范围 | 只扫「当前打开着的 Go 文档」（MVP） |
| D3 | 状态机 | `RoundStage` 独立枚举，迁移表在 core，非法迁移抛异常 |
| D4 | Round | Round 是聚合根（一轮 = 快照→生成→落笔→验证→规范化→记账） |
| D5 | 注释来源 | **模型一并产出规范化注释**；缺失即本轮失败（不做机械兜底） |
| D6 | 回滚 | 反向 plan（快照区间恢复，确定性、不依赖 AI）+ `RoundJournal` 崩溃恢复 |
| D7 | 验证 | `go build ./...`（整模块），做成设置开关；关闭后走 daemon 诊断 |
| D8 | 失败重试 | **自动重试 1 次**，再失败需改指令或手动重试（同一 instructionHash 不自动循环） |
| D9 | 账本主键 | 函数级 `FunctionKey`（含 `languageId`，为多语言预留） |
| D10 | 注释与函数之间 | 允许 **0 或 1 个空行** |
| D11 | `ai？` 等待回答 | 指令未变时不再入队；改指令 → ANSWERED，问题注释消失 → MOOT |
| D12 | 侧边窗口 | 下一轮对话专门讨论；先预留 `FeedbackPort.updateStatus` 与面板数据源 |
| D13 | 术语 | 不用 "commit"；写文档统一叫「落笔」（`WriteCommandAction`），与 git 无关 |

---

## 1. 分层与依赖规则

```
bootstrap(plugin.xml / StartupActivity / Action)
        │
        ▼
app/      CompanionController / ScanCycle / RoundExecutor / ProjectRuntime
        │
        ▼
ports/    Workspace / Feedback / Engine / Verifier / Settings / Ledger / Scheduler
        │
        ▼
adapters  ide/*（PSI、Document、Inlay、状态栏、go build 进程）   engine/*（HTTP、JSON）
        │
        ▼
core/     protocol / model / queue / policy / prompt   （纯 Kotlin，无平台依赖）
```

硬规则（`scripts/check-architecture.sh` 已接入 `test.sh`）：

1. `core` 不得 import `com.intellij.*`，也不得 import `ide/engine/ports/app`。
2. `ports` 不得 import `com.intellij.*`，也不得 import `ide/engine/app`；接口参数只用 core 类型。
3. adapter 之间不互相依赖；`app` 只依赖 ports + core。
4. 平台/HTTP 细节只允许出现在 `ide/`、`engine/`、`plugin.xml` 入口。

---

## 2. 包结构（当前已落地 + 计划）

```
src/main/kotlin/dev/commentcompanion/
├── core/                                   ✅ 已实现
│   ├── protocol/   CommentProtocol, DocCommentNormalizer
│   ├── model/      Keys, Snapshot, Plan, Round, RoundStage, Question
│   ├── queue/      WorkQueue
│   ├── policy/     RepairPolicy, FailureRetry, VerificationMode, TrustPolicy
│   └── prompt/     PromptPayload, EngineRequest, EngineOutput, EngineResult
├── ports/                                  ✅ 已实现（接口）
│   ├── WorkspacePort, EnginePort + FeedbackPort,
│   ├── VerifierPort, SettingsPort, LedgerPort + SchedulerPort
├── app/                                    ⏳ M1b
│   ├── CompanionController      组装/生命周期/启停/暂停
│   ├── ScanCycle                2s tick：dirty 集合；空闲才审查入队
│   ├── RoundExecutor            出队→快照→生成→落笔→验证→修复→规范化→记账
│   └── ProjectRuntime           单 worker、队列、scope、取消句柄
├── ide/                                    ⏳ M1b–M2
│   ├── GoFileTracker            打开文档 + stamp 追踪
│   ├── GoAnchor                 注释 → 上方函数（PSI）
│   ├── IdeWorkspace             ReadAction 快照 / previewParse / 落笔 / 反向 plan
│   ├── IdeFeedback              Inlay / 状态栏 / 通知（面板在 D12 后补）
│   ├── GoBuildVerifier          `go build ./...` 后台进程 + 错误差集
│   ├── CompanionSettings        PersistentStateComponent（开关/间隔/引擎/重试）
│   ├── RoundLedgerStore         project 级账本（RoundRecord / QuestionState）
│   ├── RoundJournal             崩溃恢复
│   ├── Actions                  扫描/暂停/重试/撤销本轮
│   └── CompanionBootstrap       StartupActivity + Service 注册
└── engine/                                 ⏳ M2
    ├── OpenAiCompatibleEngine   HttpClient + Gson
    └── EngineJson               请求/响应编解码
```

---

## 3. 核心模型（与代码一致）

身份：`FunctionKey(languageId, file, name, receiverType?, signatureHash)`，`ledgerKey()` 供账本持久化。

快照（出队时生成；`bodyText`/`commentRawLines` 是确定性回滚的数据来源）：
`FunctionSnapshot(key, signatureText, bodyRange, bodyText, commentBlockRange, commentRawLines, descriptionLines, instruction, documentStamp, bodyHash, fileTextHash)` + `precondition()`。

编辑计划：`EditOp = ReplaceBody | ReplaceComment | AddImport | RemoveImport`；`EditPlan(target, snapshot, ops, commandName)`，`invert()` 用快照原文生成反向计划。

状态机：`RoundStage { QUEUED, SNAPSHOT, GENERATING, PARSING, APPLYING, VERIFYING, REPAIRING, NORMALIZING, DONE, FAILED, CANCELLED, STALE }`，`canTransition` 白名单；`RoundRecord.advance()` 拒绝非法迁移。

问题：`QuestionState` + `QuestionResolver`（PENDING/ANSWERED/MOOT/DISMISSED）。

---

## 4. 扫描与队列

### 4.1 扫描（空闲才扫）

```
每 2s：
  for doc in openGoDocuments:
      if doc.stamp != lastSeen[doc]: dirty += doc        # 只记账，不解析
  若 队列非空 或 有 active Round 或 paused：本次结束
  否则：
      for file in dirty:
          text = readText(file)
          for block in CommentProtocol.directives(text):
              hash = sha256(instruction text)
              ledger 过滤（同 hash 已 DONE / WAITING_ANSWER / 失败闸门 → 跳过）
              queue.enqueue(file, block.startLine, hash)
      dirty.clear()
```

### 4.2 队列

- `QueueItem(seq, file, blockStartLine, instructionHash, stage, enqueuedAt, attempts)`：**坐标 + 指纹**，不是快照。
- 串行：同项目一个 worker；`WorkQueue.next()` 在已有 active 时返回 null。
- 去重：同文件 + 同 hash 已在队列/执行中 → 忽略；同位置 hash 变了 → 更新项。
- 顺序：seq（入队顺序）；同一 tick 内按文件路径 + 行号入队，保证自上而下。
- 出队后重新读文档、重新扫描、重新锚定；指令已变/消失 → 丢弃该项。

### 4.3 失败与重试

- Round 失败 → 账本记 `FAILED + instructionHash`；`ai：` 保留。
- **自动重试 1 次**（D8）；仍失败则不再自动入队，需改指令（hash 变）或手动重试。
- 队列不持久化：重启后空闲扫描从注释自然重建；账本负责去重与重试闸门。

---

## 5. 一轮执行（RoundExecutor）

```
QUEUED → SNAPSHOT（读文档/锚定/版本哈希）→ GENERATING（EnginePort）
       → PARSING（校验 body/comment 必填、无标记）
       → APPLYING（落笔 1：body + imports，单 WriteCommandAction）
       → VERIFYING（go build 或 daemon，对比 baseline）
          ├─ ok → NORMALIZING（落笔 2：模型给出的注释，收尾阶段）→ DONE
          └─ 新错误 → REPAIRING → GENERATING（≤5 次/60s）→ 仍失败 → FAILED + 反向 plan 回滚
日志：RoundJournal 在落笔前写意图；异常退出后下次启动恢复/提示
```

- 落笔 1 与落笔 2 是两条命令 → 原生 `Cmd+Z` 需两次；另提供 **「撤销本轮」** action（反向 plan 一条命令恢复）。
- 落笔前校验 `Precondition`；文档已变 → `STALE`，不覆盖用户输入。

---

## 6. 端口（已落地接口）

| 端口 | 职责 | 关键方法 |
|---|---|---|
| `WorkspacePort` | IDE 读写 | `openDocuments`、`readText`、`snapshot(file, line)`、`commit(plan)`、`previewParse` |
| `FeedbackPort` | 用户反馈 | `showTransientHint`、`setBusy`、`updateStatus`、`notify*` |
| `EnginePort` | 模型 | `suspend generate(request): EngineResult` |
| `VerifierPort` | 验证 | `baseline(file)`、`verify(file, baseline)` |
| `SettingsPort` | 配置 | `snapshot()`、`apiKey()`、`update {}` |
| `LedgerPort` | 账本 | `recordFor(key, hash)`、`record`、`question`、`recent` |
| `SchedulerPort` | 定时 | `schedule(key, delay, action)`、`cancel` |

设置快照已包含：扫描间隔（2000ms）、失败重试（AUTO_ONCE）、验证方式（GO_BUILD / DAEMON_ONLY，可关）、信任策略、引擎配置。

---

## 7. 线程契约

| 阶段 | 线程 | 备注 |
|---|---|---|
| tick | 平台定时器线程 | 只比 stamp，进 dirty 集合 |
| 扫描/快照/锚定 | BGT + ReadAction | 2s 一次且仅空闲，成本可控 |
| 生成 | `Dispatchers.IO` | 可取消 |
| 解析/计划校验 | BGT | 纯 Kotlin |
| 落笔 1/2 | EDT + `WriteCommandAction` | 先验 Precondition |
| go build | 后台进程 | 串行、60s 超时、可 destroy |
| Inlay/状态栏/通知 | EDT | 适配器内部保证 |

---

## 8. 持久化

- `CompanionSettings`：application 级 `PersistentStateComponent`；API key 存 `PasswordSafe`。
- `RoundLedgerStore`：project 级（`.idea`）；key = `FunctionKey.ledgerKey()`；存 RoundRecord + QuestionState + attempts。
- `RoundJournal`：落笔前短小意图记录，崩溃恢复用；正常结束即清除。

---

## 9. 里程碑

| 阶段 | 内容 | 状态 |
|---|---|---|
| M0 | 工具链/插件加载/Go PSI/Inlay 真机验证 | ✅ 已完成 |
| M1a | core + ports + 架构检查 | ✅ 已完成（56 测试） |
| M1b | app 编排 + ide 适配（扫描/锚定/队列/状态栏） | ⏳ 下一步 |
| M2 | 引擎 + 落笔 + go build 验证 + 修复循环 | ⏳ |
| M3 | `ai？` 问题流 + 侧边窗口 + 设置页 | ⏳（面板下轮讨论） |
| M4 | 跨文件/签名 + 信任级别 | ⏳ |
| M5 | Ayane 接入（EnginePort 替换 + contract 映射） | ⏳ |

---

## 10. 风险

| # | 风险 | 缓解 |
|---|---|---|
| R1 | daemon 诊断 API 稳定性（GO_BUILD 关闭时） | `DaemonCodeAnalyzerEx.processHighlights` 已核实存在；不可用则禁用该模式 |
| R2 | `PsiFileFactory` preview 解析在无 SDK 下行为 | 沙盒已验证 PSI 解析不需要 SDK；M1b 实测 |
| R3 | 2s 空闲扫描导致「用户改完要等」 | 只在空闲时扫，忙时排队；这是用户明确选择 |
| R4 | 自动重试 1 次仍失败 → 用户困惑 | 通知 + 面板可见 + 手动重试入口 |
| R5 | 函数锚定歧义（注释不在函数上方/隔多行） | 0–1 空行规则 + 距离校验，测试覆盖 |
| R6 | 模型输出的注释仍含 `ai：` | `PlanValidator` 拒绝；本轮失败 |
| R7 | 崩溃在半写状态 | `RoundJournal` + Local History 兜底 |

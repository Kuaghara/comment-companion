# M3a 验证：侧边窗口（三页导航）+ 状态栏 + 账本持久化

> 日期：2026-11-08
> 环境：GoLand 2026.2.3 沙盒；FakeEngine

## ① 结论

- 侧边窗口成功注册并在沙盒中被程序化打开：`verify.json: "toolWindow": "shown"`
- 面板三页（仪表盘 / 详细情况 / 设置）构造无异常
- 状态栏 widget 创建并定时刷新，无异常
- 两个 fixture 仍正常 DONE
- 日志中 `Plugin to blame: Comment Companion` 计数 **0**

## ② 实现内容

**侧边窗口**（`CompanionPanel` + `CompanionToolWindowFactory`）

- 底部三格导航（JToggleButton + CardLayout），选中页持久化在内存（后续可写 PersistentStateComponent）
- 仪表盘：圆形运行/暂停按钮（自绘），下方 1 号位（状态 + 队列/当前任务），再下方 2 号位（待你处理数量 + 最新一条），点击 2 号位跳到详细页
- 详细情况：当前任务 / 待你处理 / 任务队列 / 最近记录（默认显示最近 3 条）
- 设置：协议、Base URL、模型、API Key、验证方式、扫描间隔、失败重试；保存写回 `CompanionSettings`（API Key → PasswordSafe）
- 每 500ms 用 `PanelSnapshot` 刷新（只读投影，不持有领域对象）

**状态栏**（`CompanionStatusBarWidget`）

- 使用 2026.2 的 `StatusBarWidget.TextPresentation`（旧 `getComponent()` API 已移除）
- 显示 `CC <队列数>`，暂停时前缀 ⏸，每秒刷新

**账本持久化**（`RoundLedgerStore`）

- `@State(name="CommentCompanionLedger", storages=["comment-companion-ledger.xml"])`
- 为避免 XML bean 序列化坑，RoundRecord/QuestionState 以 JSON 字符串数组落盘（平台自带 Gson）
- 上限 200 条，`recordFor(key, instructionHash)` 供去重/失败闸门

## ③ 本轮修复

| # | 问题 | 修复 |
|---|---|---|
| 1 | 2026.2 `StatusBarWidget` 不再有 `getComponent()` | 改用 `StatusBarWidget.TextPresentation` |
| 2 | `IdeWorkspace.syncPsi` 在非 EDT/无 ReadAction 下调用 `PsiManager.findFile`，日志大量 ThreadingAssertions SEVERE | PSI 查询包进 `runReadAction`；落笔后 commitDocument 保留在 EDT |

## ④ 仍未完成（M3b）

- `ai？` 问题流（QuestionState 已有模型/账本字段，但生成端还没有把 question 写成注释）
- `RoundJournal` 崩溃恢复
- daemon-only 验证模式（当前 no-op）
- 面板细节：历史展开/折叠、队列操作按钮、设置项校验、i18n
- 真实 API 联调

## ⑤ 复现

```bash
cd ~/go/comment-companion
rm -rf .sandbox/project .sandbox/plugins .sandbox/log .sandbox/verify.json .sandbox/verify-rounds.jsonl
./scripts/dev-sandbox.sh
# 约 40 秒后：
python3 -c "import json;print(json.load(open('.sandbox/verify.json'))['toolWindow'])"
grep -c "Plugin to blame: Comment Companion" .sandbox/log/idea.log   # 期望 0
```

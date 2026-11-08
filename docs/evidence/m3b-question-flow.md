# M3b 进展：ai？问题状态机（半实现）

> 日期：2026-11-08
> 状态：**问题状态已闭环；`ai？` 注释落盘暂缓**（原因见 ③）

## ① 已实现

- `RoundStage.WAITING_ANSWER`（终态），迁移：`PARSING → WAITING_ANSWER`
- `QuestionState.question` 增加问题原文（Gson 持久化）
- `RoundExecutor.askQuestion()`：模型返回 question 时
  - 记录 `RoundRecord(WAITING_ANSWER)`
  - `QuestionState(PENDING, instructionHash, questionHash, question)`
  - 面板/通知/Inlay 展示
- 扫描去重：`ledger.pendingQuestionFor(file, instructionHash)` → 同一指令不再入队
- 下一轮执行前 `QuestionResolver`：指令变了 → ANSWERED；问题消失 → MOOT
- 面板「待你处理」合并两类：`ai？` 待回答 + 失败待重试
- `RoundResult.Question` + controller 通知（「需要你的回答：…」+ 行尾 Inlay）

## ② 沙盒验证

`FAKE_QUESTION=1`（FakeEngine 首次返回 question）：

```jsonl
{"function":"Add","stage":"QUESTION","detail":"需要处理溢出的情况吗？"}
{"function":"Mul","stage":"QUESTION","detail":"需要处理溢出的情况吗？"}
```

- 文件**保持不变**（无重复写入 / 无 DONE 重跑）
- 待处理不再入队（证明 pendingQuestionFor 生效）
- `Plugin to blame: Comment Companion` = 0

## ③ 为什么暂不写 `ai？` 注释

尝试过把 `ai？` 追加到注释块（ReplaceComment），沙盒里发现两个问题：

1. 追加后行号漂移：同一文件里排队中的其他函数 `blockStartLine` 失效（已用「按 instructionHash 全文件找回」缓解）
2. 随后成功回合的注释规范化用 PSI 区间替换时，会把 `// ai？` 行的 `//` 前缀吃掉（区间边界与 PSI 同步的边界情况）

结论：`ai？` 注释落盘需要和「注释块编辑」统一成一个原子操作（或改成独立的行插入 op），不宜在现有 ReplaceComment 上打补丁。已回退为「只记录状态 + 面板/通知/Inlay 展示」，文件零风险。

## ④ 下一步

1. 新增 `EditOp.AppendCommentLine`（基于行首/行尾插入，而非区间替换），用于写 `ai？`；规范化时保留它
2. 回答后清理 `ai？` 行（或标记失效）
3. RoundJournal 崩溃恢复
4. daemon-only 验证模式
5. 面板：待处理项点击跳转、历史展开

# M3b：`ai？` 问题流 + 崩溃 Journal

> 日期：2026-11-08

## ① 已实现

**`ai？` 注释落盘（新增 `EditOp.AppendTrailingComment`）**
- 问题写成**函数体之后的独立注释块**：`\n\n// ai？ <question>`
- 与指令注释块完全隔离 → 不会再被「注释规范化」区间替换吃掉（之前那版会丢 `//` 前缀）
- `askQuestion`：落笔 `ai？` → 记录 `WAITING_ANSWER` + `QuestionState(PENDING)`
- 扫描去重：同 instructionHash 的 `ai：` 不再入队
- 用户改指令 → 哈希变化 → `ANSWERED` → 重新入队

**崩溃 Journal（`RoundJournalStore`）**
- `.idea/comment-companion-journal.xml` 记录：file / blockStartLine / instructionHash / phase / originalBody
- 落笔 body 前写 PENDING，body 成功写 APPLIED；DONE/FAILED/STALE/CANCELLED 清理
- 项目启动时 `recoverJournal()`：APPLIED → 用当前快照回滚到 originalBody；定位失败 → 提示用 Local History

## ② 沙盒验证

**A. 只提问（`FAKE_QUESTION=1`）**：两个 QUESTION 事件，文件出现独立的 `// ai？` 注释块，无重复 DONE。

**B. 回答闭环（`FAKE_QUESTION=1 FAKE_ANSWER=1`）**：提问后自动模拟「用户改指令」：

```jsonl
{"function":"Add","stage":"QUESTION","detail":"需要处理溢出的情况吗？"}
{"function":"Mul","stage":"QUESTION","detail":"需要处理溢出的情况吗？"}
{"function":"Add","stage":"DONE","detail":"attempts=1 verifier=GoBuildVerifier mode=GO_BUILD"}
{"function":"Mul","stage":"DONE","detail":"attempts=1 verifier=GoBuildVerifier mode=GO_BUILD"}
```

最终文件：

```go
// Add 返回两数之和。
// 实现加法，只处理 int。（已答复）
func Add(a, b int) int {
	return 0
}
```

- `ai？` 注释在 DONE 后被清理
- `Plugin to blame: Comment Companion` = 0
- 回答 → 重新生成 → 规范化 → 清理问题注释，整条链路成立

## ③ 已知限制

- 追加 `ai？` 后，同一文件里**已在队列中**的其他函数行号漂移，会先记一次 `STALE`；下一次扫描会以新行号重新入队（最终结果正确，但日志有噪音）
- 回答后旧 `ai？` 注释已自动清理（`cleanupTrailingQuestion`）
- Journal 恢复路径已实现，未做「杀进程」级别的自动化验证

## ④ 测试

78 个全绿（新增 `AppendTrailingComment` 反向计划用例、DSH 解析用例）。

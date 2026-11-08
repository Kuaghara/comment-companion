# daemon-only 验证模式（不跑 go build）

> 日期：2026-11-08
> 开关：设置里 VerificationMode = DAEMON_ONLY（沙盒：`DAEMON_VERIFY=1`）

## ① 实现

`ide/DaemonVerifier.kt`：

1. 落笔后对 PsiFile 调 `DaemonCodeAnalyzer.restart(psi)`（EDT）
2. 轮询 `DaemonCodeAnalyzerEx.isErrorAnalyzingFinished(psi)`（ReadAction 内，最多 5s）
3. `DaemonCodeAnalyzerEx.processHighlights(document, project, HighlightSeverity.ERROR, 0, len)` 收集 ERROR
4. 与 baseline 求差集；超时放行（不阻塞流程）
5. 所有 PSI/daemon 调用都包在 ReadAction / EDT 中

## ② 沙盒验证（`FAKE_BAD_FIRST=1 DAEMON_VERIFY=1`）

```jsonl
{"function":"Add","stage":"VERIFY_FAILED","detail":"verify.go:6: 未解析的引用 'undefinedVar'"}
{"function":"Add","stage":"DONE","detail":"attempts=2 verifier=DaemonVerifier mode=DAEMON_ONLY"}
{"function":"Mul","stage":"VERIFY_FAILED","detail":"verify.go:12: 未解析的引用 'undefinedVar'"}
{"function":"Mul","stage":"DONE","detail":"attempts=2 verifier=DaemonVerifier mode=DAEMON_ONLY"}
```

- daemon 诊断能抓住坏 body 并触发修复循环
- `Plugin to blame: Comment Companion` = 0
- 最终文件 body 正确、注释已规范化

## ③ 两种验证方式对比

| | GO_BUILD（默认） | DAEMON_ONLY |
|---|---|---|
| 原理 | `go build ./...` 进程 | GoLand daemon 诊断 |
| 准确性 | 高（真实编译） | 中（依赖 daemon 完成度） |
| 速度 | 中（有 Go 缓存） | 快 |
| 副作用 | 需先保存文档 | 不落盘 |
| 适用 | 日常/CI | 大项目/无 Go 环境 |

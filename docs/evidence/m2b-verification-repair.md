# M2b 端到端验证：go build 验证 + 修复循环 + 收尾规范化

> 日期：2026-11-08
> 环境：GoLand 2026.2.3 沙盒；`FakeEngine` 开启「首次返回坏 body」，用于强制触发修复循环

## ① 结论

完整单轮闭环跑通：

```
快照 → baseline(go build) → 生成 → 落笔 body → go build 验证
   ├ 通过 → 落笔注释（收尾规范化）→ DONE
   └ 失败 → 回滚 body → 重新生成（≤5 轮/60s）→ …
```

沙盒事件（`verify-rounds.jsonl`）：

```jsonl
{"function":"Add","stage":"VERIFY_FAILED","detail":"verify.go:6: undefined: undefinedVar"}
{"function":"Add","stage":"DONE","detail":"attempts=2 verifier=GoBuildVerifier mode=GO_BUILD"}
{"function":"Mul","stage":"VERIFY_FAILED","detail":"verify.go:12: undefined: undefinedVar"}
{"function":"Mul","stage":"DONE","detail":"attempts=2 verifier=GoBuildVerifier mode=GO_BUILD"}
```

最终磁盘内容：坏 body 被回滚/替换为正确 body，注释完成规范化，描述行保留，`ai：` 消失。

## ② 关键修复（这一轮踩到的真坑）

| # | 现象 | 根因 | 修复 |
|---|---|---|---|
| 1 | `go build` 永远通过，坏代码被提交 | 落笔只改内存 Document，`go build` 读磁盘旧内容 | 验证前 `saveAllDocuments()`（EDT） |
| 2 | 修复/回滚后文件出现 `}两数之积。` 这类错位 | Document 改了但 PSI 未 commit，锚定拿到旧语法树 offset | 快照前 `PsiDocumentManager.commitDocument`，落笔后再 commit |
| 3 | `go` 在 GoLand 进程里可能找不到，且报错被 baseline 差集隐藏 | 基础设施错误也进了 baseline | 基础设施错误（`<build>`）单独识别并直接判失败；直接执行失败时回退 `bash -lc` |
| 4 | `go build` 非零退出但解析不到错误 → 静默通过 | 仅依赖输出解析 | 退出码非零且无解析结果时合成一条基础设施错误 |

## ③ 取消语义

- 生成/验证/规范化各阶段之间检查 `paused`；
- 已落笔 body 则反向 plan 回滚到快照后再记 `CANCELLED`；
- `ai：` 注释保留（因为规范化只发生在成功收尾）。

（取消路径已实现，尚未做沙盒自动化测试；M3 面板做「暂停/取消」按钮时补。）

## ④ 仍缺

- 真实模型 API 联调（需配置 Key；三种协议已实现）
- daemon-only 验证模式目前是 no-op，后续接 GoLand 诊断
- 面板 / 状态栏 / 设置页（M3）
- 账本仍是内存版，重启丢失（M3 前补 PersistentStateComponent）

## ⑤ 复现

```bash
cd ~/go/comment-companion
rm -rf .sandbox/project .sandbox/plugins .sandbox/log .sandbox/verify-rounds.jsonl
FAKE_BAD_FIRST=1 ./scripts/dev-sandbox.sh
# 等待约 40–60 秒
cat .sandbox/verify-rounds.jsonl
cat .sandbox/project/verify.go
```

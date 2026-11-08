# imports 自动补齐 + 签名守卫（M4 第一刀）

> 日期：2026-11-08

## ① 实现

- `EngineOutput.imports: List<EngineImport>`（path/alias），prompt 里允许模型返回并列出所需 import
- `ResponseParser` 解析 `imports`（对象数组或字符串数组均可）
- `EditOp.AddImport` 真正落地：文档编辑后用 PSI `GoFile.addImport(path, alias)`（避免 offset 位移）
- 反向计划 `RemoveImport` 也实现（PSI 删除）
- 签名守卫：模型 body 中出现 `func ` 声明 → 直接拒绝本轮（MVP 只允许改函数体）
- 失败文案：AUTH / RATE_LIMIT / TIMEOUT / NETWORK 分别给可操作提示

## ② 真实模型端到端（imports）

项目：

```go
// Format 把 n 转成十进制字符串。
// ai：用 strconv 实现，返回 n 的十进制字符串
func Format(n int) string {
	return ""
}
```

结果（DONE，attempts=1，go build 通过）：

```go
package importprobe

import "strconv"

// Format 把 n 转成十进制字符串。
func Format(n int) string {
	return strconv.Itoa(n)
}
```

- 模型返回 `body=strconv.Itoa(n)` + `imports=[strconv]`
- 插件自动补 import、验证通过、注释规范化
- `Plugin to blame: Comment Companion` = 0

## ③ 测试

79 个全绿（新增 `ResponseParser` imports 解析用例）。

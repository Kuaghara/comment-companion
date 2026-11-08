package dev.commentcompanion.core.model

import dev.commentcompanion.core.protocol.Directive
import dev.commentcompanion.core.protocol.MarkerType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EditPlanTest {

    private val key = FunctionKey("go", FileKey("calc/add.go"), "Add", null, "sig")
    private val snapshot = FunctionSnapshot(
        key = key,
        signatureText = "func Add(a, b int) int",
        bodyRange = 100..130,
        bodyText = "{\n\treturn 0\n}",
        commentBlockRange = 40..75,
        commentRawLines = listOf("// Add 返回两数之和。", "// ai：实现加法"),
        descriptionLines = listOf("// Add 返回两数之和。"),
        instruction = Directive(MarkerType.INSTRUCTION, "实现加法", markerLine = 6),
        documentStamp = 42,
        bodyHash = "body-hash",
        fileTextHash = "file-hash",
    )

    @Test
    fun `反向计划用快照原文恢复 body 与注释`() {
        val plan = EditPlan(
            target = key,
            snapshot = snapshot,
            ops = listOf(
                EditOp.ReplaceBody("{\n\treturn a + b\n}"),
                EditOp.ReplaceComment(listOf("// Add 返回两数之和。", "// 返回 a + b。")),
            ),
            commandName = "实现 Add",
        )

        val inverse = plan.invert()
        assertEquals(EditOp.ReplaceBody(snapshot.bodyText), inverse.ops[0])
        assertEquals(EditOp.ReplaceComment(snapshot.commentRawLines), inverse.ops[1])
        assertEquals("实现 Add（回滚）", inverse.commandName)
    }

    @Test
    fun `import 的反向是删除`() {
        val plan = EditPlan(
            target = key,
            snapshot = snapshot,
            ops = listOf(EditOp.AddImport("strconv", null)),
            commandName = "add import",
        )
        assertEquals(EditOp.RemoveImport("strconv", null), plan.invert().ops.single())
    }

    @Test
    fun `追加类操作的反向计划是 NoOp`() {
        val plan = EditPlan(
            target = key,
            snapshot = snapshot,
            ops = listOf(
                EditOp.AppendCommentLine("ai？ 需要处理空输入吗？"),
                EditOp.AppendTrailingComment("ai？ 需要处理空输入吗？"),
            ),
            commandName = "question",
        )
        assertEquals(listOf(EditOp.NoOp, EditOp.NoOp), plan.invert().ops)
    }

    @Test
    fun `快照前置条件包含版本与两个哈希`() {
        val precondition = snapshot.precondition()
        assertEquals(42L, precondition.documentStamp)
        assertEquals("body-hash", precondition.bodyHash)
        assertEquals(64, precondition.commentHash.length)
    }
}

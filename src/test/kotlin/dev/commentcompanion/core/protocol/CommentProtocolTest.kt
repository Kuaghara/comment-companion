package dev.commentcompanion.core.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 注释协议解析测试。
 *
 * 用例设计参考了对 Aider / pi-watcher 的证据审查：
 * 标记位置、大小写、全角/半角、边界、续行、块注释、误报（注释中间/字符串里）。
 */
class CommentProtocolTest {

    private fun scan(vararg lines: String): List<CommentBlock> =
        CommentProtocol.scan(lines.toList())

    private fun singleDirective(vararg lines: String): Directive {
        val blocks = scan(*lines)
        val withMarker = blocks.filter { it.directive != null }
        assertEquals(1, withMarker.size, "期望恰好一个带标记的注释块，实际：$withMarker")
        return withMarker.single().directive!!
    }

    // ---- 基本语法 ----

    @Test
    fun `半角冒号指令`() {
        val d = singleDirective("// ai: 实现一个加法函数")
        assertEquals(MarkerType.INSTRUCTION, d.type)
        assertEquals("实现一个加法函数", d.text)
    }

    @Test
    fun `全角冒号指令`() {
        val d = singleDirective("// ai：实现一个加法函数")
        assertEquals(MarkerType.INSTRUCTION, d.type)
        assertEquals("实现一个加法函数", d.text)
    }

    @Test
    fun `标记大小写不敏感`() {
        listOf("// AI：实现", "// Ai: 实现", "// aI ：实现").forEach { line ->
            val d = singleDirective(line)
            assertEquals(MarkerType.INSTRUCTION, d.type, "行：$line")
        }
    }

    @Test
    fun `标记与冒号之间允许空白`() {
        val d = singleDirective("// ai  ：  实现")
        assertEquals("实现", d.text)
    }

    @Test
    fun `斜杠后不加空格也能识别`() {
        val d = singleDirective("//ai：实现")
        assertEquals("实现", d.text)
    }

    @Test
    fun `空指令也解析出来（由上层决定是否忽略）`() {
        val d = singleDirective("// ai：")
        assertEquals(MarkerType.INSTRUCTION, d.type)
        assertEquals("", d.text)
    }

    // ---- 位置规则 ----

    @Test
    fun `标记在注释内容中间不算指令`() {
        val blocks = scan("// 这里要调用 ai： 生成的代码")
        assertNull(blocks.single().directive)
    }

    @Test
    fun `代码行里的 ai 冒号不算注释`() {
        val blocks = scan("x := \"ai：not a directive\"")
        assertTrue(blocks.isEmpty())
    }

    @Test
    fun `带缩进的注释也能识别`() {
        val d = singleDirective("\t\t// ai：实现")
        assertEquals("实现", d.text)
    }

    // ---- 描述行 + 续行 ----

    @Test
    fun `标记行之前的注释是描述，不算进指令`() {
        val blocks = scan(
            "// Add 返回两数之和。",
            "// 只支持 int。",
            "// ai：实现这个函数",
        )
        val block = blocks.single()
        val d = assertNotNull(block.directive).let { block.directive!! }
        assertEquals(2, d.markerLine)
        assertEquals(3, block.rawLines.size)
        assertEquals("实现这个函数", d.text)
    }

    @Test
    fun `标记行之后的注释是指令续行`() {
        val d = singleDirective(
            "// ai：实现一个加法函数，",
            "// 参数为 int，返回 int。",
        )
        assertEquals("实现一个加法函数，\n参数为 int，返回 int。", d.text)
    }

    @Test
    fun `连续两个注释块分别解析`() {
        val blocks = scan(
            "// ai：第一件事",
            "",
            "func a() {}",
            "",
            "// ai：第二件事",
            "",
            "func b() {}",
        )
        assertEquals(2, blocks.count { it.directive != null })
    }

    // ---- 块注释 ----

    @Test
    fun `单行块注释指令`() {
        val d = singleDirective("/* ai：实现 */")
        assertEquals(MarkerType.INSTRUCTION, d.type)
        assertEquals("实现", d.text)
    }

    @Test
    fun `多行块注释取第一个非空内容行作为标记`() {
        val d = singleDirective(
            "/*",
            "ai：实现",
            "只处理 int",
            "*/",
        )
        assertEquals("实现\n只处理 int", d.text)
    }

    @Test
    fun `块注释标记不在开头时忽略`() {
        val blocks = scan(
            "/*",
            "这是一段说明",
            "ai：实现",
            "*/",
        )
        assertNull(blocks.single().directive)
    }

    // ---- 反问标记 ----

    @Test
    fun `全角问号是反问标记`() {
        val d = singleDirective("// ai？需要处理空输入吗？")
        assertEquals(MarkerType.QUESTION, d.type)
        assertEquals("需要处理空输入吗？", d.text)
    }

    @Test
    fun `半角问号是反问标记`() {
        val d = singleDirective("// AI? need tests?")
        assertEquals(MarkerType.QUESTION, d.type)
        assertEquals("need tests?", d.text)
    }

    // ---- 健壮性 ----

    @Test
    fun `CRLF 行尾不影响解析`() {
        val d = singleDirective("// ai：实现\r")
        assertEquals("实现", d.text)
    }

    @Test
    fun `directives 只返回带标记的块`() {
        val lines = listOf(
            "// 普通注释",
            "func a() {}",
            "// ai：实现 b",
            "func b() {}",
        )
        assertEquals(1, CommentProtocol.directives(lines).size)
    }

    @Test
    fun `isLineComment 判断`() {
        assertTrue(CommentProtocol.isLineComment("  // x"))
        assertTrue(CommentProtocol.isLineComment("//x"))
        assertFalse(CommentProtocol.isLineComment("x := 1 // 行尾注释"))
        assertFalse(CommentProtocol.isLineComment("/* x */"))
    }

    @Test
    fun `真实 Go 片段里只识别函数上方的指令`() {
        val src = """
            package demo

            // Add 返回两数之和。
            // ai：实现它
            func Add(a, b int) int {
                // 函数体里的普通注释，不是指令
                return 0
            }

            // Mul 返回两数之积。
            // ai：实现 Mul
            func Mul(a, b int) int {
                return 0
            }
        """.trimIndent()

        val blocks = CommentProtocol.directives(src.lines())
        assertEquals(2, blocks.size)
        assertEquals(listOf("实现它", "实现 Mul"), blocks.map { it.directive!!.text })
        assertEquals(listOf(3, 10), blocks.map { it.directive!!.markerLine })
    }
}

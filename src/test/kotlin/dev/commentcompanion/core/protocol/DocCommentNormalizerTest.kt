package dev.commentcompanion.core.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 注释规范化测试。
 *
 * 重点验证（全部来自对 Aider / pi-watcher 的教训）：
 * - 只改指令部分，标记行之前的方法描述原样保留（不整块删）；
 * - 规范化后不再存在任何标记（结构性防重复触发）；
 * - 结果幂等/稳定；
 * - 指令文本本身以 `ai：` 开头时不会重新构成标记。
 */
class DocCommentNormalizerTest {

    private fun normalize(lines: List<String>, text: String): DocCommentNormalizer.Result {
        val block = CommentProtocol.scan(lines).single { it.directive != null }
        return DocCommentNormalizer.normalize(block, block.directive!!, text)
    }

    private fun hasMarker(lines: List<String>): Boolean = lines.any { line ->
        val content = line.trimStart().removePrefix("//").trimStart()
        CommentProtocol.classify(content) != null
    }

    @Test
    fun `简单指令规范化为 doc comment`() {
        val r = normalize(listOf("// ai：实现一个加法函数"), "实现一个加法函数")
        assertEquals(listOf("// 实现一个加法函数"), r.lines)
        assertFalse(r.guardApplied)
    }

    @Test
    fun `标记行之前的方法描述原样保留`() {
        val r = normalize(
            listOf(
                "// Add 返回两数之和。",
                "// 只支持 int。",
                "// ai：实现",
            ),
            "实现加法",
        )
        assertEquals(
            listOf("// Add 返回两数之和。", "// 只支持 int。", "// 实现加法"),
            r.lines,
        )
    }

    @Test
    fun `多行描述生成多行 doc comment`() {
        val r = normalize(listOf("// ai：实现"), "实现加法，\n参数为 int")
        assertEquals(listOf("// 实现加法，", "// 参数为 int"), r.lines)
    }

    @Test
    fun `空行生成空注释行`() {
        val r = normalize(listOf("// ai：实现"), "第一行\n\n第二行")
        assertEquals(listOf("// 第一行", "//", "// 第二行"), r.lines)
    }

    @Test
    fun `块注释规范化为行注释`() {
        val r = normalize(listOf("/* ai：实现 */"), "实现加法")
        assertEquals(listOf("// 实现加法"), r.lines)
    }

    @Test
    fun `规范化后不再有标记`() {
        val r = normalize(listOf("// ai：实现一个加法函数"), "实现一个加法函数")
        assertFalse(hasMarker(r.lines))
    }

    @Test
    fun `描述行里的普通文本不会被误删`() {
        val r = normalize(
            listOf(
                "// 这是一个普通说明。",
                "// ai：实现",
            ),
            "实现",
        )
        assertEquals("// 这是一个普通说明。", r.lines.first())
    }

    @Test
    fun `模型返回带注释前缀的文本会被剥掉一层`() {
        val r = normalize(listOf("// ai：实现"), "// 实现加法\n// 返回 int")
        assertEquals(listOf("// 实现加法", "// 返回 int"), r.lines)
    }

    @Test
    fun `指令文本本身以标记开头时加保护前缀`() {
        val r = normalize(listOf("// ai：输出 ai：字面量"), "ai：字面量")
        assertTrue(r.guardApplied)
        assertEquals(listOf("// 注：ai：字面量"), r.lines)
        assertFalse(hasMarker(r.lines))
    }

    @Test
    fun `描述里的空注释行保留`() {
        val r = normalize(
            listOf(
                "// 描述第一行",
                "//",
                "// ai：实现",
            ),
            "实现",
        )
        assertEquals(listOf("// 描述第一行", "//", "// 实现"), r.lines)
    }
}

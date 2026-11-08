package dev.commentcompanion.core.prompt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResponseParserTest {

    @Test
    fun `标准 JSON 解析出 body 与 comment`() {
        val raw = """{"body":"{\n\treturn a + b\n}","comment":["实现加法","返回 a + b"],"question":null}"""
        val result = ResponseParser.parse(raw)
        assertTrue(result is ResponseParser.Result.Ok)
        val output = (result as ResponseParser.Result.Ok).output
        assertEquals("{\n\treturn a + b\n}", output.body)
        assertEquals(listOf("实现加法", "返回 a + b"), output.comment)
        assertNull(output.question)
    }

    @Test
    fun `只返回问题也是合法输出`() {
        val raw = """{"body":"","comment":[],"question":"需要处理溢出吗？"}"""
        val result = ResponseParser.parse(raw)
        assertTrue(result is ResponseParser.Result.Ok)
        assertEquals("需要处理溢出吗？", (result as ResponseParser.Result.Ok).output.question)
    }

    @Test
    fun `Markdown 围栏里的 JSON 也能解析`() {
        val raw = "```json\n{\"body\":\"{}\",\"comment\":[\"空实现\"]}\n```"
        val result = ResponseParser.parse(raw)
        assertTrue(result is ResponseParser.Result.Ok)
        assertEquals("{}", (result as ResponseParser.Result.Ok).output.body)
    }

    @Test
    fun `comment 为字符串时按行拆分`() {
        val raw = """{"body":"{}","comment":"第一行\n第二行"}"""
        val result = ResponseParser.parse(raw)
        assertTrue(result is ResponseParser.Result.Ok)
        assertEquals(listOf("第一行", "第二行"), (result as ResponseParser.Result.Ok).output.comment)
    }

    @Test
    fun `解析 imports`() {
        val raw = """{"body":"{}","comment":["x"],"imports":[{"path":"strconv"},{"path":"fmt","alias":"f"}]}"""
        val result = ResponseParser.parse(raw)
        assertTrue(result is ResponseParser.Result.Ok)
        val imports = (result as ResponseParser.Result.Ok).output.imports
        assertEquals(2, imports.size)
        assertEquals("strconv", imports[0].path)
        assertEquals("f", imports[1].alias)
    }

    @Test
    fun `缺少 body 且没有问题则失败`() {
        val result = ResponseParser.parse("""{"comment":["x"]}""")
        assertTrue(result is ResponseParser.Result.Invalid)
    }

    @Test
    fun `非 JSON 输出失败`() {
        val result = ResponseParser.parse("好的，我来实现这个函数。")
        assertTrue(result is ResponseParser.Result.Invalid)
    }

    @Test
    fun `字符串里带花括号不会截断`() {
        val raw = """{"body":"{\n\ts := \"}\"\n\treturn s\n}","comment":["x"]}"""
        val result = ResponseParser.parse(raw)
        assertTrue(result is ResponseParser.Result.Ok)
        assertTrue((result as ResponseParser.Result.Ok).output.body.contains("\"}\""))
    }
}

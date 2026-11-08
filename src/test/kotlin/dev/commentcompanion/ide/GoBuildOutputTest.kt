package dev.commentcompanion.ide

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GoBuildOutputTest {

    @Test
    fun `解析相对路径错误`() {
        val errors = GoBuildOutput.parse("./calc/add.go:12:5: undefined: x")
        assertEquals(1, errors.size)
        assertEquals("calc/add.go", errors[0].file)
        assertEquals(12, errors[0].line)
        assertEquals("undefined: x", errors[0].message)
    }

    @Test
    fun `解析绝对路径错误`() {
        val errors = GoBuildOutput.parse("/Users/me/proj/calc/add.go:3: cannot use x (type int) as type string")
        assertTrue(errors[0].file.endsWith("calc/add.go"))
        assertEquals(3, errors[0].line)
        assertTrue(errors[0].message.contains("cannot use"))
    }

    @Test
    fun `忽略模块标题行与普通输出`() {
        val output = """
            # sandboxverify
            ./verify.go:7:2: undefined: undefinedVar
            note: something else
        """.trimIndent()
        val errors = GoBuildOutput.parse(output)
        assertEquals(1, errors.size)
        assertEquals("verify.go", errors[0].file)
    }

    @Test
    fun `相同错误去重`() {
        val line = "./a.go:1:1: boom"
        assertEquals(1, GoBuildOutput.parse("$line\n$line").size)
    }
}

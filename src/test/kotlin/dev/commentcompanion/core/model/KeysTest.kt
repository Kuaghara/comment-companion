package dev.commentcompanion.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class KeysTest {

    private val file = FileKey("internal/calc/add.go")

    @Test
    fun `文件扩展名`() {
        assertEquals("go", file.extension)
        assertEquals("", FileKey("Makefile").extension)
    }

    @Test
    fun `账本 key 包含语言、文件、接收者、名字与签名哈希`() {
        val key = FunctionKey("go", file, "Add", null, "sig123")
        assertEquals("go|internal/calc/add.go||Add|sig123", key.ledgerKey())
    }

    @Test
    fun `方法与同签名函数的账本 key 不同`() {
        val fn = FunctionKey("go", file, "Build", null, "sig")
        val method = FunctionKey("go", file, "Build", "*Builder", "sig")
        assertNotEquals(fn.ledgerKey(), method.ledgerKey())
    }

    @Test
    fun `签名哈希稳定且对空白敏感`() {
        val a = Hashing.sha256("func Add(a, b int) int")
        val b = Hashing.sha256("func Add(a, b int) int")
        val c = Hashing.sha256("func Add(a,b int) int")
        assertEquals(a, b)
        assertNotEquals(a, c)
        assertEquals(64, a.length)
    }
}

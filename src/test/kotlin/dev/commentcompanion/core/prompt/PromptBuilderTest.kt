package dev.commentcompanion.core.prompt

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PromptBuilderTest {

    private val payload = PromptPayload(
        languageId = "go",
        file = "calc/add.go",
        functionName = "Add",
        signature = "func Add(a, b int) int",
        descriptionLines = listOf("Add 返回两数之和。"),
        existingBody = "{\n\treturn 0\n}",
        instruction = "实现加法，只处理 int。",
    )

    @Test
    fun `system 要求 JSON 且禁止 ai 标记`() {
        val messages = PromptBuilder.build(payload)
        assertTrue(messages.system.contains("JSON"))
        assertTrue(messages.system.contains("ai："))
    }

    @Test
    fun `user 包含签名、描述、现有 body 与指令`() {
        val user = PromptBuilder.build(payload).user
        assertTrue(user.contains("func Add(a, b int) int"))
        assertTrue(user.contains("Add 返回两数之和。"))
        assertTrue(user.contains("return 0"))
        assertTrue(user.contains("实现加法，只处理 int。"))
    }

    @Test
    fun `没有修复错误时不出现错误段`() {
        val user = PromptBuilder.build(payload).user
        assertFalse(user.contains("编译错误"))
    }

    @Test
    fun `有修复错误时附加错误摘要`() {
        val user = PromptBuilder.build(payload.copy(repairErrors = listOf("add.go:12: undefined: x"))).user
        assertTrue(user.contains("编译错误"))
        assertTrue(user.contains("undefined: x"))
    }
}

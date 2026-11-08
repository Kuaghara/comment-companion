package dev.commentcompanion.core.prompt

/** 把领域快照拼成模型可读的 system/user 两段文本。 */
object PromptBuilder {

    data class Messages(val system: String, val user: String)

    private val SYSTEM = """
        你是一个 Go 代码助手，只能通过修改函数体来完成用户写在注释里的指令。
        规则：
        1. 只输出 JSON，不要输出多余文字或 Markdown 围栏。格式：
           {"body":"函数体（不含外层 func 声明，包含花括号）","comment":["规范化后的注释行，不含 // 前缀"],"question":null,"imports":[{"path":"strconv"}]}
           imports 可选：如果正文需要新的 import，列在这里；不需要就省略或空数组。
        2. body 必须是合法的 Go 代码块，保持函数签名、方法接收者、包名不变。
        3. comment 是把用户指令改写成普通 Go doc comment 的结果，禁止出现 "ai：" 或 "ai？" 字样。
        4. 如果缺少必要信息，把 question 设为问题文本，body/comment 留空。
        5. 不要修改函数签名；确实需要改签名时，把 question 设为说明，不要自行改。
    """.trimIndent()

    fun build(payload: PromptPayload): Messages {
        val sb = StringBuilder()
        sb.appendLine("文件：${payload.file}")
        sb.appendLine("函数签名：${payload.signature}")
        if (payload.descriptionLines.isNotEmpty()) {
            sb.appendLine("方法描述（保留，不要改写）：")
            payload.descriptionLines.forEach { sb.appendLine("  $it") }
        }
        sb.appendLine("当前函数体：")
        sb.appendLine(payload.existingBody)
        sb.appendLine("用户指令：")
        sb.appendLine(payload.instruction)
        if (payload.repairErrors.isNotEmpty()) {
            sb.appendLine("上一次提交后的编译错误（请修复）：")
            payload.repairErrors.take(10).forEach { sb.appendLine("  - $it") }
        }
        return Messages(system = SYSTEM, user = sb.toString().trimEnd())
    }
}

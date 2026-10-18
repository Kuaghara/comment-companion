package dev.commentcompanion.core.protocol

/**
 * 把「已完成的指令注释」规范化成普通的 Go doc comment。
 *
 * 设计目标（来自 MVP 设计文档 + 对 Aider/pi-watcher 的证据审查）：
 * 1. 只改**指令部分**：`//` 块里标记行之前的描述行保持原样（不能像 pi-watcher 那样整块删）。
 * 2. 规范化后 `ai：` 必须彻底消失——这是“不会重复触发”的结构性保证。
 * 3. 块注释统一转成 `//` 行注释（Go doc comment 的常规形态）。
 * 4. 输出保持幂等：normalize(normalize(x)) == normalize(x)。
 *
 * 该对象是纯文本转换，不依赖 IntelliJ 平台，便于单测。
 */
object DocCommentNormalizer {

    /**
     * @property lines 规范化后的完整注释块行（含 `//` 前缀）
     * @property guardApplied 是否触发了“防止规范化结果再次构成标记”的保护
     */
    data class Result(
        val lines: List<String>,
        val guardApplied: Boolean,
    )

    /**
     * @param block 原始注释块
     * @param directive 该块解析出的标记
     * @param normalizedText 由模型/机械规则得到的最终描述文本（可多行，不要求带 `//`）
     */
    fun normalize(block: CommentBlock, directive: Directive, normalizedText: String): Result {
        require(block.directive === directive) {
            "directive 必须来自该 block 的解析结果"
        }

        // 标记行之前的注释原样保留：这些是方法描述/临时上下文，不是 AI 可以改写的部分。
        val kept: List<String> = when (block.kind) {
            BlockKind.LINE -> block.rawLines.take((directive.markerLine - block.startLine).coerceIn(0, block.rawLines.size))
            BlockKind.BLOCK -> emptyList()
        }

        var guard = false
        val docLines = ArrayList<String>()
        for (rawLine in normalizedText.split("\n")) {
            var content = stripCommentPrefix(rawLine).trimEnd()
            if (CommentProtocol.classify(content) != null) {
                // 指令文本本身以 “ai：” 开头（例如用户要求“输出 ai：字面量”）。
                // 直接写成 doc comment 会再次形成标记；加一个前缀破坏标记形态。
                content = "注：$content"
                guard = true
            }
            docLines += if (content.isEmpty()) "//" else "// $content"
        }

        return Result(kept + docLines, guard)
    }

    /** 模型可能把 `// xxx` 原样带回来；这里剥掉一层注释前缀，避免出现 `// // xxx`。 */
    private fun stripCommentPrefix(rawLine: String): String {
        val trimmed = rawLine.trimStart()
        if (!trimmed.startsWith("//")) return trimmed
        val body = trimmed.removePrefix("//")
        return if (body.startsWith(" ")) body.drop(1) else body
    }
}

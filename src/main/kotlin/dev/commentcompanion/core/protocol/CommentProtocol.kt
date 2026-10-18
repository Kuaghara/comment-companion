package dev.commentcompanion.core.protocol

/**
 * 注释块种类。
 *
 * - [LINE]：连续的 `//` 行注释（Go 的 doc comment 形态）
 * - [BLOCK]：斜杠星号形式的块注释
 */
enum class BlockKind { LINE, BLOCK }

/**
 * 标记语义：
 *
 * - [INSTRUCTION]：用户写给 AI 的指令，形如 `ai：...`
 * - [QUESTION]：AI 反问用户的问题，形如 `ai？...`
 */
enum class MarkerType { INSTRUCTION, QUESTION }

/**
 * 注释块里的一个标记。
 *
 * @property type 标记语义
 * @property text 去掉标记后的文本；多行时以 `\n` 连接
 * @property markerLine 标记所在的绝对行号（0 基）
 */
data class Directive(
    val type: MarkerType,
    val text: String,
    val markerLine: Int,
)

/**
 * 一个注释块。
 *
 * @property kind 行注释还是块注释
 * @property startLine 块的第一行绝对行号（0 基）
 * @property rawLines 块的原始行（未做任何改写，便于“最小改写”）
 * @property directive 块里的标记；没有则为 null
 */
data class CommentBlock(
    val kind: BlockKind,
    val startLine: Int,
    val rawLines: List<String>,
    val directive: Directive?,
) {
    /** 块的最后一行的绝对行号（0 基）。 */
    val endLine: Int get() = startLine + rawLines.size - 1
}

/**
 * 注释协议解析器（纯文本，不依赖 IntelliJ 平台）。
 *
 * 协议规则（与设计文档一致）：
 * 1. 指令标记 = `ai` + 全角/半角冒号（`：` / `:`），大小写不敏感，标记与冒号之间允许空白。
 * 2. 反问标记 = `ai` + 全角/半角问号（`？` / `?`），规则同上。
 * 3. `//` 注释块里，标记必须位于某一行注释内容的**开头**；该行之前的注释行是“方法描述/上下文”，
 *    该行之后的注释行是指令的续行。
 * 4. 斜杠星号块注释里，标记必须位于块的**第一个非空内容行开头**。
 * 5. 一个注释块最多解析出一个标记；同一块里的第二个标记会被当作普通文本（规范化后一并消失）。
 *
 * 注意：这是文本层解析；真实插件里函数锚定由 PSI 完成。字符串字面量里出现的 `ai：`
 * 因为不满足“整行以 `//` 开头”不会误判；但原始字符串（反引号）里的例子需要 PSI 才能排除，
 * 属于插件层的额外职责。
 */
object CommentProtocol {

    /** 指令标记正则：`ai：` / `ai:`（大小写不敏感，冒号前后允许空白）。 */
    private val INSTRUCTION = Regex("""^ai\s*[：:]\s*(.*)$""", RegexOption.IGNORE_CASE)

    /** 反问标记正则：`ai？` / `ai?`（大小写不敏感，问号前允许空白）。 */
    private val QUESTION = Regex("""^ai\s*[？?]\s*(.*)$""", RegexOption.IGNORE_CASE)

    /**
     * 判断一段注释内容（已去掉 `//` 前缀）是否以标记开头。
     *
     * @return 标记类型 + 标记之后的文本；不是标记返回 null
     */
    fun classify(content: String): Pair<MarkerType, String>? {
        val trimmed = content.trimStart()
        INSTRUCTION.matchEntire(trimmed)?.let { return MarkerType.INSTRUCTION to it.groupValues[1] }
        QUESTION.matchEntire(trimmed)?.let { return MarkerType.QUESTION to it.groupValues[1] }
        return null
    }

    /** 快速判断一行原始文本是否是注释行（`//` 开头，允许缩进）。 */
    fun isLineComment(line: String): Boolean = stripCarriageReturn(line).trimStart().startsWith("//")

    /**
     * 扫描整段文本，返回所有注释块（含没有标记的块）。
     *
     * @param lines 文件按 `\n` 切分后的行
     */
    fun scan(lines: List<String>): List<CommentBlock> {
        val normalized = lines.map { stripCarriageReturn(it) }
        val result = ArrayList<CommentBlock>()
        var i = 0
        while (i < normalized.size) {
            val trimmed = normalized[i].trimStart()
            when {
                trimmed.startsWith("//") -> {
                    val start = i
                    while (i < normalized.size && normalized[i].trimStart().startsWith("//")) i++
                    result += parseLineBlock(normalized.subList(start, i), start)
                }
                trimmed.startsWith("/*") -> {
                    val start = i
                    while (i < normalized.size && !normalized[i].contains("*/")) i++
                    if (i < normalized.size) i++ // 收掉 `*/` 所在行
                    result += parseBlockComment(normalized.subList(start, i), start)
                }
                else -> i++
            }
        }
        return result
    }

    /** 只返回带标记的块，按行号升序。 */
    fun directives(lines: List<String>): List<CommentBlock> =
        scan(lines).filter { it.directive != null }

    private fun stripCarriageReturn(line: String): String =
        if (line.endsWith("\r")) line.dropLast(1) else line

    /** 去掉 `//` 前缀和紧随其后的一个空格，返回注释内容。 */
    private fun lineContent(line: String): String {
        val withoutIndent = line.trimStart()
        val body = withoutIndent.removePrefix("//")
        return if (body.startsWith(" ")) body.drop(1) else body
    }

    private fun parseLineBlock(raw: List<String>, startLine: Int): CommentBlock {
        var directive: Directive? = null
        for ((index, line) in raw.withIndex()) {
            val parsed = classify(lineContent(line)) ?: continue
            val (type, firstText) = parsed
            val continuation = raw.drop(index + 1).map { lineContent(it) }
            val text = (listOf(firstText.trimEnd()) + continuation).joinToString("\n").trim()
            directive = Directive(type = type, text = text, markerLine = startLine + index)
            break
        }
        return CommentBlock(BlockKind.LINE, startLine, raw, directive)
    }

    private fun parseBlockComment(raw: List<String>, startLine: Int): CommentBlock {
        val content = blockContent(raw)
        var directive: Directive? = null
        val firstNonBlank = content.indexOfFirst { it.isNotBlank() }
        if (firstNonBlank >= 0) {
            val parsed = classify(content[firstNonBlank])
            if (parsed != null) {
                val (type, firstText) = parsed
                val rest = content.drop(firstNonBlank + 1)
                val text = (listOf(firstText.trimEnd()) + rest).joinToString("\n").trim()
                directive = Directive(type = type, text = text, markerLine = startLine)
            }
        }
        return CommentBlock(BlockKind.BLOCK, startLine, raw, directive)
    }

    /**
     * 提取块注释的内容行：去掉开闭定界符、每行开头的 `*` 装饰与首尾空白。
     */
    private fun blockContent(raw: List<String>): List<String> {
        if (raw.isEmpty()) return emptyList()
        val first = raw.first()
        val open = first.indexOf("/*")
        var body = if (open >= 0) first.substring(open + 2) else first
        if (raw.size == 1) {
            val close = body.indexOf("*/")
            if (close >= 0) body = body.substring(0, close)
            return listOf(stripDecoration(body))
        }
        val lines = ArrayList<String>()
        lines += stripDecoration(body)
        for (line in raw.subList(1, raw.size - 1)) lines += stripDecoration(line)
        val last = raw.last()
        val close = last.indexOf("*/")
        val tail = if (close >= 0) last.substring(0, close) else last
        lines += stripDecoration(tail)
        return lines
    }

    private fun stripDecoration(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("*")) s = s.drop(1).trimStart()
        return s
    }
}

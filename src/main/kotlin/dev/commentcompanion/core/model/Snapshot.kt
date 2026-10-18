package dev.commentcompanion.core.model

import dev.commentcompanion.core.protocol.Directive
import dev.commentcompanion.core.protocol.MarkerType

/**
 * 某一刻的函数快照：出队时生成，Round 执行期间视为不可变。
 *
 * 之所以带 [bodyText] 与 [commentRawLines]：失败回滚不依赖模型输出，
 * 直接用快照原文恢复区间（反向 plan 的数据来源）。
 */
data class FunctionSnapshot(
    val key: FunctionKey,
    val signatureText: String,
    /** 旧函数体区间（文档 offset，含花括号）。 */
    val bodyRange: IntRange,
    /** 旧函数体原文（回滚用）。 */
    val bodyText: String,
    /** 指令注释块区间（文档 offset）。 */
    val commentBlockRange: IntRange,
    /** 指令注释块原文行（回滚用）。 */
    val commentRawLines: List<String>,
    /** ai：之前的方法描述行（只读，规范化时原样保留）。 */
    val descriptionLines: List<String>,
    val instruction: Directive,
    /** 快照时 `Document.modificationStamp`。 */
    val documentStamp: Long,
    val bodyHash: String,
    val fileTextHash: String,
) {
    init {
        require(instruction.type == MarkerType.INSTRUCTION) { "快照只接受指令标记（ai：）" }
        require(commentBlockRange.first <= commentBlockRange.last) { "注释块区间非法" }
        require(bodyRange.first >= 0 && bodyRange.last >= bodyRange.first) { "函数体区间非法" }
        require(bodyText.isNotEmpty()) { "函数体不能为空（无 body 的函数不应生成快照）" }
    }

    /** 提交前守卫：文档版本 + 函数体哈希 + 注释块哈希。 */
    fun precondition(): Precondition = Precondition(
        documentStamp = documentStamp,
        bodyHash = bodyHash,
        commentHash = Hashing.sha256(commentRawLines.joinToString("\n")),
    )
}

/** 落笔前的并发守卫：任何一项不匹配都放弃本轮，不覆盖用户输入。 */
data class Precondition(
    val documentStamp: Long,
    val bodyHash: String,
    val commentHash: String,
)

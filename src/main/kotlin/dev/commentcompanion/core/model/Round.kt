package dev.commentcompanion.core.model

/**
 * 一轮回合的账本记录（持久化，用于去重、失败重试策略、面板历史）。
 */
data class RoundRecord(
    val key: FunctionKey,
    val roundNo: Int,
    val instructionHash: String,
    val stage: RoundStage,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val model: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val attempts: Int = 0,
    val error: String? = null,
) {
    init {
        require(roundNo >= 1) { "roundNo 从 1 开始" }
        require(instructionHash.isNotBlank()) { "instructionHash 不能为空" }
    }

    /** 推进阶段；非法迁移抛异常（防止状态机被绕过）。 */
    fun advance(to: RoundStage, now: Long = System.currentTimeMillis()): RoundRecord {
        require(RoundStage.canTransition(stage, to)) { "非法阶段迁移：$stage → $to" }
        return copy(
            stage = to,
            finishedAt = if (to.isTerminal) now else finishedAt,
        )
    }
}

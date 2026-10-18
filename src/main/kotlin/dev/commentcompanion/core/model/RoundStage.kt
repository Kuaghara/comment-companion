package dev.commentcompanion.core.model

/**
 * 一轮的生命周期阶段（独立枚举，队列/面板/账本/日志共用同一套名字）。
 *
 * 成功路径：
 * QUEUED → SNAPSHOT → GENERATING → PARSING → APPLYING → VERIFYING → NORMALIZING → DONE
 *
 * 失败/异常路径：
 * - 生成/解析失败 → FAILED
 * - 验证出现新错误 → REPAIRING（重新生成，≤5 轮 / 60s）
 * - 快照后文档已变 → STALE（丢弃，等下一次空闲扫描）
 * - 用户取消/关闭项目 → CANCELLED
 */
enum class RoundStage {
    QUEUED,
    SNAPSHOT,
    GENERATING,
    PARSING,
    APPLYING,
    VERIFYING,
    REPAIRING,
    NORMALIZING,
    DONE,
    FAILED,
    CANCELLED,
    STALE;

    val isTerminal: Boolean
        get() = this == DONE || this == FAILED || this == CANCELLED || this == STALE

    companion object {
        private val ALLOWED: Map<RoundStage, Set<RoundStage>> = mapOf(
            QUEUED to setOf(SNAPSHOT, CANCELLED),
            SNAPSHOT to setOf(GENERATING, STALE, FAILED, CANCELLED),
            GENERATING to setOf(PARSING, FAILED, CANCELLED),
            PARSING to setOf(APPLYING, REPAIRING, FAILED, CANCELLED),
            APPLYING to setOf(VERIFYING, FAILED, CANCELLED, STALE),
            VERIFYING to setOf(NORMALIZING, REPAIRING, FAILED, CANCELLED),
            REPAIRING to setOf(GENERATING, FAILED, CANCELLED),
            NORMALIZING to setOf(DONE, FAILED, CANCELLED),
            DONE to emptySet(),
            FAILED to emptySet(),
            CANCELLED to emptySet(),
            STALE to emptySet(),
        )

        fun canTransition(from: RoundStage, to: RoundStage): Boolean =
            ALLOWED[from]?.contains(to) == true
    }
}

package dev.commentcompanion.ports

import dev.commentcompanion.core.model.FunctionKey
import dev.commentcompanion.core.model.QuestionState
import dev.commentcompanion.core.model.RoundRecord

/**
 * 账本：函数级、可持久化。用于
 * 1. 同指令哈希去重（DONE / WAITING_ANSWER 的注释不会重复处理）；
 * 2. 失败后不自动重排（同一 instructionHash 失败过，除非用户改指令或手动重试）；
 * 3. 面板历史与问题状态。
 */
interface RoundLedgerPort {
    fun lastRecord(key: FunctionKey): RoundRecord?

    /** 同一函数 + 同一指令哈希的最近记录（去重/重试判定）。 */
    fun recordFor(key: FunctionKey, instructionHash: String): RoundRecord?

    fun record(record: RoundRecord)

    fun question(key: FunctionKey): QuestionState?

    /** 扫描去重：该文件 + 指令哈希是否已有待回答的问题。 */
    fun pendingQuestionFor(file: dev.commentcompanion.core.model.FileKey, instructionHash: String): QuestionState?

    fun pendingQuestions(): List<QuestionState>

    fun updateQuestion(state: QuestionState?)

    fun recent(limit: Int): List<RoundRecord>

    fun clear()
}

/** 调度能力：2 秒扫描周期、超时回调等。 */
interface SchedulerPort {
    fun schedule(key: String, delayMillis: Long, action: () -> Unit)

    fun cancel(key: String)

    fun cancelAll()
}

package dev.commentcompanion.core.model

enum class QuestionStatus {
    /** AI 已提出，等待用户回答。 */
    PENDING,

    /** 用户修改了原 `ai：` 指令（等价于回答）。 */
    ANSWERED,

    /** 问题已因代码变化而失效。 */
    MOOT,

    /** 用户显式忽略，或新一轮成功。 */
    DISMISSED,
}

/**
 * AI 反问（`ai？`）的状态。持久化在账本里。
 */
data class QuestionState(
    val key: FunctionKey,
    val questionHash: String,
    val questionLine: Int,
    val instructionHash: String,
    val askedAtRound: Int,
    val status: QuestionStatus = QuestionStatus.PENDING,
    /** AI 的问题原文（落盘后给面板展示）。 */
    val question: String? = null,
)

/**
 * 纯函数的反问问责：扫描时用它决定「这个 ai： 要不要重新入队」。
 */
object QuestionResolver {

    /**
     * @param state 已记录的问题
     * @param currentInstructionHash 当前 `ai：` 指令的哈希
     * @param questionBlockPresent 问题注释（`ai？`）是否仍在文件里
     */
    fun resolve(
        state: QuestionState,
        currentInstructionHash: String,
        questionBlockPresent: Boolean,
    ): QuestionStatus = when {
        !questionBlockPresent -> QuestionStatus.MOOT
        state.status != QuestionStatus.PENDING -> state.status
        currentInstructionHash != state.instructionHash -> QuestionStatus.ANSWERED
        else -> QuestionStatus.PENDING
    }
}

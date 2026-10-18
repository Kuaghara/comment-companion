package dev.commentcompanion.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QuestionTest {

    private val key = FunctionKey("go", FileKey("a.go"), "Add", null, "sig")
    private val state = QuestionState(
        key = key,
        questionHash = "q-hash",
        questionLine = 12,
        instructionHash = "instr-hash",
        askedAtRound = 2,
    )

    @Test
    fun `指令未变且问题还在时保持 PENDING`() {
        assertEquals(
            QuestionStatus.PENDING,
            QuestionResolver.resolve(state, "instr-hash", questionBlockPresent = true),
        )
    }

    @Test
    fun `用户改了指令视为回答`() {
        assertEquals(
            QuestionStatus.ANSWERED,
            QuestionResolver.resolve(state, "other-hash", questionBlockPresent = true),
        )
    }

    @Test
    fun `问题注释被删除视为失效`() {
        assertEquals(
            QuestionStatus.MOOT,
            QuestionResolver.resolve(state, "instr-hash", questionBlockPresent = false),
        )
    }

    @Test
    fun `已经结束的问题状态不会被覆盖`() {
        val dismissed = state.copy(status = QuestionStatus.DISMISSED)
        assertEquals(
            QuestionStatus.DISMISSED,
            QuestionResolver.resolve(dismissed, "other-hash", questionBlockPresent = true),
        )
    }
}

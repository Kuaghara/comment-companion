package dev.commentcompanion.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RoundStageTest {

    @Test
    fun `正常成功路径可迁移`() {
        val path = listOf(
            RoundStage.QUEUED,
            RoundStage.SNAPSHOT,
            RoundStage.GENERATING,
            RoundStage.PARSING,
            RoundStage.APPLYING,
            RoundStage.VERIFYING,
            RoundStage.NORMALIZING,
            RoundStage.DONE,
        )
        path.zipWithNext().forEach { (from, to) ->
            assertTrue(RoundStage.canTransition(from, to), "$from → $to 应允许")
        }
    }

    @Test
    fun `验证失败可进入修复`() {
        assertTrue(RoundStage.canTransition(RoundStage.VERIFYING, RoundStage.REPAIRING))
        assertTrue(RoundStage.canTransition(RoundStage.REPAIRING, RoundStage.GENERATING))
    }

    @Test
    fun `快照后文档变了进入 STALE`() {
        assertTrue(RoundStage.canTransition(RoundStage.SNAPSHOT, RoundStage.STALE))
        assertTrue(RoundStage.canTransition(RoundStage.APPLYING, RoundStage.STALE))
    }

    @Test
    fun `终态不可再迁移`() {
        listOf(RoundStage.DONE, RoundStage.FAILED, RoundStage.CANCELLED, RoundStage.STALE).forEach { stage ->
            assertTrue(stage.isTerminal)
            RoundStage.entries.forEach { target ->
                assertFalse(RoundStage.canTransition(stage, target), "$stage → $target 不应允许")
            }
        }
    }

    @Test
    fun `解析阶段可以进入等待回答`() {
        assertTrue(RoundStage.canTransition(RoundStage.PARSING, RoundStage.WAITING_ANSWER))
        assertTrue(RoundStage.WAITING_ANSWER.isTerminal)
    }

    @Test
    fun `非法迁移被拒绝`() {
        assertFalse(RoundStage.canTransition(RoundStage.QUEUED, RoundStage.DONE))
        assertFalse(RoundStage.canTransition(RoundStage.GENERATING, RoundStage.NORMALIZING))
        assertFalse(RoundStage.canTransition(RoundStage.DONE, RoundStage.QUEUED))
    }

    @Test
    fun `RoundRecord 推进阶段并记录结束时间`() {
        val key = FunctionKey("go", FileKey("a.go"), "Add", null, "sig")
        var record = RoundRecord(key, roundNo = 1, instructionHash = "h", stage = RoundStage.QUEUED, startedAt = 0)
        record = record.advance(RoundStage.SNAPSHOT, now = 1)
        record = record.advance(RoundStage.GENERATING, now = 2)
        assertEquals(RoundStage.GENERATING, record.stage)
        record = record.advance(RoundStage.FAILED, now = 3)
        assertEquals(3L, record.finishedAt)
    }
}

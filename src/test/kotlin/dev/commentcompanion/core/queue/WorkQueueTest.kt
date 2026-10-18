package dev.commentcompanion.core.queue

import dev.commentcompanion.core.model.FileKey
import dev.commentcompanion.core.model.RoundStage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkQueueTest {

    private val a = FileKey("a.go")
    private val b = FileKey("b.go")

    @Test
    fun `入队按序号排序`() {
        val queue = WorkQueue()
        queue.enqueue(b, 10, "h-b", nowMillis = 1)
        queue.enqueue(a, 5, "h-a", nowMillis = 2)
        assertEquals(listOf("h-b", "h-a"), queue.pendingItems().map { it.instructionHash })
    }

    @Test
    fun `同文件同哈希重复入队被忽略`() {
        val queue = WorkQueue()
        queue.enqueue(a, 5, "h", 1)
        assertEquals(EnqueueResult.Ignored, queue.enqueue(a, 5, "h", 2))
        assertEquals(1, queue.pendingCount())
    }

    @Test
    fun `同文件同位置但指令变了则更新`() {
        val queue = WorkQueue()
        val first = queue.enqueue(a, 5, "old", 1) as EnqueueResult.Added
        val updated = queue.enqueue(a, 5, "new", 2) as EnqueueResult.Updated
        assertEquals(first.item.seq, updated.item.seq)
        assertEquals("new", queue.pendingItems().single().instructionHash)
    }

    @Test
    fun `出队后成为 active，且不允许再取`() {
        val queue = WorkQueue()
        queue.enqueue(a, 5, "h", 1)
        val item = queue.next()
        assertNotNull(item)
        assertEquals(RoundStage.SNAPSHOT, item!!.stage)
        assertNotNull(queue.active())
        assertNull(queue.next())
        assertFalse(queue.isIdle())
    }

    @Test
    fun `finish 后释放 active 并允许取下一个`() {
        val queue = WorkQueue()
        queue.enqueue(a, 5, "h1", 1)
        queue.enqueue(b, 5, "h2", 2)
        val first = queue.next()!!
        queue.finish(first)
        val second = queue.next()
        assertNotNull(second)
        assertEquals("h2", second!!.instructionHash)
        queue.finish(second)
        assertTrue(queue.isIdle())
    }

    @Test
    fun `推进阶段只影响活动项`() {
        val queue = WorkQueue()
        queue.enqueue(a, 5, "h", 1)
        val item = queue.next()!!
        queue.markStage(item.seq, RoundStage.GENERATING)
        assertEquals(RoundStage.GENERATING, queue.active()!!.stage)
    }

    @Test
    fun `取消某文件的项并清除其 active`() {
        val queue = WorkQueue()
        queue.enqueue(a, 5, "h1", 1)
        queue.enqueue(b, 5, "h2", 2)
        val item = queue.next()!!
        queue.cancelFile(item.file)
        assertNull(queue.active())
        assertEquals(listOf("h2"), queue.pendingItems().map { it.instructionHash })
    }

    @Test
    fun `取消全部后空闲`() {
        val queue = WorkQueue()
        queue.enqueue(a, 5, "h1", 1)
        queue.enqueue(b, 5, "h2", 2)
        queue.cancelAll()
        assertTrue(queue.isIdle())
        assertEquals(0, queue.pendingCount())
    }
}

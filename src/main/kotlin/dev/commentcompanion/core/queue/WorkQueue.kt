package dev.commentcompanion.core.queue

import dev.commentcompanion.core.model.FileKey
import dev.commentcompanion.core.model.RoundStage

/**
 * 队列项是「坐标 + 指纹」，不是快照。
 *
 * 快照在出队时（[WorkQueue.next] 之后由 RoundExecutor 重新读取文档）生成，
 * 保证 AI 动手时看到的是最新文档；而不是入队时的旧内容。
 */
data class QueueItem(
    val seq: Long,
    val file: FileKey,
    val blockStartLine: Int,
    val instructionHash: String,
    val stage: RoundStage = RoundStage.QUEUED,
    val enqueuedAt: Long,
    val attempts: Int = 0,
)

sealed interface EnqueueResult {
    data class Added(val item: QueueItem) : EnqueueResult
    data class Updated(val item: QueueItem) : EnqueueResult
    data object Ignored : EnqueueResult
}

/**
 * 串行工作队列：同一项目同一时间最多一个活动项。
 *
 * 规则：
 * 1. 同文件 + 同 instructionHash 已在队列/执行中 → 忽略（空闲扫描重复看到同一注释）。
 * 2. 同文件 + 同位置但指令哈希变了 → 更新该项（保持排队位置，避免饿死）。
 * 3. 其余情况追加；顺序由 seq 决定（入队即编号）。
 * 4. 出队即标记为 SNAPSHOT，并成为 active；[finish] 后才允许取下一个。
 */
class WorkQueue {

    private var nextSeq: Long = 1
    private val pending = ArrayList<QueueItem>()
    private var activeItem: QueueItem? = null

    fun enqueue(
        file: FileKey,
        blockStartLine: Int,
        instructionHash: String,
        nowMillis: Long,
        attempts: Int = 0,
    ): EnqueueResult {
        if (instructionHash.isBlank()) return EnqueueResult.Ignored

        if (pending.any { it.file == file && it.instructionHash == instructionHash }) {
            return EnqueueResult.Ignored
        }
        if (activeItem?.file == file && activeItem?.instructionHash == instructionHash) {
            return EnqueueResult.Ignored
        }

        val sameBlock = pending.indexOfFirst { it.file == file && it.blockStartLine == blockStartLine }
        if (sameBlock >= 0) {
            val updated = pending[sameBlock].copy(
                instructionHash = instructionHash,
                attempts = attempts,
            )
            pending[sameBlock] = updated
            return EnqueueResult.Updated(updated)
        }

        val item = QueueItem(
            seq = nextSeq++,
            file = file,
            blockStartLine = blockStartLine,
            instructionHash = instructionHash,
            stage = RoundStage.QUEUED,
            enqueuedAt = nowMillis,
            attempts = attempts,
        )
        pending += item
        return EnqueueResult.Added(item)
    }

    /** 取出队首并标记为 active（SNAPSHOT 阶段）；队列已有 active 时返回 null。 */
    fun next(): QueueItem? {
        if (activeItem != null) return null
        val head = pending.minByOrNull { it.seq } ?: return null
        pending.remove(head)
        val running = head.copy(stage = RoundStage.SNAPSHOT)
        activeItem = running
        return running
    }

    /** 推进活动项阶段。 */
    fun markStage(seq: Long, stage: RoundStage): QueueItem? {
        val current = activeItem
        if (current != null && current.seq == seq) {
            activeItem = current.copy(stage = stage)
            return activeItem
        }
        val index = pending.indexOfFirst { it.seq == seq }
        if (index >= 0) {
            pending[index] = pending[index].copy(stage = stage)
            return pending[index]
        }
        return null
    }

    /** 本轮结束（成功或失败）；释放 active，允许取下一个。 */
    fun finish(item: QueueItem) {
        if (activeItem?.seq == item.seq) activeItem = null
    }

    fun cancelFile(file: FileKey) {
        pending.removeAll { it.file == file }
        if (activeItem?.file == file) activeItem = null
    }

    fun cancelAll() {
        pending.clear()
        activeItem = null
    }

    fun pendingItems(): List<QueueItem> = pending.sortedBy { it.seq }

    fun active(): QueueItem? = activeItem

    /** 空闲 = 没有任何排队项，也没有正在执行的回合。扫描只允许在此时进行。 */
    fun isIdle(): Boolean = pending.isEmpty() && activeItem == null

    fun pendingCount(): Int = pending.size + if (activeItem != null) 1 else 0
}

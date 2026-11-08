package dev.commentcompanion.app

import dev.commentcompanion.core.model.FileKey
import dev.commentcompanion.core.model.Hashing
import dev.commentcompanion.core.protocol.CommentProtocol
import dev.commentcompanion.core.queue.WorkQueue
import dev.commentcompanion.ide.CompanionSettings
import dev.commentcompanion.ide.GoFileTracker
import dev.commentcompanion.ide.IdeWorkspace

/**
 * 每 2 秒的轻量调度：只有在「队列为空 + 没有活动回合 + 未暂停」时才真正审查 dirty 文件。
 *
 * 扫描只做文本级识别，入队坐标；PSI 锚定留给 RoundExecutor 出队后执行。
 */
class ScanCycle(
    private val tracker: GoFileTracker,
    private val workspace: IdeWorkspace,
    private val queue: WorkQueue,
    private val settings: CompanionSettings,
    private val shouldSkip: (FileKey, String) -> Boolean,
) {

    fun tick() {
        val snapshot = settings.snapshot()
        if (!snapshot.enabled) return
        if (!queue.isIdle()) return

        val dirty = tracker.consumeDirty()
        if (dirty.isEmpty()) return

        for (file in dirty) {
            val fileKey = workspace.toFileKey(file) ?: continue
            val text = workspace.readText(fileKey) ?: continue
            for (block in CommentProtocol.directives(text.lines())) {
                val directive = block.directive ?: continue
                if (directive.text.isBlank()) continue
                val hash = Hashing.sha256(directive.text)
                if (shouldSkip(fileKey, hash)) continue
                queue.enqueue(
                    file = fileKey,
                    blockStartLine = block.startLine,
                    instructionHash = hash,
                    nowMillis = System.currentTimeMillis(),
                )
            }
        }
    }
}

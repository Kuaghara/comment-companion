package dev.commentcompanion.ide

import com.google.gson.Gson
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import dev.commentcompanion.core.model.FunctionSnapshot
import dev.commentcompanion.core.queue.QueueItem

/** 一轮落笔前的意图记录，用于崩溃恢复。 */
data class JournalEntry(
    val file: String,
    val blockStartLine: Int,
    val instructionHash: String,
    /** PENDING（还没写 body）/ APPLIED（body 已写） */
    val phase: String,
    val originalBody: String,
)

@State(name = "CommentCompanionJournal", storages = [Storage("comment-companion-journal.xml")])
@Service(Service.Level.PROJECT)
class RoundJournalStore(@Suppress("unused") private val project: Project) :
    PersistentStateComponent<RoundJournalStore.State> {

    class State {
        var entryJson: String? = null
    }

    private val gson = Gson()
    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    @Synchronized
    fun begin(item: QueueItem, snapshot: FunctionSnapshot) {
        state.entryJson = gson.toJson(
            JournalEntry(
                file = item.file.relativePath,
                blockStartLine = item.blockStartLine,
                instructionHash = item.instructionHash,
                phase = "PENDING",
                originalBody = snapshot.bodyText,
            ),
        )
    }

    @Synchronized
    fun markApplied() {
        val entry = pending() ?: return
        state.entryJson = gson.toJson(entry.copy(phase = "APPLIED"))
    }

    @Synchronized
    fun pending(): JournalEntry? {
        val json = state.entryJson ?: return null
        return runCatching { gson.fromJson(json, JournalEntry::class.java) }.getOrNull()
    }

    @Synchronized
    fun clear() {
        state.entryJson = null
    }
}

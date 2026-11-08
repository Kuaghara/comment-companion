package dev.commentcompanion.ide

import com.google.gson.Gson
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import dev.commentcompanion.core.model.FunctionKey
import dev.commentcompanion.core.model.QuestionState
import dev.commentcompanion.core.model.RoundRecord
import dev.commentcompanion.ports.RoundLedgerPort

/**
 * 账本：project 级持久化（`.idea/comment-companion-ledger.xml`）。
 *
 * 为了避开 XML bean 序列化的坑，记录以 JSON 字符串数组落盘（Gson 平台自带）。
 */
@State(name = "CommentCompanionLedger", storages = [Storage("comment-companion-ledger.xml")])
@Service(Service.Level.PROJECT)
class RoundLedgerStore(@Suppress("unused") private val project: Project) :
    PersistentStateComponent<RoundLedgerStore.State>, RoundLedgerPort {

    class State {
        var records: MutableList<String> = mutableListOf()
        var questions: MutableList<String> = mutableListOf()
    }

    private val gson = Gson()
    private val records = ArrayList<RoundRecord>()
    private val questions = LinkedHashMap<String, QuestionState>()
    private var state = State()

    override fun getState(): State = synchronized(this) {
        state.records = records.map { gson.toJson(it) }.toMutableList()
        state.questions = questions.values.map { gson.toJson(it) }.toMutableList()
        state
    }

    override fun loadState(state: State) {
        synchronized(this) {
            this.state = state
            records.clear()
            questions.clear()
            state.records.forEach { json ->
                runCatching { records += gson.fromJson(json, RoundRecord::class.java) }
            }
            state.questions.forEach { json ->
                runCatching {
                    val parsed = gson.fromJson(json, QuestionState::class.java)
                    questions[parsed.key.ledgerKey()] = parsed
                }
            }
        }
    }

    @Synchronized
    override fun lastRecord(key: FunctionKey): RoundRecord? =
        records.lastOrNull { it.key == key }

    @Synchronized
    override fun recordFor(key: FunctionKey, instructionHash: String): RoundRecord? =
        records.lastOrNull { it.key == key && it.instructionHash == instructionHash }

    @Synchronized
    override fun record(record: RoundRecord) {
        records += record
        if (records.size > 200) records.subList(0, records.size - 200).clear()
    }

    @Synchronized
    override fun question(key: FunctionKey): QuestionState? = questions[key.ledgerKey()]

    @Synchronized
    override fun pendingQuestions(): List<QuestionState> =
        questions.values.filter { it.status == dev.commentcompanion.core.model.QuestionStatus.PENDING }

    @Synchronized
    override fun pendingQuestionFor(
        file: dev.commentcompanion.core.model.FileKey,
        instructionHash: String,
    ): QuestionState? = questions.values.firstOrNull {
        it.status == dev.commentcompanion.core.model.QuestionStatus.PENDING &&
            it.key.file == file &&
            it.instructionHash == instructionHash
    }

    @Synchronized
    override fun updateQuestion(state: QuestionState?) {
        if (state == null) return
        questions[state.key.ledgerKey()] = state
    }

    @Synchronized
    override fun recent(limit: Int): List<RoundRecord> = records.takeLast(limit)

    @Synchronized
    override fun clear() {
        records.clear()
        questions.clear()
    }
}

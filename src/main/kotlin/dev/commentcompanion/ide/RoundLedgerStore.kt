package dev.commentcompanion.ide

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import dev.commentcompanion.core.model.FunctionKey
import dev.commentcompanion.core.model.QuestionState
import dev.commentcompanion.core.model.RoundRecord
import dev.commentcompanion.ports.RoundLedgerPort

/**
 * 账本（M1b 内存版；M2 换成 project 级 PersistentStateComponent，存 .idea 下）。
 */
@Service(Service.Level.PROJECT)
class RoundLedgerStore(@Suppress("unused") private val project: Project) : RoundLedgerPort {

    private val records = LinkedHashMap<String, RoundRecord>()
    private val questions = HashMap<String, QuestionState>()

    override fun lastRecord(key: FunctionKey): RoundRecord? =
        records.values.lastOrNull { it.key == key }

    override fun recordFor(key: FunctionKey, instructionHash: String): RoundRecord? =
        records.values.lastOrNull { it.key == key && it.instructionHash == instructionHash }

    override fun record(record: RoundRecord) {
        records[record.key.ledgerKey()] = record
    }

    override fun question(key: FunctionKey): QuestionState? = questions[key.ledgerKey()]

    override fun updateQuestion(state: QuestionState?) {
        if (state == null) return
        questions[state.key.ledgerKey()] = state
    }

    override fun recent(limit: Int): List<RoundRecord> = records.values.toList().takeLast(limit)

    override fun clear() {
        records.clear()
        questions.clear()
    }
}

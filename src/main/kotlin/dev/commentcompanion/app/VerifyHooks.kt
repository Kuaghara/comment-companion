package dev.commentcompanion.app

import java.io.File

/**
 * 沙盒自动化验证钩子：仅当设置了 -Dcommentcompanion.verifyRounds 时写 JSONL。
 * 用于 M1b 端到端验证（扫描 → 队列 → FakeEngine → 落笔 → 记账），日常使用无副作用。
 */
object VerifyHooks {

    fun roundCompleted(functionName: String, stage: String, detail: String = "") {
        val path = System.getProperty("commentcompanion.verifyRounds") ?: return
        runCatching {
            val line = "{\"function\":\"${escape(functionName)}\",\"stage\":\"${escape(stage)}\",\"detail\":\"${escape(detail)}\"}\n"
            File(path).appendText(line)
        }
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}

package dev.commentcompanion.core.prompt

/** 发给引擎的纯数据结构（不含 HTTP/JSON 细节；引擎适配器负责编码）。 */
data class PromptPayload(
    val languageId: String,
    val file: String,
    val functionName: String,
    val signature: String,
    val descriptionLines: List<String>,
    val existingBody: String,
    val instruction: String,
    val repairErrors: List<String> = emptyList(),
)

data class EngineRequest(
    val payload: PromptPayload,
    val model: String,
    val temperature: Double,
    val timeoutMillis: Long,
)

/** 模型建议新增的 import。 */
data class EngineImport(
    val path: String,
    val alias: String? = null,
)

/** 模型输出：新函数体 + 规范化注释（注释由模型产出）+ 可选 imports/反问。 */
data class EngineOutput(
    val body: String,
    val comment: List<String>,
    val question: String? = null,
    val imports: List<EngineImport> = emptyList(),
)

enum class EngineFailure {
    NETWORK,
    TIMEOUT,
    AUTH,
    RATE_LIMIT,
    BAD_REQUEST,
    PARSE,
    CANCELLED,
    UNKNOWN,
}

sealed interface EngineResult {
    data class Success(val output: EngineOutput) : EngineResult

    data class Failure(
        val kind: EngineFailure,
        val message: String,
        val retryable: Boolean,
    ) : EngineResult
}

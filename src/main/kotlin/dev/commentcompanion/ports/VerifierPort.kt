package dev.commentcompanion.ports

import dev.commentcompanion.core.model.FileKey

/** 诊断指纹：用于 baseline 与新错误的差集。 */
data class DiagnosticSignature(
    val file: String,
    val line: Int,
    val message: String,
)

data class VerificationBaseline(val errors: Set<DiagnosticSignature>)

data class VerificationResult(
    val ok: Boolean,
    val newErrors: List<String>,
)

/**
 * 验证能力。用户已选择：GO_BUILD 方式直接跑 `go build ./...`（设置里可关）。
 */
interface VerifierPort {
    fun baseline(file: FileKey): VerificationBaseline

    fun verify(file: FileKey, baseline: VerificationBaseline): VerificationResult
}

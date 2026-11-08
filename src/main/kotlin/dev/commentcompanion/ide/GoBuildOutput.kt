package dev.commentcompanion.ide

import dev.commentcompanion.ports.DiagnosticSignature

/** 解析 `go build` / `go vet` 的错误输出为诊断指纹（纯函数，可单测）。 */
object GoBuildOutput {

    // 形如： ./calc/add.go:12:5: undefined: x
    //       /abs/path/calc/add.go:12: undefined: x
    private val ERROR_LINE = Regex("""^(\S*?\.go):(\d+)(?::\d+)?:\s*(.+)$""")

    fun parse(output: String): List<DiagnosticSignature> =
        output.lineSequence()
            .mapNotNull { line ->
                val match = ERROR_LINE.find(line.trim()) ?: return@mapNotNull null
                val file = match.groupValues[1].removePrefix("./")
                val lineNo = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                val message = match.groupValues[3].trim()
                DiagnosticSignature(file = file, line = lineNo, message = message)
            }
            .distinct()
            .toList()
}

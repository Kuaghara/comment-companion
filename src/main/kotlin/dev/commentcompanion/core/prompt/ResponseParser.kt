package dev.commentcompanion.core.prompt

import com.google.gson.JsonParser

/**
 * 解析模型输出：优先严格 JSON（body/comment/question），
 * 失败时尝试从 Markdown 围栏里抠 JSON。
 */
object ResponseParser {

    sealed interface Result {
        data class Ok(val output: EngineOutput) : Result
        data class Invalid(val reason: String) : Result
    }

    fun parse(raw: String): Result {
        val json = extractJson(raw) ?: return Result.Invalid("未找到 JSON 输出")
        val element = runCatching { JsonParser.parseString(json) }.getOrNull()
            ?: return Result.Invalid("JSON 解析失败")
        if (!element.isJsonObject) return Result.Invalid("输出不是 JSON 对象")
        val obj = element.asJsonObject

        val body = obj.get("body")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        val question = obj.get("question")?.takeIf { it.isJsonPrimitive && !it.asJsonPrimitive.isJsonNull }?.asString
            ?.trim()?.takeIf { it.isNotEmpty() }
        val commentElement = obj.get("comment")
        val comment = when {
            commentElement == null || commentElement.isJsonNull -> emptyList()
            commentElement.isJsonArray -> commentElement.asJsonArray.map {
                it.takeIf { e -> e.isJsonPrimitive }?.asString?.trim().orEmpty()
            }.filter { it.isNotEmpty() }
            commentElement.isJsonPrimitive -> commentElement.asString.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            else -> emptyList()
        }

        val imports = when (val importsElement = obj.get("imports")) {
            null -> emptyList()
            else -> when {
                importsElement.isJsonNull -> emptyList()
                importsElement.isJsonArray -> importsElement.asJsonArray.mapNotNull { item ->
                    when {
                        item.isJsonObject -> {
                            val path = item.asJsonObject.get("path")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
                            if (path.isNullOrBlank()) null
                            else EngineImport(path, item.asJsonObject.get("alias")?.takeIf { it.isJsonPrimitive }?.asString)
                        }
                        item.isJsonPrimitive -> item.asString.trim().takeIf { it.isNotBlank() }?.let { EngineImport(it) }
                        else -> null
                    }
                }
                else -> emptyList()
            }
        }

        if (body.isEmpty()) {
            return if (question != null) Result.Ok(EngineOutput(body = "", comment = emptyList(), question = question))
            else Result.Invalid("JSON 缺少 body")
        }
        if (comment.isEmpty() && question == null) return Result.Invalid("JSON 缺少 comment")
        return Result.Ok(EngineOutput(body = body, comment = comment, question = question, imports = imports))
    }

    /** 优先直接解析；否则找第一个「看起来完整」的 JSON 对象（支持围栏）。 */
    fun extractJson(raw: String): String? {
        val text = raw.trim()
        if (text.startsWith("{")) return text
        val fenced = Regex("```(?:json)?\\s*(\\{.*?})\\s*```", RegexOption.DOT_MATCHES_ALL).find(text)
        if (fenced != null) return fenced.groupValues[1]
        val start = text.indexOf('{')
        if (start < 0) return null
        // 简单括号配平（忽略字符串内的括号）
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }
}

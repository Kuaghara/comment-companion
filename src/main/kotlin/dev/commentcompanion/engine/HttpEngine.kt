package dev.commentcompanion.engine

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.commentcompanion.core.prompt.EngineFailure
import dev.commentcompanion.core.prompt.EngineRequest
import dev.commentcompanion.core.prompt.EngineResult
import dev.commentcompanion.core.prompt.PromptBuilder
import dev.commentcompanion.core.prompt.ResponseParser
import dev.commentcompanion.ports.EngineConfig
import dev.commentcompanion.ports.EnginePort
import dev.commentcompanion.ports.EngineProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 三种协议的 HTTP 适配器：
 * - OpenAI Chat Completions（含各类兼容网关）
 * - OpenAI Responses
 * - Anthropic Messages
 *
 * 只做「请求构造 → HTTP → 取文本 → ResponseParser」；prompt 语义在 core。
 */
class HttpEngine(
    private val protocol: EngineProtocol,
    private val config: EngineConfig,
    private val apiKey: String,
) : EnginePort {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(config.timeoutMillis.coerceAtMost(30_000)))
        .build()

    override suspend fun generate(request: EngineRequest): EngineResult = withContext(Dispatchers.IO) {
        val messages = PromptBuilder.build(request.payload)
        val prepared = when (protocol) {
            EngineProtocol.OPENAI_CHAT_COMPLETIONS -> prepareChat(messages, request)
            EngineProtocol.OPENAI_RESPONSES -> prepareResponses(messages, request)
            EngineProtocol.ANTHROPIC_MESSAGES -> prepareAnthropic(messages, request)
        }
        try {
            val httpRequest = HttpRequest.newBuilder(URI.create(prepared.uri))
                .timeout(Duration.ofMillis(request.timeoutMillis))
                .header("Content-Type", "application/json")
                .apply { prepared.headers.forEach { (k, v) -> header(k, v) } }
                .POST(HttpRequest.BodyPublishers.ofString(prepared.body))
                .build()
            val response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                return@withContext EngineResult.Failure(
                    kind = when (response.statusCode()) {
                        401, 403 -> EngineFailure.AUTH
                        429 -> EngineFailure.RATE_LIMIT
                        in 400..499 -> EngineFailure.BAD_REQUEST
                        else -> EngineFailure.UNKNOWN
                    },
                    message = "HTTP ${response.statusCode()}: ${response.body().take(300)}",
                    retryable = response.statusCode() == 429 || response.statusCode() >= 500,
                )
            }
            val text = extractText(protocol, response.body())
                ?: return@withContext EngineResult.Failure(EngineFailure.PARSE, "响应里没有文本内容", false)
            when (val parsed = ResponseParser.parse(text)) {
                is ResponseParser.Result.Ok -> EngineResult.Success(parsed.output)
                is ResponseParser.Result.Invalid -> EngineResult.Failure(EngineFailure.PARSE, parsed.reason, true)
            }
        } catch (e: java.net.http.HttpTimeoutException) {
            EngineResult.Failure(EngineFailure.TIMEOUT, "请求超时", true)
        } catch (e: java.io.IOException) {
            EngineResult.Failure(EngineFailure.NETWORK, e.message ?: "网络错误", true)
        } catch (e: Throwable) {
            EngineResult.Failure(EngineFailure.UNKNOWN, e.message ?: e.toString(), false)
        }
    }

    private data class Prepared(val uri: String, val headers: Map<String, String>, val body: String)

    private fun base(): String = config.baseUrl.trimEnd('/')

    private fun effectiveModel(request: EngineRequest): String =
        request.model.ifBlank { config.model }

    private fun prepareChat(messages: PromptBuilder.Messages, request: EngineRequest): Prepared {
        val body = JsonObject().apply {
            addProperty("model", effectiveModel(request))
            addProperty("temperature", request.temperature)
            add("messages", JsonParser.parseString(
                """[{"role":"system","content":${json(messages.system)}},{"role":"user","content":${json(messages.user)}}]"""
            ))
        }
        return Prepared("${base()}/chat/completions", mapOf("Authorization" to "Bearer $apiKey"), body.toString())
    }

    private fun prepareResponses(messages: PromptBuilder.Messages, request: EngineRequest): Prepared {
        val body = JsonObject().apply {
            addProperty("model", effectiveModel(request))
            addProperty("instructions", messages.system)
            addProperty("input", messages.user)
            addProperty("temperature", request.temperature)
        }
        return Prepared("${base()}/responses", mapOf("Authorization" to "Bearer $apiKey"), body.toString())
    }

    private fun prepareAnthropic(messages: PromptBuilder.Messages, request: EngineRequest): Prepared {
        val body = JsonObject().apply {
            addProperty("model", effectiveModel(request))
            addProperty("max_tokens", 4096)
            addProperty("temperature", request.temperature)
            addProperty("system", messages.system)
            add("messages", JsonParser.parseString("""[{"role":"user","content":${json(messages.user)}}]"""))
        }
        return Prepared(
            "${base()}/v1/messages",
            mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to "2023-06-01",
            ),
            body.toString(),
        )
    }

    private fun extractText(protocol: EngineProtocol, raw: String): String? {
        val root = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return null
        return when (protocol) {
            EngineProtocol.OPENAI_CHAT_COMPLETIONS -> root
                .getAsJsonArray("choices")?.firstOrNull()
                ?.asJsonObject?.getAsJsonObject("message")
                ?.get("content")?.takeIf { it.isJsonPrimitive }?.asString

            EngineProtocol.OPENAI_RESPONSES -> {
                val output = root.getAsJsonArray("output")
                val texts = output?.mapNotNull { item ->
                    val obj = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                    if (obj.get("type")?.asString != "message") return@mapNotNull null
                    obj.getAsJsonArray("content")?.mapNotNull { c ->
                        val co = c.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                        if (co.get("type")?.asString == "output_text") co.get("text")?.asString else null
                    }
                }?.flatten()
                if (!texts.isNullOrEmpty()) texts.joinToString("\n")
                else root.get("output_text")?.takeIf { it.isJsonPrimitive }?.asString
            }

            EngineProtocol.ANTHROPIC_MESSAGES -> {
                val content = root.getAsJsonArray("content")
                val texts = content?.mapNotNull { item ->
                    val obj = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                    if (obj.get("type")?.asString == "text") obj.get("text")?.asString else null
                }
                texts?.takeIf { it.isNotEmpty() }?.joinToString("\n")
            }
        }
    }

    private fun json(value: String): String =
        com.google.gson.JsonPrimitive(value).toString()
}

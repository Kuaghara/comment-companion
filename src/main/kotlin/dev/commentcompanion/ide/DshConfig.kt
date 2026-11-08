package dev.commentcompanion.ide

import dev.commentcompanion.ports.EngineProtocol
import java.io.File

/**
 * 从 DSH（DeepSeek Harness）读取模型配置，实现「AI 从 DSH 拿配置」。
 *
 * 读取对象：
 * - `~/.dsh/profiles/<profile>/cordis.patch.yml`：agent-default-model + llm-pi-ai providers
 * - `~/.dsh/.credentials.yaml`：refs.<ENV> 对应的密钥
 *
 * 只做只读解析，不修改 DSH 配置；API Key 只留在内存里。
 */
data class DshEngineConfig(
    val profileFile: String,
    val provider: String,
    val model: String,
    val protocol: EngineProtocol,
    val baseUrl: String,
    val apiKeyEnv: String?,
    val apiKey: String?,
)

object DshConfig {

    fun resolve(home: String = System.getProperty("user.home") ?: ""): DshEngineConfig? {
        val dshDir = File(home, ".dsh")
        if (!dshDir.isDirectory) return null
        val profileFile = findProfile(dshDir) ?: return null
        val credentialsFile = File(dshDir, ".credentials.yaml")
        return runCatching {
            parseProfile(
                profileFile.readText(),
                if (credentialsFile.isFile) credentialsFile.readText() else null,
                profileFile.path,
            )
        }.getOrNull()
    }

    private fun findProfile(dshDir: File): File? {
        val desktop = File(dshDir, "profiles/desktop/cordis.patch.yml")
        if (desktop.isFile) return desktop
        val profiles = File(dshDir, "profiles")
        return profiles.listFiles()
            ?.mapNotNull { dir -> File(dir, "cordis.patch.yml").takeIf { it.isFile } }
            ?.sortedBy { it.path }
            ?.firstOrNull()
    }

    /** 从 profile YAML + credentials YAML 解析（纯函数，便于单测）。 */
    fun parseProfile(yaml: String, credentials: String?, profileFile: String = "<memory>"): DshEngineConfig? {
        val lines = yaml.lines()
        val agent = blockOf(lines, "agent-default-model") ?: return null
        val provider = firstMatch(agent, Regex("""^\s*provider:\s*(\S+)\s*$""")) ?: return null
        val model = firstMatch(agent, Regex("""^\s*model:\s*(\S+)\s*$""")) ?: return null

        val llmBlock = blockOf(lines, "llm-pi-ai") ?: return null
        val providerBlock = providerBlockOf(llmBlock, provider) ?: return null
        val api = firstMatch(providerBlock, Regex("""^\s*api:\s*(\S+)\s*$"""))
        val baseUrl = firstMatch(providerBlock, Regex("""^\s*baseURL:\s*(\S+)\s*$""")) ?: return null
        val apiKeyEnv = firstMatch(providerBlock, Regex("""^\s*apiKeyEnv:\s*(\S+)\s*$"""))
        val key = apiKeyEnv?.let { env ->
            parseCredentialRefs(credentials ?: "")[env] ?: System.getenv(env)
        }
        return DshEngineConfig(
            profileFile = profileFile,
            provider = provider,
            model = model,
            protocol = protocolOf(api),
            baseUrl = baseUrl.trimEnd('/'),
            apiKeyEnv = apiKeyEnv,
            apiKey = key?.takeIf { it.isNotBlank() },
        )
    }

    fun protocolOf(api: String?): EngineProtocol = when (api?.lowercase()) {
        "anthropic-messages" -> EngineProtocol.ANTHROPIC_MESSAGES
        "openai-responses" -> EngineProtocol.OPENAI_RESPONSES
        else -> EngineProtocol.OPENAI_CHAT_COMPLETIONS
    }

    /** 取 `- id: <id>` 开头、到下一个顶层 `- id:` 之前的行。 */
    private fun blockOf(lines: List<String>, id: String): List<String>? {
        val start = lines.indexOfFirst { it.trimStart().startsWith("- id:") && it.substringAfter("id:").trim() == id }
        if (start < 0) return null
        val end = (start + 1 until lines.size).firstOrNull { index ->
            val line = lines[index]
            line.isNotEmpty() && !line[0].isWhitespace() && line.trimStart().startsWith("- id:")
        } ?: lines.size
        return lines.subList(start, end)
    }

    /** 在 llm-pi-ai 块里取 providers.<provider> 的子块。 */
    private fun providerBlockOf(llmBlock: List<String>, provider: String): List<String>? {
        val providersLine = llmBlock.indexOfFirst { it.trimStart().startsWith("providers:") }
        if (providersLine < 0) return null
        val providerLine = (providersLine + 1 until llmBlock.size).firstOrNull {
            llmBlock[it].trimStart().startsWith("$provider:") &&
                llmBlock[it].indexOf(provider) > llmBlock[providersLine].indexOf("providers")
        } ?: return null
        val providerIndent = llmBlock[providerLine].indexOf(provider)
        val end = (providerLine + 1 until llmBlock.size).firstOrNull {
            val line = llmBlock[it]
            line.isNotBlank() && line.firstOrNull()?.isWhitespace() == false
        } ?: (providerLine + 1 until llmBlock.size).firstOrNull { index ->
            val line = llmBlock[index]
            line.isNotBlank() && line.indexOfFirst { c -> c != ' ' } <= providerIndent
        } ?: llmBlock.size
        return llmBlock.subList(providerLine + 1, end)
    }

    /** credentials.yaml 的 refs 段：`  NAME: secret`。 */
    fun parseCredentialRefs(credentials: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var inRefs = false
        for (line in credentials.lines()) {
            if (line.trimStart().startsWith("refs:")) {
                inRefs = true
                continue
            }
            if (!inRefs) continue
            if (line.isNotBlank() && line.firstOrNull()?.isWhitespace() == false) {
                inRefs = false
                continue
            }
            val match = Regex("""^\s+([A-Z0-9_]+):\s*(\S+)\s*$""").find(line) ?: continue
            result[match.groupValues[1]] = match.groupValues[2]
        }
        return result
    }

    private fun firstMatch(lines: List<String>, regex: Regex): String? =
        lines.firstNotNullOfOrNull { regex.find(it)?.groupValues?.get(1) }
}

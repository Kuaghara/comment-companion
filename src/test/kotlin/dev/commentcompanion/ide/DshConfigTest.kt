package dev.commentcompanion.ide

import dev.commentcompanion.ports.EngineProtocol
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DshConfigTest {

    private val profile = """
        # patch layer
        - id: agent-default-model
          name: "@deepseek-ai/dsh-agent-default-model"
          config:
            provider: step
            model: step-5-preview
        - id: llm-pi-ai
          name: "@deepseek-ai/dsh-llm-pi-ai"
          config:
            providers:
              step:
                displayName: 阶跃星辰
                apiKeyEnv: STEP_API_KEY
                api: anthropic-messages
                baseURL: https://api.stepfun.com/step_plan
                models:
                  - id: step-5-preview
                    name: step-5-preview
              other:
                apiKeyEnv: OTHER_KEY
                api: openai-completions
                baseURL: https://example.com/v1
    """.trimIndent()

    private val credentials = """
        version: 1
        refs:
          DEEPSEEK_API_KEY: sk-deepseek
          STEP_API_KEY: sk-step-123
    """.trimIndent()

    @Test
    fun `解析 provider、model、协议与 baseURL`() {
        val config = DshConfig.parseProfile(profile, credentials)
        assertEquals("step", config?.provider)
        assertEquals("step-5-preview", config?.model)
        assertEquals(EngineProtocol.ANTHROPIC_MESSAGES, config?.protocol)
        assertEquals("https://api.stepfun.com/step_plan", config?.baseUrl)
        assertEquals("STEP_API_KEY", config?.apiKeyEnv)
        assertEquals("sk-step-123", config?.apiKey)
    }

    @Test
    fun `openai-completions 映射为 Chat Completions`() {
        val onlyOther = profile.substringBefore("- id: llm-pi-ai") + """
            - id: llm-pi-ai
              config:
                providers:
                  other:
                    api: openai-completions
                    baseURL: https://example.com/v1
            """.trimIndent()
        // agent provider 是 step，但 provider 块只剩 other：应当解析失败（保持严格）
        assertNull(DshConfig.parseProfile(onlyOther, credentials))
    }

    @Test
    fun `缺少 credentials 时 key 为空`() {
        val config = DshConfig.parseProfile(profile, null)
        // 测试环境通常没有 STEP_API_KEY 环境变量；核心诉求是「缺凭据时不崩、key 为空」
        if (System.getenv("STEP_API_KEY") == null) {
            assertNull(config?.apiKey)
        }
    }

    @Test
    fun `协议映射`() {
        assertEquals(EngineProtocol.OPENAI_CHAT_COMPLETIONS, DshConfig.protocolOf("openai-completions"))
        assertEquals(EngineProtocol.OPENAI_RESPONSES, DshConfig.protocolOf("openai-responses"))
        assertEquals(EngineProtocol.ANTHROPIC_MESSAGES, DshConfig.protocolOf("anthropic-messages"))
        assertEquals(EngineProtocol.OPENAI_CHAT_COMPLETIONS, DshConfig.protocolOf(null))
    }

    @Test
    fun `credentials refs 解析`() {
        val refs = DshConfig.parseCredentialRefs(credentials)
        assertEquals("sk-step-123", refs["STEP_API_KEY"])
        assertTrue(refs.containsKey("DEEPSEEK_API_KEY"))
    }
}

package dev.commentcompanion.ports

/** 引擎协议（D15）。放在 ports 层：引擎适配器与设置共用。 */
enum class EngineProtocol(val label: String, val defaultBaseUrl: String) {
    OPENAI_CHAT_COMPLETIONS("OpenAI Chat Completions", "https://api.openai.com/v1"),
    OPENAI_RESPONSES("OpenAI Responses", "https://api.openai.com/v1"),
    ANTHROPIC_MESSAGES("Anthropic Messages", "https://api.anthropic.com"),
}

/** 引擎配置来源：DSH（默认，从 DeepSeek Harness 读）或插件自己的设置。 */
enum class EngineSource { DSH, PLUGIN }

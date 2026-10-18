package dev.commentcompanion.core.model

import java.security.MessageDigest

/** 统一哈希工具（SHA-256 → 小写 hex）。 */
object Hashing {
    fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append("%02x".format(b))
        return sb.toString()
    }
}

/**
 * 文件身份：项目内相对路径（统一 `/` 分隔）。
 *
 * 不使用绝对路径：账本尽量可跨机器、可读。
 */
data class FileKey(val relativePath: String) {
    init {
        require(relativePath.isNotBlank()) { "relativePath 不能为空" }
    }

    val extension: String
        get() = relativePath.substringAfterLast('.', "").lowercase()
}

/**
 * 函数身份：跨编辑稳定，账本/队列/问题归属的主键。
 *
 * @property languageId 语言标识（"go"；为未来多语言预留）
 * @property file 项目内相对路径
 * @property name 函数名（方法名不含接收者）
 * @property receiverType 方法接收者类型文本，函数为 null
 * @property signatureHash 规范化签名文本的 SHA-256（不含函数体、不含注释；由语言适配器计算）
 */
data class FunctionKey(
    val languageId: String,
    val file: FileKey,
    val name: String,
    val receiverType: String? = null,
    val signatureHash: String,
) {
    init {
        require(languageId.isNotBlank()) { "languageId 不能为空" }
        require(name.isNotBlank()) { "name 不能为空" }
        require(signatureHash.isNotBlank()) { "signatureHash 不能为空" }
    }

    /** 账本持久化用的稳定字符串 key。 */
    fun ledgerKey(): String =
        listOf(languageId, file.relativePath, receiverType ?: "", name, signatureHash).joinToString("|")
}

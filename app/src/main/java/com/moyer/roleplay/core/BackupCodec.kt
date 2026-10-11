package com.moyer.roleplay.core

import com.moyer.roleplay.data.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

val appJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable
data class BackupBundle(
    val format: String = "roleplay.backup",
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val characters: List<CharacterCard>,
    val connections: List<ConnectionProfile> = emptyList(),
    val sessions: List<ChatSession>,
    val messages: List<ChatMessage>
)

object BackupCodec {
    const val MAX_BYTES = 16 * 1024 * 1024

    fun encode(bundle: BackupBundle): String = appJson.encodeToString(bundle)

    fun decode(text: String): BackupBundle {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "备份超过 16 MB，暂不支持" }
        val bundle = try { appJson.decodeFromString<BackupBundle>(text) }
        catch (_: Exception) { throw IllegalArgumentException("文件不是有效的 Role Play JSON 记录") }
        validate(bundle)
        return bundle
    }

    fun validate(bundle: BackupBundle) {
        require(bundle.format in setOf("roleplay.backup", "roleplay.chat") && bundle.version == 1) {
            "不支持该文件格式或版本"
        }
        require(bundle.characters.size <= 5000 && bundle.connections.size <= 500 &&
            bundle.sessions.size <= 10000 && bundle.messages.size <= 100000) { "备份对象数量超出限制" }
        fun validIds(ids: List<String>) = ids.all { it.isNotBlank() && it.length <= 128 } && ids.distinct().size == ids.size
        require(validIds(bundle.characters.map { it.id }) && validIds(bundle.connections.map { it.id }) &&
            validIds(bundle.sessions.map { it.id }) && validIds(bundle.messages.map { it.id })) { "记录 ID 缺失或重复" }
        val characterIds = bundle.characters.map { it.id }.toSet()
        val connectionIds = bundle.connections.map { it.id }.toSet()
        val sessionIds = bundle.sessions.map { it.id }.toSet()
        require(bundle.characters.all { it.name.isNotBlank() && it.name.length <= 200 }) { "角色名称无效" }
        require(bundle.connections.all { it.name.isNotBlank() && it.maxOutputTokens in 1..65536 }) { "连接配置无效" }
        require(bundle.sessions.all {
            it.characterId in characterIds && (it.connectionId == null || it.connectionId in connectionIds) &&
                it.title.isNotBlank() && it.createdAt >= 0 && it.updatedAt >= 0
        }) { "会话引用的角色或连接缺失" }
        bundle.sessions.forEach {
            val snapshot = runCatching { appJson.decodeFromString<CharacterCard>(it.characterSnapshot) }.getOrNull()
            require(snapshot != null && snapshot.id == it.characterId && snapshot.name.isNotBlank()) { "角色快照无效" }
        }
        require(bundle.messages.all { it.sessionId in sessionIds && it.role in setOf("user", "assistant") &&
            it.status in setOf("complete", "streaming", "interrupted", "failed") && it.position >= 0 && it.createdAt >= 0
        }) { "消息引用或状态无效" }
        require(bundle.messages.map { it.sessionId to it.position }.distinct().size == bundle.messages.size) { "消息排序重复" }
    }

    fun remap(bundle: BackupBundle): BackupBundle {
        validate(bundle)
        val characters = bundle.characters.associate { it.id to newId() }
        val connections = bundle.connections.associate { it.id to newId() }
        val sessions = bundle.sessions.associate { it.id to newId() }
        return bundle.copy(
            characters = bundle.characters.map { it.copy(id = characters.getValue(it.id)) },
            connections = bundle.connections.map { it.copy(id = connections.getValue(it.id), name = it.name + "（导入）") },
            sessions = bundle.sessions.map {
                val snapshot = appJson.decodeFromString<CharacterCard>(it.characterSnapshot)
                    .copy(id = characters.getValue(it.characterId))
                it.copy(id = sessions.getValue(it.id), characterId = characters.getValue(it.characterId),
                    characterSnapshot = appJson.encodeToString(snapshot),
                    connectionId = it.connectionId?.let { id -> connections.getValue(id) })
            },
            messages = bundle.messages.map { it.copy(id = newId(), sessionId = sessions.getValue(it.sessionId),
                status = if (it.status == "streaming") "interrupted" else it.status) }
        )
    }
}

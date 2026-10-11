package com.moyer.roleplay.core

import com.moyer.roleplay.data.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

val appJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable
data class BackupBundle(
    val format: String = "roleplay.backup",
    val version: Int = 2,
    val exportedAt: Long = System.currentTimeMillis(),
    val characters: List<CharacterCard>,
    val connections: List<ConnectionProfile> = emptyList(),
    val sessions: List<ChatSession>,
    val messages: List<ChatMessage>,
    val resources: List<ResourceAsset> = emptyList(),
    val defaultsConfig: String = "{}"
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
        require(bundle.format in setOf("roleplay.backup", "roleplay.chat") && bundle.version in 1..2) {
            "不支持该文件格式或版本"
        }
        require(bundle.characters.size <= 5000 && bundle.connections.size <= 500 &&
            bundle.sessions.size <= 10000 && bundle.messages.size <= 100000 && bundle.resources.size <= 5000) { "备份对象数量超出限制" }
        fun validIds(ids: List<String>) = ids.all { it.isNotBlank() && it.length <= 128 } && ids.distinct().size == ids.size
        require(validIds(bundle.characters.map { it.id }) && validIds(bundle.connections.map { it.id }) &&
            validIds(bundle.sessions.map { it.id }) && validIds(bundle.messages.map { it.id }) && validIds(bundle.resources.map { it.id })) { "记录 ID 缺失或重复" }
        bundle.resources.forEach { it.validate() }
        val resources = bundle.resources.associateBy { it.id }
        fun config(value: String) {
            val c = readConfig(value)
            require(c.lorebookIds.all { resources[it]?.kind=="lorebook" } && c.regexIds.all { resources[it]?.kind=="regex" } &&
                listOfNotNull(c.presetId,c.characterPromptId).all { resources[it]?.kind=="preset" }) { "角色或会话引用的资料缺失或类型错误" }
        }
        config(bundle.defaultsConfig)
        bundle.characters.forEach { config(it.config) }
        bundle.sessions.forEach { config(it.config) }
        val characterIds = bundle.characters.map { it.id }.toSet()
        val connectionIds = bundle.connections.map { it.id }.toSet()
        val sessionIds = bundle.sessions.map { it.id }.toSet()
        fun card(value: CharacterCard) {
            require(value.name.isNotBlank() && value.name.length<=200 && value.avatar.length<=4*1024*1024) {"角色名称或头像无效"}
            val greetings=try {appJson.decodeFromString<List<String>>(value.alternateGreetings)}
                catch(_: Exception) {throw IllegalArgumentException("角色备选开场白格式无效")}
            require(greetings.size<=100 && greetings.all {it.length<=32000}) {"角色备选开场白超出限制"}
        }
        bundle.characters.forEach(::card)
        require(bundle.connections.all { it.name.isNotBlank() && it.maxOutputTokens in 1..65536 }) { "连接配置无效" }
        require(bundle.sessions.all {
            it.characterId in characterIds && (it.connectionId == null || it.connectionId in connectionIds) &&
                it.title.isNotBlank() && it.createdAt >= 0 && it.updatedAt >= 0
        }) { "会话引用的角色或连接缺失" }
        bundle.sessions.forEach {
            val snapshot = runCatching { appJson.decodeFromString<CharacterCard>(it.characterSnapshot) }.getOrNull()
            require(snapshot != null && snapshot.id == it.characterId && snapshot.name.isNotBlank()) { "角色快照无效" }
            config(snapshot!!.config)
            card(snapshot)
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
        val resources = bundle.resources.associate { it.id to newId() }
        return bundle.copy(
            resources = bundle.resources.map { it.copy(id=resources.getValue(it.id)) },
            defaultsConfig = readConfig(bundle.defaultsConfig).remap(resources).json(),
            characters = bundle.characters.map { it.copy(id = characters.getValue(it.id),config=readConfig(it.config).remap(resources).json()) },
            connections = bundle.connections.map { it.copy(id = connections.getValue(it.id), name = it.name + "（导入）") },
            sessions = bundle.sessions.map {
                val snapshot = appJson.decodeFromString<CharacterCard>(it.characterSnapshot)
                    .let { card -> card.copy(id = characters.getValue(it.characterId),config=readConfig(card.config).remap(resources).json()) }
                it.copy(id = sessions.getValue(it.id), characterId = characters.getValue(it.characterId),
                    characterSnapshot = appJson.encodeToString(snapshot),
                    config = readConfig(it.config).remap(resources).json(),
                    connectionId = it.connectionId?.let { id -> connections.getValue(id) })
            },
            messages = bundle.messages.map { it.copy(id = newId(), sessionId = sessions.getValue(it.sessionId),
                status = if (it.status == "streaming") "interrupted" else it.status) }
        )
    }
}

package com.moyer.roleplay.data

import androidx.room.withTransaction
import com.moyer.roleplay.core.*
import kotlinx.serialization.encodeToString

class ChatRepository(val database: AppDatabase) {
    val dao = database.dao()

    suspend fun initialize() = database.withTransaction {
        dao.recoverInterrupted()
        if (dao.characterCount() == 0) dao.saveCharacter(CharacterCard(
            name = "林舟", description = "你是林舟，一位旧城档案馆的管理员。你观察细致，熟悉旧城的历史。",
            personality = "沉着、耐心，偶尔用幽默化解紧张。", scenario = "玩家在雨夜来到旧城档案馆，寻找一封失落的信。",
            greeting = "雨水沿着门檐落下。林舟合上手中的册子，抬头望向你。\n\n‘这么晚来档案馆，是在找什么？’"
        ))
    }

    suspend fun createSession(card: CharacterCard, connectionId: String?): String = database.withTransaction {
        val session = ChatSession(title = card.name, characterId = card.id,
            characterSnapshot = appJson.encodeToString(card), connectionId = connectionId)
        dao.insertSession(session)
        if (card.greeting.isNotBlank()) dao.insertMessage(ChatMessage(sessionId = session.id,
            role = "assistant", content = card.greeting, position = 0))
        session.id
    }

    suspend fun startTurn(sessionId: String, userText: String?): ChatMessage = database.withTransaction {
        val history = dao.history(sessionId)
        var position = (history.maxOfOrNull { it.position } ?: -1) + 1
        if (userText != null) dao.insertMessage(ChatMessage(sessionId = sessionId, role = "user", content = userText, position = position++))
        val reply = ChatMessage(sessionId = sessionId, role = "assistant", content = "", position = position, status = "streaming")
        dao.insertMessage(reply)
        if (userText != null) dao.saveDraft(sessionId, "")
        dao.touch(sessionId, System.currentTimeMillis())
        reply
    }

    suspend fun export(sessionId: String? = null): BackupBundle = database.withTransaction {
        val sessions = if (sessionId == null) dao.allSessions() else listOfNotNull(dao.session(sessionId))
        require(sessionId == null || sessions.isNotEmpty()) { "会话不存在" }
        val characterIds = sessions.map { it.characterId }.toSet()
        val connectionIds = sessions.mapNotNull { it.connectionId }.toSet()
        val selectedIds = sessions.map { it.id }.toSet()
        BackupBundle(format = if (sessionId == null) "roleplay.backup" else "roleplay.chat",
            characters = dao.allCharacters().filter { sessionId == null || it.id in characterIds },
            connections = dao.allConnections().filter { sessionId == null || it.id in connectionIds },
            sessions = sessions, messages = dao.allMessages().filter { it.sessionId in selectedIds })
    }

    suspend fun restore(bundle: BackupBundle) {
        val mapped = BackupCodec.remap(bundle)
        database.withTransaction {
            mapped.characters.forEach { dao.saveCharacter(it) }
            mapped.connections.forEach { dao.saveConnection(it) }
            mapped.sessions.forEach { dao.insertSession(it) }
            mapped.messages.forEach { dao.insertMessage(it) }
        }
    }

    suspend fun readableExport(id: String): String {
        val session = requireNotNull(dao.session(id)) { "会话不存在" }
        val character = appJson.decodeFromString<CharacterCard>(session.characterSnapshot)
        return buildString {
            appendLine(session.title); appendLine()
            dao.history(id).forEach {
                appendLine(if (it.role == "user") session.playerName else character.name)
                appendLine(it.content)
                if (it.status != "complete") appendLine("[${it.status}]")
                appendLine()
            }
        }
    }
}

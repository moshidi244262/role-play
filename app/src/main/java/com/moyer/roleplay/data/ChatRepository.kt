package com.moyer.roleplay.data

import androidx.room.withTransaction
import com.moyer.roleplay.core.*
import kotlinx.serialization.encodeToString

class ChatRepository(val database: AppDatabase) {
    val dao = database.dao()

    suspend fun initialize() = database.withTransaction {
        dao.recoverInterrupted()
        if (dao.setting("resources_seeded") == null) {
            dao.saveResource(ResourceAsset(id="sample-lore",kind="lorebook",name="旧城档案 · 示例世界书",payload=appJson.encodeToString(
                LoreBook(listOf(LoreEntry(name="档案馆",keys=listOf("档案馆"),content="旧城档案馆开放到深夜，禁止明火。书架编号使用字母与数字。"),
                    LoreEntry(name="失落的信",keys=listOf("信"),content="失落的信由一位姓沈的旅人留下，线索藏在 B-17 号档案盒。"))))))
            dao.saveResource(ResourceAsset(id="sample-preset",kind="preset",name="自然叙事 · 示例预设",payload=appJson.encodeToString(
                StylePreset(listOf(PromptBlock(name="写法",content="用简洁的中文叙述动作与环境，通过对话推动剧情。少重复上一轮信息，每次为玩家留下行动空间。"))))))
            dao.saveResource(ResourceAsset(id="sample-regex",kind="regex",name="整理空行 · 示例正则",payload=appJson.encodeToString(
                RegexRules(listOf(TextRule(name="合并多余空行",pattern="/\\n{3,}/g",replacement="\n\n",prompt=false))))))
            dao.saveSetting(StoredSetting("resources_seeded","true"))
        }
        if (dao.characterCount() == 0) dao.saveCharacter(CharacterCard(
            name = "林舟", description = "你是林舟，一位旧城档案馆的管理员。你观察细致，熟悉旧城的历史。",
            personality = "沉着、耐心，偶尔用幽默化解紧张。", scenario = "玩家在雨夜来到旧城档案馆，寻找一封失落的信。",
            greeting = "雨水沿着门檐落下。林舟合上手中的册子，抬头望向你。\n\n‘这么晚来档案馆，是在找什么？’",
            config = RoleplayConfig(lorebookIds=listOf("sample-lore"),presetId="sample-preset").json()
        ))
    }

    suspend fun createSession(card: CharacterCard, connectionId: String?, greeting: String? = null): String = database.withTransaction {
        val config = configDefaults(readConfig(dao.setting("defaults")?.value ?: "{}"), readConfig(card.config))
        val session = ChatSession(title = card.name, characterId = card.id,
            characterSnapshot = appJson.encodeToString(card), connectionId = connectionId, config=config.json(),
            playerName=config.playerName.ifBlank { "我" })
        dao.insertSession(session)
        val first=greeting ?: card.greeting
        if (first.isNotBlank()) dao.insertMessage(ChatMessage(sessionId = session.id,
            role = "assistant", content = first.replace("{{char}}",card.name).replace("{{user}}",session.playerName), position = 0))
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

    suspend fun forkSession(sessionId: String,messageId: String,editedText: String?=null,before: Boolean=false): String = database.withTransaction {
        val source=requireNotNull(dao.session(sessionId)) {"会话不存在"}
        val history=dao.history(sessionId)
        val index=history.indexOfFirst {it.id==messageId}
        require(index>=0) {"消息不存在"}
        require(!before || (index>0 && history[index-1].role=="user")) {"这条回复没有可重新回答的玩家消息"}
        val fork=source.copy(id=newId(),title=source.title.substringBefore(" · 分支")+" · 分支",draft="",createdAt=System.currentTimeMillis(),updatedAt=System.currentTimeMillis())
        dao.insertSession(fork)
        history.take(if(before) index else index+1).forEach {message->
            val edited=message.id==messageId && editedText!=null
            dao.insertMessage(message.copy(id=newId(),sessionId=fork.id,
                content=if(edited) editedText!! else message.content,
                status=if(edited) "complete" else if(message.status=="streaming") "interrupted" else message.status,
                inputTokens=if(edited) null else message.inputTokens,outputTokens=if(edited) null else message.outputTokens,cachedTokens=if(edited) null else message.cachedTokens))
        }
        fork.id
    }

    suspend fun exportCharacter(card: CharacterCard): BackupBundle = database.withTransaction {
        BackupBundle(characters=listOf(card),sessions=emptyList(),messages=emptyList(),resources=dao.allResources().filter {it.id in readConfig(card.config).references()})
    }

    suspend fun export(sessionId: String? = null): BackupBundle = database.withTransaction {
        val sessions = if (sessionId == null) dao.allSessions() else listOfNotNull(dao.session(sessionId))
        require(sessionId == null || sessions.isNotEmpty()) { "会话不存在" }
        val characterIds = sessions.map { it.characterId }.toSet()
        val connectionIds = sessions.mapNotNull { it.connectionId }.toSet()
        val selectedIds = sessions.map { it.id }.toSet()
        val cards = dao.allCharacters().filter { sessionId == null || it.id in characterIds }
        val needed = (cards.flatMap { readConfig(it.config).references() } + sessions.flatMap { readConfig(it.config).references() } +
            sessions.flatMap { readConfig(appJson.decodeFromString<CharacterCard>(it.characterSnapshot).config).references() }).toSet()
        BackupBundle(format = if (sessionId == null) "roleplay.backup" else "roleplay.chat",
            characters = cards,
            connections = dao.allConnections().filter { sessionId == null || it.id in connectionIds },
            sessions = sessions, messages = dao.allMessages().filter { it.sessionId in selectedIds },
            resources = dao.allResources().filter { sessionId == null || it.id in needed },
            defaultsConfig = if(sessionId==null) dao.setting("defaults")?.value ?: "{}" else "{}")
    }

    suspend fun restore(bundle: BackupBundle) {
        val mapped = BackupCodec.remap(bundle)
        database.withTransaction {
            mapped.resources.forEach { dao.saveResource(it) }
            mapped.characters.forEach { dao.saveCharacter(it) }
            mapped.connections.forEach { dao.saveConnection(it) }
            mapped.sessions.forEach { dao.insertSession(it) }
            mapped.messages.forEach { dao.insertMessage(it) }
            if(readConfig(dao.setting("defaults")?.value ?: "{}")==RoleplayConfig() && bundle.format=="roleplay.backup")
                dao.saveSetting(StoredSetting("defaults",mapped.defaultsConfig))
        }
    }

    suspend fun importCommunity(pack: ImportPreview) = database.withTransaction {
        pack.resources.forEach { it.validate(); dao.saveResource(it) }
        pack.characters.forEach { readConfig(it.config); dao.saveCharacter(it) }
    }

    suspend fun deleteAsset(id: String) = database.withTransaction {
        dao.allCharacters().forEach { card ->
            val config=readConfig(card.config)
            if(id in config.references()) dao.saveCharacter(card.copy(config=config.without(id).json()))
        }
        dao.allSessions().forEach { session ->
            val config=readConfig(session.config)
            if(id in config.references()) dao.saveConfig(session.id,config.without(id).json(),session.playerName)
            val card=appJson.decodeFromString<CharacterCard>(session.characterSnapshot)
            val snapshotConfig=readConfig(card.config)
            if(id in snapshotConfig.references()) dao.saveSnapshot(session.id,appJson.encodeToString(card.copy(config=snapshotConfig.without(id).json())))
        }
        val defaults=readConfig(dao.setting("defaults")?.value ?: "{}")
        dao.saveSetting(StoredSetting("defaults",defaults.without(id).json()))
        dao.deleteResource(id)
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

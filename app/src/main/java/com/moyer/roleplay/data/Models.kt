package com.moyer.roleplay.data

import androidx.room.*
import kotlinx.serialization.Serializable
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

@Serializable
@Entity(tableName = "characters")
data class CharacterCard(
    @PrimaryKey val id: String = newId(),
    val name: String,
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val greeting: String = "",
    val exampleDialogue: String = "",
    @ColumnInfo(defaultValue = "'{}'") val config: String = "{}",
    @ColumnInfo(defaultValue = "''") val sourceJson: String = "",
    @ColumnInfo(defaultValue = "''") val avatar: String = "",
    @ColumnInfo(defaultValue = "'[]'") val alternateGreetings: String = "[]",
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
enum class Protocol { CHAT_COMPLETIONS, RESPONSES, ANTHROPIC_MESSAGES }

@Serializable
@Entity(tableName = "connections")
data class ConnectionProfile(
    @PrimaryKey val id: String = newId(),
    val name: String,
    val provider: String = "自定义",
    val baseUrl: String,
    val protocol: Protocol,
    val model: String,
    val maxOutputTokens: Int = 2048,
    val versionHeader: String = "2023-06-01"
)

@Serializable
@Entity(tableName = "sessions", indices = [Index("updatedAt")])
data class ChatSession(
    @PrimaryKey val id: String = newId(),
    val title: String,
    val characterId: String,
    // A snapshot lets an existing conversation survive later character edits.
    val characterSnapshot: String,
    val connectionId: String? = null,
    val playerName: String = "玩家",
    val draft: String = "",
    @ColumnInfo(defaultValue = "'{}'") val config: String = "{}",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
@Entity(tableName = "messages",
    foreignKeys = [ForeignKey(entity = ChatSession::class, parentColumns = ["id"],
        childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId"), Index(value = ["sessionId", "position"], unique = true)])
data class ChatMessage(
    @PrimaryKey val id: String = newId(),
    val sessionId: String,
    val role: String,
    val content: String,
    val position: Long,
    val status: String = "complete",
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val cachedTokens: Int? = null,
    val createdAt: Long = System.currentTimeMillis()
)

data class PromptMessage(val role: String, val content: String)

object ProviderTemplates {
    val all = listOf(
        ConnectionProfile(id = "template-deepseek", name = "DeepSeek", provider = "DeepSeek",
            baseUrl = "https://api.deepseek.com", protocol = Protocol.CHAT_COMPLETIONS, model = "deepseek-flash"),
        ConnectionProfile(id = "template-openai", name = "OpenAI", provider = "OpenAI",
            baseUrl = "https://api.openai.com/v1", protocol = Protocol.RESPONSES, model = ""),
        ConnectionProfile(id = "template-claude", name = "Claude", provider = "Claude",
            baseUrl = "https://api.anthropic.com", protocol = Protocol.ANTHROPIC_MESSAGES, model = ""),
        ConnectionProfile(id = "template-grok", name = "Grok", provider = "Grok",
            baseUrl = "https://api.x.ai/v1", protocol = Protocol.RESPONSES, model = ""),
        ConnectionProfile(id = "template-custom", name = "自定义", baseUrl = "",
            protocol = Protocol.CHAT_COMPLETIONS, model = "")
    )
}

package com.moyer.roleplay.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface RolePlayDao {
    @Query("SELECT * FROM characters ORDER BY updatedAt DESC") fun characters(): Flow<List<CharacterCard>>
    @Query("SELECT * FROM connections ORDER BY name") fun connections(): Flow<List<ConnectionProfile>>
    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC") fun sessions(): Flow<List<ChatSession>>
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY position")
    fun messages(sessionId: String): Flow<List<ChatMessage>>
    @Query("SELECT * FROM characters") suspend fun allCharacters(): List<CharacterCard>
    @Query("SELECT * FROM connections") suspend fun allConnections(): List<ConnectionProfile>
    @Query("SELECT * FROM sessions") suspend fun allSessions(): List<ChatSession>
    @Query("SELECT * FROM messages ORDER BY sessionId, position") suspend fun allMessages(): List<ChatMessage>
    @Query("SELECT * FROM messages WHERE sessionId = :id ORDER BY position") suspend fun history(id: String): List<ChatMessage>
    @Query("SELECT * FROM sessions WHERE id = :id") suspend fun session(id: String): ChatSession?
    @Query("SELECT * FROM connections WHERE id = :id") suspend fun connection(id: String): ConnectionProfile?
    @Query("SELECT COUNT(*) FROM characters") suspend fun characterCount(): Int
    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :id") suspend fun messageCount(id: String): Int
    @Upsert suspend fun saveCharacter(card: CharacterCard)
    @Upsert suspend fun saveConnection(profile: ConnectionProfile)
    @Insert suspend fun insertSession(session: ChatSession)
    @Insert suspend fun insertMessage(message: ChatMessage)
    @Query("UPDATE sessions SET draft = :draft WHERE id = :id") suspend fun saveDraft(id: String, draft: String)
    @Query("UPDATE sessions SET connectionId = :connectionId WHERE id = :id") suspend fun bindConnection(id: String, connectionId: String?)
    @Query("UPDATE sessions SET updatedAt = :time WHERE id = :id") suspend fun touch(id: String, time: Long)
    @Query("UPDATE messages SET content = :text, status = :status WHERE id = :id")
    suspend fun updateMessage(id: String, text: String, status: String)
    @Query("UPDATE messages SET status = 'interrupted' WHERE status = 'streaming'") suspend fun recoverInterrupted()
    @Query("DELETE FROM sessions WHERE id = :id") suspend fun deleteSession(id: String)
}

@Database(entities = [CharacterCard::class, ConnectionProfile::class, ChatSession::class, ChatMessage::class],
    version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() { abstract fun dao(): RolePlayDao }

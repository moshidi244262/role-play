package com.moyer.roleplay.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface RolePlayDao {
    @Query("SELECT * FROM characters ORDER BY updatedAt DESC") fun characters(): Flow<List<CharacterCard>>
    @Query("SELECT * FROM connections ORDER BY name") fun connections(): Flow<List<ConnectionProfile>>
    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC") fun sessions(): Flow<List<ChatSession>>
    @Query("SELECT * FROM resources ORDER BY updatedAt DESC") fun resources(): Flow<List<ResourceAsset>>
    @Query("SELECT * FROM resources") suspend fun allResources(): List<ResourceAsset>
    @Query("SELECT * FROM settings WHERE id = :id") suspend fun setting(id: String): StoredSetting?
    @Query("SELECT value FROM settings WHERE id = 'defaults'") fun defaults(): Flow<String?>
    @Upsert suspend fun saveSetting(setting: StoredSetting)
    @Upsert suspend fun saveResource(resource: ResourceAsset)
    @Query("DELETE FROM resources WHERE id = :id") suspend fun deleteResource(id: String)
    @Query("UPDATE sessions SET config = :config, playerName = :playerName WHERE id = :id")
    suspend fun saveConfig(id: String, config: String, playerName: String)
    @Query("UPDATE sessions SET characterSnapshot = :snapshot WHERE id = :id") suspend fun saveSnapshot(id: String, snapshot: String)
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
    @Query("UPDATE messages SET inputTokens = :input, outputTokens = :output, cachedTokens = :cached WHERE id = :id")
    suspend fun updateUsage(id: String, input: Int?, output: Int?, cached: Int?)
    @Query("UPDATE messages SET status = 'interrupted' WHERE status = 'streaming'") suspend fun recoverInterrupted()
    @Query("DELETE FROM sessions WHERE id = :id") suspend fun deleteSession(id: String)
}

@Database(entities = [CharacterCard::class, ConnectionProfile::class, ChatSession::class, ChatMessage::class, ResourceAsset::class, StoredSetting::class],
    version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): RolePlayDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE characters ADD COLUMN config TEXT NOT NULL DEFAULT '{}'")
                db.execSQL("ALTER TABLE characters ADD COLUMN sourceJson TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE characters ADD COLUMN avatar TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE characters ADD COLUMN alternateGreetings TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE sessions ADD COLUMN config TEXT NOT NULL DEFAULT '{}'")
                db.execSQL("ALTER TABLE messages ADD COLUMN inputTokens INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN outputTokens INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN cachedTokens INTEGER")
                db.execSQL("CREATE TABLE IF NOT EXISTS resources (id TEXT NOT NULL, kind TEXT NOT NULL, name TEXT NOT NULL, payload TEXT NOT NULL, sourceJson TEXT NOT NULL, notes TEXT NOT NULL, enabled INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE TABLE IF NOT EXISTS settings (id TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(id))")
            }
        }
    }
}

package com.moyer.roleplay.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Dao
abstract class RolePlayDao {
    @Query("SELECT * FROM characters ORDER BY updatedAt DESC") abstract fun characters(): Flow<List<CharacterCard>>
    @Query("SELECT * FROM connections ORDER BY name") abstract fun connections(): Flow<List<ConnectionProfile>>
    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC") abstract fun sessions(): Flow<List<ChatSession>>
    @Query("SELECT id FROM resources ORDER BY updatedAt DESC") protected abstract fun resourceChanges(): Flow<List<String>>
    @Query("SELECT * FROM resources ORDER BY updatedAt DESC") protected abstract suspend fun resourceRows(): List<ResourceAsset>
    @Query("SELECT * FROM resource_text_parts ORDER BY resourceId, field, part") protected abstract suspend fun resourceParts(): List<ResourceTextPart>
    @Query("DELETE FROM resource_text_parts WHERE resourceId = :id") protected abstract suspend fun deleteResourceParts(id: String)
    @Insert protected abstract suspend fun insertResourceParts(parts: List<ResourceTextPart>)
    fun resources(): Flow<List<ResourceAsset>> = resourceChanges().map { allResources() }
    @Transaction open suspend fun allResources(): List<ResourceAsset> {
        val rows=resourceRows()
        val parts=resourceParts().groupBy { it.resourceId }
        return rows.map { ResourceTextStorage.join(it,parts[it.id].orEmpty()) }
    }
    @Query("SELECT * FROM settings WHERE id = :id") abstract suspend fun setting(id: String): StoredSetting?
    @Query("SELECT value FROM settings WHERE id = 'defaults'") abstract fun defaults(): Flow<String?>
    @Upsert abstract suspend fun saveSetting(setting: StoredSetting)
    @Upsert protected abstract suspend fun saveResourceRow(resource: ResourceAsset)
    @Transaction open suspend fun saveResource(resource: ResourceAsset) {
        val (row,parts)=ResourceTextStorage.split(resource)
        saveResourceRow(row)
        deleteResourceParts(resource.id)
        if(parts.isNotEmpty()) insertResourceParts(parts)
    }
    @Query("DELETE FROM resources WHERE id = :id") abstract suspend fun deleteResource(id: String)
    @Query("UPDATE sessions SET config = :config, playerName = :playerName WHERE id = :id")
    abstract suspend fun saveConfig(id: String, config: String, playerName: String)
    @Query("UPDATE sessions SET characterSnapshot = :snapshot WHERE id = :id") abstract suspend fun saveSnapshot(id: String, snapshot: String)
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY position")
    abstract fun messages(sessionId: String): Flow<List<ChatMessage>>
    @Query("SELECT * FROM characters") abstract suspend fun allCharacters(): List<CharacterCard>
    @Query("SELECT * FROM connections") abstract suspend fun allConnections(): List<ConnectionProfile>
    @Query("SELECT * FROM sessions") abstract suspend fun allSessions(): List<ChatSession>
    @Query("SELECT * FROM messages ORDER BY sessionId, position") abstract suspend fun allMessages(): List<ChatMessage>
    @Query("SELECT * FROM messages WHERE sessionId = :id ORDER BY position") abstract suspend fun history(id: String): List<ChatMessage>
    @Query("SELECT * FROM sessions WHERE id = :id") abstract suspend fun session(id: String): ChatSession?
    @Query("SELECT * FROM connections WHERE id = :id") abstract suspend fun connection(id: String): ConnectionProfile?
    @Query("SELECT COUNT(*) FROM characters") abstract suspend fun characterCount(): Int
    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :id") abstract suspend fun messageCount(id: String): Int
    @Query("SELECT MAX(position) FROM messages WHERE sessionId = :id") abstract suspend fun lastPosition(id: String): Long?
    @Upsert abstract suspend fun saveCharacter(card: CharacterCard)
    @Upsert abstract suspend fun saveConnection(profile: ConnectionProfile)
    @Insert abstract suspend fun insertSession(session: ChatSession)
    @Insert abstract suspend fun insertMessage(message: ChatMessage)
    @Query("UPDATE sessions SET draft = :draft WHERE id = :id") abstract suspend fun saveDraft(id: String, draft: String)
    @Query("UPDATE sessions SET connectionId = :connectionId WHERE id = :id") abstract suspend fun bindConnection(id: String, connectionId: String?)
    @Query("UPDATE sessions SET updatedAt = :time WHERE id = :id") abstract suspend fun touch(id: String, time: Long)
    @Query("UPDATE messages SET content = :text, status = :status WHERE id = :id")
    abstract suspend fun updateMessage(id: String, text: String, status: String)
    @Query("UPDATE messages SET inputTokens = :input, outputTokens = :output, cachedTokens = :cached WHERE id = :id")
    abstract suspend fun updateUsage(id: String, input: Int?, output: Int?, cached: Int?)
    @Query("UPDATE messages SET status = 'interrupted' WHERE status = 'streaming'") abstract suspend fun recoverInterrupted()
    @Query("DELETE FROM sessions WHERE id = :id") abstract suspend fun deleteSession(id: String)
}

@Database(entities = [CharacterCard::class, ConnectionProfile::class, ChatSession::class, ChatMessage::class, ResourceAsset::class, StoredSetting::class, ResourceTextPart::class],
    version = 4, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): RolePlayDao
    companion object {
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS resource_text_parts (resourceId TEXT NOT NULL, field TEXT NOT NULL, part INTEGER NOT NULL, text TEXT NOT NULL, PRIMARY KEY(resourceId, field, part), FOREIGN KEY(resourceId) REFERENCES resources(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_resource_text_parts_resourceId ON resource_text_parts(resourceId)")
                // Query only ids and lengths. A SELECT * here would reproduce the oversized-row crash
                // before the old data could be repaired. SQLite copies substrings internally.
                val lengths=mutableListOf<Pair<String,List<Int>>>()
                db.query("SELECT id, length(payload), length(sourceJson), length(notes) FROM resources").use { cursor ->
                    while(cursor.moveToNext()) lengths += cursor.getString(0) to (1..3).map { cursor.getInt(it) }
                }
                val fields=listOf("payload","sourceJson","notes")
                for((id,sizes) in lengths) for((index,field) in fields.withIndex()) {
                    val size=sizes[index]
                    if(size <= ResourceTextStorage.CHARS_PER_PART) continue
                    var offset=0
                    var part=0
                    while(offset < size) {
                        db.execSQL("INSERT INTO resource_text_parts (resourceId, field, part, text) SELECT id, ?, ?, substr($field, ?, ?) FROM resources WHERE id = ?",
                            arrayOf(field,part++,offset+1,ResourceTextStorage.CHARS_PER_PART,id))
                        offset += ResourceTextStorage.CHARS_PER_PART
                    }
                    db.execSQL("UPDATE resources SET $field = '' WHERE id = ?",arrayOf(id))
                }
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE characters ADD COLUMN customAvatar TEXT NOT NULL DEFAULT ''")
            }
        }
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

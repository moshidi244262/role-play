package com.moyer.roleplay

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moyer.roleplay.core.*
import com.moyer.roleplay.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @Test fun versionTwoUpgradePreservesCardImageSessionAndUsage() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-${newId()}.db"
        val schema = InstrumentationRegistry.getInstrumentation().context.assets.open("com.moyer.roleplay.data.AppDatabase/2.json")
            .bufferedReader().use { appJson.parseToJsonElement(it.readText()).jsonObject.getValue("database").jsonObject }
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            schema.getValue("entities").jsonArray.forEach { entity ->
                val value = entity.jsonObject; val table = value.getValue("tableName").jsonPrimitive.content
                db.execSQL(value.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                value.getValue("indices").jsonArray.forEach { index -> db.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
            schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            val snapshot = """{"id":"v2-card","name":"原角色","avatar":"original-image"}"""
            db.execSQL("INSERT INTO characters (id,name,description,personality,scenario,greeting,exampleDialogue,updatedAt,avatar) VALUES (?,?,?,?,?,?,?,?,?)",
                arrayOf("v2-card", "原角色", "", "", "", "原开场", "", 1, "original-image"))
            db.execSQL("INSERT INTO sessions (id,title,characterId,characterSnapshot,playerName,draft,createdAt,updatedAt) VALUES (?,?,?,?,?,?,?,?)",
                arrayOf("v2-chat", "旧会话", "v2-card", snapshot, "我", "原草稿", 1, 2))
            db.execSQL("INSERT INTO messages (id,sessionId,role,content,position,status,createdAt,inputTokens,outputTokens) VALUES (?,?,?,?,?,?,?,?,?)",
                arrayOf("v2-message", "v2-chat", "assistant", "原回复", 0, "complete", 1, 23, 45))
            db.version = 2
        }
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4).build()
        try {
            val card = database.dao().allCharacters().single()
            assertEquals("original-image", card.avatar); assertEquals("", card.customAvatar)
            val session = database.dao().session("v2-chat")!!
            assertEquals("原草稿", session.draft); assertEquals("original-image", sessionAvatar(session, card))
            val reply = database.dao().history(session.id).single()
            assertEquals("原回复", reply.content); assertEquals(23, reply.inputTokens); assertEquals(45, reply.outputTokens)
        } finally { database.close(); context.deleteDatabase(name) }
    }

    @Test fun actualVersionOneSchemaMigratesWithoutLosingConversationOrDraft()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="migration-${newId()}.db"
        val schema=InstrumentationRegistry.getInstrumentation().context.assets.open("com.moyer.roleplay.data.AppDatabase/1.json")
            .bufferedReader().use {appJson.parseToJsonElement(it.readText()).jsonObject.getValue("database").jsonObject}
        context.openOrCreateDatabase(name,Context.MODE_PRIVATE,null).use {db->
            schema.getValue("entities").jsonArray.forEach { entity->
                val value=entity.jsonObject; val table=value.getValue("tableName").jsonPrimitive.content
                db.execSQL(value.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}",table))
                value.getValue("indices").jsonArray.forEach {index->db.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}",table))}
            }
            schema.getValue("setupQueries").jsonArray.forEach {db.execSQL(it.jsonPrimitive.content)}
            val snapshot="""{"id":"old-card","name":"旧角色","description":"旧背景","personality":"","scenario":"","greeting":"旧开场","exampleDialogue":"","updatedAt":1}"""
            db.execSQL("INSERT INTO characters VALUES (?,?,?,?,?,?,?,?)",arrayOf("old-card","旧角色","旧背景","","","旧开场","",1))
            db.execSQL("INSERT INTO sessions VALUES (?,?,?,?,?,?,?,?,?)",arrayOf("old-chat","旧故事","old-card",snapshot,null,"旧玩家","升级前的草稿",1,2))
            db.execSQL("INSERT INTO messages VALUES (?,?,?,?,?,?,?)",arrayOf("old-message","old-chat","assistant","升级前的回复",0,"complete",1))
            db.version=1
        }
        val database=Room.databaseBuilder(context,AppDatabase::class.java,name).addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4).build()
        try {
            val repository=ChatRepository(database);repository.initialize()
            val session=database.dao().session("old-chat")!!
            assertEquals("升级前的草稿",session.draft);assertEquals("旧玩家",session.playerName)
            assertEquals("{}",session.config);assertEquals("升级前的回复",database.dao().history(session.id).single().content)
            assertEquals("旧角色",appJson.decodeFromString<CharacterCard>(session.characterSnapshot).name)
            assertEquals(1,database.dao().allCharacters().size)
            assertEquals(3,database.dao().allResources().size)
            val roundtrip=BackupCodec.decode(BackupCodec.encode(repository.export()))
            assertEquals("升级前的草稿",roundtrip.sessions.single().draft)
        } finally {database.close();context.deleteDatabase(name)}
    }
}

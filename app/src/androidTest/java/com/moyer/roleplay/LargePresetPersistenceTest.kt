package com.moyer.roleplay

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moyer.roleplay.core.*
import com.moyer.roleplay.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LargePresetPersistenceTest {
    // Synthetic counterpart of the user's 220-block / 40-rule preset with a 6 MiB helper field.
    // No private community file is uploaded to the source repository.
    private fun fixture(): ResourceAsset {
        val markers=listOf("charDescription","charPersonality","scenario","personaDescription","worldInfoBefore","worldInfoAfter","dialogueExamples","chatHistory")
        val prompts=JsonArray((0..211).map {buildJsonObject {
            put("identifier","p$it");put("name","块 $it");put("content",if(it==0) "{{setvar::style::简洁}}" else "{{getvar::style}} 写法 $it")
        }}+markers.map {buildJsonObject {put("identifier",it);put("marker",true)}})
        val json=buildJsonObject {
            put("prompts",prompts)
            put("prompt_order",buildJsonArray {add(buildJsonObject {put("character_id",100001);put("order",buildJsonArray {
                for(i in 0..211) add(buildJsonObject {put("identifier","p$i");put("enabled",i<54)})
                markers.forEach {add(buildJsonObject {put("identifier",it);put("enabled",true)})}
            })})})
            put("extensions",buildJsonObject {
                put("tavern_helper",buildJsonObject {put("code","not executable ".repeat(450000))})
                put("regex_scripts",buildJsonArray {repeat(40) {i->add(buildJsonObject {
                    put("findRegex","/unused/g");put("replaceString","");put("disabled",i>=17)
                })}})
            })
        }
        return CommunityImporter.decode(json.toString().toByteArray(),"large-preset.json","preset").resources.single()
    }

    @Test fun migrateOversizedVersionThreeRowWithoutReadingItOrLosingStory() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="oversized-v3-${newId()}.db"
        val asset=fixture()
        val schema=InstrumentationRegistry.getInstrumentation().context.assets.open("com.moyer.roleplay.data.AppDatabase/3.json")
            .bufferedReader().use {appJson.parseToJsonElement(it.readText()).jsonObject.getValue("database").jsonObject}
        context.openOrCreateDatabase(name,Context.MODE_PRIVATE,null).use {db->
            schema.getValue("entities").jsonArray.forEach {entity->
                val e=entity.jsonObject;val table=e.getValue("tableName").jsonPrimitive.content
                db.execSQL(e.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}",table))
                e.getValue("indices").jsonArray.forEach {db.execSQL(it.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}",table))}
            }
            schema.getValue("setupQueries").jsonArray.forEach {db.execSQL(it.jsonPrimitive.content)}
            db.execSQL("INSERT INTO resources VALUES(?,?,?,?,?,?,?,?)",arrayOf(asset.id,asset.kind,asset.name,asset.payload,asset.sourceJson,asset.notes,1,asset.updatedAt))
            val card=CharacterCard(id="old-card",name="旧人物",config=RoleplayConfig(presetId=asset.id).json())
            db.execSQL("INSERT INTO characters (id,name,description,personality,scenario,greeting,exampleDialogue,config,sourceJson,avatar,customAvatar,alternateGreetings,updatedAt) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf(card.id,card.name,"","","","","",card.config,"","","","[]",1))
            db.execSQL("INSERT INTO sessions (id,title,characterId,characterSnapshot,connectionId,playerName,draft,config,createdAt,updatedAt) VALUES(?,?,?,?,?,?,?,?,?,?)",
                arrayOf("old-chat","旧剧情",card.id,appJson.encodeToString(card),null,"我","原草稿",card.config,1,2))
            db.execSQL("INSERT INTO messages (id,sessionId,role,content,position,status,createdAt) VALUES(?,?,?,?,?,?,?)",
                arrayOf("old-message","old-chat","assistant","原聊天内容",0,"complete",1))
            db.version=3
            val error=runCatching {db.rawQuery("SELECT * FROM resources",null).use {it.moveToFirst();it.getString(it.getColumnIndexOrThrow("sourceJson"))}}.exceptionOrNull()
            assertNotNull("Legacy full-row query must reproduce the Android cursor failure",error)
            assertTrue(error!!.message.orEmpty().contains("CursorWindow"))
        }
        fun open()=Room.databaseBuilder(context,AppDatabase::class.java,name).addMigrations(AppDatabase.MIGRATION_3_4).build()
        var database=open()
        try {
            val restored=database.dao().allResources().single()
            assertEquals(asset,restored)
            assertEquals(220,appJson.decodeFromString<StylePreset>(restored.payload).blocks.size)
            assertEquals(40,appJson.decodeFromString<StylePreset>(restored.payload).regexRules.size)
            assertEquals("原草稿",database.dao().session("old-chat")!!.draft)
            assertEquals("原聊天内容",database.dao().history("old-chat").single().content)
            database.close();database=open()
            assertEquals(asset.sourceJson,withTimeout(15000) {database.dao().resources().first()}.single().sourceJson)
            val backup=ChatRepository(database).export()
            val decoded=BackupCodec.decode(BackupCodec.encode(backup))
            assertEquals(asset.sourceJson,decoded.resources.single().sourceJson)
            assertEquals("原草稿",decoded.sessions.single().draft)
            database.dao().saveResource(restored.copy(enabled=false))
            assertFalse(database.dao().allResources().single().enabled)
            assertEquals(asset.sourceJson,database.dao().allResources().single().sourceJson)
            database.dao().deleteResource(asset.id)
            database.openHelper.readableDatabase.query("SELECT count(*) FROM resource_text_parts").use {it.moveToFirst();assertEquals(0,it.getInt(0))}
        } finally {database.close();context.deleteDatabase(name)}
    }

    @Test fun newLargeImportPayloadAndSourceSurviveReopenAndBackupRestore() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="large-import-${newId()}.db"
        fun open()=Room.databaseBuilder(context,AppDatabase::class.java,name).build()
        var database=open()
        try {
            val asset=fixture()
            ChatRepository(database).importCommunity(ImportPreview(resources=listOf(asset)))
            assertEquals(asset,withTimeout(15000) {database.dao().resources().first()}.single())
            database.close();database=open()
            assertEquals(asset,database.dao().allResources().single())
            val backup=BackupCodec.decode(BackupCodec.encode(ChatRepository(database).export()))
            val copy=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
            try {
                ChatRepository(copy).restore(backup)
                val imported=copy.dao().allResources().single()
                assertEquals(asset.sourceJson,imported.sourceJson);assertEquals(asset.payload,imported.payload)
            } finally {copy.close()}
            val largePayload=appJson.encodeToString(StylePreset(listOf(PromptBlock(content="中".repeat(800000)))))
            database.dao().saveResource(asset.copy(payload=largePayload,notes="🦊".repeat(100000)))
            assertEquals(largePayload,database.dao().allResources().single().payload)
            database.dao().saveResource(asset.copy(payload=appJson.encodeToString(StylePreset()),sourceJson="{}",notes=""))
            val small=database.dao().allResources().single()
            assertEquals("{}",small.sourceJson);assertEquals("",small.notes)
            database.openHelper.readableDatabase.query("SELECT count(*) FROM resource_text_parts").use {it.moveToFirst();assertEquals(0,it.getInt(0))}
        } finally {database.close();context.deleteDatabase(name)}
    }
}

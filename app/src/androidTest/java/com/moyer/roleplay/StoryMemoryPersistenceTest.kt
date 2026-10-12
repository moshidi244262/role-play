package com.moyer.roleplay

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moyer.roleplay.core.*
import com.moyer.roleplay.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StoryMemoryPersistenceTest {
    @Test fun forkAndRestoreKeepOnlySummaryWhoseFullSourceStillExists() = runBlocking {
        val database=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),AppDatabase::class.java).build()
        try {
            val repo=ChatRepository(database);val dao=repo.dao
            val card=CharacterCard(name="分支记忆")
            dao.saveCharacter(card)
            val id=repo.createSession(card,null)
            (0 until 40).forEach {dao.insertMessage(ChatMessage(sessionId=id,position=it.toLong(),role=if(it%2==0) "user" else "assistant",content="消息$it"))}
            val history=dao.history(id)
            val summary=StoryMemory.plan(history,null)!!.summary("林舟找到钥匙")
            dao.saveConfig(id,RoleplayConfig(storySummary=summary,compactSummaryHistory=true).json(),"我")
            val early=repo.forkSession(id,history[8].id)
            assertNull(readConfig(dao.session(early)!!.config).storySummary)
            val edit=repo.forkSession(id,history[8].id,"钥匙丢失")
            assertNull(readConfig(dao.session(edit)!!.config).storySummary)
            val late=repo.forkSession(id,history[35].id,"改变新选择")
            assertEquals(summary,readConfig(dao.session(late)!!.config).storySummary)
            assertNotNull(StoryMemory.valid(summary,dao.history(late)))
            val regenerate=repo.forkSession(id,history[27].id,before=true)
            assertNull(readConfig(dao.session(regenerate)!!.config).storySummary)
            repo.restore(repo.export(id))
            val restored=dao.allSessions().first {it.id !in setOf(id,early,edit,late,regenerate)}
            assertNotNull(StoryMemory.valid(readConfig(restored.config).storySummary,dao.history(restored.id)))
            assertEquals(summary,readConfig(dao.session(id)!!.config).storySummary)
        } finally {database.close()}
    }

    @Test fun recentStoryPreviewUsesLatestUsableTextAndDoesNotReadWholeLongRow() = runBlocking {
        val database=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),AppDatabase::class.java).build()
        try {
            val repo=ChatRepository(database);val card=CharacterCard(name="最近剧情")
            repo.dao.saveCharacter(card);val id=repo.createSession(card,null)
            repo.dao.insertMessage(ChatMessage(sessionId=id,position=0,role="assistant",content="故事".repeat(10000)))
            repo.dao.insertMessage(ChatMessage(sessionId=id,position=1,role="assistant",content="失败",status="failed"))
            val preview=repo.dao.sessionPreviews().first().single()
            assertEquals(1000,preview.content.length)
            assertEquals("故事".repeat(500),preview.content)
        } finally {database.close()}
    }
}

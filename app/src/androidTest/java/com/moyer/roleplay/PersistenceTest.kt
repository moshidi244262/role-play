package com.moyer.roleplay

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moyer.roleplay.data.*
import com.moyer.roleplay.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import android.content.Context

@RunWith(AndroidJUnit4::class)
class PersistenceTest {
    @Test fun reopenDatabaseKeepsHistoryDraftAndRecoversInterruptedReply() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "persistence-${newId()}.db"
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        var database = open()
        try {
            var repository = ChatRepository(database)
            repository.initialize()
            val card = repository.dao.allCharacters().first()
            val sessionId = repository.createSession(card, null)
            val reply = repository.startTurn(sessionId, "我来找一封信")
            repository.dao.updateMessage(reply.id, "他指向书架", "streaming")
            repository.dao.saveDraft(sessionId, "下次要说的话")
            database.close()
            database = open()
            repository = ChatRepository(database)
            repository.initialize()
            assertEquals("下次要说的话", repository.dao.session(sessionId)!!.draft)
            val history = repository.dao.history(sessionId)
            assertEquals(3, history.size)
            assertEquals("我来找一封信", history[1].content)
            assertEquals("他指向书架", history[2].content)
            assertEquals("interrupted", history[2].status)
        } finally { database.close(); context.deleteDatabase(name) }
    }

    @Test fun backupRoundTripMergesWithoutDeletingOriginalAndCascadeDeleteWorks() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repository = ChatRepository(database)
            repository.initialize()
            val original = repository.createSession(repository.dao.allCharacters().first(), null)
            val reply = repository.startTurn(original, "你好")
            repository.dao.updateMessage(reply.id, "欢迎", "complete")
            repository.dao.saveDraft(original, "草稿")
            val backup = BackupCodec.decode(BackupCodec.encode(repository.export()))
            repository.restore(backup)
            assertEquals(2, repository.dao.allSessions().size)
            val imported = repository.dao.allSessions().first { it.id != original }
            assertEquals("草稿", imported.draft)
            assertEquals(repository.dao.history(original).map { it.content }, repository.dao.history(imported.id).map { it.content })
            repository.dao.deleteSession(imported.id)
            assertTrue(repository.dao.history(imported.id).isEmpty())
            assertEquals(3, repository.dao.history(original).size)
        } finally { database.close() }
    }
}

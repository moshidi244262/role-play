package com.moyer.roleplay

import com.moyer.roleplay.core.*
import com.moyer.roleplay.data.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class BackupCodecTest {
    private val card = CharacterCard(name = "林舟", description = "档案管理员", greeting = "你好")
    private val connection = ProviderTemplates.all[0].copy(id = newId(), model = "model-for-test")
    private val session = ChatSession(title = "雨夜", characterId = card.id, characterSnapshot = appJson.encodeToString(card),
        connectionId = connection.id, draft = "尚未发出的草稿")
    private val message = ChatMessage(sessionId = session.id, role = "assistant", content = "你好\n世界", position = 0, status = "streaming")
    private fun bundle() = BackupBundle(characters = listOf(card), connections = listOf(connection), sessions = listOf(session), messages = listOf(message))

    @Test fun roundTripPreservesDraftSnapshotUnicodeAndOrdering() {
        val restored = BackupCodec.decode(BackupCodec.encode(bundle()))
        assertEquals(bundle().copy(exportedAt = restored.exportedAt), restored)
        assertEquals("尚未发出的草稿", restored.sessions.single().draft)
        assertFalse(BackupCodec.encode(restored).contains("apiKey"))
    }

    @Test fun remapKeepsReferencesAndMarksUnfinishedReplyInterrupted() {
        val source = bundle()
        val mapped = BackupCodec.remap(source)
        assertNotEquals(source.sessions.single().id, mapped.sessions.single().id)
        assertEquals(mapped.characters.single().id, mapped.sessions.single().characterId)
        assertEquals(mapped.connections.single().id, mapped.sessions.single().connectionId)
        assertEquals(mapped.sessions.single().id, mapped.messages.single().sessionId)
        assertEquals("interrupted", mapped.messages.single().status)
        BackupCodec.validate(mapped)
    }

    @Test fun importDoesNotReuseIdsOnRepeatedRestores() {
        assertNotEquals(BackupCodec.remap(bundle()).sessions.single().id, BackupCodec.remap(bundle()).sessions.single().id)
    }

    @Test fun rejectsUnknownVersionMissingReferencesAndDuplicateMessagePositions() {
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.validate(bundle().copy(version = 2)) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.validate(bundle().copy(characters = emptyList())) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.validate(bundle().copy(messages = listOf(message, message.copy(id = newId())))) }
    }

    @Test fun rejectsTamperedSnapshotAndUnknownMessageRole() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.validate(bundle().copy(sessions = listOf(session.copy(characterSnapshot = "{}"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.validate(bundle().copy(messages = listOf(message.copy(role = "system"))))
        }
    }
}

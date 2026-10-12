package com.moyer.roleplay

import com.moyer.roleplay.core.*
import com.moyer.roleplay.data.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class AvatarBackupTest {
    @Test fun customAndOriginalImagesSurviveBackupAndIdRemapping() {
        val card = CharacterCard(name = "角色", avatar = "original-png", customAvatar = "custom-png")
        val session = ChatSession(title = card.name, characterId = card.id, characterSnapshot = appJson.encodeToString(card))
        val restored = BackupCodec.remap(BackupCodec.decode(BackupCodec.encode(BackupBundle(characters = listOf(card), sessions = listOf(session), messages = emptyList()))))
        val imported = restored.characters.single()
        assertNotEquals(card.id, imported.id)
        assertEquals("original-png", imported.avatar)
        assertEquals("custom-png", sessionAvatar(restored.sessions.single(), imported))
        assertEquals("custom-png", sessionAvatar(restored.sessions.single(), null))
        assertEquals("original-png", sessionAvatar(restored.sessions.single(), imported.copy(customAvatar = "")))
        assertEquals(session.characterSnapshot, appJson.encodeToString(card))
    }

    @Test fun preUpdateSnapshotsAndCorruptImagesHaveSafeFallbacks() {
        val snapshot = """{"id":"old","name":"旧角色","avatar":"original"}"""
        val session = ChatSession(title = "旧角色", characterId = "old", characterSnapshot = snapshot)
        assertEquals("original", sessionAvatar(session, null))
        assertEquals("", sessionAvatar(session.copy(characterSnapshot = "invalid"), null))
    }
}

package com.moyer.roleplay.core

import com.moyer.roleplay.data.CharacterCard
import com.moyer.roleplay.data.ChatSession

fun CharacterCard.displayAvatar(): String = customAvatar.ifBlank { avatar }

// Appearance follows the current card; story settings continue to use their snapshot.
fun sessionAvatar(session: ChatSession, currentCard: CharacterCard?): String =
    (currentCard?.takeIf { it.id == session.characterId }
        ?: runCatching { appJson.decodeFromString<CharacterCard>(session.characterSnapshot) }.getOrNull())
        ?.displayAvatar().orEmpty()

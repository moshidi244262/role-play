package com.moyer.roleplay.core

import com.moyer.roleplay.data.CharacterCard

fun searchCharacters(cards: List<CharacterCard>, query: String): List<CharacterCard> {
    val terms = query.trim().split(Regex("\\s+")).filter(String::isNotBlank)
    if (terms.isEmpty()) return cards
    return cards.filter { card ->
        val fields = listOf(card.name, card.description, card.personality, card.scenario)
        terms.all { term -> fields.any { it.contains(term, ignoreCase = true) } }
    }
}

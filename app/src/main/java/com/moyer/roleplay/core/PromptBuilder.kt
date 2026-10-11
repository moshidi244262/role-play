package com.moyer.roleplay.core

import com.moyer.roleplay.data.*

object PromptBuilder {
    fun build(session: ChatSession, history: List<ChatMessage>): List<PromptMessage> {
        val card = appJson.decodeFromString<CharacterCard>(session.characterSnapshot)
        fun macros(text: String) = text.replace("{{char}}", card.name).replace("{{user}}", session.playerName)
        val system = buildString {
            appendLine("你正在进行虚构故事中的角色扮演。扮演 ${card.name}，保持人物与情节一致。")
            appendLine("以角色的语言和行动回应。玩家身份是 ${session.playerName}。不要替玩家决定行动或发言。")
            if (card.description.isNotBlank()) appendLine("角色描述：${macros(card.description)}")
            if (card.personality.isNotBlank()) appendLine("性格：${macros(card.personality)}")
            if (card.scenario.isNotBlank()) appendLine("场景：${macros(card.scenario)}")
            if (card.exampleDialogue.isNotBlank()) appendLine("以下是对话风格示例，不属于当前剧情：\n${macros(card.exampleDialogue)}")
        }
        return listOf(PromptMessage("system", system)) + history
            .filter { it.content.isNotBlank() && it.status != "failed" }
            .map { PromptMessage(it.role, it.content) }
    }
}

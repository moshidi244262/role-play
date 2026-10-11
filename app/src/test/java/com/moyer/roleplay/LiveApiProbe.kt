package com.moyer.roleplay

import com.moyer.roleplay.core.*
import com.moyer.roleplay.data.*
import com.moyer.roleplay.network.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.encodeToString

// Opt-in manual probe. No credentials in source, arguments, reports or CI; input is stdin only.
object LiveApiProbe {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val key=readln().trim()
        val api=ApiClient()
        val profile=ProviderTemplates.all.first()
        val models=api.models(profile,key)
        println("DISCOVERY_OK models="+models.joinToString(","))
        val card=CharacterCard(name="林舟",description="你是档案馆管理员，用一句话回答玩家的问题。")
        val lore=ResourceAsset(kind="lorebook",name="档案",payload=appJson.encodeToString(LoreBook(listOf(
            LoreEntry(keys=listOf("信"),content="失落的信在 B-17 号档案盒中。")))))
        val session=ChatSession(title=card.name,characterId=card.id,characterSnapshot=appJson.encodeToString(card),config=RoleplayConfig(lorebookIds=listOf(lore.id)).json())
        val report=PromptBuilder.compile(session,listOf(ChatMessage(sessionId=session.id,role="user",content="失落的信在哪个档案盒？只回答编号。",position=0)),listOf(lore))
        var text="";var usage: ApiEvent.Usage?=null;var done=false
        api.stream(profile.copy(model=models.firstOrNull {it==profile.model} ?: models.first(),maxOutputTokens=512),key,report.messages).collect {
            when(it) {is ApiEvent.Text->text+=it.value;is ApiEvent.Usage->usage=it;ApiEvent.Done->done=true}
        }
        check(done && text.isNotBlank()) {"No completed visible response"}
        println("STREAM_OK done=$done visible_chars=${text.length} lore_hits=${report.hits.size} expected_fact=${text.contains("B-17")} usage=$usage")
    }
}

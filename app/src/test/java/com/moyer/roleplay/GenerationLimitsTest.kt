package com.moyer.roleplay

import com.moyer.roleplay.core.*
import com.moyer.roleplay.data.*
import com.moyer.roleplay.network.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class GenerationLimitsTest {
    private val profile = ProviderTemplates.all.first()
    private val card = CharacterCard(name = "长度测试")
    private fun session(config: RoleplayConfig) = ChatSession(title = card.name, characterId = card.id,
        characterSnapshot = appJson.encodeToString(card), config = config.json())

    @Test fun specifiedLimitsAndProviderDefaultsReachTheHttpRequestUnchanged() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            for (provider in listOf("DeepSeek", "OpenAI")) for (protocol in listOf(Protocol.CHAT_COMPLETIONS, Protocol.RESPONSES)) {
                for (limit in listOf(0, 16384, 393216)) {
                    val end = if (protocol == Protocol.RESPONSES) """{"type":"response.completed","response":{}}""" else "[DONE]"
                    server.enqueue(MockResponse().setBody("data: $end\n\n"))
                    ApiClient(true).stream(profile.copy(provider = provider, protocol = protocol,
                        baseUrl = server.url("/v1").toString(), maxOutputTokens = limit), "fake-test-key",
                        listOf(PromptMessage("user", "test"))).toList()
                    val body = appJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
                    val field = if (protocol == Protocol.RESPONSES) "max_output_tokens" else if (provider == "OpenAI") "max_completion_tokens" else "max_tokens"
                    if (limit == 0) assertFalse(body.keys.any { it in setOf("max_tokens", "max_completion_tokens", "max_output_tokens") })
                    else assertEquals(limit, body[field]!!.jsonPrimitive.int)
                }
            }
        } finally { server.shutdown() }
    }

    @Test fun messagesRequiresAnExplicitLimitAndInvalidEditsNeverBecomeDefaults() {
        assertThrows(IllegalArgumentException::class.java) {
            ApiWire.body(profile.copy(protocol = Protocol.ANTHROPIC_MESSAGES, maxOutputTokens = 0), emptyList())
        }
        assertEquals(8192, ApiWire.body(profile.copy(protocol = Protocol.ANTHROPIC_MESSAGES, maxOutputTokens = 8192),
            emptyList())["max_tokens"]!!.jsonPrimitive.int)
        for (limit in listOf(-1, 1048577)) assertThrows(IllegalArgumentException::class.java) {
            ApiWire.body(profile.copy(maxOutputTokens = limit), emptyList())
        }
    }

    @Test fun lengthStopRetainsTextAndTrailingUsageThenCompletesTransport() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("""
                data: {"choices":[{"delta":{"content":"未写完"}}]}

                data: {"choices":[{"delta":{},"finish_reason":"length"}]}

                data: {"choices":[],"usage":{"prompt_tokens":7358,"completion_tokens":2048,"prompt_cache_hit_tokens":3200}}

                data: [DONE]

            """.trimIndent()))
            val events = ApiClient(true).stream(profile.copy(baseUrl = server.url("/v1").toString()),
                "fake-test-key", listOf(PromptMessage("user", "test"))).toList()
            assertEquals(listOf(ApiEvent.Text("未写完"), ApiEvent.End("length_limited"), ApiEvent.Usage(7358,2048,3200), ApiEvent.Done), events)
        } finally { server.shutdown() }
    }

    @Test fun responsesIncompleteRetainsUsageAndClaudeDistinguishesNormalStopFromLength() {
        assertEquals(listOf(ApiEvent.Usage(80,32,null), ApiEvent.End("length_limited"), ApiEvent.Done),
            ApiWire.parse(Protocol.RESPONSES, """{"type":"response.incomplete","response":{"incomplete_details":{"reason":"max_output_tokens"},"usage":{"input_tokens":80,"output_tokens":32}}}"""))
        assertEquals(listOf(ApiEvent.End("filtered"), ApiEvent.Done), ApiWire.parse(Protocol.RESPONSES,
            """{"type":"response.incomplete","response":{"incomplete_details":{"reason":"content_filter"}}}"""))
        for ((reason,status) in listOf("end_turn" to "complete", "max_tokens" to "length_limited", "model_context_window_exceeded" to "length_limited")) {
            assertEquals(listOf(ApiEvent.Usage(output=2048), ApiEvent.End(status)), ApiWire.parse(Protocol.ANTHROPIC_MESSAGES,
                """{"type":"message_delta","delta":{"stop_reason":"$reason"},"usage":{"output_tokens":2048}}"""))
        }
    }

    @Test fun finiteBudgetPreservesAllMessagesThatFitWithoutA200MessageCap() {
        val history = List(251) { ChatMessage(sessionId="s",role=if(it%2==0) "user" else "assistant",content="线索 $it",position=it.toLong()) }
        for (budget in listOf(0, 48000)) {
            val report = PromptBuilder.compile(session(RoleplayConfig(historyBudgetChars=budget)),history)
            assertEquals(0,report.trimmedMessages)
            assertTrue(report.messages.any {it.content=="线索 0"})
            assertEquals("线索 250",report.messages.last().content)
        }
    }

    @Test fun unlimitedContextPreservesLargeFixedContentAndHistoryWhileFiniteBudgetStillTrims() {
        val history = List(20) { ChatMessage(sessionId="s",role=if(it%2==0) "user" else "assistant",
            content="第 $it 条："+"文".repeat(500),position=it.toLong()) }
        val config = RoleplayConfig(historyBudgetChars=0,memory="固定线索".repeat(1000))
        val unlimited = PromptBuilder.compile(session(config),history)
        assertEquals(0,unlimited.trimmedMessages)
        assertEquals(history.map {it.content},unlimited.messages.filter {it.role!="system"}.map {it.content})
        assertTrue(unlimited.readable().contains("不限制"))
        val finite = PromptBuilder.compile(session(RoleplayConfig(historyBudgetChars=2000)),history)
        assertTrue(finite.trimmedMessages>0)
        assertTrue(finite.messages.sumOf {it.content.length}<=2000)
        assertEquals(history.last().content,finite.messages.last().content)
        assertThrows(IllegalArgumentException::class.java) { PromptBuilder.compile(session(config.copy(historyBudgetChars=2000)),history) }
    }

    @Test fun backupRoundTripPreservesDefaultOutputUnlimitedContextAndLengthStatus() {
        val config = RoleplayConfig(historyBudgetChars=0)
        val connection = profile.copy(maxOutputTokens=0)
        val chat = session(config).copy(connectionId=connection.id)
        val messages = listOf("length_limited","filtered").mapIndexed { i,status ->
            ChatMessage(sessionId=chat.id,role="assistant",content="保留的文字",position=i.toLong(),status=status,inputTokens=7358,outputTokens=2048)
        }
        val bundle = BackupBundle(characters=listOf(card),connections=listOf(connection),sessions=listOf(chat),messages=messages,defaultsConfig=config.json())
        val restored = BackupCodec.decode(BackupCodec.encode(bundle))
        assertEquals(0,restored.connections.single().maxOutputTokens)
        assertEquals(0,readConfig(restored.sessions.single().config).historyBudgetChars)
        assertEquals(messages,restored.messages)
        assertEquals(restored.messages.map {it.status},BackupCodec.remap(restored).messages.map {it.status})
    }
}

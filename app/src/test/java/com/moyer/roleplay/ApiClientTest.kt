package com.moyer.roleplay

import com.moyer.roleplay.data.*
import com.moyer.roleplay.network.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class ApiClientTest {
    private val profile = ProviderTemplates.all[0].copy(model = "test-model")

    @Test fun endpointsDoNotDuplicateVersionAndRejectSecretInUrl() {
        assertEquals("/v1/chat/completions", ApiWire.endpoint(profile, "chat/completions").encodedPath)
        assertEquals("/v1/responses", ApiWire.endpoint(profile.copy(baseUrl = "https://api.openai.com/v1"), "responses").encodedPath)
        assertEquals("/proxy/v1/messages", ApiWire.endpoint(profile.copy(baseUrl = "https://example.com/proxy/v1/"), "messages").encodedPath)
        assertThrows(IllegalArgumentException::class.java) {
            ApiWire.endpoint(profile.copy(baseUrl = "https://user:key@example.com"), "models")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ApiWire.endpoint(profile.copy(baseUrl = "https://example.com/v1/responses"), "models")
        }
    }

    @Test fun anthropicSystemFieldIsSeparateAndResponsesDisablesStorage() {
        val messages = listOf(PromptMessage("system", "角色规则"), PromptMessage("user", "你好"))
        val anthropic = ApiWire.body(profile.copy(protocol = Protocol.ANTHROPIC_MESSAGES), messages)
        assertEquals("角色规则", anthropic["system"]!!.jsonPrimitive.content)
        assertEquals(1, anthropic["messages"]!!.jsonArray.size)
        val responses = ApiWire.body(profile.copy(protocol = Protocol.RESPONSES), messages)
        assertFalse(responses["store"]!!.jsonPrimitive.boolean)
        assertTrue(responses.containsKey("max_output_tokens"))
        assertFalse(responses.containsKey("max_tokens"))
    }

    @Test fun reasoningIsNotMixedIntoRoleDialogue() {
        val events = ApiWire.parse(Protocol.CHAT_COMPLETIONS,
            """{"choices":[{"delta":{"reasoning_content":"hidden","content":"你好"}}]}""")
        assertEquals(listOf(ApiEvent.Text("你好")), events)
        assertEquals(emptyList<ApiEvent>(), ApiWire.parse(Protocol.ANTHROPIC_MESSAGES,
            """{"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"hidden"}}"""))
    }

    @Test fun parsesResponsesAndClaudeTextEvents() {
        assertEquals(listOf(ApiEvent.Text("故事")), ApiWire.parse(Protocol.RESPONSES,
            """{"type":"response.output_text.delta","delta":"故事"}"""))
        assertEquals(listOf(ApiEvent.Text("继续")), ApiWire.parse(Protocol.ANTHROPIC_MESSAGES,
            """{"type":"content_block_delta","delta":{"type":"text_delta","text":"继续"}}"""))
    }

    @Test fun mockHttpStreamsWithAuthenticationAndDetectsTruncation() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val configured = profile.copy(baseUrl = server.url("/v1").toString())
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\ndata: [DONE]\n\n"))
            val events = ApiClient(true).stream(configured, "fake-test-key", listOf(PromptMessage("user", "你好"))).toList()
            assertEquals(listOf(ApiEvent.Text("你好"), ApiEvent.Done), events)
            val request = server.takeRequest()
            assertEquals("/v1/chat/completions", request.path)
            assertEquals("Bearer fake-test-key", request.getHeader("Authorization"))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"半段\"}}]}\n\n"))
            var failure: Exception? = null
            try { ApiClient(true).stream(configured, "fake-test-key", listOf(PromptMessage("user", "你好"))).toList() }
            catch (e: Exception) { failure = e }
            assertTrue(failure is ApiFailure)
        } finally { server.shutdown() }
    }

    @Test fun messagesTemplateDoesNotOverrideExistingSnapshot() {
        val card = CharacterCard(name = "旧角色", description = "{{char}} 对 {{user}} 说话")
        val session = ChatSession(title = "测试", characterId = card.id,
            characterSnapshot = com.moyer.roleplay.core.appJson.encodeToJsonElement(card).toString())
        val result = com.moyer.roleplay.core.PromptBuilder.build(session, emptyList()).single().content
        assertTrue(result.contains("旧角色 对 玩家 说话"))
    }

    @Test fun stoppingGenerationCancelsBlockedHttpRead() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = launch {
                ApiClient(true).stream(profile.copy(baseUrl = server.url("/v1").toString()),
                    "fake-test-key", listOf(PromptMessage("user", "test"))).toList()
            }
            delay(200)
            val started = System.nanoTime()
            job.cancelAndJoin()
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3000)
        } finally { server.shutdown() }
    }
}

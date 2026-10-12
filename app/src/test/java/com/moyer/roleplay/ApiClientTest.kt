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

    @Test fun malformedStreamFieldsAreSanitizedAndNullErrorIsAccepted() {
        val failure = runCatching {
            ApiWire.parse(Protocol.CHAT_COMPLETIONS, """{"choices":"private-api-response"}""")
        }.exceptionOrNull()
        assertTrue(failure is ApiFailure)
        assertFalse(failure!!.message!!.contains("private-api-response"))
        assertEquals(listOf(ApiEvent.Text("你好")), ApiWire.parse(Protocol.CHAT_COMPLETIONS,
            """{"error":null,"choices":[{"delta":{"content":"你好"}}]}"""))
    }

    @Test fun oversizedNetworkResponsesFailWithBoundedReads() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val configured = profile.copy(baseUrl = server.url("/v1").toString())
            server.enqueue(MockResponse().setBody("data: " + "x".repeat(1024 * 1024 + 1)))
            val streamError = runCatching {
                ApiClient(true).stream(configured, "fake-test-key", listOf(PromptMessage("user", "test"))).toList()
            }.exceptionOrNull()
            assertTrue(streamError is ApiFailure)
            assertTrue(streamError!!.message!!.contains("过大"))
            server.enqueue(MockResponse().setBody("x".repeat(4 * 1024 * 1024 + 1)))
            val modelsError = runCatching { ApiClient(true).models(configured, "fake-test-key") }.exceptionOrNull()
            assertTrue(modelsError is ApiFailure)
            assertTrue(modelsError!!.message!!.contains("过大"))
        } finally { server.shutdown() }
    }

    @Test fun cancellingModelDiscoveryClosesBlockedSocket() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = launch {
                ApiClient(true).models(profile.copy(baseUrl = server.url("/v1").toString()), "fake-test-key")
            }
            assertNotNull(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) })
            val started = System.nanoTime()
            job.cancelAndJoin()
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3000)
        } finally { server.shutdown() }
    }

    @Test fun lastSseEventWithoutNewlineStillCompletes() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"content\":\"尾段\"}}]}\n\ndata: [DONE]"))
            assertEquals(listOf(ApiEvent.Text("尾段"), ApiEvent.Done), ApiClient(true)
                .stream(profile.copy(baseUrl = server.url("/v1").toString()), "fake-test-key", listOf(PromptMessage("user", "test"))).toList())
        } finally { server.shutdown() }
    }

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
        val result = com.moyer.roleplay.core.PromptBuilder.build(session, emptyList()).joinToString("\n") {it.content}
        assertTrue(result.contains("旧角色 对 我 说话"))
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

    @Test fun modelDiscoveryAcceptsBlankModelAndSanitizesFailures() = runBlocking {
        val server=MockWebServer();server.start()
        try {
            val configured=profile.copy(baseUrl=server.url("/").toString(),model="")
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"b"},{"id":"a"},{"id":"a"},{"id":""}]}"""))
            assertEquals(listOf("a","b"),ApiClient(true).models(configured,"fake-test-key"))
            val request=server.takeRequest();assertEquals("/v1/models",request.path)
            assertEquals("Bearer fake-test-key",request.getHeader("Authorization"))
            server.enqueue(MockResponse().setResponseCode(401).setBody("private response secret"))
            val error=runCatching {ApiClient(true).models(configured,"fake-test-key")}.exceptionOrNull()!!
            assertTrue(error is ApiFailure);assertFalse(error.message!!.contains("private"))
            server.enqueue(MockResponse().setBody("private malformed body"))
            assertTrue(runCatching {ApiClient(true).models(configured,"fake-test-key")}.exceptionOrNull() is ApiFailure)
        } finally {server.shutdown()}
    }

    @Test fun cacheUsageIsReportedWithoutReasoningText() {
        assertEquals(listOf(ApiEvent.Usage(100,20,80)),ApiWire.parse(Protocol.CHAT_COMPLETIONS,
            """{"usage":{"prompt_tokens":100,"completion_tokens":20,"prompt_cache_hit_tokens":80}}"""))
        assertEquals(listOf(ApiEvent.Usage(100,20,80),ApiEvent.Done),ApiWire.parse(Protocol.RESPONSES,
            """{"type":"response.completed","response":{"usage":{"input_tokens":100,"output_tokens":20,"input_tokens_details":{"cached_tokens":80}}}}"""))
        assertEquals(listOf(ApiEvent.Usage(10,0,50)),ApiWire.parse(Protocol.ANTHROPIC_MESSAGES,
            """{"type":"message_start","message":{"usage":{"input_tokens":10,"output_tokens":0,"cache_read_input_tokens":50}}}"""))
        assertEquals(listOf(ApiEvent.Usage(output=20)),ApiWire.parse(Protocol.ANTHROPIC_MESSAGES,
            """{"type":"message_delta","usage":{"output_tokens":20}}"""))
    }

    @Test fun claudeModelDiscoveryFetchesAllPagesWithCorrectAuthentication() = runBlocking {
        val server=MockWebServer();server.start()
        try {
            val claude=ProviderTemplates.all[2].copy(baseUrl=server.url("/v1").toString())
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"claude-sonnet-example"}],"has_more":true,"last_id":"claude-sonnet-example"}"""))
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"claude-opus-example"},{"id":"old-retired","lifecycle":"retired"}],"has_more":false}"""))
            assertEquals(listOf("claude-opus-example","claude-sonnet-example"),ApiClient(true).models(claude,"fake-test-key"))
            val first=server.takeRequest();assertEquals("/v1/models",first.path)
            assertEquals("fake-test-key",first.getHeader("x-api-key"))
            assertEquals("2023-06-01",first.getHeader("anthropic-version"))
            assertEquals("/v1/models?after_id=claude-sonnet-example",server.takeRequest().path)
        } finally {server.shutdown()}
    }

    @Test fun brokenModelPaginationDoesNotLoopOrReturnPartialSuccess() = runBlocking {
        val server=MockWebServer();server.start()
        try {
            repeat(2) {server.enqueue(MockResponse().setBody("""{"data":[{"id":"a"}],"has_more":true,"last_id":"a"}"""))}
            val failure=runCatching {ApiClient(true).models(profile.copy(baseUrl=server.url("/v1").toString()),"fake-test-key")}.exceptionOrNull()
            assertTrue(failure is ApiFailure);assertEquals(2,server.requestCount)
        } finally {server.shutdown()}
    }
}

package com.moyer.roleplay.network

import com.moyer.roleplay.core.appJson
import com.moyer.roleplay.data.*
import com.moyer.roleplay.data.Protocol
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

sealed interface ApiEvent {
    data class Text(val value: String) : ApiEvent
    data class Usage(val input: Int? = null, val output: Int? = null, val cached: Int? = null) : ApiEvent
    data object Done : ApiEvent
}

class ApiFailure(val safeMessage: String) : Exception(safeMessage)

object ApiWire {
    fun endpoint(profile: ConnectionProfile, resource: String): HttpUrl {
        val base = profile.baseUrl.trim().trimEnd('/').toHttpUrlOrNull()
            ?: throw IllegalArgumentException("服务地址无效")
        require(base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null) {
            "服务地址不能包含账号、密钥、查询参数或片段"
        }
        val path = base.encodedPath.trimEnd('/')
        require(!path.endsWith("/chat/completions") && !path.endsWith("/responses") &&
            !path.endsWith("/messages") && !path.endsWith("/models")) { "请填写 Base URL，不要填写完整请求路径" }
        val prefix = if (path.isEmpty()) "/v1" else path
        return base.newBuilder().encodedPath("$prefix/$resource").build()
    }

    fun body(profile: ConnectionProfile, messages: List<PromptMessage>): JsonObject = buildJsonObject {
        put("model", profile.model)
        put("stream", true)
        when (profile.protocol) {
            Protocol.CHAT_COMPLETIONS -> {
                put("messages", JsonArray(messages.map { buildJsonObject {
                    put("role", it.role); put("content", it.content)
                } }))
                put(if (profile.provider == "OpenAI") "max_completion_tokens" else "max_tokens", profile.maxOutputTokens)
                if(profile.provider in setOf("OpenAI","DeepSeek","Grok")) put("stream_options",buildJsonObject { put("include_usage",true) })
            }
            Protocol.RESPONSES -> {
                put("store", false)
                put("input", JsonArray(messages.map { buildJsonObject {
                    put("role", it.role); put("content", it.content)
                } }))
                put("max_output_tokens", profile.maxOutputTokens)
            }
            Protocol.ANTHROPIC_MESSAGES -> {
                put("system", messages.filter { it.role == "system" }.joinToString("\n\n") { it.content })
                put("messages", JsonArray(messages.filter { it.role != "system" }.map { buildJsonObject {
                    put("role", it.role); put("content", it.content)
                } }))
                put("max_tokens", profile.maxOutputTokens)
            }
        }
    }

    fun parse(protocol: Protocol, data: String): List<ApiEvent> {
        if (data == "[DONE]") return listOf(ApiEvent.Done)
        val root = runCatching { appJson.parseToJsonElement(data).jsonObject }.getOrNull()
            ?: throw ApiFailure("服务商返回了无效的流式数据")
        if (root["error"] != null || root["type"]?.jsonPrimitive?.content in setOf("error", "response.failed", "response.incomplete")) {
            throw ApiFailure("服务商中断了生成，请检查模型权限、额度或输出上限")
        }
        return buildList {
            when (protocol) {
                Protocol.CHAT_COMPLETIONS -> {
                    val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                    choice?.get("delta")?.jsonObject?.get("content")?.let {
                        if (it is JsonPrimitive && it.isString) add(ApiEvent.Text(it.content))
                    }
                    root["usage"]?.takeIf { it is JsonObject }?.jsonObject?.let {
                        add(ApiEvent.Usage(it["prompt_tokens"]?.jsonPrimitive?.intOrNull,
                            it["completion_tokens"]?.jsonPrimitive?.intOrNull,
                            it["prompt_cache_hit_tokens"]?.jsonPrimitive?.intOrNull ?: (it["prompt_tokens_details"] as? JsonObject)?.get("cached_tokens")?.jsonPrimitive?.intOrNull))
                    }
                    // Wait for [DONE] to capture any trailing usage-only chunk.
                }
                Protocol.RESPONSES -> when (root["type"]?.jsonPrimitive?.content) {
                    "response.output_text.delta", "response.refusal.delta" ->
                        root["delta"]?.jsonPrimitive?.contentOrNull?.let { add(ApiEvent.Text(it)) }
                    "response.completed" -> {
                        root["response"]?.jsonObject?.get("usage")?.jsonObject?.let {
                            add(ApiEvent.Usage(it["input_tokens"]?.jsonPrimitive?.intOrNull,
                                it["output_tokens"]?.jsonPrimitive?.intOrNull,
                                (it["input_tokens_details"] as? JsonObject)?.get("cached_tokens")?.jsonPrimitive?.intOrNull))
                        }
                        add(ApiEvent.Done)
                    }
                }
                Protocol.ANTHROPIC_MESSAGES -> when (root["type"]?.jsonPrimitive?.content) {
                    "message_start", "message_delta" -> {
                        val usage=(root["usage"] ?: (root["message"] as? JsonObject)?.get("usage")) as? JsonObject
                        usage?.let { add(ApiEvent.Usage(it["input_tokens"]?.jsonPrimitive?.intOrNull,
                            it["output_tokens"]?.jsonPrimitive?.intOrNull,it["cache_read_input_tokens"]?.jsonPrimitive?.intOrNull)) }
                    }
                    "content_block_delta" -> root["delta"]?.jsonObject?.let {
                        if (it["type"]?.jsonPrimitive?.content == "text_delta")
                            it["text"]?.jsonPrimitive?.contentOrNull?.let { text -> add(ApiEvent.Text(text)) }
                    }
                    "message_stop" -> add(ApiEvent.Done)
                }
            }
        }
    }

    fun httpError(code: Int): String = when (code) {
        400, 422 -> "请求参数不被该模型支持，请检查协议、模型 ID 和输出上限"
        401 -> "密钥无效或已过期，请重新填写"
        402 -> "API 余额不足，请检查服务商账户"
        403 -> "没有该模型的调用权限，或服务商限制了访问"
        404 -> "模型或接口不存在，请检查模型 ID 与服务地址"
        413 -> "聊天上下文超过接口限制，请缩短记录或使用更大上下文的模型"
        429 -> "API 限流或额度不足，请稍后再试并检查账户额度"
        in 500..599 -> "服务商暂时不可用，请稍后重试"
        else -> "API 请求失败（HTTP $code）"
    }
}

class ApiClient(private val allowInsecureLocalhost: Boolean = false) {
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS).callTimeout(300, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()

    private fun request(profile: ConnectionProfile, key: String, resource: String): Request.Builder {
        val url = ApiWire.endpoint(profile, resource)
        require(url.isHttps || (allowInsecureLocalhost && url.host in setOf("localhost", "127.0.0.1"))) {
            "服务地址必须使用 HTTPS"
        }
        require(key.isNotBlank()) { "请先填写 API 密钥" }
        require(key.all { it.code in 33..126 }) { "API 密钥包含空格或无效字符，请重新复制" }
        require(profile.versionHeader.all { it.code in 32..126 }) { "API 版本头包含无效字符" }
        return Request.Builder().url(url).header("Accept", "application/json").apply {
            if (profile.protocol == Protocol.ANTHROPIC_MESSAGES) {
                header("x-api-key", key); header("anthropic-version", profile.versionHeader)
            } else header("Authorization", "Bearer $key")
        }
    }

    fun stream(profile: ConnectionProfile, key: String, messages: List<PromptMessage>): Flow<ApiEvent> = flow {
        require(profile.model.isNotBlank()) { "请填写模型 ID" }
        val resource = when (profile.protocol) {
            Protocol.CHAT_COMPLETIONS -> "chat/completions"
            Protocol.RESPONSES -> "responses"
            Protocol.ANTHROPIC_MESSAGES -> "messages"
        }
        val call = client.newCall(request(profile, key, resource).header("Accept", "text/event-stream")
            .post(ApiWire.body(profile, messages).toString().toRequestBody("application/json".toMediaType())).build())
        coroutineScope {
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw ApiFailure(ApiWire.httpError(response.code))
                    val source = response.body?.source() ?: throw ApiFailure("服务商返回空响应")
                    var complete = false
                    val eventData = StringBuilder()
                    suspend fun dispatch() {
                        if (eventData.isEmpty()) return
                        val events = ApiWire.parse(profile.protocol, eventData.toString())
                        eventData.clear()
                        for (event in events) {
                            emit(event)
                            if (event == ApiEvent.Done) complete = true
                        }
                    }
                    while (!complete) {
                        currentCoroutineContext().ensureActive()
                        val line = source.readUtf8Line() ?: break
                        if (line.isEmpty()) dispatch()
                        else if (line.startsWith("data:")) {
                            if (eventData.isNotEmpty()) eventData.append('\n')
                            eventData.append(line.removePrefix("data:").removePrefix(" "))
                            if (eventData.length > 1024 * 1024) throw ApiFailure("流式数据块过大")
                        }
                    }
                    if (!complete) dispatch()
                    if (!complete) throw ApiFailure("连接在生成完成前断开，已保留收到的内容")
                }
            } catch (error: java.io.IOException) {
                // Closing the socket during Stop is cancellation, not a network failure.
                currentCoroutineContext().ensureActive()
                throw error
            } finally { cancellation.cancel(); call.cancel() }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun models(profile: ConnectionProfile, key: String): List<String> = withContext(Dispatchers.IO) {
        val modelClient = client.newBuilder().callTimeout(20, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
        modelClient.newCall(request(profile, key, "models").get().build()).execute().use {
            if (!it.isSuccessful) throw ApiFailure(ApiWire.httpError(it.code))
            val body = it.body?.string() ?: throw ApiFailure("服务商返回空模型列表")
            try {
                val root = appJson.parseToJsonElement(body).jsonObject
                root["data"]?.jsonArray?.mapNotNull { row -> row.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
                    ?.filter { id -> id.isNotBlank() }?.distinct()?.sorted() ?: emptyList()
            } catch (_: Exception) { throw ApiFailure("服务商返回的模型列表格式不兼容，可以手动填写模型 ID") }
        }
    }
}

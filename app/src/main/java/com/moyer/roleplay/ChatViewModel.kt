package com.moyer.roleplay

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moyer.roleplay.core.*
import com.moyer.roleplay.data.*
import com.moyer.roleplay.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as RolePlayApplication
    private val repository = app.repository
    private val dao = repository.dao
    private val preferences = app.getSharedPreferences("ui", Context.MODE_PRIVATE)
    private val api = ApiClient()
    private val mutations = Mutex()
    private var generation: Job? = null
    val characters = dao.characters().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val connections = dao.connections().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val sessions = dao.sessions().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val selectedId = MutableStateFlow<String?>(null)
    val messages = selectedId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else dao.messages(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val sending = MutableStateFlow(false)
    val ready = MutableStateFlow(false)
    val notice = MutableStateFlow<String?>(null)
    val pendingImport = MutableStateFlow<BackupBundle?>(null)
    val operationBusy = MutableStateFlow(false)
    val modelChoices = MutableStateFlow<List<String>>(emptyList())
    val darkMode = MutableStateFlow(preferences.getBoolean("dark", false))
    val drafts = MutableStateFlow<Map<String, String>>(emptyMap())

    init {
        viewModelScope.launch {
            try {
                repository.initialize()
                preferences.getString("last_session", null)?.let { if (dao.session(it) != null) selectedId.value = it }
            } catch (_: Exception) { notice.value = "本地数据库打开失败，请保留应用数据并反馈问题" }
            finally { ready.value = true }
        }
    }

    fun clearNotice() { notice.value = null }
    fun openChat(id: String) {
        selectedId.value = id
        preferences.edit().putString("last_session", id).apply()
    }
    fun backToList() { selectedId.value = null }
    fun setDark(value: Boolean) {
        darkMode.value = value
        preferences.edit().putBoolean("dark", value).apply()
    }

    fun newChat(card: CharacterCard) = viewModelScope.launch {
        safe { openChat(repository.createSession(card, connections.value.firstOrNull()?.id)) }
    }

    fun saveCharacter(card: CharacterCard) = viewModelScope.launch {
        safe {
            require(card.name.isNotBlank()) { "请填写角色名称" }
            dao.saveCharacter(card.copy(name = card.name.trim(), updatedAt = System.currentTimeMillis()))
            notice.value = "角色已保存；已有会话继续使用原角色快照"
        }
    }

    fun saveConnection(profile: ConnectionProfile, newKey: String, onSaved: () -> Unit) = viewModelScope.launch {
        safe {
            ApiWire.endpoint(profile, "models").also { require(it.isHttps) { "服务地址必须使用 HTTPS" } }
            require(profile.name.isNotBlank() && profile.model.isNotBlank()) { "请填写连接名称和模型 ID" }
            require(profile.maxOutputTokens in 1..65536) { "输出上限须在 1～65536 之间" }
            if (newKey.isBlank()) {
                val existing = dao.connection(profile.id)
                require(existing == null || sameCredentialTarget(existing, profile)) { "服务商或地址已改变，请重新填写密钥" }
            }
            if (newKey.isNotBlank()) withContext(Dispatchers.IO) { app.secrets.put(profile.id, newKey.trim()) }
            dao.saveConnection(profile.copy(name = profile.name.trim(), model = profile.model.trim(), baseUrl = profile.baseUrl.trim()))
            notice.value = "连接已保存"; onSaved()
        }
    }

    fun fetchModels(profile: ConnectionProfile, newKey: String) = viewModelScope.launch {
        if (operationBusy.value) return@launch
        operationBusy.value = true; modelChoices.value = emptyList()
        safe {
            val key = resolveKey(profile, newKey)
            modelChoices.value = api.models(profile, key.trim())
            notice.value = if (modelChoices.value.isEmpty()) "该接口未返回模型列表，可以手动填写模型 ID" else "已获取模型列表"
        }
        operationBusy.value = false
    }

    fun testConnection(profile: ConnectionProfile, newKey: String) = viewModelScope.launch {
        if (operationBusy.value) return@launch
        operationBusy.value = true
        safe {
            val key = resolveKey(profile, newKey)
            var text = ""
            api.stream(profile.copy(maxOutputTokens = 256), key.trim(), listOf(PromptMessage("user", "请只回复：连接成功")))
                .collect { if (it is ApiEvent.Text) text += it.value }
            require(text.isNotBlank()) { "接口没有返回文字，请检查模型类型或输出上限" }
            notice.value = "连接测试成功，已收到模型文字回复"
        }
        operationBusy.value = false
    }

    fun saveDraft(id: String, draft: String) {
        drafts.value = drafts.value + (id to draft)
        viewModelScope.launch { mutations.withLock { safe { dao.saveDraft(id, draft) } } }
    }

    fun bindConnection(id: String, connectionId: String) = viewModelScope.launch {
        if (!sending.value) safe { dao.bindConnection(id, connectionId) }
    }

    fun send(text: String?, continueStory: Boolean = false) {
        val id = selectedId.value ?: return
        if (sending.value || (!continueStory && text.isNullOrBlank())) return
        sending.value = true
        generation = viewModelScope.launch {
            var reply: ChatMessage? = null
            val accumulated = StringBuilder()
            var lastFlush = 0L
            try {
                val session = requireNotNull(dao.session(id)) { "会话不存在" }
                val profile = session.connectionId?.let { dao.connection(it) }
                    ?: throw IllegalArgumentException("请先在设置中添加 API 连接，再为本会话选择连接")
                val key = withContext(Dispatchers.IO) { app.secrets.get(profile.id) }
                    ?: throw IllegalArgumentException("请在设置中为该连接填写 API 密钥")
                val userText = if (continueStory) null else text!!.trim()
                val currentReply = mutations.withLock { repository.startTurn(id, userText) }
                reply = currentReply
                if (userText != null) drafts.value = drafts.value + (id to "")
                val history = dao.history(id).filter { it.id != currentReply.id }
                val prompt = PromptBuilder.build(session, history).toMutableList()
                if (continueStory) prompt += PromptMessage("user", "请从当前剧情自然继续，不复述已有内容，也不要替玩家决定行动。")
                api.stream(profile, key, prompt).collect { event ->
                    if (event is ApiEvent.Text) {
                        accumulated.append(event.value)
                        val now = System.currentTimeMillis()
                        if (now - lastFlush >= 180) {
                            dao.updateMessage(currentReply.id, accumulated.toString(), "streaming"); lastFlush = now
                        }
                    }
                }
                require(accumulated.isNotBlank()) { "模型没有返回文字，请检查模型能力或输出上限" }
                dao.updateMessage(currentReply.id, accumulated.toString(), "complete")
                dao.touch(id, System.currentTimeMillis())
            } catch (e: CancellationException) {
                withContext(NonCancellable) { reply?.let { dao.updateMessage(it.id, accumulated.toString(), "interrupted") } }
                throw e
            } catch (e: Exception) {
                reply?.let { dao.updateMessage(it.id, accumulated.toString(), if (accumulated.isEmpty()) "failed" else "interrupted") }
                notice.value = safeError(e)
            } finally { sending.value = false; generation = null }
        }
    }

    fun stop() { generation?.cancel() }

    fun deleteChat(id: String) = viewModelScope.launch {
        if (sending.value) { notice.value = "请先停止生成再删除会话"; return@launch }
        safe {
            mutations.withLock { dao.deleteSession(id) }
            if (preferences.getString("last_session", null) == id) preferences.edit().remove("last_session").apply()
            if (selectedId.value == id) selectedId.value = null
        }
    }

    fun exportTo(uri: Uri, sessionId: String?, readable: Boolean) = viewModelScope.launch {
        if (operationBusy.value) return@launch
        operationBusy.value = true
        safe {
            val text = mutations.withLock {
                if (readable) repository.readableExport(requireNotNull(sessionId))
                else BackupCodec.encode(repository.export(sessionId))
            }
            require(text.toByteArray(Charsets.UTF_8).size <= BackupCodec.MAX_BYTES) { "记录超过 16 MB，暂不支持完整导出" }
            withContext(Dispatchers.IO) {
                val stream = app.contentResolver.openOutputStream(uri, "wt") ?: error("文件无法写入")
                stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            }
            notice.value = "已导出；API 密钥不包含在文件中"
        }
        operationBusy.value = false
    }

    fun previewImport(uri: Uri) = viewModelScope.launch {
        if (operationBusy.value) return@launch
        operationBusy.value = true
        safe {
            val text = withContext(Dispatchers.IO) {
                val stream = app.contentResolver.openInputStream(uri) ?: error("文件无法读取")
                stream.use {
                    val output = ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (true) {
                        val count = it.read(chunk)
                        if (count < 0) break
                        require(output.size() + count <= BackupCodec.MAX_BYTES) { "文件超过 16 MB，暂不支持" }
                        output.write(chunk, 0, count)
                    }
                    output.toString("UTF-8")
                }
            }
            pendingImport.value = BackupCodec.decode(text)
        }
        operationBusy.value = false
    }

    fun confirmImport() = viewModelScope.launch {
        val bundle = pendingImport.value ?: return@launch
        if (operationBusy.value || sending.value) { notice.value = "请先停止生成，再导入记录"; return@launch }
        pendingImport.value = null; operationBusy.value = true
        safe {
            mutations.withLock { repository.restore(bundle) }
            notice.value = "已导入 ${bundle.sessions.size} 个会话、${bundle.messages.size} 条消息；导入连接需重新填写密钥"
        }
        operationBusy.value = false
    }

    suspend fun promptPreview(id: String): String {
        val session = requireNotNull(dao.session(id))
        return PromptBuilder.build(session, dao.history(id)).joinToString("\n\n") { "[${it.role}]\n${it.content}" }
    }

    private suspend fun safe(action: suspend () -> Unit) {
        try { action() } catch (e: CancellationException) { throw e } catch (e: Exception) { notice.value = safeError(e) }
    }

    private fun sameCredentialTarget(a: ConnectionProfile, b: ConnectionProfile): Boolean =
        a.provider == b.provider && a.protocol == b.protocol &&
            a.baseUrl.trim().trimEnd('/') == b.baseUrl.trim().trimEnd('/')

    private suspend fun resolveKey(profile: ConnectionProfile, typedKey: String): String {
        if (typedKey.isNotBlank()) return typedKey.trim()
        val stored = dao.connection(profile.id)
        require(stored != null && sameCredentialTarget(stored, profile)) { "请为该服务地址填写 API 密钥" }
        return withContext(Dispatchers.IO) { app.secrets.get(profile.id) }
            ?: throw IllegalArgumentException("请填写 API 密钥")
    }

    // Never surface raw network exception bodies, URLs with credentials, or API response dumps.
    private fun safeError(error: Exception): String = when (error) {
        is ApiFailure -> error.safeMessage
        is IllegalArgumentException -> error.message ?: "配置或文件格式无效"
        is java.net.SocketTimeoutException -> "连接超时，已保留收到的文字"
        is java.io.IOException -> "网络或文件读写失败，请检查连接或重新选择文件"
        else -> "操作未完成，请重试；已有聊天记录仍保存在本地"
    }
}

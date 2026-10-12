package com.moyer.roleplay

import android.app.Application
import android.content.Context
import android.net.Uri
import android.graphics.BitmapFactory
import android.util.Base64
import android.provider.OpenableColumns
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

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
    val resources = dao.resources().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val defaults = dao.defaults().map { readConfig(it ?: "{}") }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RoleplayConfig())
    val selectedId = MutableStateFlow<String?>(null)
    val messages = selectedId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else dao.messages(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val sending = MutableStateFlow(false)
    val ready = MutableStateFlow(false)
    val notice = MutableStateFlow<String?>(null)
    val pendingImport = MutableStateFlow<ImportPreview?>(null)
    val operationBusy = MutableStateFlow(false)
    val modelChoices = MutableStateFlow<List<String>>(emptyList())
    val savedModels = MutableStateFlow(runCatching {
        appJson.decodeFromString<Map<String, List<String>>>(preferences.getString("model_lists", "{}") ?: "{}")
    }.getOrDefault(emptyMap()))
    val connectionFeedback = MutableStateFlow<String?>(null)
    val darkMode = MutableStateFlow(preferences.getBoolean("dark", false))
    val showTokenUsage = MutableStateFlow(preferences.getBoolean("show_token_usage", true))
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

    fun setShowTokenUsage(value: Boolean) {
        showTokenUsage.value = value
        preferences.edit().putBoolean("show_token_usage", value).apply()
    }

    fun newChat(card: CharacterCard, greeting: String? = null) = viewModelScope.launch {
        safe { openChat(repository.createSession(card, connections.value.firstOrNull()?.id, greeting)) }
    }

    fun saveCharacter(card: CharacterCard) = viewModelScope.launch {
        safe {
            require(card.name.isNotBlank()) { "请填写角色名称" }
            require(card.name.length<=200) {"角色名称最多 200 字符"}
            val greetings=appJson.decodeFromString<List<String>>(card.alternateGreetings)
            require(greetings.size<=100 && greetings.all {it.length<=32000}) {"备选开场白超出限制"}
            dao.saveCharacter(card.copy(name = card.name.trim(), updatedAt = System.currentTimeMillis()))
            notice.value = "角色已保存；头像同步到会话列表，已有剧情设定保留"
        }
    }

    fun saveConnection(profile: ConnectionProfile, newKey: String, onSaved: () -> Unit) = viewModelScope.launch {
        try {
            val previous = dao.connection(profile.id)
            if (previous != null && !sameCredentialTarget(previous, profile)) cacheModels(profile.id, emptyList())
            ApiWire.endpoint(profile, "models").also { require(it.isHttps) { "服务地址必须使用 HTTPS" } }
            require(profile.name.isNotBlank()) { "请填写连接名称" }
            require(profile.maxOutputTokens in 1..65536) { "输出上限须在 1～65536 之间" }
            if (newKey.isBlank()) {
                val existing = dao.connection(profile.id)
                require(existing == null || sameCredentialTarget(existing, profile)) { "服务商或地址已改变，请重新填写密钥" }
            }
            if (newKey.isNotBlank()) {
                require(newKey.trim().all { it.code in 33..126 }) { "API 密钥含有空格或无效字符，请重新复制" }
                withContext(Dispatchers.IO) { app.secrets.put(profile.id, newKey.trim()) }
            }
            dao.saveConnection(profile.copy(name = profile.name.trim(), model = profile.model.trim(), baseUrl = profile.baseUrl.trim()))
            notice.value = if (profile.model.isBlank()) "连接已保存；开始聊天前请选定或填写模型 ID" else "连接已保存"
            onSaved()
        } catch(e: CancellationException) { throw e }
        catch(e: Exception) { connectionFeedback.value = safeError(e) }
    }

    fun fetchModels(profile: ConnectionProfile, newKey: String) = viewModelScope.launch {
        if (operationBusy.value) return@launch
        operationBusy.value = true; modelChoices.value = emptyList(); connectionFeedback.value = "正在获取模型，最长等待 20 秒…"
        try {
            val key = resolveKey(profile, newKey)
            modelChoices.value = api.models(profile, key.trim())
            val existing = dao.connection(profile.id)
            if (existing == null || sameCredentialTarget(existing, profile)) cacheModels(profile.id, modelChoices.value)
            connectionFeedback.value = if (modelChoices.value.isEmpty()) "该接口未返回模型列表，可以手动填写模型 ID" else "已获取 ${modelChoices.value.size} 个模型，点击名称选择"
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { connectionFeedback.value = safeError(e) }
        finally { operationBusy.value = false }
    }

    private fun cacheModels(id: String, models: List<String>) {
        savedModels.value = savedModels.value + (id to models)
        preferences.edit().putString("model_lists", appJson.encodeToString(savedModels.value)).apply()
    }

    fun selectModel(sessionId: String, model: String) = viewModelScope.launch {
        if (!sending.value) safe {
            require(model.isNotBlank() && model.length <= 200) { "模型 ID 无效" }
            val session = requireNotNull(dao.session(sessionId))
            dao.saveConfig(session.id, readConfig(session.config).copy(modelOverride = model).json(), session.playerName)
        }
    }

    fun testConnection(profile: ConnectionProfile, newKey: String) = viewModelScope.launch {
        if (operationBusy.value) return@launch
        operationBusy.value = true; connectionFeedback.value = "正在测试生成连接…"
        try {
            val key = resolveKey(profile, newKey)
            var text = ""
            api.stream(profile.copy(maxOutputTokens = profile.maxOutputTokens.coerceIn(512, 2048)), key.trim(), listOf(PromptMessage("user", "请只回复：连接成功")))
                .collect { if (it is ApiEvent.Text) text += it.value }
            require(text.isNotBlank()) { "接口没有返回文字，请检查模型类型或输出上限" }
            connectionFeedback.value = "连接测试成功，已收到模型文字回复"
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { connectionFeedback.value = safeError(e) }
        finally { operationBusy.value = false }
    }

    fun saveDraft(id: String, draft: String) {
        drafts.value = drafts.value + (id to draft)
        viewModelScope.launch { mutations.withLock { safe { dao.saveDraft(id, draft) } } }
    }

    fun bindConnection(id: String, connectionId: String) = viewModelScope.launch {
        if (!sending.value) safe { mutations.withLock {
            val session = requireNotNull(dao.session(id))
            dao.bindConnection(id, connectionId)
            dao.saveConfig(id, readConfig(session.config).copy(modelOverride = "").json(), session.playerName)
        } }
    }

    fun send(text: String?, continueStory: Boolean = false, answerExisting: Boolean = false) {
        val id = selectedId.value ?: return
        if (sending.value || (!continueStory && !answerExisting && text.isNullOrBlank())) return
        sending.value = true
        generation = viewModelScope.launch {
            var reply: ChatMessage? = null
            val accumulated = StringBuilder()
            var lastFlush = 0L
            var inputTokens: Int? = null; var outputTokens: Int? = null; var cachedTokens: Int? = null
            try {
                val session = requireNotNull(dao.session(id)) { "会话不存在" }
                val profile = session.connectionId?.let { dao.connection(it) }?.let { sessionProfile(it, readConfig(session.config)) }
                    ?: throw IllegalArgumentException("请先在设置中添加 API 连接，再为本会话选择连接")
                val key = withContext(Dispatchers.IO) { app.secrets.get(profile.id) }
                    ?: throw IllegalArgumentException("请在设置中为该连接填写 API 密钥")
                require(profile.model.isNotBlank()) { "请在设置中为该连接选择或填写模型 ID；草稿已保留" }
                val userText = if (continueStory || answerExisting) null else text!!.trim()
                // Validate and compile before committing a turn so a budget error preserves the draft.
                val originalHistory = dao.history(id)
                val preparedHistory = originalHistory + listOfNotNull(userText?.let { ChatMessage(sessionId=id,role="user",content=it,position=(originalHistory.maxOfOrNull { m -> m.position } ?: -1)+1) })
                val assets=dao.allResources()
                val report=withContext(Dispatchers.Default) { PromptBuilder.compile(session,preparedHistory,assets) }
                val currentReply = mutations.withLock { repository.startTurn(id, userText) }
                reply = currentReply
                if (userText != null) drafts.value = drafts.value + (id to "")
                val prompt = report.messages.toMutableList()
                if (continueStory) prompt += PromptMessage("user", "请从当前剧情自然继续，不复述已有内容，也不要替玩家决定行动。")
                api.stream(profile, key, prompt).collect { event ->
                    if (event is ApiEvent.Text) {
                        accumulated.append(event.value)
                        val now = System.currentTimeMillis()
                        if (now - lastFlush >= 180) {
                            dao.updateMessage(currentReply.id, accumulated.toString(), "streaming"); lastFlush = now
                        }
                    }
                    if(event is ApiEvent.Usage) {
                        inputTokens=event.input ?: inputTokens; outputTokens=event.output ?: outputTokens; cachedTokens=event.cached ?: cachedTokens
                        dao.updateUsage(currentReply.id,inputTokens,outputTokens,cachedTokens)
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

    fun forkMessage(messageId: String,edited: String?=null,regenerate: Boolean=false) = viewModelScope.launch {
        val id=selectedId.value ?: return@launch
        if(sending.value) {notice.value="请先停止生成";return@launch}
        safe {
            if(edited!=null) require(edited.isNotBlank()) {"消息不能为空"}
            val fork=mutations.withLock {repository.forkSession(id,messageId,edited,regenerate)}
            openChat(fork)
            if(regenerate) send(null,answerExisting=true) else notice.value="已创建分支，原会话保留"
        }
    }

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
            val bytes = withContext(Dispatchers.IO) {
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
                    output.toByteArray()
                }
            }
            val filename=withContext(Dispatchers.IO) {
                app.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {
                    if(it.moveToFirst()) it.getString(0) else "导入资料"
                } ?: "导入资料"
            }
            pendingImport.value = withContext(Dispatchers.Default) {
                val pack=CommunityImporter.decode(bytes,filename)
                if(pack.characters.isNotEmpty() && PngCardCodec.isPng(bytes)) {
                    val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                    BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                    require(bounds.outWidth in 1..20000 && bounds.outHeight in 1..20000) { "角色卡图片尺寸无效" }
                    var sample=1
                    while(bounds.outWidth/sample>600 || bounds.outHeight/sample>600) sample*=2
                    val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize=sample })
                    val avatar=if(bitmap==null) "" else ByteArrayOutputStream().use { output ->
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output); bitmap.recycle()
                        Base64.encodeToString(output.toByteArray(),Base64.NO_WRAP)
                    }
                    pack.copy(characters=pack.characters.map { it.copy(avatar=avatar) })
                } else pack
            }
        }
        operationBusy.value = false
    }

    fun confirmImport() = viewModelScope.launch {
        val pack = pendingImport.value ?: return@launch
        if (operationBusy.value || sending.value) { notice.value = "请先停止生成，再导入记录"; return@launch }
        pendingImport.value = null; operationBusy.value = true
        safe {
            mutations.withLock { pack.backup?.let { repository.restore(it) } ?: repository.importCommunity(pack) }
            notice.value = "已导入 ${pack.characterCount} 个角色、${pack.resourceCount} 份资料、${pack.sessionCount} 个会话" +
                if(pack.backup?.connections?.isNotEmpty()==true) "；导入连接需重新填写密钥" else ""
        }
        operationBusy.value = false
    }

    suspend fun promptPreview(id: String): String {
        val session = requireNotNull(dao.session(id))
        val history=dao.history(id); val assets=dao.allResources()
        val report=withContext(Dispatchers.Default) { PromptBuilder.compile(session,history,assets) }
        val profile=session.connectionId?.let { dao.connection(it) }?.let { sessionProfile(it, readConfig(session.config)) }
        return report.readable() + if(profile?.protocol==Protocol.ANTHROPIC_MESSAGES)
            "\nClaude Messages 会将所有 system 提示合并到独立 system 字段；system 深度位置会转换为全局提示。\n\n实际请求正文（不含密钥）：\n${ApiWire.body(profile,report.messages)}" else ""
    }

    fun saveResource(asset: ResourceAsset, onSaved: ()->Unit) = viewModelScope.launch {
        safe { asset.validate(); dao.saveResource(asset.copy(name=asset.name.trim(),updatedAt=System.currentTimeMillis())); onSaved(); notice.value="资料已保存" }
    }
    fun toggleResource(asset: ResourceAsset) = viewModelScope.launch { safe { dao.saveResource(asset.copy(enabled=!asset.enabled)) } }
    fun deleteResource(id: String) = viewModelScope.launch {
        if(sending.value) { notice.value="请停止生成后删除资料"; return@launch }
        safe { mutations.withLock { repository.deleteAsset(id) }; notice.value="资料已删除，关联已解除" }
    }
    fun saveConfig(sessionId: String?, config: RoleplayConfig, onSaved: ()->Unit) = viewModelScope.launch {
        if(sending.value) { notice.value="请停止生成后修改会话设定"; return@launch }
        safe {
            config.validate()
            mutations.withLock {
                if(sessionId==null) dao.saveSetting(StoredSetting("defaults",config.json()))
                else dao.saveConfig(sessionId,config.json(),config.playerName.ifBlank { "我" })
            }
            onSaved(); notice.value=if(sessionId==null) "新会话默认资料已保存" else "会话设定已保存"
        }
    }
    fun exportAsset(uri: Uri, asset: ResourceAsset) = viewModelScope.launch {
        safe {
            val text=buildJsonObject { put("format","roleplay.asset"); put("version",1); put("asset",appJson.encodeToJsonElement(asset)) }.toString()
            withContext(Dispatchers.IO) {
                (app.contentResolver.openOutputStream(uri,"wt") ?: error("文件无法写入")).use { it.write(text.toByteArray()) }
            }
            notice.value="资料已导出"
        }
    }

    fun exportCharacter(uri: Uri, card: CharacterCard) = viewModelScope.launch {
        safe {
            val text=BackupCodec.encode(repository.exportCharacter(card))
            require(text.toByteArray().size<=BackupCodec.MAX_BYTES) {"角色与关联资料超过 16 MB"}
            withContext(Dispatchers.IO) {
                (app.contentResolver.openOutputStream(uri,"wt") ?: error("文件无法写入")).use {it.write(text.toByteArray())}
            }
            notice.value="角色与关联资料已导出为本 App JSON，可重新导入"
        }
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
        is kotlinx.serialization.SerializationException -> "数据格式不兼容，请检查文件类型或 API 协议"
        is IllegalArgumentException -> error.message ?: "配置或文件格式无效"
        is java.net.UnknownHostException -> "无法解析服务地址，请检查手机网络、DNS 或 VPN 后重试"
        is javax.net.ssl.SSLException -> "HTTPS 安全连接失败，请检查设备时间或网络代理"
        is java.net.SocketTimeoutException -> "连接超时，已保留收到的文字"
        is java.io.IOException -> "网络或文件读写失败，请检查连接或重新选择文件"
        else -> "操作未完成，请重试；已有聊天记录仍保存在本地"
    }
}

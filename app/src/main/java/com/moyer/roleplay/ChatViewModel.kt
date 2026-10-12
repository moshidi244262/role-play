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
    private var summaryGeneration: Job? = null
    val summaryBusy=MutableStateFlow(false)
    val summaryCandidate=MutableStateFlow<SummaryCandidate?>(null)
    private val pendingDrafts = linkedMapOf<String, String>()
    private var draftWriter: Job? = null
    val selectedId = MutableStateFlow<String?>(null)
    val characters = dao.characters().distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val connections = dao.connections().distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val sessions = dao.sessions().distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val sessionPreviews = selectedId.flatMapLatest { id ->
        if(id!=null) flowOf(emptyMap<String,String>()) else dao.sessionPreviews().map {rows ->
            withContext(Dispatchers.Default) {rows.associate {it.sessionId to MessageMarkup.preview(it.content)}}
        }
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyMap())
    val resources = dao.resources().distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val defaults = dao.defaults().map { readConfig(it ?: "{}") }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RoleplayConfig())
    val messages = selectedId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else dao.messages(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val sending = MutableStateFlow(false)
    val ready = MutableStateFlow(false)
    val notice = MutableStateFlow<String?>(null)
    val pendingImport = MutableStateFlow<ImportPreview?>(null)
    val cardInspection = MutableStateFlow<CardInspection?>(null)
    val cardInspectionError = MutableStateFlow<String?>(null)
    val cardInspectionBusy = MutableStateFlow(false)
    var pendingInspectionExport: CardInspection? = null
    val operationBusy = MutableStateFlow(false)
    val modelChoices = MutableStateFlow<List<String>>(emptyList())
    val savedModels = MutableStateFlow(runCatching {
        appJson.decodeFromString<Map<String, List<String>>>(preferences.getString("model_lists", "{}") ?: "{}")
    }.getOrDefault(emptyMap()))
    val connectionFeedback = MutableStateFlow<String?>(null)
    val darkMode = MutableStateFlow(preferences.getBoolean("dark", false))
    val showTokenUsage = MutableStateFlow(preferences.getBoolean("show_token_usage", true))
    val readingAppearance=MutableStateFlow(ReadingAppearance(preferences.getInt("reading_font",16).coerceIn(14,22),preferences.getBoolean("quiet_reading",false)))
    val drafts = MutableStateFlow<Map<String, String>>(emptyMap())

    init {
        viewModelScope.launch {
            try {
                repository.initialize()
                // Cold launches always start at the session list. Activity recreation keeps this ViewModel.
                preferences.edit().remove("last_session").apply()
            } catch (_: Exception) { notice.value = "本地数据库打开失败，请保留应用数据并反馈问题" }
            finally { ready.value = true }
        }
    }

    fun clearNotice() { notice.value = null }
    fun openChat(id: String) {
        selectedId.value = id
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

    fun setReadingAppearance(value: ReadingAppearance) {
        val checked=value.copy(fontSize=value.fontSize.coerceIn(14,22))
        readingAppearance.value=checked
        preferences.edit().putInt("reading_font",checked.fontSize).putBoolean("quiet_reading",checked.quiet).apply()
    }

    fun newChat(card: CharacterCard, greeting: String? = null) = viewModelScope.launch {
        safe { openChat(repository.createSession(card, connections.value.firstOrNull()?.id, greeting)) }
    }

    fun saveCharacter(card: CharacterCard, onSaved: () -> Unit = {}) = viewModelScope.launch {
        safe {
            withContext(Dispatchers.Default) {
                require(card.name.isNotBlank()) { "请填写角色名称" }
                require(card.name.length<=200) {"角色名称最多 200 字符"}
                readConfig(card.config)
                val greetings=appJson.decodeFromString<List<String>>(card.alternateGreetings)
                require(greetings.size<=100 && greetings.all {it.length<=32000}) {"备选开场白超出限制"}
            }
            dao.saveCharacter(card.copy(name = card.name.trim(), updatedAt = System.currentTimeMillis()))
            onSaved()
            notice.value = "角色已保存；头像同步到会话列表，已有剧情设定保留"
        }
    }

    fun saveConnection(profile: ConnectionProfile, newKey: String, onSaved: () -> Unit) = viewModelScope.launch {
        try {
            val previous = dao.connection(profile.id)
            if (previous != null && !sameCredentialTarget(previous, profile)) cacheModels(profile.id, emptyList())
            ApiWire.endpoint(profile, "models").also { require(it.isHttps) { "服务地址必须使用 HTTPS" } }
            require(profile.name.isNotBlank()) { "请填写连接名称" }
            profile.validateOutputLimit()
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
        if (!sending.value && !summaryBusy.value) safe {
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
        if (drafts.value[id] == draft) return
        drafts.value = drafts.value + (id to draft)
        pendingDrafts[id] = draft
        if (draftWriter?.isActive == true) return
        // Keep the newest value per session while a write is running, without a timed debounce.
        draftWriter = viewModelScope.launch {
            while (pendingDrafts.isNotEmpty()) mutations.withLock {
                pendingDrafts.entries.firstOrNull()?.let { (sessionId, value) ->
                    pendingDrafts.remove(sessionId)
                    safe { dao.saveDraft(sessionId, value) }
                }
            }
        }
    }

    fun bindConnection(id: String, connectionId: String) = viewModelScope.launch {
        if (!sending.value && !summaryBusy.value) safe { mutations.withLock {
            val session = requireNotNull(dao.session(id))
            dao.bindConnection(id, connectionId)
            dao.saveConfig(id, readConfig(session.config).copy(modelOverride = "").json(), session.playerName)
        } }
    }

    fun send(text: String?, continueStory: Boolean = false, answerExisting: Boolean = false) {
        val id = selectedId.value ?: return
        if (sending.value || summaryBusy.value || (!continueStory && !answerExisting && text.isNullOrBlank())) return
        sending.value = true
        generation = viewModelScope.launch {
            var reply: ChatMessage? = null
            val accumulated = StringBuilder()
            var lastFlush = 0L
            var inputTokens: Int? = null; var outputTokens: Int? = null; var cachedTokens: Int? = null
            var endStatus = "complete"
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
                val report=withContext(Dispatchers.Default) { PromptBuilder.compile(session,preparedHistory,assets,if(continueStory) "continue" else if(answerExisting) "regenerate" else "normal") }
                if (report.trimmedMessages > 0) notice.value = "本轮上下文已省略 ${report.trimmedMessages} 条旧消息；可在会话设定中提高预算或关闭 App 裁剪。本地记录仍保留。"
                val currentReply = mutations.withLock {
                    repository.startTurn(id, userText).also {
                        if (userText != null) pendingDrafts.remove(id)
                    }
                }
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
                    if (event is ApiEvent.End) endStatus = event.status
                }
                require(accumulated.isNotBlank() || endStatus != "complete") { "模型没有返回文字，请检查模型能力或输出上限" }
                dao.updateMessage(currentReply.id, accumulated.toString(), endStatus)
                dao.touch(id, System.currentTimeMillis())
                if(endStatus=="complete" && readConfig(requireNotNull(dao.session(id)).config).autoSummary) generateStorySummary(id,true)
            } catch (e: CancellationException) {
                withContext(NonCancellable) { reply?.let { dao.updateMessage(it.id, accumulated.toString(), "interrupted") } }
                throw e
            } catch (e: Exception) {
                reply?.let { dao.updateMessage(it.id, accumulated.toString(), if (accumulated.isEmpty()) "failed" else "interrupted") }
                notice.value = safeError(e)
            } finally { sending.value = false; generation = null }
        }
    }

    fun stop() { generation?.cancel(); summaryGeneration?.cancel() }

    fun generateStorySummary(sessionId: String, automatic: Boolean = false) {
        if(summaryBusy.value || (!automatic && sending.value)) return
        summaryBusy.value=true
        summaryCandidate.value=null
        summaryGeneration=viewModelScope.launch {
            try {
                val session=requireNotNull(dao.session(sessionId)) {"会话不存在"}
                val config=readConfig(session.config)
                val plan=withContext(Dispatchers.Default) {StoryMemory.plan(dao.history(sessionId),config.storySummary,if(automatic) 20 else 1)}
                if(plan==null) {
                    if(!automatic) notice.value="暂时没有可整理的旧剧情。保留最近12条消息，需要至少13条完整消息；单条旧消息超过60000字符时请先手工整理。"
                    return@launch
                }
                val profile=session.connectionId?.let {dao.connection(it)}?.let {sessionProfile(it,config)}
                    ?: throw IllegalArgumentException("请先选择API连接与模型")
                require(profile.model.isNotBlank()) {"请先选择模型"}
                val key=withContext(Dispatchers.IO) {app.secrets.get(profile.id)} ?: throw IllegalArgumentException("请先填写该连接的API密钥")
                val content=StringBuilder();var status="complete"
                api.stream(profile.copy(maxOutputTokens=if(profile.maxOutputTokens==0) 4096 else profile.maxOutputTokens.coerceAtMost(4096)),key,plan.prompt()).collect {event ->
                    if(event is ApiEvent.Text) {content.append(event.value);require(content.length<=16000) {"摘要过长，请手工整理"}}
                    if(event is ApiEvent.End) status=event.status
                }
                require(status=="complete" && content.isNotBlank()) {"摘要未完整生成，原有记忆保留，请重试或手工整理"}
                val summary=plan.summary(content.toString()).also {it.validate()}
                mutations.withLock {
                    val latest=requireNotNull(dao.session(sessionId))
                    val latestConfig=readConfig(latest.config)
                    require(latestConfig.storySummary==config.storySummary && StoryMemory.valid(summary,dao.history(sessionId))!=null) {"剧情已变化，请重新整理摘要"}
                    if(automatic && latestConfig.autoSummary) {
                        dao.saveConfig(sessionId,latestConfig.copy(storySummary=summary).json(),latest.playerName)
                        notice.value="已更新剧情摘要；可在剧情记忆中查看与修订"
                    } else if(!automatic) summaryCandidate.value=SummaryCandidate(sessionId,summary,config.storySummary)
                }
            } catch(e: CancellationException) {throw e}
            catch(e: Exception) {notice.value="剧情摘要：${safeError(e)}；原有记忆保留"}
            finally {summaryBusy.value=false;summaryGeneration=null}
        }
    }

    fun saveStoryMemory(id: String, fixed: String, summary: StorySummary?, automatic: Boolean, compact: Boolean,
        expected: StorySummary?, onSaved: ()->Unit) = viewModelScope.launch {
        if(sending.value || summaryBusy.value) return@launch
        safe {mutations.withLock {
            val session=requireNotNull(dao.session(id))
            val config=readConfig(session.config)
            require(config.storySummary==expected) {"摘要已更新，请关闭后重新打开剧情记忆"}
            require(summary==null || StoryMemory.valid(summary,dao.history(id))!=null) {"摘要来源已变化，请重新生成"}
            val value=config.copy(memory=fixed,storySummary=summary,autoSummary=automatic,compactSummaryHistory=compact)
            value.validate();dao.saveConfig(id,value.json(),session.playerName)
            summaryCandidate.value=null
            notice.value="剧情记忆已保存";onSaved()
        }}
    }


    fun forkMessage(messageId: String,edited: String?=null,regenerate: Boolean=false) = viewModelScope.launch {
        val id=selectedId.value ?: return@launch
        if(sending.value || summaryBusy.value) {notice.value="请先停止生成";return@launch}
        safe {
            if(edited!=null) require(edited.isNotBlank()) {"消息不能为空"}
            val fork=mutations.withLock {repository.forkSession(id,messageId,edited,regenerate)}
            openChat(fork)
            if(regenerate) send(null,answerExisting=true) else notice.value="已创建分支，原会话保留"
        }
    }

    fun deleteChat(id: String) = viewModelScope.launch {
        if (sending.value || summaryBusy.value) { notice.value = "请先停止生成再删除会话"; return@launch }
        safe {
            mutations.withLock {
                dao.deleteSession(id)
                pendingDrafts.remove(id)
                drafts.value = drafts.value - id
            }
            if (preferences.getString("last_session", null) == id) preferences.edit().remove("last_session").apply()
            if (selectedId.value == id) selectedId.value = null
        }
    }

    fun exportTo(uri: Uri, sessionId: String?, readable: Boolean) = viewModelScope.launch {
        if (operationBusy.value) return@launch
        operationBusy.value = true
        try { safe {
            val bytes = mutations.withLock {
                if (readable) withContext(Dispatchers.Default) { repository.readableExport(requireNotNull(sessionId)).toByteArray(Charsets.UTF_8) }
                else {
                    val bundle = repository.export(sessionId)
                    withContext(Dispatchers.Default) { BackupCodec.encode(bundle).toByteArray(Charsets.UTF_8) }
                }
            }
            require(bytes.size <= BackupCodec.MAX_BYTES) { "记录超过 16 MB，暂不支持完整导出" }
            withContext(Dispatchers.IO) {
                val stream = app.contentResolver.openOutputStream(uri, "wt") ?: error("文件无法写入")
                stream.use { it.write(bytes) }
            }
            notice.value = "已导出；API 密钥不包含在文件中"
        } } finally { operationBusy.value = false }
    }

    fun previewImport(uri: Uri, expectedKind: String? = null) = viewModelScope.launch {
        if (operationBusy.value) return@launch
        operationBusy.value = true
        pendingImport.value = null
        try { safe {
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
                }?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/')?.substringAfter(':')?.takeIf { it.isNotBlank() } ?: "导入资料"
            }
            pendingImport.value = withContext(Dispatchers.Default) {
                val pack=CommunityImporter.decode(bytes,filename,expectedKind)
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
        } } finally { operationBusy.value = false }
    }

    fun inspectCharacterFile(uri: Uri) = viewModelScope.launch {
        if(cardInspectionBusy.value) return@launch
        cardInspectionBusy.value=true; cardInspection.value=null; cardInspectionError.value=null
        try {
            val bytes=withContext(Dispatchers.IO) {
                (app.contentResolver.openInputStream(uri) ?: error("文件无法读取")).use {input ->
                    val output=ByteArrayOutputStream(); val buffer=ByteArray(8192)
                    while(true) {
                        val n=input.read(buffer); if(n<0) break
                        require(output.size()+n<=BackupCodec.MAX_BYTES) {"文件超过 16 MB，暂不支持"}
                        output.write(buffer,0,n)
                    }
                    output.toByteArray()
                }
            }
            cardInspection.value=withContext(Dispatchers.Default) {CharacterCardInspector.inspect(bytes)}
        } catch(e: CancellationException) {throw e}
        catch(e: Exception) {cardInspectionError.value=safeError(e)}
        finally {cardInspectionBusy.value=false}
    }

    fun inspectStoredCharacter(card: CharacterCard) = viewModelScope.launch {
        if(cardInspectionBusy.value) return@launch
        cardInspectionBusy.value=true; cardInspection.value=null; cardInspectionError.value=null
        try {cardInspection.value=withContext(Dispatchers.Default) {CharacterCardInspector.stored(card)}}
        catch(e: CancellationException) {throw e}
        catch(e: Exception) {cardInspectionError.value=safeError(e)}
        finally {cardInspectionBusy.value=false}
    }

    fun exportCardInspection(uri: Uri, inspection: CardInspection, json: Boolean) = viewModelScope.launch {
        if(cardInspectionBusy.value) return@launch
        cardInspectionBusy.value=true
        try {safe {
            val bytes=withContext(Dispatchers.Default) {
                (if(json) inspection.sourceJson else inspection.plainText()).toByteArray(Charsets.UTF_8)
            }
            withContext(Dispatchers.IO) {
                (app.contentResolver.openOutputStream(uri,"wt") ?: error("文件无法写入")).use {it.write(bytes)}
            }
            notice.value="角色卡${if(json) " JSON" else "明文"}已导出"
        }} finally {cardInspectionBusy.value=false}
    }

    fun confirmImport() = viewModelScope.launch {
        val pack = pendingImport.value ?: return@launch
        if (operationBusy.value || sending.value) { notice.value = "请先停止生成，再导入记录"; return@launch }
        pendingImport.value = null; operationBusy.value = true
        try { safe {
            mutations.withLock { pack.backup?.let { repository.restore(it) } ?: repository.importCommunity(pack) }
            notice.value = "已导入 ${pack.characterCount} 个角色、${pack.resourceCount} 份资料、${pack.sessionCount} 个会话" +
                if(pack.backup?.connections?.isNotEmpty()==true) "；导入连接需重新填写密钥" else ""
        } } finally { operationBusy.value = false }
    }

    suspend fun promptPreview(id: String): String {
        val session = requireNotNull(dao.session(id))
        val history=dao.history(id); val assets=dao.allResources()
        val report=withContext(Dispatchers.Default) { PromptBuilder.compile(session,history,assets) }
        val profile=session.connectionId?.let { dao.connection(it) }?.let { sessionProfile(it, readConfig(session.config)) }
        val output = profile?.let { if (it.maxOutputTokens == 0) "输出上限：跟随服务商默认（App 不发送输出限额参数；服务商仍有限制）" else "本次 API 最大输出：${it.maxOutputTokens} tokens（模型可能提前结束；以服务商支持范围为准）" }
        return (output?.plus("\n\n") ?: "") + report.readable() + if(profile?.protocol==Protocol.ANTHROPIC_MESSAGES)
            "\nClaude Messages 会将所有 system 提示合并到独立 system 字段；system 深度位置会转换为全局提示。\n\n实际请求正文（不含密钥）：\n${ApiWire.body(profile,report.messages)}" else ""
    }

    fun saveResource(asset: ResourceAsset, onSaved: ()->Unit) = viewModelScope.launch {
        safe { withContext(Dispatchers.Default) { asset.validate() }; dao.saveResource(asset.copy(name=asset.name.trim(),updatedAt=System.currentTimeMillis())); onSaved(); notice.value="资料已保存" }
    }
    fun toggleResource(asset: ResourceAsset) = viewModelScope.launch { safe { dao.saveResource(asset.copy(enabled=!asset.enabled)) } }
    fun deleteResource(id: String) = viewModelScope.launch {
        if(sending.value) { notice.value="请停止生成后删除资料"; return@launch }
        safe { mutations.withLock { repository.deleteAsset(id) }; notice.value="资料已删除，关联已解除" }
    }
    fun saveConfig(sessionId: String?, config: RoleplayConfig, onSaved: ()->Unit) = viewModelScope.launch {
        if(sending.value || summaryBusy.value) { notice.value="请停止生成后修改会话设定"; return@launch }
        safe {
            config.validate()
            mutations.withLock {
                if(sessionId==null) dao.saveSetting(StoredSetting("defaults",config.json()))
                else {
                    val current=requireNotNull(dao.session(sessionId))
                    val live=readConfig(current.config)
                    require(live.storySummary==config.storySummary) {"剧情记忆已更新，请重新打开会话设定"}
                    dao.saveConfig(sessionId,config.json(),config.playerName.ifBlank { "我" })
                }
            }
            onSaved(); notice.value=if(sessionId==null) "新会话默认资料已保存" else "会话设定已保存"
        }
    }
    fun exportAsset(uri: Uri, asset: ResourceAsset) = viewModelScope.launch {
        safe {
            val bytes=withContext(Dispatchers.Default) {
                buildJsonObject { put("format","roleplay.asset"); put("version",1); put("asset",appJson.encodeToJsonElement(asset)) }.toString().toByteArray(Charsets.UTF_8)
            }
            withContext(Dispatchers.IO) {
                (app.contentResolver.openOutputStream(uri,"wt") ?: error("文件无法写入")).use { it.write(bytes) }
            }
            notice.value="资料已导出"
        }
    }

    fun exportCharacter(uri: Uri, card: CharacterCard) = viewModelScope.launch {
        safe {
            val bundle=repository.exportCharacter(card)
            val bytes=withContext(Dispatchers.Default) { BackupCodec.encode(bundle).toByteArray(Charsets.UTF_8) }
            require(bytes.size<=BackupCodec.MAX_BYTES) {"角色与关联资料超过 16 MB"}
            withContext(Dispatchers.IO) {
                (app.contentResolver.openOutputStream(uri,"wt") ?: error("文件无法写入")).use {it.write(bytes)}
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

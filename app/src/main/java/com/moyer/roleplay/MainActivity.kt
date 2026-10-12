package com.moyer.roleplay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moyer.roleplay.data.*
import com.moyer.roleplay.core.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.encodeToString
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RolePlayApp() }
    }
}

private data class NavItem(val title: String, val icon: ImageVector)
private val navigation = listOf(NavItem("会话", Icons.Outlined.ChatBubbleOutline),
    NavItem("角色", Icons.Outlined.PeopleOutline), NavItem("资料库", Icons.Outlined.AutoStories),
    NavItem("设置", Icons.Outlined.Settings))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RolePlayApp(vm: ChatViewModel = viewModel()) {
    val dark by vm.darkMode.collectAsStateWithLifecycle()
    val colors = if (dark) darkColorScheme(primary = Color(0xFFBFB1FF), secondary = Color(0xFFA9C8C8),
        background = Color(0xFF15141C), surface = Color(0xFF1C1B25))
    else lightColorScheme(primary = Color(0xFF7463C9), secondary = Color(0xFF537E80),
        background = Color(0xFFF8F7FC), surface = Color(0xFFFDFCFF))
    MaterialTheme(colorScheme = colors) {
        val ready by vm.ready.collectAsStateWithLifecycle()
        val cards by vm.characters.collectAsStateWithLifecycle()
        val connections by vm.connections.collectAsStateWithLifecycle()
        val sessions by vm.sessions.collectAsStateWithLifecycle()
        val resources by vm.resources.collectAsStateWithLifecycle()
        val defaults by vm.defaults.collectAsStateWithLifecycle()
        val selectedId by vm.selectedId.collectAsStateWithLifecycle()
        val messages by vm.messages.collectAsStateWithLifecycle()
        val sending by vm.sending.collectAsStateWithLifecycle()
        val drafts by vm.drafts.collectAsStateWithLifecycle()
        val notice by vm.notice.collectAsStateWithLifecycle()
        val pending by vm.pendingImport.collectAsStateWithLifecycle()
        val operationBusy by vm.operationBusy.collectAsStateWithLifecycle()
        val savedModels by vm.savedModels.collectAsStateWithLifecycle()
        val modelChoices by vm.modelChoices.collectAsStateWithLifecycle()
        val connectionFeedback by vm.connectionFeedback.collectAsStateWithLifecycle()
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var editCharacter by remember { mutableStateOf<CharacterCard?>(null) }
        var editConnection by remember { mutableStateOf<ConnectionProfile?>(null) }
        var deleting by remember { mutableStateOf<ChatSession?>(null) }
        var exportTarget by rememberSaveable { mutableStateOf<String?>(null) }
        var preview by remember { mutableStateOf<String?>(null) }
        var editingAsset by remember { mutableStateOf<ResourceAsset?>(null) }
        var deletingAsset by remember { mutableStateOf<ResourceAsset?>(null) }
        var exportAsset by remember { mutableStateOf<ResourceAsset?>(null) }
        var configTarget by remember { mutableStateOf<ChatSession?>(null) }
        var editingDefaults by remember { mutableStateOf(false) }
        var greetingCard by remember { mutableStateOf<CharacterCard?>(null) }
        var exportCard by remember {mutableStateOf<CharacterCard?>(null)}
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val jsonExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) vm.exportTo(uri, exportTarget?.takeIf { it != "all" }, false)
        }
        val textExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) vm.exportTo(uri, exportTarget, true)
        }
        val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) vm.previewImport(uri)
        }
        val assetExporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if(uri!=null) exportAsset?.let {vm.exportAsset(uri,it)}
        }
        val cardExporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {uri->
            if(uri!=null) exportCard?.let {vm.exportCharacter(uri,it)}
        }
        fun importFile() { importer.launch(arrayOf("application/json","text/plain","application/octet-stream","image/png")) }
        fun exportJson(id: String?) {
            exportTarget = id ?: "all"
            jsonExporter.launch(if (id == null) "roleplay-backup.json" else "roleplay-chat.json")
        }
        fun exportText(id: String) { exportTarget = id; textExporter.launch("roleplay-chat.txt") }
        LaunchedEffect(notice) { notice?.let { vm.clearNotice(); snackbar.showSnackbar(it) } }
        fun returnToSessions() { tab = 0; vm.backToList() }
        BackHandler(enabled = selectedId != null) { returnToSessions() }
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
            if (selectedId == null) NavigationBar {
                navigation.forEachIndexed { index, item ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index },
                        icon = { Icon(item.icon, item.title) }, label = { Text(item.title) })
                }
            }
        }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                val session = sessions.firstOrNull { it.id == selectedId }
                when {
                    !ready -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    selectedId != null && session == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    session != null -> ChatScreen(session, messages.filter { it.sessionId == session.id }, connections, resources, drafts[session.id] ?: session.draft, sending,
                        onBack = ::returnToSessions, onDraft = { vm.saveDraft(session.id, it) },
                        onSend = { vm.send(it) }, onStop = vm::stop,
                        onContinue = { vm.send(null, true) }, onConnection = { vm.bindConnection(session.id, it) },
                        onExportJson = { exportJson(session.id) }, onExportText = { exportText(session.id) },
                        onConfig = {configTarget=session},
                        onFork = {messageId,edited,regenerate->vm.forkMessage(messageId,edited,regenerate)},onAnswer = {vm.send(null,answerExisting=true)},
                        savedModels = savedModels, fetchedModels = modelChoices, modelFeedback = connectionFeedback, modelBusy = operationBusy,
                        onFetchModels = { vm.modelChoices.value = savedModels[it.id].orEmpty(); vm.fetchModels(it, "") },
                        onModel = { vm.selectModel(session.id, it) },
                        onPreview = { scope.launch { runCatching { vm.promptPreview(session.id) }.onSuccess { preview = it }.onFailure {vm.notice.value="上下文无法组装，请检查会话预算与资料"} } })
                    tab == 0 -> SessionScreen(sessions, vm::openChat, { deleting = it }, { tab = 1 },
                        { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) })
                    tab == 1 -> CharacterScreen(cards, { editCharacter = CharacterCard(name = "") }, ::importFile,
                        {exportCard=it;cardExporter.launch("roleplay-character.json")},
                        { editCharacter = it }, {card ->
                            if(runCatching {appJson.decodeFromString<List<String>>(card.alternateGreetings).isNotEmpty()}.getOrDefault(false)) greetingCard=card else vm.newChat(card)
                        })
                    tab == 2 -> ResourceScreen(resources,operationBusy,::importFile,{kind ->
                        editingAsset=ResourceAsset(kind=kind,name="",payload=when(kind) {"lorebook"->appJson.encodeToString(LoreBook());"preset"->appJson.encodeToString(StylePreset());else->appJson.encodeToString(RegexRules())})
                    },{editingAsset=it},vm::toggleResource,{deletingAsset=it},{exportAsset=it;assetExporter.launch("roleplay-${it.kind}.json")},
                        {exportJson(null)},{editingDefaults=true})
                    else -> SettingsScreen(connections, dark, { vm.setDark(it) },
                        { editConnection = ProviderTemplates.all.first().copy(id = newId()) }, { editConnection = it },
                        { exportJson(null) }, { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) })
                }
            }
        }
        editCharacter?.let { CharacterEditor(it, resources, { editCharacter = null }) { card ->
            vm.saveCharacter(card); editCharacter = null
        } }
        editConnection?.let { ConnectionEditor(it, vm, { editConnection = null }) }
        editingAsset?.let {asset->ResourceEditor(asset,{editingAsset=null}) {vm.saveResource(it) {editingAsset=null}}}
        deletingAsset?.let {asset->AlertDialog(onDismissRequest={deletingAsset=null},title={Text("删除资料？")},
            text={Text("将删除“${asset.name}”，并解除它与角色及会话的绑定。已有聊天文字会保留。")},
            confirmButton={TextButton(onClick={vm.deleteResource(asset.id);deletingAsset=null}) {Text("删除")}},
            dismissButton={TextButton(onClick={deletingAsset=null}) {Text("取消")}})}
        configTarget?.let {session->ConfigEditor(readConfig(session.config),resources,"会话设定",{configTarget=null}) {vm.saveConfig(session.id,it) {configTarget=null}}}
        if(editingDefaults) ConfigEditor(defaults,resources,"新会话默认设定",{editingDefaults=false}) {vm.saveConfig(null,it) {editingDefaults=false}}
        greetingCard?.let {card->
            AlertDialog(onDismissRequest={greetingCard=null},title={Text("选择开场白")},text={Column(Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState())) {
                (listOf(card.greeting)+appJson.decodeFromString<List<String>>(card.alternateGreetings)).forEachIndexed {index,text->
                    TextButton(onClick={vm.newChat(card,text);greetingCard=null}) {Text("${index+1}. ${text.take(180)}")}
                }
            }},confirmButton={TextButton(onClick={greetingCard=null}) {Text("取消")}})
        }
        pending?.let { bundle ->
            AlertDialog(onDismissRequest = { vm.pendingImport.value = null }, title = { Text("导入预览") },
                text = { Column(Modifier.heightIn(max=450.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text("${bundle.characterCount} 个角色、${bundle.resourceCount} 份资料、${bundle.sessionCount} 个会话、${bundle.messageCount} 条消息。\n\n合并导入，不覆盖当前记录；重复导入会新增副本。")
                    if(bundle.backup?.connections?.isNotEmpty()==true) Text("连接密钥需要重新填写。")
                    bundle.warnings.forEach {Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.tertiary)}
                } },
                confirmButton = { TextButton(onClick = { vm.confirmImport() }, enabled = !sending && !operationBusy) { Text("导入副本") } },
                dismissButton = { TextButton(onClick = { vm.pendingImport.value = null }) { Text("取消") } })
        }
        deleting?.let { session ->
            AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除会话？") },
                text = { Text("“${session.title}”的本地聊天记录将被删除。需要保留时，请先导出备份。") },
                confirmButton = { TextButton(onClick = { vm.deleteChat(session.id); deleting = null }) { Text("删除") } },
                dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
        }
        preview?.let { text ->
            AlertDialog(onDismissRequest = { preview = null }, title = { Text("当前聊天上下文") },
                text = { SelectionContainer { Text(text, Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) } },
                confirmButton = { TextButton(onClick = { preview = null }) { Text("关闭") } })
        }
    }
}

@Composable
private fun PageHeading(title: String, subtitle: String, action: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp))
        }
        action?.invoke()
    }
}

@Composable
private fun Avatar(name: String,avatar: String="") {
    val bitmap=remember(avatar) { runCatching {
        if(avatar.isBlank() || avatar.length>4*1024*1024) null else {
            val bytes=android.util.Base64.decode(avatar,android.util.Base64.DEFAULT)
            val bounds=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true}
            android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            var sample=1
            while(bounds.outWidth/sample>128 || bounds.outHeight/sample>128) sample*=2
            android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,android.graphics.BitmapFactory.Options().apply {inSampleSize=sample})?.asImageBitmap()
        }
    }.getOrNull() }
    if(bitmap!=null) {Image(bitmap,contentDescription=name,modifier=Modifier.size(48.dp).clip(CircleShape),contentScale=ContentScale.Crop);return}
    Box(Modifier.size(48.dp).background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.secondaryContainer)), CircleShape), contentAlignment = Alignment.Center) {
        Text(name.take(1).ifBlank { "角" }, fontSize = 22.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SessionScreen(sessions: List<ChatSession>, onOpen: (String) -> Unit, onDelete: (ChatSession) -> Unit,
    onNew: () -> Unit, onImport: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { PageHeading("会话", "故事保存在这里，随时接着聊") {
            FilledTonalIconButton(onClick = onNew) { Icon(Icons.Outlined.Add, "新建会话") }
        } }
        if (sessions.isEmpty()) item {
            EmptyPanel("开始你的第一个故事", "从角色页选择角色，也可以导入之前导出的记录。", onNew, "选择角色")
            TextButton(onClick = onImport) { Text("导入聊天记录") }
        }
        items(sessions, key = { it.id }) { session ->
            ElevatedCard(onClick = { onOpen(session.id) }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(session.title)
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(session.title, fontWeight = FontWeight.SemiBold)
                        Text(SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(session.updatedAt)),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (session.draft.isNotEmpty()) Text("有未发送的草稿", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { onDelete(session) }) { Icon(Icons.Outlined.DeleteOutline, "删除会话") }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun CharacterScreen(cards: List<CharacterCard>, onNew: () -> Unit, onImport: ()->Unit, onExport: (CharacterCard)->Unit, onEdit: (CharacterCard) -> Unit,
    onChat: (CharacterCard) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    val filtered = remember(cards, search) { searchCharacters(cards, search) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp).testTag("characters"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageHeading("角色", "选择一位角色，开启新的故事") {
            FilledTonalIconButton(onClick = onNew) { Icon(Icons.Outlined.Add, "新建角色") }
        } }
        item { TextButton(onClick=onImport) {Text("导入酒馆角色卡（PNG / JSON）")} }
        item {
            OutlinedTextField(search, { search = it }, modifier = Modifier.fillMaxWidth().testTag("character-search"),
                label = { Text("搜索角色名称、描述、性格或场景") }, singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null) }, trailingIcon = {
                    if (search.isNotEmpty()) IconButton(onClick = { search = "" }) { Icon(Icons.Outlined.Close, "清空搜索") }
                })
            if (search.isNotBlank()) Text("找到 ${filtered.size} / ${cards.size} 个角色", style = MaterialTheme.typography.bodySmall)
        }
        if (filtered.isEmpty() && search.isNotBlank()) item { Text("没有匹配的角色，试试其他关键词。") }
        items(filtered, key = { it.id }) { card ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(card.name,card.avatar)
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            Text(card.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Text(card.personality.ifBlank { "自定义角色" }, maxLines = 2,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { onEdit(card) }) { Icon(Icons.Outlined.Edit, "编辑角色") }
                    }
                    if (card.scenario.isNotBlank()) Text(card.scenario, Modifier.padding(vertical = 16.dp), maxLines = 3,
                        style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { onChat(card) }, modifier = Modifier.fillMaxWidth().semantics {contentDescription="开始与${card.name}的新会话"}) { Text("开始新会话") }
                    TextButton(onClick={onExport(card)}) {Text("导出角色与关联资料")}
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun EmptyPanel(title: String, detail: String, action: (() -> Unit)? = null, label: String = "") {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (action != null) Button(onClick = action) { Text(label) }
        }
    }
}

@Composable
private fun SettingsScreen(connections: List<ConnectionProfile>, dark: Boolean, onDark: (Boolean) -> Unit,
    onNew: () -> Unit, onEdit: (ConnectionProfile) -> Unit, onBackup: () -> Unit, onImport: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeading("设置", "连接模型，调整你的使用方式") }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("API 连接", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onNew) { Text("添加连接") }
            }
        }
        if (connections.isEmpty()) item { EmptyPanel("添加你的第一个 API", "支持 DeepSeek、OpenAI、Claude、Grok 及自定义兼容服务。", onNew, "添加连接") }
        items(connections, key = { it.id }) { profile ->
            OutlinedCard(onClick = { onEdit(profile) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(profile.name, fontWeight = FontWeight.SemiBold)
                    Text("${profile.provider} · ${profile.model}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("深色模式", Modifier.weight(1f)); Switch(checked = dark, onCheckedChange = onDark)
            }
            HorizontalDivider()
            TextButton(onClick = onBackup) { Text("导出完整备份") }
            TextButton(onClick = onImport) { Text("导入备份 / 聊天记录") }
            Text("Role Play 0.3.0 · 测试版\n聊天保存在设备本地。生成时，选中的聊天上下文会发送给你配置的 API 服务商。",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatScreen(session: ChatSession, messages: List<ChatMessage>, connections: List<ConnectionProfile>,
    resources: List<ResourceAsset>, draft: String, sending: Boolean, onBack: () -> Unit, onDraft: (String) -> Unit, onSend: (String) -> Unit,
    onStop: () -> Unit, onContinue: () -> Unit, onConnection: (String) -> Unit,
    onExportJson: () -> Unit, onExportText: () -> Unit, onConfig: ()->Unit, onPreview: () -> Unit,
    onFork: (String,String?,Boolean)->Unit, onAnswer: ()->Unit,
    savedModels: Map<String,List<String>>, fetchedModels: List<String>, modelFeedback: String?, modelBusy: Boolean,
    onFetchModels: (ConnectionProfile)->Unit, onModel: (String)->Unit) {
    var menu by remember { mutableStateOf(false) }
    var choosingConnection by remember { mutableStateOf(false) }
    var choosingModel by remember(session.id) { mutableStateOf(false) }
    var refreshedModels by remember { mutableStateOf(false) }
    var selectedMessage by remember(session.id) {mutableStateOf<ChatMessage?>(null)}
    var editingMessage by remember(session.id) {mutableStateOf<ChatMessage?>(null)}
    var editedText by remember {mutableStateOf("")}
    val clipboard=androidx.compose.ui.platform.LocalClipboardManager.current
    val scroll = remember(session.id) { androidx.compose.foundation.lazy.LazyListState() }
    var followLatest by rememberSaveable(session.id) { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val currentCount by rememberUpdatedState(messages.size)
    val readerScroll = remember(session.id) { object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && available.y > 0f) followLatest = false
            return Offset.Zero
        }
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && consumed.y < 0f && !scroll.canScrollForward) followLatest = true
            return Offset.Zero
        }
    } }
    val config=remember(session.config) {readConfig(session.config)}
    val userName=playerIdentity(session,config)
    val activeConnection=connections.firstOrNull {it.id==session.connectionId}
    val activeModel=activeConnection?.let {sessionProfile(it,config).model}.orEmpty()
    val characterName=remember(session.characterSnapshot) {appJson.decodeFromString<CharacterCard>(session.characterSnapshot).name}
    val rules=remember(config,resources) {PromptBuilder.rules(config,resources)}
    LaunchedEffect(session.id, messages.size, messages.lastOrNull()?.content, followLatest) {
        // Layout snapshots may resume this effect while the lazy list is still being measured.
        withFrameNanos { }; kotlinx.coroutines.yield()
        if (messages.isNotEmpty() && followLatest && !scroll.isScrollInProgress) scroll.requestScrollToItem(messages.size)
    }
    LaunchedEffect(session.id) {
        snapshotFlow { followLatest && !scroll.isScrollInProgress && scroll.canScrollForward }
            .distinctUntilChanged().collect { shouldFollow -> if (shouldFollow) {
                withFrameNanos { }; kotlinx.coroutines.yield()
                if (followLatest && !scroll.isScrollInProgress && scroll.canScrollForward) scroll.requestScrollToItem(currentCount)
            } }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(title = {
            Column {
                Text(session.title, maxLines = 1)
                Text(activeConnection?.let { "${it.name} · $activeModel" } ?: "尚未选择 API",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
            actions = {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "会话菜单") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("选择 API 连接") }, onClick = { menu = false; choosingConnection = true }, enabled = !sending)
                        DropdownMenuItem(text = { Text("切换当前会话模型") }, onClick = { menu = false; refreshedModels = false; choosingModel = true }, enabled = !sending && activeConnection != null)
                        DropdownMenuItem(text = { Text("会话设定：资料与玩家身份") }, onClick = {menu=false;onConfig()},enabled=!sending)
                        DropdownMenuItem(text = { Text("导出可恢复记录（JSON）") }, onClick = { menu = false; onExportJson() })
                        DropdownMenuItem(text = { Text("导出阅读文本（TXT）") }, onClick = { menu = false; onExportText() })
                        DropdownMenuItem(text = { Text("查看聊天上下文") }, onClick = { menu = false; onPreview() })
                    }
                }
            })
        Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().nestedScroll(readerScroll).testTag("chat-messages"),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            items(messages, key = { it.id }) { message ->
                val user = message.role == "user"
                val depth=messages.lastIndex-messages.indexOf(message)
                val shown by produceState(initialValue="",message.content,rules,depth,userName) {
                    value=withContext(Dispatchers.Default) {RegexEngine.apply(message.content,rules,message.role,true,depth,characterName,userName).text}
                }
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(if (user) userName else session.title, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 5.dp, start = 4.dp, end = 4.dp))
                        IconButton(onClick={selectedMessage=message},modifier=Modifier.size(28.dp),enabled=!sending) {Icon(Icons.Outlined.MoreHoriz,"消息操作 ${message.position}",modifier=Modifier.size(16.dp))}
                    }
                    Surface(shape = RoundedCornerShape(18.dp), color = if (user) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.widthIn(max = 680.dp)) {
                        MessageContent(shown.ifEmpty { if (message.status == "streaming") "正在回应…" else if(message.content.isNotEmpty()) "（内容已被显示规则隐藏）" else "未收到文字" }, message.id)
                    }
                    if (message.status != "complete") Text(when (message.status) {
                        "streaming" -> "生成中 · 已收到的文字会自动保存"
                        "interrupted" -> "生成已中断 · 已保留收到的内容"
                        else -> "生成失败 · 可重新发送或续写"
                    }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 5.dp))
                    if(message.inputTokens!=null || message.outputTokens!=null) Text("用量 ${message.inputTokens ?: "—"} / ${message.outputTokens ?: "—"} tokens" +
                        (message.cachedTokens?.let {" · 缓存命中 $it"} ?: ""),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item(key = "latest-edge") { Spacer(Modifier.height(1.dp)) }
        }
        if (!followLatest) FilledTonalButton(onClick = {
            followLatest = true; scope.launch { scroll.scrollToItem(messages.size) }
        }, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).testTag("back-to-latest")) { Text("回到最新") }
        }
        if (!sending && messages.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.End) {
            if(messages.last().role=="user") TextButton(onClick=onAnswer) {Text("生成回复")}
            TextButton(onClick = onContinue) { Text("续写") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(value = draft, onValueChange = onDraft, enabled = !sending, modifier = Modifier.weight(1f),
                placeholder = { Text("说些什么，或描述你的行动…") }, maxLines = 6, shape = RoundedCornerShape(24.dp))
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = { if (sending) onStop() else { followLatest = true; onSend(draft) } },
                enabled = sending || draft.isNotBlank(), modifier = Modifier.size(52.dp)) {
                Icon(if (sending) Icons.Outlined.Stop else Icons.AutoMirrored.Outlined.Send, if (sending) "停止生成" else "发送")
            }
        }
    }
    selectedMessage?.let {message->
        val canRegenerate=message.role=="assistant" && messages.indexOf(message)>0 && messages[messages.indexOf(message)-1].role=="user"
        AlertDialog(onDismissRequest={selectedMessage=null},title={Text("消息操作")},text={Column {
            TextButton(onClick={clipboard.setText(androidx.compose.ui.text.AnnotatedString(message.content));selectedMessage=null}) {Text("复制原文")}
            TextButton(onClick={editingMessage=message;editedText=message.content;selectedMessage=null}) {Text("编辑并新建分支")}
            TextButton(onClick={onFork(message.id,null,false);selectedMessage=null}) {Text("从此处开新分支")}
            if(canRegenerate) TextButton(onClick={onFork(message.id,null,true);selectedMessage=null}) {Text("从此处重新生成")}
            Text("分支保留原会话。重新生成会创建新分支并调用当前 API，产生用量。",style=MaterialTheme.typography.bodySmall)
        }},confirmButton={TextButton(onClick={selectedMessage=null}) {Text("关闭")}})
    }
    editingMessage?.let {message->AlertDialog(onDismissRequest={editingMessage=null},title={Text("编辑消息并分支")},text={Column {
        Field("消息原文",editedText,{editedText=it})
        Text("新分支保留到这条消息为止；原会话及后续回复仍保留。不会自动请求模型。",style=MaterialTheme.typography.bodySmall)
    }},confirmButton={TextButton(onClick={onFork(message.id,editedText,false);editingMessage=null},enabled=editedText.isNotBlank()) {Text("保存分支")}},dismissButton={TextButton(onClick={editingMessage=null}) {Text("取消")}})}
    if (choosingConnection) AlertDialog(onDismissRequest = { choosingConnection = false }, title = { Text("选择 API 连接") },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                if (connections.isEmpty()) Text("请先返回设置页添加 API 连接。")
                connections.forEach { profile ->
                    TextButton(onClick = { onConnection(profile.id); choosingConnection = false }) {
                        Text("${profile.name} · ${profile.model}")
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { choosingConnection = false }) { Text("关闭") } })
    if (choosingModel && activeConnection != null) ModelPicker(activeModel,
        (if (refreshedModels) fetchedModels else savedModels[activeConnection.id].orEmpty()) + listOf(activeConnection.model) +
            (if(activeConnection.provider=="DeepSeek") listOf("deepseek-flash","deepseek-v4-pro") else emptyList()),
        modelBusy, if (refreshedModels) modelFeedback else null,
        { refreshedModels = true; onFetchModels(activeConnection) }, { onModel(it); choosingModel = false }, { choosingModel = false })
}

@Composable
private fun CharacterEditor(card: CharacterCard, resources: List<ResourceAsset>, onDismiss: () -> Unit, onSave: (CharacterCard) -> Unit) {
    var name by remember(card.id) { mutableStateOf(card.name) }
    var description by remember(card.id) { mutableStateOf(card.description) }
    var personality by remember(card.id) { mutableStateOf(card.personality) }
    var scenario by remember(card.id) { mutableStateOf(card.scenario) }
    var greeting by remember(card.id) { mutableStateOf(card.greeting) }
    var examples by remember(card.id) { mutableStateOf(card.exampleDialogue) }
    var config by remember(card.id) {mutableStateOf(readConfig(card.config))}
    var bindings by remember {mutableStateOf(false)}
    var alternates by remember(card.id) {mutableStateOf(appJson.decodeFromString<List<String>>(card.alternateGreetings).joinToString("\n---\n"))}
    AlertDialog(onDismissRequest = onDismiss, title = { Text("角色设定") }, text = {
        Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            EditorField("角色名称", name, { name = it }, true)
            EditorField("描述与背景", description, { description = it })
            EditorField("性格", personality, { personality = it })
            EditorField("场景", scenario, { scenario = it })
            EditorField("开场白", greeting, { greeting = it })
            EditorField("对话风格示例", examples, { examples = it })
            EditorField("备选开场白（用单独一行 --- 分隔）",alternates,{alternates=it})
            TextButton(onClick={bindings=true}) {Text("绑定世界书、预设与正则（${config.references().size} 份）")}
            Text("已有会话保留原设定。修改后的角色用于新会话。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
        onSave(card.copy(name = name, description = description, personality = personality, scenario = scenario,
            greeting = greeting, exampleDialogue = examples,config=config.json(),alternateGreetings=appJson.encodeToString(alternates.split("\n---\n").map {it.trim()}.filter(String::isNotBlank))))
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
    if(bindings) ConfigEditor(config,resources,"角色绑定（用于新会话）",{bindings=false}) {config=it;bindings=false}
}

@Composable
private fun EditorField(label: String, value: String, onChange: (String) -> Unit, single: Boolean = false) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) },
        modifier = Modifier.fillMaxWidth(), singleLine = single, minLines = if (single) 1 else 2, maxLines = if (single) 1 else 8)
}

@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ConnectionEditor(initial: ConnectionProfile, vm: ChatViewModel, onDismiss: () -> Unit) {
    var provider by remember(initial.id) { mutableStateOf(initial.provider) }
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var url by remember(initial.id) { mutableStateOf(initial.baseUrl) }
    var protocol by remember(initial.id) { mutableStateOf(initial.protocol) }
    var model by remember(initial.id) { mutableStateOf(initial.model) }
    var key by remember(initial.id) { mutableStateOf("") }
    var output by remember(initial.id) { mutableStateOf(initial.maxOutputTokens.toString()) }
    var version by remember(initial.id) { mutableStateOf(initial.versionHeader) }
    var advanced by remember { mutableStateOf(false) }
    var costConfirmation by remember { mutableStateOf(false) }
    var modelPicker by remember { mutableStateOf(false) }
    val choices by vm.modelChoices.collectAsStateWithLifecycle()
    val feedback by vm.connectionFeedback.collectAsStateWithLifecycle()
    val busy by vm.operationBusy.collectAsStateWithLifecycle()
    val feedbackView = remember { BringIntoViewRequester() }
    LaunchedEffect(feedback) {
        if (feedback != null) {
            withFrameNanos { }
            kotlinx.coroutines.yield()
            feedbackView.bringIntoView()
        }
    }
    fun profile() = initial.copy(name = name, provider = provider, baseUrl = url, protocol = protocol,
        model = model, maxOutputTokens = output.toIntOrNull() ?: 0, versionHeader = version)
    LaunchedEffect(initial.id) { vm.modelChoices.value = vm.savedModels.value[initial.id].orEmpty(); vm.connectionFeedback.value = null }
    LaunchedEffect(choices) { if (model.isBlank() && choices.isNotEmpty()) model = choices.first() }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("API 连接") }, text = {
        val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
        val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        fun finishTyping() { focusManager.clearFocus(); keyboard?.hide() }
        Column(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f,fill=false).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ProviderTemplates.all.forEach { template -> FilterChip(selected = provider == template.provider, enabled = !busy,
                    onClick = {
                        provider = template.provider; name = template.name; url = template.baseUrl
                        protocol = template.protocol; model = template.model; key = ""; vm.modelChoices.value = emptyList()
                        vm.connectionFeedback.value = null
                    }, label = { Text(template.provider) }) }
            }
            EditorField("连接名称", name, { name = it }, true)
            OutlinedTextField(value = key, onValueChange = { key = it; vm.modelChoices.value = emptyList() }, label = { Text("API 密钥") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("编辑已有连接时，留空保留原密钥。密钥不会写入备份。") })
            EditorField("服务地址（Base URL）", url, { url = it; vm.modelChoices.value = emptyList(); vm.connectionFeedback.value = null }, true)
            EditorField("模型 ID", model, { model = it }, true)
            TextButton(enabled = !busy, onClick = { finishTyping(); vm.fetchModels(profile(), key) }) { Text(if (busy) "正在连接…" else "获取模型列表") }
            feedback?.let { Text(it, modifier=Modifier.fillMaxWidth().bringIntoViewRequester(feedbackView),
                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
            Text("模型列表不可用时也能先保存连接，再手动填写模型 ID。", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick={finishTyping();modelPicker=true},enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text("选择模型（${choices.size} 个已获取）")}
            if(provider=="DeepSeek") {
                Text("官方常用模型（仍需账户权限）",style=MaterialTheme.typography.labelSmall)
                FlowRow { listOf("deepseek-flash", "deepseek-v4-pro").forEach { id ->
                    FilterChip(selected=model==id,onClick={model=id},label={Text(id)})
                } }
            }
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级选项" else "高级选项") }
            if (advanced) {
                Text("API 协议", style = MaterialTheme.typography.labelLarge)
                Protocol.entries.forEach { choice ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = protocol == choice, onClick = { protocol = choice; vm.modelChoices.value = emptyList() })
                        Text(when (choice) { Protocol.CHAT_COMPLETIONS -> "Chat Completions"; Protocol.RESPONSES -> "Responses"; Protocol.ANTHROPIC_MESSAGES -> "Claude Messages" })
                    }
                }
                EditorField("最大输出 token 数", output, { output = it.filter(Char::isDigit) }, true)
                if (protocol == Protocol.ANTHROPIC_MESSAGES) EditorField("API 版本头", version, { version = it }, true)
            }
            OutlinedButton(onClick = { finishTyping(); costConfirmation = true }, enabled = !busy && model.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text("测试生成连接")
            }
            }
        }
    }, confirmButton = {
        val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
        val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        TextButton(enabled = !busy && name.isNotBlank() && url.isNotBlank(), onClick = {
        focusManager.clearFocus(); keyboard?.hide(); vm.saveConnection(profile(), key, onDismiss)
    }) { Text("保存") } }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } })
    if (costConfirmation) AlertDialog(onDismissRequest = { costConfirmation = false }, title = { Text("测试连接") },
        text = { Text("将向所选 API 发送一条简短测试消息，会产生少量 API 用量。") },
        confirmButton = { TextButton(onClick = { costConfirmation = false; vm.testConnection(profile(), key) }) { Text("测试") } },
        dismissButton = { TextButton(onClick = { costConfirmation = false }) { Text("取消") } })
    if(modelPicker) ModelPicker(model,choices,busy,feedback,{vm.fetchModels(profile(),key)},
        {model=it;modelPicker=false},{modelPicker=false})
}

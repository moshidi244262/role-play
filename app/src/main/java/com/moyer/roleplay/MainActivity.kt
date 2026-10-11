package com.moyer.roleplay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moyer.roleplay.data.*
import kotlinx.coroutines.launch
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
        val selectedId by vm.selectedId.collectAsStateWithLifecycle()
        val messages by vm.messages.collectAsStateWithLifecycle()
        val sending by vm.sending.collectAsStateWithLifecycle()
        val drafts by vm.drafts.collectAsStateWithLifecycle()
        val notice by vm.notice.collectAsStateWithLifecycle()
        val pending by vm.pendingImport.collectAsStateWithLifecycle()
        val operationBusy by vm.operationBusy.collectAsStateWithLifecycle()
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var editCharacter by remember { mutableStateOf<CharacterCard?>(null) }
        var editConnection by remember { mutableStateOf<ConnectionProfile?>(null) }
        var deleting by remember { mutableStateOf<ChatSession?>(null) }
        var exportTarget by rememberSaveable { mutableStateOf<String?>(null) }
        var preview by remember { mutableStateOf<String?>(null) }
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
        fun exportJson(id: String?) {
            exportTarget = id ?: "all"
            jsonExporter.launch(if (id == null) "roleplay-backup.json" else "roleplay-chat.json")
        }
        fun exportText(id: String) { exportTarget = id; textExporter.launch("roleplay-chat.txt") }
        LaunchedEffect(notice) { notice?.let { vm.clearNotice(); snackbar.showSnackbar(it) } }
        BackHandler(enabled = selectedId != null) { vm.backToList() }
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
                    session != null -> ChatScreen(session, messages.filter { it.sessionId == session.id }, connections, drafts[session.id] ?: session.draft, sending,
                        onBack = vm::backToList, onDraft = { vm.saveDraft(session.id, it) },
                        onSend = { vm.send(it) }, onStop = vm::stop,
                        onContinue = { vm.send(null, true) }, onConnection = { vm.bindConnection(session.id, it) },
                        onExportJson = { exportJson(session.id) }, onExportText = { exportText(session.id) },
                        onPreview = { scope.launch { runCatching { vm.promptPreview(session.id) }.onSuccess { preview = it } } })
                    tab == 0 -> SessionScreen(sessions, vm::openChat, { deleting = it }, { tab = 1 },
                        { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) })
                    tab == 1 -> CharacterScreen(cards, { editCharacter = CharacterCard(name = "") },
                        { editCharacter = it }, { vm.newChat(it) })
                    tab == 2 -> LibraryScreen(cards.size, sessions.size, operationBusy,
                        { exportJson(null) }, { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) })
                    else -> SettingsScreen(connections, dark, { vm.setDark(it) },
                        { editConnection = ProviderTemplates.all.first().copy(id = newId()) }, { editConnection = it },
                        { exportJson(null) }, { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) })
                }
            }
        }
        editCharacter?.let { CharacterEditor(it, { editCharacter = null }) { card ->
            vm.saveCharacter(card); editCharacter = null
        } }
        editConnection?.let { ConnectionEditor(it, vm, { editConnection = null }) }
        pending?.let { bundle ->
            AlertDialog(onDismissRequest = { vm.pendingImport.value = null }, title = { Text("导入记录") },
                text = { Text("${bundle.characters.size} 个角色、${bundle.sessions.size} 个会话、${bundle.messages.size} 条消息。\n\n合并导入，不覆盖当前记录；重复导入会新增副本。连接密钥需要重新填写。") },
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
private fun Avatar(name: String) {
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
private fun CharacterScreen(cards: List<CharacterCard>, onNew: () -> Unit, onEdit: (CharacterCard) -> Unit,
    onChat: (CharacterCard) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageHeading("角色", "选择一位角色，开启新的故事") {
            FilledTonalIconButton(onClick = onNew) { Icon(Icons.Outlined.Add, "新建角色") }
        } }
        items(cards, key = { it.id }) { card ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(card.name)
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            Text(card.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Text(card.personality.ifBlank { "自定义角色" }, maxLines = 2,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { onEdit(card) }) { Icon(Icons.Outlined.Edit, "编辑角色") }
                    }
                    if (card.scenario.isNotBlank()) Text(card.scenario, Modifier.padding(vertical = 16.dp), maxLines = 3,
                        style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { onChat(card) }, modifier = Modifier.fillMaxWidth()) { Text("开始新会话") }
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
private fun LibraryScreen(cardCount: Int, sessionCount: Int, busy: Boolean, onBackup: () -> Unit, onImport: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeading("资料库", "把人物与故事好好保存")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("数据备份", style = MaterialTheme.typography.titleLarge)
                Text("$cardCount 个角色 · $sessionCount 个会话")
                Text("完整备份包括角色、聊天、草稿及连接配置，不包含 API 密钥。导出的文件请妥善保存。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onBackup, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("导出完整备份") }
                OutlinedButton(onClick = onImport, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("导入备份 / 聊天记录") }
            }
        }
        EmptyPanel("下一阶段：世界书、预设与正则", "资料编辑与酒馆格式导入将在下一阶段加入。当前可创建角色，并备份与恢复 Role Play 记录。")
        Spacer(Modifier.height(20.dp))
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
            Text("Role Play 0.1.0 · 测试版\n聊天保存在设备本地。生成时，选中的聊天上下文会发送给你配置的 API 服务商。",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(session: ChatSession, messages: List<ChatMessage>, connections: List<ConnectionProfile>,
    draft: String, sending: Boolean, onBack: () -> Unit, onDraft: (String) -> Unit, onSend: (String) -> Unit,
    onStop: () -> Unit, onContinue: () -> Unit, onConnection: (String) -> Unit,
    onExportJson: () -> Unit, onExportText: () -> Unit, onPreview: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var choosingConnection by remember { mutableStateOf(false) }
    val scroll = rememberLazyListState()
    val nearBottom by remember { derivedStateOf { !scroll.canScrollForward ||
        (scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= messages.lastIndex - 1 } }
    LaunchedEffect(messages.size, messages.lastOrNull()?.content) {
        if (messages.isNotEmpty() && nearBottom) scroll.scrollToItem(messages.lastIndex)
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(title = {
            Column {
                Text(session.title, maxLines = 1)
                Text(connections.firstOrNull { it.id == session.connectionId }?.name ?: "尚未选择 API",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
            actions = {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "会话菜单") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("选择 API 连接") }, onClick = { menu = false; choosingConnection = true }, enabled = !sending)
                        DropdownMenuItem(text = { Text("导出可恢复记录（JSON）") }, onClick = { menu = false; onExportJson() })
                        DropdownMenuItem(text = { Text("导出阅读文本（TXT）") }, onClick = { menu = false; onExportText() })
                        DropdownMenuItem(text = { Text("查看聊天上下文") }, onClick = { menu = false; onPreview() })
                    }
                }
            })
        LazyColumn(state = scroll, modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            items(messages, key = { it.id }) { message ->
                val user = message.role == "user"
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
                    Text(if (user) session.playerName else session.title, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 5.dp, start = 4.dp, end = 4.dp))
                    Surface(shape = RoundedCornerShape(18.dp), color = if (user) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.widthIn(max = 680.dp)) {
                        SelectionContainer { Text(message.content.ifEmpty { if (message.status == "streaming") "正在回应…" else "未收到文字" },
                            modifier = Modifier.padding(16.dp), lineHeight = 25.sp) }
                    }
                    if (message.status != "complete") Text(when (message.status) {
                        "streaming" -> "生成中 · 已收到的文字会自动保存"
                        "interrupted" -> "生成已中断 · 已保留收到的内容"
                        else -> "生成失败 · 可重新发送或续写"
                    }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 5.dp))
                }
            }
        }
        if (!sending && messages.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onContinue) { Text("续写") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(value = draft, onValueChange = onDraft, enabled = !sending, modifier = Modifier.weight(1f),
                placeholder = { Text("说些什么，或描述你的行动…") }, maxLines = 6, shape = RoundedCornerShape(24.dp))
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = { if (sending) onStop() else onSend(draft) },
                enabled = sending || draft.isNotBlank(), modifier = Modifier.size(52.dp)) {
                Icon(if (sending) Icons.Outlined.Stop else Icons.AutoMirrored.Outlined.Send, if (sending) "停止生成" else "发送")
            }
        }
    }
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
}

@Composable
private fun CharacterEditor(card: CharacterCard, onDismiss: () -> Unit, onSave: (CharacterCard) -> Unit) {
    var name by remember(card.id) { mutableStateOf(card.name) }
    var description by remember(card.id) { mutableStateOf(card.description) }
    var personality by remember(card.id) { mutableStateOf(card.personality) }
    var scenario by remember(card.id) { mutableStateOf(card.scenario) }
    var greeting by remember(card.id) { mutableStateOf(card.greeting) }
    var examples by remember(card.id) { mutableStateOf(card.exampleDialogue) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("角色设定") }, text = {
        Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            EditorField("角色名称", name, { name = it }, true)
            EditorField("描述与背景", description, { description = it })
            EditorField("性格", personality, { personality = it })
            EditorField("场景", scenario, { scenario = it })
            EditorField("开场白", greeting, { greeting = it })
            EditorField("对话风格示例", examples, { examples = it })
            Text("已有会话保留原设定。修改后的角色用于新会话。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
        onSave(card.copy(name = name, description = description, personality = personality, scenario = scenario,
            greeting = greeting, exampleDialogue = examples))
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun EditorField(label: String, value: String, onChange: (String) -> Unit, single: Boolean = false) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) },
        modifier = Modifier.fillMaxWidth(), singleLine = single, minLines = if (single) 1 else 2, maxLines = if (single) 1 else 8)
}

@OptIn(ExperimentalLayoutApi::class)
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
    val choices by vm.modelChoices.collectAsStateWithLifecycle()
    val busy by vm.operationBusy.collectAsStateWithLifecycle()
    fun profile() = initial.copy(name = name, provider = provider, baseUrl = url, protocol = protocol,
        model = model, maxOutputTokens = output.toIntOrNull() ?: 0, versionHeader = version)
    LaunchedEffect(initial.id) { vm.modelChoices.value = emptyList() }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("API 连接") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ProviderTemplates.all.forEach { template -> FilterChip(selected = provider == template.provider, enabled = !busy,
                    onClick = {
                        provider = template.provider; name = template.name; url = template.baseUrl
                        protocol = template.protocol; model = ""; key = ""; vm.modelChoices.value = emptyList()
                    }, label = { Text(template.provider) }) }
            }
            EditorField("连接名称", name, { name = it }, true)
            OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("API 密钥") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("编辑已有连接时，留空保留原密钥。密钥不会写入备份。") })
            EditorField("服务地址（Base URL）", url, { url = it }, true)
            EditorField("模型 ID", model, { model = it }, true)
            TextButton(enabled = !busy, onClick = { vm.fetchModels(profile(), key) }) { Text(if (busy) "正在连接…" else "获取模型列表") }
            if (choices.isNotEmpty()) Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                choices.forEach { id -> TextButton(onClick = { model = id }) { Text(id) } }
            }
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级选项" else "高级选项") }
            if (advanced) {
                Text("API 协议", style = MaterialTheme.typography.labelLarge)
                Protocol.entries.forEach { choice ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = protocol == choice, onClick = { protocol = choice })
                        Text(when (choice) { Protocol.CHAT_COMPLETIONS -> "Chat Completions"; Protocol.RESPONSES -> "Responses"; Protocol.ANTHROPIC_MESSAGES -> "Claude Messages" })
                    }
                }
                EditorField("最大输出 token 数", output, { output = it.filter(Char::isDigit) }, true)
                if (protocol == Protocol.ANTHROPIC_MESSAGES) EditorField("API 版本头", version, { version = it }, true)
            }
            OutlinedButton(onClick = { costConfirmation = true }, enabled = !busy && model.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text("测试生成连接")
            }
        }
    }, confirmButton = { TextButton(enabled = !busy && model.isNotBlank() && name.isNotBlank(), onClick = {
        vm.saveConnection(profile(), key, onDismiss)
    }) { Text("保存") } }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } })
    if (costConfirmation) AlertDialog(onDismissRequest = { costConfirmation = false }, title = { Text("测试连接") },
        text = { Text("将向所选 API 发送一条简短测试消息，会产生少量 API 用量。") },
        confirmButton = { TextButton(onClick = { costConfirmation = false; vm.testConnection(profile(), key) }) { Text("测试") } },
        dismissButton = { TextButton(onClick = { costConfirmation = false }) { Text("取消") } })
}

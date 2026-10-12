package com.moyer.roleplay

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import com.moyer.roleplay.core.*
import com.moyer.roleplay.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

fun resourceLabel(kind: String) = when(kind) { "lorebook"->"世界书"; "preset"->"预设"; else->"正则" }

@Composable
fun ResourceScreen(assets: List<ResourceAsset>, busy: Boolean, onImport: ()->Unit, onNew: (String)->Unit,
    onEdit: (ResourceAsset)->Unit, onToggle: (ResourceAsset)->Unit, onDelete: (ResourceAsset)->Unit,
    onExport: (ResourceAsset)->Unit, onBackup: ()->Unit, onDefaults: ()->Unit, onImportKind: ((String)->Unit)? = null) {
    var kind by remember { mutableStateOf("lorebook") }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(top=28.dp,bottom=8.dp)) {
                Text("资料库",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
                Text("按需要绑定到角色或会话",color=MaterialTheme.colorScheme.onSurfaceVariant)
                Row { TextButton(onClick=onImport,enabled=!busy) { Text("导入 JSON / PNG") }
                    TextButton(onClick={onNew(kind)}) { Text("新建${resourceLabel(kind)}") } }
                if(kind in setOf("preset","lorebook") && onImportKind != null) {
                    OutlinedButton(onClick={onImportKind(kind)},enabled=!busy,modifier=Modifier.fillMaxWidth()) {
                        Text("导入${resourceLabel(kind)}文件")
                    }
                }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf("lorebook","preset","regex").forEach { value ->
                        FilterChip(selected=kind==value,onClick={kind=value},label={Text(resourceLabel(value))})
                    }
                }
            }
        }
        items(assets.filter { it.kind==kind },key={it.id}) { asset ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(asset.name,Modifier.weight(1f),fontWeight=FontWeight.SemiBold)
                        Switch(checked=asset.enabled,onCheckedChange={onToggle(asset)})
                    }
                    val count=remember(asset.payload) { when(asset.kind) {
                        "lorebook"->appJson.decodeFromString<LoreBook>(asset.payload).entries.size
                        "preset"->appJson.decodeFromString<StylePreset>(asset.payload).blocks.size
                        else->appJson.decodeFromString<RegexRules>(asset.payload).rules.size
                    } }
                    Text("$count 条 · ${if(asset.enabled) "启用" else "已停用"}",style=MaterialTheme.typography.bodySmall)
                    if(asset.notes.isNotBlank()) Text("有兼容提示，请在编辑中查看",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.tertiary)
                    Row {
                        TextButton(onClick={onEdit(asset)}) { Text("编辑") }
                        TextButton(onClick={onExport(asset)}) { Text("导出") }
                        TextButton(onClick={onDelete(asset)}) { Text("删除") }
                    }
                }
            }
        }
        if(assets.none { it.kind==kind }) item { Text("还没有${resourceLabel(kind)}，可以新建或从文件导入。") }
        item {
            HorizontalDivider(Modifier.padding(top=12.dp))
            TextButton(onClick=onDefaults) { Text("新会话默认资料与玩家身份") }
            TextButton(onClick=onBackup,enabled=!busy) { Text("导出完整备份") }
            Text("停用资料会暂停它在所有会话中的作用。原始导入字段会保留，兼容范围可在编辑页查看。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
internal fun Field(label: String,value: String,onChange: (String)->Unit,single: Boolean=false) {
    OutlinedTextField(value=value,onValueChange=onChange,label={Text(label)},modifier=Modifier.fillMaxWidth(),
        singleLine=single,minLines=if(single) 1 else 2,maxLines=if(single) 1 else 8)
}

@Composable
private fun Check(label: String,checked: Boolean,onChange: (Boolean)->Unit) {
    Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(checked=checked,onCheckedChange=onChange); Text(label,Modifier.weight(1f)) }
}

@Composable
private fun Options(label: String,values: List<Pair<String,String>>,selected: String,onChange: (String)->Unit) {
    Text(label,style=MaterialTheme.typography.labelLarge)
    values.forEach { (id,name) -> Row(verticalAlignment=Alignment.CenterVertically) {
        RadioButton(selected=selected==id,onClick={onChange(id)}); Text(name)
    } }
}

private val positions=listOf("before_char" to "角色描述前","after_char" to "角色描述后","depth" to "聊天内指定深度")
private val lorePositions=positions+listOf("before_examples" to "示例对话前","after_examples" to "示例对话后","before_author" to "作者注释前","after_author" to "作者注释后")
private val roles=listOf("system" to "System","user" to "User","assistant" to "Assistant")
private fun keys(text: String)=text.split('\n',',','，').map { it.trim() }.filter(String::isNotBlank)

@Composable
fun ConfigEditor(initial: RoleplayConfig,assets: List<ResourceAsset>,title: String,onDismiss: ()->Unit,onSave: (RoleplayConfig)->Unit) {
    var config by remember { mutableStateOf(initial) }
    var advanced by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest=onDismiss,title={Text(title)},text={
        Column(Modifier.heightIn(max=500.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("世界书",style=MaterialTheme.typography.titleSmall)
            assets.filter { it.kind=="lorebook" }.forEach { asset ->
                Check(asset.name+(if(asset.enabled) "" else "（已停用）"),asset.id in config.lorebookIds) { checked ->
                    config=config.copy(lorebookIds=if(checked) config.lorebookIds+asset.id else config.lorebookIds-asset.id)
                }
            }
            Text("叙事预设",style=MaterialTheme.typography.titleSmall)
            Row(verticalAlignment=Alignment.CenterVertically) { RadioButton(selected=config.presetId==null,onClick={config=config.copy(presetId=null)}); Text("不使用") }
            assets.filter { it.kind=="preset" && it.id!=config.characterPromptId }.forEach { asset ->
                Row(verticalAlignment=Alignment.CenterVertically) {
                    RadioButton(selected=config.presetId==asset.id,onClick={config=config.copy(presetId=asset.id)}); Text(asset.name)
                }
            }
            config.characterPromptId?.let { id -> Text("角色附加提示：${assets.firstOrNull { it.id==id }?.name ?: "资料缺失"}。选择叙事预设后仍会保留。",style=MaterialTheme.typography.bodySmall) }
            Text("正则规则",style=MaterialTheme.typography.titleSmall)
            Text("按绑定顺序执行前 32 条启用规则。",style=MaterialTheme.typography.bodySmall)
            assets.filter { it.kind=="regex" }.forEach { asset ->
                Check(asset.name,asset.id in config.regexIds) { checked -> config=config.copy(regexIds=if(checked) config.regexIds+asset.id else config.regexIds-asset.id) }
            }
            Field("用户角色名称（留空用：我）",config.playerName,{config=config.copy(playerName=it)},true)
            Field("玩家身份说明",config.persona,{config=config.copy(persona=it)})
            Text("未填写姓名时，第一人称使用‘我’。具体人物姓名可在上方身份中填写。", style=MaterialTheme.typography.bodySmall)
            Options("用户角色的叙述视角", listOf("auto" to "按角色卡与情境", "first" to "第一人称（我）", "third" to "第三人称（人物姓名）"), config.narrativeView) { config=config.copy(narrativeView=it) }
            Field("固定剧情记忆（已发生的事件、关系与线索）",config.memory,{config=config.copy(memory=it)})
            Field("作者注释 / 当前剧情提醒",config.authorNote,{config=config.copy(authorNote=it)})
            TextButton(onClick={advanced=!advanced}) { Text(if(advanced) "收起高级选项" else "上下文与扫描选项") }
            if(advanced) {
                NumberField("作者注释深度（距最新消息）",config.authorDepth) {config=config.copy(authorDepth=it)}
                NumberField("世界书扫描最近消息数",config.scanDepth) {config=config.copy(scanDepth=it)}
                NumberField("世界书预算（估算 tokens）",config.loreBudget) {config=config.copy(loreBudget=it)}
                NumberField("上下文字符预算",config.historyBudgetChars) {config=config.copy(historyBudgetChars=it)}
                Text("预算为字符和 token 估算，会从最早的聊天记录开始裁剪；本地记录仍完整保留。",style=MaterialTheme.typography.bodySmall)
            }
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        }
    },confirmButton={TextButton(onClick={
        runCatching { config.validate() }.onSuccess {onSave(config)}.onFailure {error=it.message}
    }) {Text("保存")}},dismissButton={TextButton(onClick=onDismiss) {Text("取消")}})
}

@Composable
private fun NumberField(label: String,value: Int,onChange: (Int)->Unit) {
    var text by remember(label) { mutableStateOf(value.toString()) }
    // An empty, non-numeric or overflowing edit must fail validation, not save the old value silently.
    Field(label,text,{text=it; onChange(it.toIntOrNull() ?: -1)},true)
}

@Composable
fun ResourceEditor(asset: ResourceAsset,onDismiss: ()->Unit,onSave: (ResourceAsset)->Unit,onImport: (() -> Unit)? = null) {
    var name by remember(asset.id) { mutableStateOf(asset.name) }
    var lore by remember(asset.id) { mutableStateOf(if(asset.kind=="lorebook") appJson.decodeFromString<LoreBook>(asset.payload) else LoreBook()) }
    var preset by remember(asset.id) { mutableStateOf(if(asset.kind=="preset") appJson.decodeFromString<StylePreset>(asset.payload) else StylePreset()) }
    var regex by remember(asset.id) { mutableStateOf(if(asset.kind=="regex") appJson.decodeFromString<RegexRules>(asset.payload) else RegexRules()) }
    var entry by remember { mutableStateOf<LoreEntry?>(null) }
    var block by remember { mutableStateOf<PromptBlock?>(null) }
    var rule by remember { mutableStateOf<TextRule?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notes by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var enabledOnly by remember { mutableStateOf(false) }
    var showRules by remember { mutableStateOf(false) }
    fun matches(title: String,content: String,enabled: Boolean) = (!enabledOnly || enabled) &&
        (query.isBlank() || title.contains(query,true) || content.contains(query,true))
    AlertDialog(onDismissRequest=onDismiss,title={Text("编辑${resourceLabel(asset.kind)}")},text={
        LazyColumn(Modifier.heightIn(max=500.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            item {
                Field("名称",name,{name=it},true)
                if(onImport != null) OutlinedButton(onClick=onImport) {Text("导入${resourceLabel(asset.kind)}文件")}
                if(asset.notes.isNotBlank()) {
                    TextButton(onClick={notes=!notes}) {Text("导入兼容提示")}
                    if(notes) Text(asset.notes,style=MaterialTheme.typography.bodySmall)
                }
                Field("搜索条目或内容",query,{query=it},true)
                Check("只看启用的条目",enabledOnly) {enabledOnly=it}
            }
            when(asset.kind) {
                "lorebook" -> {
                    item {
                        TextButton(onClick={advanced=!advanced}) {Text("世界书扫描选项")}
                        if(advanced) {
                            Check("递归扫描（最多 4 轮）",lore.recursive) {lore=lore.copy(recursive=it)}
                            NumberField("默认扫描深度",lore.scanDepth) {lore=lore.copy(scanDepth=it)}
                            NumberField("本书预算（估算 tokens）",lore.budget) {lore=lore.copy(budget=it)}
                        }
                    }
                    items(lore.entries.filter {matches(it.name,it.content,it.enabled)},key={it.id}) {value ->
                        ItemControls(value.name,value.enabled,{entry=value},
                            {lore=lore.copy(entries=lore.entries.map {if(it.id==value.id) it.copy(enabled=!it.enabled) else it})},
                            {lore=lore.copy(entries=lore.entries.filter {it.id!=value.id})})
                    }
                    item {OutlinedButton(onClick={entry=LoreEntry()}) {Text("添加条目")}}
                }
                "preset" -> {
                    item {
                        Text(if(preset.ordered) "按照酒馆排列发送；占位块填入真实角色、世界书和聊天。空块用于分组。" else "按列表顺序插入；角色附加提示独立保留。",style=MaterialTheme.typography.bodySmall)
                        if(preset.regexRules.isNotEmpty()) TextButton(onClick={showRules=!showRules}) {Text(if(showRules) "查看提示块" else "内嵌正则（${preset.regexRules.size} 条）")}
                    }
                    if(showRules) {
                        items(preset.regexRules.filter {matches(it.name,it.pattern+it.replacement,it.enabled)},key={it.id}) {value ->
                            ItemControls(value.name,value.enabled,{rule=value},
                                {preset=preset.copy(regexRules=preset.regexRules.map {if(it.id==value.id) it.copy(enabled=!it.enabled) else it})},
                                {preset=preset.copy(regexRules=preset.regexRules.filter {it.id!=value.id})})
                        }
                    } else {
                        items(preset.blocks.filter {matches(it.name,it.content,it.enabled)},key={it.id}) {value ->
                            Column {
                                ItemControls(value.name+(if(value.marker.isNotBlank()) " · 占位块" else ""),value.enabled,{block=value},
                                    {preset=preset.copy(blocks=preset.blocks.map {if(it.id==value.id) it.copy(enabled=!it.enabled) else it})},
                                    {preset=preset.copy(blocks=preset.blocks.filter {it.id!=value.id})})
                                val index=preset.blocks.indexOfFirst { it.id==value.id }
                                if(index>0) TextButton(onClick={
                                    val list=preset.blocks.toMutableList();list.removeAt(index);list.add(index-1,value);preset=preset.copy(blocks=list)
                                }) {Text("上移")}
                            }
                        }
                        item {OutlinedButton(onClick={block=PromptBlock()},enabled=preset.blocks.size<1000) {Text("添加提示块")}}
                    }
                }
                else -> {
                    item {Text("原始聊天不会被改写。可保存 256 条；按绑定顺序执行前 32 条启用规则。",style=MaterialTheme.typography.bodySmall)}
                    items(regex.rules.filter {matches(it.name,it.pattern+it.replacement,it.enabled)},key={it.id}) {value ->
                        ItemControls(value.name,value.enabled,{rule=value},
                            {regex=regex.copy(rules=regex.rules.map {if(it.id==value.id) it.copy(enabled=!it.enabled) else it})},
                            {regex=regex.copy(rules=regex.rules.filter {it.id!=value.id})})
                    }
                    item {OutlinedButton(onClick={rule=TextRule()},enabled=regex.rules.size<256) {Text("添加规则")}}
                }
            }
            error?.let {item {Text(it,color=MaterialTheme.colorScheme.error)}}
        }
    },confirmButton={TextButton(onClick={
        val value=asset.copy(name=name,payload=when(asset.kind) {"lorebook"->appJson.encodeToString(lore);"preset"->appJson.encodeToString(preset);else->appJson.encodeToString(regex)})
        runCatching {value.validate()}.onSuccess {onSave(value)}.onFailure {error=it.message ?: "配置无效"}
    },enabled=name.isNotBlank()) {Text("保存")}},dismissButton={TextButton(onClick=onDismiss) {Text("取消")}})
    entry?.let {value -> LoreEntryEditor(value,{entry=null}) {changed ->
        lore=lore.copy(entries=if(lore.entries.any {it.id==changed.id}) lore.entries.map {if(it.id==changed.id) changed else it} else lore.entries+changed);entry=null
    }}
    block?.let {value -> BlockEditor(value,{block=null}) {changed ->
        preset=preset.copy(blocks=if(preset.blocks.any {it.id==changed.id}) preset.blocks.map {if(it.id==changed.id) changed else it} else preset.blocks+changed);block=null
    }}
    rule?.let {value -> RuleEditor(value,{rule=null}) {changed ->
        if(asset.kind=="preset") preset=preset.copy(regexRules=preset.regexRules.map {if(it.id==changed.id) changed else it})
        else regex=regex.copy(rules=if(regex.rules.any {it.id==changed.id}) regex.rules.map {if(it.id==changed.id) changed else it} else regex.rules+changed)
        rule=null
    }}
}

@Composable
private fun ItemControls(name: String,enabled: Boolean,onEdit: ()->Unit,onToggle: ()->Unit,onDelete: ()->Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(8.dp)) {
        Text(name,fontWeight=FontWeight.Medium)
        Row { TextButton(onClick=onEdit) {Text("编辑")}; TextButton(onClick=onToggle) {Text(if(enabled) "停用" else "启用")}; TextButton(onClick=onDelete) {Text("移除")} }
    } }
}

@Composable
private fun LoreEntryEditor(initial: LoreEntry,onDismiss: ()->Unit,onSave: (LoreEntry)->Unit) {
    var value by remember {mutableStateOf(initial)}
    var primary by remember {mutableStateOf(initial.keys.joinToString("\n"))}
    var secondary by remember {mutableStateOf(initial.secondaryKeys.joinToString("\n"))}
    var advanced by remember {mutableStateOf(false)}
    AlertDialog(onDismissRequest=onDismiss,title={Text("世界书条目")},text={
        Column(Modifier.heightIn(max=500.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Field("条目名称",value.name,{value=value.copy(name=it)},true)
            Check("常驻（不依赖关键词）",value.constant) {value=value.copy(constant=it)}
            Field("关键词（换行或逗号分隔）",primary,{primary=it})
            Field("背景内容",value.content,{value=value.copy(content=it)})
            TextButton(onClick={advanced=!advanced}) {Text("匹配与插入选项")}
            if(advanced) {
                Check("区分大小写",value.caseSensitive) {value=value.copy(caseSensitive=it)}
                Check("整词匹配（中文通常关闭）",value.matchWholeWords) {value=value.copy(matchWholeWords=it)}
                NumberField("触发概率（0 到 100）",value.probability) {value=value.copy(probability=it)}
                Check("扫描玩家身份",value.matchPersona) {value=value.copy(matchPersona=it)}
                Check("扫描角色描述",value.matchDescription) {value=value.copy(matchDescription=it)}
                Check("扫描角色性格",value.matchPersonality) {value=value.copy(matchPersonality=it)}
                Check("扫描场景",value.matchScenario) {value=value.copy(matchScenario=it)}
                Check("使用辅助关键词",value.selective) {value=value.copy(selective=it)}
                if(value.selective) {
                    Field("辅助关键词",secondary,{secondary=it})
                    Options("辅助关键词条件",listOf("and_any" to "命中任意一个","and_all" to "全部命中","not_any" to "全部未命中","not_all" to "至少一个未命中"),value.logic) {value=value.copy(logic=it)}
                }
                NumberField("插入顺序（较大值靠后）",value.order) {value=value.copy(order=it)}
                Options("插入位置",lorePositions,value.position) {value=value.copy(position=it)}
                if(value.position=="depth") {NumberField("深度",value.depth) {value=value.copy(depth=it)};Options("消息角色",roles,value.role) {value=value.copy(role=it)}}
                Check("不参与后续递归触发",value.preventRecursion) {value=value.copy(preventRecursion=it)}
                Check("不通过递归激活本条",value.excludeRecursion) {value=value.copy(excludeRecursion=it)}
            }
        }
    },confirmButton={TextButton(onClick={onSave(value.copy(keys=keys(primary),secondaryKeys=keys(secondary),depth=value.depth.coerceIn(0,100)))},enabled=value.name.isNotBlank()) {Text("完成")}},dismissButton={TextButton(onClick=onDismiss) {Text("取消")}})
}

@Composable
private fun BlockEditor(initial: PromptBlock,onDismiss: ()->Unit,onSave: (PromptBlock)->Unit) {
    var value by remember {mutableStateOf(initial)}
    AlertDialog(onDismissRequest=onDismiss,title={Text("提示块")},text={Column(Modifier.heightIn(max=500.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Field("名称",value.name,{value=value.copy(name=it)},true)
        if(value.marker.isBlank()) Field("提示内容",value.content,{value=value.copy(content=it)})
        else Text("占位块：${value.marker}，生成时自动填入实际资料。",style=MaterialTheme.typography.bodySmall)
        Options("插入位置",positions,value.position) {value=value.copy(position=it)}
        if(value.position=="depth") NumberField("距最新消息的深度",value.depth) {value=value.copy(depth=it)}
        Options("消息角色",roles,value.role) {value=value.copy(role=it)}
        if(value.position=="depth") NumberField("同深度插入顺序",value.order) {value=value.copy(order=it)}
        Text("触发方式（不勾选则每种方式都使用）",style=MaterialTheme.typography.labelLarge)
        listOf("normal" to "普通发送","continue" to "续写","regenerate" to "重生成","swipe" to "备选回复（重生成时使用）").forEach { (id,label) ->
            Check(label,id in value.triggers) {checked -> value=value.copy(triggers=if(checked) value.triggers+id else value.triggers-id)}
        }
        Text("可使用基础宏和 {{setvar::变量::文字}} / {{getvar::变量}}；详见设置 → 帮助。",style=MaterialTheme.typography.bodySmall)
    }},confirmButton={TextButton(onClick={onSave(value.copy(depth=value.depth.coerceIn(0,100)))}) {Text("完成")}},dismissButton={TextButton(onClick=onDismiss) {Text("取消")}})
}

@Composable
private fun RuleEditor(initial: TextRule,onDismiss: ()->Unit,onSave: (TextRule)->Unit) {
    var value by remember {mutableStateOf(initial)}
    var sample by remember {mutableStateOf("在这里输入用于测试的文字")}
    var result by remember {mutableStateOf(RuleResult("",emptyList()))}
    var error by remember {mutableStateOf<String?>(null)}
    var advanced by remember {mutableStateOf(false)}
    LaunchedEffect(value,sample) { result=withContext(Dispatchers.Default) {RegexEngine.apply(sample,listOf(value.copy(enabled=true,targets=listOf("assistant"),display=true)),"assistant",true,0,"角色","玩家")} }
    AlertDialog(onDismissRequest=onDismiss,title={Text("正则规则")},text={Column(Modifier.heightIn(max=500.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Field("名称",value.name,{value=value.copy(name=it)},true)
        Field("表达式，如 /旧称/g",value.pattern,{value=value.copy(pattern=it)})
        Field("替换文本（可使用 $1、{{match}}）",value.replacement,{value=value.copy(replacement=it)})
        listOf("user" to "玩家输入","assistant" to "模型回复","lore" to "世界书内容").forEach { (id,label) ->
            Check(label,id in value.targets) {checked->value=value.copy(targets=if(checked) value.targets+id else value.targets-id)}
        }
        Check("用于聊天显示",value.display) {value=value.copy(display=it)}
        Check("用于发送给模型的上下文",value.prompt) {value=value.copy(prompt=it)}
        TextButton(onClick={advanced=!advanced}) {Text("深度与宏选项")}
        if(advanced) {
            NumberField("最小深度",value.minDepth) {value=value.copy(minDepth=it)}
            NumberField("最大深度（-1 为不限）",value.maxDepth) {value=value.copy(maxDepth=it)}
            Options("查找中的角色 / 玩家宏",listOf("0" to "不替换","1" to "按原文替换","2" to "转义后替换"),value.substituteFind.toString()) {value=value.copy(substituteFind=it.toInt())}
            Field("从匹配结果移除的文字（每行一项）",value.trims.joinToString("\n"),{value=value.copy(trims=it.lines().filter(String::isNotEmpty))})
        }
        Field("测试文字",sample,{sample=it})
        Text("显示预览（深度 0）：\n${result.text}",style=MaterialTheme.typography.bodySmall)
        result.warnings.forEach {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        Text("使用 RE2 正则，支持 /…/gims 和常见分组替换。前后查找及模式内反向引用不支持；超出计算预算会保留原文。",style=MaterialTheme.typography.bodySmall)
    }},confirmButton={TextButton(onClick={runCatching {RegexEngine.compileRule(value)}.onSuccess {onSave(value)}.onFailure {error="表达式无效，请检查语法与标志"}}) {Text("完成")}},dismissButton={TextButton(onClick=onDismiss) {Text("取消")}})
}

package com.moyer.roleplay

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moyer.roleplay.core.CardInspection
import com.moyer.roleplay.data.CharacterCard

@Composable
fun CardInspectorScreen(cards: List<CharacterCard>, inspection: CardInspection?, busy: Boolean, error: String?,
    onBack: ()->Unit, onFile: ()->Unit, onStored: (CharacterCard)->Unit, onExport: (Boolean)->Unit) {
    var query by rememberSaveable(inspection?.name) {mutableStateOf("")}
    var choosing by remember {mutableStateOf(false)}
    // Keep large text out of SavedState/Bundle and split layout into bounded lazy items.
    val sections=remember(inspection,query) {
        inspection?.sections.orEmpty().filter {query.isBlank() || it.title.contains(query,true) || it.body.contains(query,true)}
            .flatMap { section -> section.body.ifEmpty {"（空）"}.chunked(2000).mapIndexed {i,body ->
                (if(i==0) section.title else "${section.title}（续）") to body
            } }
    }
    BackHandler {onBack()}
    Column(Modifier.fillMaxSize().padding(horizontal=20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top=16.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onBack) {Icon(Icons.AutoMirrored.Outlined.ArrowBack,"返回设置")}
            Text("角色卡解析器",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
        }
        LazyColumn(Modifier.weight(1f).testTag("card-inspector-content"),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=24.dp)) {
            item {
                Text("读取原始 PNG / JSON 角色卡，或查看已导入角色的明文。普通图片和截图不含角色设定。",color=MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.padding(top=8.dp)) {
                    OutlinedButton(onClick=onFile,enabled=!busy,modifier=Modifier.testTag("inspect-card-file")) {Text("选择角色卡文件")}
                    OutlinedButton(onClick={choosing=true},enabled=!busy && cards.isNotEmpty(),modifier=Modifier.testTag("inspect-stored-card")) {Text("已导入角色")}
                }
                if(busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top=8.dp))
                error?.let {Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(top=8.dp).testTag("card-inspector-error"))}
            }
            if(inspection != null) {
                item {
                    Text(inspection.name,style=MaterialTheme.typography.titleLarge)
                    Text(inspection.summary,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(top=6.dp))
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick={onExport(false)},enabled=!busy,modifier=Modifier.testTag("export-card-text")) {Text("导出明文 TXT")}
                        TextButton(onClick={onExport(true)},enabled=!busy) {Text("导出 JSON")}
                    }
                    OutlinedTextField(query,{query=it},label={Text("搜索设定或世界书内容")},singleLine=true,
                        modifier=Modifier.fillMaxWidth().testTag("card-inspector-search"))
                }
                if(sections.isEmpty()) item {Text("没有找到相关内容。")}
                items(sections) { (title,body) ->
                    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text(title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                        SelectionContainer {Text(body,style=MaterialTheme.typography.bodyLarge)}
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    if(choosing) AlertDialog(onDismissRequest={choosing=false},title={Text("选择已导入角色")},
        text={LazyColumn(Modifier.heightIn(max=400.dp)) {
            items(cards,key={it.id}) {card -> TextButton(onClick={choosing=false;onStored(card)}) {Text(card.name)}}
        }},confirmButton={TextButton(onClick={choosing=false}) {Text("取消")}})
}

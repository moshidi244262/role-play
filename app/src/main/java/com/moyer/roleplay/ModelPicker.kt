package com.moyer.roleplay

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun ModelPicker(selected: String, models: List<String>, busy: Boolean, feedback: String?,
    onRefresh: ()->Unit, onSelect: (String)->Unit, onDismiss: ()->Unit) {
    var query by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf(selected) }
    val distinct = remember(models) { models.filter(String::isNotBlank).distinct().sorted() }
    val visible = remember(distinct,query) { distinct.filter { it.contains(query.trim(),ignoreCase=true) } }
    AlertDialog(onDismissRequest=onDismiss,title={Text("选择模型")},text={
        val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
        val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        Column(Modifier.heightIn(max=500.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            feedback?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
            OutlinedTextField(query,{query=it},label={Text("搜索模型")},singleLine=true,modifier=Modifier.fillMaxWidth())
            Text("${visible.size} / ${distinct.size} 个模型 · 生成权限以服务商为准",style=MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.weight(1f,fill=false).heightIn(min=60.dp,max=230.dp).testTag("model-list")) {
                items(visible,key={it}) {id->TextButton(onClick={onSelect(id)},enabled=!busy,modifier=Modifier.fillMaxWidth()) {
                    Text(if(id==selected) "✓ $id" else id)
                } }
                if(visible.isEmpty()) item {Text(if(distinct.isEmpty()) "点击获取模型列表。" else "没有匹配的模型。")}
            }
            TextButton(onClick={focusManager.clearFocus();keyboard?.hide();onRefresh()},enabled=!busy) {Text(if(busy) "正在连接…" else "获取模型列表")}
            OutlinedTextField(manual,{manual=it},label={Text("手动模型 ID（可选）")},singleLine=true,modifier=Modifier.fillMaxWidth())
        }
    },confirmButton={TextButton(onClick={onSelect(manual.trim())},enabled=!busy && manual.isNotBlank()) {Text("使用此模型")}},
        dismissButton={TextButton(onClick=onDismiss) {Text("关闭")}})
}

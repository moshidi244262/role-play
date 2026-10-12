package com.moyer.roleplay

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.moyer.roleplay.core.*
import com.moyer.roleplay.data.*

@Composable
internal fun StoryMemoryEditor(session: ChatSession, history: List<ChatMessage>, busy: Boolean,
    candidate: SummaryCandidate?, onGenerate: ()->Unit, onStop: ()->Unit, onDismiss: ()->Unit,
    onSave: (String,StorySummary?,Boolean,Boolean,StorySummary?)->Unit) {
    val initial=remember(session.id) {readConfig(session.config)}
    var fixed by remember(session.id) {mutableStateOf(initial.memory)}
    var summary by remember(session.id) {mutableStateOf(initial.storySummary)}
    var summaryText by remember(session.id) {mutableStateOf(initial.storySummary?.text.orEmpty())}
    var automatic by remember(session.id) {mutableStateOf(initial.autoSummary)}
    var compact by remember(session.id) {mutableStateOf(initial.compactSummaryHistory)}
    var error by remember {mutableStateOf<String?>(null)}
    LaunchedEffect(candidate) {candidate?.takeIf {it.sessionId==session.id}?.let {
        summary=it.summary;summaryText=it.summary.text;error=null
    }}
    val valid=remember(summary,history) {StoryMemory.valid(summary,history)}
    AlertDialog(onDismissRequest={if(!busy) onDismiss()},title={Text("剧情记忆")},text={
        Column(Modifier.heightIn(max=540.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("重要的事留下来，故事才能走得更远。",color=MaterialTheme.colorScheme.onSurfaceVariant)
            Field("固定记忆",fixed,{fixed=it})
            Text("由你决定的事件、关系和线索；整理摘要不会改写这里。",style=MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("剧情摘要",style=MaterialTheme.typography.titleMedium)
            if(summary!=null) {
                Text("来源：第1～${summary!!.throughPosition+1}条中的${summary!!.sourceCount}条完整消息",style=MaterialTheme.typography.bodySmall)
                if(valid==null) Text("来源已变化，旧摘要不会发送，请清除后重新整理。",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                Field("可修订的摘要",summaryText,{summaryText=it})
                TextButton(onClick={summary=null;summaryText=""},enabled=!busy) {Text("清除摘要")}
            } else Text("还没有摘要。整理旧剧情时会保留最近12条完整消息。",style=MaterialTheme.typography.bodySmall)
            if(busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick=onStop) {Text("停止整理")}
            } else OutlinedButton(onClick=onGenerate,modifier=Modifier.fillMaxWidth().testTag("generate-summary")) {Text("生成 / 更新摘要")}
            Text("使用当前会话的API连接和模型，发送旧剧情并产生额外用量。手动生成后需保存才会采用；生成失败会保留原有记忆。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row {Checkbox(automatic,{automatic=it},enabled=!busy);Column(Modifier.weight(1f)) {
                Text("自动整理旧剧情")
                Text("默认关闭。开启后，每新增20条可整理的旧消息调用一次当前API，自动保存摘要并提示；最近12条完整消息始终保留。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }}
            Row {Checkbox(compact,{compact=it},enabled=!busy);Column(Modifier.weight(1f)) {
                Text("用摘要替代已整理的旧消息")
                Text("开启后仅发送摘要和未整理的历史，减少上下文；本地原始聊天保留。关闭时仍发送全部预算内的历史。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }}
            error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        }
    },confirmButton={TextButton(enabled=!busy,onClick={
        runCatching {
            val value=summary?.copy(text=summaryText.trim())
            value?.validate();require(fixed.length<=32000) {"固定记忆最多32000字符"}
            require(value==null || valid!=null) {"摘要来源无效，请先清除旧摘要"}
            onSave(fixed,value,automatic,compact,initial.storySummary)
        }.onFailure {error=it.message}
    }) {Text("保存记忆")}},dismissButton={TextButton(enabled=!busy,onClick=onDismiss) {Text("关闭")}})
}

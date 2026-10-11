package com.moyer.roleplay

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moyer.roleplay.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun MessageContent(text: String, messageId: String) {
    val blocks by produceState(initialValue = emptyList<MessageBlock>(), key1 = text) {
        value = withContext(Dispatchers.Default) { MessageMarkup.parse(text) }
    }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MessageBlocks(blocks, messageId)
    }
}

@Composable
private fun MessageBlocks(blocks: List<MessageBlock>, path: String) {
    blocks.forEachIndexed { index, block ->
        when (block) {
            is MessageBlock.Paragraph -> SelectionContainer {
                Text(buildAnnotatedString {
                    block.runs.forEach { run -> withStyle(SpanStyle(
                        fontWeight = if (run.bold) FontWeight.Bold else null,
                        fontStyle = if (run.italic) FontStyle.Italic else null,
                        fontFamily = if (run.code) FontFamily.Monospace else null
                    )) { append(run.text) } }
                }, lineHeight = 25.sp)
            }
            is MessageBlock.Disclosure -> {
                var expanded by rememberSaveable("$path/$index") { mutableStateOf(block.open) }
                OutlinedCard(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                        Text("${if (expanded) "▾" else "▸"} ${block.title}", fontWeight = FontWeight.SemiBold)
                    }
                    if (expanded) Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) { MessageBlocks(block.children, "$path/$index") }
                }
            }
        }
    }
}

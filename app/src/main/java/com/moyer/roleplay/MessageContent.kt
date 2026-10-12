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
import com.moyer.roleplay.data.TextRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun MessageContent(text: String, messageId: String, rules: List<TextRule> = emptyList(),
    role: String = "assistant", depth: Int = 0, characterName: String = "", userName: String = "我", status: String = "complete") {
    // Transform and parse together; a pending transform must not flash a false "hidden" message.
    key(messageId) {
    val blocks by produceState<List<MessageBlock>?>(null, text, rules, role, depth, characterName, userName, status) {
        value = withContext(Dispatchers.Default) {
            val shown = RegexEngine.apply(text, rules, role, true, depth, characterName, userName).text
            MessageMarkup.parse(shown.ifEmpty {
                if (status == "streaming") "正在回应…" else if (text.isNotEmpty()) "（内容已被显示规则隐藏）" else "未收到文字"
            })
        }
    }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks?.let { MessageBlocks(it, messageId) }
    }
    }
}

@Composable
private fun MessageBlocks(blocks: List<MessageBlock>, path: String) {
    val reading=LocalReadingAppearance.current
    blocks.forEachIndexed { index, block ->
        when (block) {
            is MessageBlock.Paragraph -> SelectionContainer {
                Text(buildAnnotatedString {
                    block.runs.forEach { run -> withStyle(SpanStyle(
                        fontWeight = if (run.bold) FontWeight.Bold else null,
                        fontStyle = if (run.italic) FontStyle.Italic else null,
                        fontFamily = if (run.code) FontFamily.Monospace else null
                    )) { append(run.text) } }
                }, fontSize=reading.fontSize.sp, lineHeight=(reading.fontSize*1.75f).sp)
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

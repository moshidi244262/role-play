package com.moyer.roleplay

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moyer.roleplay.core.HelpCatalog

@Composable
fun HelpScreen(onBack: () -> Unit) {
    val focus=LocalFocusManager.current
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val article=HelpCatalog.articles.firstOrNull { it.id==selected }
    val results=remember(query,category) { HelpCatalog.search(query,category) }
    fun back() { if(selected!=null) selected=null else onBack() }
    BackHandler { back() }
    Column(Modifier.fillMaxSize().padding(horizontal=20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top=16.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=::back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,"返回帮助或设置") }
            Text(if(article==null) "帮助" else article.category,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
        }
        if(article==null) {
            if(query.isBlank()) Text("了解酒馆资料，找到适合你的用法",color=MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(value=query,onValueChange={query=it},label={Text("搜索帮助")},
                placeholder={Text("角色卡、世界书、思维链、导入…")},singleLine=true,
                keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={focus.clearFocus()}),
                leadingIcon={Icon(Icons.Outlined.Search,null)},trailingIcon={if(query.isNotEmpty()) IconButton(onClick={query=""}) {Icon(Icons.Outlined.Close,"清空帮助搜索")}},
                modifier=Modifier.fillMaxWidth().padding(vertical=12.dp).testTag("help-search"))
            LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                item { FilterChip(category==null,{category=null},label={Text("全部")}) }
                items(HelpCatalog.articles.map { it.category }.distinct()) { value -> FilterChip(category==value,{category=value},label={Text(value)}) }
            }
            Text("${results.size} 条内容 · 搜索包含正文",style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(vertical=8.dp))
            LazyColumn(Modifier.weight(1f).testTag("help-results"),verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(bottom=24.dp)) {
                if(results.isEmpty()) item {
                    Text("没有找到相关内容。试试更短的关键词，如“世界书”“变量”“空白”，或切换到“全部”。",modifier=Modifier.padding(vertical=24.dp))
                }
                items(results,key={it.id}) { value ->
                    OutlinedCard(onClick={focus.clearFocus();selected=value.id},modifier=Modifier.fillMaxWidth().testTag("help-article-${value.id}")) {
                        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Text(value.category,color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelMedium)
                            Text(value.title,fontWeight=FontWeight.SemiBold)
                            Text(value.summary,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f).testTag("help-detail"),verticalArrangement=Arrangement.spacedBy(16.dp),contentPadding=PaddingValues(bottom=24.dp)) {
                item {
                    Text(article.title,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
                    Text(article.summary,modifier=Modifier.padding(top=8.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(article.sections) { (title,body) ->
                    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text(title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                        SelectionContainer { Text(body,style=MaterialTheme.typography.bodyLarge) }
                    }
                }
            }
        }
    }
}

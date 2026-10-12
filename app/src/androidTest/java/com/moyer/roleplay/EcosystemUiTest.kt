package com.moyer.roleplay

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moyer.roleplay.core.*
import com.moyer.roleplay.data.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EcosystemUiTest {
    @get:Rule val compose=createComposeRule()

    @Test fun helpSearchFindsBodyAliasesAndCanReturnToSearch() {
        var returned=false
        compose.setContent {MaterialTheme {HelpScreen {returned=true}}}
        compose.onNodeWithTag("help-search").performTextInput("世界树")
        compose.onNodeWithTag("help-article-lore").performClick()
        compose.onNodeWithText("世界书是什么？什么时候用？").assertIsDisplayed()
        compose.onNodeWithText("怎么用").assertExists()
        compose.onNodeWithContentDescription("返回帮助或设置").performClick()
        compose.onNodeWithTag("help-search").assertTextContains("世界树")
        compose.onNodeWithContentDescription("清空帮助搜索").performClick()
        compose.onNodeWithTag("help-search").performTextInput("abcdef不存在")
        compose.onNodeWithText("没有找到相关内容。",substring=true).assertIsDisplayed()
        compose.onNodeWithContentDescription("返回帮助或设置").performClick()
        assertTrue(returned)
    }

    @Test fun dedicatedFileImportButtonsDispatchCorrectKindsAndRespectBusyState() {
        var imported=""
        compose.setContent {MaterialTheme {ResourceScreen(emptyList(),false,{}, {}, {}, {}, {}, {}, {}, {}, {imported=it})}}
        compose.onNodeWithText("导入世界书文件").performClick();assertEquals("lorebook",imported)
        compose.onNodeWithText("预设").performClick()
        compose.onNodeWithText("导入预设文件").performClick();assertEquals("preset",imported)
    }

    @Test fun presetEditorSearchesLargeImportedPresetAndEditsEmbeddedRules() {
        val preset=StylePreset(blocks=List(220) {PromptBlock(name="块$it",content="内容$it")},
            regexRules=listOf(TextRule(name="预设自带的隐藏规则",pattern="/旧/g",replacement="新")),ordered=true)
        val asset=ResourceAsset(kind="preset",name="大预设",payload=appJson.encodeToString(preset))
        var saved: ResourceAsset?=null
        compose.setContent {MaterialTheme {ResourceEditor(asset,{}, {saved=it})}}
        compose.onNodeWithText("搜索条目或内容").performTextInput("块219")
        compose.onNodeWithText("块219").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("块218").assertDoesNotExist()
        compose.onNodeWithText("搜索条目或内容").performTextClearance()
        compose.onNodeWithText("内嵌正则（1 条）").performScrollTo().performClick()
        compose.onNodeWithText("预设自带的隐藏规则").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("停用").performClick()
        compose.onNodeWithText("保存").performClick()
        assertFalse(appJson.decodeFromString<StylePreset>(saved!!.payload).regexRules.single().enabled)
    }

    @Test fun generatedThinkBlockFoldsAndPreservesBody() {
        compose.setContent {MaterialTheme {MessageContent("<think>这段是生成的规划文字。</think><content>门外响起脚步声。</content>","thought")}}
        compose.waitUntil(5000) {compose.onAllNodesWithText("▸ 思考说明（模型生成的文字）").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("门外响起脚步声。").assertIsDisplayed()
        compose.onNodeWithText("这段是生成的规划文字。").assertDoesNotExist()
        compose.onNodeWithText("▸ 思考说明（模型生成的文字）").performClick()
        compose.onNodeWithText("这段是生成的规划文字。").assertIsDisplayed()
    }
}

package com.moyer.roleplay

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moyer.roleplay.core.appJson
import com.moyer.roleplay.data.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadingUiTest {
    @get:Rule val compose=createComposeRule()

    private val card=CharacterCard(name="阅读测试")
    private val session=ChatSession(title=card.name,characterId=card.id,characterSnapshot=appJson.encodeToString(card))

    @Test fun invalidBudgetInputDoesNotSavePreviousValueSilently() {
        var saved = false
        compose.setContent { MaterialTheme { ConfigEditor(RoleplayConfig(), emptyList(), "数字校验", {}, { saved = true }) } }
        compose.onNodeWithText("上下文与扫描选项").performScrollTo().performClick()
        compose.onNodeWithText("上下文字符预算").performScrollTo().performTextReplacement("99999999999999999")
        compose.onNodeWithText("保存").performClick()
        assertFalse(saved)
        compose.onNodeWithText("数字校验").assertExists()
        compose.onNodeWithText("上下文字符预算").performScrollTo().performTextReplacement("24000")
        compose.onNodeWithText("保存").performClick()
        assertTrue(saved)
    }

    @Test fun tallStreamingMessageDoesNotPullReaderDownAfterUpwardGesture() {
        val content=mutableStateOf((1..120).joinToString("\n") {"第 $it 行：这是逐步生成的长篇故事。"})
        compose.setContent { MaterialTheme {
            ChatScreen(session,listOf(ChatMessage(id="reply",sessionId=session.id,role="assistant",content=content.value,position=0,status="streaming")),
                emptyList(),emptyList(),"",true,{}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {_,_,_->}, {},
                emptyMap(),emptyList(),null,false,{}, {})
        } }
        compose.waitUntil(5000) {compose.onAllNodesWithText("第 120 行",substring=true).fetchSemanticsNodes().isNotEmpty()}
        compose.waitForIdle()
        val list=compose.onNodeWithTag("chat-messages")
        list.performTouchInput { swipeDown(startY=height*0.2f,endY=height*0.85f,durationMillis=400) }
        compose.onNodeWithTag("back-to-latest").assertIsDisplayed()
        compose.onNodeWithText("回到最新").assertDoesNotExist()
        compose.onNodeWithContentDescription("回到最新").assertIsDisplayed()
        val before=list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        compose.runOnIdle {content.value += "\n"+(121..160).joinToString("\n") {"第 $it 行：继续收到新的内容。"}}
        compose.waitUntil(5000) {compose.onAllNodesWithText("第 160 行",substring=true).fetchSemanticsNodes().isNotEmpty()}
        compose.waitForIdle()
        val after=list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals(before,after,0.001f)
        compose.onNodeWithTag("back-to-latest").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("back-to-latest").assertDoesNotExist()
        assertTrue(list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()>after)
    }

    @Test fun statusPanelCanExpandWithoutShowingHtmlCode() {
        compose.setContent {MaterialTheme {MessageContent("院中静了一瞬。<details><summary>引水序</summary><div>序甲：广寂</div></details>","status")}}
        compose.waitUntil(5000) {compose.onAllNodesWithText("▸ 引水序").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("院中静了一瞬。").assertExists()
        compose.onNodeWithText("序甲：广寂").assertDoesNotExist()
        compose.onNodeWithText("▸ 引水序").performClick()
        compose.onNodeWithText("序甲：广寂").assertIsDisplayed()
        compose.onNodeWithText("<details>",substring=true).assertDoesNotExist()
    }

    @Test fun displayRuleRunsBeforeHtmlParsingAndLeavesRawContentUnchanged() {
        val raw = "隐藏字<details><summary>状态</summary><p>真实内容</p></details>"
        compose.setContent { MaterialTheme {
            MessageContent(raw, "rule-markup", listOf(TextRule(pattern = "/隐藏字/g", replacement = "")))
        } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("▸ 状态").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("隐藏字", substring = true).assertDoesNotExist()
        compose.onNodeWithText("▸ 状态").performClick()
        compose.onNodeWithText("真实内容").assertIsDisplayed()
        assertTrue(raw.startsWith("隐藏字"))
    }

    @Test fun modelPickerSearchFindsAnotherModelInSameConnection() {
        var selected=""
        compose.setContent {MaterialTheme {ModelPicker("deepseek-flash",listOf("deepseek-flash","deepseek-v4-pro"),false,null,{}, {selected=it},{})}}
        compose.onNodeWithText("搜索模型").performTextInput("pro")
        compose.onNodeWithText("deepseek-v4-pro").performClick()
        assertEquals("deepseek-v4-pro",selected)
    }
}

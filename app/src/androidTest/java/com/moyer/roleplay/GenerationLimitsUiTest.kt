package com.moyer.roleplay

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moyer.roleplay.core.*
import com.moyer.roleplay.data.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GenerationLimitsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun unlimitedContextCanBeEnabledAndRestoresThePreviouslySpecifiedBudget() {
        var saved: RoleplayConfig? = null
        compose.setContent { MaterialTheme { ConfigEditor(RoleplayConfig(historyBudgetChars=90000),emptyList(),"会话设定",{}, {saved=it}) } }
        compose.onNodeWithText("上下文与扫描选项").performScrollTo().performClick()
        compose.onNodeWithTag("unlimited-context").performScrollTo().performClick()
        compose.onNodeWithText("上下文字符预算").assertDoesNotExist()
        compose.onNodeWithText("保存").performClick()
        assertEquals(0,saved!!.historyBudgetChars)
        assertTrue(saved!!.builtInMemoryEnabled)
        compose.onNodeWithTag("unlimited-context").performScrollTo().performClick()
        compose.onNodeWithText("上下文字符预算").performScrollTo().assertTextContains("90000")
        compose.onNodeWithText("保存").performClick()
        assertEquals(90000,saved!!.historyBudgetChars)
    }

    @Test fun lengthLimitedReplyRemainsVisibleWhenUsageIsHidden() {
        val card=CharacterCard(name="长度状态")
        val session=ChatSession(title=card.name,characterId=card.id,characterSnapshot=appJson.encodeToString(card))
        compose.setContent { MaterialTheme {
            ChatScreen(session,listOf(ChatMessage(sessionId=session.id,role="assistant",content="故事尚未结束",position=0,
                status="length_limited",inputTokens=7358,outputTokens=2048)),emptyList(),emptyList(),"",false,
                {},{},{},{},{},{},{},{},{},{},{_,_,_->},{},emptyMap(),emptyList(),null,false,{},{},showTokenUsage=false)
        } }
        compose.onNodeWithText("故事尚未结束").assertIsDisplayed()
        compose.onNodeWithText("已达输出或模型上下文上限 · 可提高输出上限或点击续写").assertIsDisplayed()
        compose.onNodeWithText("续写").assertIsDisplayed()
        compose.onNodeWithText("输入 7358 · 输出 2048 tokens").assertDoesNotExist()
    }
}

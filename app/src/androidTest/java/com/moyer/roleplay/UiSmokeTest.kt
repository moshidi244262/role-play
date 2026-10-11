package com.moyer.roleplay

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun characterStartsConversationAndDraftSurvivesActivityRecreation() {
        compose.waitUntil(10000) { compose.onAllNodesWithText("角色").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("角色").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("开始新会话").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("开始新会话").onFirst().performClick()
        compose.onNodeWithText("说些什么，或描述你的行动…").performTextInput("这是重开后保留的草稿")
        compose.waitForIdle()
        // Returning to the list waits for the Room draft write to appear in UI.
        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("有未发送的草稿").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("林舟").onFirst().performClick()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10000) { compose.onAllNodesWithText("这是重开后保留的草稿").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("这是重开后保留的草稿").assertIsDisplayed()
    }
}

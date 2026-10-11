package com.moyer.roleplay

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import java.io.File
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
        screenshot("01-characters")
        compose.onAllNodesWithText("开始新会话").onFirst().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("说些什么，或描述你的行动…").fetchSemanticsNodes().isNotEmpty() }
        screenshot("02-chat")
        compose.onNodeWithContentDescription("会话菜单").performClick()
        compose.onNodeWithText("查看聊天上下文").performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithText("世界书命中：",substring=true).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("旧城档案",substring=true).assertExists()
        compose.onNodeWithText("关闭").performClick()
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

    @Test fun connectionCanSaveWithoutFetchedModelAndShowsInlineKeyError() {
        compose.waitUntil(10000) {compose.onAllNodesWithContentDescription("返回").fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithText("设置").fetchSemanticsNodes().isNotEmpty()}
        if(compose.onAllNodesWithContentDescription("返回").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("设置").performClick()
        compose.onAllNodesWithText("添加连接").onFirst().performClick()
        compose.onNodeWithText("模型 ID").performTextClearance()
        compose.onNodeWithText("保存").assertIsEnabled()
        compose.onNodeWithText("获取模型列表").performScrollTo().performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithText("请为该服务地址填写 API 密钥").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("请为该服务地址填写 API 密钥").assertIsDisplayed()
        screenshot("03-connection-feedback")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithText("DeepSeek · ").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("资料库").performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithText("旧城档案 · 示例世界书").fetchSemanticsNodes().isNotEmpty()}
        screenshot("04-library")
    }

    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}

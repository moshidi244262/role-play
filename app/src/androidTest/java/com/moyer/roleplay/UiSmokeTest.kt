package com.moyer.roleplay

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import androidx.lifecycle.ViewModelProvider
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.zip.CRC32

@RunWith(AndroidJUnit4::class)
class UiSmokeTest {
    @get:Rule(order=0) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order=1) val captureFailure = object : TestWatcher() {
        override fun failed(error: Throwable?, description: Description) { screenshot("failure-${description.methodName}") }
    }
    private lateinit var vm: ChatViewModel

    @Before fun awaitDatabaseAndReturnToNavigation() {
        compose.runOnUiThread {vm=ViewModelProvider(compose.activity)[ChatViewModel::class.java]}
        compose.waitUntil(15000) {vm.ready.value}
        compose.waitForIdle()
        if(compose.onAllNodesWithContentDescription("返回").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("返回").performClick()
    }

    @Test fun characterStartsConversationAndDraftSurvivesActivityRecreation() {
        compose.waitUntil(10000) { compose.onAllNodesWithText("角色").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("角色").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("开始新会话").fetchSemanticsNodes().isNotEmpty() }
        screenshot("01-characters")
        compose.onNodeWithTag("characters").performScrollToNode(hasContentDescription("开始与林舟的新会话"))
        compose.onNodeWithContentDescription("开始与林舟的新会话").performClick()
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
        compose.waitUntil(5000) {compose.onNodeWithText("请为该服务地址填写 API 密钥").isDisplayed()}
        compose.onNodeWithText("请为该服务地址填写 API 密钥").assertIsDisplayed()
        screenshot("03-connection-feedback")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithText("DeepSeek · ").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("资料库").performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithText("旧城档案 · 示例世界书").fetchSemanticsNodes().isNotEmpty()}
        screenshot("04-library")
    }

    @Test fun pngCardImportPreviewCreatesCharacterAvatarAndBoundLore() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val json="""{"spec":"chara_card_v2","data":{"name":"导入验证角色","description":"高塔守卫","first_mes":"你来到了高塔。","character_book":{"entries":[{"keys":["高塔"],"content":"塔楼编号是 T-09。","enabled":true}]}}}"""
        val png=ByteArrayOutputStream().also {out->
            Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888).apply {compress(Bitmap.CompressFormat.PNG,100,out);recycle()}
        }.toByteArray()
        val data="chara".toByteArray()+byteArrayOf(0)+Base64.getEncoder().encode(json.toByteArray())
        val chunk=ByteArrayOutputStream().also {out->DataOutputStream(out).apply {
            writeInt(data.size);write("tEXt".toByteArray());write(data);writeInt(CRC32().apply {update("tEXt".toByteArray()+data)}.value.toInt())
        }}.toByteArray()
        val file=File(context.cacheDir,"ui-character.png").apply {writeBytes(png.copyOfRange(0,png.size-12)+chunk+png.copyOfRange(png.size-12,png.size))}
        compose.runOnUiThread {vm.previewImport(Uri.fromFile(file))}
        compose.waitUntil(15000) {compose.onAllNodesWithText("导入预览").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("1 个角色、1 份资料",substring=true).assertExists()
        compose.onNodeWithText("导入副本").performClick()
        compose.waitUntil(15000) {vm.pendingImport.value==null && !vm.operationBusy.value}
        compose.onNodeWithText("角色").performClick()
        compose.onNodeWithTag("characters").performScrollToNode(hasTestTag("character-search"))
        compose.onNodeWithTag("character-search").performTextInput("导入验证角色")
        compose.waitUntil(15000) {compose.onAllNodesWithText("导入验证角色").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("characters").performScrollToNode(hasContentDescription("开始与导入验证角色的新会话"))
        compose.onNodeWithContentDescription("开始与导入验证角色的新会话").performClick()
        compose.waitUntil(15000) {compose.onAllNodesWithText("说些什么，或描述你的行动…").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("会话菜单").performClick();compose.onNodeWithText("查看聊天上下文").performClick()
        compose.waitUntil(15000) {compose.onAllNodesWithText("塔楼编号是 T-09。",substring=true).fetchSemanticsNodes().isNotEmpty()}
        screenshot("05-imported-lore")
        compose.onNodeWithText("关闭").performClick()
        file.delete()
    }

    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(folder, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG,100,it)
        }
        bitmap.recycle()
    }
}

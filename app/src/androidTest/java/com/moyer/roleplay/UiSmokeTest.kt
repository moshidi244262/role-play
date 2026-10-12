package com.moyer.roleplay

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import kotlinx.coroutines.flow.first
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

    @Test fun fullLargePresetImportSurvivesActivityRecreationAndFreshStartup() {
        if(vm.selectedId.value!=null) compose.onNodeWithContentDescription("返回").performClick()
        compose.onNode(hasText("资料库") and hasClickAction()).performClick()
        val fixture=LargePresetPersistenceTest().fixture()
        val file=File(compose.activity.cacheDir,"large-ui-preset.json")
        file.writeText(fixture.sourceJson)
        var assetId: String?=null
        val store=androidx.lifecycle.ViewModelStore()
        try {
            compose.runOnUiThread {vm.previewImport(Uri.fromFile(file),"preset")}
            compose.waitUntil(30000) {vm.pendingImport.value!=null && !vm.operationBusy.value}
            assetId=vm.pendingImport.value!!.resources.single().id
            compose.onNodeWithText("导入副本").performClick()
            compose.waitUntil(30000) {vm.resources.value.any {it.id==assetId}}
            compose.onNodeWithText("预设").performClick()
            compose.onNodeWithText("large-ui-preset").performScrollTo().assertIsDisplayed()
            screenshot("11-large-preset-saved")
            compose.activityRule.scenario.recreate()
            compose.runOnUiThread {vm=ViewModelProvider(compose.activity)[ChatViewModel::class.java]}
            compose.waitUntil(30000) {vm.resources.value.any {it.id==assetId}}
            lateinit var fresh: ChatViewModel
            compose.runOnUiThread {fresh=ViewModelProvider(store,ViewModelProvider.AndroidViewModelFactory.getInstance(compose.activity.application))[ChatViewModel::class.java]}
            compose.waitUntil(15000) {fresh.ready.value}
            org.junit.Assert.assertNull(fresh.selectedId.value)
            val restored=kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(30000) {fresh.resources.first {items->items.any {it.id==assetId}}.first {it.id==assetId}}
            }
            assertEquals(fixture.sourceJson,restored.sourceJson)
            assertEquals(220,com.moyer.roleplay.core.appJson.decodeFromString<com.moyer.roleplay.data.StylePreset>(restored.payload).blocks.size)
        } finally {
            compose.runOnUiThread {store.clear();assetId?.let {vm.deleteResource(it)}}
            if(assetId!=null) compose.waitUntil(30000) {vm.resources.value.none {it.id==assetId}}
            file.delete()
        }
    }

    @Test fun freshLaunchStartsAtSessionsWhileRotationKeepsCurrentConversation() {
        val session=vm.sessions.value.firstOrNull()
        if(session==null) {
            compose.onNode(hasText("角色") and hasClickAction()).performClick()
            compose.onNodeWithTag("characters").performScrollToNode(hasContentDescription("开始与林舟的新会话"))
            compose.onNodeWithContentDescription("开始与林舟的新会话").performClick()
            compose.waitUntil(10000) {vm.selectedId.value!=null}
        } else compose.runOnUiThread {vm.openChat(session.id)}
        val selected=vm.selectedId.value
        compose.activityRule.scenario.recreate()
        compose.runOnUiThread {vm=ViewModelProvider(compose.activity)[ChatViewModel::class.java]}
        assertEquals(selected,vm.selectedId.value)
        val store=androidx.lifecycle.ViewModelStore()
        lateinit var fresh: ChatViewModel
        compose.runOnUiThread {
            compose.activity.application.getSharedPreferences("ui",android.content.Context.MODE_PRIVATE).edit().putString("last_session",selected).commit()
            fresh=ViewModelProvider(store,ViewModelProvider.AndroidViewModelFactory.getInstance(compose.activity.application))[ChatViewModel::class.java]
        }
        compose.waitUntil(10000) {fresh.ready.value}
        org.junit.Assert.assertNull(fresh.selectedId.value)
        compose.runOnUiThread {store.clear()}
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNode(hasText("会话") and hasClickAction()).assertExists()
    }

    @Test fun settingsHelpSearchAndTypedFileImportRemainAvailableAfterRecreation() {
        compose.onNode(hasText("设置") and hasClickAction()).performClick()
        compose.onNodeWithTag("settings-help").performScrollTo().performClick()
        compose.onNodeWithTag("help-search").performTextInput("思维链")
        compose.onNodeWithTag("help-search").performImeAction()
        screenshot("08-help-search")
        compose.onNodeWithTag("help-article-cot").performClick()
        screenshot("09-help-detail")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("思维链是什么？需要开启吗？").assertExists()
        compose.onNodeWithContentDescription("返回帮助或设置").performClick()
        compose.onNodeWithTag("help-search").assertTextContains("思维链")
        compose.onNodeWithContentDescription("返回帮助或设置").performClick()
        compose.onNodeWithTag("settings-help").assertExists()
        val file=File(compose.activity.cacheDir,"typed-preset.json")
        file.writeText("{\"prompts\":[{\"identifier\":\"a\",\"content\":\"{{setvar::style::简洁}}{{getvar::style}}\"}],\"extensions\":{\"regex_scripts\":[{\"findRegex\":\"/旧/g\",\"replaceString\":\"新\"}]}}")
        compose.runOnUiThread {vm.previewImport(Uri.fromFile(file),"preset")}
        compose.waitUntil(10000) {vm.pendingImport.value!=null}
        compose.onNodeWithText("导入副本").performClick()
        compose.waitUntil(10000) {vm.resources.value.any {it.name=="typed-preset"}}
        compose.activityRule.scenario.recreate()
        compose.runOnUiThread {vm=ViewModelProvider(compose.activity)[ChatViewModel::class.java]}
        compose.waitUntil(10000) {vm.resources.value.any {it.name=="typed-preset"}}
        val imported=vm.resources.value.first {it.name=="typed-preset"}
        assertEquals(1,com.moyer.roleplay.core.appJson.decodeFromString<com.moyer.roleplay.data.StylePreset>(imported.payload).regexRules.size)
        compose.onNode(hasText("资料库") and hasClickAction()).performClick()
        compose.onNodeWithText("导入世界书文件").assertExists()
        compose.onNodeWithText("预设").performClick()
        compose.onNodeWithText("导入预设文件").assertExists()
        screenshot("10-preset-import")
        file.delete()
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

    @Test fun invalidCharacterSaveKeepsEditorAndEnteredTextUntilSuccessfulSave() {
        compose.onNode(hasText("角色") and hasClickAction()).performClick()
        compose.onNodeWithContentDescription("新建角色").performClick()
        val invalid = "名".repeat(201)
        compose.onNodeWithText("角色名称").performTextInput(invalid)
        compose.onNodeWithText("保存").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("角色设定").assertExists()
        compose.onNodeWithText(invalid).assertExists()
        compose.onNodeWithText("角色名称").performTextReplacement("保存校验测试")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("角色设定").fetchSemanticsNodes().isEmpty() }
        compose.waitUntil(10000) { vm.characters.value.any { it.name == "保存校验测试" } }
    }

    @Test fun rapidDraftChangesKeepNewestTextInEachSessionAndDeletionClearsMemory() {
        val repository = (compose.activity.application as RolePlayApplication).repository
        val ids = kotlinx.coroutines.runBlocking {
            val card = repository.dao.allCharacters().first()
            listOf(repository.createSession(card, null), repository.createSession(card, null))
        }
        compose.runOnUiThread {
            repeat(200) {
                vm.saveDraft(ids[0], "第一会话 $it")
                vm.saveDraft(ids[1], "第二会话 $it")
            }
            vm.saveDraft(ids[0], "")
            vm.saveDraft(ids[1], "最后保留的草稿")
        }
        compose.waitUntil(10000) { vm.sessions.value.any { it.id == ids[1] && it.draft == "最后保留的草稿" } }
        kotlinx.coroutines.runBlocking {
            org.junit.Assert.assertEquals("", repository.dao.session(ids[0])!!.draft)
            org.junit.Assert.assertEquals("最后保留的草稿", repository.dao.session(ids[1])!!.draft)
        }
        compose.runOnUiThread { ids.forEach { vm.deleteChat(it) } }
        compose.waitUntil(10000) { ids.none { it in vm.drafts.value } }
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
        compose.waitForIdle()
        screenshot("03-connection-feedback")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithText("DeepSeek · ").fetchSemanticsNodes().isNotEmpty()}
        compose.waitUntil(10000) {compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty()}
        compose.waitForIdle()
        compose.onNodeWithText("资料库").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("旧城档案 · 示例世界书"))
        compose.onNodeWithText("旧城档案 · 示例世界书").assertIsDisplayed()
        screenshot("04-library")
    }

    @Test fun tokenSettingPersistsForFreshViewModelAndActivity() {
        compose.runOnUiThread { vm.setShowTokenUsage(true) }
        compose.onNode(hasText("设置") and hasClickAction()).performClick()
        compose.onNodeWithTag("show-token-usage").performScrollTo().assertIsOn().performClick()
        compose.onNodeWithTag("show-token-usage").assertIsOff()
        val store = androidx.lifecycle.ViewModelStore()
        try {
            compose.runOnUiThread {
                val fresh = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(compose.activity.application))[ChatViewModel::class.java]
                org.junit.Assert.assertFalse(fresh.showTokenUsage.value)
            }
            compose.activityRule.scenario.recreate()
            compose.waitUntil(10000) { compose.onAllNodes(hasText("设置") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText("设置") and hasClickAction()).performClick()
            compose.onNodeWithTag("show-token-usage").performScrollTo().assertIsOff()
            screenshot("06-token-setting")
        } finally {
            compose.runOnUiThread { store.clear(); vm.setShowTokenUsage(true) }
        }
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
        compose.waitForIdle()
        runCatching {InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(200,3000)}
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(folder, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG,100,it)
        }
        bitmap.recycle()
    }
}

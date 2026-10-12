package com.moyer.roleplay

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.room.withTransaction
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.moyer.roleplay.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlinx.coroutines.runBlocking

class CardInspectorIntegrationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun settingsReadsStoredCardSurvivesRotationAndWritesBothFormats() {
        val app=compose.activity.application as RolePlayApplication
        lateinit var vm: ChatViewModel
        compose.activityRule.scenario.onActivity {vm=ViewModelProvider(it)[ChatViewModel::class.java]}
        compose.waitUntil(10000) {vm.ready.value}
        val raw="""{"spec":"chara_card_v2","data":{"name":"解析集成角色","description":"角色描述","character_book":{"entries":[{"name":"背景","constant":true,"content":"完整的世界背景"}]}}}"""
        val pack=CommunityImporter.decode(raw.toByteArray())
        val txt=File(app.cacheDir,"card-inspector-test.txt");val json=File(app.cacheDir,"card-inspector-test.json")
        val jpeg=File(app.cacheDir,"fake-card.png")
        try {
        runBlocking {app.repository.importCommunity(pack)}
        compose.waitUntil(10000) {vm.characters.value.any {it.name=="解析集成角色"}}
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithTag("settings-card-inspector").performScrollTo().performClick()
        compose.onNodeWithTag("inspect-stored-card").performClick()
        compose.onNodeWithText("解析集成角色").performClick()
        compose.waitUntil(10000) {vm.cardInspection.value!=null}
        val text=vm.cardInspection.value!!.plainText()
        assertTrue(text.contains("完整的世界背景"))
        val screenshots=File(app.getExternalFilesDir(null),"screenshots").apply {mkdirs()}
        File(screenshots,"12-card-inspector.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
        }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("card-inspector-search").performTextInput("完整的世界背景")
        compose.onNodeWithText("世界书 1：背景").performScrollTo().assertIsDisplayed()
        txt.delete();json.delete()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {vm.exportCardInspection(Uri.fromFile(txt),vm.cardInspection.value!!,false)}
        compose.waitUntil(10000) {!vm.cardInspectionBusy.value && txt.exists()}
        assertEquals(text,txt.readText())
        InstrumentationRegistry.getInstrumentation().runOnMainSync {vm.exportCardInspection(Uri.fromFile(json),vm.cardInspection.value!!,true)}
        compose.waitUntil(10000) {!vm.cardInspectionBusy.value && json.exists()}
        assertEquals("解析集成角色",CharacterCardInspector.inspect(json.readBytes()).name)
        jpeg.writeBytes(byteArrayOf(-1,-40,-1,-32))
        InstrumentationRegistry.getInstrumentation().runOnMainSync {vm.inspectCharacterFile(Uri.fromFile(jpeg))}
        compose.waitUntil(10000) {!vm.cardInspectionBusy.value && vm.cardInspectionError.value!=null}
        assertTrue(vm.cardInspectionError.value!!.contains("JPEG"));assertNull(vm.cardInspection.value)
        compose.onNodeWithTag("inspect-stored-card").performScrollTo().assertIsEnabled()
        } finally {
            runBlocking {app.repository.database.withTransaction {
                pack.resources.forEach {app.repository.dao.deleteResource(it.id)}
                app.repository.database.openHelper.writableDatabase.execSQL("DELETE FROM characters WHERE id = ?",arrayOf(pack.characters.single().id))
            }}
            listOf(txt,json,jpeg).forEach {it.delete()}
        }
    }
}

package com.moyer.roleplay

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.moyer.roleplay.core.*
import com.moyer.roleplay.data.CharacterCard
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CardInspectorUiTest {
    @get:Rule val compose=createComposeRule()
    @Test fun searchFindsWorldbookBodyAndExportsReadableText() {
        val inspection=CharacterCardInspector.inspect("""{"spec":"chara_card_v2","data":{"name":"测试角色","character_book":{"entries":[{"name":"背景","content":"引水阁的历史"},{"name":"未启用秘密","enabled":false,"content":"隐藏档案"}]}}}""".toByteArray())
        var export: Boolean?=null;var returned=false
        compose.setContent {MaterialTheme {CardInspectorScreen(emptyList(),inspection,false,null,{returned=true},{},{},{export=it})}}
        compose.onNodeWithTag("export-card-text").performClick();assertEquals(false,export)
        compose.onNodeWithTag("card-inspector-search").performTextInput("隐藏档案")
        compose.onNodeWithText("世界书 2：未启用秘密").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("世界书 1：背景").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回设置").performClick();assertTrue(returned)
    }
    @Test fun storedPickerAndBadImageErrorRemainUsable() {
        val card=CharacterCard(name="软件中保存的角色");var selected: CharacterCard?=null
        compose.setContent {MaterialTheme {CardInspectorScreen(listOf(card),null,false,"收到的是 JPEG 图片，没有可读取的 PNG 角色卡资料",{},{},{selected=it},{})}}
        compose.onNodeWithTag("card-inspector-error").assertIsDisplayed()
        compose.onNodeWithTag("inspect-stored-card").performClick()
        compose.onNodeWithText(card.name).performClick();assertEquals(card,selected)
        compose.onNodeWithTag("inspect-card-file").assertIsEnabled()
    }
}

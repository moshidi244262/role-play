package com.moyer.roleplay

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import com.moyer.roleplay.core.*
import com.moyer.roleplay.data.*
import kotlinx.serialization.encodeToString
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DesignReviewTest {
    @get:Rule val compose=createComposeRule()
    private val card=CharacterCard(id="design-character",name="林舟",personality="温和而敏锐，偶尔带一点不动声色的幽默。",scenario="旧城档案馆的灯亮着。窗外的雨还没有停，他正在等一位迟到的旅人。")
    private val session=ChatSession(id="design-story",title=card.name,characterId=card.id,characterSnapshot=appJson.encodeToString(card))
    private fun capture(name: String) {
        compose.waitForIdle()
        val context=ApplicationProvider.getApplicationContext<Context>()
        val folder=File(context.getExternalFilesDir(null),"screenshots").apply {mkdirs()}
        File(folder,name).outputStream().use {compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
    }

    @Test fun latestStoryHasOneDirectContinueActionAndDeletionIsInMenu() {
        var opened="";var deleted=false
        compose.setContent {RolePlayTheme(false) {SessionScreen(listOf(session),listOf(card),{opened=it},{deleted=true},{},{},mapOf(session.id to "林舟把一枚蓝色钥匙放在桌边。他说，明晚西门会有人等你。"))}}
        capture("20-redesign-sessions.png")
        compose.onNodeWithTag("continue-last-story").performClick()
        assertEquals(session.id,opened)
        compose.onNodeWithText("删除会话").assertDoesNotExist()
        compose.onNodeWithContentDescription("会话操作：林舟").performClick()
        compose.onNodeWithText("删除会话").performClick()
        assertTrue(deleted)
    }

    @Test fun rolePrimaryActionRemainsDirectWhileExportIsSecondary() {
        var chatted=false;var exported=false
        compose.setContent {RolePlayTheme(false) {CharacterScreen(listOf(card),{},{},{exported=true},{},{chatted=true})}}
        capture("21-redesign-characters.png")
        compose.onNodeWithContentDescription("开始与林舟的新会话").performClick()
        assertTrue(chatted)
        compose.onNodeWithContentDescription("更多角色操作：林舟").performClick()
        compose.onNodeWithText("导出角色与关联资料").performClick()
        assertTrue(exported)
    }

    @Test fun readingModesKeepRawMessageAvailableAndExposeMemoryWithoutAvatar() {
        val quiet=mutableStateOf(false);val dark=mutableStateOf(false)
        var memory=false
        val messages=listOf(ChatMessage(sessionId=session.id,role="user",content="我把信递给他，问起西门的约定。",position=0),
            ChatMessage(sessionId=session.id,role="assistant",content="林舟接过信，没有急着拆开。\n\n“你来得正好。”他指了指窗边的座位，“雨还要下一会儿，先坐。”\n\n桌上的灯把信封照成温暖的浅黄色。那枚蓝色钥匙，就放在你的手边。",position=1))
        compose.setContent {RolePlayTheme(dark.value,ReadingAppearance(17,quiet.value)) {
            ChatScreen(session,messages,emptyList(),emptyList(),"",false,{},{},{},{},{},{},{},{},{},{},{_,_,_->},{},
                emptyMap(),emptyList(),null,false,{},{},showTokenUsage=false,onMemory={memory=true})
        }}
        compose.waitUntil(5000) {compose.onAllNodesWithText("林舟接过信",substring=true).fetchSemanticsNodes().isNotEmpty()}
        capture("22-redesign-chat-light.png")
        compose.onNodeWithContentDescription("角色头像：林舟").assertDoesNotExist()
        compose.onNodeWithContentDescription("剧情记忆").performClick();assertTrue(memory)
        compose.runOnIdle {quiet.value=true;dark.value=true}
        capture("23-redesign-chat-dark-reading.png")
        compose.onNodeWithText("林舟接过信",substring=true).assertExists()
    }

    @Test fun memoryEditorDefaultsDoNotOptIntoPaidAutomaticCalls() {
        var automatic=true;var compact=true
        compose.setContent {RolePlayTheme(false) {StoryMemoryEditor(session,emptyList(),false,null,{},{},{},{_,_,a,c,_->automatic=a;compact=c})}}
        capture("24-redesign-memory.png")
        compose.onNodeWithText("固定记忆").performTextInput("约好明晚在西门见面。")
        compose.onNodeWithText("保存记忆").performClick()
        assertFalse(automatic);assertFalse(compact)
    }
}

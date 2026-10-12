package com.moyer.roleplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.media.ExifInterface
import android.util.Base64
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moyer.roleplay.core.*
import com.moyer.roleplay.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppearanceUiTest {
    @get:Rule val compose = createComposeRule()

    private fun image(color: Int): String = ByteArrayOutputStream().use { out ->
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color); compress(Bitmap.CompressFormat.PNG, 100, out); recycle()
        }
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    @Test fun repeatedAvatarUsesSameThumbnailAndDifferentImageStaysSeparate() {
        val red = image(Color.RED); val blue = image(Color.BLUE)
        val first = AvatarImages.decode(red)
        assertNotNull(first)
        assertSame(first, AvatarImages.decode(red))
        assertNotSame(first, AvatarImages.decode(blue))
        assertNull(AvatarImages.decode("invalid-avatar"))
    }

    @Test fun sessionListShowsCurrentCustomImageThenRestoredCardAndSnapshotFallback() {
        val card = CharacterCard(name = "头像测试", avatar = image(Color.RED))
        val session = ChatSession(title = card.name, characterId = card.id, characterSnapshot = appJson.encodeToString(card))
        val cards = mutableStateOf(listOf(card))
        compose.setContent { MaterialTheme { SessionScreen(listOf(session), cards.value, {}, {}, {}, {}) } }
        fun waitForColor(red: Boolean) {
            compose.waitUntil(5000) {
                val nodes = compose.onAllNodes(hasTestTag("session-avatar-${session.id}") and hasContentDescription("角色头像：头像测试"), useUnmergedTree = true).fetchSemanticsNodes()
                if (nodes.isEmpty()) false else {
                    val pixels = compose.onNodeWithTag("session-avatar-${session.id}", useUnmergedTree = true).captureToImage().toPixelMap()
                    val center = pixels[pixels.width / 2, pixels.height / 2]
                    if (red) center.red > 0.8f && center.blue < 0.1f else center.blue > 0.8f && center.red < 0.1f
                }
            }
        }
        waitForColor(true)
        compose.runOnIdle { cards.value = listOf(card.copy(customAvatar = image(Color.BLUE))) }
        waitForColor(false)
        compose.runOnIdle { cards.value = listOf(card) }
        waitForColor(true)
        compose.runOnIdle { cards.value = emptyList() }
        waitForColor(true)
    }

    @Test fun tokenMenuHidesUsageWhileChatRemainsFreeOfAvatars() {
        val card = CharacterCard(name = "阅读", avatar = image(Color.RED), customAvatar = image(Color.BLUE))
        val session = ChatSession(title = card.name, characterId = card.id, characterSnapshot = appJson.encodeToString(card))
        val visible = mutableStateOf(true)
        compose.setContent { MaterialTheme {
            ChatScreen(session, listOf(ChatMessage(sessionId = session.id, role = "assistant", content = "你好。", position = 0, inputTokens = 123, outputTokens = 45, cachedTokens = 6)),
                emptyList(), emptyList(), "", false, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, { _, _, _ -> }, {},
                emptyMap(), emptyList(), null, false, {}, {}, showTokenUsage = visible.value, onShowTokenUsage = { visible.value = it })
        } }
        compose.onNodeWithText("用量 123 / 45 tokens · 缓存命中 6").assertIsDisplayed()
        compose.onNodeWithContentDescription("角色头像：阅读").assertDoesNotExist()
        compose.onNodeWithContentDescription("会话菜单").performClick()
        compose.onNodeWithText("隐藏 Token 用量").performClick()
        compose.onNodeWithText("用量 123 / 45 tokens · 缓存命中 6").assertDoesNotExist()
        compose.onNodeWithText("你好。").assertIsDisplayed()
        compose.onNodeWithContentDescription("会话菜单").performClick()
        compose.onNodeWithText("显示 Token 用量").performClick()
        compose.onNodeWithText("用量 123 / 45 tokens · 缓存命中 6").assertIsDisplayed()
    }

    @Test fun galleryImageIsSizedAndOrientedThenPersistsThroughDatabaseAndBackup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File(context.cacheDir, "gallery-${newId()}.jpg")
        source.outputStream().use { out -> Bitmap.createBitmap(1200, 600, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE); compress(Bitmap.CompressFormat.JPEG, 95, out); recycle()
        } }
        ExifInterface(source.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes()
        }
        val name = "gallery-${newId()}.db"
        var database = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            val custom = AvatarImages.fromGallery(context, Uri.fromFile(source))
            val bytes = Base64.decode(custom, Base64.DEFAULT)
            val decoded = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertTrue(decoded.height <= 512); assertEquals(decoded.width * 2, decoded.height); decoded.recycle()
            val card = CharacterCard(name = "图库头像", avatar = image(Color.RED), customAvatar = custom)
            database.dao().saveCharacter(card)
            val id = ChatRepository(database).createSession(card, null)
            source.delete() // The app stores its own bytes, not a temporary gallery permission.
            database.close()
            database = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            val stored = database.dao().allCharacters().single()
            assertEquals(custom, stored.customAvatar)
            val exported = BackupCodec.decode(BackupCodec.encode(ChatRepository(database).export()))
            val imported = BackupCodec.remap(exported)
            assertEquals(custom, imported.characters.single().customAvatar)
            assertEquals(custom, sessionAvatar(database.dao().session(id)!!, stored))
            assertEquals(card.avatar, imported.characters.single().avatar)
        } finally { database.close(); context.deleteDatabase(name); source.delete() }
    }
}

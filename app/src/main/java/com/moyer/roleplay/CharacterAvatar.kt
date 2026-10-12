package com.moyer.roleplay

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun CharacterAvatar(name: String, avatar: String = "", modifier: Modifier = Modifier) {
    var image by remember(avatar) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(avatar) { image = withContext(Dispatchers.Default) { AvatarImages.decode(avatar)?.asImageBitmap() } }
    val bitmap = image
    if (bitmap != null) {
        Image(bitmap, contentDescription = "角色头像：$name", modifier = modifier.size(48.dp).clip(CircleShape), contentScale = ContentScale.Crop)
    } else {
        Box(modifier.size(48.dp).background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.secondaryContainer)), CircleShape).semantics { contentDescription = "默认头像：$name" }, contentAlignment = Alignment.Center) {
            Text(name.take(1).ifBlank { "角" }, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        }
    }
}

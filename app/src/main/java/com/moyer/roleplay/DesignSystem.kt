package com.moyer.roleplay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class ReadingAppearance(val fontSize: Int = 16, val quiet: Boolean = false)
internal val LocalReadingAppearance = staticCompositionLocalOf { ReadingAppearance() }

@Composable
internal fun RolePlayTheme(dark: Boolean, reading: ReadingAppearance = ReadingAppearance(), content: @Composable () -> Unit) {
    val colors = if (dark) darkColorScheme(
        primary=Color(0xFFC7BAEF), onPrimary=Color(0xFF30264C), primaryContainer=Color(0xFF393049), onPrimaryContainer=Color(0xFFE8DFFA),
        secondary=Color(0xFFB9CEC5), background=Color(0xFF17171C), onBackground=Color(0xFFE7E4EB),
        surface=Color(0xFF1C1C22), onSurface=Color(0xFFE7E4EB), onSurfaceVariant=Color(0xFFB5B1BE),
        surfaceContainer=Color(0xFF25242D), surfaceContainerLow=Color(0xFF202027), surfaceContainerHigh=Color(0xFF2D2B35),
        outlineVariant=Color(0xFF3A3744))
    else lightColorScheme(
        primary=Color(0xFF6F598C), onPrimary=Color.White, primaryContainer=Color(0xFFEBE3F5), onPrimaryContainer=Color(0xFF362647),
        secondary=Color(0xFF577267), background=Color(0xFFF7F6F8), onBackground=Color(0xFF29262F),
        surface=Color(0xFFFFFEFF), onSurface=Color(0xFF29262F), onSurfaceVariant=Color(0xFF777180),
        surfaceContainer=Color(0xFFF0EDF3), surfaceContainerLow=Color(0xFFFBF9FC), surfaceContainerHigh=Color(0xFFE8E4ED),
        outlineVariant=Color(0xFFE5E0E9))
    val typography=Typography().let { it.copy(
        headlineMedium=it.headlineMedium.copy(fontSize=28.sp, lineHeight=36.sp, fontWeight=FontWeight.SemiBold),
        titleLarge=it.titleLarge.copy(fontSize=21.sp, lineHeight=29.sp, fontWeight=FontWeight.SemiBold),
        bodyLarge=it.bodyLarge.copy(fontSize=16.sp,lineHeight=28.sp),
        bodyMedium=it.bodyMedium.copy(fontSize=14.sp,lineHeight=23.sp),
        bodySmall=it.bodySmall.copy(fontSize=12.sp,lineHeight=19.sp)) }
    MaterialTheme(colorScheme=colors, typography=typography,
        shapes=Shapes(small=RoundedCornerShape(12.dp),medium=RoundedCornerShape(20.dp),large=RoundedCornerShape(24.dp))) {
        Surface(modifier=Modifier.fillMaxSize(),color=colors.background) {
            CompositionLocalProvider(LocalReadingAppearance provides reading, content=content)
        }
    }
}

@Composable
internal fun PageHeading(title: String, subtitle: String, action: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top=26.dp,bottom=14.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title,style=MaterialTheme.typography.headlineMedium)
            Text(subtitle,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,
                modifier=Modifier.padding(top=5.dp))
        }
        action?.invoke()
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(text,style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant,
        modifier=Modifier.padding(top=12.dp,bottom=2.dp))
}

@Composable
internal fun AccentRule() {
    Spacer(Modifier.width(32.dp).height(3.dp).background(Brush.horizontalGradient(listOf(
        MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary.copy(alpha=0.25f))),RoundedCornerShape(2.dp)))
}

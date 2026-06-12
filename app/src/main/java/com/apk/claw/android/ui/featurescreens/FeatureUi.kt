package com.apk.claw.android.ui.featurescreens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 与各 Compose 页一致的深色 iOS 风配色
val FBg = Color(0xFF000000)
val FSurface = Color(0xFF1C1C1E)
val FSurface2 = Color(0xFF2C2C2E)
val FPrimary = Color(0xFF0A84FF)
val FText = Color(0xFFFFFFFF)
val FSub = Color(0xFF98989D)
val FMuted = Color(0xFF8E8E93)
val FBorder = Color(0xFF38383A)
val FSuccess = Color(0xFF30D158)
val FWarning = Color(0xFFFF9F0A)

/** 通用深色页脚手架：顶栏（返回 + 标题 + 可选右侧动作）+ 内容区 */
@Composable
fun FeatureScaffold(
    title: String,
    onBack: () -> Unit,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(FBg).statusBarsPadding().navigationBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(52.dp).padding(start = 6.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "‹",
                color = FText,
                fontSize = 26.sp,
                modifier = Modifier.size(44.dp).clickable(onClick = onBack).padding(start = 12.dp),
            )
            Text(title, color = FText, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (action != null) action()
        }
        content()
    }
}

@Composable
fun FSectionTitle(text: String) {
    Text(
        text,
        color = FSub,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
    )
}

@Composable
fun FCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = FSurface,
        border = BorderStroke(1.dp, FBorder),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp), content = content)
    }
}

@Composable
fun FEmpty(text: String) {
    Text(
        text,
        color = FMuted,
        fontSize = 13.sp,
        textAlign = TextAlign.Center,
        lineHeight = 18.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
    )
}

@Composable
fun FPill(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(5.dp), color = color.copy(alpha = 0.15f)) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            color = color,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

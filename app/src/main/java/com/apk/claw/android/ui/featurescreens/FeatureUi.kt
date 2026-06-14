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
import com.apk.claw.android.ui.compose.theme.OctopusColors

val FBg get() = OctopusColors.Background
val FSurface get() = OctopusColors.Surface
val FSurface2 get() = OctopusColors.SurfaceVariant
val FPrimary get() = OctopusColors.Primary
val FText get() = OctopusColors.TextPrimary
val FSub get() = OctopusColors.TextSecondary
val FMuted get() = OctopusColors.TextMuted
val FBorder get() = OctopusColors.Border
val FSuccess get() = OctopusColors.Success
val FWarning get() = OctopusColors.Warning

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
            modifier = Modifier.fillMaxWidth().height(54.dp).padding(start = 6.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "‹",
                color = FText,
                fontSize = 26.sp,
                modifier = Modifier.size(44.dp).clickable(onClick = onBack).padding(start = 12.dp),
            )
            Text(title, color = FText, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
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
        shape = RoundedCornerShape(18.dp),
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

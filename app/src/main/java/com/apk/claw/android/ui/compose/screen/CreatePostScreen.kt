package com.apk.claw.android.ui.compose.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.apk.claw.android.R
import com.apk.claw.android.account.AccountStore
import com.apk.claw.android.ui.compose.theme.OctopusBackground
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusShape
import com.apk.claw.android.ui.compose.theme.OctopusSpacing
import com.apk.claw.android.ui.compose.theme.OctopusType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 发帖编辑页(小红书式)。
 *
 * - 标题(必填,1-100 字) + 正文(选填,5000 字内) + 多图(最多 9 张,Photo Picker)
 * + 标签(选填)
 * - 图片先调 SquarePostApi.uploadImage 上传到服务端拿 URL,再随帖一起 publish
 * - 未登录直接拦截提示;发布中禁用按钮 + 转圈;成功后 onPublished 回调让上层关闭页面
 */
@Composable
fun CreatePostScreen(
    onBack: () -> Unit,
    onPublished: () -> Unit = {},
    onMessage: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var tag by remember { mutableStateOf("") }
    val imageUris = remember { mutableStateListOf<Uri>() }
    val uploadedUrls = remember { mutableStateListOf<String>() }
    var publishing by remember { mutableStateOf(false) }

    // Photo Picker:多选,上限 9 张
    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(9)
    ) { uris ->
        if (uris.isNotEmpty()) {
            // 控制总张数 ≤ 9
            val remain = 9 - imageUris.size
            imageUris.addAll(uris.take(remain))
        }
    }

    fun pickImages() {
        if (imageUris.size >= 9) {
            onMessage("最多 9 张图")
            return
        }
        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    fun publish() {
        val t = title.trim()
        if (t.isEmpty()) {
            onMessage("请填写标题")
            return
        }
        if (!AccountStore.isLoggedIn) {
            onMessage("请先登录")
            return
        }
        scope.launch {
            publishing = true
            try {
                // 1) 先上传所有图片(串行,避免并发打爆服务端限流)
                val urls = mutableListOf<String>()
                for (u in imageUris) {
                    val file = withContext(Dispatchers.IO) { uriToFile(context, u) } ?: continue
                    val r = SquarePostApi.uploadImage(file)
                    if (r.url.isNotBlank()) urls.add(r.url)
                }
                // 2) 发帖
                val r = SquarePostApi.publishPost(t, content.trim(), urls, tag.trim())
                if (r.ok) {
                    onMessage(r.message.ifBlank { "已提交,审核通过后就会出现在广场" })
                    onPublished()
                } else {
                    onMessage(r.reason.ifBlank { "发布失败" })
                }
            } catch (e: Exception) {
                onMessage("发布失败:${e.message ?: e::class.simpleName}")
            } finally {
                publishing = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OctopusBackground.pageBrush())
            .statusBarsPadding(),
    ) {
        // ── 顶栏 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = OctopusSpacing.sm, end = OctopusSpacing.lg, top = OctopusSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                    tint = OctopusColors.TextPrimary,
                )
            }
            Text(
                stringResource(R.string.create_post_title),
                modifier = Modifier.weight(1f),
                color = OctopusColors.TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Button(
                onClick = { publish() },
                enabled = !publishing && title.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = OctopusColors.Primary),
                contentPadding = PaddingValues(horizontal = OctopusSpacing.md, vertical = OctopusSpacing.xs),
            ) {
                if (publishing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = Color.White,
                    )
                    Spacer(Modifier.width(OctopusSpacing.xs))
                    Text(stringResource(R.string.create_post_publishing), color = Color.White, fontSize = OctopusType.body)
                } else {
                    Text(stringResource(R.string.create_post_publish), color = Color.White, fontSize = OctopusType.body)
                }
            }
        }

        // ── 表单 ──
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(OctopusSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(OctopusSpacing.md),
        ) {
            // 标题
            OutlinedTextField(
                value = title,
                onValueChange = { if (it.length <= 100) title = it },
                label = { Text(stringResource(R.string.create_post_title_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = fieldColors(),
                shape = OctopusShape.medium,
            )

            // 正文
            OutlinedTextField(
                value = content,
                onValueChange = { if (it.length <= 5000) content = it },
                label = { Text(stringResource(R.string.create_post_content_label)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                colors = fieldColors(),
                shape = OctopusShape.medium,
            )

            // 图片选择
            Text(
                stringResource(R.string.create_post_images_label),
                color = OctopusColors.TextSecondary,
                fontSize = OctopusType.label,
                fontWeight = FontWeight.SemiBold,
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.sm),
                contentPadding = PaddingValues(end = OctopusSpacing.lg),
            ) {
                items(imageUris) { uri ->
                    ImageThumb(uri = uri, onRemove = { imageUris.remove(uri) })
                }
                if (imageUris.size < 9) {
                    item { AddImageButton(remaining = 9 - imageUris.size, onClick = { pickImages() }) }
                }
            }

            // 标签
            OutlinedTextField(
                value = tag,
                onValueChange = { if (it.length <= 20) tag = it },
                label = { Text(stringResource(R.string.create_post_tag_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = fieldColors(),
                shape = OctopusShape.medium,
            )

            Spacer(Modifier.height(OctopusSpacing.lg))
        }
    }
}

@Composable
private fun ImageThumb(uri: Uri, onRemove: () -> Unit) {
    Box(
        modifier = Modifier
            .size(96.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(OctopusColors.SurfaceVariant),
    ) {
        AsyncImage(
            model = uri,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        IconButton(
            onClick = onRemove,
            modifier = Modifier.align(Alignment.TopEnd).size(24.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun AddImageButton(remaining: Int, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(96.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(OctopusBackground.cardSurface)
            .border(1.dp, OctopusBackground.cardBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = OctopusColors.TextSecondary)
            Spacer(Modifier.height(4.dp))
            Text(
                "$remaining/9",
                color = OctopusColors.TextMuted,
                fontSize = OctopusType.tag,
            )
        }
    }
}

@Composable
private fun fieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = OctopusBackground.cardSurface,
    unfocusedContainerColor = OctopusBackground.cardSurface,
    focusedIndicatorColor = OctopusColors.Primary,
    unfocusedIndicatorColor = OctopusColors.Border,
    focusedTextColor = OctopusColors.TextPrimary,
    unfocusedTextColor = OctopusColors.TextPrimary,
    focusedLabelColor = OctopusColors.Primary,
    unfocusedLabelColor = OctopusColors.TextMuted,
)

/** Uri → 临时 File(复制到 cacheDir,供上传用)。 */
private fun uriToFile(context: android.content.Context, uri: Uri): File? = runCatching {
    val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
    val ext = when (mime) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> "jpg"
    }
    val file = File.createTempFile("upload_", ".$ext", context.cacheDir)
    context.contentResolver.openInputStream(uri)?.use { input ->
        file.outputStream().use { output -> input.copyTo(output) }
    } ?: return null
    file
}.getOrNull()

package com.hermes.chatandroid.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hermes.chatandroid.data.BotAttachment
import com.hermes.chatandroid.net.AttachmentImageStore
import com.hermes.chatandroid.ui.theme.hermesColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 디코드 결과: 화면용 비트맵 + 원본 크기·용량. */
class LoadedImage(val bitmap: Bitmap, val width: Int, val height: Int, val bytes: Int)

private sealed interface ImageLoad {
    data object Loading : ImageLoad
    data object Unavailable : ImageLoad
    data class Ready(val image: LoadedImage) : ImageLoad
}

/** 긴 변이 [maxSide] 를 넘지 않게 줄여 디코드(메모리 보호). */
private fun decode(bytes: ByteArray, maxSide: Int): LoadedImage? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return null
    return LoadedImage(bmp, bounds.outWidth, bounds.outHeight, bytes.size)
}

@Composable
private fun rememberAttachmentImage(store: AttachmentImageStore, path: String, maxSide: Int): ImageLoad {
    val state by produceState<ImageLoad>(ImageLoad.Loading, path, maxSide) {
        value = try {
            val bytes = store.load(path)
            val img = bytes?.let { withContext(Dispatchers.Default) { decode(it, maxSide) } }
            if (img != null) ImageLoad.Ready(img) else ImageLoad.Unavailable
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ImageLoad.Unavailable
        }
    }
    return state
}

/**
 * 메시지에 붙은 이미지 — 잘리지 않게 원래 비율 그대로 보여 준다. 누르면 원본 뷰어.
 * 받을 수 없는 파일이면 [fallback](파일명 칩)을 그린다.
 */
@Composable
fun AttachmentImage(
    store: AttachmentImageStore,
    attachment: BotAttachment,
    modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit,
) {
    var open by remember(attachment.path) { mutableStateOf(false) }
    when (val load = rememberAttachmentImage(store, attachment.path, maxSide = 1600)) {
        ImageLoad.Unavailable -> fallback()
        ImageLoad.Loading -> Surface(
            modifier = modifier.size(width = 220.dp, height = 132.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.hermesColors.cardBorder),
        ) {
            Box(contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
        }
        is ImageLoad.Ready -> {
            val img = load.image
            Image(
                img.bitmap.asImageBitmap(),
                contentDescription = "첨부 사진 ${attachment.name} — 눌러서 원본 보기",
                contentScale = ContentScale.Fit,
                modifier = modifier
                    .widthIn(max = 360.dp)
                    .heightIn(max = 360.dp)
                    .aspectRatio(img.width.toFloat() / img.height.toFloat(), matchHeightConstraintsFirst = img.height > img.width)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { open = true },
            )
        }
    }
    if (open) ImageViewerDialog(store, attachment, onDismiss = { open = false })
}

/** 원본 전체 화면 보기 — 두 손가락 확대·이동, 두 번 탭으로 확대/원래대로. */
@Composable
fun ImageViewerDialog(store: AttachmentImageStore, attachment: BotAttachment, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val load = rememberAttachmentImage(store, attachment.path, maxSide = 4096)
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when (load) {
                ImageLoad.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
                ImageLoad.Unavailable -> Text("사진을 불러오지 못했습니다", Modifier.align(Alignment.Center), color = Color.White)
                is ImageLoad.Ready -> Image(
                    load.image.bitmap.asImageBitmap(),
                    contentDescription = "원본 사진 ${attachment.name}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 8f)
                                offset = if (scale == 1f) Offset.Zero else offset + pan
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = {
                                if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 3f
                            })
                        }
                        .graphicsLayer {
                            scaleX = scale; scaleY = scale
                            translationX = offset.x; translationY = offset.y
                        },
                )
            }
            Column(Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(16.dp)) {
                Text(attachment.name, color = Color.White, style = MaterialTheme.typography.labelLarge)
                (load as? ImageLoad.Ready)?.image?.let {
                    Text(
                        "원본 ${it.width}×${it.height} · ${it.bytes / 1024}KB",
                        color = Color.White.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(8.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f)),
            ) {
                Icon(Icons.Rounded.Close, contentDescription = "닫기", tint = Color.White)
            }
        }
    }
}

package app.momoding.feature.taskdetail

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun GeneratedImagePreviewDialog(
    preview: GeneratedImagePreviewUiModel,
    onAction: (TaskDetailAction) -> Unit,
) {
    val bitmap = remember(preview.attachmentId, preview.imageBytes) {
        BitmapFactory.decodeByteArray(preview.imageBytes, 0, preview.imageBytes.size)?.asImageBitmap()
    }
    var scale by remember(preview.attachmentId) { mutableStateOf(1f) }
    var offset by remember(preview.attachmentId) { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { _, zoomChange, panChange, _ ->
        val nextScale = (scale * zoomChange).coerceIn(1f, 5f)
        scale = nextScale
        offset = if (nextScale == 1f) Offset.Zero else offset + panChange
    }
    Dialog(
        onDismissRequest = { onAction(TaskDetailAction.DismissGeneratedImage) },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().testTag("generated-image-preview"),
            color = Color.Black,
        ) {
            Box(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = preview.displayName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            }
                            .pointerInput(preview.attachmentId) {
                                detectTapGestures(
                                    onDoubleTap = {
                                        if (scale > 1f) {
                                            scale = 1f
                                            offset = Offset.Zero
                                        } else {
                                            scale = 2f
                                        }
                                    },
                                )
                            }
                            .transformable(transformState)
                            .testTag("generated-image-preview-content"),
                    )
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.68f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Generated image",
                        modifier = Modifier.weight(1f),
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(
                        onClick = { onAction(TaskDetailAction.DismissGeneratedImage) },
                        modifier = Modifier.testTag("dismiss-generated-image-preview"),
                    ) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close image", tint = Color.White)
                    }
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.68f))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    TextButton(
                        onClick = {
                            onAction(TaskDetailAction.CopyGeneratedImage(preview.attachmentId))
                        },
                        modifier = Modifier.testTag("copy-generated-image"),
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, tint = Color.White)
                        Text("Copy", color = Color.White, modifier = Modifier.padding(start = 8.dp))
                    }
                    TextButton(
                        onClick = {
                            onAction(TaskDetailAction.DownloadGeneratedImage(preview.attachmentId))
                        },
                        modifier = Modifier.testTag("download-generated-image"),
                    ) {
                        Icon(Icons.Outlined.Download, contentDescription = null, tint = Color.White)
                        Text("Download", color = Color.White, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

package com.openfolio.feature.review

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.openfolio.core.ui.theme.LocalOpenFolioColors
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun calculateInSampleSize(
    options: BitmapFactory.Options,
    reqWidth: Int,
    reqHeight: Int,
): Int {
    val height = options.outHeight
    val width = options.outWidth
    var inSampleSize = 1

    if (height > reqHeight || width > reqWidth) {
        val halfHeight = height / 2
        val halfWidth = width / 2

        while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
            inSampleSize *= 2
        }
    }

    return inSampleSize.coerceAtLeast(1)
}

fun decodeSampledBitmapFromFile(
    filePath: String,
    reqWidth: Int = 1080,
    reqHeight: Int = 1920,
): Bitmap? {
    val file = File(filePath)
    if (!file.exists() || !file.canRead()) return null

    val options = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    BitmapFactory.decodeFile(filePath, options)
    if (options.outWidth <= 0 || options.outHeight <= 0) {
        return null
    }

    options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
    options.inJustDecodeBounds = false
    return BitmapFactory.decodeFile(filePath, options)
}

fun renderPdfFirstPage(
    filePath: String,
    maxWidth: Int = 1440,
    maxHeight: Int = 1920,
): Triple<Bitmap, PdfRenderer, ParcelFileDescriptor> {
    val file = File(filePath)
    if (!file.exists() || !file.canRead()) {
        throw IllegalArgumentException("PDF file does not exist or is unreadable: $filePath")
    }

    val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    val renderer = try {
        PdfRenderer(pfd)
    } catch (e: Exception) {
        pfd.close()
        throw e
    }

    try {
        if (renderer.pageCount == 0) {
            renderer.close()
            pfd.close()
            throw IllegalArgumentException("PDF has no pages: $filePath")
        }
        val page = renderer.openPage(0)
        try {
            val pageWidth = page.width
            val pageHeight = page.height

            val scale = minOf(
                maxWidth.toFloat() / pageWidth.coerceAtLeast(1),
                maxHeight.toFloat() / pageHeight.coerceAtLeast(1),
                2f,
            ).coerceAtLeast(1f)

            val bitmapWidth = (pageWidth * scale).toInt().coerceAtLeast(1)
            val bitmapHeight = (pageHeight * scale).toInt().coerceAtLeast(1)

            val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

            return Triple(bitmap, renderer, pfd)
        } finally {
            page.close()
        }
    } catch (e: Exception) {
        renderer.close()
        pfd.close()
        throw e
    }
}

@Composable
fun PdfPreview(
    filePath: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpenFolioColors.current
    var isLoading by remember(filePath) { mutableStateOf(true) }
    var errorMessage by remember(filePath) { mutableStateOf<String?>(null) }
    var bitmap by remember(filePath) { mutableStateOf<Bitmap?>(null) }
    var pdfRenderer by remember(filePath) { mutableStateOf<PdfRenderer?>(null) }
    var parcelFd by remember(filePath) { mutableStateOf<ParcelFileDescriptor?>(null) }

    DisposableEffect(filePath) {
        onDispose {
            bitmap?.let { if (!it.isRecycled) it.recycle() }
            pdfRenderer?.close()
            parcelFd?.close()
        }
    }

    LaunchedEffect(filePath) {
        isLoading = true
        errorMessage = null
        try {
            val (renderedBitmap, renderer, pfd) = withContext(Dispatchers.IO) {
                renderPdfFirstPage(filePath)
            }
            bitmap = renderedBitmap
            pdfRenderer = renderer
            parcelFd = pfd
        } catch (_: Exception) {
            errorMessage = "Preview unavailable"
        } finally {
            isLoading = false
        }
    }

    when {
        isLoading -> {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = colors.brand,
                    strokeWidth = 2.dp,
                )
            }
        }
        errorMessage != null || bitmap == null -> {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = errorMessage ?: "Preview unavailable",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.muted,
                )
            }
        }
        else -> {
            ZoomableBitmapPreview(
                bitmap = bitmap!!,
                modifier = modifier,
            )
        }
    }
}

@Composable
fun ImagePreview(
    filePath: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpenFolioColors.current
    var isLoading by remember(filePath) { mutableStateOf(true) }
    var errorMessage by remember(filePath) { mutableStateOf<String?>(null) }
    var bitmap by remember(filePath) { mutableStateOf<Bitmap?>(null) }

    DisposableEffect(filePath) {
        onDispose {
            bitmap?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    LaunchedEffect(filePath) {
        isLoading = true
        errorMessage = null
        try {
            val decoded = withContext(Dispatchers.IO) {
                decodeSampledBitmapFromFile(filePath)
            }
            if (decoded != null) {
                bitmap = decoded
            } else {
                errorMessage = "Preview unavailable"
            }
        } catch (_: Exception) {
            errorMessage = "Preview unavailable"
        } finally {
            isLoading = false
        }
    }

    when {
        isLoading -> {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = colors.brand,
                    strokeWidth = 2.dp,
                )
            }
        }
        errorMessage != null || bitmap == null -> {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = errorMessage ?: "Preview unavailable",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.muted,
                )
            }
        }
        else -> {
            ZoomableBitmapPreview(
                bitmap = bitmap!!,
                modifier = modifier,
            )
        }
    }
}

@Composable
fun ZoomableBitmapPreview(
    bitmap: Bitmap,
    modifier: Modifier = Modifier,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    offset = if (scale == 1f) Offset.Zero else offset + pan
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (!bitmap.isRecycled) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Document preview",
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    ),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

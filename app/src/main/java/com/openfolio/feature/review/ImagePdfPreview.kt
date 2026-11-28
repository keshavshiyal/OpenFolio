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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openfolio.core.data.local.LocalFileCrypto
import com.openfolio.core.ui.theme.LocalOpenFolioColors
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.CancellationException
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
    fileCrypto: LocalFileCrypto? = null,
): Bitmap? {
    val file = File(filePath)
    if (!file.exists() || !file.canRead()) return null

    fun openStream() = fileCrypto?.openPlaintextInputStream(file) ?: FileInputStream(file)

    val options = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    openStream().use { stream ->
        BitmapFactory.decodeStream(stream, null, options)
    }
    if (options.outWidth <= 0 || options.outHeight <= 0) {
        return null
    }

    options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
    options.inJustDecodeBounds = false
    return openStream().use { stream ->
        BitmapFactory.decodeStream(stream, null, options)
    }
}

class PdfResourceHolder(
    val bitmap: Bitmap,
    val renderer: PdfRenderer? = null,
    val pfd: ParcelFileDescriptor? = null,
) {
    fun close() {
        if (!bitmap.isRecycled) bitmap.recycle()
        try { renderer?.close() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
    }
}

fun renderPdfFirstPage(
    filePath: String,
    password: String? = null,
    maxWidth: Int = 1440,
    maxHeight: Int = 1920,
    fileCrypto: LocalFileCrypto? = null,
    scratchDir: File? = null,
): PdfResourceHolder {
    val sourceFile = File(filePath)
    if (!sourceFile.exists() || !sourceFile.canRead()) {
        throw IllegalArgumentException("PDF file does not exist or is unreadable: $filePath")
    }

    val tempScratch = scratchDir ?: sourceFile.parentFile ?: File(".")
    return if (fileCrypto != null) {
        fileCrypto.withDecryptedTempFile(sourceFile, tempScratch, "pdf") { readableFile ->
            renderPdfFirstPageFromReadableFile(readableFile, filePath, password, maxWidth, maxHeight)
        }
    } else {
        renderPdfFirstPageFromReadableFile(sourceFile, filePath, password, maxWidth, maxHeight)
    }
}

private fun renderPdfFirstPageFromReadableFile(
    readableFile: File,
    filePath: String,
    password: String?,
    maxWidth: Int,
    maxHeight: Int,
): PdfResourceHolder {
    if (password.isNullOrBlank()) {
        val pfd = runCatching {
            ParcelFileDescriptor.open(readableFile, ParcelFileDescriptor.MODE_READ_ONLY)
        }.getOrNull()
        if (pfd != null) {
            try {
                val renderer = PdfRenderer(pfd)
                try {
                    if (renderer.pageCount > 0) {
                        val page = renderer.openPage(0)
                        try {
                            val scale = minOf(
                                maxWidth.toFloat() / page.width.coerceAtLeast(1),
                                maxHeight.toFloat() / page.height.coerceAtLeast(1),
                                2f,
                            ).coerceAtLeast(1f)
                            val bitmapWidth = (page.width * scale).toInt().coerceAtLeast(1)
                            val bitmapHeight = (page.height * scale).toInt().coerceAtLeast(1)
                            val bitmap = Bitmap.createBitmap(
                                bitmapWidth,
                                bitmapHeight,
                                Bitmap.Config.ARGB_8888,
                            )
                            val canvas = Canvas(bitmap)
                            canvas.drawColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            return PdfResourceHolder(bitmap, null, null)
                        } finally {
                            page.close()
                        }
                    }
                } finally {
                    try { renderer.close() } catch (_: Exception) {}
                }
            } catch (_: Exception) {
            } finally {
                try { pfd.close() } catch (_: Exception) {}
            }
        }
    }

    // PDFBox fallback (handles password-protected PDFs and native renderer failures)
    val doc = if (password.isNullOrBlank()) {
        com.tom_roush.pdfbox.pdmodel.PDDocument.load(readableFile)
    } else {
        com.tom_roush.pdfbox.pdmodel.PDDocument.load(readableFile, password)
    }
    return doc.use { pdDoc ->
        if (pdDoc.numberOfPages == 0) {
            throw IllegalArgumentException("PDF has no pages: $filePath")
        }
        val pdfBoxRenderer = com.tom_roush.pdfbox.rendering.PDFRenderer(pdDoc)
        val bitmap = pdfBoxRenderer.renderImageWithDPI(0, 150f)
        PdfResourceHolder(bitmap, null, null)
    }
}

@Composable
fun PdfPreview(
    filePath: String,
    password: String? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpenFolioColors.current
    val appContext = LocalContext.current.applicationContext
    val fileCrypto = remember(appContext) { LocalFileCrypto(appContext) }
    val scratchDir = remember(appContext) { File(appContext.cacheDir, LocalFileCrypto.DEC_TEMP_DIR_NAME) }
    var resourceHolder by remember(filePath, password) { mutableStateOf<PdfResourceHolder?>(null) }
    var isLoading by remember(filePath, password) { mutableStateOf(true) }
    var errorMessage by remember(filePath, password) { mutableStateOf<String?>(null) }

    DisposableEffect(filePath, password) {
        onDispose {
            resourceHolder?.close()
            resourceHolder = null
        }
    }

    LaunchedEffect(filePath, password) {
        isLoading = true
        errorMessage = null
        var rendered: PdfResourceHolder? = null
        try {
            rendered = withContext(Dispatchers.IO) {
                renderPdfFirstPage(
                    filePath = filePath,
                    password = password,
                    fileCrypto = fileCrypto,
                    scratchDir = scratchDir,
                )
            }
            resourceHolder?.close()
            resourceHolder = rendered
            rendered = null
        } catch (ce: CancellationException) {
            rendered?.close()
            throw ce
        } catch (_: Exception) {
            rendered?.close()
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
        errorMessage != null || resourceHolder == null -> {
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
                bitmap = resourceHolder!!.bitmap,
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
    val appContext = LocalContext.current.applicationContext
    val fileCrypto = remember(appContext) { LocalFileCrypto(appContext) }
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
        var decoded: Bitmap? = null
        try {
            decoded = withContext(Dispatchers.IO) {
                decodeSampledBitmapFromFile(filePath, fileCrypto = fileCrypto)
            }
            if (decoded != null) {
                bitmap?.let { if (!it.isRecycled) it.recycle() }
                bitmap = decoded
                decoded = null
            } else {
                errorMessage = "Preview unavailable"
            }
        } catch (ce: CancellationException) {
            decoded?.let { if (!it.isRecycled) it.recycle() }
            throw ce
        } catch (_: Exception) {
            decoded?.let { if (!it.isRecycled) it.recycle() }
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
private fun ZoomableBitmapPreview(
    bitmap: Bitmap,
    modifier: Modifier = Modifier,
) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(bitmap) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 4f)
                    scale = newScale
                    if (newScale > 1f) {
                        offset += pan
                    } else {
                        offset = Offset.Zero
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Document preview",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                ),
        )
    }
}

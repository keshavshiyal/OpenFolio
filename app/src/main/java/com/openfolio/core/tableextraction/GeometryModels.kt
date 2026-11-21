package com.openfolio.core.tableextraction

import java.io.File

/**
 * Geometric bounding box for extracted text in normalized or point coordinates.
 */
data class BoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun overlapsVertically(other: BoundingBox, toleranceFactor: Float = 0.5f): Boolean {
        val minHeight = minOf(height, other.height).coerceAtLeast(1f)
        val tolerance = minHeight * toleranceFactor
        return top - tolerance <= other.bottom && bottom + tolerance >= other.top
    }
}

/**
 * An individual word or token extracted with its geometric bounding box.
 */
data class ExtractedWord(
    val text: String,
    val bbox: BoundingBox,
    val pageIndex: Int,
)

/**
 * A group of words forming a single horizontal line or row on a specific page.
 */
data class ExtractedRow(
    val words: List<ExtractedWord>,
    val pageIndex: Int,
    val top: Float,
    val bottom: Float,
) {
    val centerY: Float get() = (top + bottom) / 2f
}

/**
 * Represents a structured table candidate detected from geometric clustering.
 */
data class ExtractedTable(
    val tableIndex: Int,
    val pageIndices: List<Int>,
    val headers: List<String>,
    val rows: List<List<String>>,
    val sourceFileName: String,
    val csvFile: File? = null,
)

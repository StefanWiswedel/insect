package dk.biomon.insect.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.ContentScale
import dk.biomon.insect.MaskSnapshot
import dk.biomon.insect.PreviewFrame
import androidx.compose.foundation.Image

/**
 * The trigger mask, drawn over the preview.
 *
 * This is the only way to tell, in the field, whether the trap is looking at the
 * bait or at a waving branch -- so it draws what actually triggered, not a
 * prettier abstraction of it.
 *
 * One small [Bitmap] the size of the mask is kept and scaled up by the draw,
 * rather than a full-size bitmap rebuilt per update: the snapshot arrives a few
 * times a second and allocating a screen-sized bitmap at that rate would churn
 * the heap for no visible gain.
 */
@Composable
fun MaskOverlay(snapshot: MaskSnapshot?, modifier: Modifier = Modifier) {
    if (snapshot == null || snapshot.width <= 0 || snapshot.height <= 0) return

    val holder = remember { BitmapHolder() }
    val bitmap = remember(snapshot) { holder.update(snapshot) }

    Canvas(modifier = modifier) {
        // Letterbox rather than stretch: a mask drawn at the wrong aspect ratio
        // would put the boxes somewhere the insects are not.
        val scale = minOf(size.width / snapshot.width, size.height / snapshot.height)
        val drawnWidth = snapshot.width * scale
        val drawnHeight = snapshot.height * scale
        val left = (size.width - drawnWidth) / 2f
        val top = (size.height - drawnHeight) / 2f

        drawImage(
            image = bitmap.asImageBitmap(),
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(snapshot.width, snapshot.height),
            dstOffset = IntOffset(left.toInt(), top.toInt()),
            dstSize = IntSize(drawnWidth.toInt(), drawnHeight.toInt()),
        )

        for (blob in snapshot.blobs) {
            drawRect(
                color = StateGreen,
                topLeft = Offset(left + blob.left * scale, top + blob.top * scale),
                size = Size(blob.width * scale, blob.height * scale),
                style = Stroke(width = 2f),
            )
        }
    }
}

/** Reuses one bitmap while the mask geometry is unchanged, which is almost always. */
private class BitmapHolder {
    private var bitmap: Bitmap? = null
    private var pixels = IntArray(0)

    fun update(snapshot: MaskSnapshot): Bitmap {
        val existing = bitmap
        val target = if (
            existing != null &&
            existing.width == snapshot.width &&
            existing.height == snapshot.height
        ) {
            existing
        } else {
            Bitmap.createBitmap(snapshot.width, snapshot.height, Bitmap.Config.ARGB_8888)
                .also { bitmap = it }
        }
        if (pixels.size != snapshot.mask.size) pixels = IntArray(snapshot.mask.size)
        for (i in snapshot.mask.indices) {
            pixels[i] = if (snapshot.mask[i].toInt() != 0) MASK_ARGB else 0
        }
        target.setPixels(pixels, 0, snapshot.width, 0, 0, snapshot.width, snapshot.height)
        return target
    }

    private companion object {
        /** StateGreen at a third alpha: visible over the preview, not opaque. */
        const val MASK_ARGB = 0x5557A05B
    }
}

/**
 * Draws a grayscale [PreviewFrame] as an image.
 *
 * Kept next to the mask overlay because the two must agree about scaling: both
 * letterbox into the same box, so a blob box lands on the pixels that produced
 * it.
 */
@Composable
fun LumaImage(preview: PreviewFrame, modifier: Modifier = Modifier) {
    val holder = remember { LumaBitmapHolder() }
    val bitmap = remember(preview) { holder.update(preview) }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}

/** Reuses one bitmap while the preview geometry is unchanged, which is always. */
private class LumaBitmapHolder {
    private var bitmap: Bitmap? = null
    private var pixels = IntArray(0)

    fun update(preview: PreviewFrame): Bitmap {
        val existing = bitmap
        val target = if (
            existing != null &&
            existing.width == preview.width &&
            existing.height == preview.height
        ) {
            existing
        } else {
            Bitmap.createBitmap(preview.width, preview.height, Bitmap.Config.ARGB_8888)
                .also { bitmap = it }
        }
        if (pixels.size != preview.luma.size) pixels = IntArray(preview.luma.size)
        for (i in preview.luma.indices) {
            val v = preview.luma[i].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        target.setPixels(pixels, 0, preview.width, 0, 0, preview.width, preview.height)
        return target
    }
}

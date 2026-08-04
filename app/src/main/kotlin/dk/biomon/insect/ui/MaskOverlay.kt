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
import dk.biomon.insect.core.image.LumaPreview
import dk.biomon.insect.PreviewFrame
import androidx.compose.foundation.Image

/**
 * The trigger mask, drawn over the preview.
 *
 * This is the only way to tell, in the field, whether the trap is looking at the
 * bait or at a waving branch -- so it draws what actually triggered, not a
 * prettier abstraction of it.
 *
 * The [Bitmap] is built at mask resolution and scaled up by the draw, rather
 * than at screen resolution: the snapshot arrives a few times a second and
 * allocating a screen-sized bitmap at that rate would churn the heap for no
 * visible gain. It is drawn *over* the preview, so its background must stay
 * transparent -- only flagged pixels get an alpha.
 */
@Composable
fun MaskOverlay(snapshot: MaskSnapshot?, modifier: Modifier = Modifier) {
    if (snapshot == null || snapshot.width <= 0 || snapshot.height <= 0) return

    // Fresh bitmap per snapshot, for the same reason as the preview above.
    val bitmap = remember(snapshot) {
        val pixels = IntArray(snapshot.width * snapshot.height)
        for (i in snapshot.mask.indices) {
            pixels[i] = if (snapshot.mask[i].toInt() != 0) MASK_ARGB else 0
        }
        Bitmap.createBitmap(pixels, snapshot.width, snapshot.height, Bitmap.Config.ARGB_8888)
    }

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

/**
 * Draws a grayscale [PreviewFrame] as an image.
 *
 * A **fresh, immutable** Bitmap is built per frame rather than mutating a
 * retained one. Mutating a Bitmap in place and handing the same instance back to
 * Compose is how the preview ends up frozen or blank: the composition sees an
 * unchanged object, and the render pipeline is entitled to keep drawing the
 * texture it already uploaded. A new Bitmap each time removes the question. At
 * 320x240 and a few hertz the allocation is nothing, and it happens only while
 * somebody is looking at the screen.
 */
@Composable
fun LumaImage(preview: PreviewFrame, modifier: Modifier = Modifier) {
    val bitmap = remember(preview) {
        val pixels = IntArray(preview.width * preview.height)
        LumaPreview.toArgb(preview.luma, pixels, pixels.size)
        Bitmap.createBitmap(pixels, preview.width, preview.height, Bitmap.Config.ARGB_8888)
    }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}

/** StateGreen at a third alpha: visible over the preview, not opaque. */
private const val MASK_ARGB = 0x5557A05B

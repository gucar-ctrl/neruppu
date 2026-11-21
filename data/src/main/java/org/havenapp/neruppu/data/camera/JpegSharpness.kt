package org.havenapp.neruppu.data.camera

import android.graphics.BitmapFactory

/**
 * Scores a JPEG photo with [LaplacianSharpness]. The photo is decoded at half size to keep the work cheap;
 * fine sensor noise counts for less at that scale.
 */
object JpegSharpness {

    private const val DECODE_SAMPLE_SIZE = 2

    /** Higher is sharper. Returns 0.0 when the bytes are not a decodable image. */
    fun score(jpeg: ByteArray): Double {
        val options = BitmapFactory.Options().apply { inSampleSize = DECODE_SAMPLE_SIZE }
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options) ?: return 0.0
        return try {
            val width = bitmap.width
            val height = bitmap.height
            val argb = IntArray(width * height)
            bitmap.getPixels(argb, 0, width, 0, 0, width, height)
            LaplacianSharpness.variance(LaplacianSharpness.lumaFromArgb(argb), width, height)
        } finally {
            bitmap.recycle()
        }
    }
}

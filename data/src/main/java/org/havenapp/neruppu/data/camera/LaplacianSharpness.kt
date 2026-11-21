package org.havenapp.neruppu.data.camera

/**
 * Sharpness measure for grayscale images. Kept free of Android types so it runs in plain JVM unit tests.
 *
 * A sharp edge changes brightness quickly from one pixel to the next, a blurred edge does not.
 * The Laplacian (a pixel minus the average of its four neighbours) picks up exactly that change,
 * and the variance of the Laplacian over the image is high for sharp frames and low for blurred ones.
 * The number has no absolute meaning: compare frames of the same scene at the same resolution only.
 */
object LaplacianSharpness {

    /** Luma (0..255) of packed ARGB pixels, using the ITU-R BT.601 weights 0.299, 0.587 and 0.114. */
    fun lumaFromArgb(argb: IntArray): IntArray = IntArray(argb.size) { i ->
        val pixel = argb[i]
        (((pixel shr 16) and 0xFF) * 299 + ((pixel shr 8) and 0xFF) * 587 + (pixel and 0xFF) * 114) / 1000
    }

    /**
     * Variance of the four-neighbour Laplacian over the inner pixels of a row-major grayscale image.
     * Returns 0.0 for images smaller than 3x3, which have no inner pixel.
     */
    fun variance(luma: IntArray, width: Int, height: Int): Double {
        require(luma.size == width * height) { "luma has ${luma.size} values, expected ${width * height}" }
        if (width < 3 || height < 3) return 0.0
        var count = 0L
        var sum = 0.0
        var sumOfSquares = 0.0
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val i = row + x
                val laplacian = (4 * luma[i] - luma[i - 1] - luma[i + 1] - luma[i - width] - luma[i + width]).toDouble()
                sum += laplacian
                sumOfSquares += laplacian * laplacian
                count++
            }
        }
        val mean = sum / count
        return sumOfSquares / count - mean * mean
    }
}

package com.example.converter

/**
 * Utility to convert ARGB image pixels to YUV420 color formats (NV12 and I420)
 * compatible with Android's MediaCodec video encoders.
 */
object YuvConverter {

    /**
     * Converts ARGB int array to NV12 (YUV420SemiPlanar).
     * Output format: Y plane followed by interleaved UV plane.
     */
    fun argbToNv12(argb: IntArray, width: Int, height: Int, outNv12: ByteArray) {
        val frameSize = width * height
        var yIndex = 0
        var uvIndex = frameSize

        var r: Int
        var g: Int
        var b: Int
        var y: Int
        var u: Int
        var v: Int
        var index = 0

        for (j in 0 until height) {
            for (i in 0 until width) {
                val pixel = argb[index++]
                r = (pixel shr 16) and 0xff
                g = (pixel shr 8) and 0xff
                b = pixel and 0xff

                // Standard BT.601 RGB to YUV conversion formula
                y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128

                outNv12[yIndex++] = (if (y < 0) 0 else if (y > 255) 255 else y).toByte()

                // Subsample U and V 2x2
                if (j % 2 == 0 && i % 2 == 0 && uvIndex < outNv12.size - 1) {
                    outNv12[uvIndex++] = (if (u < 0) 0 else if (u > 255) 255 else u).toByte()
                    outNv12[uvIndex++] = (if (v < 0) 0 else if (v > 255) 255 else v).toByte()
                }
            }
        }
    }

    /**
     * Converts ARGB int array to I420 (YUV420Planar).
     * Output format: Y plane followed by U plane, followed by V plane.
     */
    fun argbToI420(argb: IntArray, width: Int, height: Int, outI420: ByteArray) {
        val frameSize = width * height
        val qFrameSize = frameSize / 4
        var yIndex = 0
        var uIndex = frameSize
        var vIndex = frameSize + qFrameSize

        var r: Int
        var g: Int
        var b: Int
        var y: Int
        var u: Int
        var v: Int
        var index = 0

        for (j in 0 until height) {
            for (i in 0 until width) {
                val pixel = argb[index++]
                r = (pixel shr 16) and 0xff
                g = (pixel shr 8) and 0xff
                b = pixel and 0xff

                y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128

                outI420[yIndex++] = (if (y < 0) 0 else if (y > 255) 255 else y).toByte()

                if (j % 2 == 0 && i % 2 == 0) {
                    if (uIndex < frameSize + qFrameSize) {
                        outI420[uIndex++] = (if (u < 0) 0 else if (u > 255) 255 else u).toByte()
                    }
                    if (vIndex < outI420.size) {
                        outI420[vIndex++] = (if (v < 0) 0 else if (v > 255) 255 else v).toByte()
                    }
                }
            }
        }
    }
}

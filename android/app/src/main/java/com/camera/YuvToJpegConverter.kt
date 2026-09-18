package com.camera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

object YuvToJpegConverter {

    fun convert(
        image: ImageProxy,
        quality: Int = 80
    ): ByteArray? {

        val width = image.width
        val height = image.height

        if (image.format != ImageFormat.YUV_420_888) {
            return null
        }

        val nv21 = yuv420888ToNv21(image)

        val yuvImage = YuvImage(
            nv21,
            ImageFormat.NV21,
            width,
            height,
            null
        )

        val output = ByteArrayOutputStream()

        val success = yuvImage.compressToJpeg(
            Rect(
                0,
                0,
                width,
                height
            ),
            quality,
            output
        )

        if (!success) {
            return null
        }

        return output.toByteArray()
    }

    private fun yuv420888ToNv21(
        image: ImageProxy
    ): ByteArray {

        val width = image.width
        val height = image.height

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride

        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        val nv21 = ByteArray(
            width * height +
                    2 * (width / 2) * (height / 2)
        )

        var outputOffset = 0

        /*
         * Y
         */

        for (row in 0 until height) {

            val rowStart = row * yRowStride

            for (col in 0 until width) {

                val index = rowStart + col

                nv21[outputOffset++] =
                    yBuffer.get(index)
            }
        }

        /*
         * VU
         */

        val chromaHeight = height / 2
        val chromaWidth = width / 2

        for (row in 0 until chromaHeight) {

            val uRowStart = row * uRowStride
            val vRowStart = row * vRowStride

            for (col in 0 until chromaWidth) {

                val uIndex =
                    uRowStart + col * uPixelStride

                val vIndex =
                    vRowStart + col * vPixelStride

                nv21[outputOffset++] =
                    vBuffer.get(vIndex)

                nv21[outputOffset++] =
                    uBuffer.get(uIndex)
            }
        }

        return nv21
    }
}
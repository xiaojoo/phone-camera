package com.camera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

object YuvToJpegConverter {

    /*
     * rotationDegrees 只认 0/90/180/270，顺时针。
     *
     * 旋转是直接折进 NV21 的收集循环里的：读相机平面时就把像素写到
     * 旋转后的目标位置，所以只有一趟拷贝，不存在「先正着拼一遍再转」。
     * 90/270 会交换输出的宽高（960x720 出来是 720x960）。
     *
     * 不走 CameraX 的 targetRotation：ImageAnalysis 只把角度写在
     * ImageInfo 里，缓冲区本身永远是传感器方向，转了等于没转。
     */
    fun convert(
        image: ImageProxy,
        quality: Int = 80,
        rotationDegrees: Int = 0
    ): ByteArray? {

        val width = image.width
        val height = image.height

        if (image.format != ImageFormat.YUV_420_888) {
            return null
        }

        val transposed =
            rotationDegrees == 90 ||
                    rotationDegrees == 270

        val outWidth = if (transposed) height else width
        val outHeight = if (transposed) width else height

        val nv21 = yuv420888ToNv21(
            image,
            rotationDegrees,
            outWidth,
            outHeight
        )

        val yuvImage = YuvImage(
            nv21,
            ImageFormat.NV21,
            outWidth,
            outHeight,
            null
        )

        val output = ByteArrayOutputStream()

        val success = yuvImage.compressToJpeg(
            Rect(
                0,
                0,
                outWidth,
                outHeight
            ),
            quality,
            output
        )

        if (!success) {
            return null
        }

        return output.toByteArray()
    }

    /*
     * 目标下标按「每个源行是一条等差数列」来推：
     * 给定该源行在目标里的起点 dStart 和步长 dStep，行内逐列加步长即可。
     * 这样四种角度共用一套循环，不需要每个像素再判一次角度。
     *
     * 源像素 (col,row) 顺时针旋转后的落点：
     *   0    (col, row)
     *   180  (W-1-col, H-1-row)
     *   90   (H-1-row, col)          输出 W'=H、H'=W
     *   270  (row, W-1-col)          输出 W'=H、H'=W
     */
    private fun yuv420888ToNv21(
        image: ImageProxy,
        rotation: Int,
        outWidth: Int,
        outHeight: Int
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

        val transposed =
            rotation == 90 ||
                    rotation == 270

        val reversed =
            rotation == 180 ||
                    rotation == 270

        val nv21 = ByteArray(
            outWidth * outHeight +
                    2 * (outWidth / 2) * (outHeight / 2)
        )

        /*
         * Y
         */
        for (row in 0 until height) {

            val srcRowStart = row * yRowStride

            val dStart: Int
            val dStep: Int

            if (transposed) {
                dStart =
                    if (rotation == 90) height - 1 - row
                    else (width - 1) * outWidth + row
                dStep = if (rotation == 90) outWidth else -outWidth
            } else {
                dStart =
                    if (reversed) (height - 1 - row) * outWidth + width - 1
                    else row * outWidth
                dStep = if (reversed) -1 else 1
            }

            var d = dStart

            for (col in 0 until width) {

                nv21[d] = yBuffer.get(srcRowStart + col)

                d += dStep
            }
        }

        /*
         * VU
         *
         * 色度从亮度平面之后开始写：以前靠一个自增的 offset 续着写，
         * 现在下标是按公式算的，基址必须显式加上，否则色度会盖掉亮度。
         *
         * 色度按 2x2 块整体旋转：源块的四个亮度像素正好落到目标的同一个
         * 2x2 块里（宽高都是偶数才成立，1280x720 这类档位满足），
         * 所以块内 V、U 仍然相邻，只有块的位置换了。
         */
        val chromaBase = outWidth * outHeight

        val chromaWidth = width / 2
        val chromaHeight = height / 2

        val outChromaWidth = outWidth / 2

        for (row in 0 until chromaHeight) {

            val uSrcRowStart = row * uRowStride
            val vSrcRowStart = row * vRowStride

            val dStart: Int
            val dStep: Int

            if (transposed) {
                dStart =
                    if (rotation == 90) chromaBase + 2 * (outChromaWidth - 1 - row)
                    else chromaBase + 2 * ((chromaWidth - 1) * outChromaWidth + row)
                dStep = if (rotation == 90) 2 * outChromaWidth else -2 * outChromaWidth
            } else {
                dStart =
                    if (reversed) chromaBase + 2 * ((chromaHeight - 1 - row) * outChromaWidth + chromaWidth - 1)
                    else chromaBase + 2 * row * outChromaWidth
                dStep = if (reversed) -2 else 2
            }

            var d = dStart

            for (col in 0 until chromaWidth) {

                val uIndex =
                    uSrcRowStart + col * uPixelStride

                val vIndex =
                    vSrcRowStart + col * vPixelStride

                nv21[d] = vBuffer.get(vIndex)
                nv21[d + 1] = uBuffer.get(uIndex)

                d += dStep
            }
        }

        return nv21
    }
}

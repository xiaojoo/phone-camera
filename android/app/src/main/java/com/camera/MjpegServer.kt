package com.camera

import android.os.SystemClock
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class MjpegServer(
    private val port: Int = 8080
) {

    companion object {

        private const val BOUNDARY = "frame"

        private const val CONTENT_TYPE =
            "multipart/x-mixed-replace; boundary=$BOUNDARY"

        /*
         * 没有新帧时，隔一会儿重发上一帧，
         * 顺便靠这次写入发现客户端已经走了。
         */
        private const val KEEPALIVE_MS = 2000L

        /*
         * 连一帧都没有过（比如相机没起来）的观看者，
         * 超过这个时间直接断开。
         */
        private const val DEAD_AFTER_MS = 30_000L
    }

    private val running =
        AtomicBoolean(false)

    private val clients =
        AtomicInteger(0)

    val isRunning: Boolean
        get() = running.get()

    val activeClients: Int
        get() = clients.get()

    /*
     * 当前镜头："back" 或 "front"，由 Activity 负责真正切换。
     */
    @Volatile
    var lens: String = "back"

    var onLens: ((String) -> Unit)? = null

    /*
     * 补光开关，同样由 Activity 负责真正点亮。
     */
    @Volatile
    var light: Boolean = false

    var onLight: ((Boolean) -> Unit)? = null

    /*
     * 目标帧率与实测帧率。切换由 Activity 负责（要改 AE 区间和编码节流），
     * 这里只负责暴露出来，让电脑端能核对设置有没有真的生效。
     */
    @Volatile
    var fpsTarget: Int = 0

    @Volatile
    var actualFps: Double = 0.0

    var onFps: ((Int) -> Unit)? = null

    private val latestFrame =
        AtomicReference<ByteArray?>(null)

    private var serverSocket: ServerSocket? = null

    private val clientExecutor: ExecutorService =
        Executors.newCachedThreadPool()

    private var serverThread: Thread? = null

    fun start() {

        if (running.get()) {
            return
        }

        running.set(true)

        serverThread = Thread {

            try {

                serverSocket =
                    ServerSocket(
                        port,
                        50,
                        InetAddress.getByName("0.0.0.0")
                    )

                while (running.get()) {

                    val socket =
                        serverSocket?.accept()
                            ?: break

                    clientExecutor.execute {
                        handleClient(socket)
                    }
                }

            } catch (e: Exception) {

                if (running.get()) {
                    e.printStackTrace()
                }
            }

        }.apply {

            name = "MjpegServer"

            isDaemon = true

            start()
        }
    }

    fun stop() {

        running.set(false)

        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }

        serverSocket = null

        clientExecutor.shutdownNow()

        clients.set(0)

        latestFrame.set(null)
    }

    fun updateFrame(
        jpeg: ByteArray
    ) {

        latestFrame.set(jpeg)
    }

    fun getLocalIpAddress(): String? {

        return try {

            val interfaces =
                Collections.list(
                    NetworkInterface.getNetworkInterfaces()
                )

            for (networkInterface in interfaces) {

                if (!networkInterface.isUp) {
                    continue
                }

                if (networkInterface.isLoopback) {
                    continue
                }

                val addresses =
                    Collections.list(
                        networkInterface.inetAddresses
                    )

                for (address in addresses) {

                    if (address.isLoopbackAddress) {
                        continue
                    }

                    val hostAddress =
                        address.hostAddress

                    if (
                        hostAddress != null &&
                        !hostAddress.contains(":")
                    ) {
                        return hostAddress
                    }
                }
            }

            null

        } catch (_: Exception) {

            null
        }
    }

    private fun handleClient(
        socket: Socket
    ) {

        socket.use {

            try {

                socket.soTimeout = 10_000

                val reader =
                    BufferedReader(
                        InputStreamReader(
                            socket.getInputStream()
                        )
                    )

                val requestLine =
                    reader.readLine()
                        ?: return

                /*
                 * 读取 HTTP headers
                 */

                while (true) {

                    val line =
                        reader.readLine()
                            ?: break

                    if (line.isEmpty()) {
                        break
                    }
                }

                val path =
                    requestLine
                        .split(" ")
                        .getOrNull(1)
                        ?: "/"

                when {

                    path == "/video" ||
                            path.startsWith("/video?") -> {

                        streamVideo(socket)
                    }

                    path == "/" -> {

                        sendIndex(socket)
                    }

                    path == "/status" -> {

                        sendStatus(socket)
                    }

                    path == "/camera" ||
                            path.startsWith("/camera?") -> {

                        sendCamera(socket, path)
                    }

                    path == "/torch" ||
                            path.startsWith("/torch?") -> {

                        sendTorch(socket, path)
                    }

                    path == "/fps" ||
                            path.startsWith("/fps?") -> {

                        sendFps(socket, path)
                    }

                    else -> {

                        send404(socket)
                    }
                }

            } catch (_: Exception) {
                // 客户端断开连接属于正常情况
            }
        }
    }

    private fun streamVideo(
        socket: Socket
    ) {

        val output =
            socket.getOutputStream()

        val header = buildString {

            append("HTTP/1.1 200 OK\r\n")

            append(
                "Content-Type: $CONTENT_TYPE\r\n"
            )

            append("Cache-Control: no-cache\r\n")

            append("Pragma: no-cache\r\n")

            append("Connection: close\r\n")

            append("\r\n")
        }

        output.write(
            header.toByteArray(Charsets.UTF_8)
        )

        output.flush()

        clients.incrementAndGet()

        /*
         * 只在写帧时才能发现客户端已经走了。
         *
         * 没有新帧的时候这个循环不会写任何东西，
         * 所以另开一个线程盯着读取端：
         * 对端关闭后 read() 返回 -1，这里立刻关掉 socket，
         * 下一次 write 抛异常，clients 才会减回去。
         */
        socket.soTimeout = 0

        Thread {

            try {

                val input =
                    socket.getInputStream()

                while (input.read() != -1) {
                    // 观看者不会再发数据，读到内容直接丢弃
                }

            } catch (_: Exception) {
            }

            try {
                socket.close()
            } catch (_: Exception) {
            }

        }.apply {
            name = "MjpegClientWatcher"
            isDaemon = true
            start()
        }

        try {

            var lastFrame: ByteArray? = null
            var lastSend = SystemClock.elapsedRealtime()

            while (running.get()) {

                val frame =
                    latestFrame.get()

                val now =
                    SystemClock.elapsedRealtime()

                if (
                    frame == null ||
                    frame === lastFrame
                ) {

                    if (now - lastSend > DEAD_AFTER_MS) {
                        break
                    }

                    val stale = lastFrame

                    if (
                        stale != null &&
                        now - lastSend > KEEPALIVE_MS
                    ) {

                        /*
                         * 没有新帧也要写一次：
                         * 对端已经断开的话，这里才会拿到异常。
                         */
                        writePart(output, stale)

                        lastSend = now

                        continue
                    }

                    Thread.sleep(5)

                    continue
                }

                writePart(output, frame)

                lastFrame = frame
                lastSend = now
            }

        } finally {

            clients.decrementAndGet()
        }
    }

    private fun writePart(
        output: OutputStream,
        frame: ByteArray
    ) {

        val partHeader = buildString {

            append("--$BOUNDARY\r\n")

            append(
                "Content-Type: image/jpeg\r\n"
            )

            append(
                "Content-Length: ${frame.size}\r\n"
            )

            append("\r\n")
        }

        output.write(
            partHeader.toByteArray(Charsets.UTF_8)
        )

        output.write(frame)

        output.write(
            "\r\n".toByteArray(Charsets.UTF_8)
        )

        output.flush()
    }

    private fun sendIndex(
        socket: Socket
    ) {

        val ip =
            getLocalIpAddress()
                ?: "unknown"

        val body = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <title>PhoneCamera</title>
                <style>
                    body {
                        background: #101114;
                        color: white;
                        font-family: sans-serif;
                        text-align: center;
                    }

                    img {
                        max-width: 95%;
                        max-height: 80vh;
                        background: black;
                    }

                    .info {
                        margin: 20px;
                        color: #aaa;
                    }
                </style>
            </head>
            <body>

                <h2>PhoneCamera</h2>

                <img src="/video">

                <div class="info">
                    MJPEG: http://$ip:$port/video
                </div>

            </body>
            </html>
        """.trimIndent()

        sendResponse(
            socket,
            "200 OK",
            "text/html; charset=utf-8",
            body.toByteArray(Charsets.UTF_8)
        )
    }

    private fun sendStatus(
        socket: Socket
    ) {

        val ip =
            getLocalIpAddress()
                ?: "unknown"

        val body = """
            {
                "name": "PhoneCamera",
                "running": ${running.get()},
                "ip": "$ip",
                "port": $port,
                "clients": ${clients.get()},
                "camera": "$lens",
                "light": ${light},
                "fps_target": $fpsTarget,
                "fps": ${fpsText()},
                "stream": "http://$ip:$port/video"
            }
        """.trimIndent()

        sendResponse(
            socket,
            "200 OK",
            "application/json; charset=utf-8",
            body.toByteArray(Charsets.UTF_8)
        )
    }

    /*
     * GET /camera              -> 返回当前镜头
     * GET /camera?face=front   -> 切到前置并返回
     * GET /camera?face=back    -> 切到后置并返回
     */
    private fun sendCamera(
        socket: Socket,
        path: String
    ) {

        when (path.substringAfter("face=", "")) {
            "front" -> {
                lens = "front"
                onLens?.invoke("front")
            }

            "back" -> {
                lens = "back"
                onLens?.invoke("back")
            }
        }

        val body = """
            {
                "camera": "$lens"
            }
        """.trimIndent()

        sendResponse(
            socket,
            "200 OK",
            "application/json; charset=utf-8",
            body.toByteArray(Charsets.UTF_8)
        )
    }

    /*
     * GET /torch        -> 返回当前补光状态
     * GET /torch?on=1   -> 打开补光
     * GET /torch?on=0   -> 关闭补光
     */
    private fun sendTorch(
        socket: Socket,
        path: String
    ) {

        when (path.substringAfter("on=", "")) {
            "1", "true" -> {
                light = true
                onLight?.invoke(true)
            }

            "0", "false" -> {
                light = false
                onLight?.invoke(false)
            }
        }

        val body = """
            {
                "light": ${light}
            }
        """.trimIndent()

        sendResponse(
            socket,
            "200 OK",
            "application/json; charset=utf-8",
            body.toByteArray(Charsets.UTF_8)
        )
    }

    private fun sendFps(
        socket: Socket,
        path: String
    ) {

        path.substringAfter("value=", "")
            .toIntOrNull()
            ?.let { onFps?.invoke(it) }

        val body = """
            {
                "target": $fpsTarget,
                "fps": ${fpsText()}
            }
        """.trimIndent()

        sendResponse(
            socket,
            "200 OK",
            "application/json; charset=utf-8",
            body.toByteArray(Charsets.UTF_8)
        )
    }

    /* Double.toString 不受 Locale 影响，format 会，所以这里手动留一位小数 */
    private fun fpsText(): String =
        (Math.round(actualFps * 10) / 10.0).toString()

    private fun send404(
        socket: Socket
    ) {

        val body =
            "404 Not Found"

        sendResponse(
            socket,
            "404 Not Found",
            "text/plain; charset=utf-8",
            body.toByteArray(Charsets.UTF_8)
        )
    }

    private fun sendResponse(
        socket: Socket,
        status: String,
        contentType: String,
        body: ByteArray
    ) {

        val output: OutputStream =
            socket.getOutputStream()

        val header = buildString {

            append("HTTP/1.1 $status\r\n")

            append(
                "Content-Type: $contentType\r\n"
            )

            append(
                "Content-Length: ${body.size}\r\n"
            )

            append("Connection: close\r\n")

            append("\r\n")
        }

        output.write(
            header.toByteArray(Charsets.UTF_8)
        )

        output.write(body)

        output.flush()
    }
}
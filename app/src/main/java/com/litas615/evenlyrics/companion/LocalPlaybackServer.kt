package com.litas615.evenlyrics.companion

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class LocalPlaybackServer(
    private val port: Int = 5288,
    private val onCommandReceived: (action: String, positionMs: Long?) -> Unit
) {
    private val tag = "LocalPlaybackServer"
    private var serverSocket: ServerSocket? = null
    private var running = false
    private val executor = Executors.newCachedThreadPool()
    private val connectedClients = CopyOnWriteArrayList<Socket>()
    private var lastStateJson: String? = null

    val clientCount: Int
        get() = connectedClients.size

    val isRunning: Boolean
        get() = running

    fun start() {
        if (running) return
        running = true

        executor.execute {
            try {
                // Bind to localhost 127.0.0.1
                val bindAddr = InetAddress.getByName("127.0.0.1")
                serverSocket = ServerSocket(port, 50, bindAddr).apply {
                    reuseAddress = true
                }
                Log.i(tag, "LocalPlaybackServer started on 127.0.0.1:$port")

                while (running && serverSocket != null && !serverSocket!!.isClosed) {
                    val client = serverSocket!!.accept()
                    executor.execute { handleClient(client) }
                }
            } catch (e: Exception) {
                if (running) {
                    Log.e(tag, "ServerSocket error: ${e.message}", e)
                }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            val inputStream = socket.getInputStream()
            val outputStream = socket.getOutputStream()

            // Read HTTP Request Headers
            val headerBuilder = StringBuilder()
            val buffer = ByteArray(1024)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                val chunk = String(buffer, 0, bytesRead, Charsets.UTF_8)
                headerBuilder.append(chunk)
                if (headerBuilder.contains("\r\n\r\n")) {
                    break
                }
            }

            val request = headerBuilder.toString()
            if (request.contains("Upgrade: websocket", ignoreCase = true)) {
                // WebSocket Handshake
                val keyRegex = "Sec-WebSocket-Key:\\s*([^\\r\\n]+)".toRegex(RegexOption.IGNORE_CASE)
                val keyMatch = keyRegex.find(request)
                if (keyMatch != null) {
                    val clientKey = keyMatch.groupValues[1].trim()
                    val acceptKey = computeWebSocketAccept(clientKey)

                    val response = "HTTP/1.1 101 Switching Protocols\r\n" +
                            "Upgrade: websocket\r\n" +
                            "Connection: Upgrade\r\n" +
                            "Sec-WebSocket-Accept: $acceptKey\r\n\r\n"

                    outputStream.write(response.toByteArray(Charsets.UTF_8))
                    outputStream.flush()

                    connectedClients.add(socket)
                    Log.i(tag, "WebSocket client connected. Total clients: ${connectedClients.size}")

                    // Send current state immediately if available
                    lastStateJson?.let { state ->
                        sendText(socket, state)
                    }

                    // Process WebSocket Frames
                    processWebSocketFrames(socket, inputStream)
                }
            } else {
                // Standard HTTP GET /status /playback response
                val body = JSONObject().apply {
                    put("service", "EvenLyrics Android Companion")
                    put("port", port)
                    put("connectedClients", connectedClients.size)
                    put("status", "running")
                }.toString()

                val httpResponse = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n\r\n" +
                        body

                outputStream.write(httpResponse.toByteArray(Charsets.UTF_8))
                outputStream.flush()
                socket.close()
            }
        } catch (e: Exception) {
            Log.d(tag, "Client connection ended: ${e.message}")
        } finally {
            connectedClients.remove(socket)
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun processWebSocketFrames(socket: Socket, input: InputStream) {
        try {
            while (running && !socket.isClosed) {
                val b0 = input.read()
                if (b0 == -1) break

                val opcode = b0 and 0x0F
                val b1 = input.read()
                if (b1 == -1) break

                val isMasked = (b1 and 0x80) != 0
                var payloadLength = (b1 and 0x7F).toLong()

                if (payloadLength == 126L) {
                    val hi = input.read()
                    val lo = input.read()
                    if (hi == -1 || lo == -1) break
                    payloadLength = ((hi shl 8) or lo).toLong()
                } else if (payloadLength == 127L) {
                    var len = 0L
                    for (i in 0 until 8) {
                        val b = input.read()
                        if (b == -1) return
                        len = (len shl 8) or (b and 0xFF).toLong()
                    }
                    payloadLength = len
                }

                val mask = ByteArray(4)
                if (isMasked) {
                    var read = 0
                    while (read < 4) {
                        val r = input.read(mask, read, 4 - read)
                        if (r == -1) return
                        read += r
                    }
                }

                val payload = ByteArray(payloadLength.toInt())
                var totalRead = 0
                while (totalRead < payload.size) {
                    val r = input.read(payload, totalRead, payload.size - totalRead)
                    if (r == -1) return
                    totalRead += r
                }

                if (isMasked) {
                    for (i in payload.indices) {
                        payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                    }
                }

                when (opcode) {
                    0x01 -> { // Text Frame
                        val text = String(payload, Charsets.UTF_8)
                        handleIncomingText(text)
                    }
                    0x08 -> { // Close Frame
                        break
                    }
                    0x09 -> { // Ping Frame -> reply Pong (0x0A)
                        sendFrame(socket, 0x0A, payload)
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun handleIncomingText(text: String) {
        try {
            val json = JSONObject(text)
            if (json.optString("type") == "EVENLYRICS_PLAYBACK_COMMAND") {
                val action = json.optString("action")
                val posMs = if (json.has("positionMs")) json.optLong("positionMs") else null
                if (action.isNotEmpty()) {
                    onCommandReceived(action, posMs)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse incoming command: ${e.message}")
        }
    }

    fun broadcastState(
        packageName: String,
        title: String,
        artist: String,
        durationMs: Long?,
        positionMs: Long,
        isPlaying: Boolean,
        playbackSpeed: Float = 1.0f,
        lrc: String? = null
    ) {
        val payload = JSONObject().apply {
            put("packageName", packageName)
            put("title", title)
            put("artist", artist)
            if (durationMs != null && durationMs > 0) {
                put("durationMs", durationMs)
            }
            put("positionMs", positionMs)
            put("isPlaying", isPlaying)
            put("playbackSpeed", playbackSpeed.toDouble())
            put("timestamp", System.currentTimeMillis())
            if (!lrc.isNullOrBlank()) {
                put("lrc", lrc)
            }
        }

        val envelope = JSONObject().apply {
            put("type", "EVENLYRICS_PLAYBACK_STATE")
            put("payload", payload)
        }

        val jsonString = envelope.toString()
        lastStateJson = jsonString

        for (client in connectedClients) {
            try {
                sendText(client, jsonString)
            } catch (e: Exception) {
                connectedClients.remove(client)
                try { client.close() } catch (_: Exception) {}
            }
        }
    }

    private fun sendText(socket: Socket, text: String) {
        sendFrame(socket, 0x01, text.toByteArray(Charsets.UTF_8))
    }

    private fun sendFrame(socket: Socket, opcode: Int, payload: ByteArray) {
        val os: OutputStream = socket.getOutputStream()
        synchronized(socket) {
            os.write(0x80 or (opcode and 0x0F))
            if (payload.size <= 125) {
                os.write(payload.size)
            } else if (payload.size <= 65535) {
                os.write(126)
                os.write((payload.size shr 8) and 0xFF)
                os.write(payload.size and 0xFF)
            } else {
                os.write(127)
                for (i in 7 downTo 0) {
                    os.write(((payload.size.toLong() shr (i * 8)) and 0xFF).toInt())
                }
            }
            os.write(payload)
            os.flush()
        }
    }

    private fun computeWebSocketAccept(key: String): String {
        val magic = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        val sha1 = MessageDigest.getInstance("SHA-1")
        val hash = sha1.digest((key + magic).toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    fun stop() {
        running = false
        for (client in connectedClients) {
            try { client.close() } catch (_: Exception) {}
        }
        connectedClients.clear()
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
    }
}

package com.camhub.desktop

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObsWebSocketClientTest {
    @Test
    fun `creates authenticated browser input in current program scene`() {
        MockObsServer(authentication = true).use { obs ->
            val result = ObsWebSocketClient(obs.port, "obs-secret".toCharArray()).use {
                it.addPgmBrowserSource("http://127.0.0.1:48765/")
            }

            assertEquals("Added 'CamHub PGM' to 'Program'.", result)
            obs.awaitRequests(3)
            assertEquals(
                listOf("GetCurrentProgramScene", "GetInputList", "CreateInput"),
                obs.requests.map { it["requestType"]?.jsonPrimitive?.content }
            )
            val create = obs.requests.last()["requestData"]!!.jsonObject
            assertEquals("Program", create["sceneName"]?.jsonPrimitive?.content)
            assertEquals("browser_source", create["inputKind"]?.jsonPrimitive?.content)
            val settings = create["inputSettings"]!!.jsonObject
            assertEquals("http://127.0.0.1:48765/", settings["url"]?.jsonPrimitive?.content)
            assertEquals("1920", settings["width"]?.jsonPrimitive?.content)
            assertEquals("1080", settings["height"]?.jsonPrimitive?.content)
            assertEquals(expectedAuthentication("obs-secret", "salt-value", "challenge-value"), obs.authentication)
        }
    }

    @Test
    fun `updates an existing input and adds it to the current scene`() {
        MockObsServer(authentication = false, sourceAlreadyExists = true).use { obs ->
            val result = ObsWebSocketClient(obs.port, charArrayOf()).use {
                it.addPgmBrowserSource("http://127.0.0.1:48765/")
            }

            assertEquals("Updated and added 'CamHub PGM' to 'Program'.", result)
            obs.awaitRequests(5)
            assertEquals(
                listOf("GetCurrentProgramScene", "GetInputList", "SetInputSettings", "GetSceneItemList", "CreateSceneItem"),
                obs.requests.map { it["requestType"]?.jsonPrimitive?.content }
            )
        }
    }

    @Test
    fun `updates an existing input already present in the current scene`() {
        MockObsServer(authentication = false, sourceAlreadyExists = true, sourceInCurrentScene = true).use { obs ->
            val result = ObsWebSocketClient(obs.port, charArrayOf()).use {
                it.addPgmBrowserSource("http://127.0.0.1:48765/")
            }

            assertEquals("Updated 'CamHub PGM' in 'Program'.", result)
            obs.awaitRequests(4)
            assertEquals("GetSceneItemList", obs.requests.last()["requestType"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `does not overwrite a same named non browser obs input`() {
        MockObsServer(authentication = false, sourceAlreadyExists = true, existingInputKind = "image_source").use { obs ->
            val failure = runCatching {
                ObsWebSocketClient(obs.port, charArrayOf()).use {
                    it.addPgmBrowserSource("http://127.0.0.1:48765/")
                }
            }.exceptionOrNull()

            assertTrue(failure?.message?.contains("non-Browser source") == true)
            obs.awaitRequests(2)
            assertEquals(listOf("GetCurrentProgramScene", "GetInputList"),
                obs.requests.map { it["requestType"]?.jsonPrimitive?.content })
        }
    }

    private class MockObsServer(
        authentication: Boolean,
        private val sourceAlreadyExists: Boolean = false,
        private val sourceInCurrentScene: Boolean = false,
        private val existingInputKind: String = "browser_source"
    ) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val threadFailure = AtomicReference<Throwable?>()
        val requests = CopyOnWriteArrayList<JsonObject>()
        @Volatile var authentication: String? = null
            private set
        val port: Int get() = server.localPort

        init {
            Thread({ runCatching { serve(authentication) }.onFailure(threadFailure::set) }, "MockObsWebSocket")
                .apply { isDaemon = true; start() }
        }

        fun awaitRequests(count: Int) {
            val deadline = System.nanoTime() + 3_000_000_000L
            while (requests.size < count && System.nanoTime() < deadline && threadFailure.get() == null) {
                Thread.sleep(5)
            }
            threadFailure.get()?.let { throw AssertionError("Mock OBS failed", it) }
            assertTrue("Received ${requests.size} OBS requests, expected $count", requests.size >= count)
        }

        override fun close() {
            runCatching { server.close() }
            threadFailure.get()?.let { throw AssertionError("Mock OBS failed", it) }
        }

        private fun serve(requiresAuthentication: Boolean) {
            server.accept().use { socket ->
                socket.soTimeout = 3_000
                val input = BufferedInputStream(socket.getInputStream())
                val output = socket.getOutputStream()
                val headers = readHeaders(input)
                val key = headers.lineSequence()
                    .first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
                    .substringAfter(':').trim()
                val accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1")
                        .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
                )
                output.write(("HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $accept\r\n" +
                    "Sec-WebSocket-Protocol: obswebsocket.json\r\n\r\n").toByteArray(Charsets.US_ASCII))
                output.flush()

                writeMessage(output, 0, buildJsonObject {
                    put("rpcVersion", 1)
                    put("obsWebSocketVersion", "5.5.2")
                    if (requiresAuthentication) put("authentication", buildJsonObject {
                        put("salt", "salt-value")
                        put("challenge", "challenge-value")
                    })
                })
                val identify = Json.parseToJsonElement(readTextFrame(input)).jsonObject["d"]!!.jsonObject
                authentication = identify["authentication"]?.jsonPrimitive?.contentOrNull
                writeMessage(output, 2, buildJsonObject { put("negotiatedRpcVersion", 1) })

                while (true) {
                    val (opcode, payload) = readFrame(input)
                    if (opcode == 0x8) {
                        writeFrame(output, 0x8, byteArrayOf(0x03, 0xe8.toByte()))
                        return
                    }
                    if (opcode == 0x9) {
                        writeFrame(output, 0xA, payload)
                        continue
                    }
                    if (opcode != 0x1) continue
                    val request = Json.parseToJsonElement(payload.toString(Charsets.UTF_8)).jsonObject
                    if (request["op"]?.jsonPrimitive?.content != "6") continue
                    val data = request["d"]!!.jsonObject
                    requests += data
                    val type = data["requestType"]?.jsonPrimitive?.content.orEmpty()
                    val responseData = when (type) {
                        "GetCurrentProgramScene" -> buildJsonObject { put("currentProgramSceneName", "Program") }
                        "GetInputList" -> buildJsonObject {
                            put("inputs", if (sourceAlreadyExists) {
                                JsonArray(listOf(buildJsonObject {
                                    put("inputName", "CamHub PGM")
                                    put("inputKind", existingInputKind)
                                }))
                            } else JsonArray(emptyList()))
                        }
                        "GetSceneItemList" -> buildJsonObject {
                            put("sceneItems", if (sourceInCurrentScene) {
                                JsonArray(listOf(buildJsonObject { put("sourceName", "CamHub PGM") }))
                            } else JsonArray(emptyList()))
                        }
                        else -> buildJsonObject {}
                    }
                    writeMessage(output, 7, buildJsonObject {
                        put("requestType", type)
                        put("requestId", data["requestId"]?.jsonPrimitive?.content.orEmpty())
                        put("requestStatus", buildJsonObject { put("result", true); put("code", 100) })
                        put("responseData", responseData)
                    })
                }
            }
        }

        private fun readHeaders(input: BufferedInputStream): String {
            val bytes = ByteArrayOutputStream()
            var lastFour = 0
            while (true) {
                val value = input.read()
                if (value < 0) error("OBS client closed during WebSocket handshake.")
                bytes.write(value)
                lastFour = (lastFour shl 8) or value
                if ((lastFour and 0xffff_ffff.toInt()) == 0x0d0a0d0a) break
                if (bytes.size() > 16 * 1024) error("OBS WebSocket handshake headers are too large.")
            }
            return bytes.toByteArray().toString(Charsets.US_ASCII)
        }

        private fun readTextFrame(input: BufferedInputStream): String {
            val (opcode, payload) = readFrame(input)
            check(opcode == 0x1) { "Expected OBS Hello text frame." }
            return payload.toString(Charsets.UTF_8)
        }

        private fun readFrame(input: BufferedInputStream): Pair<Int, ByteArray> {
            val first = input.read()
            val second = input.read()
            check(first >= 0 && second >= 0) { "OBS WebSocket closed unexpectedly." }
            val opcode = first and 0x0f
            val masked = second and 0x80 != 0
            var length = second and 0x7f
            if (length == 126) length = (input.read() shl 8) or input.read()
            else if (length == 127) error("Mock OBS does not accept oversized frames.")
            val mask = if (masked) ByteArray(4).also { readFully(input, it) } else null
            val payload = ByteArray(length).also { readFully(input, it) }
            if (mask != null) for (index in payload.indices) {
                payload[index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte()
            }
            return opcode to payload
        }

        private fun readFully(input: BufferedInputStream, target: ByteArray) {
            var offset = 0
            while (offset < target.size) {
                val count = input.read(target, offset, target.size - offset)
                if (count < 0) error("OBS WebSocket closed in a frame.")
                offset += count
            }
        }

        private fun writeMessage(output: OutputStream, op: Int, data: JsonObject) =
            writeFrame(output, 0x1, buildJsonObject { put("op", op); put("d", data) }.toString().toByteArray())

        private fun writeFrame(output: OutputStream, opcode: Int, payload: ByteArray) {
            output.write(0x80 or opcode)
            if (payload.size < 126) output.write(payload.size)
            else {
                output.write(126)
                output.write(payload.size ushr 8)
                output.write(payload.size and 0xff)
            }
            output.write(payload)
            output.flush()
        }
    }

    private fun expectedAuthentication(password: String, salt: String, challenge: String): String {
        fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        val secret = Base64.getEncoder().encodeToString(sha256(password + salt))
        return Base64.getEncoder().encodeToString(sha256(secret + challenge))
    }
}

package com.camhub.desktop

import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Small OBS WebSocket 5 client for adding CamHub's loopback PGM to the current scene. */
internal class ObsWebSocketClient(
    private val port: Int,
    password: CharArray
) : AutoCloseable {
    private val password = password.copyOf()
    private val timeoutSeconds = 5L
    private val listener = ObsListener(this.password)
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(timeoutSeconds))
        .build()
    private var socket: WebSocket? = null

    init {
        require(port in 1..65_535) { "OBS WebSocket port must be between 1 and 65535." }
    }

    fun addPgmSource(url: String, sourceName: String = "CamHub PGM"): String {
        val uri = URI("ws://127.0.0.1:$port")
        socket = httpClient.newWebSocketBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(timeoutSeconds))
            .subprotocols("obswebsocket.json")
            .buildAsync(uri, listener)
            .get(timeoutSeconds, TimeUnit.SECONDS)
        listener.awaitIdentified()

        val currentScene = listener.request("GetCurrentProgramScene")
            .responseData["currentProgramSceneName"]?.jsonPrimitive?.contentOrNull
            ?: error("OBS did not return its current program scene.")
        val inputs = listener.request("GetInputList")
            .responseData["inputs"]?.jsonArray ?: JsonArray(emptyList())
        val existingInput = inputs.firstOrNull { element ->
            element.jsonObject["inputName"]?.jsonPrimitive?.contentOrNull == sourceName
        }?.jsonObject
        val inputKind = existingInput?.get("inputKind")?.jsonPrimitive?.contentOrNull
            ?: MEDIA_SOURCE_KIND
        if (inputKind !in SUPPORTED_SOURCE_KINDS) {
            error("OBS already has an incompatible source named '$sourceName'. Rename that source before connecting CamHub.")
        }
        val settings = when (inputKind) {
            BROWSER_SOURCE_KIND -> browserSettings(url)
            else -> mediaSourceSettings(url)
        }

        if (existingInput == null) {
            listener.request(
                requestType = "CreateInput",
                requestData = buildJsonObject {
                    put("sceneName", currentScene)
                    put("inputName", sourceName)
                    put("inputKind", inputKind)
                    put("inputSettings", settings)
                    put("sceneItemEnabled", true)
                }
            )
            return "Added '$sourceName' to '$currentScene'."
        }

        listener.request(
            requestType = "SetInputSettings",
            requestData = buildJsonObject {
                put("inputName", sourceName)
                put("inputSettings", settings)
                put("overlay", true)
            }
        )
        val sceneItems = listener.request("GetSceneItemList", buildJsonObject {
            put("sceneName", currentScene)
        }).responseData["sceneItems"]?.jsonArray ?: JsonArray(emptyList())
        val alreadyInScene = sceneItems.any { element ->
            element.jsonObject["sourceName"]?.jsonPrimitive?.contentOrNull == sourceName
        }
        if (!alreadyInScene) {
            listener.request("CreateSceneItem", buildJsonObject {
                put("sceneName", currentScene)
                put("sourceName", sourceName)
                put("sceneItemEnabled", true)
            })
        }
        return if (alreadyInScene) {
            "Updated '$sourceName' in '$currentScene'."
        } else {
            "Updated and added '$sourceName' to '$currentScene'."
        }
    }

    override fun close() {
        runCatching {
            socket?.sendClose(WebSocket.NORMAL_CLOSURE, "CamHub finished OBS setup")
                ?.get(1, TimeUnit.SECONDS)
        }
        listener.failPending(IllegalStateException("OBS connection closed."))
        password.fill('\u0000')
    }

    private fun sendIdentify(authentication: String?) {
        val data = buildJsonObject {
            put("rpcVersion", 1)
            put("eventSubscriptions", 0)
            if (authentication != null) put("authentication", authentication)
        }
        send(1, data)
    }

    private fun send(op: Int, data: JsonObject) {
        val currentSocket = socket ?: error("OBS WebSocket is not connected.")
        val message = buildJsonObject {
            put("op", op)
            put("d", data)
        }.toString()
        currentSocket.sendText(message, true).get(timeoutSeconds, TimeUnit.SECONDS)
    }

    private fun browserSettings(url: String): JsonObject = buildJsonObject {
        put("url", url)
        put("width", 1920)
        put("height", 1080)
        put("shutdown", false)
        put("reroute_audio", false)
    }

    private fun mediaSourceSettings(baseUrl: String): JsonObject = buildJsonObject {
        put("input", URI(baseUrl).resolve("pgm.mjpg").toString())
        put("input_format", "mpjpeg")
        put("is_local_file", false)
        put("buffering_mb", 0)
        put("restart_on_activate", false)
        put("close_when_inactive", false)
        put("hw_decode", false)
        put("seekable", false)
    }

    private inner class ObsListener(private val password: CharArray) : WebSocket.Listener {
        private val identified = CompletableFuture<Unit>()
        private val responses = ConcurrentHashMap<String, CompletableFuture<JsonObject>>()
        private val textBuffer = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            socket = webSocket
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletableFuture<*>? {
            textBuffer.append(data)
            if (last) {
                runCatching { handleMessage(Json.parseToJsonElement(textBuffer.toString()).jsonObject) }
                    .onFailure(::failPending)
                textBuffer.setLength(0)
            }
            webSocket.request(1)
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            failPending(error)
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletableFuture<*>? {
            if (!identified.isDone) failPending(IllegalStateException("OBS closed the WebSocket ($statusCode). $reason"))
            return null
        }

        fun awaitIdentified() {
            identified.get(timeoutSeconds, TimeUnit.SECONDS)
        }

        fun request(requestType: String, requestData: JsonObject = buildJsonObject {}): RequestResult {
            val id = UUID.randomUUID().toString()
            val result = CompletableFuture<JsonObject>()
            responses[id] = result
            try {
                send(6, buildJsonObject {
                    put("requestType", requestType)
                    put("requestId", id)
                    put("requestData", requestData)
                })
                val message = result.get(timeoutSeconds, TimeUnit.SECONDS)
                val status = message["requestStatus"]?.jsonObject
                if (status?.get("result")?.jsonPrimitive?.content != "true") {
                    val comment = status?.get("comment")?.jsonPrimitive?.contentOrNull
                    error("OBS request $requestType failed${comment?.let { ": $it" } ?: "."}")
                }
                return RequestResult(message["responseData"]?.jsonObject ?: buildJsonObject {})
            } finally {
                responses.remove(id)
            }
        }

        fun failPending(error: Throwable) {
            identified.completeExceptionally(error)
            responses.values.forEach { it.completeExceptionally(error) }
        }

        private fun handleMessage(message: JsonObject) {
            val op = message["op"]?.jsonPrimitive?.intOrNull ?: return
            val data = message["d"]?.jsonObject ?: return
            when (op) {
                0 -> {
                    val auth = data["authentication"]?.jsonObject
                    val authentication = if (auth == null) null else {
                        val salt = auth["salt"]?.jsonPrimitive?.contentOrNull
                            ?: error("OBS authentication challenge omitted its salt.")
                        val challenge = auth["challenge"]?.jsonPrimitive?.contentOrNull
                            ?: error("OBS authentication challenge omitted its challenge.")
                        createAuthentication(password.concatToString(), salt, challenge)
                    }
                    sendIdentify(authentication)
                }
                2 -> identified.complete(Unit)
                7 -> {
                    val id = data["requestId"]?.jsonPrimitive?.contentOrNull ?: return
                    responses[id]?.complete(data)
                }
            }
        }
    }

    private data class RequestResult(val responseData: JsonObject)

    companion object {
        private const val MEDIA_SOURCE_KIND = "ffmpeg_source"
        private const val BROWSER_SOURCE_KIND = "browser_source"
        private val SUPPORTED_SOURCE_KINDS = setOf(MEDIA_SOURCE_KIND, BROWSER_SOURCE_KIND)

        internal fun createAuthentication(password: String, salt: String, challenge: String): String {
            val secret = Base64.getEncoder().encodeToString(sha256(password + salt))
            return Base64.getEncoder().encodeToString(sha256(secret + challenge))
        }

        private fun sha256(value: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    }
}

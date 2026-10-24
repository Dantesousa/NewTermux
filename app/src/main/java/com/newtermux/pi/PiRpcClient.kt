package com.newtermux.pi

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pi RPC mode client (JSONL protocol, see pi docs/rpc.md).
 *
 * Pi runs as a separate process in the Termux environment; the daemon
 * (~/work/nebula-pi/daemon/pi-rpc-daemon.mjs) bridges the local TCP socket
 * and `pi --mode rpc`. Because the port is fixed, the app works both in the
 * full build (com.termux) and in the demo flavor (com.termux.demo, another
 * UID) — TCP on localhost works across different UIDs.
 *
 * Implementation care points:
 * - NEVER write to the socket on the main thread: Android throws
 *   NetworkOnMainThreadException. All network I/O happens on dedicated
 *   threads; the main thread only queues the payload.
 * - Do not use PrintWriter: it swallows IOException and hides a broken pipe.
 * - Socket fields are @Volatile and there is a "generation" so that an old
 *   thread does not close the socket of a new connection.
 * - Every error goes to logcat (tag NebulaPi), which Termux can read.
 */
class PiRpcClient(private val port: Int = PiSession.DEFAULT_PORT) {

    interface Listener {
        fun onConnected()
        fun onDisconnected(reason: String?)
        /** Response to an RPC command. [data] is null on error. */
        fun onResponse(command: String, success: Boolean, data: JSONObject?, error: String?)
        fun onUserMessage(text: String)
        fun onTextDelta(text: String)
        fun onThinkingDelta(text: String)
        fun onToolStart(name: String)
        fun onToolEnd(name: String, text: String = "", images: List<String> = emptyList())
        fun onAssistantMessage(text: String, thinking: String, images: List<String>)
        fun onSettled()
        /** Dialogo de extensao (select/confirm/input/editor). */
        fun onExtensionRequest(request: JSONObject)
        /** Aviso/estado de extensao (notify, setStatus, setTitle...). */
        fun onExtensionNotice(message: String)
        /** Saida de comando bash. */
        fun onBashOutput(chunk: String)
        /** Protocol error (prompt refused, no connection, etc). */
        fun onProtocolError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val requestCounter = AtomicInteger(0)
    private val sendLock = Any()

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var out: OutputStream? = null

    /** invalidates threads from old connections */
    @Volatile
    private var generation = 0

    @Volatile
    private var connected = false

    @Volatile
    private var closing = false

    private var readerThread: Thread? = null
    private var writerThread: Thread? = null
    private var listener: Listener? = null

    /** Write queue: the write thread does the real I/O. */
    private val writeQueue = LinkedBlockingQueue<ByteArray>()

    // -------------------------------------------------------------- connection

    /** Connects to the daemon. Does not block: runs on its own thread. */
    fun connect(l: Listener) {
        listener = l
        val gen = ++generation
        closing = false
        writeQueue.clear()

        startWriter(gen)

        readerThread = Thread {
            var sock: Socket? = null
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(HOST, port), CONNECT_TIMEOUT_MS)
                sock = s

                synchronized(sendLock) {
                    if (gen != generation) {
                        s.close()
                        return@Thread
                    }
                    socket = s
                    out = s.getOutputStream()
                }
                connected = true
                Log.i(TAG, "connected to the Pi daemon at $HOST:$port")
                main.post { listener?.onConnected() }
                request("get_state")

                val input = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
                while (gen == generation) {
                    val line = input.readLine() ?: break
                    if (line.isBlank()) continue
                    try {
                        handle(JSONObject(line))
                    } catch (e: Exception) {
                        Log.e(TAG, "invalid event from daemon: $line", e)
                    }
                }
                if (gen == generation && !closing) {
                    main.post { listener?.onDisconnected("connection closed by the daemon") }
                }
            } catch (e: Exception) {
                Log.e(TAG, "connection error with the Pi daemon", e)
                if (gen == generation && !closing) {
                    main.post { listener?.onDisconnected(describe(e)) }
                }
            } finally {
                if (gen == generation) {
                    synchronized(sendLock) {
                        socket = null
                        out = null
                    }
                    connected = false
                }
                try {
                    sock?.close()
                } catch (_: Exception) {
                }
            }
        }.apply {
            isDaemon = true
            name = "pi-rpc-reader"
            start()
        }
    }

    /**
     * Thread dedicated to writing to the socket. All network I/O happens here,
     * never on the main thread (NetworkOnMainThreadException).
     */
    private fun startWriter(gen: Int) {
        writerThread = Thread {
            while (gen == generation && !closing) {
                val bytes = try {
                    writeQueue.poll(300, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    null
                } ?: continue

                var attempts = 0
                var sent = false
                while (!sent && gen == generation && !closing && attempts < 40) {
                    val stream = synchronized(sendLock) { out }
                    if (stream == null) {
                        // socket not ready yet: wait and try again
                        attempts++
                        try {
                            Thread.sleep(50)
                        } catch (e: InterruptedException) {
                            break
                        }
                        continue
                    }
                    try {
                        stream.write(bytes)
                        stream.flush()
                        sent = true
                    } catch (e: Exception) {
                        Log.e(TAG, "failed to write to the Pi daemon", e)
                        main.post { listener?.onDisconnected(describe(e)) }
                        break
                    }
                }
            }
        }.apply {
            isDaemon = true
            name = "pi-rpc-writer"
            start()
        }
    }

    fun isConnected(): Boolean = connected

    // ---------------------------------------------------------------- commands

    /**
     * Sends a prompt.
     * [images] is optional: list of (mimeType, base64 without the data: prefix).
     * [streamingBehavior] only goes out when the agent is already running: "steer"
     * queues it for the next turn, without dropping or losing the message.
     * Returns false if it could not even be queued (no connection).
     */
    fun sendPrompt(text: String, images: List<Pair<String, String>> = emptyList(), streamingBehavior: String? = null): Boolean {
        val payload = JSONObject()
        payload.put("type", "prompt")
        payload.put("message", text)
        if (streamingBehavior != null) payload.put("streamingBehavior", streamingBehavior)
        if (images.isNotEmpty()) {
            val arr = org.json.JSONArray()
            for ((mime, data) in images) {
                arr.put(
                    JSONObject()
                        .put("type", "image")
                        .put("data", data)
                        .put("mimeType", mime)
                )
            }
            payload.put("images", arr)
        }
        return send(payload)
    }

    /** Generic request with an id, to correlate the response. */
    fun request(type: String): String = request(type, null)

    /** Generic request with extra fields (e.g. {"level":"high"}). */
    fun request(type: String, extras: JSONObject?): String {
        val id = "req-" + requestCounter.incrementAndGet()
        val payload = JSONObject()
        payload.put("id", id)
        payload.put("type", type)
        if (extras != null) {
            val keys = extras.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                payload.put(key, extras.get(key))
            }
        }
        send(payload)
        return id
    }

    /** Answers an extension dialog with a value (select/input/editor). */
    fun respondExtensionUi(id: String, value: String) {
        val payload = JSONObject()
        payload.put("type", "extension_ui_response")
        payload.put("id", id)
        payload.put("value", value)
        send(payload)
    }

    /** Answers an extension dialog with a confirmation (confirm). */
    fun respondExtensionUiConfirmed(id: String, confirmed: Boolean) {
        val payload = JSONObject()
        payload.put("type", "extension_ui_response")
        payload.put("id", id)
        payload.put("confirmed", confirmed)
        send(payload)
    }

    /** Cancels an extension dialog. */
    fun cancelExtensionUi(id: String) {
        val payload = JSONObject()
        payload.put("type", "extension_ui_response")
        payload.put("id", id)
        payload.put("cancelled", true)
        send(payload)
    }

    /** Queues a JSONL record. Does no network I/O (main thread). */
    private fun send(payload: JSONObject): Boolean {
        if (!connected) {
            Log.w(TAG, "no connection: command dropped")
            main.post { listener?.onProtocolError("no connection to the daemon (run 'pi-rpc' in Termux)") }
            return false
        }
        val bytes = (payload.toString() + "\n").toByteArray(StandardCharsets.UTF_8)
        if (!writeQueue.offer(bytes)) {
            main.post { listener?.onProtocolError("write queue full") }
            return false
        }
        return true
    }

    // ------------------------------------------------------------- eventos

    private fun handle(record: JSONObject) {
        when (record.optString("type")) {
            "response" -> handleResponse(record)
            "extension_ui_request" -> handleExtensionRequest(record)
            "bash_execution_update" -> {
                val out = record.optString("output").ifEmpty { record.optString("delta") }
                if (out.isNotEmpty()) main.post { listener?.onBashOutput(out) }
            }
            "message_start" -> {
                val message = record.optJSONObject("message") ?: return
                if (message.optString("role") == "user") {
                    val text = extractText(message.optJSONArray("content"))
                    if (text.isNotBlank()) main.post { listener?.onUserMessage(text) }
                }
            }
            "message_update" -> handleMessageUpdate(record)
            "message_end" -> {
                val message = record.optJSONObject("message") ?: return
                if (message.optString("role") == "assistant") {
                    val content = message.optJSONArray("content")
                    var text = ""
                    var thinking = ""
                    val images = mutableListOf<String>()
                    for (i in 0 until (content?.length() ?: 0)) {
                        val block = content!!.optJSONObject(i) ?: continue
                        when (block.optString("type")) {
                            "text" -> text += block.optString("text")
                            "thinking" -> thinking += block.optString("thinking")
                            "image" -> {
                                val data = block.optString("data")
                                if (data.isNotEmpty()) images.add(data)
                            }
                        }
                    }
                    main.post { listener?.onAssistantMessage(text, thinking, images) }
                }
            }
            "tool_execution_start" -> {
                val name = record.optString("toolName").ifEmpty { record.optString("tool") }
                if (name.isNotEmpty()) main.post { listener?.onToolStart(name) }
            }
            "tool_execution_end" -> {
                val name = record.optString("toolName").ifEmpty { record.optString("tool") }
                if (name.isNotEmpty()) {
                    val (text, images) = extractToolResult(record.optJSONObject("result"))
                    main.post { listener?.onToolEnd(name, text, images) }
                }
            }
            "agent_settled" -> main.post { listener?.onSettled() }
        }
    }

    private fun handleResponse(record: JSONObject) {
        val command = record.optString("command")
        val success = record.optBoolean("success")
        val error = record.optString("error")
        val data = record.optJSONObject("data")
        main.post { listener?.onResponse(command, success, data, error) }
    }

    private fun handleExtensionRequest(record: JSONObject) {
        val method = record.optString("method")
        when (method) {
            "notify" -> {
                val msg = record.optString("message")
                main.post { listener?.onExtensionNotice(msg) }
            }
            "setStatus" -> {
                val msg = record.optString("statusText")
                if (msg.isNotEmpty()) main.post { listener?.onExtensionNotice(msg) }
            }
            "setTitle" -> {
                val msg = record.optString("title")
                if (msg.isNotEmpty()) main.post { listener?.onExtensionNotice(msg) }
            }
            "setWidget" -> {
                val lines = record.optJSONArray("widgetLines")
                val msg = StringBuilder()
                for (i in 0 until (lines?.length() ?: 0)) msg.append(lines!!.optString(i)).append('\n')
                if (msg.isNotEmpty()) main.post { listener?.onExtensionNotice(msg.toString().trim()) }
            }
            "set_editor_text" -> Unit
            else -> main.post { listener?.onExtensionRequest(record) }
        }
    }

    private fun handleMessageUpdate(record: JSONObject) {
        val event = record.optJSONObject("assistantMessageEvent") ?: return
        when (event.optString("type")) {
            "text_delta" -> {
                val delta = event.optString("delta")
                if (delta.isNotEmpty()) main.post { listener?.onTextDelta(delta) }
            }
            "thinking_delta" -> {
                val delta = event.optString("delta")
                if (delta.isNotEmpty()) main.post { listener?.onThinkingDelta(delta) }
            }
            "toolcall_start" -> {
                val name = event.optString("toolName")
                if (name.isNotEmpty()) main.post { listener?.onToolStart(name) }
            }
            "error" -> {
                val reason = event.optString("error").ifEmpty { event.optString("reason") }
                main.post { listener?.onProtocolError(reason.ifEmpty { "provider error" }) }
            }
        }
    }

    private fun extractText(content: org.json.JSONArray?): String {
        if (content == null) return ""
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") sb.append(block.optString("text"))
        }
        return sb.toString()
    }

    /**
     * Extracts a text and image preview from the `result` of a tool_execution_end.
     * The format is the same as message blocks: content:[{type,text|image}].
     * Text is truncated so it does not flood the conversation; images limited to 3.
     */
    private fun extractToolResult(result: JSONObject?): Pair<String, List<String>> {
        if (result == null) return "" to emptyList()
        val content = result.optJSONArray("content") ?: return "" to emptyList()
        val text = StringBuilder()
        val images = mutableListOf<String>()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            when (block.optString("type")) {
                "text" -> {
                    val t = block.optString("text")
                    if (t.isNotEmpty()) {
                        if (text.isNotEmpty()) text.append("\n")
                        text.append(t)
                    }
                }
                "image" -> {
                    val data = block.optString("data")
                    if (data.isNotEmpty() && images.size < MAX_TOOL_IMAGES) images.add(data)
                }
            }
            if (text.length > MAX_TOOL_TEXT) break
        }
        var preview = text.toString().trim()
        if (preview.length > MAX_TOOL_TEXT) {
            preview = preview.take(MAX_TOOL_TEXT).trimEnd() + "\u2026 (+continua)"
        }
        return preview to images
    }

    // ------------------------------------------------------------- ciclo de vida

    fun disconnect() {
        closing = true
        generation++
        writeQueue.clear()
        // Closing the socket is also I/O: off the main thread.
        Thread {
            synchronized(sendLock) {
                try {
                    out?.close()
                } catch (_: Exception) {
                }
                try {
                    socket?.close()
                } catch (_: Exception) {
                }
                out = null
                socket = null
            }
        }.apply {
            isDaemon = true
            name = "pi-rpc-close"
            start()
        }
        connected = false
    }

    private fun describe(e: Exception): String = when (e) {
        is java.net.SocketTimeoutException -> "timeout connecting to $HOST:$port"
        is java.net.ConnectException ->
            "did not connect to $HOST:$port - is the Pi daemon running? (in Termux: pi-rpc)"
        is java.io.EOFException -> "the daemon closed the connection"
        else -> e.message ?: e.javaClass.simpleName
    }

    companion object {
        const val HOST = "127.0.0.1"
        const val DEFAULT_PORT = 37877
        private const val CONNECT_TIMEOUT_MS = 4000
        private const val TAG = "NebulaPi"
        /** Text preview of a tool result (avoids flooding the conversation with a large `read`). */
        const val MAX_TOOL_TEXT = 800
        /** Max images per tool result (avoids OOM with several images). */
        const val MAX_TOOL_IMAGES = 3
    }
}

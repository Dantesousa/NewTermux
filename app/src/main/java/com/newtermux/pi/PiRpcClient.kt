package com.newtermux.pi

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cliente do modo RPC do Pi (protocolo JSONL, ver pi docs/rpc.md).
 *
 * O Pi roda como processo separado no ambiente Termux; o daemon
 * (~/work/nebula-pi/daemon/pi-rpc-daemon.mjs) faz a ponte entre o socket TCP
 * local e o `pi --mode rpc`. Como a porta e fixa, o app funciona tanto no
 * build completo (com.termux, que compartilha o filesystem com o Termux) quanto
 * no flavor demo (com.termux.demo, outro UID) — TCP em localhost funciona
 * entre UIDs diferentes.
 */
class PiRpcClient(private val port: Int = PiSession.DEFAULT_PORT) {

    interface Listener {
        fun onConnected()
        fun onDisconnected(reason: String?)
        fun onState(state: JSONObject)
        fun onUserMessage(text: String)
        fun onTextDelta(text: String)
        fun onThinkingDelta(text: String)
        fun onToolStart(name: String)
        fun onToolEnd(name: String)
        fun onAssistantMessage(text: String, thinking: String)
        fun onSettled()
        fun onProtocolError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val requestCounter = AtomicInteger(0)

    private var socket: Socket? = null
    private var readerThread: Thread? = null
    private var writer: PrintWriter? = null
    private var listener: Listener? = null

    @Volatile
    private var closing = false

    /** Conecta no daemon. Não bloqueia: roda em thread própria. */
    fun connect(l: Listener) {
        listener = l
        closing = false
        readerThread = Thread {
            var sock: Socket? = null
            try {
                sock = Socket()
                sock.tcpNoDelay = true
                sock.connect(InetSocketAddress(HOST, port), CONNECT_TIMEOUT_MS)
                socket = sock
                writer = PrintWriter(sock.getOutputStream(), true)
                main.post { listener?.onConnected() }
                request("get_state")

                val input = BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))
                while (!closing) {
                    val line = input.readLine() ?: break
                    if (line.isBlank()) continue
                    try {
                        handle(JSONObject(line))
                    } catch (e: Exception) {
                        main.post { listener?.onProtocolError("evento invalido: ${e.message}") }
                    }
                }
                if (!closing) main.post { listener?.onDisconnected("conexao fechada pelo daemon") }
            } catch (e: Exception) {
                if (!closing) {
                    val msg = when (e) {
                        is java.net.SocketTimeoutException -> "timeout conectando em $HOST:$port"
                        is java.net.ConnectException ->
                            "nao conectou em $HOST:$port - o daemon do Pi esta rodando? (no Termux: pi-rpc)"
                        else -> e.message ?: e.javaClass.simpleName
                    }
                    main.post { listener?.onDisconnected(msg) }
                }
            } finally {
                closeSocket()
            }
        }.apply {
            isDaemon = true
            name = "pi-rpc-client"
            start()
        }
    }

    /** Envia um prompt. [images] e opcional (base64 puro, sem prefixo data:). */
    fun sendPrompt(text: String, images: List<Pair<String, String>> = emptyList()) {
        val payload = JSONObject()
        payload.put("type", "prompt")
        payload.put("message", text)
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
        send(payload)
    }

    /** Requisicao generica com id, para correlacionar a resposta. */
    fun request(type: String): String {
        val id = "req-" + requestCounter.incrementAndGet()
        val payload = JSONObject()
        payload.put("id", id)
        payload.put("type", type)
        send(payload)
        return id
    }

    private fun send(payload: JSONObject) {
        val w = writer
        if (w == null) {
            main.post { listener?.onProtocolError("sem conexao com o daemon") }
            return
        }
        try {
            w.println(payload.toString())
            w.flush()
        } catch (e: Exception) {
            main.post { listener?.onDisconnected(e.message ?: "erro ao escrever") }
        }
    }

    // ------------------------------------------------------------- eventos

    private fun handle(record: JSONObject) {
        when (record.optString("type")) {
            "response" -> handleResponse(record)
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
                    for (i in 0 until (content?.length() ?: 0)) {
                        val block = content!!.optJSONObject(i) ?: continue
                        when (block.optString("type")) {
                            "text" -> text += block.optString("text")
                            "thinking" -> thinking += block.optString("thinking")
                        }
                    }
                    main.post { listener?.onAssistantMessage(text, thinking) }
                }
            }
            "tool_execution_start" -> {
                val name = record.optString("toolName").ifEmpty { record.optString("tool") }
                if (name.isNotEmpty()) main.post { listener?.onToolStart(name) }
            }
            "tool_execution_end" -> {
                val name = record.optString("toolName").ifEmpty { record.optString("tool") }
                if (name.isNotEmpty()) main.post { listener?.onToolEnd(name) }
            }
            "agent_settled" -> main.post { listener?.onSettled() }
        }
    }

    private fun handleResponse(record: JSONObject) {
        val command = record.optString("command")
        if (!record.optBoolean("success")) {
            val error = record.optString("error")
            if (error.isNotEmpty()) main.post { listener?.onProtocolError(error) }
            return
        }
        if (command == "get_state") {
            val data = record.optJSONObject("data") ?: JSONObject()
            main.post { listener?.onState(data) }
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
                main.post { listener?.onProtocolError(reason.ifEmpty { "erro do provider" }) }
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

    // ------------------------------------------------------------- ciclo de vida

    fun disconnect() {
        closing = true
        closeSocket()
    }

    private fun closeSocket() {
        try {
            writer?.close()
        } catch (_: Exception) {
        }
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        writer = null
        socket = null
    }

    companion object {
        const val HOST = "127.0.0.1"
        const val DEFAULT_PORT = 37877
        private const val CONNECT_TIMEOUT_MS = 4000
    }
}

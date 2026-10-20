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
 * Cliente do modo RPC do Pi (protocolo JSONL, ver pi docs/rpc.md).
 *
 * O Pi roda como processo separado no ambiente Termux; o daemon
 * (~/work/nebula-pi/daemon/pi-rpc-daemon.mjs) faz a ponte entre o socket TCP
 * local e o `pi --mode rpc`. Como a porta e fixa, o app funciona tanto no
 * build completo (com.termux) quanto no flavor demo (com.termux.demo, outro
 * UID) — TCP em localhost funciona entre UIDs diferentes.
 *
 * Cuidados de implementacao:
 * - NUNCA escrever no socket na main thread: o Android joga
 *   NetworkOnMainThreadException. Toda a I/O de rede acontece em threads
 *   dedicadas; a main thread so enfileira o payload.
 * - Nao usar PrintWriter: ele engole IOException e esconde pipe quebrado.
 * - Campos de socket sao @Volatile e existe um "generation" para que uma
 *   thread antiga nao feche o socket de uma conexao nova.
 * - Todo erro vai para o logcat (tag NebulaPi), que o Termux consegue ler.
 */
class PiRpcClient(private val port: Int = PiSession.DEFAULT_PORT) {

    interface Listener {
        fun onConnected()
        fun onDisconnected(reason: String?)
        /** Resposta de um comando RPC. [data] e null em caso de erro. */
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
        /** Erro de protocolo (prompt recusado, sem conexao, etc). */
        fun onProtocolError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val requestCounter = AtomicInteger(0)
    private val sendLock = Any()

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var out: OutputStream? = null

    /** invalida threads de conexao antigas */
    @Volatile
    private var generation = 0

    @Volatile
    private var connected = false

    @Volatile
    private var closing = false

    private var readerThread: Thread? = null
    private var writerThread: Thread? = null
    private var listener: Listener? = null

    /** Fila de escritas: a thread de escrita faz o I/O de verdade. */
    private val writeQueue = LinkedBlockingQueue<ByteArray>()

    // ------------------------------------------------------------- conexao

    /** Conecta no daemon. Não bloqueia: roda em thread própria. */
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
                Log.i(TAG, "conectado no daemon do Pi em $HOST:$port")
                main.post { listener?.onConnected() }
                request("get_state")

                val input = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
                while (gen == generation) {
                    val line = input.readLine() ?: break
                    if (line.isBlank()) continue
                    try {
                        handle(JSONObject(line))
                    } catch (e: Exception) {
                        Log.e(TAG, "evento invalido do daemon: $line", e)
                    }
                }
                if (gen == generation && !closing) {
                    main.post { listener?.onDisconnected("conexao fechada pelo daemon") }
                }
            } catch (e: Exception) {
                Log.e(TAG, "erro de conexao com o daemon do Pi", e)
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
     * Thread dedicada a escrita no socket. Toda a I/O de rede acontece aqui,
     * nunca na main thread (NetworkOnMainThreadException).
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
                        // socket ainda nao pronto: espera e tenta de novo
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
                        Log.e(TAG, "falha ao escrever no daemon do Pi", e)
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

    // ------------------------------------------------------------- comandos

    /**
     * Envia um prompt.
     * [images] e opcional: lista de (mimeType, base64 sem prefixo data:).
     * Retorna false se nem deu para enfileirar (sem conexao).
     */
    fun sendPrompt(text: String, images: List<Pair<String, String>> = emptyList()): Boolean {
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
        return send(payload)
    }

    /** Requisicao generica com id, para correlacionar a resposta. */
    fun request(type: String): String = request(type, null)

    /** Requisicao generica com campos extras (ex.: {"level":"high"}). */
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

    /** Responde a um dialogo de extensao com um valor (select/input/editor). */
    fun respondExtensionUi(id: String, value: String) {
        val payload = JSONObject()
        payload.put("type", "extension_ui_response")
        payload.put("id", id)
        payload.put("value", value)
        send(payload)
    }

    /** Responde a um dialogo de extensao com confirmacao (confirm). */
    fun respondExtensionUiConfirmed(id: String, confirmed: Boolean) {
        val payload = JSONObject()
        payload.put("type", "extension_ui_response")
        payload.put("id", id)
        payload.put("confirmed", confirmed)
        send(payload)
    }

    /** Cancela um dialogo de extensao. */
    fun cancelExtensionUi(id: String) {
        val payload = JSONObject()
        payload.put("type", "extension_ui_response")
        payload.put("id", id)
        payload.put("cancelled", true)
        send(payload)
    }

    /** Enfileira um registro JSONL. Nao faz I/O de rede (thread main). */
    private fun send(payload: JSONObject): Boolean {
        if (!connected) {
            Log.w(TAG, "sem conexao: comando descartado")
            main.post { listener?.onProtocolError("sem conexao com o daemon (rode 'pi-rpc' no Termux)") }
            return false
        }
        val bytes = (payload.toString() + "\n").toByteArray(StandardCharsets.UTF_8)
        if (!writeQueue.offer(bytes)) {
            main.post { listener?.onProtocolError("fila de escrita cheia") }
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

    /**
     * Extrai previa de texto e imagens do `result` de um tool_execution_end.
     * O formato e o mesmo dos blocos de mensagem: content:[{type,text|image}].
     * Texto e truncado para nao lotar a conversa; imagens limitadas a 3.
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
        // Fechar o socket tambem e I/O: fora da main thread.
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
        is java.net.SocketTimeoutException -> "timeout conectando em $HOST:$port"
        is java.net.ConnectException ->
            "nao conectou em $HOST:$port - o daemon do Pi esta rodando? (no Termux: pi-rpc)"
        is java.io.EOFException -> "o daemon fechou a conexao"
        else -> e.message ?: e.javaClass.simpleName
    }

    companion object {
        const val HOST = "127.0.0.1"
        const val DEFAULT_PORT = 37877
        private const val CONNECT_TIMEOUT_MS = 4000
        private const val TAG = "NebulaPi"
        /** Previa de texto de tool result (evita lotar a conversa com `read` grande). */
        const val MAX_TOOL_TEXT = 800
        /** Maximo de imagens por tool result (evita OOM com varias imagens). */
        const val MAX_TOOL_IMAGES = 3
    }
}

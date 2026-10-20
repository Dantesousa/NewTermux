package com.newtermux.pi

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

/** Um item da conversa exibida na tela do Pi. */
sealed class PiItem {
    data class User(val text: String) : PiItem()
    data class Assistant(val text: String) : PiItem()
    data class Thinking(val text: String) : PiItem()
    data class Tool(val name: String, val running: Boolean) : PiItem()
    data class Notice(val text: String, val error: Boolean = false) : PiItem()
}

/**
 * Estado da sessao do Pi, compartilhado entre a Activity e o cliente RPC.
 * Fica em object para sobreviver a recriacao da Activity (rotacao, etc).
 */
object PiSession {

    const val DEFAULT_PORT = PiRpcClient.DEFAULT_PORT
    private const val TAG = "NebulaPi"

    val items = mutableStateListOf<PiItem>()

    var connected by mutableStateOf(false)
        private set
    var status by mutableStateOf("desconectado")
        private set
    var model by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set

    private val client = PiRpcClient(DEFAULT_PORT)

    private var assistantIndex = -1
    private var thinkingIndex = -1
    private var toolIndex = -1
    private val assistantBuffer = StringBuilder()
    private val thinkingBuffer = StringBuilder()

    /** Prompt que nao conseguiu sair porque a conexao tinha morrido. */
    private var pendingPrompt: String? = null

    private val listener = object : PiRpcClient.Listener {

        override fun onConnected() {
            connected = true
            status = "conectado"
            // Reenvia o prompt que ficou pendente.
            pendingPrompt?.let {
                pendingPrompt = null
                Log.i(TAG, "reenviando prompt pendente")
                busy = true
                client.sendPrompt(it)
            }
        }

        override fun onDisconnected(reason: String?) {
            connected = false
            busy = false
            status = reason ?: "desconectado"
            items.add(PiItem.Notice(status, error = true))
        }

        override fun onState(state: JSONObject) {
            val m = state.optJSONObject("model")
            model = m?.optString("id")?.takeIf { it.isNotEmpty() } ?: model
            val thinking = state.optString("thinkingLevel")
            status = if (thinking.isNotEmpty()) "conectado - $model ($thinking)" else "conectado - $model"
        }

        override fun onUserMessage(text: String) {
            // A UI ja mostra a mensagem enviada; evita duplicar.
        }

        override fun onTextDelta(text: String) {
            if (assistantIndex < 0 || assistantIndex >= items.size) {
                assistantBuffer.setLength(0)
                items.add(PiItem.Assistant(""))
                assistantIndex = items.size - 1
            }
            assistantBuffer.append(text)
            items[assistantIndex] = PiItem.Assistant(assistantBuffer.toString())
        }

        override fun onThinkingDelta(text: String) {
            if (thinkingIndex < 0 || thinkingIndex >= items.size) {
                thinkingBuffer.setLength(0)
                items.add(PiItem.Thinking(""))
                thinkingIndex = items.size - 1
            }
            thinkingBuffer.append(text)
            items[thinkingIndex] = PiItem.Thinking(thinkingBuffer.toString())
        }

        override fun onToolStart(name: String) {
            items.add(PiItem.Tool(name, running = true))
            toolIndex = items.size - 1
        }

        override fun onToolEnd(name: String) {
            if (toolIndex >= 0 && toolIndex < items.size) {
                val current = items[toolIndex]
                if (current is PiItem.Tool) {
                    items[toolIndex] = PiItem.Tool(current.name, running = false)
                }
            }
        }

        override fun onAssistantMessage(text: String, thinking: String) {
            if (assistantIndex >= 0 && assistantIndex < items.size && text.isNotEmpty()) {
                items[assistantIndex] = PiItem.Assistant(text)
            }
            assistantIndex = -1
            thinkingIndex = -1
        }

        override fun onSettled() {
            busy = false
        }

        override fun onProtocolError(message: String) {
            busy = false
            items.add(PiItem.Notice(message, error = true))
        }
    }

    /** Conecta (ou reconecta) no daemon. */
    fun start() {
        if (client.isConnected()) {
            connected = true
            return
        }
        status = "conectando..."
        client.connect(listener)
    }

    /** Mantem a conexao viva entre onStop/onStart; so desliga no onDestroy. */
    fun stop() {
        // nada a fazer: reconectar e mais barato do que refazer a sessao do Pi
    }

    fun shutdown() {
        client.disconnect()
        connected = false
        busy = false
        status = "desconectado"
    }

    fun send(text: String) {
        if (text.isBlank()) return
        items.add(PiItem.User(text))
        assistantIndex = -1
        thinkingIndex = -1
        assistantBuffer.setLength(0)
        thinkingBuffer.setLength(0)
        busy = true
        if (!client.sendPrompt(text)) {
            // A conexao estava morta: guarda e reenvia assim que reconectar.
            pendingPrompt = text
            start()
        }
    }

    fun clear() {
        items.clear()
        assistantIndex = -1
        thinkingIndex = -1
        toolIndex = -1
    }
}

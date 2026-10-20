package com.newtermux.pi

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
    data class Bash(val text: String) : PiItem()
    data class Notice(val text: String, val error: Boolean = false) : PiItem()
}

/** Comando da palette. [kind]: builtin, extension, prompt ou skill. */
data class PiCommand(
    val name: String,
    val label: String,
    val description: String,
    val kind: String
)

/** Qual seletor esta aberto na UI. */
enum class PiPicker { Commands, Models, Thinking }

/** Dialogo que pede um texto ao usuario. */
class PiTextDialog(val title: String, val hint: String, val onOk: (String) -> Unit)

/** Dialogo de extensao do Pi (select/confirm/input/editor). */
data class PiExtDialog(
    val id: String,
    val method: String,
    val title: String,
    val message: String,
    val options: List<String>,
    val placeholder: String,
    val prefill: String
)

/** Anexo pronto para ser enviado. */
data class PiAttachment(
    val name: String,
    val mime: String,
    val path: String?,
    val base64: String?,
    val isImage: Boolean
)

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

    /** Comandos da palette: built-in (RPC) + descobertos pelo Pi. */
    var commands by mutableStateOf<List<PiCommand>>(emptyList())
        private set

    /** Seletor aberto (comandos, modelos ou niveis de raciocinio). */
    var picker by mutableStateOf<PiPicker?>(null)

    /** Dialogo pedindo texto. */
    var textDialog by mutableStateOf<PiTextDialog?>(null)

    /** Dialogo de extensao do Pi. */
    var extDialog by mutableStateOf<PiExtDialog?>(null)

    /** Anexos pendentes para a proxima mensagem. */
    val attachments = mutableStateListOf<PiAttachment>()

    private val client = PiRpcClient(DEFAULT_PORT)
    private var appContext: Context? = null

    private var assistantIndex = -1
    private var thinkingIndex = -1
    private var toolIndex = -1
    private val assistantBuffer = StringBuilder()
    private val thinkingBuffer = StringBuilder()

    /** Prompt que nao conseguiu sair porque a conexao tinha morrido. */
    private var pendingPrompt: String? = null

    private val models = mutableStateListOf<PiModelChoice>()
    private val thinkingLevels = mutableStateListOf<String>()

    data class PiModelChoice(val id: String, val name: String, val provider: String)

    /** Comandos que o app sabe executar via RPC (equivalentes aos do TUI). */
    private val builtinCommands = listOf(
        PiCommand("model", "/model", "Trocar de modelo", "builtin"),
        PiCommand("thinking", "/thinking", "Nivel de raciocinio", "builtin"),
        PiCommand("new", "/new", "Nova sessao", "builtin"),
        PiCommand("compact", "/compact", "Compactar o contexto", "builtin"),
        PiCommand("abort", "/abort", "Parar a operacao atual", "builtin"),
        PiCommand("queue", "/clear_queue", "Limpar a fila de mensagens", "builtin"),
        PiCommand("stats", "/session", "Estatisticas e custo da sessao", "builtin"),
        PiCommand("copy", "/copy", "Copiar a ultima resposta do Pi", "builtin"),
        PiCommand("export", "/export", "Exportar a sessao em HTML", "builtin"),
        PiCommand("name", "/name", "Nomear a sessao", "builtin"),
        PiCommand("tree", "/tree", "Ver a arvore da sessao", "builtin"),
        PiCommand("bash", "/bash", "Rodar um comando no shell", "builtin")
    )

    private val listener = object : PiRpcClient.Listener {

        override fun onConnected() {
            connected = true
            status = "conectado"
            client.request("get_commands")
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

        override fun onResponse(command: String, success: Boolean, data: JSONObject?, error: String?) {
            if (!success) {
                items.add(PiItem.Notice(error.ifEmpty { "falha no comando $command" }, error = true))
                return
            }
            handleResponse(command, data)
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

        override fun onExtensionRequest(request: JSONObject) {
            val method = request.optString("method")
            val options = mutableListOf<String>()
            val arr = request.optJSONArray("options")
            for (i in 0 until (arr?.length() ?: 0)) options.add(arr!!.optString(i))
            extDialog = PiExtDialog(
                id = request.optString("id"),
                method = method,
                title = request.optString("title"),
                message = request.optString("message"),
                options = options,
                placeholder = request.optString("placeholder"),
                prefill = request.optString("prefill")
            )
        }

        override fun onExtensionNotice(message: String) {
            items.add(PiItem.Notice(message, error = false))
        }

        override fun onBashOutput(chunk: String) {
            items.add(PiItem.Bash(chunk))
        }

        override fun onProtocolError(message: String) {
            busy = false
            items.add(PiItem.Notice(message, error = true))
        }
    }

    // ---------------------------------------------------------- respostas

    private fun handleResponse(command: String, data: JSONObject?) {
        when (command) {
            "get_state" -> {
                val m = data?.optJSONObject("model")
                val id = m?.optString("id")?.takeIf { it.isNotEmpty() }
                if (id != null) model = id
                val thinking = data?.optString("thinkingLevel").orEmpty()
                status = if (thinking.isNotEmpty()) "$model ($thinking)" else model.ifEmpty { "conectado" }
            }
            "get_commands" -> {
                val found = mutableListOf<PiCommand>()
                val arr = data?.optJSONArray("commands")
                for (i in 0 until (arr?.length() ?: 0)) {
                    val c = arr!!.optJSONObject(i) ?: continue
                    val name = c.optString("name")
                    if (name.isEmpty()) continue
                    val kind = c.optString("source").ifEmpty { "extension" }
                    found.add(
                        PiCommand(
                            name = name,
                            label = "/" + name,
                            description = c.optString("description"),
                            kind = kind
                        )
                    )
                }
                commands = builtinCommands + found
            }
            "get_available_models" -> {
                models.clear()
                val arr = data?.optJSONArray("models")
                for (i in 0 until (arr?.length() ?: 0)) {
                    val m = arr!!.optJSONObject(i) ?: continue
                    models.add(
                        PiModelChoice(
                            id = m.optString("id"),
                            name = m.optString("name").ifEmpty { m.optString("id") },
                            provider = m.optString("provider")
                        )
                    )
                }
            }
            "get_available_thinking_levels" -> {
                thinkingLevels.clear()
                val arr = data?.optJSONArray("levels")
                for (i in 0 until (arr?.length() ?: 0)) thinkingLevels.add(arr!!.optString(i))
            }
            "set_model" -> {
                // A resposta do set_model traz o objeto Model direto em data.
                val id = data?.optString("id")
                if (!id.isNullOrEmpty()) model = id
                status = model
            }
            "set_thinking_level" -> {
                val level = data?.optString("level").orEmpty()
                status = if (level.isNotEmpty()) "$model ($level)" else model
            }
            "get_session_stats" -> items.add(PiItem.Notice(formatStats(data), error = false))
            "new_session" -> {
                clear()
                items.add(PiItem.Notice("Nova sessao iniciada", error = false))
            }
            "compact" -> items.add(PiItem.Notice("Contexto compactado", error = false))
            "export_html" -> items.add(PiItem.Notice("Sessao exportada: " + (data?.optString("path") ?: "?"), error = false))
            "get_last_assistant_text" -> {
                val text = data?.optString("text").orEmpty()
                if (text.isNotEmpty()) {
                    copyToClipboard(text)
                    items.add(PiItem.Notice("Ultima resposta copiada (${text.length} chars)", error = false))
                } else {
                    items.add(PiItem.Notice("Nada para copiar ainda", error = true))
                }
            }
            "get_tree" -> items.add(PiItem.Notice(formatTree(data), error = false))
        }
    }

    private fun formatStats(data: JSONObject?): String {
        if (data == null) return "Sem dados"
        val tokens = data.optJSONObject("tokens")
        val usage = data.optJSONObject("contextUsage")
        val sb = StringBuilder()
        sb.append("Sessao: ").append(data.optString("sessionFile", "?")).append('\n')
        sb.append("Mensagens: ").append(data.optInt("userMessages")).append(" user / ")
            .append(data.optInt("assistantMessages")).append(" assistant / ")
            .append(data.optInt("toolCalls")).append(" tools\n")
        sb.append("Tokens: ").append(tokens?.optInt("total") ?: 0).append('\n')
        sb.append("Custo: US$ ").append(String.format(java.util.Locale.US, "%.4f", data.optDouble("cost")))
        if (usage != null) {
            sb.append("\nContexto: ").append(usage.optInt("tokens")).append('/')
                .append(usage.optInt("contextWindow")).append(" (")
                .append(usage.optInt("percent")).append("%)")
        }
        return sb.toString()
    }

    private fun formatTree(data: JSONObject?): String {
        if (data == null) return "Arvore vazia"
        val arr = data.optJSONArray("entries") ?: data.optJSONArray("tree")
        if (arr == null || arr.length() == 0) return "Arvore vazia"
        val sb = StringBuilder()
        for (i in 0 until minOf(arr.length(), 20)) {
            val e = arr.optJSONObject(i) ?: continue
            sb.append(e.optString("role", "?")).append(": ")
                .append(e.optString("preview", e.optString("text", ""))).append('\n')
        }
        return sb.toString().trim()
    }

    private fun copyToClipboard(text: String) {
        val ctx = appContext ?: return
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Pi", text))
        } catch (e: Exception) {
            Log.e(TAG, "falha ao copiar para a area de transferencia", e)
        }
    }

    // ---------------------------------------------------------- comandos

    /** Executa um comando da palette. */
    fun runCommand(cmd: PiCommand) {
        picker = null
        when (cmd.name) {
            "model" -> {
                client.request("get_available_models")
                picker = PiPicker.Models
            }
            "thinking" -> {
                client.request("get_available_thinking_levels")
                picker = PiPicker.Thinking
            }
            "new" -> client.request("new_session")
            "compact" -> client.request("compact")
            "abort" -> client.request("abort")
            "queue" -> client.request("clear_queue")
            "stats" -> client.request("get_session_stats")
            "copy" -> client.request("get_last_assistant_text")
            "export" -> client.request("export_html")
            "name" -> textDialog = PiTextDialog("Nome da sessao", "minha-sessao") {
                client.request("set_session_name", JSONObject().put("name", it))
            }
            "tree" -> client.request("get_tree")
            "bash" -> textDialog = PiTextDialog("Comando shell", "ls -la") {
                client.request("bash", JSONObject().put("command", it))
            }
            else -> send("/" + cmd.name)
        }
    }

    fun chooseModel(choice: PiModelChoice) {
        picker = null
        client.request(
            "set_model",
            JSONObject().put("provider", choice.provider).put("modelId", choice.id)
        )
    }

    fun chooseThinking(level: String) {
        picker = null
        client.request("set_thinking_level", JSONObject().put("level", level))
    }

    fun availableModels(): List<PiModelChoice> = models.toList()

    fun availableThinkingLevels(): List<String> = thinkingLevels.toList()

    fun openCommandPalette() {
        client.request("get_commands")
        picker = PiPicker.Commands
    }

    // ------------------------------------------------- dialogos de extensao

    fun respondExtensionValue(id: String, value: String) {
        extDialog = null
        client.respondExtensionUi(id, value)
    }

    fun respondExtensionConfirmed(id: String, confirmed: Boolean) {
        extDialog = null
        client.respondExtensionUiConfirmed(id, confirmed)
    }

    fun cancelExtension(id: String) {
        extDialog = null
        client.cancelExtensionUi(id)
    }

    // ---------------------------------------------------------- anexos

    fun addAttachment(attachment: PiAttachment) {
        attachments.add(attachment)
    }

    fun removeAttachment(attachment: PiAttachment) {
        attachments.remove(attachment)
    }

    fun clearAttachments() {
        attachments.clear()
    }

    // ---------------------------------------------------------- envio

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
        val trimmed = text.trim()
        val pending = attachments.toList()
        attachments.clear()
        if (trimmed.isEmpty() && pending.isEmpty()) return

        val images = mutableListOf<Pair<String, String>>()
        val refs = mutableListOf<String>()
        for (a in pending) {
            if (a.isImage && a.base64 != null) {
                images.add(a.mime to a.base64)
            } else if (a.path != null) {
                refs.add(a.path)
            }
        }

        var message = trimmed
        if (refs.isNotEmpty()) {
            val list = refs.joinToString("\n") { "- $it" }
            message = if (message.isEmpty()) "Leia o(s) arquivo(s) anexado(s):\n$list"
            else "$message\n\nArquivo(s) para ler:\n$list"
        }

        items.add(PiItem.User(message))
        assistantIndex = -1
        thinkingIndex = -1
        assistantBuffer.setLength(0)
        thinkingBuffer.setLength(0)
        busy = true
        if (!client.sendPrompt(message, images)) {
            // A conexao estava morta: guarda e reenvia assim que reconectar.
            pendingPrompt = message
            start()
        }
    }

    fun clear() {
        items.clear()
        assistantIndex = -1
        thinkingIndex = -1
        toolIndex = -1
    }

    fun attachContext(context: Context) {
        appContext = context.applicationContext
    }
}

package com.newtermux.pi

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.newtermux.features.NewTermuxTheme
import org.json.JSONObject

/** Um item da conversa exibida na tela do Pi. */
sealed class PiItem {
    data class User(val text: String, val images: List<String> = emptyList()) : PiItem()
    data class Assistant(val text: String, val images: List<String> = emptyList()) : PiItem()
    data class Thinking(val text: String, val images: List<String> = emptyList()) : PiItem()
    data class Tool(val name: String, val running: Boolean, val text: String = "", val images: List<String> = emptyList()) : PiItem()
    data class Bash(val text: String) : PiItem()
    data class Notice(val text: String, val error: Boolean = false) : PiItem()
}

/** Sessao salva do Pi (listada pelo daemon). */
data class PiSessionInfo(
    val id: String,
    val title: String,
    val file: String,
    val modified: Long,
    val size: Long,
    val messages: Int,
    val userMessages: Int,
    val cwd: String
)

/** Comando da palette. [kind]: builtin, extension, prompt ou skill. */
data class PiCommand(
    val name: String,
    val label: String,
    val description: String,
    val kind: String
)

/** Qual seletor esta aberto na UI. */
enum class PiPicker { Commands, Models, Thinking, Sessions, Theme, Memory, Engines, Providers }

/** Uma memoria do Hermes (lista de memories). */
data class PiMemory(
    val file: String,
    val text: String,
    val created: String,
    val last: String
)

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
    val isImage: Boolean,
    /** Primeiras linhas do documento, para mostrar no chip. */
    val preview: String? = null
)

/** Conteudo compartilhado de outro app (texto ou imagem). */
data class PiShared(val text: String?, val attachment: PiAttachment?)

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

    /** Seletor aberto (comandos, modelos, niveis de raciocinio ou sessoes). */
    var picker by mutableStateOf<PiPicker?>(null)

    /** Sessoes salvas do Pi. */
    var sessions by mutableStateOf<List<PiSessionInfo>>(emptyList())
        private set

    /** Filtro de busca das sessoes (titulo). */
    var sessionQuery by mutableStateOf("")

    /** Memorias do Hermes (lidas pelo daemon). */
    var memories by mutableStateOf<List<PiMemory>>(emptyList())
        private set

    /** Engines disponiveis (Pi Node x Pi-Bolt nativo). */
    var engines by mutableStateOf<List<PiEngine>>(emptyList())
        private set
    var currentEngine by mutableStateOf("")
        private set

    /** Provedores do Pi (auth). */
    var providers by mutableStateOf<List<PiProvider>>(emptyList())
        private set

    /** Filtro de busca das memorias. */
    var memoryQuery by mutableStateOf("")

    /** Cor de destaque em uso (acento do app inteiro). */
    var accentColor by mutableStateOf(0)
        private set

    /** Texto da ultima resposta, pra notificar quando o turno terminar. */
    private var lastAssistantText = ""

    /** Arquivo da sessao ativa (pra destacar na lista). */
    var currentSessionFile by mutableStateOf("")
        private set

    /** Dialogo pedindo texto. */
    var textDialog by mutableStateOf<PiTextDialog?>(null)

    /** Dialogo de extensao do Pi. */
    var extDialog by mutableStateOf<PiExtDialog?>(null)

    /** Anexos pendentes para a proxima mensagem. */
    val attachments = mutableStateListOf<PiAttachment>()

    /** Conteudo compartilhado de outro app, aguardando ser aplicado na UI. */
    var shared by mutableStateOf<PiShared?>(null)

    private val client = PiRpcClient(DEFAULT_PORT)
    private var appContext: Context? = null

    private var assistantIndex = -1
    private var thinkingIndex = -1
    private var toolIndex = -1
    private val assistantBuffer = StringBuilder()
    private val thinkingBuffer = StringBuilder()

    /** Caminhos inseridos via mencao @, aguardando o envio. */
    private val mentionedPaths = mutableSetOf<String>()

    /** Para a execucao atual (botao stop durante o streaming). */
    fun abort() {
        client.request("abort")
    }

    /** Registra um caminho inserido via @ pra virar ref no envio. */
    fun noteMentionedPath(path: String) {
        if (path.isNotBlank()) mentionedPaths.add(path)
    }

    /** Prompt (e anexos) que nao conseguiu sair porque a conexao tinha morrido. */
    private var pendingPrompt: Pair<String, List<Pair<String, String>>>? = null

    /** Auto-start do daemon: quantas vezes ja tentamos subir sozinho. */
    private var autoStartTries = 0
    private var autoStartInFlight = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val models = mutableStateListOf<PiModelChoice>()
    private val thinkingLevels = mutableStateListOf<String>()

    data class PiModelChoice(val id: String, val name: String, val provider: String)

    data class PiEngine(val id: String, val name: String, val bin: String, val available: Boolean)

    data class PiProvider(val id: String, val name: String, val env: String, val featured: Boolean, val configured: Boolean, val viaEnv: Boolean)

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

    private val listener: PiRpcClient.Listener = object : PiRpcClient.Listener {

        override fun onConnected() {
            connected = true
            status = "conectado"
            client.request("get_commands")
            client.request("get_state")
            client.request("get_available_models")
            client.request("get_available_thinking_levels")
            client.request("nebula_engines")
            client.request("nebula_providers")
            pendingPrompt?.let { (text, pendingImages) ->
                pendingPrompt = null
                Log.i(TAG, "reenviando prompt pendente")
                busy = true
                client.sendPrompt(text, pendingImages)
            }
        }

        override fun onDisconnected(reason: String?) {
            handleDisconnect(reason)
        }

        override fun onResponse(command: String, success: Boolean, data: JSONObject?, error: String?) {
            if (!success) {
                val message = error.orEmpty().ifEmpty { "falha no comando $command" }
                items.add(PiItem.Notice(message, error = true))
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
            lastAssistantText = assistantBuffer.toString()
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

        override fun onToolEnd(name: String, text: String, images: List<String>) {
            // Procura de tras pra frente a tool ainda rodando com o mesmo nome
            // (suporta tools em paralelo; cai no toolIndex como fallback).
            var idx = -1
            for (i in items.size - 1 downTo 0) {
                val it = items[i]
                if (it is PiItem.Tool && it.running && it.name == name) {
                    idx = i
                    break
                }
            }
            if (idx < 0 && toolIndex >= 0 && toolIndex < items.size) {
                val current = items[toolIndex]
                if (current is PiItem.Tool && current.running) idx = toolIndex
            }
            if (idx >= 0) {
                val current = items[idx] as PiItem.Tool
                items[idx] = PiItem.Tool(current.name, running = false, text = text, images = images)
                if (idx == toolIndex) toolIndex = -1
            }
        }

        override fun onAssistantMessage(text: String, thinking: String, images: List<String>) {
            if (assistantIndex >= 0 && assistantIndex < items.size) {
                val current = items[assistantIndex]
                if (current is PiItem.Assistant) {
                    items[assistantIndex] = PiItem.Assistant(
                        text.ifEmpty { current.text },
                        images.ifEmpty { current.images }
                    )
                }
            }
            assistantIndex = -1
            thinkingIndex = -1
        }

        override fun onSettled() {
            busy = false
            // Avisa fora do app: se o painel estiver em background, o usuario
            // precisa saber que a resposta ficou pronta.
            val ctx = appContext
            if (ctx != null) {
                val summary = lastAssistantText.ifBlank { "Resposta pronta." }
                Thread { PiNotify.turnDone(ctx, summary) }.apply {
                    isDaemon = true
                    name = "pi-notify"
                    start()
                }
            }
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

    /** Desconexao com auto-start do daemon (evita recursao de tipos no listener). */
    private fun handleDisconnect(reason: String?) {
        connected = false
        busy = false
        val msg: String = reason ?: "desconectado"
        val ctx: android.content.Context? = appContext
        val canRetry: Boolean = ctx != null && !autoStartInFlight && autoStartTries < 3
        val isConnFail: Boolean = msg.contains("timeout", ignoreCase = true) ||
            msg.contains("recus", ignoreCase = true) ||
            msg.contains("failed", ignoreCase = true) ||
            msg.contains("refused", ignoreCase = true) ||
            msg.contains("daemon", ignoreCase = true) ||
            msg == "desconectado"
        if (canRetry && isConnFail) {
            autoStartInFlight = true
            autoStartTries += 1
            val attempt: Int = autoStartTries
            status = "subindo daemon (tentativa $attempt/3)..."
            items.add(PiItem.Notice("Sem conexao — subindo daemon sozinho (tentativa $attempt/3)...", error = false))
            val appCtx: android.content.Context = ctx!!
            val t = Thread {
                val sent: Boolean = PiDaemon.ensureRunning(appCtx)
                try {
                    Thread.sleep(2500)
                } catch (_: Exception) {
                }
                mainHandler.post {
                    autoStartInFlight = false
                    if (!client.isConnected()) {
                        status = "conectando..."
                        val l: PiRpcClient.Listener = listener
                        client.connect(l)
                        if (!sent && attempt >= 3) {
                            items.add(
                                PiItem.Notice(
                                    "Não consegui subir sozinho. Rode 'pi-rpc' no Termux e toque em reconectar.",
                                    error = true
                                )
                            )
                        }
                    }
                }
            }
            t.isDaemon = true
            t.name = "pi-daemon-autostart"
            t.start()
            return
        }
        status = msg
        items.add(PiItem.Notice(status, error = true))
    }

    // ---------------------------------------------------------- respostas

    private fun handleResponse(command: String, data: JSONObject?) {
        when (command) {
            "prompt" -> {
                // Mensagem enviada no meio da execucao vai como steer: o Pi
                // enfileira ("queued") e entrega na proxima volta sozinho.
                if (data?.optString("disposition") == "queued") {
                    items.add(PiItem.Notice("Na fila — entra na proxima resposta", error = false))
                }
            }
            "get_state" -> {
                val m = data?.optJSONObject("model")
                val id = m?.optString("id")?.takeIf { it.isNotEmpty() }
                if (id != null) model = id
                val thinking = data?.optString("thinkingLevel").orEmpty()
                status = if (thinking.isNotEmpty()) "$model ($thinking)" else model.ifEmpty { "conectado" }
                val file = data?.optString("sessionFile").orEmpty()
                if (file.isNotEmpty()) currentSessionFile = file
            }
            "nebula_sessions" -> {
                val list = mutableListOf<PiSessionInfo>()
                val arr = data?.optJSONArray("sessions")
                for (i in 0 until (arr?.length() ?: 0)) {
                    val s = arr!!.optJSONObject(i) ?: continue
                    list.add(
                        PiSessionInfo(
                            id = s.optString("id"),
                            title = s.optString("title"),
                            file = s.optString("file"),
                            modified = s.optLong("modified"),
                            size = s.optLong("size"),
                            messages = s.optInt("messages"),
                            userMessages = s.optInt("userMessages"),
                            cwd = s.optString("cwd")
                        )
                    )
                }
                sessions = list
            }
            "nebula_delete_session" -> {
                val deleted = data?.optBoolean("deleted") ?: false
                items.add(
                    PiItem.Notice(
                        if (deleted) "Sessao apagada" else "Nao consegui apagar a sessao",
                        error = !deleted
                    )
                )
                client.request("nebula_sessions")
            }
            "switch_session" -> {
                val cancelled = data?.optBoolean("cancelled") ?: false
                if (cancelled) {
                    items.add(PiItem.Notice("Troca de sessao cancelada", error = true))
                } else {
                    client.request("get_messages")
                }
            }
            "get_messages" -> reloadMessages(data)
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
                // Mantem a lista de opcoes fresca apos a troca.
                client.request("get_available_models")
                client.request("get_available_thinking_levels")
            "set_thinking_level" -> {
                val level = data?.optString("level").orEmpty()
                status = if (level.isNotEmpty()) "$model ($level)" else model
                client.request("get_state")
            }
            "get_session_stats" -> items.add(PiItem.Notice(formatStats(data), error = false))
            "new_session" -> {
                clear()
                items.add(PiItem.Notice("Nova sessao iniciada", error = false))
                client.request("get_state")
                client.request("nebula_sessions")
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
            "nebula_memory" -> {
                val list = mutableListOf<PiMemory>()
                val arr = data?.optJSONArray("memories")
                for (i in 0 until (arr?.length() ?: 0)) {
                    val m = arr!!.optJSONObject(i) ?: continue
                    val text = m.optString("text")
                    if (text.isBlank()) continue
                    list.add(
                        PiMemory(
                            file = m.optString("file"),
                            text = text,
                            created = m.optString("created"),
                            last = m.optString("last")
                        )
                    )
                }
                memories = list
            }
            "nebula_memory_save" -> {
                val saved = data?.optBoolean("saved") ?: false
                items.add(
                    PiItem.Notice(
                        if (saved) "Memoria salva" else "Nao consegui salvar a memoria",
                        error = !saved
                    )
                )
                if (saved) client.request("nebula_memory")
            }
            "nebula_engines" -> {
                val list = mutableListOf<PiEngine>()
                val arr = data?.optJSONArray("engines")
                for (i in 0 until (arr?.length() ?: 0)) {
                    val e = arr!!.optJSONObject(i) ?: continue
                    list.add(PiEngine(e.optString("id"), e.optString("name").ifEmpty { e.optString("id") }, e.optString("bin"), e.optBoolean("available", true)))
                }
                engines = list
                val cur = data?.optString("current").orEmpty()
                if (cur.isNotEmpty()) currentEngine = cur
            }
            "nebula_set_engine" -> {
                val id = data?.optString("engine").orEmpty()
                if (id.isNotEmpty()) currentEngine = id
                items.add(PiItem.Notice("Engine trocada para $id — sessao reiniciada (anterior salva em sessoes)", error = false))
                clear()
                client.request("nebula_engines")
                client.request("get_state")
                client.request("nebula_sessions")
            }
            "nebula_providers" -> {
                val list = mutableListOf<PiProvider>()
                val arr = data?.optJSONArray("providers")
                for (i in 0 until (arr?.length() ?: 0)) {
                    val p = arr!!.optJSONObject(i) ?: continue
                    list.add(PiProvider(p.optString("id"), p.optString("name").ifEmpty { p.optString("id") }, p.optString("env"), p.optBoolean("featured"), p.optBoolean("configured"), p.optBoolean("viaEnv")))
                }
                providers = list
            }
            "nebula_provider_save" -> {
                items.add(PiItem.Notice("Provedor configurado — modelos liberados em /model", error = false))
                client.request("nebula_providers")
                client.request("get_available_models")
            }
            "nebula_provider_delete" -> {
                val ok = data?.optBoolean("deleted") ?: false
                items.add(PiItem.Notice(if (ok) "Provedor removido" else "Nada para remover (pode ser via env)", error = !ok))
                client.request("nebula_providers")
            }
        }
    }

    /** Recarrega a conversa depois de trocar de sessao. */
    private fun reloadMessages(data: JSONObject?) {
        val arr = data?.optJSONArray("messages") ?: return
        clear()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            when (m.optString("role")) {
                "user" -> {
                    val (text, images) = extractContent(m)
                    if (text.isNotBlank() || images.isNotEmpty()) {
                        items.add(PiItem.User(text, images))
                    }
                }
                "assistant" -> {
                    val (text, images) = extractContent(m)
                    if (text.isNotBlank() || images.isNotEmpty()) {
                        items.add(PiItem.Assistant(text, images))
                    }
                }
                "toolResult" -> {
                    // Resultado de tool (ex.: `read` em imagem): mostra como embed.
                    // Sem isso, imagem lida pelo Pi ia so pro modelo, sem preview na UI.
                    val (text, images) = extractContent(m)
                    val name = m.optString("toolName").ifEmpty { "tool" }
                    var preview = text.trim()
                    if (preview.length > PiRpcClient.MAX_TOOL_TEXT) {
                        preview = preview.take(PiRpcClient.MAX_TOOL_TEXT).trimEnd() + "\u2026 (+continua)"
                    }
                    val imgs = images.take(PiRpcClient.MAX_TOOL_IMAGES)
                    if (preview.isNotBlank() || imgs.isNotEmpty()) {
                        items.add(PiItem.Tool(name, running = false, text = preview, images = imgs))
                    }
                }
            }
        }
        items.add(PiItem.Notice("Sessao carregada", error = false))
    }

    /** Extrai texto e imagens do conteudo de uma mensagem. */
    private fun extractContent(message: JSONObject): Pair<String, List<String>> {
        val text = StringBuilder()
        val images = mutableListOf<String>()
        val content = message.opt("content")
        when (content) {
            is String -> text.append(content)
            is org.json.JSONArray -> {
                for (i in 0 until content.length()) {
                    val block = content.optJSONObject(i) ?: continue
                    when (block.optString("type")) {
                        "text" -> text.append(block.optString("text"))
                        "image" -> {
                            val data = block.optString("data")
                            if (data.isNotEmpty()) images.add(data)
                        }
                    }
                }
            }
            else -> Unit
        }
        return text.toString().trim() to images
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
        refreshCommands()
        picker = PiPicker.Commands
    }

    /** Recarrega a lista de comandos (palette e autocomplete usam a mesma). */
    fun refreshCommands() {
        client.request("get_commands")
    }

    // ------------------------------------------------------------ sessoes

    fun openSessions() {
        client.request("nebula_sessions")
        picker = PiPicker.Sessions
    }

    /** Sessoes filtradas pelo texto da busca (titulo ou caminho). */
    fun filteredSessions(): List<PiSessionInfo> {
        val q = sessionQuery.trim()
        if (q.isEmpty()) return sessions
        val needle = q.lowercase()
        return sessions.filter {
            it.title.lowercase().contains(needle) || it.file.lowercase().contains(needle)
        }
    }

    // ------------------------------------------------------------- memoria

    fun openMemory() {
        client.request("nebula_memory")
        picker = PiPicker.Memory
    }

    /** Memorias filtradas pela busca. */
    fun filteredMemories(): List<PiMemory> {
        val q = memoryQuery.trim()
        if (q.isEmpty()) return memories
        val needle = q.lowercase()
        return memories.filter {
            it.text.lowercase().contains(needle) || it.file.lowercase().contains(needle)
        }
    }

    /** Salva uma memoria nova (o daemon escreve no arquivo do Hermes). */
    fun saveMemory(text: String, file: String = "MEMORY.md") {
        client.request(
            "nebula_memory_save",
            JSONObject().put("text", text).put("file", file)
        )
    }

    /** Manda uma memoria como contexto pro Pi. */
    fun useMemory(memory: PiMemory) {
        picker = null
        send("Considere esta memoria como contexto:\n\n${memory.text}")
    }

    // --------------------------------------------------------------- tema

    /** Abre o seletor de cor de destaque. */
    fun openTheme() {
        picker = PiPicker.Theme
    }

    fun openEngines() {
        client.request("nebula_engines")
        picker = PiPicker.Engines
    }

    fun chooseEngine(id: String) {
        picker = null
        items.add(PiItem.Notice("Trocando engine para $id...", error = false))
        client.request("nebula_set_engine", JSONObject().put("engine", id))
    }

    fun availableEngines(): List<PiEngine> = engines

    fun openProviders() {
        client.request("nebula_providers")
        picker = PiPicker.Providers
    }

    fun availableProviders(): List<PiProvider> = providers

    fun askProviderKey(provider: PiProvider) {
        textDialog = PiTextDialog("Chave: " + provider.name, provider.env) {
            client.request("nebula_provider_save", JSONObject().put("provider", provider.id).put("key", it))
        }
    }

    fun removeProvider(provider: PiProvider) {
        client.request("nebula_provider_delete", JSONObject().put("provider", provider.id))
    }

    /** Troca o acento do app inteiro (o Termux tambem usa esse acento). */
    fun setAccent(color: Int) {
        val ctx = appContext ?: return
        NewTermuxTheme.setAccentColor(ctx, color)
        accentColor = color
        com.newtermux.compose.NewTermuxAccent.set(ctx, color)
        picker = null
    }

    /** Cores de destaque disponiveis (inclui as presets do Termux + Nebula). */
    fun accentOptions(): List<Pair<Int, String>> {
        return NewTermuxTheme.COLORS.mapIndexed { i, c -> c to NewTermuxTheme.COLOR_NAMES[i] }
    }

    /** Comeca uma sessao nova (o Pi cria o arquivo quando houver atividade). */
    fun newSession() {
        picker = null
        client.request("new_session")
    }

    fun switchSession(info: PiSessionInfo) {
        picker = null
        items.clear()
        client.request("switch_session", JSONObject().put("sessionPath", info.file))
    }

    fun deleteSession(info: PiSessionInfo) {
        client.request("nebula_delete_session", JSONObject().put("path", info.file))
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
        // Toque manual em reconectar libera novo ciclo de auto-start.
        if (!autoStartInFlight) autoStartTries = 0
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
        // @-mencoes: so vira ref se o caminho continua no texto (se o
        // usuario apagou, ignora). Limpa o pendente de qualquer jeito.
        val it = mentionedPaths.iterator()
        while (it.hasNext()) {
            val p = it.next()
            if (message.contains(p) && !refs.contains(p)) refs.add(p)
            it.remove()
        }
        // Caminhos absolutos colados no texto viram ref sozinhos.
        if (refs.size < 10) {
            message.split(Regex("\\s+"))
                .map { tok -> tok.trim().trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '"', '\'') }
                .filter { tok -> tok.startsWith("/") && tok.length in 2..300 }
                .distinct()
                .take(10 - refs.size)
                .forEach { cand ->
                    try {
                        val f = java.io.File(cand)
                        if (f.isFile && f.canRead() && f.length() < 20L * 1024 * 1024 && !refs.contains(cand)) {
                            refs.add(cand)
                        }
                    } catch (_: Exception) {
                    }
                }
        }
        if (refs.isNotEmpty()) {
            val list = refs.joinToString("\n") { "- $it" }
            message = if (message.isEmpty()) "Leia o(s) arquivo(s) anexado(s):\n$list"
            else "$message\n\nArquivo(s) para ler:\n$list"
        }

        items.add(PiItem.User(message, images.map { it.second }))
        assistantIndex = -1
        thinkingIndex = -1
        assistantBuffer.setLength(0)
        thinkingBuffer.setLength(0)
        // Se o Pi esta no meio de uma execucao, a mensagem vai como steer:
        // entra automaticamente na proxima volta em vez de dar erro e se perder.
        // Comandos de extensao ("/...") executam imediato mesmo em streaming,
        // entao nao levam streamingBehavior.
        val steer = busy && !message.startsWith("/")
        busy = true
        if (!client.sendPrompt(message, images, if (steer) "steer" else null)) {
            // A conexao estava morta: guarda e reenvia assim que reconectar.
            pendingPrompt = message to images
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
        accentColor = NewTermuxTheme.getAccentColor(appContext!!)
        com.newtermux.compose.NewTermuxAccent.load(appContext!!)
        PiNotify.ensureChannel(appContext!!)
    }
}

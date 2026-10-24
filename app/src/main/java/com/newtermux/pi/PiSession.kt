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

/** One item of the conversation shown on the Pi screen. */
sealed class PiItem {
    data class User(val text: String, val images: List<String> = emptyList()) : PiItem()
    data class Assistant(val text: String, val images: List<String> = emptyList()) : PiItem()
    data class Thinking(val text: String, val images: List<String> = emptyList()) : PiItem()
    data class Tool(val name: String, val running: Boolean, val text: String = "", val images: List<String> = emptyList()) : PiItem()
    data class Bash(val text: String) : PiItem()
    data class Notice(val text: String, val error: Boolean = false) : PiItem()
}

/** Pi session saved to disk (listed by the daemon). */
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

/** Which picker is open in the UI. */
enum class PiPicker { Commands, Models, Thinking, Sessions, Theme, Memory, Engines, Providers }

/** A Hermes memory (list of memories). */
data class PiMemory(
    val file: String,
    val text: String,
    val created: String,
    val last: String
)

/** Dialog that asks the user for some text. */
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

/** Attachment ready to be sent. */
data class PiAttachment(
    val name: String,
    val mime: String,
    val path: String?,
    val base64: String?,
    val isImage: Boolean,
    /** First lines of the document, to show in the chip. */
    val preview: String? = null
)

/** Content shared from another app (text or image). */
data class PiShared(val text: String?, val attachment: PiAttachment?)

/**
 * Pi session state, shared between the Activity and the RPC client.
 * Kept in an object so it survives Activity recreation (rotation, etc).
 */
object PiSession {

    const val DEFAULT_PORT = PiRpcClient.DEFAULT_PORT
    private const val TAG = "NebulaPi"

    val items = mutableStateListOf<PiItem>()

    var connected by mutableStateOf(false)
        private set
    var status by mutableStateOf("disconnected")
        private set
    var model by mutableStateOf("")
        private set

    /** Current thinking level (off, minimal, low, medium, high, xhigh, max). */
    var thinkingLevel by mutableStateOf("")
        private set

    var busy by mutableStateOf(false)
        private set

    /** Palette commands: built-in (RPC) + discovered by Pi. */
    var commands by mutableStateOf<List<PiCommand>>(emptyList())
        private set

    /** Open picker (commands, models, thinking levels or sessions). */
    var picker by mutableStateOf<PiPicker?>(null)

    /** Pi sessions saved to disk. */
    var sessions by mutableStateOf<List<PiSessionInfo>>(emptyList())
        private set

    /** Session search filter (title). */
    var sessionQuery by mutableStateOf("")

    /** Hermes memories (read by the daemon). */
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

    /** Memory search filter. */
    var memoryQuery by mutableStateOf("")

    /** Accent color in use (accent of the whole app). */
    var accentColor by mutableStateOf(0)
        private set

    /** Last response text, to notify when the turn ends. */
    private var lastAssistantText = ""

    /** File of the active session (to highlight it in the list). */
    var currentSessionFile by mutableStateOf("")

    /** Session whose saved model must be re-applied once get_state reports its file. */
    private var restoreModelFor: String? = null
    /** Set while a brand new session must fall back to the configured default model. */
    private var restoreDefaultPending = false
    /** Model picked while the session still had no file on disk. */
    private var pendingModelChoice: PiSessionModels.Choice? = null
    /** Model from Pi's settings.json, used to reset new sessions. */
    private var defaultModelChoice: PiSessionModels.Choice? = null
        private set

    /** Dialogo pedindo texto. */
    var textDialog by mutableStateOf<PiTextDialog?>(null)

    /** Dialogo de extensao do Pi. */
    var extDialog by mutableStateOf<PiExtDialog?>(null)

    /** Attachments pending for the next message. */
    val attachments = mutableStateListOf<PiAttachment>()

    /** Content shared from another app, waiting to be applied in the UI. */
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

    /** Stops the current run (stop button during streaming). */
    fun abort() {
        client.request("abort")
    }

    /** Registers a path inserted via @ so it becomes a ref when sending. */
    fun noteMentionedPath(path: String) {
        if (path.isNotBlank()) mentionedPaths.add(path)
    }

    /** Prompt (and attachments) that could not go out because the connection had died. */
    private var pendingPrompt: Pair<String, List<Pair<String, String>>>? = null

    /** Daemon auto-start: how many times we already tried to start it on our own. */
    private var autoStartTries = 0
    private var autoStartInFlight = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val models = mutableStateListOf<PiModelChoice>()
    private val thinkingLevels = mutableStateListOf<String>()

    data class PiModelChoice(val id: String, val name: String, val provider: String)

    data class PiEngine(val id: String, val name: String, val bin: String, val available: Boolean)

    data class PiProvider(val id: String, val name: String, val env: String, val featured: Boolean, val configured: Boolean, val viaEnv: Boolean)

    /** Commands the app knows how to run via RPC (equivalent to the TUI ones). */
    private val builtinCommands = listOf(
        PiCommand("model", "/model", "Switch model", "builtin"),
        PiCommand("thinking", "/thinking", "Thinking level", "builtin"),
        PiCommand("new", "/new", "New session", "builtin"),
        PiCommand("compact", "/compact", "Compactar o contexto", "builtin"),
        PiCommand("abort", "/abort", "Stop the current operation", "builtin"),
        PiCommand("queue", "/clear_queue", "Clear the message queue", "builtin"),
        PiCommand("stats", "/session", "Session stats and cost", "builtin"),
        PiCommand("copy", "/copy", "Copy Pi last response", "builtin"),
        PiCommand("export", "/export", "Export the session as HTML", "builtin"),
        PiCommand("name", "/name", "Name the session", "builtin"),
        PiCommand("tree", "/tree", "View the session tree", "builtin"),
        PiCommand("bash", "/bash", "Run a shell command", "builtin")
    )

    private val listener: PiRpcClient.Listener = object : PiRpcClient.Listener {

        override fun onConnected() {
            connected = true
            status = "connected"
            client.request("get_commands")
            client.request("get_state")
            client.request("nebula_default_model")
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
                val message = error.orEmpty().ifEmpty { "command failed: $command" }
                items.add(PiItem.Notice(message, error = true))
                return
            }
            handleResponse(command, data)
        }

        override fun onUserMessage(text: String) {
            // The UI already shows the sent message; avoids duplicating.
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
            // Search backwards for the tool still running with the same name
            // (supports parallel tools; falls back to toolIndex).
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
            // Notifies outside the app: if the panel is in the background, the user
            // needs to know the response is ready.
            val ctx = appContext
            if (ctx != null) {
                val summary = lastAssistantText.ifBlank { "Response ready." }
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

    /** Disconnection with daemon auto-start (avoids type recursion in the listener). */
    private fun handleDisconnect(reason: String?) {
        connected = false
        busy = false
        val msg: String = reason ?: "disconnected"
        val ctx: android.content.Context? = appContext
        val canRetry: Boolean = ctx != null && !autoStartInFlight && autoStartTries < 3
        val isConnFail: Boolean = msg.contains("timeout", ignoreCase = true) ||
            msg.contains("recus", ignoreCase = true) ||
            msg.contains("failed", ignoreCase = true) ||
            msg.contains("refused", ignoreCase = true) ||
            msg.contains("daemon", ignoreCase = true) ||
            msg == "disconnected"
        if (canRetry && isConnFail) {
            autoStartInFlight = true
            autoStartTries += 1
            val attempt: Int = autoStartTries
            status = "starting daemon (attempt $attempt/3)..."
            items.add(PiItem.Notice("No connection — starting the daemon on my own (attempt $attempt/3)...", error = false))
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
                        status = "connecting..."
                        val l: PiRpcClient.Listener = listener
                        client.connect(l)
                        if (!sent && attempt >= 3) {
                            items.add(
                                PiItem.Notice(
                                    "Could not start it on my own. Run 'pi-rpc' in Termux and tap reconnect.",
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
                // A message sent mid-run goes as steer: Pi
                // queues it ("queued") and delivers it on the next turn by itself.
                if (data?.optString("disposition") == "queued") {
                    items.add(PiItem.Notice("Queued — goes out with the next response", error = false))
                }
            }
            "get_state" -> {
                val m = data?.optJSONObject("model")
                val id = m?.optString("id")?.takeIf { it.isNotEmpty() }
                if (id != null) model = id
                thinkingLevel = data?.optString("thinkingLevel").orEmpty()
                status = "connected"
                val file = data?.optString("sessionFile").orEmpty()
                if (file.isNotEmpty()) currentSessionFile = file
                afterStateResolved()
            }
            "nebula_default_model" -> {
                val provider = data?.optString("provider").orEmpty()
                val modelId = data?.optString("modelId").orEmpty()
                if (provider.isNotEmpty() && modelId.isNotEmpty()) {
                    defaultModelChoice = PiSessionModels.Choice(provider, modelId)
                }
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
                        if (deleted) "Session deleted" else "Could not delete the session",
                        error = !deleted
                    )
                )
                client.request("nebula_sessions")
            }
            "switch_session" -> {
                val cancelled = data?.optBoolean("cancelled") ?: false
                if (cancelled) {
                    restoreModelFor = null
                    items.add(PiItem.Notice("Session switch cancelled", error = true))
                } else {
                    client.request("get_messages")
                    client.request("get_state")
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
                // The set_model response carries the Model object straight in data.
                val id = data?.optString("id")
                if (!id.isNullOrEmpty()) model = id
                status = "connected"
                // Keeps the option list fresh after the switch.
                client.request("get_available_models")
                client.request("get_available_thinking_levels")
            }
            "set_thinking_level" -> {
                val level = data?.optString("level").orEmpty()
                if (level.isNotEmpty()) thinkingLevel = level
                client.request("get_state")
            }
            "get_session_stats" -> items.add(PiItem.Notice(formatStats(data), error = false))
            "new_session" -> {
                clear()
                items.add(PiItem.Notice("New session started", error = false))
                client.request("get_state")
                client.request("nebula_sessions")
            }
            "compact" -> items.add(PiItem.Notice("Contexto compactado", error = false))
            "export_html" -> items.add(PiItem.Notice("Session exported: " + (data?.optString("path") ?: "?"), error = false))
            "get_last_assistant_text" -> {
                val text = data?.optString("text").orEmpty()
                if (text.isNotEmpty()) {
                    copyToClipboard(text)
                    items.add(PiItem.Notice("Last response copied (${text.length} chars)", error = false))
                } else {
                    items.add(PiItem.Notice("Nothing to copy yet", error = true))
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
                        if (saved) "Memory saved" else "Could not save the memory",
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
                items.add(PiItem.Notice("Engine switched to $id — session restarted (previous one saved under sessions)", error = false))
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
                items.add(PiItem.Notice("Provider configured — models unlocked in /model", error = false))
                client.request("nebula_providers")
                client.request("get_available_models")
            }
            "nebula_provider_delete" -> {
                val ok = data?.optBoolean("deleted") ?: false
                items.add(PiItem.Notice(if (ok) "Provider removed" else "Nothing to remove (it may come from env)", error = !ok))
                client.request("nebula_providers")
            }
        }
    }

    /** Reloads the conversation after switching sessions. */
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
                    // Tool result (e.g. `read` on an image): show it as an embed.
                    // Without this, an image read by Pi only went to the model, with no UI preview.
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
        items.add(PiItem.Notice("Session loaded", error = false))
    }

    /** Extracts text and images from a message content. */
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
        if (data == null) return "No data"
        val tokens = data.optJSONObject("tokens")
        val usage = data.optJSONObject("contextUsage")
        val sb = StringBuilder()
        sb.append("Session: ").append(data.optString("sessionFile", "?")).append('\n')
        sb.append("Messages: ").append(data.optInt("userMessages")).append(" user / ")
            .append(data.optInt("assistantMessages")).append(" assistant / ")
            .append(data.optInt("toolCalls")).append(" tools\n")
        sb.append("Tokens: ").append(tokens?.optInt("total") ?: 0).append('\n')
        sb.append("Cost: US$ ").append(String.format(java.util.Locale.US, "%.4f", data.optDouble("cost")))
        if (usage != null) {
            sb.append("\nContext: ").append(usage.optInt("tokens")).append('/')
                .append(usage.optInt("contextWindow")).append(" (")
                .append(usage.optInt("percent")).append("%)")
        }
        return sb.toString()
    }

    private fun formatTree(data: JSONObject?): String {
        if (data == null) return "Empty tree"
        val arr = data.optJSONArray("entries") ?: data.optJSONArray("tree")
        if (arr == null || arr.length() == 0) return "Empty tree"
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
            Log.e(TAG, "failed to copy to clipboard", e)
        }
    }

    // -------------------------------------------------------------- commands

    /** Runs a palette command. */
    fun runCommand(cmd: PiCommand) {
        picker = null
        when (cmd.name) {
            "model" -> openModels()
            "thinking" -> openThinking()
            "new" -> client.request("new_session")
            "compact" -> client.request("compact")
            "abort" -> client.request("abort")
            "queue" -> client.request("clear_queue")
            "stats" -> client.request("get_session_stats")
            "copy" -> client.request("get_last_assistant_text")
            "export" -> client.request("export_html")
            "name" -> textDialog = PiTextDialog("Session name", "my-session") {
                client.request("set_session_name", JSONObject().put("name", it))
            }
            "tree" -> client.request("get_tree")
            "bash" -> textDialog = PiTextDialog("Shell command", "ls -la") {
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
        // Remember the choice for this session: Pi would go back to the default model otherwise.
        val ctx = appContext
        val file = currentSessionFile
        if (ctx != null && file.isNotEmpty()) {
            PiSessionModels.put(ctx, file, choice.provider, choice.id)
            pendingModelChoice = null
        } else {
            pendingModelChoice = PiSessionModels.Choice(choice.provider, choice.id)
        }
    }

    /**
     * Runs after every get_state: records a model chosen before the session file existed, and
     * re-applies per-session models. A brand new session falls back to the default model.
     */
    private fun afterStateResolved() {
        val ctx = appContext
        val file = currentSessionFile

        pendingModelChoice?.let { choice ->
            if (ctx != null && file.isNotEmpty()) {
                PiSessionModels.put(ctx, file, choice.provider, choice.modelId)
                pendingModelChoice = null
            }
        }

        val pending = restoreModelFor
        if (pending != null && pending == file) {
            restoreModelFor = null
            val saved = ctx?.let { PiSessionModels.get(it, pending) }
            if (saved != null && saved.modelId != model) {
                client.request(
                    "set_model",
                    JSONObject().put("provider", saved.provider).put("modelId", saved.modelId)
                )
                return
            }
        }

        if (restoreDefaultPending) {
            restoreDefaultPending = false
            val fallback = defaultModelChoice
            if (fallback != null && fallback.modelId != model) {
                client.request(
                    "set_model",
                    JSONObject().put("provider", fallback.provider).put("modelId", fallback.modelId)
                )
            }
        }
    }

    fun chooseThinking(level: String) {
        picker = null
        client.request("set_thinking_level", JSONObject().put("level", level))
    }

    fun availableModels(): List<PiModelChoice> = models.toList()

    fun availableThinkingLevels(): List<String> = thinkingLevels.toList()

    /** Opens the model picker (used by the model pill in the top bar). */
    fun openModels() {
        client.request("get_available_models")
        picker = PiPicker.Models
    }

    /** Opens the thinking level picker (thinking pill in the top bar). */
    fun openThinking() {
        client.request("get_available_thinking_levels")
        picker = PiPicker.Thinking
    }

    fun openCommandPalette() {
        refreshCommands()
        picker = PiPicker.Commands
    }

    /** Reloads the command list (palette and autocomplete share the same one). */
    fun refreshCommands() {
        client.request("get_commands")
    }

    // -------------------------------------------------------------- sessions

    fun openSessions() {
        client.request("nebula_sessions")
        picker = PiPicker.Sessions
    }

    /** Sessions filtered by the search text (title or path). */
    fun filteredSessions(): List<PiSessionInfo> {
        val q = sessionQuery.trim()
        if (q.isEmpty()) return sessions
        val needle = q.lowercase()
        return sessions.filter {
            it.title.lowercase().contains(needle) || it.file.lowercase().contains(needle)
        }
    }

    // ---------------------------------------------------------------- memory

    fun openMemory() {
        client.request("nebula_memory")
        picker = PiPicker.Memory
    }

    /** Memories filtered by the search. */
    fun filteredMemories(): List<PiMemory> {
        val q = memoryQuery.trim()
        if (q.isEmpty()) return memories
        val needle = q.lowercase()
        return memories.filter {
            it.text.lowercase().contains(needle) || it.file.lowercase().contains(needle)
        }
    }

    /** Saves a new memory (the daemon writes it to the Hermes file). */
    fun saveMemory(text: String, file: String = "MEMORY.md") {
        client.request(
            "nebula_memory_save",
            JSONObject().put("text", text).put("file", file)
        )
    }

    /** Sends a memory to Pi as context. */
    fun useMemory(memory: PiMemory) {
        picker = null
        send("Consider this memory as context:\n\n${memory.text}")
    }

    // ----------------------------------------------------------------- theme

    /** Opens the accent color picker. */
    fun openTheme() {
        picker = PiPicker.Theme
    }

    fun openEngines() {
        client.request("nebula_engines")
        picker = PiPicker.Engines
    }

    fun chooseEngine(id: String) {
        picker = null
        items.add(PiItem.Notice("Switching engine to $id...", error = false))
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

    /** Changes the accent of the whole app (Termux uses that accent too). */
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

    /** Starts a new session (Pi creates the file once there is activity). */
    fun newSession() {
        picker = null
        restoreModelFor = null
        pendingModelChoice = null
        // A fresh session must come back on the default model, whatever the last one used.
        restoreDefaultPending = true
        client.request("new_session")
    }

    fun switchSession(info: PiSessionInfo) {
        picker = null
        items.clear()
        // Model chosen for this session is re-applied when get_state reports the session file.
        restoreModelFor = info.file
        restoreDefaultPending = false
        pendingModelChoice = null
        client.request("switch_session", JSONObject().put("sessionPath", info.file))
    }

    fun deleteSession(info: PiSessionInfo) {
        appContext?.let { PiSessionModels.remove(it, info.file) }
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

    /** Connects (or reconnects) to the daemon. */
    fun start() {
        if (client.isConnected()) {
            connected = true
            return
        }
        // A manual tap on reconnect allows a new auto-start cycle.
        if (!autoStartInFlight) autoStartTries = 0
        status = "connecting..."
        client.connect(listener)
    }

    /** Keeps the connection alive across onStop/onStart; only shuts down on onDestroy. */
    fun stop() {
        // nothing to do: reconnecting is cheaper than rebuilding the Pi session
    }

    fun shutdown() {
        client.disconnect()
        connected = false
        busy = false
        status = "disconnected"
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
        // @-mentions: only becomes a ref if the path is still in the text (if the
        // user deleted it, ignore). Clears the pending one either way.
        val it = mentionedPaths.iterator()
        while (it.hasNext()) {
            val p = it.next()
            if (message.contains(p) && !refs.contains(p)) refs.add(p)
            it.remove()
        }
        // Absolute paths pasted into the text become refs on their own.
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
            message = if (message.isEmpty()) "Read the attached file(s):\n$list"
            else "$message\n\nFile(s) to read:\n$list"
        }

        items.add(PiItem.User(message, images.map { it.second }))
        assistantIndex = -1
        thinkingIndex = -1
        assistantBuffer.setLength(0)
        thinkingBuffer.setLength(0)
        // If Pi is mid-run, the message goes as steer:
        // it enters the next turn automatically instead of erroring out and getting lost.
        // Extension commands ("/...") run immediately even while streaming,
        // so they do not carry streamingBehavior.
        val steer = busy && !message.startsWith("/")
        busy = true
        if (!client.sendPrompt(message, images, if (steer) "steer" else null)) {
            // The connection was dead: keep it and resend as soon as it reconnects.
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

package com.newtermux.pi

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.newtermux.compose.NewTermuxComposeTheme
import com.newtermux.features.SpeechInputManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pi screen inside Nebula: chat style conversation, with the
 * technical blocks (reasoning, tool calls, bash) in monospace and collapsible.
 *
 * Beyond the conversation: slash command palette, model/thinking level
 * switching, attachments (embedded image, copied document) and the dialogs Pi
 * asks for through the extension UI.
 */
class PiActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashLogger()
        PiSession.attachContext(this)
        requestOverlayPermissionOnce()
        handleShareIntent(intent)
        setContent {
            NewTermuxComposeTheme(this) {
                PiScreen()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    /**
     * Requests the "Display over other apps" permission once. Without it Termux
     * cannot start activities in the background (and the terminal `pi-panel`
     * command does not work).
     */
    private fun requestOverlayPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val prefs = getSharedPreferences("nebula_pi", Context.MODE_PRIVATE)
        if (prefs.getBoolean("overlay_requested", false)) return
        prefs.edit().putBoolean("overlay_requested", true).apply()
        try {
            if (!com.termux.shared.android.PermissionUtils.checkDisplayOverOtherAppsPermission(this)) {
                com.termux.shared.android.PermissionUtils.requestDisplayOverOtherAppsPermission(this)
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not request the overlay permission", e)
        }
    }

    /** Receives text or images shared from another app. */
    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action != Intent.ACTION_SEND) return
        val type = intent.type ?: return

        if (type.startsWith("text/")) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            if (text.isNotBlank()) {
                PiSession.shared = PiShared(text, null)
            }
            return
        }

        if (type.startsWith("image/")) {
            val uri: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)
            if (uri == null) return
            Thread {
                val mime = PiAttach.mimeOf(this, uri)
                val name = PiAttach.displayName(this, uri)
                val bytes = PiAttach.readBytes(this, uri)
                if (bytes == null || bytes.isEmpty()) return@Thread
                runOnUiThread {
                    PiSession.shared = PiShared(
                        "Analyze this image:",
                        PiAttachment(name, mime, null, PiAttach.toBase64(bytes), isImage = true)
                    )
                }
            }.apply {
                isDaemon = true
                name = "pi-share"
                start()
            }
        }
    }

    /**
     * Logs any crash to logcat (tag NebulaPi) before delegating to the
     * default handler. Termux can read the system logcat, so it is
     * possible to diagnose the demo app (com.termux.demo) from outside.
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            Log.e(TAG, "CRASH on thread ${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }
    }

    override fun onStart() {
        super.onStart()
        PiSession.start()
    }

    override fun onStop() {
        super.onStop()
        // The connection stays alive: reconnecting is cheaper than rebuilding the Pi session.
    }

    override fun onDestroy() {
        super.onDestroy()
        PiTts.stop()
        PiTts.shutdown()
        PiSession.shutdown()
    }

    companion object {
        private const val TAG = "NebulaPi"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PiScreen() {
    var input by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var viewerImage by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // scroll to the end when a new message arrives
    LaunchedEffect(PiSession.items.size) {
        val last = PiSession.items.size - 1
        if (last >= 0) listState.animateScrollToItem(last)
    }

    // content shared from another app: fills the field and/or attaches
    LaunchedEffect(PiSession.shared) {
        val shared = PiSession.shared ?: return@LaunchedEffect
        PiSession.shared = null
        shared.text?.let { input = it }
        shared.attachment?.let { PiSession.addAttachment(it) }
    }

    fun notice(text: String, error: Boolean = true) {
        PiSession.items.add(PiItem.Notice(text, error))
    }

    // ------------------------------------------------------------ anexos

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val mime = PiAttach.mimeOf(context, uri)
            val name = PiAttach.displayName(context, uri)
            val bytes = withContext(Dispatchers.IO) { PiAttach.readBytes(context, uri) }
            if (bytes == null || bytes.isEmpty()) {
                notice("Could not read $name")
                return@launch
            }
            if (bytes.size > 12 * 1024 * 1024) {
                notice("$name is too large (${bytes.size / 1024 / 1024} MB) to embed as an image")
                return@launch
            }
            PiSession.addAttachment(
                PiAttachment(name, mime, null, PiAttach.toBase64(bytes), isImage = true)
            )
        }
    }

    val docPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val name = PiAttach.displayName(context, uri)
            val mime = PiAttach.mimeOf(context, uri)
            val path = withContext(Dispatchers.IO) { PiAttach.copyToShared(context, uri, name) }
            if (path == null) {
                notice("Could not copy $name to shared storage")
                return@launch
            }
            // Preview: first lines of the text, to give context in the chip.
            val preview = withContext(Dispatchers.IO) { PiAttach.previewOf(path) }
            PiSession.addAttachment(
                PiAttachment(name, mime, path, null, isImage = false, preview = preview)
            )
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            docPicker.launch(arrayOf("*/*"))
        } else {
            notice("Storage permission denied: cannot attach documents")
        }
    }

    fun pickDocument() {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            docPicker.launch(arrayOf("*/*"))
        } else {
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    // ------------------------------------------------------- voice (microphone)

    val speech = remember(context) { SpeechInputManager(context) }
    DisposableEffect(speech) {
        onDispose { speech.destroy() }
    }

    fun startListening() {
        if (!SpeechInputManager.isAvailable(context)) {
            notice("Voice recognition unavailable on this device")
            return
        }
        speech.setCallback(object : SpeechInputManager.SpeechCallback {
            override fun onResult(text: String) {
                input = if (input.isBlank()) text else input.trimEnd() + " " + text
                listening = false
            }

            override fun onError(error: String) {
                notice("Microphone: $error")
                listening = false
            }

            override fun onListeningStarted() {
                listening = true
            }

            override fun onListeningStopped() {
                listening = false
            }
        })
        try {
            speech.startListening()
        } catch (e: Exception) {
            notice("Could not open the microphone")
            listening = false
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startListening()
        else notice("Microphone permission denied")
    }

    fun tapMic() {
        if (listening) {
            speech.stopListening()
            listening = false
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) startListening()
        else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // ------------------------------------------------------------ tela

    Scaffold(
        topBar = { NebulaTopBar() },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .navigationBarsPadding()
                ) {
                    SlashSuggestions(input) { cmd ->
                        PiSession.runCommand(cmd)
                        input = ""
                    }
                    MentionSuggestions(input, onPick = { path, start, end ->
                        input = input.substring(0, start) + path + " " + input.substring(end)
                        PiSession.noteMentionedPath(path)
                    })
                    if (PiSession.attachments.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PiSession.attachments.forEach { att ->
                                AssistChip(
                                    onClick = { PiSession.removeAttachment(att) },
                                    label = {
                                        Column {
                                            Text(
                                                (if (att.isImage) "🖼 " else "📄 ") + att.name,
                                                fontSize = 11.sp,
                                                maxLines = 1
                                            )
                                            if (!att.preview.isNullOrBlank()) {
                                                Text(
                                                    att.preview,
                                                    fontSize = 10.sp,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    },
                                    trailingIcon = {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Remove",
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { imagePicker.launch(arrayOf("image/*")) }) {
                            Icon(Icons.Default.AttachFile, contentDescription = "Attach image")
                        }
                        IconButton(onClick = { pickDocument() }) {
                            Icon(Icons.Default.Description, contentDescription = "Attach document")
                        }
                        IconButton(onClick = { tapMic() }) {
                            Icon(
                                Icons.Default.Mic,
                                contentDescription = if (listening) "Stop listening" else "Dictate",
                                tint = if (listening) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            placeholder = {
                                Text(
                                    if (listening) "Listening... speak now"
                                    else "Talk to Pi... (/ commands, @ file)",
                                    fontSize = 14.sp
                                )
                            },
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                            maxLines = 5,
                            shape = RoundedCornerShape(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        if (PiSession.busy) {
                            IconButton(onClick = { PiSession.abort() }) {
                                Icon(
                                    Icons.Default.Stop,
                                    contentDescription = "Stop",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                val text = input.trim()
                                if (text.isNotEmpty() || PiSession.attachments.isNotEmpty()) {
                                    PiSession.send(text)
                                    input = ""
                                }
                            },
                            enabled = input.isNotBlank() || PiSession.attachments.isNotEmpty()
                        ) {
                            Icon(Icons.Default.Send, contentDescription = "Send")
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (PiSession.items.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(PiSession.items) { index, item ->
                        PiItemView(index, item, onOpenImage = { viewerImage = it })
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ overlays

    when (PiSession.picker) {
        PiPicker.Commands -> CommandSheet()
        PiPicker.Models -> ModelSheet()
        PiPicker.Thinking -> ThinkingSheet()
        PiPicker.Sessions -> SessionsSheet()
        PiPicker.Theme -> ThemeSheet()
        PiPicker.Memory -> MemorySheet()
        PiPicker.Engines -> EngineSheet()
        PiPicker.Providers -> ProviderSheet()
        null -> Unit
    }

    PiSession.textDialog?.let { dialog ->
        TextInputDialog(dialog)
    }

    PiSession.extDialog?.let { dialog ->
        ExtensionDialog(dialog)
    }

    viewerImage?.let { base64 ->
        ImageViewerDialog(base64 = base64, onClose = { viewerImage = null })
    }
}

// ------------------------------------------------------------------ top bar

/**
 * Slim top bar: connection state, sessions, current model and thinking level.
 * Everything configuration-related lives behind the gear menu.
 */
@Composable
private fun NebulaTopBar() {
    var menuOpen by remember { mutableStateOf(false) }
    val modelLabel = PiSession.model.ifEmpty { "model" }
    val thinkLabel = PiSession.thinkingLevel.ifEmpty { "thinking" }

    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .padding(start = 14.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (PiSession.connected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline
                        )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (PiSession.connected) "Nebula Pi" else PiSession.status,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { PiSession.openSessions() }) {
                    Icon(
                        Icons.Default.History,
                        contentDescription = "Sessions",
                        modifier = Modifier.size(21.dp)
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Options and settings",
                            modifier = Modifier.size(21.dp)
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        TopMenuItem("Engine", Icons.Default.Memory) {
                            menuOpen = false
                            PiSession.openEngines()
                        }
                        TopMenuItem("Providers", Icons.Default.Key) {
                            menuOpen = false
                            PiSession.openProviders()
                        }
                        TopMenuItem("Memory", Icons.Default.Psychology) {
                            menuOpen = false
                            PiSession.openMemory()
                        }
                        TopMenuItem("Theme", Icons.Default.Palette) {
                            menuOpen = false
                            PiSession.openTheme()
                        }
                        TopMenuItem("Pi commands", Icons.Default.List) {
                            menuOpen = false
                            PiSession.openCommandPalette()
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        TopMenuItem("Reconnect", Icons.Default.Refresh) {
                            menuOpen = false
                            PiSession.stop()
                            PiSession.start()
                        }
                        TopMenuItem("Clear conversation", Icons.Default.Delete) {
                            menuOpen = false
                            PiSession.clear()
                        }
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 10.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TopPill(text = modelLabel, modifier = Modifier.weight(1f)) {
                    PiSession.openModels()
                }
                TopPill(text = "thinking: $thinkLabel") {
                    PiSession.openThinking()
                }
            }
        }
    }
}

/** Tappable pill in the top bar (model and thinking level). */
@Composable
private fun TopPill(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/** Item of the gear menu. */
@Composable
private fun TopMenuItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, fontSize = 14.sp) },
        leadingIcon = {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        },
        onClick = onClick
    )
}

// --------------------------------------------------------------- command picker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommandSheet() {
    val builtins = PiSession.commands.filter { it.kind == "builtin" }
    val others = PiSession.commands.filter { it.kind != "builtin" }

    ModalBottomSheet(onDismissRequest = { PiSession.picker = null }) {
        LazyColumn(modifier = Modifier.heightIn(max = 520.dp)) {
            item {
                Text(
                    "Pi commands",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            items(builtins) { cmd -> CommandRow(cmd) }
            if (others.isNotEmpty()) {
                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    Text(
                        "Extensions, templates and skills",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
                items(others) { cmd -> CommandRow(cmd) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun CommandRow(cmd: PiCommand) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { PiSession.runCommand(cmd) }
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(cmd.label, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
        if (cmd.description.isNotEmpty()) {
            Text(
                cmd.description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderSheet() {
    val all = PiSession.availableProviders()
    val featured = all.filter { it.featured }
    val rest = all.filter { !it.featured }
    ModalBottomSheet(onDismissRequest = { PiSession.picker = null }) {
        LazyColumn(modifier = Modifier.heightIn(max = 520.dp)) {
            item {
                Text("Providers", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                Text("Tap to paste the key (saved in auth.json). ● = configured.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
            }
            if (all.isEmpty()) {
                item { Text("Loading...", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
            }
            if (featured.isNotEmpty()) {
                item {
                    Text("★ OpenCode (recommended)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                }
            }
            items(featured) { p -> ProviderRow(p, highlight = true) }
            if (rest.isNotEmpty()) {
                item {
                    Text("All Pi providers", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                }
            }
            items(rest) { p -> ProviderRow(p, highlight = false) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ProviderRow(p: com.newtermux.pi.PiSession.PiProvider, highlight: Boolean) {
    Row(modifier = Modifier.fillMaxWidth().clickable { PiSession.askProviderKey(p) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text((if (p.configured) "● " else "○ ") + p.name, fontSize = 14.sp, fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal, color = if (p.configured) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(p.id + " · " + p.env + (if (p.viaEnv) " (via env)" else ""), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (p.configured && !p.viaEnv) {
            IconButton(onClick = { PiSession.removeProvider(p) }) {
                Icon(Icons.Default.Delete, contentDescription = "Remove", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EngineSheet() {
    val engines = PiSession.engines
    val current = PiSession.currentEngine
    ModalBottomSheet(onDismissRequest = { PiSession.picker = null }) {
        LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
            item {
                Text("Engine", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                Text("Pi (Node) or Pi-Bolt (native). Switches without reconnecting — same folder, same sessions.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
            }
            if (engines.isEmpty()) {
                item { Text("Loading...", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
            }
            items(engines) { e ->
                Column(modifier = Modifier.fillMaxWidth().clickable(enabled = e.available) { PiSession.chooseEngine(e.id) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text((if (e.id == current) "● " else "○ ") + e.name, fontSize = 14.sp, fontWeight = if (e.id == current) FontWeight.SemiBold else FontWeight.Normal, color = if (e.available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(e.id + (if (e.available) "" else " (unavailable)"), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSheet() {
    val models = PiSession.availableModels()
    ModalBottomSheet(onDismissRequest = { PiSession.picker = null }) {
        LazyColumn(modifier = Modifier.heightIn(max = 520.dp)) {
            item {
                Text(
                    "Models",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            if (models.isEmpty()) {
                item {
                    Text(
                        "Loading...",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            items(models) { m ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { PiSession.chooseModel(m) }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(m.name, fontSize = 14.sp)
                    Text(
                        m.provider + " / " + m.id,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThinkingSheet() {
    val levels = PiSession.availableThinkingLevels()
    ModalBottomSheet(onDismissRequest = { PiSession.picker = null }) {
        LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
            item {
                Text(
                    "Thinking level",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            if (levels.isEmpty()) {
                item {
                    Text(
                        "Loading...",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            items(levels) { level ->
                Text(
                    level,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { PiSession.chooseThinking(level) }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeSheet() {
    val options = PiSession.accentOptions()
    val current = PiSession.accentColor
    ModalBottomSheet(onDismissRequest = { PiSession.picker = null }) {
        LazyColumn(modifier = Modifier.heightIn(max = 520.dp)) {
            item {
                Text(
                    "Accent color",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                Text(
                    "Changes the color of the whole app (Termux and Nebula Pi).",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }
            items(options.chunked(3)) { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEach { (color, name) ->
                        val isCurrent = color == current
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { PiSession.setAccent(color) },
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(color))
                                    .then(
                                        if (isCurrent) Modifier.border(
                                            3.dp,
                                            MaterialTheme.colorScheme.onSurface,
                                            RoundedCornerShape(10.dp)
                                        ) else Modifier
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isCurrent) {
                                    Icon(
                                        Icons.Default.Palette,
                                        contentDescription = null,
                                        tint = Color.Black.copy(alpha = 0.6f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(name, fontSize = 11.sp, maxLines = 1)
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemorySheet() {
    val all = PiSession.memories
    val list = PiSession.filteredMemories()
    var showSave by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = { PiSession.picker = null }) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Memory",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { showSave = true }) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Save", fontSize = 13.sp)
                }
            }
            OutlinedTextField(
                value = PiSession.memoryQuery,
                onValueChange = { PiSession.memoryQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                placeholder = { Text("Search ${all.size} memories...", fontSize = 13.sp) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                singleLine = true
            )
            LazyColumn(modifier = Modifier.heightIn(max = 460.dp)) {
                if (list.isEmpty()) {
                    item {
                        Text(
                            if (all.isEmpty()) "No memories found" else "Nothing found",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
                items(list) { m ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { PiSession.useMemory(m) }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(m.text, fontSize = 12.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        Text(
                            m.file + (if (m.last.isNotEmpty()) " · " + m.last else ""),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (showSave) {
        SaveMemoryDialog(onDismiss = { showSave = false })
    }
}

@Composable
private fun SaveMemoryDialog(onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var file by remember { mutableStateOf("MEMORY.md") }
    val files = listOf("MEMORY.md", "USER.md", "failures.md")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save memory") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("What should I remember?") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    files.forEach { f ->
                        AssistChip(
                            onClick = { file = f },
                            label = { Text(f, fontSize = 11.sp) },
                            leadingIcon = if (file == f) {
                                {
                                    Icon(
                                        Icons.Default.Palette,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            } else null
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    PiSession.saveMemory(text.trim(), file)
                    onDismiss()
                },
                enabled = text.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionsSheet() {
    val sessions = PiSession.filteredSessions()
    var toDelete by remember { mutableStateOf<PiSessionInfo?>(null) }

    ModalBottomSheet(onDismissRequest = { PiSession.picker = null }) {
        LazyColumn(modifier = Modifier.heightIn(max = 560.dp)) {
            item {
                Text(
                    "Sessions",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { PiSession.newSession() }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("New session", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    "Tap to open the session. The trash icon deletes the file.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
                OutlinedTextField(
                    value = PiSession.sessionQuery,
                    onValueChange = { PiSession.sessionQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    placeholder = {
                        Text(
                            "Search ${PiSession.sessions.size} sessions...",
                            fontSize = 13.sp
                        )
                    },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                    singleLine = true
                )
            }
            if (sessions.isEmpty()) {
                item {
                    Text(
                        "No sessions found",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            items(sessions) { s ->
                val isCurrent = s.file == PiSession.currentSessionFile
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { PiSession.switchSession(s) }
                        .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            (if (isCurrent) "● " else "") + s.title.ifEmpty { "(untitled)" },
                            fontSize = 14.sp,
                            maxLines = 2
                        )
                        Text(
                            s.userMessages.toString() + " questions · " +
                                s.messages.toString() + " messages · " +
                                formatDate(s.modified),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { toDelete = s }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete session",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    toDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete session?") },
            text = {
                Text(
                    "\"" + target.title.ifEmpty { "(untitled)" } + "\"\n" +
                        "The session file will be deleted from disk. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    PiSession.deleteSession(target)
                    toDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { toDelete = null }) { Text("Cancel") }
            }
        )
    }
}

private fun formatDate(millis: Long): String {
    return try {
        java.text.DateFormat.getDateTimeInstance(
            java.text.DateFormat.SHORT, java.text.DateFormat.SHORT
        ).format(java.util.Date(millis))
    } catch (e: Exception) {
        ""
    }
}

// -------------------------------------------------------------- dialogos

@Composable
private fun TextInputDialog(dialog: PiTextDialog) {
    var text by remember(dialog.title) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { PiSession.textDialog = null },
        title = { Text(dialog.title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(dialog.hint) },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = {
                dialog.onOk(text.trim())
                PiSession.textDialog = null
            }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = { PiSession.textDialog = null }) { Text("Cancel") }
        }
    )
}

@Composable
private fun ExtensionDialog(dialog: PiExtDialog) {
    var text by remember(dialog.id) { mutableStateOf(dialog.prefill) }

    when (dialog.method) {
        "select" -> AlertDialog(
            onDismissRequest = { PiSession.cancelExtension(dialog.id) },
            title = { Text(dialog.title.ifEmpty { "Choose an option" }) },
            text = {
                Column {
                    dialog.options.forEach { option ->
                        TextButton(
                            onClick = { PiSession.respondExtensionValue(dialog.id, option) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(option, modifier = Modifier.fillMaxWidth()) }
                    }
                }
            },
            confirmButton = {}
        )

        "confirm" -> AlertDialog(
            onDismissRequest = { PiSession.cancelExtension(dialog.id) },
            title = { Text(dialog.title.ifEmpty { "Confirmar" }) },
            text = { Text(dialog.message) },
            confirmButton = {
                TextButton(onClick = { PiSession.respondExtensionConfirmed(dialog.id, true) }) {
                    Text("Yes")
                }
            },
            dismissButton = {
                TextButton(onClick = { PiSession.respondExtensionConfirmed(dialog.id, false) }) {
                    Text("No")
                }
            }
        )

        else -> AlertDialog(
            onDismissRequest = { PiSession.cancelExtension(dialog.id) },
            title = { Text(dialog.title.ifEmpty { "Input" }) },
            text = {
                Column {
                    if (dialog.message.isNotEmpty()) {
                        Text(dialog.message, fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                    }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = { Text(dialog.placeholder) },
                        minLines = if (dialog.method == "editor") 4 else 1,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { PiSession.respondExtensionValue(dialog.id, text) }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { PiSession.cancelExtension(dialog.id) }) { Text("Cancel") }
            }
        )
    }
}

// ------------------------------------------------------------------- messages

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Nebula Pi", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            if (PiSession.connected) "Connected. Tell me what you need."
            else if (PiSession.status.startsWith("starting") || PiSession.status == "connecting...") PiSession.status + "\nHold on, I am starting the daemon on my own."
            else "No connection to the Pi daemon.\nI am trying to start it on my own — if it fails, run 'pi-rpc' in Termux and tap reconnect.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (PiSession.busy) {
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun PiItemView(index: Int, item: PiItem, onOpenImage: (String) -> Unit) {
    when (item) {
        is PiItem.User -> UserBubble("user-$index", item.text, item.images, onOpenImage)
        is PiItem.Assistant -> AssistantBubble("asst-$index", item.text, item.images, onOpenImage)
        is PiItem.Thinking -> ThinkingBlock(index, item.text)
        is PiItem.Tool -> ToolChip(item.name, item.running, item.text, item.images, onOpenImage)
        is PiItem.Bash -> BashBlock(item.text)
        is PiItem.Notice -> NoticeLine(item.text, item.error)
    }
}

/** Attached image thumbnail (raw base64). Tap opens it full screen. */
@Composable
private fun PiImageThumb(base64: String, onOpen: ((String) -> Unit)? = null) {
    val bitmap = remember(base64) { decodeImage(base64) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "image (tap to zoom)",
            modifier = Modifier
                .widthIn(max = 260.dp)
                .clip(RoundedCornerShape(10.dp))
                .then(if (onOpen != null) Modifier.clickable { onOpen(base64) } else Modifier)
        )
    }
}

private fun decodeImage(base64: String): Bitmap? {
    return try {
        val raw = if (base64.startsWith("data:")) base64.substringAfter(',') else base64
        val bytes = Base64.decode(raw, Base64.DEFAULT)
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxDim / sample > 900) sample *= 2
        val opts = BitmapFactory.Options()
        opts.inSampleSize = sample
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    } catch (e: Exception) {
        Log.w("NebulaPi", "could not decode the image", e)
        null
    }
}

@Composable
private fun UserBubble(
    key: String,
    text: String,
    images: List<String> = emptyList(),
    onOpenImage: (String) -> Unit = {}
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp),
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                images.forEach { PiImageThumb(it, onOpenImage) }
                if (images.isNotEmpty() && text.isNotEmpty()) Spacer(Modifier.height(6.dp))
                if (text.isNotEmpty()) {
                    MarkdownText(
                        text = text,
                        modifier = Modifier.padding(horizontal = 4.dp),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                if (text.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        CopyButton(text, key)
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantBubble(
    key: String,
    text: String,
    images: List<String> = emptyList(),
    onOpenImage: (String) -> Unit = {}
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp),
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                images.forEach { PiImageThumb(it, onOpenImage) }
                if (images.isNotEmpty() && text.isNotEmpty()) Spacer(Modifier.height(6.dp))
                if (text.isNotEmpty()) {
                    MarkdownText(
                        text = text,
                        modifier = Modifier.padding(horizontal = 4.dp),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (text.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SpeakButton(key, text)
                        CopyButton(text, key)
                    }
                }
            }
        }
    }
}

/** Small copy button (turns into a check for 1s). */
@Composable
private fun CopyButton(text: String, key: String) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember(key, text.length) { mutableStateOf(false) }
    IconButton(
        onClick = {
            clipboard.setText(AnnotatedString(text))
            copied = true
            scope.launch {
                kotlinx.coroutines.delay(1200)
                copied = false
            }
        },
        modifier = Modifier.size(28.dp)
    ) {
        Icon(
            Icons.Default.ContentCopy,
            contentDescription = if (copied) "Copied" else "Copy",
            modifier = Modifier.size(14.dp),
            tint = if (copied) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}

/** Speaker: speaks the response (TTS), tap again to stop. */
@Composable
private fun SpeakButton(key: String, text: String) {
    val context = LocalContext.current
    val active = PiTts.isSpeaking && PiTts.speakingKey == key
    IconButton(
        onClick = { PiTts.speak(context, key, text) },
        modifier = Modifier.size(28.dp)
    ) {
        Icon(
            if (active) Icons.Default.Stop else Icons.Default.VolumeUp,
            contentDescription = if (active) "Stop speaking" else "Read response aloud",
            modifier = Modifier.size(14.dp),
            tint = if (active) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun ThinkingBlock(index: Int, text: String) {
    var expanded by remember(index) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Text(
            if (expanded) "▾ reasoning" else "▸ reasoning",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        if (expanded && text.isNotBlank()) {
            Text(
                text,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun ToolChip(
    name: String,
    running: Boolean,
    text: String = "",
    images: List<String> = emptyList(),
    onOpenImage: (String) -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Text(
            "⚙ " + name + (if (running) " ..." else " ✓"),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
        )
        if (text.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Box(modifier = Modifier.padding(start = 16.dp)) {
                DiffAwareMonoText(text, collapsedLines = 8)
            }
        }
        if (images.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Column(modifier = Modifier.padding(start = 16.dp)) {
                images.forEach { PiImageThumb(it, onOpenImage) }
            }
        }
    }
}

@Composable
private fun BashBlock(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(modifier = Modifier.padding(8.dp)) {
            DiffAwareMonoText(text, collapsedLines = 12)
        }
    }
}

@Composable
private fun NoticeLine(text: String, error: Boolean) {
    Text(
        text,
        fontSize = 12.sp,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
    )
}

// -------------------------------------------------------------- autocomplete / e @

/** Slash command suggestions while typing "/...". Tap runs it. */
@Composable
private fun SlashSuggestions(input: String, onPick: (PiCommand) -> Unit) {
    // trim on both ends: the space after the "/" (that the keyboard adds by itself)
    // must not hide the box; it only hides once there is a real argument.
    val q = input.trim()
    if (!q.startsWith("/") || q.contains(" ") || q.contains("\n")) return
    if (PiSession.commands.isEmpty()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
        ) {
            Text(
                "Loading commands... tap to reload",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { PiSession.refreshCommands() }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
        return
    }
    val matches = PiSession.commands
        .filter { it.label.startsWith(q, ignoreCase = true) }
        .take(6)
    if (matches.isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
    ) {
        Column(modifier = Modifier.heightIn(max = 220.dp)) {
            matches.forEach { cmd ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(cmd) }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Text(cmd.label, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    if (cmd.description.isNotEmpty()) {
                        Text(
                            cmd.description,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** Finds "@search" at the end of the text (the @ must come after a space/start). */
private fun extractMentionQuery(input: String): Pair<String, IntRange>? {
    val at = input.lastIndexOf('@')
    if (at < 0) return null
    if (at > 0) {
        val before = input[at - 1]
        if (!before.isWhitespace() && before != '(' && before != '"' && before != '\'') return null
    }
    val query = input.substring(at + 1)
    if (query.contains(' ') || query.contains('\n')) return null
    if (query.contains('/')) {
        // partial path: only the file name filters
        val name = query.substringAfterLast('/')
        if (name.contains(' ')) return null
    }
    return query to (at..input.length)
}

/** File suggestions for @ (Download + attachments). Tap inserts the path. */
@Composable
private fun MentionSuggestions(input: String, onPick: (path: String, start: Int, end: Int) -> Unit) {
    val mention = remember(input) { extractMentionQuery(input) } ?: return
    val (query, range) = mention
    val filter = query.substringAfterLast('/').trim()
    val files = remember(query) { PiAttach.listMentionFiles(filter) }
    if (files.isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
    ) {
        Column(modifier = Modifier.heightIn(max = 220.dp)) {
            files.forEach { f ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(f.absolutePath, range.first, range.last) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Description,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(f.name, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// ------------------------------------------------------------ full screen image

/** Fullscreen dialog with pinch-to-zoom to look at the image properly. */
@Composable
private fun ImageViewerDialog(base64: String, onClose: () -> Unit) {
    val bitmap = remember(base64) { decodeImage(base64) }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.95f))
                .clickable { onClose() }
        ) {
            if (bitmap != null) {
                var scale by remember { mutableStateOf(1f) }
                var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
                val state = rememberTransformableState { zoomChange, panChange, _ ->
                    scale = (scale * zoomChange).coerceIn(1f, 5f)
                    offset = androidx.compose.ui.geometry.Offset(
                        (offset.x + panChange.x).coerceIn(-800f, 800f),
                        (offset.y + panChange.y).coerceIn(-800f, 800f)
                    )
                }
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "zoomed image",
                    modifier = Modifier
                        .fillMaxSize()
                        .transformable(state)
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y
                        )
                )
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Fechar", tint = Color.White)
            }
        }
    }
}

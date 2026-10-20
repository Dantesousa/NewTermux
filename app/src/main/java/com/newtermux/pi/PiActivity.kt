package com.newtermux.pi

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.newtermux.compose.NewTermuxComposeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tela do Pi dentro do Nebula: conversa em estilo chat, com os blocos
 * tecnicos (raciocinio, tool calls, bash) em monoespacada e expansiveis.
 *
 * Alem da conversa: palette de slash commands, troca de modelo/nivel de
 * raciocinio, anexos (imagem embutida, documento copiado) e os dialogs que o
 * Pi pede via extension UI.
 */
class PiActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashLogger()
        PiSession.attachContext(this)
        setContent {
            NewTermuxComposeTheme(this) {
                PiScreen()
            }
        }
    }

    /**
     * Loga qualquer crash no logcat (tag NebulaPi) antes de delegar ao
     * handler padrao. O Termux consegue ler o logcat do sistema, entao da
     * para diagnosticar o app demo (com.termux.demo) de fora.
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            Log.e(TAG, "CRASH na thread ${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }
    }

    override fun onStart() {
        super.onStart()
        PiSession.start()
    }

    override fun onStop() {
        super.onStop()
        // A conexao fica viva: reconectar e mais barato que refazer a sessao do Pi.
    }

    override fun onDestroy() {
        super.onDestroy()
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
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // rola para o fim quando chega mensagem nova
    LaunchedEffect(PiSession.items.size) {
        val last = PiSession.items.size - 1
        if (last >= 0) listState.animateScrollToItem(last)
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
                notice("Nao consegui ler $name")
                return@launch
            }
            if (bytes.size > 12 * 1024 * 1024) {
                notice("$name e grande demais (${bytes.size / 1024 / 1024} MB) para embutir como imagem")
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
                notice("Nao consegui copiar $name para o armazenamento compartilhado")
                return@launch
            }
            PiSession.addAttachment(PiAttachment(name, mime, path, null, isImage = false))
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            docPicker.launch(arrayOf("*/*"))
        } else {
            notice("Permissao de armazenamento negada: nao da para anexar documentos")
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

    // ------------------------------------------------------------ tela

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Nebula Pi", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            PiSession.status,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { PiSession.openSessions() }) {
                        Icon(Icons.Default.History, contentDescription = "Sessoes", modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { PiSession.openCommandPalette() }) {
                        Icon(Icons.Default.List, contentDescription = "Comandos", modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = {
                        PiSession.stop()
                        PiSession.start()
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reconectar", modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { PiSession.clear() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Limpar", modifier = Modifier.size(20.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .navigationBarsPadding()
                ) {
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
                                        Text(
                                            (if (att.isImage) "🖼 " else "📄 ") + att.name,
                                            fontSize = 11.sp,
                                            maxLines = 1
                                        )
                                    },
                                    trailingIcon = {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Remover",
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
                            Icon(Icons.Default.AttachFile, contentDescription = "Anexar imagem")
                        }
                        IconButton(onClick = { pickDocument() }) {
                            Icon(Icons.Default.Description, contentDescription = "Anexar documento")
                        }
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Fale com o Pi...", fontSize = 14.sp) },
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                            maxLines = 5,
                            shape = RoundedCornerShape(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
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
                            Icon(Icons.Default.Send, contentDescription = "Enviar")
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
                        PiItemView(index, item)
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
        null -> Unit
    }

    PiSession.textDialog?.let { dialog ->
        TextInputDialog(dialog)
    }

    PiSession.extDialog?.let { dialog ->
        ExtensionDialog(dialog)
    }
}

// -------------------------------------------------------------- seletor de comandos

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommandSheet() {
    val builtins = PiSession.commands.filter { it.kind == "builtin" }
    val others = PiSession.commands.filter { it.kind != "builtin" }

    ModalBottomSheet(onDismissRequest = { PiSession.picker = null }) {
        LazyColumn(modifier = Modifier.heightIn(max = 520.dp)) {
            item {
                Text(
                    "Comandos do Pi",
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
                        "Extensoes, templates e skills",
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
private fun ModelSheet() {
    val models = PiSession.availableModels()
    ModalBottomSheet(onDismissRequest = { PiSession.picker = null }) {
        LazyColumn(modifier = Modifier.heightIn(max = 520.dp)) {
            item {
                Text(
                    "Modelos",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            if (models.isEmpty()) {
                item {
                    Text(
                        "Carregando...",
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
                    "Nivel de raciocinio",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            if (levels.isEmpty()) {
                item {
                    Text(
                        "Carregando...",
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
private fun SessionsSheet() {
    val sessions = PiSession.sessions
    var toDelete by remember { mutableStateOf<PiSessionInfo?>(null) }

    ModalBottomSheet(onDismissRequest = { PiSession.picker = null }) {
        LazyColumn(modifier = Modifier.heightIn(max = 560.dp)) {
            item {
                Text(
                    "Sessoes",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                Text(
                    "Toque para abrir a sessao. A lixeira apaga o arquivo.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }
            if (sessions.isEmpty()) {
                item {
                    Text(
                        "Nenhuma sessao encontrada",
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
                            (if (isCurrent) "● " else "") + s.title.ifEmpty { "(sem titulo)" },
                            fontSize = 14.sp,
                            maxLines = 2
                        )
                        Text(
                            s.userMessages.toString() + " perguntas · " +
                                s.messages.toString() + " mensagens · " +
                                formatDate(s.modified),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { toDelete = s }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Apagar sessao",
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
            title = { Text("Apagar sessao?") },
            text = {
                Text(
                    "\"" + target.title.ifEmpty { "(sem titulo)" } + "\"\n" +
                        "O arquivo da sessao sera apagado do disco. Nao da para desfazer."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    PiSession.deleteSession(target)
                    toDelete = null
                }) { Text("Apagar") }
            },
            dismissButton = {
                TextButton(onClick = { toDelete = null }) { Text("Cancelar") }
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
            TextButton(onClick = { PiSession.textDialog = null }) { Text("Cancelar") }
        }
    )
}

@Composable
private fun ExtensionDialog(dialog: PiExtDialog) {
    var text by remember(dialog.id) { mutableStateOf(dialog.prefill) }

    when (dialog.method) {
        "select" -> AlertDialog(
            onDismissRequest = { PiSession.cancelExtension(dialog.id) },
            title = { Text(dialog.title.ifEmpty { "Escolha uma opcao" }) },
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
                    Text("Sim")
                }
            },
            dismissButton = {
                TextButton(onClick = { PiSession.respondExtensionConfirmed(dialog.id, false) }) {
                    Text("Nao")
                }
            }
        )

        else -> AlertDialog(
            onDismissRequest = { PiSession.cancelExtension(dialog.id) },
            title = { Text(dialog.title.ifEmpty { "Entrada" }) },
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
                TextButton(onClick = { PiSession.cancelExtension(dialog.id) }) { Text("Cancelar") }
            }
        )
    }
}

// -------------------------------------------------------------- mensagens

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
            if (PiSession.connected) "Conectado. Manda o que voce precisa."
            else "Sem conexao com o daemon do Pi.\nRode 'pi-rpc' no Termux e toque em reconectar.",
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
private fun PiItemView(index: Int, item: PiItem) {
    when (item) {
        is PiItem.User -> UserBubble(item.text, item.images)
        is PiItem.Assistant -> AssistantBubble(item.text, item.images)
        is PiItem.Thinking -> ThinkingBlock(index, item.text)
        is PiItem.Tool -> ToolChip(item.name, item.running)
        is PiItem.Bash -> BashBlock(item.text)
        is PiItem.Notice -> NoticeLine(item.text, item.error)
    }
}

/** Miniatura de imagem anexada (base64 puro). */
@Composable
private fun PiImageThumb(base64: String) {
    val bitmap = remember(base64) { decodeImage(base64) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "imagem",
            modifier = Modifier
                .widthIn(max = 260.dp)
                .clip(RoundedCornerShape(10.dp))
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
        Log.w("NebulaPi", "nao consegui decodificar a imagem", e)
        null
    }
}

@Composable
private fun UserBubble(text: String, images: List<String> = emptyList()) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp),
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                images.forEach { PiImageThumb(it) }
                if (images.isNotEmpty() && text.isNotEmpty()) Spacer(Modifier.height(6.dp))
                if (text.isNotEmpty()) {
                    Text(
                        text,
                        modifier = Modifier.padding(horizontal = 4.dp),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun AssistantBubble(text: String, images: List<String> = emptyList()) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp),
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                images.forEach { PiImageThumb(it) }
                if (images.isNotEmpty() && text.isNotEmpty()) Spacer(Modifier.height(6.dp))
                if (text.isNotEmpty()) {
                    Text(
                        text,
                        modifier = Modifier.padding(horizontal = 4.dp),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
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
            if (expanded) "▾ raciocinio" else "▸ raciocinio",
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
private fun ToolChip(name: String, running: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "⚙ " + name + (if (running) " ..." else " ✓"),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
        )
    }
}

@Composable
private fun BashBlock(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(8.dp)
        )
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

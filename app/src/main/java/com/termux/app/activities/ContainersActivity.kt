package com.termux.app.activities

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newtermux.compose.NewTermuxComposeTheme
import com.newtermux.features.NebulaContainers
import com.newtermux.features.NewTermuxSettings
import com.termux.app.TermuxActivity
import kotlinx.coroutines.launch

/**
 * Linux containers (proot-distro) manager. Lists what is installed with its disk usage, lets the
 * user pull new images from Docker Hub, and runs the graphical stack inside a container on top of
 * the Termux:X11 server.
 *
 * All the heavy lifting lives in `scripts/nebula-containers.sh`, which the app installs into
 * $PREFIX/bin, so the same commands work from a terminal.
 */
class ContainersActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NewTermuxComposeTheme(this) {
                ContainersScreen(
                    onBack = { finish() },
                    onRunInTerminal = { command ->
                        NewTermuxSettings.setPendingCommand(this, command)
                        startActivity(
                            Intent(this, TermuxActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            }
                        )
                    },
                )
            }
        }
    }
}

private val QUICK_IMAGES = listOf("alpine", "debian", "ubuntu", "archlinux", "fedora", "kali")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContainersScreen(
    onBack: () -> Unit,
    onRunInTerminal: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var containers by remember { mutableStateOf<List<NebulaContainers.Container>>(emptyList()) }
    var totals by remember { mutableStateOf(NebulaContainers.Totals(0, 0, 0)) }
    var doctor by remember { mutableStateOf<NebulaContainers.Doctor?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableStateOf(0) }

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<NebulaContainers.Image>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    var installDesktop by remember { mutableStateOf(true) }

    var confirmRemove by remember { mutableStateOf<NebulaContainers.Container?>(null) }
    var desktopOffer by remember { mutableStateOf<NebulaContainers.Container?>(null) }
    var desktopChoice by remember { mutableStateOf<NebulaContainers.Container?>(null) }

    var autostart by remember { mutableStateOf(NewTermuxSettings.getAutostartContainer(context)) }
    var autostartOpen by remember { mutableStateOf(false) }

    fun refresh() { refreshKey++ }

    LaunchedEffect(refreshKey) {
        loading = true
        doctor = NebulaContainers.doctor(context)
        containers = NebulaContainers.list(context)
        totals = NebulaContainers.totals(context)
        loading = false
    }

    fun runBusy(label: String, block: suspend () -> NebulaContainers.Result, after: (NebulaContainers.Result) -> Unit = {}) {
        scope.launch {
            busy = label
            val result = block()
            busy = null
            after(result)
            refresh()
        }
    }

    fun startDesktop(container: NebulaContainers.Container, app: String) {
        scope.launch {
            busy = "Checking ${container.name}…"
            val ready = NebulaContainers.desktopReady(context, container.name)
            busy = null
            if (ready) {
                NebulaContainers.startDesktop(context, container.name, app)
                snackbar.showSnackbar("Starting $app in ${container.name}")
            } else {
                desktopOffer = container
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Linux Containers") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { refresh() }) { Text("Refresh") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            if (busy != null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (busy != null) {
                Text(
                    busy ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }

            OverviewCard(
                totals = totals,
                loading = loading,
                onClearCache = {
                    runBusy("Clearing the image cache…", { NebulaContainers.clearCache(context) }) { result ->
                        scope.launch {
                            snackbar.showSnackbar(
                                if (result.ok) result.message.ifBlank { "Cache cleared" } else result.message
                            )
                        }
                    }
                },
            )

            val doc = doctor
            if (doc != null && (!doc.prootDistro || !doc.termuxX11)) {
                MissingDepsCard(doc, onRunInTerminal)
            }

            SectionHeader("Installed")
            if (loading && containers.isEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }
            } else if (containers.isEmpty()) {
                Text(
                    "No containers yet. Pick an image below to install one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            } else {
                containers.forEach { container ->
                    ContainerCard(
                        container = container,
                        enabled = busy == null,
                        onDesktop = { desktopChoice = container },
                        onTerminal = { onRunInTerminal("proot-distro login ${container.name}") },
                        onRemove = { confirmRemove = container },
                        onReinstall = {
                            onRunInTerminal(
                                "proot-distro remove ${container.name} && ${NebulaContainers.installCommand(container.name, false)}"
                            )
                        },
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SectionHeader("Add environment")
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("Docker Hub image") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    enabled = query.isNotBlank() && busy == null,
                    onClick = {
                        scope.launch {
                            busy = "Searching Docker Hub for “$query”…"
                            results = NebulaContainers.search(context, query.trim())
                            searched = true
                            busy = null
                        }
                    },
                ) { Text("Search") }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QUICK_IMAGES.take(4).forEach { image ->
                    AssistChip(
                        onClick = { query = image },
                        label = { Text(image) },
                    )
                }
            }

            DesktopToggleRow(
                checked = installDesktop,
                onCheckedChange = { installDesktop = it },
            )

            if (searched && results.isEmpty() && busy == null) {
                Text(
                    "No images found for “$query”.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }

            results.forEach { image ->
                ImageRow(
                    image = image,
                    enabled = busy == null,
                    onInstall = {
                        onRunInTerminal(NebulaContainers.installCommand(image.name, installDesktop))
                    },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SectionHeader("Desktop")
            AutostartRow(
                container = autostart,
                containers = containers,
                open = autostartOpen,
                onToggleOpen = { autostartOpen = it },
                onSelect = { name ->
                    autostart = name
                    NewTermuxSettings.setAutostartContainer(context, name)
                    autostartOpen = false
                },
                onStop = {
                    runBusy("Stopping the X11 server…", { NebulaContainers.stopDesktop(context) }) {
                        scope.launch { snackbar.showSnackbar("X11 server stopped") }
                    }
                },
            )

            Text(
                "Desktops run inside the container and draw on the Termux:X11 server. " +
                    "Mirroring and acceleration live in the viewer app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }

    confirmRemove?.let { container ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove ${container.name}?") },
            text = {
                Text(
                    "Deletes the container and everything inside it " +
                        "(${NebulaContainers.formatKb(container.sizeKb)}). This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = null
                    runBusy("Removing ${container.name}…", { NebulaContainers.remove(context, container.name) }) { result ->
                        scope.launch {
                            snackbar.showSnackbar(
                                if (result.ok) "${container.name} removed" else result.message
                            )
                        }
                    }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Cancel") } },
        )
    }

    desktopChoice?.let { container ->
        AlertDialog(
            onDismissRequest = { desktopChoice = null },
            title = { Text("Open in ${container.name}") },
            text = {
                Column {
                    DesktopOption("Desktop") { desktopChoice = null; startDesktop(container, "desktop") }
                    DesktopOption("Chromium") { desktopChoice = null; startDesktop(container, "chromium") }
                    DesktopOption("Terminal only") { desktopChoice = null; startDesktop(container, "terminal") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { desktopChoice = null }) { Text("Cancel") } },
        )
    }

    desktopOffer?.let { container ->
        AlertDialog(
            onDismissRequest = { desktopOffer = null },
            title = { Text("Desktop not installed") },
            text = {
                Text(
                    "${container.name} has no window manager yet. Install a lightweight desktop " +
                        "(Openbox + xterm, a couple hundred MB) now?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    desktopOffer = null
                    runBusy("Installing the desktop in ${container.name}…", {
                        NebulaContainers.setupDesktop(context, container.name)
                    }) { result ->
                        scope.launch {
                            snackbar.showSnackbar(
                                if (result.ok) "Desktop ready — tap Desktop to start it" else result.message
                            )
                        }
                    }
                }) { Text("Install") }
            },
            dismissButton = { TextButton(onClick = { desktopOffer = null }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------- pieces

@Composable
private fun DesktopOption(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun OverviewCard(
    totals: NebulaContainers.Totals,
    loading: Boolean,
    onClearCache: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Disk usage", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            UsageRow("Containers", NebulaContainers.formatKb(totals.containersKb))
            UsageRow("Image cache", NebulaContainers.formatKb(totals.cacheKb))
            UsageRow("Free space", NebulaContainers.formatKb(totals.freeKb))
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onClearCache, enabled = !loading && totals.cacheKb > 0) {
                    Text("Clear cache")
                }
            }
        }
    }
}

@Composable
private fun UsageRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MissingDepsCard(doctor: NebulaContainers.Doctor, onRunInTerminal: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Setup needed", style = MaterialTheme.typography.titleSmall)
            if (!doctor.prootDistro) {
                Text("proot-distro is not installed.", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { onRunInTerminal("pkg install proot-distro") }) {
                    Text("Install proot-distro")
                }
            }
            if (!doctor.termuxX11) {
                Text("termux-x11 is not installed (needed for desktops).", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { onRunInTerminal("pkg install x11-repo termux-x11-nightly") }) {
                    Text("Install termux-x11")
                }
            }
        }
    }
}

@Composable
private fun ContainerCard(
    container: NebulaContainers.Container,
    enabled: Boolean,
    onDesktop: () -> Unit,
    onTerminal: () -> Unit,
    onRemove: () -> Unit,
    onReinstall: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(container.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        buildString {
                            append(container.version)
                            append(" · ")
                            append(NebulaContainers.formatKb(container.sizeKb))
                            append(" · ")
                            append(if (container.running) "running" else "stopped")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (container.family != "unknown") {
                    Text(
                        container.family,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (!container.complete) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Incomplete install — the rootfs is empty. Reinstall to use it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (container.complete) {
                    TextButton(onClick = onDesktop, enabled = enabled) { Text("Desktop") }
                    TextButton(onClick = onTerminal, enabled = enabled) { Text("Terminal") }
                } else {
                    TextButton(onClick = onReinstall, enabled = enabled) { Text("Reinstall") }
                }
                TextButton(onClick = onRemove, enabled = enabled) { Text("Remove") }
            }
        }
    }
}

@Composable
private fun DesktopToggleRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Install a desktop with the image", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Adds Openbox and a terminal after the image is unpacked",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ImageRow(
    image: NebulaContainers.Image,
    enabled: Boolean,
    onInstall: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(image.name, style = MaterialTheme.typography.bodyLarge)
                    if (image.official) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "official",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (image.description.isNotBlank()) {
                    Text(
                        image.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "${image.pulls} pulls · ${image.stars} stars",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onInstall, enabled = enabled) { Text("Install") }
        }
        HorizontalDivider()
    }
}

@Composable
private fun AutostartRow(
    container: String?,
    containers: List<NebulaContainers.Container>,
    open: Boolean,
    onToggleOpen: (Boolean) -> Unit,
    onSelect: (String?) -> Unit,
    onStop: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Start desktop with the app", style = MaterialTheme.typography.bodyLarge)
                Text(
                    container?.let { "Container: $it" } ?: "Off",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = container != null,
                onCheckedChange = { enabled ->
                    if (enabled) {
                        containers.firstOrNull { it.complete }?.let { onSelect(it.name) }
                    } else {
                        onSelect(null)
                    }
                },
                enabled = containers.any { it.complete } || container != null,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onToggleOpen(true) }, enabled = containers.isNotEmpty()) {
                Text("Choose container")
            }
            DropdownMenu(expanded = open, onDismissRequest = { onToggleOpen(false) }) {
                DropdownMenuItem(
                    text = { Text("Off") },
                    onClick = { onSelect(null) },
                )
                containers.filter { it.complete }.forEach { c ->
                    DropdownMenuItem(
                        text = { Text(c.name) },
                        onClick = { onSelect(c.name) },
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onStop) { Text("Stop X11") }
        }
    }
}

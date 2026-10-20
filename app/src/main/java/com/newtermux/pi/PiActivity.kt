package com.newtermux.pi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newtermux.compose.NewTermuxComposeTheme

/**
 * Tela do Pi dentro do Nebula: conversa em estilo chat, com os blocos
 * tecnicos (raciocinio, tool calls) em monoespacada e expansiveis.
 */
class PiActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NewTermuxComposeTheme(this) {
                PiScreen()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        PiSession.start()
    }

    override fun onStop() {
        super.onStop()
        PiSession.stop()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PiScreen() {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // rola para o fim quando chega mensagem nova
    LaunchedEffect(PiSession.items.size) {
        val last = PiSession.items.size - 1
        if (last >= 0) listState.animateScrollToItem(last)
    }

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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .navigationBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Fale com o Pi...", fontSize = 14.sp) },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                        maxLines = 5,
                        shape = RoundedCornerShape(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    IconButton(
                        onClick = {
                            val text = input.trim()
                            if (text.isNotEmpty()) {
                                PiSession.send(text)
                                input = ""
                            }
                        },
                        enabled = input.isNotBlank()
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Enviar")
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
}

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
        Spacer(Modifier.size(8.dp))
        Text(
            if (PiSession.connected) "Conectado. Manda o que voce precisa."
            else "Sem conexao com o daemon do Pi.\nRode 'pi-rpc' no Termux e toque em reconectar.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (PiSession.busy) {
            Spacer(Modifier.size(16.dp))
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun PiItemView(index: Int, item: PiItem) {
    when (item) {
        is PiItem.User -> UserBubble(item.text)
        is PiItem.Assistant -> AssistantBubble(item.text)
        is PiItem.Thinking -> ThinkingBlock(index, item.text)
        is PiItem.Tool -> ToolChip(item.name, item.running)
        is PiItem.Notice -> NoticeLine(item.text, item.error)
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp),
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Text(
                text,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun AssistantBubble(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp),
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Text(
                text,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
private fun NoticeLine(text: String, error: Boolean) {
    Text(
        text,
        fontSize = 12.sp,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
    )
}

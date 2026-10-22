package com.newtermux.pi

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Blocos
// ---------------------------------------------------------------------------

private sealed interface MdBlock {
    data class Code(val lang: String, val code: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Bullet(val text: String, val indent: Int) : MdBlock
    data class Ordered(val num: String, val text: String, val indent: Int) : MdBlock
    data object Divider : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Table(val rows: List<String>) : MdBlock
}

private fun parseBlocks(src: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val lines = src.split("\n")
    var i = 0
    val para = StringBuilder()

    fun flushPara() {
        if (para.isNotEmpty()) {
            out.add(MdBlock.Paragraph(para.toString().trim()))
            para.setLength(0)
        }
    }

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()

        // --- fenced code ```lang ... ```
        if (trimmed.startsWith("```")) {
            flushPara()
            val lang = trimmed.removePrefix("```").trim().substringBefore(" ").substringBefore("{")
            val buf = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                buf.append(lines[i])
                if (i < lines.size - 1) buf.append("\n")
                i++
            }
            // pula o ``` de fechamento (se houver)
            if (i < lines.size) i++ else i = lines.size
            out.add(MdBlock.Code(lang, buf.toString().trimEnd()))
            continue
        }

        // linha em branco = separa paragrafos
        if (line.isBlank()) {
            flushPara()
            i++
            continue
        }

        // divider --- ou *** ou ___
        val t = line.trim()
        if (t == "---" || t == "***" || t == "___" || t == "···") {
            flushPara()
            out.add(MdBlock.Divider)
            i++
            continue
        }

        // heading # .. ######
        if (trimmed.startsWith("#")) {
            var level = 0
            while (level < trimmed.length && trimmed[level] == '#') level++
            if (level in 1..6 && trimmed.getOrNull(level) == ' ') {
                flushPara()
                out.add(MdBlock.Heading(level, trimmed.drop(level).trim()))
                i++
                continue
            }
        }

        // quote > ...
        if (trimmed.startsWith(">")) {
            flushPara()
            val q = StringBuilder()
            while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                if (q.isNotEmpty()) q.append("\n")
                q.append(lines[i].trimStart().removePrefix(">").trimStart())
                i++
            }
            out.add(MdBlock.Quote(q.toString()))
            continue
        }

        // tabela: linhas com | (e/ou linha de --- com |)
        if (line.trim().startsWith("|") && line.contains("|")) {
            flushPara()
            val rows = mutableListOf<String>()
            while (i < lines.size && lines[i].trim().contains("|") &&
                (lines[i].trim().startsWith("|") || lines[i].trim().startsWith(":") || lines[i].contains("---"))
            ) {
                // para de consumir tabela quando a linha nao parece tabela
                val l = lines[i].trim()
                if (l.isEmpty() || (!l.contains("|") && !l.contains("-"))) break
                // linha separadora |---|---|
                if (l.all { it == '|' || it == '-' || it == ':' || it.isWhitespace() }) {
                    i++
                    continue
                }
                if (!l.contains("|")) break
                rows.add(l)
                i++
                // se a proxima linha nao tem |, para
                if (i < lines.size && !lines[i].contains("|")) break
            }
            if (rows.isNotEmpty()) out.add(MdBlock.Table(rows))
            continue
        }

        // bullet: - * • + (com indent)
        val bulletMatch = Regex("""^(\s*)[-*•+]\s+(.*)$""").matchEntire(line)
        if (bulletMatch != null) {
            flushPara()
            val indent = bulletMatch.groupValues[1].length / 2
            out.add(MdBlock.Bullet(bulletMatch.groupValues[2], indent))
            i++
            continue
        }

        // ordered: 1. 1) (com indent)
        val orderedMatch = Regex("""^(\s*)(\d+)[.)]\s+(.*)$""").matchEntire(line)
        if (orderedMatch != null) {
            flushPara()
            val indent = orderedMatch.groupValues[1].length / 2
            out.add(MdBlock.Ordered(orderedMatch.groupValues[2], orderedMatch.groupValues[3], indent))
            i++
            continue
        }

        // indent code (4 espacos) isolado? trata como paragrafo normal pra nao quebrar listas
        if (para.isNotEmpty()) para.append("\n")
        para.append(line)
        i++
    }
    flushPara()
    return out
}

// ---------------------------------------------------------------------------
// Inline: **negrito** *italico* _italico_ ~~riscado~~ ++sublinhado++
// <u>sublinhado</u> `codigo` [texto](url) url solta
// ---------------------------------------------------------------------------

private fun buildInline(
    text: String,
    baseColor: Color,
    codeBg: Color,
    linkColor: Color,
    codeColor: Color,
): AnnotatedString {
    return buildAnnotatedString {
        parseRange(text, 0, text.length, baseColor, codeBg, linkColor, codeColor)
    }
}

private fun AnnotatedString.Builder.parseRange(
    s: String,
    l: Int,
    r: Int,
    baseColor: Color,
    codeBg: Color,
    linkColor: Color,
    codeColor: Color,
) {
    var i = l
    while (i < r) {
        // escape \* \_ \` etc
        if (s[i] == '\\' && i + 1 < r && "\\`*_{}[]()#+-.!~+<>".contains(s[i + 1])) {
            append(s[i + 1].toString())
            i += 2
            continue
        }
        // inline code `...`
        if (s[i] == '`') {
            // `` duplo? trata como single tambem (bloco ``` ja foi separado)
            val close = s.indexOf('`', i + 1)
            if (close in (i + 1) until r) {
                val inner = s.substring(i + 1, close)
                withStyle(SpanStyle(color = codeColor, background = codeBg, fontFamily = FontFamily.Monospace, fontSize = 13.sp)) {
                    append(inner)
                }
                i = close + 1
                continue
            }
            append("`")
            i++
            continue
        }
        // bold **...** ou __...__
        if ((s.startsWith("**", i) && i + 2 < r) || (s.startsWith("__", i) && i + 2 < r)) {
            val delim = s.substring(i, i + 2)
            val close = s.indexOf(delim, i + 2)
            if (close in 0 until r) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    parseRange(s, i + 2, close, baseColor, codeBg, linkColor, codeColor)
                }
                i = close + 2
                continue
            }
            append(delim)
            i += 2
            continue
        }
        // strikethrough ~~...~~
        if (s.startsWith("~~", i) && i + 2 < r) {
            val close = s.indexOf("~~", i + 2)
            if (close in 0 until r) {
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    parseRange(s, i + 2, close, baseColor, codeBg, linkColor, codeColor)
                }
                i = close + 2
                continue
            }
            append("~~")
            i += 2
            continue
        }
        // underline ++...++ (extensao estilo Nebula)
        if (s.startsWith("++", i) && i + 2 < r) {
            val close = s.indexOf("++", i + 2)
            if (close in 0 until r) {
                withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
                    parseRange(s, i + 2, close, baseColor, codeBg, linkColor, codeColor)
                }
                i = close + 2
                continue
            }
            append("++")
            i += 2
            continue
        }
        // underline <u>...</u>
        if (s.startsWith("<u>", i, ignoreCase = true)) {
            val close = s.indexOf("</u>", i + 3, ignoreCase = true)
            if (close in 0 until r) {
                withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
                    parseRange(s, i + 3, close, baseColor, codeBg, linkColor, codeColor)
                }
                i = close + 4
                continue
            }
            append("<u>")
            i += 3
            continue
        }
        // link [texto](url)
        if (s[i] == '[') {
            val closeBracket = findLinkClose(s, i, r)
            if (closeBracket > i && closeBracket + 1 < r && s[closeBracket + 1] == '(') {
                val closeParen = s.indexOf(')', closeBracket + 2)
                if (closeParen in 0 until r) {
                    val url = s.substring(closeBracket + 2, closeParen).trim()
                    pushStringAnnotation(tag = "URL", annotation = url)
                    withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                        parseRange(s, i + 1, closeBracket, baseColor, codeBg, linkColor, codeColor)
                    }
                    pop()
                    i = closeParen + 1
                    continue
                }
            }
            append("[")
            i++
            continue
        }
        // bare url https://...
        if (s.startsWith("http://", i) || s.startsWith("https://", i)) {
            var j = i
            while (j < r && !s[j].isWhitespace() && s[j] != ')' && s[j] != ']' && s[j] != '"' && s[j] != '\'') j++
            // tira pontuacao final . , ; ! ?
            var end = j
            while (end > i && ".,;:!?".contains(s[end - 1])) end--
            val url = s.substring(i, end)
            pushStringAnnotation(tag = "URL", annotation = url)
            withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                append(url)
            }
            pop()
            // devolve a pontuacao
            if (end < j) append(s.substring(end, j))
            i = j
            continue
        }
        // italic *...* ou _..._ (single)
        if (s[i] == '*' || s[i] == '_') {
            val delim = s[i]
            // se for __ ou ** ja tratado acima; aqui garante single
            if (i + 1 < r && s[i + 1] == delim) {
                append(delim.toString())
                i++
                continue
            }
            // _ dentro de palavra (snake_case) = literal
            if (delim == '_' && i > l && i + 1 < r &&
                (s[i - 1].isLetterOrDigit() || s[i - 1] == '_') &&
                (s[i + 1].isLetterOrDigit() || s[i + 1] == '_')
            ) {
                append("_")
                i++
                continue
            }
            val close = findSingleClose(s, delim, i + 1, r)
            if (close in 0 until r) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    parseRange(s, i + 1, close, baseColor, codeBg, linkColor, codeColor)
                }
                i = close + 1
                continue
            }
            append(delim.toString())
            i++
            continue
        }
        append(s[i].toString())
        i++
    }
}

private fun findLinkClose(s: String, open: Int, r: Int): Int {
    var depth = 0
    var idx = open
    while (idx < r) {
        if (s[idx] == '\\') {
            idx += 2
            continue
        }
        if (s[idx] == '[') depth++
        if (s[idx] == ']') {
            depth--
            if (depth == 0) return idx
        }
        idx++
    }
    return -1
}

private fun findSingleClose(s: String, delim: Char, from: Int, r: Int): Int {
    var idx = from
    while (idx < r) {
        if (s[idx] == '\\') {
            idx += 2
            continue
        }
        if (s[idx] == '`') {
            // pula codigo inline pra nao confundir * dentro de codigo
            val c = s.indexOf('`', idx + 1)
            if (c in 0 until r) {
                idx = c + 1
                continue
            }
            return -1
        }
        if (s[idx] == delim) {
            // se for duplo (** ou __), nao fecha single
            if (idx + 1 < r && s[idx + 1] == delim) return -1
            if (idx - 1 >= from && s[idx - 1] == delim) {
                idx++
                continue
            }
            return idx
        }
        idx++
    }
    return -1
}

// ---------------------------------------------------------------------------
// Highlight de codigo (leve, sem dependencias)
// ---------------------------------------------------------------------------

private val CODE_KEYWORDS = setOf(
    "if", "else", "elif", "for", "while", "do", "switch", "case", "default",
    "break", "continue", "return", "yield", "throw", "try", "catch", "finally",
    "import", "from", "as", "export", "package", "namespace", "using",
    "class", "interface", "enum", "struct", "type", "extends", "implements",
    "fun", "function", "def", "lambda", "const", "let", "var", "val", "final",
    "static", "public", "private", "protected", "internal", "open", "override",
    "abstract", "sealed", "data", "object", "companion", "new", "delete",
    "this", "self", "super", "null", "nil", "none", "true", "false",
    "and", "or", "not", "in", "of", "is", "async", "await", "with",
    "void", "int", "long", "short", "float", "double", "string", "bool",
    "boolean", "char", "select", "insert", "update", "delete", "create",
    "table", "where", "join", "union", "group", "order", "by", "limit",
    "template", "typename", "sizeof", "typedef", "goto", "fi", "then",
    "echo", "exit", "local", "global", "fn", "mut", "use", "mod", "pub",
    "crate", "impl", "match", "loop", "elif", "unless", "until"
)

private fun highlightCode(code: String, base: Color): AnnotatedString {
    val kw = Color(0xFFC792EA)
    val str = Color(0xFFC3E88D)
    val com = Color(0xFF8A8A8A)
    val num = Color(0xFFF78C6C)
    val fn = Color(0xFF82AAFF)
    val ann = Color(0xFFFFCB6B)
    return buildAnnotatedString {
        var i = 0
        val n = code.length
        while (i < n) {
            val c = code[i]
            // comentarios //... e #...
            if (c == '/' && i + 1 < n && code[i + 1] == '/') {
                val j = code.indexOf('\n', i).let { if (it == -1) n else it }
                withStyle(SpanStyle(color = com, fontStyle = FontStyle.Italic)) { append(code.substring(i, j)) }
                i = j
                continue
            }
            if (c == '#') {
                val j = code.indexOf('\n', i).let { if (it == -1) n else it }
                withStyle(SpanStyle(color = com, fontStyle = FontStyle.Italic)) { append(code.substring(i, j)) }
                i = j
                continue
            }
            // bloco /* ... */
            if (c == '/' && i + 1 < n && code[i + 1] == '*') {
                val j = code.indexOf("*/", i + 2).let { if (it == -1) n else it + 2 }
                withStyle(SpanStyle(color = com, fontStyle = FontStyle.Italic)) { append(code.substring(i, j)) }
                i = j
                continue
            }
            // html <!-- ... -->
            if (c == '<' && code.startsWith("!--", i + 1)) {
                val j = code.indexOf("-->", i + 4).let { if (it == -1) n else it + 3 }
                withStyle(SpanStyle(color = com, fontStyle = FontStyle.Italic)) { append(code.substring(i, j)) }
                i = j
                continue
            }
            // strings " ' `
            if (c == '"' || c == '\'' || c == '`') {
                var j = i + 1
                var closed = false
                while (j < n) {
                    if (code[j] == '\\') {
                        j += 2
                        continue
                    }
                    if (code[j] == c) {
                        closed = true
                        j++
                        break
                    }
                    if (code[j] == '\n' && c != '`') break
                    j++
                }
                withStyle(SpanStyle(color = str)) { append(code.substring(i, if (closed) j else j)) }
                i = j
                continue
            }
            // anotacao @word e variavel $WORD / $word / ${}
            if (c == '@' && i + 1 < n && (code[i + 1].isLetter() || code[i + 1] == '_')) {
                var j = i + 1
                while (j < n && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                withStyle(SpanStyle(color = ann)) { append(code.substring(i, j)) }
                i = j
                continue
            }
            if (c == '$' && i + 1 < n && (code[i + 1].isLetter() || code[i + 1] == '_' || code[i + 1] == '{')) {
                var j = i + 1
                if (code[j] == '{') {
                    val k = code.indexOf('}', j)
                    j = if (k == -1) n else k + 1
                } else {
                    while (j < n && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                }
                withStyle(SpanStyle(color = ann)) { append(code.substring(i, j)) }
                i = j
                continue
            }
            // flag --alguma-coisa (bash)
            if (c == '-' && i + 1 < n && code[i + 1] == '-' && i + 2 < n && code[i + 2].isLetter()) {
                var j = i + 2
                while (j < n && (code[j].isLetterOrDigit() || code[j] == '-' || code[j] == '_')) j++
                withStyle(SpanStyle(color = ann)) { append(code.substring(i, j)) }
                i = j
                continue
            }
            // numero
            if (c.isDigit()) {
                var j = i
                while (j < n && (code[j].isLetterOrDigit() || code[j] == '.' || code[j] == '_' || code[j] == 'x' || code[j] == 'X')) j++
                withStyle(SpanStyle(color = num)) { append(code.substring(i, j)) }
                i = j
                continue
            }
            // palavra: keyword ou funcao ou normal
            if (c.isLetter() || c == '_') {
                var j = i
                while (j < n && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                val word = code.substring(i, j)
                // olha se eh chamada de funcao: prox nao-espaco eh '('
                var k = j
                while (k < n && code[k].isWhitespace() && code[k] != '\n') k++
                val isCall = k < n && code[k] == '('
                when {
                    CODE_KEYWORDS.contains(word.lowercase()) && word.length > 1 -> withStyle(SpanStyle(color = kw, fontWeight = FontWeight.SemiBold)) { append(word) }
                    isCall -> withStyle(SpanStyle(color = fn)) { append(word) }
                    else -> withStyle(SpanStyle(color = base)) { append(word) }
                }
                i = j
                continue
            }
            withStyle(SpanStyle(color = base.copy(alpha = 0.9f))) { append(c.toString()) }
            i++
        }
    }
}

// ---------------------------------------------------------------------------
// Composables
// ---------------------------------------------------------------------------

/**
 * Texto com markdown leve: **negrito** *italico* ~~riscado~~ ++sublinhado++
 * `codigo` ```bloco``` # titulos - listas > quote [link](url).
 *
 * Sem dependencias externas (roda no minSdk 21, sem baixar nada).
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 14.sp,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    if (text.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    val codeBg = cs.surface.copy(alpha = 0.9f)
    val codeColor = Color(0xFFFFCB6B)
    val linkColor = cs.primary
    val baseCode = Color(0xFFD6DEEB)

    // parse e barato pra mensagens normais; pra streaming longo, ainda ok (<8k chars)
    val blocks = remember(text) { parseBlocks(text) }

    SelectionContainer {
        Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            blocks.forEach { block ->
                when (block) {
                    is MdBlock.Code -> CodeBlockView(block.lang, block.code, baseCode)
                    is MdBlock.Heading -> {
                        val size = when (block.level) {
                            1 -> 19.sp
                            2 -> 17.sp
                            3 -> 16.sp
                            else -> 15.sp
                        }
                        val ann = remember(block.text) { buildInline(block.text, color, codeBg, linkColor, codeColor) }
                        InlineClickable(ann, size, color, FontWeight.Bold)
                    }
                    is MdBlock.Quote -> {
                        Row(
                            modifier = Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(cs.primary.copy(alpha = 0.7f))
                            )
                            Spacer(Modifier.width(8.dp))
                            val ann = remember(block.text) { buildInline(block.text, color, codeBg, linkColor, codeColor) }
                            InlineClickable(ann, 13.sp, color.copy(alpha = 0.85f), null, Modifier.weight(1f))
                        }
                    }
                    is MdBlock.Bullet -> {
                        Row(modifier = Modifier.fillMaxWidth().padding(start = (block.indent * 12).dp)) {
                            Text("• ", fontSize = fontSize, color = color)
                            val ann = remember(block.text) { buildInline(block.text, color, codeBg, linkColor, codeColor) }
                            InlineClickable(ann, fontSize, color, null, Modifier.weight(1f))
                        }
                    }
                    is MdBlock.Ordered -> {
                        Row(modifier = Modifier.fillMaxWidth().padding(start = (block.indent * 12).dp)) {
                            Text(block.num + ". ", fontSize = fontSize, color = color, fontFamily = FontFamily.Monospace)
                            val ann = remember(block.text) { buildInline(block.text, color, codeBg, linkColor, codeColor) }
                            InlineClickable(ann, fontSize, color, null, Modifier.weight(1f))
                        }
                    }
                    is MdBlock.Divider -> HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = cs.outline.copy(alpha = 0.5f)
                    )
                    is MdBlock.Table -> TableView(block.rows)
                    is MdBlock.Paragraph -> {
                        val ann = remember(block.text) { buildInline(block.text, color, codeBg, linkColor, codeColor) }
                        InlineClickable(ann, fontSize, color, null)
                    }
                }
            }
        }
    }
}

@Composable
private fun InlineClickable(
    ann: AnnotatedString,
    fontSize: TextUnit,
    color: Color,
    weight: FontWeight?,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    ClickableText(
        text = ann,
        modifier = modifier,
        style = androidx.compose.ui.text.TextStyle(
            fontSize = fontSize,
            color = color,
            fontWeight = weight,
            lineHeight = (fontSize.value + 4).sp
        ),
        onClick = { offset ->
            ann.getStringAnnotations(tag = "URL", start = offset, end = offset)
                .firstOrNull()?.let { uriHandler.openUri(it.item) }
        }
    )
}

@Composable
private fun TableView(rows: List<String>) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surface.copy(alpha = 0.7f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, cs.outline.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
    ) {
        Column(modifier = Modifier.padding(8.dp).horizontalScroll(rememberScrollState())) {
            rows.forEachIndexed { idx, row ->
                val cells = row.trim().trim('|').split("|").map { it.trim() }
                Text(
                    cells.joinToString("  │  "),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (idx == 0) cs.onSurface else cs.onSurfaceVariant,
                    fontWeight = if (idx == 0) FontWeight.Bold else null,
                    maxLines = 1
                )
                if (idx == 0) HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = cs.outline.copy(alpha = 0.4f))
            }
        }
    }
}

@Composable
private fun CodeBlockView(lang: String, code: String, baseCode: Color) {
    val cs = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember(code) { mutableStateOf(false) }
    val highlighted = remember(code) { highlightCode(code, baseCode) }

    Surface(
        color = Color(0xFF0F0F0F),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, cs.outline.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (lang.isNotBlank()) lang else "code",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF8A8A8A),
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(code))
                    copied = true
                    scope.launch {
                        delay(1200)
                        copied = false
                    }
                }) {
                    Text(if (copied) "Copiado!" else "Copiar", fontSize = 11.sp)
                }
            }
            HorizontalDivider(color = Color(0xFF2A2A2A))
            SelectionContainer {
                Text(
                    text = highlighted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(10.dp).horizontalScroll(rememberScrollState())
                )
            }
        }
    }
}

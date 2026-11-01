package io.github.vprodc83.gamecatalog.ui

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil3.compose.AsyncImage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import io.github.vprodc83.gamecatalog.core.Catalog
import io.github.vprodc83.gamecatalog.core.Identify
import io.github.vprodc83.gamecatalog.core.Text as T
import io.github.vprodc83.gamecatalog.data.Item
import io.github.vprodc83.gamecatalog.data.Lista
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

/** Um jogo candidato mostrado na tela de identificação. */
data class Cand(val titulo: String, val console: String, val plataforma: String, val repo: String, val alternativas: List<Catalog.Match> = emptyList(), val origem: String)

/** Lê o texto da foto no próprio aparelho (ML Kit) e devolve os blocos com a posição relativa. */
suspend fun readText(ctx: Context, uri: Uri): Pair<List<Identify.Block>, String> {
    val img = InputImage.fromFilePath(ctx, uri)
    val res = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(img).await()
    val w = img.width.toFloat().coerceAtLeast(1f); val h = img.height.toFloat().coerceAtLeast(1f)
    val blocks = res.textBlocks.map { b -> val r = b.boundingBox; Identify.Block(b.text, (r?.left ?: 0) / w, (r?.top ?: 0) / h, (r?.right ?: 0) / w, (r?.bottom ?: 0) / h) }
    return blocks to res.text
}

/** Código de console da planilha que corresponde a um acervo do catálogo (ex.: acervo do SNES → "SNES"). */
fun consoleFor(m: AppModel, repoId: String): String = m.repo.state.value.consoles.firstOrNull { m.repo.platformOf(it) == repoId } ?: ""

@Composable
fun IdentifyScreen(m: AppModel, onOpen: (Item) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var photo by remember { mutableStateOf<File?>(null) }
    var status by remember { mutableStateOf("Fotografe a etiqueta ou a caixa do jogo. Vale foto com vários cartuchos.") }
    val cands = remember { mutableStateListOf<Cand>() }
    var reading by remember { mutableStateOf(false) }
    var lensPending by remember { mutableStateOf(false) }
    var clip by remember { mutableStateOf<String?>(null) }
    var lastClip by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }

    fun analyze(f: File) {
        photo = f; cands.clear(); reading = true; status = "Lendo a etiqueta…"
        scope.launch {
            try {
                val (blocks, text) = readText(ctx, fileUri(ctx, f))
                val cat = m.catalog ?: run { status = "O catálogo de jogos ainda está carregando. Tente de novo em instantes."; return@launch }
                val repos = m.repo.state.value.consoles.map { m.repo.platformOf(it) }.filter { it.isNotEmpty() }.distinct()
                val found = withContext(Dispatchers.Default) { Identify.find(cat, blocks, text, repos) }
                found.forEach { cands.add(Cand(it.match.tituloLimpo, consoleFor(m, it.match.repo), it.match.plataforma, it.match.repo, it.alternativas, "etiqueta")) }
                status = when {
                    found.isNotEmpty() -> "Encontrei ${found.size} jogo(s) pela etiqueta. Confira abaixo."
                    text.isBlank() -> "Não consegui ler texto na foto. Use o Google Lens ou a busca por nome."
                    else -> "Li o texto, mas não reconheci o jogo. Use o Google Lens ou a busca por nome."
                }
            } catch (e: Exception) { status = "Não consegui ler a foto: ${e.message}" } finally { reading = false }
        }
    }

    val shotFile = remember { mutableStateOf<File?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) shotFile.value?.let { analyze(it) } }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val f = File(File(ctx.cacheDir, "fotos").apply { mkdirs() }, "galeria-${System.currentTimeMillis()}.jpg")
            withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { i.copyTo(it) } } }
            analyze(f)
        }
    }

    // ao voltar do Google Lens, oferece o nome que você copiou lá
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, lensPending) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME && lensPending) {
                val t = runCatching { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim() }.getOrNull()
                if (!t.isNullOrBlank() && t != lastClip && t.length <= 120) clip = t
            }
        }
        owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
    }

    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val f = File(File(ctx.cacheDir, "fotos").apply { mkdirs() }, "foto-${System.currentTimeMillis()}.jpg"); shotFile.value = f
                            camera.launch(fileUri(ctx, f))
                        }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.PhotoCamera, null); Text("  Fotografar") }
                        OutlinedButton(onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Image, null); Text("  Galeria") }
                    }
                    photo?.let { AsyncImage(model = it, contentDescription = "Foto", modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)) }
                    Row { if (reading) CircularProgressIndicator(Modifier.width(20.dp).padding(end = 8.dp)); Hint(status) }
                    photo?.let { f ->
                        OutlinedButton(onClick = { lastClip = runCatching { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim() }.getOrNull(); lensPending = openLens(ctx, fileUri(ctx, f)) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Perguntar ao Google Lens")
                        }
                        Hint("No Lens, toque no nome do jogo, escolha Copiar e volte para cá: o app oferece o nome copiado.")
                    }
                }
            }
        }
        clip?.let { t ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Você copiou “$t”. Procurar este jogo?", fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { search = t; lastClip = t; clip = null; lensPending = false }) { Text("Procurar") }
                            TextButton(onClick = { lastClip = t; clip = null }) { Text("Agora não") }
                        }
                    }
                }
            }
        }
        items(cands, key = { it.repo + it.titulo }) { c -> CandCard(m, c, onOpen, onReplace = { nc -> val i = cands.indexOf(c); if (i >= 0) cands[i] = nc }) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Buscar pelo nome", fontWeight = FontWeight.SemiBold)
                    NameSearch(m, search, onQuery = { search = it }) { c -> cands.add(0, c) }
                }
            }
        }
        item { Spacer8() }
    }
}

/** Busca por nome (ou parte dele) na planilha e no catálogo; ao escolher, vira um cartão de candidato. */
@Composable
fun NameSearch(m: AppModel, query: String, onQuery: (String) -> Unit, onPick: (Cand) -> Unit) {
    val consoles = m.repo.state.value.consoles
    var console by remember { mutableStateOf("") }
    OutlinedTextField(value = query, onValueChange = onQuery, label = { Text("Nome do jogo ou parte dele") }, singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth())
    Picker("Console", console, listOf("" to "Todos os consoles") + consoles.map { it to it }) { console = it }
    val q = query.trim()
    if (q.length >= 2) {
        val mine = m.repo.state.value.items.filter { (console.isEmpty() || m.repo.platformOf(it.console) == m.repo.platformOf(console)) && T.score(q, it.nome) >= 0.6 }
            .sortedByDescending { T.score(q, it.nome) }.take(6)
        val repos = if (console.isEmpty()) consoles.map { m.repo.platformOf(it) }.filter { it.isNotEmpty() }.distinct() else listOfNotNull(m.repo.platformOf(console).ifEmpty { null })
        val cat = m.catalog?.search(q, repos, limit = 10, minScore = 0.6) ?: emptyList()
        mine.forEach { i -> TextButton(onClick = { onPick(Cand(i.nome, i.console, i.console, m.repo.platformOf(i.console), origem = "planilha")) }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) { Text(i.nome); Hint("${i.console} · ${i.lista.titulo} · linha ${i.linha}") } } }
        cat.forEach { c -> TextButton(onClick = { onPick(Cand(c.tituloLimpo, console.ifEmpty { consoleFor(m, c.repo) }, c.plataforma, c.repo, origem = "busca")) }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) { Text(c.tituloLimpo); Hint("Catálogo · ${c.plataforma}") } } }
        if (mine.isEmpty() && cat.isEmpty()) Hint("Nada parecido. Tente outro nome ou outro console.")
        OutlinedButton(onClick = { onPick(Cand(q, console, console, m.repo.platformOf(console), origem = "digitado")) }) { Text("Usar o nome digitado") }
    }
}

/** Cartão de um jogo encontrado: se você já tem, se procura ou se já comprou, e as ações possíveis. */
@Composable
fun CandCard(m: AppModel, c: Cand, onOpen: (Item) -> Unit, onReplace: (Cand) -> Unit) {
    val st by m.repo.state.collectAsState()
    val found = remember(c, st) { m.repo.find(c.titulo, c.console.ifEmpty { null }) }
    val have = found.filter { it.item.lista == Lista.COLECAO }; val want = found.filter { it.item.lista == Lista.PROCURADO }; val buying = found.filter { it.item.lista == Lista.COMPRADO }
    var dialog by remember { mutableStateOf<String?>(null) }
    val cover = remember(c) { m.catalog?.let { cat -> cat.search(c.titulo, listOfNotNull(c.repo.ifEmpty { null }), limit = 1, minScore = 0.75).firstOrNull()?.let { cat.coverUrl(it.repo, it.nomeAcervo) } } }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Cover(cover, c.plataforma, Modifier.width(84.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(c.titulo, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Hint(listOf(c.console, c.plataforma).filter { it.isNotBlank() }.distinct().joinToString(" · ").ifEmpty { "Console não identificado" })
                    when {
                        have.isNotEmpty() -> Pill("Você já tem", Ok)
                        buying.isNotEmpty() -> Pill("Já está nas compras", Warn)
                        want.isNotEmpty() -> Pill("Está nos procurados", Info)
                        else -> Pill("Você não tem", MaterialTheme.colorScheme.primary)
                    }
                    Hint(when (c.origem) { "etiqueta" -> "Reconhecido pela etiqueta"; "planilha" -> "Escolhido da sua planilha"; "busca" -> "Escolhido no catálogo"; else -> "Nome digitado" })
                }
            }
            found.take(3).forEach { f -> TextButton(onClick = { onOpen(f.item) }) { Text("${f.item.lista.titulo}: ${f.item.console} · ${f.item.nome} (linha ${f.item.linha})") } }
            Chips {
                if (have.isEmpty() && buying.isEmpty()) {
                    AssistChip(onClick = { dialog = "comprei" }, label = { Text("Comprei") })
                    AssistChip(onClick = { dialog = "colecao" }, label = { Text("Já tenho: pôr na coleção") })
                    if (want.isEmpty() && m.repo.state.value.config?.procurados?.isNotEmpty() == true) AssistChip(onClick = { dialog = "quero" }, label = { Text("Quero (procurados)") })
                }
                if (have.isNotEmpty()) AssistChip(onClick = { dialog = "comprei" }, label = { Text("Comprei outra cópia") })
            }
            if (c.alternativas.isNotEmpty()) {
                SectionTitle("Pode ser também")
                Chips { c.alternativas.forEach { a -> AssistChip(onClick = { onReplace(c.copy(titulo = a.tituloLimpo, repo = a.repo, plataforma = a.plataforma, console = consoleFor(m, a.repo), alternativas = c.alternativas - a + Catalog.Match(c.titulo, c.titulo, c.repo, c.plataforma, 0.0))) }, label = { Text(a.tituloLimpo) }) } }
            }
            PriceBox(m, c.titulo, c.plataforma, c.repo, key = null)
        }
    }
    when (dialog) {
        "comprei" -> BuyDialog(m, c.console, c.titulo, want.firstOrNull()?.item) { dialog = null }
        "colecao" -> AddCollectionDialog(m, c.console, c.titulo) { dialog = null }
        "quero" -> WantDialog(m, c.console, c.titulo) { dialog = null }
    }
}

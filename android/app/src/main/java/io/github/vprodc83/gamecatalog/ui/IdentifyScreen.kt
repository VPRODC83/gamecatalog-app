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
import androidx.compose.runtime.LaunchedEffect
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
data class Cand(val titulo: String, val console: String, val plataforma: String, val repo: String, val alternativas: List<Catalog.Match> = emptyList(), val origem: String, val box: Identify.Block? = null)

/** Lê o texto da foto no próprio aparelho (ML Kit) e devolve os blocos com a posição relativa. */
suspend fun readText(ctx: Context, uri: Uri): Pair<List<Identify.Block>, String> {
    val img = InputImage.fromFilePath(ctx, uri)
    val res = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(img).await()
    val w = img.width.toFloat().coerceAtLeast(1f); val h = img.height.toFloat().coerceAtLeast(1f)
    val blocks = res.textBlocks.map { b -> val r = b.boundingBox; Identify.Block(b.text, (r?.left ?: 0) / w, (r?.top ?: 0) / h, (r?.right ?: 0) / w, (r?.bottom ?: 0) / h) }
    return blocks to res.text
}

/** Texto e momento da cópia atuais da área de transferência (null se vazia ou sem permissão). */
fun readClip(ctx: Context): Pair<String, Long>? = runCatching {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val c = cm.primaryClip ?: return null
    val t = c.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim().orEmpty()
    val stamp = if (android.os.Build.VERSION.SDK_INT >= 26) c.description.timestamp else t.hashCode().toLong()
    if (t.isEmpty()) null else t to stamp
}.getOrNull()

/** Nome copiado do Lens, sem sobras de sites ("- Wikipedia", "| eBay", várias linhas…). */
fun cleanClip(t: String): String = t.lineSequence().map { it.trim() }.firstOrNull { it.length >= 2 }.orEmpty()
    .replace("\\s+[-|–—:]\\s+(Wikip[eé]dia|eBay|Mercado Livre|Amazon|OLX|Shopee|PriceCharting|YouTube|IGN|GameFAQs|MobyGames).*$".toRegex(RegexOption.IGNORE_CASE), "")
    .take(120).trim()

/** Código de console da planilha que corresponde a um acervo do catálogo (ex.: acervo do SNES → "SNES"). */
fun consoleFor(m: AppModel, repoId: String): String = m.repo.state.value.consoles.firstOrNull { m.repo.platformOf(it) == repoId } ?: ""

@Composable
fun IdentifyScreen(m: AppModel, onOpen: (Item) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var photo by remember { mutableStateOf<File?>(null) }
    var status by remember { mutableStateOf(tr("Fotografe a etiqueta ou a caixa do jogo. Vale foto com vários cartuchos.")) }
    val cands = remember { mutableStateListOf<Cand>() }
    var reading by remember { mutableStateOf(false) }
    var lensPending by remember { mutableStateOf(false) }
    var clip by remember { mutableStateOf<String?>(null) }
    var clipStamp by remember { mutableStateOf(-1L) }
    var search by remember { mutableStateOf("") }
    var lida by remember { mutableStateOf<Reading?>(null) }
    val marcadas = remember { mutableStateListOf<Int>() }
    var aoVivo by remember { mutableStateOf(false) }
    val parecidos = remember { mutableStateListOf<CoverMatcher.Similar>() }

    /** Guarda o recorte da foto como capa provisória do jogo, se ele ainda não tem capa (melhoria 19). */
    fun keepIdentPhoto(c: Cand, r: Reading, sozinho: Boolean) {
        if (c.console.isBlank()) return
        val chave = T.norm(c.console) + "|" + T.norm(c.titulo)
        if (m.photo(chave, "capa") != null || m.photo(chave, "ident") != null || m.repo.state.value.capas[chave]?.get("capa") != null) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val f = saveJpeg(if (sozinho) r.bitmap else cropAround(r.bitmap, c.box), File(m.photoDir(), "ident-${System.currentTimeMillis()}.jpg"))
                withContext(Dispatchers.Main) { m.setPhoto(chave, "ident", f) }
            }
        }
    }

    fun analyze(f: File) {
        photo = f; cands.clear(); marcadas.clear(); parecidos.clear(); lida = null; reading = true; status = tr("Lendo a etiqueta…")
        scope.launch {
            try {
                val r = readPhoto(f); lida = r
                val blocks = r.blocks; val text = r.text
                var cat = m.catalog
                var waited = 0
                while (cat == null && m.catalogError == null && waited < 100) { kotlinx.coroutines.delay(100); waited++; cat = m.catalog }
                if (cat == null) { status = m.catalogError ?: tr("O catálogo de jogos ainda está carregando. Tente de novo em instantes."); return@launch }
                val repos = m.repo.state.value.consoles.map { m.repo.platformOf(it) }.filter { it.isNotEmpty() }.distinct()
                // 1º código do produto impresso (exato, sem internet); 2º leitura do nome na etiqueta
                val porCodigo = m.serials?.find(text) ?: emptyList()
                porCodigo.forEach { h -> val t = Catalog.cleanTitle(h.nome); cands.add(Cand(t, consoleFor(m, h.repo), cat.label(h.repo), h.repo, origem = "codigo:" + h.code)) }
                val found = withContext(Dispatchers.Default) { Identify.find(cat, blocks, text, repos) }
                found.filter { f0 -> cands.none { T.norm(it.titulo) == T.norm(f0.match.tituloLimpo) && it.repo == f0.match.repo } }
                    .forEach { cands.add(Cand(it.match.tituloLimpo, consoleFor(m, it.match.repo), it.match.plataforma, it.match.repo, it.alternativas, "etiqueta", it.block)) }
                cands.toList().forEach { keepIdentPhoto(it, r, cands.size == 1) }
                // capas parecidas (melhoria 16), só se o índice já foi preparado em Ajustes
                if (m.matcher.count > 0) scope.launch {
                    val sim = runCatching { m.matcher.similar(r.bitmap) }.getOrDefault(emptyList())
                    parecidos.clear(); parecidos.addAll(sim.filter { s0 -> cands.none { T.norm(it.titulo) == T.norm(s0.titulo) } })
                    if (cands.isEmpty() && sim.isNotEmpty()) status = tr("Não li o nome, mas achei capas parecidas. Confira abaixo.")
                }
                // 3º código de barras da caixa (consulta o PriceCharting, em segundo plano)
                r.barcodes.forEach { code ->
                    scope.launch {
                        val hit = withContext(Dispatchers.IO) { io.github.vprodc83.gamecatalog.core.Prices.byBarcode(code) } ?: return@launch
                        val repo = io.github.vprodc83.gamecatalog.core.Prices.repoOfPcConsole(hit.second)
                        val cons = if (repo.startsWith("@")) repo.drop(1) else consoleFor(m, repo)
                        val c = Cand(hit.first, cons, if (repo.startsWith("@")) cn(repo.drop(1)) else cat.label(repo), repo.removePrefix("@"), origem = "barras:$code")
                        if (cands.none { T.norm(it.titulo) == T.norm(c.titulo) }) { cands.add(0, c); keepIdentPhoto(c, r, true) }
                        status = tr("Código de barras {0}: {1}", code, hit.first)
                    }
                }
                val total = porCodigo.size + found.size
                status = when {
                    porCodigo.isNotEmpty() -> tr("Identificado pelo código impresso ({0}). Confira abaixo.", porCodigo.joinToString { it.code })
                    found.isNotEmpty() -> tr("Encontrei {0} jogo(s) pela etiqueta. Confira abaixo.", total)
                    r.barcodes.isNotEmpty() -> tr("Consultando o código de barras {0}…", r.barcodes.first())
                    text.isBlank() -> tr("Não consegui ler texto na foto. Use o Google Lens ou a busca por nome.")
                    else -> tr("Li o texto, mas não reconheci o jogo. Use o Google Lens ou a busca por nome.")
                }
            } catch (e: Exception) { status = tr("Não consegui ler a foto: {0}", e.message) } finally { reading = false }
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
    // O Android só deixa ler a área de transferência com o app em primeiro plano e com foco,
    // então confere quando a janela recebe o foco e mais algumas vezes logo depois de voltar.
    fun checkClip() {
        if (!lensPending) return
        val (t, stamp) = readClip(ctx) ?: return
        if (stamp != clipStamp && t.isNotBlank()) { clip = t; clipStamp = stamp }
    }
    val focused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused, lensPending) { if (focused && lensPending) for (d in listOf(150L, 500L, 1200L, 2500L)) { kotlinx.coroutines.delay(d); checkClip(); if (clip != null) break } }
    DisposableEffect(owner, lensPending) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME && lensPending) scope.launch { kotlinx.coroutines.delay(300); checkClip() } }
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
                        }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.PhotoCamera, null); Text(tr("  Fotografar")) }
                        OutlinedButton(onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Image, null); Text(tr("  Galeria")) }
                    }
                    OutlinedButton(onClick = { aoVivo = true }, modifier = Modifier.fillMaxWidth()) { Text(tr("Câmera ao vivo (selos na prateleira)")) }
                    val rd = lida
                    if (rd != null && rd.words.isNotEmpty()) {
                        WordPhoto(rd, marcadas.toSet(), onToggle = { i ->
                            if (i in marcadas) marcadas.remove(i) else marcadas.add(i)
                            // ordem de leitura: de cima para baixo, da esquerda para a direita
                            search = marcadas.sortedWith(compareBy({ (rd.words[it].t * 20).toInt() }, { rd.words[it].l })).joinToString(" ") { rd.words[it].text }
                        })
                        Hint(tr("Toque nas palavras da foto para montar a busca (o resultado aparece em Buscar pelo nome, abaixo)."))
                    } else photo?.let { AsyncImage(model = it, contentDescription = tr("Foto"), modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)) }
                    Row { if (reading) CircularProgressIndicator(Modifier.width(20.dp).padding(end = 8.dp)); Hint(status) }
                    photo?.let { f ->
                        OutlinedButton(onClick = { clipStamp = readClip(ctx)?.second ?: -1L; clip = null; lensPending = openLens(ctx, fileUri(ctx, f)) }, modifier = Modifier.fillMaxWidth()) {
                            Text(tr("Perguntar ao Google Lens"))
                        }
                        Hint(tr("No Lens, segure o dedo no nome do jogo (no texto da foto ou num resultado), toque em Copiar e volte para cá: o app oferece o nome copiado. Se não oferecer, use o botão Colar em Buscar pelo nome."))
                    }
                }
            }
        }
        clip?.let { t ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tr("Você copiou “{0}”. Procurar este jogo?", cleanClip(t)), fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { search = cleanClip(t); clip = null; lensPending = false }) { Text(tr("Procurar")) }
                            TextButton(onClick = { clip = null; lensPending = false }) { Text(tr("Agora não")) }
                        }
                    }
                }
            }
        }
        if (parecidos.isNotEmpty()) item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tr("Parecido pela capa"), fontWeight = FontWeight.SemiBold)
                    parecidos.toList().forEach { p ->
                        TextButton(onClick = {
                            parecidos.remove(p)
                            cands.add(0, Cand(p.titulo, p.console.ifBlank { consoleFor(m, p.repo) }, m.catalog?.label(p.repo) ?: p.console, p.repo, origem = "capa"))
                        }, modifier = Modifier.fillMaxWidth()) { Text("${p.titulo} · ${cn(p.console.ifBlank { m.catalog?.label(p.repo) ?: "" })} · ${(p.score * 100).toInt()}%", modifier = Modifier.fillMaxWidth()) }
                    }
                }
            }
        }
        items(cands, key = { it.repo + it.titulo }) { c -> CandCard(m, c, onOpen, onReplace = { nc -> val i = cands.indexOf(c); if (i >= 0) cands[i] = nc }) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Buscar pelo nome"), fontWeight = FontWeight.SemiBold)
                    NameSearch(m, search, onQuery = { search = it }) { c -> cands.add(0, c) }
                }
            }
        }
        item { Spacer8() }
    }
    if (aoVivo) LiveCameraDialog(m, onOpen = onOpen, onPick = { c -> if (cands.none { it.repo == c.repo && it.titulo == c.titulo }) cands.add(0, c) }, onClose = { aoVivo = false })
}

/** Busca por nome (ou parte dele) na planilha e no catálogo; ao escolher, vira um cartão de candidato. */
@Composable
fun NameSearch(m: AppModel, query: String, onQuery: (String) -> Unit, onPick: (Cand) -> Unit) {
    val consoles = m.repo.state.value.consoles
    var console by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    OutlinedTextField(value = query, onValueChange = onQuery, label = { Text(tr("Nome do jogo ou parte dele")) }, singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth(),
        trailingIcon = { TextButton(onClick = { readClip(ctx)?.first?.let { onQuery(cleanClip(it)) } }) { Text(tr("Colar")) } })
    if (m.catalog == null) Hint(m.catalogError ?: tr("Carregando o catálogo de jogos…"))
    Picker(tr("Console"), console, listOf("" to tr("Todos os consoles")) + consoles.map { it to cn(it) }) { console = it }
    val q = query.trim()
    if (q.length >= 2) {
        val mine = m.repo.state.value.items.filter { (console.isEmpty() || m.repo.platformOf(it.console) == m.repo.platformOf(console)) && T.score(q, it.nome) >= 0.6 }
            .sortedByDescending { T.score(q, it.nome) }.take(6)
        val repos = if (console.isEmpty()) consoles.map { m.repo.platformOf(it) }.filter { it.isNotEmpty() }.distinct() else listOfNotNull(m.repo.platformOf(console).ifEmpty { null })
        val cat = m.catalog?.search(q, repos, limit = 10, minScore = 0.6) ?: emptyList()
        mine.forEach { i -> TextButton(onClick = { onPick(Cand(i.nome, i.console, i.console, m.repo.platformOf(i.console), origem = "planilha")) }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) { Text(i.nome); Hint(tr("{0} · {1} · linha {2}", cn(i.console), tr(i.lista.titulo), i.linha)) } } }
        cat.forEach { c -> TextButton(onClick = { onPick(Cand(c.tituloLimpo, console.ifEmpty { consoleFor(m, c.repo) }, c.plataforma, c.repo, origem = "busca")) }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) { Text(c.tituloLimpo); Hint(tr("Catálogo · {0}", c.plataforma)) } } }
        if (mine.isEmpty() && cat.isEmpty()) Hint(tr("Nada parecido. Tente outro nome ou outro console."))
        OutlinedButton(onClick = { onPick(Cand(q, console, console, m.repo.platformOf(console), origem = "digitado")) }) { Text(tr("Usar o nome digitado")) }
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
                Cover(cover, cn(c.console).ifEmpty { c.plataforma }, Modifier.width(84.dp), title = c.titulo)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(c.titulo, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Hint(listOf(cn(c.console), c.plataforma).filter { it.isNotBlank() }.distinct().joinToString(" · ").ifEmpty { tr("Console não identificado") })
                    when {
                        have.isNotEmpty() -> Pill(tr("Você já tem"), Ok)
                        buying.isNotEmpty() -> Pill(tr("Já está nas compras"), Warn)
                        want.isNotEmpty() -> Pill(tr("Está nos procurados"), Info)
                        else -> Pill(tr("Você não tem"), MaterialTheme.colorScheme.primary)
                    }
                    Hint(when {
                        c.origem == "etiqueta" -> tr("Reconhecido pela etiqueta"); c.origem == "planilha" -> tr("Escolhido da sua planilha"); c.origem == "busca" -> tr("Escolhido no catálogo")
                        c.origem.startsWith("codigo:") -> tr("Identificado pelo código {0} (exato)", c.origem.substringAfter(':'))
                        c.origem == "capa" -> tr("Reconhecido pela imagem da capa")
                        c.origem.startsWith("barras:") -> tr("Identificado pelo código de barras {0}", c.origem.substringAfter(':'))
                        else -> tr("Nome digitado") })
                }
            }
            found.take(3).forEach { f -> TextButton(onClick = { onOpen(f.item) }) { Text(tr("{0}: {1} · {2} (linha {3})", tr(f.item.lista.titulo), cn(f.item.console), f.item.nome, f.item.linha)) } }
            Chips {
                if (have.isEmpty() && buying.isEmpty()) {
                    AssistChip(onClick = { dialog = "comprei" }, label = { Text(tr("Comprei")) })
                    AssistChip(onClick = { dialog = "colecao" }, label = { Text(tr("Já tenho: pôr na coleção")) })
                    if (want.isEmpty() && m.repo.state.value.config?.procurados?.isNotEmpty() == true) AssistChip(onClick = { dialog = "quero" }, label = { Text(tr("Quero (procurados)")) })
                }
                if (have.isNotEmpty()) AssistChip(onClick = { dialog = "comprei" }, label = { Text(tr("Comprei outra cópia")) })
            }
            if (c.alternativas.isNotEmpty()) {
                SectionTitle(tr("Pode ser também"))
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

package io.github.vprodc83.gamecatalog.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.vprodc83.gamecatalog.core.Text as T
import io.github.vprodc83.gamecatalog.data.Item
import io.github.vprodc83.gamecatalog.data.Lista
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Coleção, procurados ou compras, com busca e filtro por console. */
@Composable
fun ListScreen(m: AppModel, lista: Lista, onOpen: (Item) -> Unit) {
    val st by m.repo.state.collectAsState()
    @Suppress("UNUSED_VARIABLE") val pv = m.photosVersion
    var q by remember(lista) { mutableStateOf("") }
    var console by remember(lista) { mutableStateOf("") }
    val all = remember(st, lista) { st.of(lista) }
    val consoles = remember(all) { all.map { it.console.uppercase() }.filter { it.isNotBlank() }.distinct().sorted() }
    val shown = remember(all, q, console) {
        all.filter { (console.isEmpty() || it.console.equals(console, true)) && (q.isBlank() || T.score(q, it.nome) >= 0.6 || T.norm(it.nome).contains(T.norm(q))) }
            .let { l -> if (q.isBlank()) l.sortedWith(compareBy({ T.norm(it.console) }, { T.norm(it.nome) })) else l.sortedByDescending { T.score(q, it.nome) } }
    }
    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = q, onValueChange = { q = it }, label = { Text(tr("Procurar em {0}", tr(lista.titulo).lowercase())) }, singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth())
                if (consoles.size > 1) Picker(tr("Console"), console, listOf("" to tr("Todos ({0})", all.size)) + consoles.map { c -> c to "${cn(c)} (${all.count { it.console.equals(c, true) }})" }) { console = it }
                if (lista == Lista.COLECAO && all.isNotEmpty()) CoverUpdateCard(m)
                Hint(when {
                    st.offline -> tr("Sem internet: mostrando a última cópia salva no aparelho. {0} jogo(s).", shown.size)
                    else -> tr("{0} jogo(s)", shown.size)
                })
            }
        }
        if (all.isEmpty()) item {
            Hint(when (lista) {
                Lista.COLECAO -> tr("Sua coleção está vazia. Use Identificar para incluir jogos.")
                Lista.PROCURADO -> if (st.config?.procurados.isNullOrEmpty()) tr("Nenhuma aba de procurados configurada (veja em Ajustes).") else tr("Nenhum jogo procurado em aberto.")
                Lista.COMPRADO -> tr("Nenhuma compra aguardando chegada. Quando comprar um jogo, toque em Comprei.")
            })
        }
        items(shown, key = { it.id }) { i -> ItemRow(m, i) { onOpen(i) } }
        item { Spacer8() }
    }
}

@Composable
fun ItemRow(m: AppModel, i: Item, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(m.coverFor(i), cn(i.console), Modifier.width(52.dp))
            Column(Modifier.weight(1f)) {
                Text(i.nome, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Hint(listOf(cn(i.console), i.regiao, if (i.lista == Lista.PROCURADO) cn(i.aba) else "", if (i.lista == Lista.COMPRADO) tr("comprado {0}", i.data) else "").filter { it.isNotBlank() }.joinToString(" · "))
                if (i.lista == Lista.PROCURADO && i.info.isNotBlank()) Hint(i.info)
            }
        }
    }
}

/** Ficha do jogo: capa, dados da planilha e ações. */
@Composable
fun DetailScreen(m: AppModel, item0: Item, onBack: () -> Unit) {
    val st by m.repo.state.collectAsState()
    // depois de atualizar, acha a mesma linha de novo (pode ter mudado de posição)
    val item = remember(st) { st.items.firstOrNull { it.id == item0.id && it.chave == item0.chave } ?: st.items.firstOrNull { it.lista == item0.lista && it.chave == item0.chave } ?: item0 }
    @Suppress("UNUSED_VARIABLE") val pv = m.photosVersion
    var dialog by remember { mutableStateOf<String?>(null) }
    var zoom by remember { mutableStateOf<Any?>(null) }
    val repoId = m.repo.platformOf(item.console)
    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick = onBack) { Text(tr("‹ Voltar")) } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { val cv = m.coverFor(item, "capa"); Cover(cv, cn(item.console), Modifier.fillMaxWidth().clickable(enabled = cv != null) { zoom = cv }); TextButton(onClick = { dialog = "capa" }) { Text(tr("Trocar capa")) } }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { val md = m.coverFor(item, "midia"); Cover(md, tr("Foto do cartucho/disco"), Modifier.fillMaxWidth().clickable(enabled = md != null) { zoom = md }); TextButton(onClick = { dialog = "midia" }) { Text(tr("Foto da mídia")) } }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.nome, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Hint(tr("{0} · aba {1} · linha {2}", tr(item.lista.titulo), cn(item.aba), item.linha))
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val consoleCol = st.config?.colecao?.console ?: -1
                    if (item.lista == Lista.COLECAO) item.raw.forEachIndexed { k, v -> if (v.isNotBlank()) Row { Text(m.colName(k) + ": ", fontWeight = FontWeight.SemiBold); Text(if (k == consoleCol) cn(v) else v) } }
                    else {
                        listOf(tr("Console") to cn(item.console), tr("Região") to item.regiao, tr("Status") to item.status, tr("Observação") to item.info.ifBlank { item.obs }, tr("Data da compra") to item.data)
                            .filter { it.second.isNotBlank() }.forEach { (k, v) -> Row { Text("$k: ", fontWeight = FontWeight.SemiBold); Text(v) } }
                    }
                }
            }
        }
        item {
            Chips {
                when (item.lista) {
                    Lista.COLECAO -> {
                        Button(onClick = { dialog = "editar" }) { Text(tr("Editar dados")) }
                        OutlinedButton(onClick = { dialog = "comprei" }) { Text(tr("Comprei outra cópia")) }
                        OutlinedButton(onClick = { dialog = "excluir" }, colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(tr("Excluir")) }
                    }
                    Lista.PROCURADO -> Button(onClick = { dialog = "comprei" }) { Text(tr("Comprei")) }
                    Lista.COMPRADO -> Button(onClick = { dialog = "recebi" }) { Text(tr("Recebi")) }
                }
            }
        }
        item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { PriceBox(m, item.nome, item.console, repoId, key = item.chave) } }
        item { Spacer8() }
    }
    when (dialog) {
        "editar" -> EditDialog(m, item) { dialog = null }
        "comprei" -> BuyDialog(m, item.console, item.nome, if (item.lista == Lista.PROCURADO) item else null) { dialog = null }
        "recebi" -> ReceiveDialog(m, item) { dialog = null }
        "excluir" -> DeleteDialog(m, item, onClose = { dialog = null }, onDeleted = onBack)
        "capa", "midia" -> CoverDialog(m, item, dialog!!) { dialog = null }
    }
    zoom?.let { ImageViewer(it, item.nome) { zoom = null } }
}

/** Trocar a capa: sugestões do catálogo por semelhança de nome, busca livre, ou uma foto sua. */
@Composable
fun CoverDialog(m: AppModel, item: Item, tipo: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf(item.nome) }
    val repoId = m.repo.platformOf(item.console)
    val sug = remember(q, m.catalog) { m.catalog?.let { c -> c.search(q, listOfNotNull(repoId.ifEmpty { null }), limit = 12, minScore = 0.5).mapNotNull { r -> c.coverUrl(r.repo, r.nomeAcervo)?.let { r.nomeAcervo to it } } } ?: emptyList() }
    fun keep(src: File) {
        val f = File(m.photoDir(), "${tipo}-${System.currentTimeMillis()}.jpg"); src.copyTo(f, overwrite = true)
        m.photo(item.chave, tipo)?.delete(); m.setPhoto(item.chave, tipo, f); onClose()
    }
    val shot = remember { mutableStateOf<File?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) shot.value?.let { keep(it) } }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val tmp = File(File(ctx.cacheDir, "fotos").apply { mkdirs() }, "g-${System.currentTimeMillis()}.jpg")
            withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { i.copyTo(it) } } }
            keep(tmp)
        }
    }
    ConfirmDialog(if (tipo == "capa") tr("Escolher a capa") else tr("Foto da mídia"), tr("Fechar"), onClose, onConfirm = onClose) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { val f = File(File(ctx.cacheDir, "fotos").apply { mkdirs() }, "c-${System.currentTimeMillis()}.jpg"); shot.value = f; camera.launch(fileUri(ctx, f)) }) { Text(tr("Fotografar")) }
            OutlinedButton(onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text(tr("Galeria")) }
        }
        Hint(tr("Fotos suas ficam só neste aparelho."))
        if (tipo == "capa") {
            HorizontalDivider()
            OutlinedTextField(value = q, onValueChange = { q = it }, label = { Text(tr("Buscar capas pelo nome")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (sug.isEmpty()) Hint(tr("Nenhuma capa parecida no catálogo") + (if (repoId.isEmpty()) tr(" (console {0} sem acervo associado).", cn(item.console)) else "."))
            sug.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (nome, url) ->
                        Column(Modifier.weight(1f).clickable {
                            onClose(); m.photo(item.chave, "capa")?.let { it.delete(); m.setPhoto(item.chave, "capa", null) }
                            m.run { m.repo.setCapa(item.chave, "capa", url, nome); m.message = tr("Capa trocada para todos que usam a planilha.") }
                        }) { Cover(url, "", Modifier.fillMaxWidth()); Text(nome, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                    repeat(3 - row.size) { Column(Modifier.weight(1f)) {} }
                }
            }
            HorizontalDivider()
        }
        AssistChip(onClick = {
            onClose(); m.photo(item.chave, tipo)?.let { it.delete(); m.setPhoto(item.chave, tipo, null) }
            if (m.repo.state.value.capas[item.chave]?.get(tipo) != null) m.run { m.repo.setCapa(item.chave, tipo, "", "") }
        }, label = { Text(if (tipo == "capa") tr("Voltar à capa automática") else tr("Remover a foto")) })
    }
}

/** Imagem em tela cheia: pinça para aproximar, arrastar para mover, dois toques para aproximar/voltar. */
@Composable
fun ImageViewer(model: Any, title: String, onClose: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
            coil3.compose.AsyncImage(model = model, contentDescription = title, contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures(onDoubleTap = { if (scale > 1f) { scale = 1f; offset = androidx.compose.ui.geometry.Offset.Zero } else scale = 2.5f }) }
                    .pointerInput(Unit) { detectTransformGestures { _, pan, zoomBy, _ -> scale = (scale * zoomBy).coerceIn(1f, 6f); offset = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else offset + pan } }
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y))
            androidx.compose.material3.FilledTonalButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp)) { Text(tr("‹ Voltar")) }
            Text(tr("Pinça para aproximar · dois toques para ampliar"), color = androidx.compose.ui.graphics.Color.White.copy(alpha = .7f), style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp))
        }
    }
}

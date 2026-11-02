package io.github.vprodc83.gamecatalog.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import io.github.vprodc83.gamecatalog.data.Item
import io.github.vprodc83.gamecatalog.data.Lista
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.File

/**
 * Atualiza as capas da coleção pela internet (catálogo libretro), baixando tudo para o celular.
 * Fotos tiradas por você nunca são trocadas sozinhas: cada uma vai para uma lista de revisão,
 * onde você compara a sua foto com a da internet e decide.
 */
class CoverUpdater(private val m: AppModel) {
    data class Review(val item: Item, val mine: File, val web: String)

    var running by mutableStateOf(false); private set
    var done by mutableStateOf(0); private set
    var total by mutableStateOf(0); private set
    var summary by mutableStateOf<String?>(null)
    val reviews = mutableStateListOf<Review>()
    private var job: Job? = null

    fun start(ctx: Context) {
        if (running) return
        val cat = m.catalog ?: run { summary = m.catalogError ?: "O catálogo de jogos ainda está carregando."; return }
        val items = m.repo.state.value.of(Lista.COLECAO).distinctBy { it.chave }
        running = true; done = 0; total = items.size; summary = null; reviews.clear()
        job = m.scope.launch {
            var found = 0; var missing = 0; var failed = 0
            val loader = SingletonImageLoader.get(ctx)
            val gate = Semaphore(4)
            m.clearAutoCovers()
            coroutineScope {
                items.map { item ->
                    async {
                        gate.withPermit {
                            val url = withContext(Dispatchers.Default) {
                                val repoId = m.repo.platformOf(item.console)
                                if (repoId.isEmpty()) null else cat.search(item.nome, listOf(repoId), limit = 1, minScore = 0.8, regiao = item.regiao).firstOrNull()?.let { cat.coverUrl(it.repo, it.nomeAcervo) }
                            }
                            if (url == null) missing++
                            else {
                                val ok = runCatching { loader.execute(ImageRequest.Builder(ctx).data(url).build()) is SuccessResult }.getOrDefault(false)
                                if (ok) {
                                    found++
                                    val mine = m.photo(item.chave, "capa")
                                    if (mine != null) reviews.add(Review(item, mine, url))
                                } else failed++
                            }
                            done++
                        }
                    }
                }.awaitAll()
            }
            m.photosVersion++
            summary = buildString {
                append("$found capa(s) atualizadas")
                if (missing > 0) append(" · $missing sem capa no catálogo")
                if (failed > 0) append(" · $failed não baixaram (internet?)")
                append(". Fotos de cartuchos e discos: não há fonte gratuita na internet; as suas fotos continuam como estão.")
                if (reviews.isNotEmpty()) append(" Você tem ${reviews.size} foto(s) sua(s) para comparar.")
            }
            running = false
        }
    }

    fun cancel() { job?.cancel(); running = false; summary = "Atualização interrompida ($done de $total)." }
}

/** Cartão no topo da Coleção: botão, progresso e resultado. */
@Composable
fun CoverUpdateCard(m: AppModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val u = m.covers
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (u.running) {
                Text("Atualizando capas: ${u.done} de ${u.total}")
                LinearProgressIndicator(progress = { if (u.total == 0) 0f else u.done.toFloat() / u.total }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { u.cancel() }) { Text("Parar") }
            } else {
                OutlinedButton(onClick = { u.start(ctx) }, modifier = Modifier.fillMaxWidth()) { Text("Atualizar capas pela internet") }
                u.summary?.let { Hint(it) }
                if (u.reviews.isNotEmpty()) TextButton(onClick = { m.showReview = true }) { Text("Comparar as minhas fotos (${u.reviews.size})") }
            }
        }
    }
    if (m.showReview && u.reviews.isNotEmpty()) ReviewDialog(m)
}


/** Compara a sua foto com a capa da internet; nada muda sem a sua escolha. */
@Composable
fun ReviewDialog(m: AppModel) {
    val u = m.covers
    val r = u.reviews.firstOrNull() ?: return
    AlertDialog(onDismissRequest = { m.showReview = false },
        title = { Text("Trocar a sua foto?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(r.item.nome, fontWeight = FontWeight.SemiBold)
                Hint("${cn(r.item.console)} · faltam ${u.reviews.size}")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) { Text("Sua foto", style = MaterialTheme.typography.labelMedium); Cover(r.mine, "", Modifier.fillMaxWidth()) }
                    Column(Modifier.weight(1f)) { Text("Da internet", style = MaterialTheme.typography.labelMedium); Cover(r.web, "", Modifier.fillMaxWidth()) }
                }
                TextButton(onClick = { u.reviews.clear(); m.showReview = false }) { Text("Manter todas as minhas fotos") }
            }
        },
        confirmButton = { TextButton(onClick = {
            m.photo(r.item.chave, "capa")?.delete(); m.setPhoto(r.item.chave, "capa", null); u.reviews.removeAt(0)
        }) { Text("Usar a da internet") } },
        dismissButton = { TextButton(onClick = { u.reviews.removeAt(0) }) { Text("Manter a minha") } })
}

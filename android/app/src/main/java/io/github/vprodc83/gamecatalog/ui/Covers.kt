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
        val cat = m.catalog ?: run { summary = m.catalogError ?: tr("O catálogo de jogos ainda está carregando."); return }
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
                append(tr("{0} capa(s) atualizadas", found))
                if (missing > 0) append(tr(" · {0} sem capa no catálogo", missing))
                if (failed > 0) append(tr(" · {0} não baixaram (internet?)", failed))
                append(tr(". Fotos de cartuchos e discos: não há fonte gratuita na internet; as suas fotos continuam como estão."))
                if (reviews.isNotEmpty()) append(tr(" Você tem {0} foto(s) sua(s) para comparar.", reviews.size))
            }
            running = false
        }
    }

    fun cancel() { job?.cancel(); running = false; summary = tr("Atualização interrompida ({0} de {1}).", done, total) }
}

/** Cartão no topo da Coleção: botão, progresso e resultado. */
@Composable
fun CoverUpdateCard(m: AppModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val u = m.covers
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (u.running) {
                Text(tr("Atualizando capas: {0} de {1}", u.done, u.total))
                LinearProgressIndicator(progress = { if (u.total == 0) 0f else u.done.toFloat() / u.total }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { u.cancel() }) { Text(tr("Parar")) }
            } else {
                OutlinedButton(onClick = { u.start(ctx) }, modifier = Modifier.fillMaxWidth()) { Text(tr("Atualizar capas pela internet")) }
                u.summary?.let { Hint(it) }
                if (u.reviews.isNotEmpty()) TextButton(onClick = { m.showReview = true }) { Text(tr("Comparar as minhas fotos ({0})", u.reviews.size)) }
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
        title = { Text(tr("Trocar a sua foto?")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(r.item.nome, fontWeight = FontWeight.SemiBold)
                Hint(tr("{0} · faltam {1}", cn(r.item.console), u.reviews.size))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) { Text(tr("Sua foto"), style = MaterialTheme.typography.labelMedium); Cover(r.mine, "", Modifier.fillMaxWidth()) }
                    Column(Modifier.weight(1f)) { Text(tr("Da internet"), style = MaterialTheme.typography.labelMedium); Cover(r.web, "", Modifier.fillMaxWidth()) }
                }
                TextButton(onClick = { u.reviews.clear(); m.showReview = false }) { Text(tr("Manter todas as minhas fotos")) }
            }
        },
        confirmButton = { TextButton(onClick = {
            m.photo(r.item.chave, "capa")?.delete(); m.setPhoto(r.item.chave, "capa", null); u.reviews.removeAt(0)
        }) { Text(tr("Usar a da internet")) } },
        dismissButton = { TextButton(onClick = { u.reviews.removeAt(0) }) { Text(tr("Manter a minha")) } })
}

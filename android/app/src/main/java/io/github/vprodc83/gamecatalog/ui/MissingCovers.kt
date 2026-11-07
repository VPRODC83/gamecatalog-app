package io.github.vprodc83.gamecatalog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.vprodc83.gamecatalog.core.CoverSources.Hit
import io.github.vprodc83.gamecatalog.data.Item
import io.github.vprodc83.gamecatalog.data.Lista
import kotlinx.coroutines.launch

/** Grade de sugestões de capa (de todas as fontes), com a fonte embaixo de cada uma. */
@Composable
fun SuggestionGrid(hits: List<Hit>, console: String, onPick: (Hit) -> Unit) {
    hits.filter { it.tipo == "capa" }.distinctBy { it.url }.chunked(3).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { h ->
                Column(Modifier.weight(1f).clickable { onPick(h) }) {
                    Cover(h.url, cn(console), Modifier.fillMaxWidth())
                    Text(h.fonte, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(h.titulo, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            repeat(3 - row.size) { Column(Modifier.weight(1f)) {} }
        }
    }
}

/** Tela "Jogos sem capa" (melhoria 21): sugestões de todas as fontes; a escolha vale para todos (aba CAPAS). */
@Composable
fun MissingCoversDialog(m: AppModel, onClose: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val v = m.resolver.version
    val scope = rememberCoroutineScope()
    val all = m.repo.state.value.of(Lista.COLECAO).distinctBy { it.chave }
    val missing = all.filter { m.coverFor(it) == null }
    var open by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }
    Dialog(onDismissRequest = { if (!running) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose, enabled = !running) { Text(tr("‹ Voltar")) }
                Text(tr("Jogos sem capa ({0})", missing.size), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Hint(tr("Toque em Sugestões para ver capas de todas as fontes (libretro, Wikipédia, PriceCharting, VGDB, Tec Toy, TheGamesDB). A escolhida fica salva na planilha e vale para todos."))
            if (running) {
                Text(tr("Procurando e salvando: {0} de {1}", done, total))
                LinearProgressIndicator(progress = { if (total == 0) 0f else done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
            } else if (missing.isNotEmpty()) Button(onClick = {
                running = true; done = 0; total = missing.size
                scope.launch {
                    val found = ArrayList<Triple<String, String, String>>()
                    for (it in missing) { m.resolver.best(it)?.let { h -> found.add(Triple(it.chave, h.url, h.fonte + ": " + h.titulo)) }; done++ }
                    m.run { m.repo.setCapas(found); m.message = tr("{0} capa(s) salvas na planilha; {1} continuam sem capa.", found.size, missing.size - found.size) }
                    running = false
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(tr("Aplicar a melhor sugestão em todos")) }
            if (missing.isEmpty()) Text(tr("Todos os jogos da coleção têm capa."))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(missing, key = { it.chave }) { item -> MissingRow(m, item, open == item.chave) { open = if (open == item.chave) null else item.chave } }
            }
        }
    }
}

@Composable
private fun MissingRow(m: AppModel, item: Item, expanded: Boolean, onToggle: () -> Unit) {
    var hits by remember(item.chave) { mutableStateOf<List<Hit>?>(null) }
    if (expanded) LaunchedEffect(item.chave) { if (hits == null) hits = m.resolver.suggestions(item) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(null, cn(item.console), Modifier.width(44.dp), title = item.nome)
                Column(Modifier.weight(1f)) { Text(item.nome, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis); Hint(cn(item.console)) }
                OutlinedButton(onClick = onToggle) { Text(if (expanded) tr("Fechar") else tr("Sugestões")) }
            }
            if (expanded) {
                val h = hits
                when {
                    h == null -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.width(20.dp)); Hint("  " + tr("Procurando em todas as fontes…")) }
                    h.none { it.tipo == "capa" } -> Hint(tr("Nenhuma fonte tem capa para este nome. Tente Trocar capa na ficha do jogo (busca por outro nome ou foto sua)."))
                    else -> SuggestionGrid(h, item.console) { pick -> m.run { m.repo.setCapa(item.chave, "capa", pick.url, pick.fonte + ": " + pick.titulo); m.message = tr("Capa salva para todos que usam a planilha.") } }
                }
            }
        }
    }
}

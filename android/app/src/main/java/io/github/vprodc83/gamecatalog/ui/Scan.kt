package io.github.vprodc83.gamecatalog.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.vprodc83.gamecatalog.core.Text as T
import io.github.vprodc83.gamecatalog.data.Item
import io.github.vprodc83.gamecatalog.data.Lista

/** Um jogo que está nos procurados (COMPRADO) ou nas compras (RECEBIDO), mas ainda não em GAMES. */
data class Missing(val origem: Item, val row: List<String>, val pendente: Boolean)

/**
 * Varredura (opção A): procurados marcados COMPRADO e compras RECEBIDAS que não estão na coleção.
 * Um jogo comprado que ainda está pendente nas compras é marcado como pendente.
 */
fun findMissing(m: AppModel): List<Missing> {
    val st = m.repo.state.value; val c = st.config?.colecao ?: return emptyList()
    val pend = st.of(Lista.COMPRADO)
    val seen = HashSet<String>()
    return (st.wantedBought + st.received).mapNotNull { it0 ->
        val inGames = m.repo.find(it0.nome, it0.console.ifBlank { null }).any { it.item.lista == Lista.COLECAO }
        if (inGames) return@mapNotNull null
        val row = m.repo.emptyCollectionRow(it0.console, it0.nome, it0.regiao)
        val key = T.norm(row[c.console]) + "|" + T.norm(it0.nome)
        if (!seen.add(key)) return@mapNotNull null
        val pendente = it0.lista == Lista.PROCURADO && pend.any { p -> T.score(p.nome, it0.nome) >= 0.9 }
        Missing(it0, row, pendente)
    }
}

/** Botões no topo da Coleção: jogo novo e varredura. */
@Composable
fun CollectionTools(m: AppModel) {
    var dialog by remember { mutableStateOf<String?>(null) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { dialog = "novo" }, modifier = Modifier.weight(1f)) { Text(tr("Novo jogo")) }
        OutlinedButton(onClick = { dialog = "varrer" }, modifier = Modifier.weight(1f)) { Text(tr("Faltam em {0}?", m.repo.state.value.config?.colecao?.aba ?: "GAMES")) }
    }
    when (dialog) {
        "novo" -> AddCollectionDialog(m, "", "") { dialog = null }
        "varrer" -> ScanDialog(m) { dialog = null }
    }
}

@Composable
fun ScanDialog(m: AppModel, onClose: () -> Unit) {
    val c = m.repo.state.value.config?.colecao ?: return
    val list = remember { findMissing(m) }
    val on = remember { mutableStateMapOf<Int, Boolean>().apply { list.indices.forEach { put(it, true) } } }
    val n = on.count { it.value }
    ConfirmDialog(tr("Jogos que faltam em {0}", c.aba), tr("Incluir {0} jogo(s)", n), onClose, enabled = n > 0, onConfirm = {
        onClose(); val rows = list.filterIndexed { i, _ -> on[i] == true }.map { it.row }
        m.run { m.repo.addManyToCollection(rows); m.repo.refresh(); m.message = tr("{0} jogo(s) incluído(s) em {1}.", rows.size, c.aba) }
    }) {
        if (list.isEmpty()) { Text(tr("Nada faltando: todos os procurados marcados COMPRADO e as compras recebidas já estão em {0}.", c.aba)); return@ConfirmDialog }
        Hint(tr("Procurados marcados COMPRADO e compras recebidas que não estão em {0}. Desmarque o que não quiser incluir. Nenhuma linha existente é alterada.", c.aba))
        if (list.any { it.pendente }) Text(tr("Os marcados “ainda não recebido” estão pendentes nas compras."), color = Warn, style = MaterialTheme.typography.bodySmall)
        Row { TextButton(onClick = { list.indices.forEach { on[it] = true } }) { Text(tr("Marcar todos")) }; TextButton(onClick = { list.indices.forEach { on[it] = false } }) { Text(tr("Desmarcar todos")) } }
        list.forEachIndexed { i, x ->
            Card(Modifier.fillMaxWidth().clickable { on[i] = on[i] != true }) {
                Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = on[i] == true, onCheckedChange = { on[i] = it })
                    Column(Modifier.weight(1f)) {
                        Text(x.origem.nome, fontWeight = FontWeight.SemiBold)
                        Hint(listOf(cn(x.row[c.console]), if (c.regiao >= 0) x.row[c.regiao] else "", tr("de {0}", cn(x.origem.aba))).filter { it.isNotBlank() }.joinToString(" · "))
                        if (x.pendente) Text(tr("ainda não recebido"), color = Warn, style = MaterialTheme.typography.labelSmall)
                        if (m.repo.options(c.aba, c.console)?.none { it == x.row[c.console] } == true)
                            Text(tr("console {0} não está na lista da tabela; ajuste depois em Editar dados", x.row[c.console]), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

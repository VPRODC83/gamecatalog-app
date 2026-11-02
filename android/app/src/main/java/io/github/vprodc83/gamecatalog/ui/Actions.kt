package io.github.vprodc83.gamecatalog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.vprodc83.gamecatalog.core.Prices
import io.github.vprodc83.gamecatalog.data.Item
import io.github.vprodc83.gamecatalog.data.Repo
import io.github.vprodc83.gamecatalog.google.Sheets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Campo de console com sugestões dos consoles que já estão na coleção. */
@Composable
fun ConsoleField(m: AppModel, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = { onChange(it.uppercase()) }, label = { Text(tr("Console")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        supportingText = if (cn(value) != value) ({ Text(cn(value)) }) else null)
    val sug = m.repo.state.value.consoles.filter { it != value && (value.isBlank() || it.contains(value, true)) }.take(8)
    if (sug.isNotEmpty()) Chips { sug.forEach { AssistChip(onClick = { onChange(it) }, label = { Text(cn(it)) }) } }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) =
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())

/** Diálogo padrão: o botão de confirmar só grava depois que você conferiu o que vai mudar. */
@Composable
fun ConfirmDialog(title: String, confirm: String, onClose: () -> Unit, enabled: Boolean = true, onConfirm: () -> Unit, content: @Composable () -> Unit) {
    AlertDialog(onDismissRequest = onClose, title = { Text(title) },
        text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() } },
        confirmButton = { TextButton(onClick = onConfirm, enabled = enabled) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onClose) { Text(tr("Cancelar")) } })
}

@Composable
fun BuyDialog(m: AppModel, console0: String, nome0: String, want: Item?, onClose: () -> Unit) {
    var console by remember { mutableStateOf(console0) }; var nome by remember { mutableStateOf(nome0) }
    var regiao by remember { mutableStateOf(want?.regiao ?: "") }; var obs by remember { mutableStateOf("") }
    val cfg = m.repo.state.value.config
    ConfirmDialog(tr("Comprei"), tr("Anotar na planilha"), onClose, enabled = nome.isNotBlank(), onConfirm = {
        onClose(); m.run { val w = m.repo.buy(console, nome.trim(), regiao, obs, want); m.repo.refresh(); m.message = w ?: tr("Anotado em {0}. Quando chegar, toque em Recebi.", cfg?.compras) }
    }) {
        ConsoleField(m, console) { console = it }
        Field(tr("Nome"), nome) { nome = it }
        Field(tr("Região (opcional)"), regiao) { regiao = it }
        Field(tr("Observação (onde, preço…)"), obs) { obs = it }
        Hint(tr("Vai para a aba {0} como uma linha nova.", cfg?.compras ?: "COMPRAS") + if (want != null) tr(" Na aba {0}, a linha {1} será marcada COMPRADO.", cn(want.aba), want.linha) else "")
    }
}

@Composable
fun AddCollectionDialog(m: AppModel, console0: String, nome0: String, onClose: () -> Unit) {
    var console by remember { mutableStateOf(console0) }; var nome by remember { mutableStateOf(nome0) }; var regiao by remember { mutableStateOf("") }
    val c = m.repo.state.value.config?.colecao ?: return
    ConfirmDialog(tr("Pôr na coleção"), tr("Incluir linha"), onClose, enabled = nome.isNotBlank() && console.isNotBlank(), onConfirm = {
        onClose(); m.run { m.repo.addToCollection(m.repo.emptyCollectionRow(console, nome.trim(), regiao)); m.repo.refresh(); m.message = tr("Incluído na coleção.") }
    }) {
        ConsoleField(m, console) { console = it }
        Field(tr("Nome"), nome) { nome = it }
        if (c.regiao >= 0) Field(tr("Região"), regiao) { regiao = it }
        Hint(tr("Será uma linha nova no fim da aba {0}. Nenhuma linha existente é alterada.", c.aba))
    }
}

@Composable
fun WantDialog(m: AppModel, console0: String, nome0: String, onClose: () -> Unit) {
    val tabs = m.repo.state.value.config?.procurados ?: emptyList()
    var wi by remember { mutableStateOf(tabs.indexOfFirst { it.console.isNotBlank() && m.repo.platformOf(it.console) == m.repo.platformOf(console0) }.coerceAtLeast(0)) }
    var console by remember { mutableStateOf(console0) }; var nome by remember { mutableStateOf(nome0) }; var info by remember { mutableStateOf("") }
    ConfirmDialog(tr("Quero este jogo"), tr("Incluir nos procurados"), onClose, enabled = nome.isNotBlank() && tabs.isNotEmpty(), onConfirm = {
        onClose(); m.run { m.repo.addWanted(console, nome.trim(), wi, info); m.repo.refresh(); m.message = tr("Incluído em {0}.", cn(tabs[wi].aba)) }
    }) {
        Picker(tr("Aba de procurados"), wi, tabs.mapIndexed { i, t -> i to cn(t.aba) }) { wi = it }
        if (tabs.getOrNull(wi)?.consoleCol ?: -1 >= 0) ConsoleField(m, console) { console = it }
        Field(tr("Nome"), nome) { nome = it }
        if (tabs.getOrNull(wi)?.info ?: -1 >= 0) Field(tr("Observação"), info) { info = it }
        Hint(tr("Será uma linha nova no fim da aba."))
    }
}

/** Edição de uma linha da coleção: primeiro você muda, depois confere o antes → depois e aceita. */
@Composable
fun EditDialog(m: AppModel, item: Item, onClose: () -> Unit) {
    val head = m.repo.state.value.head
    val vals = remember { mutableStateListOf<String>().apply { addAll(item.raw) } }
    var review by remember { mutableStateOf(false) }
    val diffs = vals.indices.filter { vals[it] != item.raw[it] }
    fun label(i: Int) = "${Sheets.col(i)} · ${m.colName(i)}"
    if (!review) ConfirmDialog(tr("Editar {0}", item.nome), tr("Revisar"), onClose, enabled = diffs.isNotEmpty(), onConfirm = { review = true }) {
        Hint(tr("Aba {0}, linha {1}.", cn(item.aba), item.linha))
        vals.indices.forEach { i -> Field(label(i), vals[i]) { vals[i] = it } }
    } else ConfirmDialog(tr("Confirmar alteração"), tr("Sim, alterar a planilha"), { review = false }, onConfirm = {
        onClose(); val novos = vals.toList()
        m.run {
            m.message = when (val r = m.repo.editRow(item, novos)) {
                Repo.EditResult.Ok -> tr("Linha {0} alterada.", item.linha)
                Repo.EditResult.Moved -> tr("A linha {0} mudou na planilha desde a leitura (alguém editou?). Nada foi gravado; atualize e tente de novo.", item.linha)
                is Repo.EditResult.Partial -> tr("Parte não foi gravada: {0}. Confira na planilha.", r.wrong.joinToString())
            }
            m.repo.refresh()
        }
    }) {
        Text(tr("Atenção: isto altera uma linha que já existe na planilha (aba {0}, linha {1}).", cn(item.aba), item.linha), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
        diffs.forEach { i ->
            Column { Text(label(i), fontWeight = FontWeight.SemiBold); Text(tr("Antes: ") + item.raw[i].ifEmpty { tr("(vazio)") }); Text(tr("Depois: ") + vals[i].ifEmpty { tr("(vazio)") }, color = MaterialTheme.colorScheme.primary) }
        }
        Hint(tr("Toque em Cancelar para voltar e corrigir."))
    }
}

/** Excluir: aviso claro, mostra a linha inteira e só apaga se ela continuar igual na planilha. */
@Composable
fun DeleteDialog(m: AppModel, item: Item, onClose: () -> Unit, onDeleted: () -> Unit) {
    ConfirmDialog(tr("Excluir {0}?", item.nome), tr("Sim, excluir da planilha"), onClose, onConfirm = {
        onClose()
        m.run {
            when (m.repo.deleteFromCollection(item)) {
                Repo.DeleteResult.Ok -> {
                    m.repo.refresh()
                    if (m.repo.state.value.items.none { it.chave == item.chave }) { for (t in listOf("capa", "midia")) { m.photo(item.chave, t)?.delete(); m.setPhoto(item.chave, t, null) } }
                    m.message = tr("{0} foi excluído da coleção.", item.nome); onDeleted()
                }
                Repo.DeleteResult.Moved -> { m.message = tr("A linha {0} mudou na planilha desde a leitura. Nada foi excluído; atualize e confira.", item.linha); m.repo.refresh() }
            }
        }
    }) {
        Text(tr("Atenção: a linha {0} da aba {1} será apagada da planilha, para todas as pessoas que usam ela. Isso não pode ser desfeito pelo app.", item.linha, cn(item.aba)), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
        item.raw.forEachIndexed { k, v -> if (v.isNotBlank()) Text("${m.colName(k)}: $v") }
        Hint(tr("Se precisar recuperar, use o histórico de versões do Google Sheets (Arquivo → Histórico de versões)."))
    }
}

/** Recebi: confere a linha que vai entrar na coleção e marca RECEBIDO nas compras. */
@Composable
fun ReceiveDialog(m: AppModel, buy: Item, onClose: () -> Unit) {
    val head = m.repo.state.value.head
    val c = m.repo.state.value.config?.colecao ?: return
    val vals = remember { mutableStateListOf<String>().apply { addAll(m.repo.emptyCollectionRow(buy.console, buy.nome, buy.regiao)) } }
    ConfirmDialog(tr("Recebi {0}", buy.nome), tr("Pôr na coleção"), onClose, enabled = vals[c.nome].isNotBlank(), onConfirm = {
        onClose(); val v = vals.toList()
        m.run {
            m.message = when (m.repo.receive(buy, v)) {
                Repo.ReceiveResult.Ok -> tr("{0} entrou na coleção.", buy.nome)
                Repo.ReceiveResult.Done -> tr("Essa compra já estava marcada como recebida.")
                Repo.ReceiveResult.Moved -> tr("A lista de compras mudou desde a leitura. Nada foi gravado; atualize e tente de novo.")
            }
            m.repo.refresh()
        }
    }) {
        Hint(tr("Linha nova no fim da aba {0}. Preencha o que quiser:", c.aba))
        vals.indices.forEach { i -> Field("${Sheets.col(i)} · ${m.colName(i)}", vals[i]) { vals[i] = it } }
    }
}

/** Preços: links das lojas do Brasil e valores do PriceCharting (em dólar e convertidos). */
@Composable
fun PriceBox(m: AppModel, titulo: String, plataforma: String, repo: String, key: String?) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val st by m.repo.state.collectAsState()
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var data by remember(key, st.precos[key ?: ""]) { mutableStateOf(key?.let { st.precos[it] }?.let { runCatching { JSONObject(it) }.getOrNull() }) }
    SectionTitle(tr("Preços"))
    Chips { Prices.links(titulo, plataforma).forEach { l -> AssistChip(onClick = { openUrl(ctx, l.url) }, label = { Text(l.loja) }) } }
    data?.let { d ->
        val dol = d.optDouble("dolar").takeIf { !it.isNaN() }
        fun v(k: String) = d.optDouble(k).takeIf { !it.isNaN() }
        Column {
            Text(tr("Mundo (PriceCharting · {0})", d.optString("produto")), fontWeight = FontWeight.SemiBold)
            listOf("loose" to tr("Só o jogo"), "cib" to tr("Completo"), "novo" to tr("Lacrado")).forEach { (k, t) ->
                v(k)?.let { Text("$t: ${usd(it)}" + (dol?.let { x -> " ≈ ${brl(it * x)}" } ?: "")) }
            }
            Hint(tr("Consultado em {0}", d.optString("data")) + (dol?.let { tr(" · dólar {0}", brl(it)) } ?: ""))
            TextButton(onClick = { openUrl(ctx, d.optString("url")) }) { Text(tr("Ver no PriceCharting")) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !loading, onClick = {
            loading = true; err = null
            scope.launch {
                try {
                    val (w, dol) = withContext(Dispatchers.IO) { Prices.world(titulo, repo) to Prices.dollar() }
                    if (w == null) err = tr("Não achei esse jogo no PriceCharting. Use os links das lojas.")
                    else {
                        val o = JSONObject().put("produto", w.produto).put("url", w.url).put("data", Repo.today())
                        w.loose?.let { o.put("loose", it) }; w.cib?.let { o.put("cib", it) }; w.novo?.let { o.put("novo", it) }; dol?.let { o.put("dolar", it) }
                        data = o
                        if (key != null) m.run { m.repo.savePrice(key, o.toString()) }
                    }
                } catch (e: Exception) { err = tr("Sem resposta do PriceCharting agora ({0}).", e.message) } finally { loading = false }
            }
        }) { Text(if (data == null) tr("Consultar valor mundial") else tr("Consultar de novo")) }
        if (loading) CircularProgressIndicator()
    }
    err?.let { Hint(it) }
}

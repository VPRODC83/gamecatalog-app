package io.github.vprodc83.gamecatalog.data

import io.github.vprodc83.gamecatalog.ui.tr
import io.github.vprodc83.gamecatalog.core.Platforms
import io.github.vprodc83.gamecatalog.core.Text
import io.github.vprodc83.gamecatalog.google.GoogleError
import io.github.vprodc83.gamecatalog.google.Sheets
import io.github.vprodc83.gamecatalog.google.Sheets.Companion.col
import io.github.vprodc83.gamecatalog.google.Sheets.Companion.q
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Os dados do app vêm da planilha do Google Sheets (compartilhada entre as pessoas da casa).
 * Uma cópia fica no aparelho para abrir rápido e mostrar a coleção mesmo sem internet;
 * toda gravação vai direto para a planilha.
 */
class Repo(private val dir: File, val sheets: Sheets) {
    data class Capa(val origem: String, val valor: String, val ref: String)
    data class State(
        val sheetId: String = "", val title: String = "", val config: Config? = null,
        val head: List<String> = emptyList(), val items: List<Item> = emptyList(),
        val capas: Map<String, Map<String, Capa>> = emptyMap(), val capasRows: Map<String, Int> = emptyMap(),
        val precos: Map<String, String> = emptyMap(), val precosRows: Map<String, Int> = emptyMap(),
        val atualizadoEm: Long = 0, val offline: Boolean = false
    ) {
        val consoles: List<String> get() = items.filter { it.lista == Lista.COLECAO }.map { it.console.uppercase() }.filter { it.isNotBlank() }.distinct().sorted()
        fun of(l: Lista) = items.filter { it.lista == l }
    }

    private val cacheFile = File(dir, "planilha-cache.json")
    private val prefsFile = File(dir, "ajustes.json")
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    val prefs: JSONObject = runCatching { JSONObject(prefsFile.readText()) }.getOrDefault(JSONObject())

    init { runCatching { loadCache() } }

    fun savePrefs() { prefsFile.writeText(prefs.toString()) }
    var sheetId: String
        get() = prefs.optString("sheetId")
        set(v) { prefs.put("sheetId", v); savePrefs() }

    // ---------------- leitura ----------------

    /** Lê a planilha inteira (coleção, procurados, compras, capas e preços) e guarda a cópia no aparelho. */
    suspend fun refresh(): State {
        val id = sheetId.ifBlank { throw IllegalStateException(tr("Nenhuma planilha escolhida")) }
        val meta = sheets.meta(id)
        val cfg = if (Config.TAB in meta.tabs) Config.parse(sheets.get(id, q(Config.TAB) + "!A1").firstOrNull()?.firstOrNull()) else null
        if (cfg == null) { _state.value = State(sheetId = id, title = meta.title); return _state.value }
        val c = cfg.colecao
        val ranges = mutableListOf("${q(c.aba)}!A1:${col(c.ultima)}5000")
        cfg.procurados.forEach { ranges.add("${q(it.aba)}!A1:Z5000") }
        ranges.add("${q(cfg.compras)}!A1:H5000")
        if (Config.CAPAS in meta.tabs) ranges.add("${q(Config.CAPAS)}!A1:F5000")
        if (Config.PRECOS in meta.tabs) ranges.add("${q(Config.PRECOS)}!A1:C5000")
        val vals = sheets.batchGet(id, ranges)
        val st = build(id, meta.title, cfg, vals, Config.CAPAS in meta.tabs, Config.PRECOS in meta.tabs)
        _state.value = st
        runCatching { saveCache(st, vals, meta.tabs) }
        return st
    }

    private fun pad(r: List<String>?, n: Int) = List(n) { k -> r?.getOrNull(k)?.trim() ?: "" }

    private fun build(id: String, title: String, cfg: Config, vals: List<List<List<String>>>, hasCapas: Boolean, hasPrecos: Boolean): State {
        val c = cfg.colecao; val n = c.ultima + 1
        val gv = vals.getOrElse(0) { emptyList() }
        val head = pad(gv.firstOrNull(), n)
        val items = ArrayList<Item>()
        gv.drop(1).forEachIndexed { i, r ->
            val raw = pad(r, n); val nome = raw[c.nome]; if (nome.isBlank()) return@forEachIndexed
            val flags = head.indices.filter { it != c.console && it != c.nome && it != c.regiao }.map { k -> (head[k].ifBlank { col(k) }) to raw[k] }
            items.add(Item(Lista.COLECAO, c.aba, i + 2, raw, raw[c.console], nome, if (c.regiao >= 0) raw[c.regiao] else "", flags = flags))
        }
        cfg.procurados.forEachIndexed { wi, w ->
            vals.getOrElse(1 + wi) { emptyList() }.drop(1).forEachIndexed { i, r ->
                val raw = pad(r, maxOf(r.size, 26)); val nome = raw.getOrElse(w.nome) { "" }; if (nome.isBlank()) return@forEachIndexed
                val status = if (w.status >= 0) raw[w.status] else ""
                if (Text.norm(status) == "COMPRADO") return@forEachIndexed   // já aparece em Compras
                items.add(Item(Lista.PROCURADO, w.aba, i + 2, raw, if (w.consoleCol >= 0) raw[w.consoleCol] else w.console, nome,
                    status = status, info = if (w.info >= 0) raw[w.info] else "", wi = wi))
            }
        }
        val bi = 1 + cfg.procurados.size
        vals.getOrElse(bi) { emptyList() }.drop(1).forEachIndexed { i, r ->
            val raw = pad(r, 8); if (raw[2].isBlank()) return@forEachIndexed
            if (Text.norm(raw[5]) == "RECEBIDO") return@forEachIndexed
            items.add(Item(Lista.COMPRADO, cfg.compras, i + 2, raw, raw[1], raw[2], raw[3], status = raw[5], info = raw[4], data = raw[0], obs = raw[7]))
        }
        var k = bi + 1
        val capas = HashMap<String, MutableMap<String, Capa>>(); val capasRows = HashMap<String, Int>()
        if (hasCapas) vals.getOrElse(k++) { emptyList() }.drop(1).forEachIndexed { i, r ->
            val p = pad(r, 5); if (p[0].isBlank() || p[1].isBlank()) return@forEachIndexed
            capasRows[p[0] + "|" + p[1]] = i + 2
            if (p[3].isNotBlank()) capas.getOrPut(p[0]) { HashMap() }[p[1]] = Capa(p[2], p[3], p[4])
        }
        val precos = HashMap<String, String>(); val precosRows = HashMap<String, Int>()
        if (hasPrecos) vals.getOrElse(k) { emptyList() }.drop(1).forEachIndexed { i, r ->
            val p = pad(r, 3); if (p[0].isBlank()) return@forEachIndexed
            precosRows[p[0]] = i + 2; if (p[2].isNotBlank()) precos[p[0]] = p[2]
        }
        return State(id, title, cfg, head, items, capas, capasRows, precos, precosRows, System.currentTimeMillis())
    }

    private fun saveCache(st: State, vals: List<List<List<String>>>, tabs: List<String>) {
        val o = JSONObject().put("sheetId", st.sheetId).put("title", st.title).put("config", st.config?.toString()).put("em", st.atualizadoEm)
            .put("hasCapas", Config.CAPAS in tabs).put("hasPrecos", Config.PRECOS in tabs)
            .put("vals", JSONArray(vals.map { t -> JSONArray(t.map { JSONArray(it) }) }))
        val tmp = File(dir, "planilha-cache.json.tmp"); tmp.writeText(o.toString()); tmp.renameTo(cacheFile)
    }

    private fun loadCache() {
        if (!cacheFile.exists()) return
        val o = JSONObject(cacheFile.readText())
        if (o.optString("sheetId") != sheetId) return
        val cfg = Config.parse(o.optString("config")) ?: return
        val va = o.getJSONArray("vals")
        val vals = (0 until va.length()).map { t -> va.getJSONArray(t).let { rs -> (0 until rs.length()).map { r -> rs.getJSONArray(r).let { cs -> (0 until cs.length()).map { cs.optString(it) } } } } }
        _state.value = build(o.getString("sheetId"), o.optString("title"), cfg, vals, o.optBoolean("hasCapas"), o.optBoolean("hasPrecos")).copy(atualizadoEm = o.optLong("em"), offline = true)
    }

    // ---------------- configuração ----------------

    suspend fun tabsWithHeaders(): Pair<String, Map<String, List<String>>> {
        val meta = sheets.meta(sheetId)
        val tabs = meta.tabs.filter { it !in setOf(Config.TAB, Config.CAPAS, Config.PRECOS) }
        val heads = sheets.batchGet(sheetId, tabs.map { "${q(it)}!A1:Z1" })
        return meta.title to tabs.mapIndexed { i, t -> t to (heads.getOrNull(i)?.firstOrNull() ?: emptyList()) }.toMap()
    }

    private suspend fun ensureTab(title: String, header: List<String>?, hidden: Boolean = false) {
        val meta = sheets.meta(sheetId)
        if (title in meta.tabs) return
        sheets.addTab(sheetId, title, hidden)
        header?.let { sheets.put(sheetId, "${q(title)}!A1", listOf(it)) }
    }

    suspend fun saveConfig(cfg: Config) {
        ensureTab(Config.TAB, null, hidden = true)
        sheets.put(sheetId, "${q(Config.TAB)}!A1:B1", listOf(listOf(cfg.toString(), "Configuração do app Game Catalog. Não edite esta aba à mão.")))
        ensureTab(cfg.compras, Config.BUY_HEAD)
        _state.value = _state.value.copy(config = cfg)
    }

    /** Cria uma planilha nova já no formato do app. */
    suspend fun createSheet(): String {
        val id = sheets.create("Coleção de games", listOf("COLECAO", "PROCURADOS", "COMPRAS"))
        sheetId = id
        sheets.put(id, "COLECAO!A1:H1", listOf(listOf("CONSOLE", "NOME", "REGIAO", "CIB", "CAIXA", "MANUAL", "CONDICAO", "OBS")))
        sheets.put(id, "PROCURADOS!A1:D1", listOf(listOf("CONSOLE", "NOME", "STATUS", "OBS")))
        sheets.put(id, "COMPRAS!A1:H1", listOf(Config.BUY_HEAD))
        saveConfig(Config.build(Config.Colecao("COLECAO", 0, 1, 2, 7), listOf(Config.Procurado("PROCURADOS", 1, 2, 0, "", 3)), "COMPRAS", null))
        return id
    }

    // ---------------- gravações ----------------

    private val cfg get() = _state.value.config ?: throw IllegalStateException(tr("Planilha sem configuração"))

    sealed class EditResult { object Ok : EditResult(); object Moved : EditResult(); data class Partial(val wrong: List<String>) : EditResult() }

    /** Edita células de uma linha da coleção: confere se a linha continua igual e grava só o que mudou. */
    suspend fun editRow(item: Item, novos: List<String>): EditResult {
        val n = item.raw.size; val rng = "${q(item.aba)}!A${item.linha}:${col(n - 1)}${item.linha}"
        val cur = pad(sheets.get(sheetId, rng).firstOrNull(), n)
        if (cur != item.raw) return EditResult.Moved
        val diffs = novos.indices.filter { novos[it] != item.raw[it] }
        for (k in diffs) sheets.put(sheetId, "${q(item.aba)}!${col(k)}${item.linha}", listOf(listOf(novos[k])))
        val after = pad(sheets.get(sheetId, rng).firstOrNull(), n)
        val wrong = diffs.filter { after[it] != novos[it] }.map { col(it) + item.linha }
        // capa e preço acompanham o jogo quando o nome ou o console mudam
        val c = cfg.colecao
        if (c.console in diffs || c.nome in diffs) runCatching {
            val oldK = item.chave; val newK = Text.norm(novos[c.console]) + "|" + Text.norm(novos[c.nome])
            for (tipo in listOf("capa", "midia")) _state.value.capasRows["$oldK|$tipo"]?.let { sheets.put(sheetId, "${q(Config.CAPAS)}!A$it", listOf(listOf(newK))) }
            _state.value.precosRows[oldK]?.let { sheets.put(sheetId, "${q(Config.PRECOS)}!A$it", listOf(listOf(newK))) }
        }
        return if (wrong.isEmpty()) EditResult.Ok else EditResult.Partial(wrong)
    }

    sealed class DeleteResult { object Ok : DeleteResult(); object Moved : DeleteResult() }

    /** Exclui um jogo da coleção: só apaga se a linha da planilha ainda for exatamente a que você viu. */
    suspend fun deleteFromCollection(item: Item): DeleteResult {
        val n = item.raw.size; val rng = "${q(item.aba)}!A${item.linha}:${col(n - 1)}${item.linha}"
        val cur = pad(sheets.get(sheetId, rng).firstOrNull(), n)
        if (cur != item.raw) return DeleteResult.Moved
        sheets.deleteRow(sheetId, sheets.tabId(sheetId, item.aba), item.linha)
        return DeleteResult.Ok
    }

    /**
     * Acrescenta linhas a uma aba. Se a aba tem uma tabela do Google Sheets que começa na coluna A
     * (como a tabela GAMES), as linhas entram dentro da tabela; senão, vão para o fim da aba como antes.
     */
    private suspend fun appendRows(tab: String, width: Int, rows: List<List<String>>) {
        val t = runCatching { sheets.tables(sheetId) }.getOrDefault(emptyList()).firstOrNull { it.tab == tab && it.startCol == 0 && it.startRow == 0 }
        if (t != null) {
            val w = t.endCol - t.startCol
            val extra = rows.any { r -> r.drop(w).any { it.isNotBlank() } }
            if (!extra) { sheets.appendToTable(sheetId, t, rows.map { r -> List(w) { k -> r.getOrElse(k) { "" } } }); return }
        }
        sheets.append(sheetId, "${q(tab)}!A1:${col(width - 1)}1", rows)
    }

    /** Linha nova no fim da coleção; [valores] tem uma posição por coluna da aba. */
    suspend fun addToCollection(valores: List<String>) {
        val c = cfg.colecao
        appendRows(c.aba, c.ultima + 1, listOf(pad(valores, c.ultima + 1)))
    }

    fun emptyCollectionRow(console: String, nome: String, regiao: String): MutableList<String> {
        val c = cfg.colecao
        return MutableList(c.ultima + 1) { "" }.also { it[c.console] = console; it[c.nome] = nome; if (c.regiao >= 0) it[c.regiao] = regiao }
    }

    /** Inclui na primeira aba de procurados (ou na indicada). */
    suspend fun addWanted(console: String, nome: String, wi: Int = 0, info: String = "") {
        val w = cfg.procurados.getOrNull(wi) ?: throw IllegalStateException(tr("Nenhuma aba de procurados configurada"))
        val n = maxOf(w.nome, w.status, w.consoleCol, w.info) + 1
        val row = MutableList(n) { "" }.also { it[w.nome] = nome; if (w.consoleCol >= 0) it[w.consoleCol] = console; if (w.info >= 0) it[w.info] = info }
        appendRows(w.aba, n, listOf(row))
    }

    /** Comprei: linha nova na lista de compras e, se veio dos procurados, marca COMPRADO lá. Devolve um aviso, se houver. */
    suspend fun buy(console: String, nome: String, regiao: String, obs: String, want: Item?): String? {
        ensureTab(cfg.compras, Config.BUY_HEAD)
        appendRows(cfg.compras, 8, listOf(listOf(today(), console, nome, regiao, want?.aba ?: "", "COMPRADO", "", obs)))
        if (want != null) {
            val w = cfg.procurados.getOrNull(want.wi) ?: return null
            if (w.status < 0) return null
            val cur = sheets.get(sheetId, "${q(w.aba)}!${col(w.nome)}${want.linha}").firstOrNull()?.firstOrNull()
            if (Text.norm(cur) != Text.norm(want.nome)) return tr("A aba {0} mudou desde a leitura, então não marquei COMPRADO lá.", w.aba)
            sheets.put(sheetId, "${q(w.aba)}!${col(w.status)}${want.linha}", listOf(listOf("COMPRADO")))
        }
        return null
    }

    sealed class ReceiveResult { object Ok : ReceiveResult(); object Moved : ReceiveResult(); object Done : ReceiveResult() }

    /** Recebi: linha nova no fim da coleção e RECEBIDO na lista de compras. */
    suspend fun receive(buy: Item, valores: List<String>): ReceiveResult {
        val cur = sheets.get(sheetId, "${q(cfg.compras)}!A${buy.linha}:H${buy.linha}").firstOrNull()
        if (cur == null || Text.norm(cur.getOrNull(2)) != Text.norm(buy.nome)) return ReceiveResult.Moved
        if (Text.norm(cur.getOrNull(5)) == "RECEBIDO") return ReceiveResult.Done
        addToCollection(valores)
        sheets.put(sheetId, "${q(cfg.compras)}!F${buy.linha}:G${buy.linha}", listOf(listOf("RECEBIDO", today())))
        return ReceiveResult.Ok
    }

    /** Capa escolhida (aba CAPAS, compartilhada com o app web). Valor vazio volta à capa automática. */
    suspend fun setCapa(chave: String, tipo: String, valor: String, ref: String) {
        ensureTab(Config.CAPAS, listOf("CHAVE", "TIPO", "ORIGEM", "VALOR", "REF", "DATA"))
        val row = _state.value.capasRows["$chave|$tipo"]
        val vals = listOf(listOf(chave, tipo, if (valor.isBlank()) "" else "url", valor, ref, if (valor.isBlank()) "" else today()))
        val linha = if (row != null) { sheets.put(sheetId, "${q(Config.CAPAS)}!A$row:F$row", vals); row } else sheets.append(sheetId, "${q(Config.CAPAS)}!A1:F1", vals)
        val s = _state.value
        val capas = s.capas.toMutableMap().also { m -> val mm = (m[chave] ?: emptyMap()).toMutableMap(); if (valor.isBlank()) mm.remove(tipo) else mm[tipo] = Capa("url", valor, ref); m[chave] = mm }
        _state.value = s.copy(capas = capas, capasRows = if (linha != null) s.capasRows + ("$chave|$tipo" to linha) else s.capasRows)
    }

    /** Último preço consultado (aba PRECOS, compartilhada). */
    suspend fun savePrice(chave: String, json: String) {
        ensureTab(Config.PRECOS, listOf("CHAVE", "CONSULTA", "DADOS"))
        val row = _state.value.precosRows[chave]
        val vals = listOf(listOf(chave, java.time.Instant.now().toString(), json.take(45000)))
        val linha = if (row != null) { sheets.put(sheetId, "${q(Config.PRECOS)}!A$row:C$row", vals); row } else sheets.append(sheetId, "${q(Config.PRECOS)}!A1:C1", vals)
        val s = _state.value
        _state.value = s.copy(precos = s.precos + (chave to json), precosRows = if (linha != null) s.precosRows + (chave to linha) else s.precosRows)
    }

    suspend fun setPlataforma(code: String, repo: String) { saveConfig(cfg.withPlataforma(Text.norm(code), repo)) }
    fun platformOf(console: String): String = _state.value.config?.plataformas?.get(Text.norm(console)) ?: Platforms.repoOf(console)

    data class Found(val item: Item, val score: Double)

    /** Procura o jogo nas três listas, aceitando erros de digitação e consoles da mesma família (SNES = SFC). */
    fun find(nome: String, console: String?, outros: List<String> = emptyList()): List<Found> {
        val names = (listOf(nome) + outros).filter { it.isNotBlank() }
        return _state.value.items.mapNotNull { i ->
            if (!console.isNullOrBlank() && i.console.isNotBlank() && platformOf(console).ifEmpty { Text.norm(console) } != platformOf(i.console).ifEmpty { Text.norm(i.console) }) return@mapNotNull null
            val s = names.maxOf { n -> if (Text.score(n, i.nome) >= 0.82 && Text.score(i.nome, n) >= 0.6) Text.score(n, i.nome) else 0.0 }
            if (s > 0) Found(i, s) else null
        }.sortedByDescending { it.score }
    }

    companion object {
        fun today(): String = SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR")).format(Date())
        fun friendly(e: Throwable): String = when {
            e is io.github.vprodc83.gamecatalog.google.GoogleAuth.NeedsLogin -> tr("Entre com a sua conta Google.")
            e is GoogleError && e.status == 401 -> tr("A sessão do Google expirou. Toque em Atualizar.")
            e is GoogleError && e.status == 403 && (e.message ?: "").contains("scope", true) ->
                tr("A permissão para as planilhas não foi concedida. Em Ajustes, toque em Sair da conta e entre de novo, marcando a caixa das planilhas na tela do Google.")
            e is GoogleError && e.status == 403 && (e.message ?: "").let { it.contains("has not been used", true) || it.contains("disabled", true) } ->
                tr("A API do Google Sheets está desativada no projeto do app no Google Cloud. Avise quem publicou o app.")
            e is GoogleError && e.status == 403 -> tr("O Google negou o acesso a esta planilha para a conta {0}. Ela precisa estar compartilhada com exatamente esse e-mail como Editor (no Google Sheets: Compartilhar). Se o celular usa outra conta Google, entre com a conta que recebeu o compartilhamento.",
                io.github.vprodc83.gamecatalog.google.GoogleAuth.lastEmail ?: tr("(desconhecida)"))
            e is GoogleError && e.status == 404 -> tr("Planilha ou aba não encontrada.")
            e is GoogleError -> tr("Erro do Google: {0}", e.message)
            e is java.net.UnknownHostException || e is java.net.SocketTimeoutException || e is java.net.ConnectException -> tr("Sem internet no momento.")
            else -> e.message ?: tr("Algo deu errado.")
        }
    }
}

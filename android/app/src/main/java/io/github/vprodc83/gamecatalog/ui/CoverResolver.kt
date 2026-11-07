package io.github.vprodc83.gamecatalog.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.vprodc83.gamecatalog.BuildConfig
import io.github.vprodc83.gamecatalog.core.Catalog
import io.github.vprodc83.gamecatalog.core.CoverSources
import io.github.vprodc83.gamecatalog.core.CoverSources.Hit
import io.github.vprodc83.gamecatalog.core.Text
import io.github.vprodc83.gamecatalog.data.Item
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Capas automáticas (melhorias 17 e 18): procura em várias fontes, uma depois da outra, e guarda o
 * resultado no celular (achou ou não), para não repetir a busca. Uma busca sem resultado é refeita
 * depois de 7 dias ou quando você pede "Atualizar capas".
 */
class CoverResolver(private val m: AppModel) {
    private val file = File(m.ctx.filesDir, "capas-auto.json")
    private val cache: JSONObject = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
    private val datesFile = File(m.ctx.filesDir, "lancamentos.json")
    private val dates: JSONObject = runCatching { JSONObject(datesFile.readText()) }.getOrDefault(JSONObject())
    /** Muda quando chega uma capa nova (as telas se redesenham). */
    var version by mutableStateOf(0); private set
    private val queue = Channel<Item>(Channel.UNLIMITED)
    private val queued = HashSet<String>()
    var vgdb: CoverSources.Vgdb? = null
    private val tgdbKey: String = runCatching { BuildConfig.TGDB_KEY }.getOrDefault("")

    init {
        repeat(2) { m.scope.launch(Dispatchers.IO) { for (it in queue) runCatching { resolve(it) }; } }
    }

    private fun k(item: Item, tipo: String) = item.chave + "|" + tipo
    private fun save() = runCatching { synchronized(cache) { file.writeText(cache.toString()) } }

    /** Capa já encontrada (url) ou null; [tried] diz se já se procurou recentemente. */
    fun cached(item: Item, tipo: String): String? = synchronized(cache) { cache.optJSONObject(k(item, tipo))?.optString("u")?.ifBlank { null } }
    private fun tried(item: Item, tipo: String): Boolean = synchronized(cache) {
        val o = cache.optJSONObject(k(item, tipo)) ?: return false
        o.optString("u").isNotBlank() || System.currentTimeMillis() - o.optLong("t") < 7L * 24 * 3600 * 1000
    }
    fun source(item: Item, tipo: String = "capa"): String? = synchronized(cache) { cache.optJSONObject(k(item, tipo))?.optString("f")?.ifBlank { null } }

    private fun put(item: Item, tipo: String, hit: Hit?) {
        synchronized(cache) { cache.put(k(item, tipo), JSONObject().put("u", hit?.url ?: "").put("f", hit?.fonte ?: "").put("t", System.currentTimeMillis())) }
        save()
    }

    fun clear() { synchronized(cache) { cache.keys().asSequence().toList().forEach { cache.remove(it) } }; save(); version++ }
    fun forget(item: Item) { synchronized(cache) { cache.remove(k(item, "capa")); cache.remove(k(item, "midia")) }; save() }

    /** Pede a busca em segundo plano (se ainda não foi feita). */
    fun request(item: Item) {
        if (tried(item, "capa")) return
        synchronized(queued) { if (!queued.add(item.chave)) return }
        queue.trySend(item)
    }

    /** Capa do libretro (catálogo embutido), tentando as versões limpas do nome. */
    fun libretro(item: Item, limit: Int = 1): List<Hit> {
        val c: Catalog = m.catalog ?: return emptyList()
        val repoId = m.repo.platformOf(item.console).ifEmpty { return emptyList() }
        val out = ArrayList<Hit>()
        for ((i, n) in Text.nameVariants(item.nome).withIndex()) {
            c.search(n, listOf(repoId), limit = limit, minScore = if (i == 0) 0.8 else 0.78, regiao = item.regiao).forEach { r ->
                c.coverUrl(r.repo, r.nomeAcervo)?.let { u -> if (out.none { it.url == u }) out.add(Hit(u, "libretro", r.tituloLimpo)) }
            }
            if (out.size >= limit) break
        }
        return out.take(limit)
    }

    /** Fontes na ordem da melhoria 18 (libretro já é tentado sem internet, antes). */
    private fun online(item: Item, all: Boolean): List<Hit> {
        val repo = m.repo.platformOf(item.console)
        val out = ArrayList<Hit>()
        val steps: List<() -> List<Hit>> = listOf(
            { CoverSources.wikipedia(item.nome, item.console, repo, if (all) 2 else 1) },
            { CoverSources.priceCharting(Text.nameVariants(item.nome).getOrElse(1) { item.nome }, item.console, repo) },
            { vgdb?.covers(item.nome, item.console, repo, item.regiao) ?: emptyList() },
            { CoverSources.tectoy(Text.nameVariants(item.nome).getOrElse(1) { item.nome }, repo) },
            { CoverSources.tgdb(tgdbKey, item.nome, item.console, repo) }
        )
        for (s in steps) {
            val r = runCatching { s() }.getOrDefault(emptyList())
            out.addAll(r)
            if (!all && r.any { it.tipo == "capa" }) break
        }
        return out
    }

    /** Busca em segundo plano: libretro → Wikipédia → PriceCharting → VGDB → Tec Toy → TheGamesDB. */
    private suspend fun resolve(item: Item) {
        try {
            val lib = libretro(item).firstOrNull()
            val hits = if (lib != null) listOf(lib) + (vgdb?.let { v -> runCatching { v.covers(item.nome, item.console, m.repo.platformOf(item.console), item.regiao) }.getOrDefault(emptyList()) }?.filter { it.tipo == "midia" } ?: emptyList())
                       else online(item, false)
            put(item, "capa", hits.firstOrNull { it.tipo == "capa" })
            hits.firstOrNull { it.tipo == "midia" }?.let { put(item, "midia", it) }
            hits.firstNotNullOfOrNull { it.lancamento }?.let { setDate(item, it) }
            withContext(Dispatchers.Main) { version++ }
        } finally { synchronized(queued) { queued.remove(item.chave) } }
    }

    /** Todas as sugestões, de todas as fontes (tela "Jogos sem capa" e "Trocar capa"). */
    suspend fun suggestions(item: Item): List<Hit> = withContext(Dispatchers.IO) { libretro(item, 4) + online(item, true) }

    /** A melhor capa, procurando já (usada em "aplicar a melhor em todos"). */
    suspend fun best(item: Item): Hit? = withContext(Dispatchers.IO) {
        (libretro(item).firstOrNull() ?: online(item, false).firstOrNull { it.tipo == "capa" }).also { put(item, "capa", it); withContext(Dispatchers.Main) { version++ } }
    }

    // ---------------- datas de lançamento (ordenação da melhoria 12) ----------------

    fun date(item: Item): String? = synchronized(dates) { dates.optString(item.chave).ifBlank { null } }
    fun dateTried(item: Item): Boolean = synchronized(dates) { dates.has(item.chave) }
    private fun setDate(item: Item, d: String?) { synchronized(dates) { dates.put(item.chave, d ?: "") }; runCatching { datesFile.writeText(synchronized(dates) { dates.toString() }) } }

    var datesRunning by mutableStateOf(false); private set
    var datesDone by mutableStateOf(0); private set
    var datesTotal by mutableStateOf(0); private set

    /** Procura as datas que faltam (Wikidata), uma por vez, em segundo plano. */
    fun fetchDates(items: List<Item>) {
        if (datesRunning) return
        val todo = items.filter { !dateTried(it) }.distinctBy { it.chave }
        if (todo.isEmpty()) return
        datesRunning = true; datesDone = 0; datesTotal = todo.size
        m.scope.launch {
            for (it in todo) {
                val d = withContext(Dispatchers.IO) { runCatching { CoverSources.releaseOf(it.nome, it.console, m.repo.platformOf(it.console)) }.getOrNull() }
                setDate(it, d); datesDone++
                if (datesDone % 10 == 0) version++
            }
            datesRunning = false; version++
        }
    }
}

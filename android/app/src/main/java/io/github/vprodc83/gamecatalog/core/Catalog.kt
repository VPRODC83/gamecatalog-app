package io.github.vprodc83.gamecatalog.core

import org.json.JSONObject
import java.io.InputStream
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

/**
 * Catálogo de nomes de jogos do libretro-thumbnails (cerca de 100 mil títulos), embutido no app.
 * Serve para reconhecer o jogo a partir do texto lido na etiqueta e para montar o endereço da capa.
 */
class Catalog(val repos: List<Repo>) {
    class Repo(val repo: String, val nome: String, val sha: String, val names: List<String>) {
        val base: List<String> = names.map { Text.words(it) }
        // índice de palavras → posições, para não comparar com todos os nomes
        val index: Map<String, IntArray> by lazy {
            val m = HashMap<String, MutableList<Int>>()
            base.forEachIndexed { i, b -> b.split(' ').filter { it.length >= 2 }.toSet().forEach { w -> m.getOrPut(w) { mutableListOf() }.add(i) } }
            m.mapValues { it.value.toIntArray() }
        }
        private val keys: Array<String> by lazy { index.keys.sorted().toTypedArray() }
        /** Palavras do índice que começam com [p] (busca binária na lista ordenada). */
        fun withPrefix(p: String, max: Int = 40): List<String> {
            var lo = java.util.Arrays.binarySearch(keys, p).let { if (it < 0) -it - 1 else it }
            val out = ArrayList<String>()
            while (lo < keys.size && keys[lo].startsWith(p) && out.size < max) { if (keys[lo] != p) out.add(keys[lo]); lo++ }
            return out
        }
    }

    data class Match(val titulo: String, val nomeAcervo: String, val repo: String, val plataforma: String, val score: Double) {
        /** Título limpo, sem as marcações de região/revisão do libretro: "Donkey Kong Country (USA)" → "Donkey Kong Country". */
        val tituloLimpo: String get() = cleanTitle(nomeAcervo)
    }

    private val byRepo = repos.associateBy { it.repo }
    fun repo(id: String): Repo? = byRepo[id]
    fun label(repo: String): String = byRepo[repo]?.nome ?: repo.replace("_-_", " · ").replace('_', ' ')

    fun coverUrl(repo: String, name: String): String? {
        val r = byRepo[repo] ?: return null
        val path = URLEncoder.encode("$name.png", "UTF-8").replace("+", "%20")
        return "https://raw.githubusercontent.com/libretro-thumbnails/$repo/${r.sha}/Named_Boxarts/$path"
    }

    /**
     * Busca títulos parecidos com [q]. [repoIds] limita os acervos (vazio = todos).
     * [regiao] orienta a escolha entre edições iguais (BR → Brazil/USA…).
     */
    fun search(q: String, repoIds: Collection<String> = emptyList(), limit: Int = 20, minScore: Double = 0.55, regiao: String? = null): List<Match> {
        val qa = Text.words(q)
        if (qa.length < 2) return emptyList()
        val tokens = qa.split(' ').filter { it.length >= 2 }
        val pref = regionPrefs(regiao)
        val targets = if (repoIds.isEmpty()) repos else repoIds.mapNotNull { byRepo[it] }
        val out = ArrayList<Match>()
        for (r in targets) {
            val cand = HashSet<Int>()
            for (t in tokens) {
                r.index[t]?.forEach { cand.add(it) }
                if (t.length >= 3 && cand.size < 4000) r.withPrefix(t).forEach { k -> r.index[k]?.forEach { cand.add(it) } }
            }
            for (i in cand) {
                val s = Text.score(qa, r.base[i])
                if (s >= minScore) out.add(Match(cleanTitle(r.names[i]), r.names[i], r.repo, r.nome, s))
            }
        }
        // um resultado por título/plataforma: a edição da região preferida, sem beta/protótipo
        return out.groupBy { it.repo + "|" + Text.norm(it.titulo) }
            .map { (_, g) -> g.sortedWith(compareBy<Match>({ bad.containsMatchIn(it.nomeAcervo) }, { regionRank(it.nomeAcervo, pref) }, { it.nomeAcervo.length })).first().copy(score = g.maxOf { it.score }) }
            .sortedWith(compareByDescending<Match> { Math.round(it.score * 50) }.thenBy { it.titulo.length })
            .take(limit)
    }

    /** Melhor capa automática para um jogo da coleção (null se nada parecido o bastante). */
    fun bestCover(console: String, nome: String, regiao: String?): Pair<String, String>? {
        val repo = Platforms.repoOf(console).ifEmpty { return null }
        val m = search(nome, listOf(repo), limit = 1, minScore = 0.8, regiao = regiao).firstOrNull() ?: return null
        val url = coverUrl(m.repo, m.nomeAcervo) ?: return null
        return url to m.nomeAcervo
    }

    companion object {
        private val bad = "\\((Beta|Proto|Demo|Sample|Pirate|Unl|Hack|Aftermarket|Kiosk)".toRegex(RegexOption.IGNORE_CASE)
        /** Versões que não interessam (protótipos, demos, piratas…). */
        fun bad(name: String): Boolean = bad.containsMatchIn(name)
        fun cleanTitle(n: String): String = n.replace("\\s*\\(.*$".toRegex(), "").replace(" _ ", " / ").trim()
        fun regionPrefs(r: String?): List<String> {
            val n = Text.norm(r)
            return when {
                n.matches("^(BR|BRA|BRASIL|BRAZIL|NTSCBR|PALM).*".toRegex()) -> listOf("Brazil", "USA", "World", "Europe", "Japan")
                n.matches("^(JP|JAP|JPN|JAPAO|JAPAN|NTSCJ).*".toRegex()) -> listOf("Japan", "Asia", "World", "USA")
                n.matches("^(EU|EUR|PAL|EUROPA|UK).*".toRegex()) -> listOf("Europe", "World", "USA")
                else -> listOf("USA", "World", "Europe", "Japan")
            }
        }
        fun regionRank(name: String, pref: List<String>): Int {
            val r0 = "\\(([^)]*)\\)".toRegex().find(name)?.groupValues?.get(1) ?: ""
            val i = pref.indexOfFirst { p -> r0 == p || r0.split(", ").contains(p) }
            return if (i < 0) 9 else i
        }

        /** Lê o catálogo gerado por tools/build_catalog.py (JSON compactado com gzip). */
        fun load(input: InputStream): Catalog {
            // o Android tira o .gz dos assets ao montar o APK; aceita o arquivo compactado ou não
            val b = java.io.BufferedInputStream(input); b.mark(2)
            val gz = b.read() == 0x1f && b.read() == 0x8b; b.reset()
            val txt = (if (gz) GZIPInputStream(b) else b).bufferedReader(Charsets.UTF_8).readText()
            val o = JSONObject(txt); val arr = o.getJSONArray("repos")
            val list = (0 until arr.length()).map { i ->
                val r = arr.getJSONObject(i); val ns = r.getJSONArray("names")
                Repo(r.getString("repo"), r.getString("nome"), r.getString("sha"), (0 until ns.length()).map { ns.getString(it) })
            }
            return Catalog(list)
        }
    }
}

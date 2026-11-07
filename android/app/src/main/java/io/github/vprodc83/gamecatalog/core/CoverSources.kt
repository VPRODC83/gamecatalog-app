package io.github.vprodc83.gamecatalog.core

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Fontes de capas além do libretro, consultadas uma depois da outra (melhoria 18), todas gratuitas:
 * Wikipédia/Wikidata, PriceCharting, VGDB (edições brasileiras e fotos de mídia), Tec Toy (Sega no Brasil)
 * e TheGamesDB (chave pública do app). Cada função devolve sugestões; nenhuma lança erro (sem rede = lista vazia).
 */
object CoverSources {
    data class Hit(val url: String, val fonte: String, val titulo: String, val tipo: String = "capa", val lancamento: String? = null)

    private val http = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).followRedirects(true).build()
    private const val UA = "GameCatalog/1.0 (Android; +https://github.com/VPRODC83/gamecatalog-app)"
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun get(url: String, ua: String = UA): String? = runCatching {
        http.newCall(Request.Builder().url(url).header("User-Agent", ua).build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    }.getOrNull()

    /** Nome do console para ajudar a busca textual (Wikipédia). */
    fun consoleWords(console: String, repo: String): String = when {
        repo.isNotEmpty() -> repo.substringAfterLast("_-_").replace('_', ' ').replace(" - ", " ")
        else -> mapOf("XONE" to "Xbox One", "XSX" to "Xbox Series X", "PS5" to "PlayStation 5", "NSW" to "Nintendo Switch", "NSW2" to "Nintendo Switch 2",
            "PSVITA" to "PlayStation Vita")[Text.norm(console)] ?: console
    }

    // ---------------- Wikipédia + Wikidata ----------------

    /**
     * Procura o artigo do jogo (inglês, depois português) e devolve a imagem principal (normalmente a capa)
     * e a data de lançamento do Wikidata (P577), quando houver.
     */
    fun wikipedia(nome: String, console: String, repo: String, limit: Int = 2): List<Hit> {
        val out = ArrayList<Hit>()
        for (lang in listOf("en", "pt")) {
            val q = "$nome ${consoleWords(console, repo)} video game"
            val js = get("https://$lang.wikipedia.org/w/api.php?action=query&format=json&generator=search&gsrlimit=5&gsrsearch=${enc(q)}" +
                "&prop=pageimages|pageprops&piprop=original|thumbnail&pithumbsize=600&ppprop=wikibase_item") ?: continue
            val pages = runCatching { JSONObject(js).getJSONObject("query").getJSONObject("pages") }.getOrNull() ?: continue
            val list = pages.keys().asSequence().map { pages.getJSONObject(it) }.sortedBy { it.optInt("index", 99) }.toList()
            for (p in list) {
                val title = p.optString("title").replace(" \\((video ?game|jogo eletrônico|jogo).*\\)".toRegex(RegexOption.IGNORE_CASE), "")
                if (Text.score(nome, title) < 0.72 && Text.score(title, nome) < 0.72) continue
                val img = p.optJSONObject("thumbnail")?.optString("source")?.ifBlank { null } ?: p.optJSONObject("original")?.optString("source")?.ifBlank { null } ?: continue
                if (img.endsWith(".svg", true) || img.contains("logo", true)) continue
                val qid = p.optJSONObject("pageprops")?.optString("wikibase_item")
                out.add(Hit(img, "Wikipédia ($lang)", title, lancamento = qid?.let { releaseDate(it) }))
                if (out.size >= limit) return out
            }
            if (out.isNotEmpty()) return out
        }
        return out
    }

    /** Data de lançamento mais antiga do Wikidata (P577), como "AAAA-MM-DD" (ou "AAAA"). */
    fun releaseDate(qid: String): String? {
        val js = get("https://www.wikidata.org/w/api.php?action=wbgetclaims&format=json&property=P577&entity=${enc(qid)}") ?: return null
        val arr = runCatching { JSONObject(js).getJSONObject("claims").getJSONArray("P577") }.getOrNull() ?: return null
        return (0 until arr.length()).mapNotNull { i ->
            val v = arr.getJSONObject(i).optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optJSONObject("value") ?: return@mapNotNull null
            val t = v.optString("time").trimStart('+'); val prec = v.optInt("precision", 11)
            when { prec >= 11 -> t.take(10); prec == 10 -> t.take(7); else -> t.take(4) }.replace("-00", "")
        }.minOrNull()
    }

    /** Só a data de lançamento (para ordenar a lista), procurando o artigo do jogo. */
    fun releaseOf(nome: String, console: String, repo: String): String? {
        for (n in Text.nameVariants(nome).drop(1).ifEmpty { listOf(nome) }) {
            val js = get("https://en.wikipedia.org/w/api.php?action=query&format=json&generator=search&gsrlimit=3&gsrsearch=${enc("$n ${consoleWords(console, repo)} video game")}&prop=pageprops&ppprop=wikibase_item") ?: return null
            val pages = runCatching { JSONObject(js).getJSONObject("query").getJSONObject("pages") }.getOrNull() ?: continue
            val p = pages.keys().asSequence().map { pages.getJSONObject(it) }.sortedBy { it.optInt("index", 99) }
                .firstOrNull { val t = it.optString("title").replace(" \\(.*\\)".toRegex(), ""); Text.score(n, t) >= 0.75 || Text.score(t, n) >= 0.75 } ?: continue
            return p.optJSONObject("pageprops")?.optString("wikibase_item")?.ifBlank { null }?.let { releaseDate(it) }
        }
        return null
    }

    // ---------------- PriceCharting ----------------

    fun priceCharting(nome: String, console: String, repo: String): List<Hit> = runCatching {
        val r = if (repo.isNotEmpty()) repo else "@" + Text.norm(console)
        val (u, html, prod) = Prices.productPage(nome, r) ?: return emptyList()
        val img = Prices.parseImage(html) ?: return emptyList()
        listOf(Hit(img, "PriceCharting", prod))
    }.getOrDefault(emptyList())

    // ---------------- VGDB (vgdb.com.br) ----------------

    /** Índice embutido: console do VGDB → nomes (slugs) dos jogos. */
    class Vgdb(private val consoles: Map<String, List<String>>) {
        /** Console do VGDB para o acervo do libretro / código da planilha. */
        private fun slugsFor(console: String, repo: String): List<String> = when (repo) {
            Platforms.ATARI -> listOf("atari-2600"); "Magnavox_-_Odyssey2" -> listOf("odyssey-2", "magnavox-odyssey")
            Platforms.MD -> listOf("mega-drive"); Platforms.MS -> listOf("master-system"); "Sega_-_Game_Gear" -> listOf("game-gear")
            "Sega_-_Mega-CD_-_Sega_CD" -> listOf("sega-cd"); "Sega_-_Saturn" -> listOf("saturn"); "Sega_-_Dreamcast" -> listOf("dreamcast")
            "Nintendo_-_Game_Boy" -> listOf("game-boy"); "Nintendo_-_Game_Boy_Color" -> listOf("game-boy-color"); "Nintendo_-_Game_Boy_Advance" -> listOf("game-boy-advance")
            Platforms.NES -> listOf("nes-nintendo-entertainment-system", "famicom-disk-system"); Platforms.SNES -> listOf("super-nintendo")
            Platforms.N64 -> listOf("nintendo-64"); "Nintendo_-_GameCube" -> listOf("gamecube"); "Nintendo_-_Wii" -> listOf("wii"); "Nintendo_-_Wii_U" -> listOf("wii-u")
            "Nintendo_-_Nintendo_DS" -> listOf("nintendo-ds", "nintendo-dsi"); "Nintendo_-_Nintendo_3DS" -> listOf("nintendo-3ds-2ds", "new-nintendo-3ds")
            "Sony_-_PlayStation" -> listOf("playstation"); "Sony_-_PlayStation_2" -> listOf("playstation-2"); "Sony_-_PlayStation_3" -> listOf("playstation-3-")
            "Sony_-_PlayStation_4" -> listOf("playstation-4"); "Sony_-_PlayStation_Portable" -> listOf("playstation-portable")
            "Microsoft_-_Xbox" -> listOf("xbox"); "Microsoft_-_Xbox_360" -> listOf("xbox-360"); "Microsoft_-_MSX" -> listOf("msx")
            "SNK_-_Neo_Geo" -> listOf("neo-geo-advanced-entertainment-system", "neo-geo-cd"); "NEC_-_PC_Engine_-_TurboGrafx_16" -> listOf("pc-engine-supergrafx")
            else -> when (Text.norm(console)) { "XONE" -> listOf("xbox-one"); "XSX" -> listOf("xbox-series-x-s"); "PS5" -> listOf("playstation-5"); "PSVITA" -> listOf("playstation-vita"); else -> emptyList() }
        }

        /** Página do jogo no VGDB (melhor nome parecido), ou null. */
        fun page(nome: String, console: String, repo: String): String? {
            var best: Pair<String, Double>? = null
            for (slug in slugsFor(console, repo)) for (g in consoles[slug] ?: emptyList()) {
                val t = g.replace('-', ' ')
                for (n in Text.nameVariants(nome)) {
                    val s = maxOf(Text.score(n, t), Text.score(t, n) - 0.05)
                    if (s >= 0.8 && (best == null || s > best.second)) best = "https://www.vgdb.com.br/$slug/jogos/$g/" to s
                }
            }
            return best?.first
        }

        /** Capa e foto da mídia da região preferida, lidas da página do jogo. */
        fun covers(nome: String, console: String, repo: String, regiao: String?): List<Hit> {
            val url = page(nome, console, repo) ?: return emptyList()
            val html = get(url, "Mozilla/5.0 (Linux; Android 14) " + UA) ?: return emptyList()
            val photos = "<a\\b[^>]*href=\"([^\"]+/gf/fotos/games/[^\"]+\\.(?:jpe?g|png|webp))\"[^>]*>".toRegex(RegexOption.IGNORE_CASE).findAll(html).map { m ->
                val t = "title=\"([^\"]*)\"".toRegex().find(m.value)?.groupValues?.get(1)?.let { Text.unescapeHtml(it) } ?: ""
                t.trim() to (if (m.groupValues[1].startsWith("http")) m.groupValues[1] else "https://www.vgdb.com.br" + m.groupValues[1])
            }.distinct().toList()
            val pref = regionOrder(regiao)
            val titulo = url.trimEnd('/').substringAfterLast('/').replace('-', ' ')
            return listOfNotNull(pick(photos, "capa", pref)?.let { Hit(it, "VGDB", titulo, "capa") }, pick(photos, "midia", pref)?.let { Hit(it, "VGDB", titulo, "midia") })
        }

        private val reg = mapOf("BR" to "BR", "BRA" to "BR", "USA" to "USA", "EUA" to "USA", "AMERICANA" to "USA", "US" to "USA",
            "EUR" to "EUR", "EU" to "EUR", "JP" to "JP", "JAP" to "JP", "JPN" to "JP", "AUS" to "AUS", "ASIA" to "ASIA")
        private val kinds = mapOf("capa" to listOf("capa", "caixa", "cover", "frente"), "midia" to listOf("midia", "cartucho", "disco", "cd", "disco 1"))
        private fun regionOrder(r: String?): List<String> = when (Text.norm(r)) {
            "BR", "BRA", "BRASIL" -> listOf("BR", "USA", "EUR", "JP"); "JP", "JAP", "JPN" -> listOf("JP", "ASIA", "USA", "BR")
            "EU", "EUR", "PAL" -> listOf("EUR", "USA", "BR", "JP"); else -> listOf("USA", "BR", "EUR", "JP")
        }
        private fun pick(photos: List<Pair<String, String>>, kind: String, pref: List<String>): String? = photos.mapNotNull { (label, u) ->
            val low = Text.stripAccents(label).lowercase().replace("\\(([^)]*)\\)".toRegex(), " $1 ").replace("\\b(foto|fotos|scan|versao)\\b.*$".toRegex(), "")
            val words = "[a-z]+|\\d+".toRegex().findAll(low).map { it.value }.toList()
            val region = words.firstNotNullOfOrNull { reg[it.uppercase()] }
            val rest = words.filter { reg[it.uppercase()] == null }.joinToString(" ")
            val prio = kinds[kind]!!.indexOf(rest)
            if (prio < 0) null else Triple(pref.indexOf(region).let { if (it < 0) 9 else it }, prio, u)
        }.minWithOrNull(compareBy({ it.first }, { it.second }))?.third

        companion object {
            fun load(input: java.io.InputStream): Vgdb {
                // o Android tira o .gz dos assets ao montar o APK; aceita compactado ou não
                val b = java.io.BufferedInputStream(input); b.mark(2)
                val gz = b.read() == 0x1f && b.read() == 0x8b; b.reset()
                val o = JSONObject((if (gz) GZIPInputStream(b) else b).bufferedReader().readText())
                return Vgdb(o.keys().asSequence().associateWith { k -> o.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) } } })
            }
        }
    }

    // ---------------- Tec Toy (wiki da Sega no Brasil) ----------------

    private val tectoyRepos = setOf(Platforms.MD, Platforms.MS, "Sega_-_Game_Gear", "Sega_-_Mega-CD_-_Sega_CD", "Sega_-_Saturn", "Sega_-_Dreamcast")

    fun tectoy(nome: String, repo: String): List<Hit> {
        if (repo !in tectoyRepos) return emptyList()
        val api = "https://www.sega-brasil.com.br/Tectoy/api.php?format=json&action=query"
        val js = get("$api&list=search&srlimit=3&srsearch=${enc(nome)}") ?: return emptyList()
        val title = runCatching { JSONObject(js).getJSONObject("query").getJSONArray("search") }.getOrNull()?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it).optString("title") }.firstOrNull { Text.score(nome, it.replace(" \\(.*\\)".toRegex(), "")) >= 0.75 }
        } ?: return emptyList()
        val im = get("$api&prop=images&imlimit=max&redirects=1&titles=${enc(title)}") ?: return emptyList()
        val files = runCatching { JSONObject(im).getJSONObject("query").getJSONObject("pages").let { p -> p.getJSONObject(p.keys().next()).getJSONArray("images") } }.getOrNull()
            ?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("title") } } ?: return emptyList()
        val imgs = files.filter { ".(jpe?g|png)$".toRegex(RegexOption.IGNORE_CASE).containsMatchIn(it) && !it.contains("manual", true) }
        val front = imgs.firstOrNull { "(^|[ _])ft([ _.]|$)".toRegex(RegexOption.IGNORE_CASE).containsMatchIn(it.substringAfter(':')) }
            ?: imgs.firstOrNull { it.contains("capa", true) } ?: return emptyList()
        val info = get("$api&prop=imageinfo&iiprop=url&titles=${enc(front)}") ?: return emptyList()
        val u = runCatching { JSONObject(info).getJSONObject("query").getJSONObject("pages").let { p -> p.getJSONObject(p.keys().next()).getJSONArray("imageinfo").getJSONObject(0).getString("url") } }.getOrNull() ?: return emptyList()
        return listOf(Hit(u, "Tec Toy", title))
    }

    // ---------------- TheGamesDB ----------------

    @Volatile private var tgdbPlatforms: Map<Int, String>? = null

    private fun tgdbPlatformIds(key: String, console: String, repo: String): List<Int> {
        val all = tgdbPlatforms ?: run {
            val js = get("https://api.thegamesdb.net/v1/Platforms?apikey=${enc(key)}") ?: return emptyList()
            val o = runCatching { JSONObject(js).getJSONObject("data").getJSONObject("platforms") }.getOrNull() ?: return emptyList()
            o.keys().asSequence().associate { k -> o.getJSONObject(k).let { it.getInt("id") to it.optString("name") } }.also { tgdbPlatforms = it }
        }
        val want = Text.words(consoleWords(console, repo)).replace("sony ", "").replace("nintendo entertainment system", "nes")
        val extra = when (Text.norm(console)) { "SFC" -> "super famicom"; "FAMICOM", "FC" -> "famicom"; "MD" -> "genesis"; else -> null }
        return all.filter { (_, n) -> val w = Text.words(n).replace("sony ", "").replace("sega ", "").replace("nintendo entertainment system nes", "nes")
            Text.score(want.replace("sega ", ""), w) >= 0.85 || (extra != null && w.contains(extra)) }.keys.toList()
    }

    fun tgdb(key: String?, nome: String, console: String, repo: String): List<Hit> {
        if (key.isNullOrBlank()) return emptyList()
        return runCatching {
            val ids = tgdbPlatformIds(key, console, repo)
            val plat = if (ids.isEmpty()) "" else "&filter%5Bplatform%5D=" + ids.joinToString(",")
            val js = get("https://api.thegamesdb.net/v1.1/Games/ByGameName?apikey=${enc(key)}&name=${enc(Text.nameVariants(nome).getOrElse(1) { nome })}$plat&include=boxart") ?: return emptyList()
            val o = JSONObject(js)
            val games = o.getJSONObject("data").getJSONArray("games")
            val box = o.optJSONObject("include")?.optJSONObject("boxart") ?: return emptyList()
            val base = box.getJSONObject("base_url").let { it.optString("medium").ifBlank { it.optString("original") } }
            val data = box.getJSONObject("data")
            (0 until games.length()).map { games.getJSONObject(it) }
                .filter { Text.score(nome, it.optString("game_title")) >= 0.72 || Text.score(it.optString("game_title"), nome) >= 0.72 }
                .mapNotNull { g ->
                    val imgs = data.optJSONArray(g.getInt("id").toString()) ?: return@mapNotNull null
                    val f = (0 until imgs.length()).map { imgs.getJSONObject(it) }.firstOrNull { it.optString("side") == "front" } ?: return@mapNotNull null
                    Hit(base + f.getString("filename"), "TheGamesDB", g.optString("game_title"), lancamento = g.optString("release_date").ifBlank { null })
                }.take(3)
        }.getOrDefault(emptyList())
    }
}

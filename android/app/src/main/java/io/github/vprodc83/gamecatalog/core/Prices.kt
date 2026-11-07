package io.github.vprodc83.gamecatalog.core

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Preços sem chave de nenhum serviço:
 * - links prontos para as buscas nas lojas brasileiras (abrem no navegador);
 * - valores do PriceCharting (solto, completo e lacrado, em dólar), lidos da página pública do jogo;
 * - cotação do dólar (AwesomeAPI).
 * Mercado Livre e outras lojas não permitem leitura automática, por isso ficam só como links.
 */
object Prices {
    data class Link(val loja: String, val url: String)
    data class World(val loose: Double?, val cib: Double?, val novo: Double?, val url: String, val produto: String)

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).followRedirects(true).build()
    private const val UA = "Mozilla/5.0 (Linux; Android 14) GameCatalog/1.0 (+https://github.com/VPRODC83/gamecatalog-app)"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun links(titulo: String, plataforma: String): List<Link> {
        val q = "$titulo $plataforma".trim().replace("\\s+".toRegex(), " ")
        val slug = Text.words(q).replace(' ', '-')
        return listOf(
            Link("Mercado Livre", "https://lista.mercadolivre.com.br/$slug"),
            Link("OLX", "https://www.olx.com.br/brasil?q=${enc(q)}"),
            Link("Shopee", "https://shopee.com.br/search?keyword=${enc(q)}"),
            Link("Enjoei", "https://www.enjoei.com.br/s?q=${enc(q)}"),
            Link("PriceCharting", "https://www.pricecharting.com/search-products?type=prices&q=${enc(titulo)}"),
            Link("eBay (vendidos)", "https://www.ebay.com/sch/i.html?_nkw=${enc(q)}&LH_Sold=1&LH_Complete=1")
        )
    }

    internal fun get(url: String): Pair<String, String> {
        http.newCall(Request.Builder().url(url).header("User-Agent", UA).header("Accept-Language", "en-US,en;q=0.8").build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
            return r.request.url.toString() to (r.body?.string() ?: "")
        }
    }

    /** Lê os três preços da página de produto do PriceCharting. */
    fun parseProduct(html: String): Triple<Double?, Double?, Double?> {
        fun p(id: String): Double? {
            val m = "id=\"$id\"[\\s\\S]{0,400}?\\$\\s*([0-9,]+\\.[0-9]{2})".toRegex().find(html) ?: return null
            return m.groupValues[1].replace(",", "").toDoubleOrNull()
        }
        return Triple(p("used_price"), p("complete_price"), p("new_price"))
    }

    /** Endereços de produtos na página de busca do PriceCharting, com o nome do console. */
    fun parseSearch(html: String): List<Pair<String, String>> =
        "href=\"(https://www\\.pricecharting\\.com/game/([^/\"]+)/[^\"#?]+)\"".toRegex().findAll(html).map { it.groupValues[1] to it.groupValues[2] }.distinct().toList()

    /** Valores mundiais do jogo. [plataforma] ajuda a escolher a versão certa (ex.: super-nintendo vs super-famicom). */
    fun world(titulo: String, consoleRepo: String): World? {
        val (u, html, nome) = productPage(titulo, consoleRepo) ?: return null
        val (l, c, n) = parseProduct(html)
        return World(l, c, n, u, nome)
    }

    /** Página de produto do PriceCharting para o jogo: (endereço, html, nome do produto). */
    fun productPage(titulo: String, consoleRepo: String): Triple<String, String, String>? {
        val (finalUrl, html) = get("https://www.pricecharting.com/search-products?type=prices&q=${enc(titulo)}")
        if (finalUrl.contains("/game/")) return Triple(finalUrl, html, titulo)
        val hint = pcConsole(consoleRepo)
        val cands = parseSearch(html)
        val pick = cands.firstOrNull { (u, c) -> hint.any { c == it } && Text.score(titulo, u.substringAfterLast('/').replace('-', ' ')) >= 0.7 }
            ?: (if (hint.isEmpty()) cands.firstOrNull { (u, _) -> Text.score(titulo, u.substringAfterLast('/').replace('-', ' ')) >= 0.8 } else null) ?: return null
        val (u2, h2) = get(pick.first)
        return Triple(u2, h2, pick.first.substringAfterLast('/').replace('-', ' '))
    }

    /** Foto principal do produto na página do PriceCharting (a maior disponível). */
    fun parseImage(html: String): String? =
        "https://storage\\.googleapis\\.com/images\\.pricecharting\\.com/[A-Za-z0-9]+/1600\\.jpg".toRegex().find(html)?.value
            ?: "https://storage\\.googleapis\\.com/images\\.pricecharting\\.com/[A-Za-z0-9]+/240\\.jpg".toRegex().find(html)?.value

    internal fun pcConsole(repo: String): List<String> = when (repo) {
        Platforms.SNES -> listOf("super-nintendo", "super-famicom", "pal-super-nintendo")
        Platforms.NES -> listOf("nes", "famicom", "pal-nes")
        Platforms.MD -> listOf("sega-genesis", "sega-mega-drive", "pal-sega-mega-drive")
        Platforms.MS -> listOf("sega-master-system", "pal-sega-master-system")
        Platforms.N64 -> listOf("nintendo-64", "jp-nintendo-64", "pal-nintendo-64")
        Platforms.ATARI -> listOf("atari-2600")
        "Sony_-_PlayStation" -> listOf("playstation", "pal-playstation", "jp-playstation")
        "Sony_-_PlayStation_2" -> listOf("playstation-2", "pal-playstation-2")
        "Sony_-_PlayStation_3" -> listOf("playstation-3", "pal-playstation-3")
        "Sony_-_PlayStation_4" -> listOf("playstation-4", "pal-playstation-4")
        "Nintendo_-_Game_Boy" -> listOf("gameboy"); "Nintendo_-_Game_Boy_Color" -> listOf("gameboy-color"); "Nintendo_-_Game_Boy_Advance" -> listOf("gameboy-advance")
        "Nintendo_-_GameCube" -> listOf("gamecube"); "Nintendo_-_Wii" -> listOf("wii"); "Nintendo_-_Wii_U" -> listOf("wii-u"); "Nintendo_-_Nintendo_DS" -> listOf("nintendo-ds")
        "Nintendo_-_Nintendo_3DS" -> listOf("nintendo-3ds"); "Sega_-_Saturn" -> listOf("sega-saturn"); "Sega_-_Dreamcast" -> listOf("sega-dreamcast")
        "Sega_-_Game_Gear" -> listOf("sega-game-gear"); "Sega_-_Mega-CD_-_Sega_CD" -> listOf("sega-cd"); "Microsoft_-_Xbox" -> listOf("xbox"); "Microsoft_-_Xbox_360" -> listOf("xbox-360")
        "Sony_-_PlayStation_Portable" -> listOf("psp"); "SNK_-_Neo_Geo" -> listOf("neo-geo-mvs", "neo-geo-aes"); "Magnavox_-_Odyssey2" -> listOf("magnavox-odyssey-2")
        "Microsoft_-_MSX" -> listOf("msx"); "NEC_-_PC_Engine_-_TurboGrafx_16" -> listOf("turbografx-16", "pc-engine")
        // consoles atuais (sem acervo no libretro): o código da planilha vem em "@CÓDIGO"
        "@XONE" -> listOf("xbox-one", "pal-xbox-one"); "@XSX" -> listOf("xbox-series-x"); "@PS5" -> listOf("playstation-5", "pal-playstation-5")
        "@NSW" -> listOf("nintendo-switch", "pal-nintendo-switch", "jp-nintendo-switch"); "@NSW2" -> listOf("nintendo-switch-2")
        "@PSVITA" -> listOf("playstation-vita")
        else -> emptyList()
    }

    /** Cotação atual do dólar comercial em reais. */
    fun dollar(): Double? = runCatching { JSONObject(get("https://economia.awesomeapi.com.br/json/last/USD-BRL").second).getJSONObject("USDBRL").getString("bid").toDouble() }.getOrNull()
}

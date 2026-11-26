package io.github.vprodc83.gamecatalog.core

/**
 * Regiões (nacionalidade da versão): a mesma região escrita de jeitos diferentes vira um grupo só,
 * para saber se dois exemplares do mesmo jogo são a mesma versão ou versões diferentes.
 */
object Regions {
    private val reParen = "\\(([^)]*)\\)".toRegex()
    // compilados uma vez só (group roda em cada comparação de versões)
    private val reBR = "^(BR|BRA|BRASIL|BRAZIL|NTSCBR|PALM|TECTOY).*".toRegex()
    private val reJP = "^(JP|JAP|JPN|JAPAO|JAPAN|NTSCJ|NTSCJP).*".toRegex()
    private val reUS = "^(US|USA|EUA|NTSCU|NTSCUS|NTSC|NA|AMERICA|AMERICANO|AMERICANA).*".toRegex()
    private val reEU = "^(EU|EUR|EUROPA|EUROPE|PAL|UK|ALEMANHA|GERMANY|FRANCA|FRANCE|ESPANHA|SPAIN|ITALIA|ITALY).*".toRegex()
    private val reKR = "^(KR|KOR|KOREA|COREIA).*".toRegex()
    private val reCN = "^(CN|CHN|CHINA).*".toRegex()
    private val reASIA = "^(ASIA|ASI|HK|HONGKONG|TW|TAIWAN).*".toRegex()
    /** BR, JP, US, EU, ASIA, KR, CN… ou o próprio texto normalizado; vazio quando não foi informada. */
    fun group(r: String?): String {
        val n = Text.norm(r).replace(" ", "")
        return when {
            n.isEmpty() -> ""
            n.matches(reBR) -> "BR"
            n.matches(reJP) -> "JP"
            n.matches(reUS) -> "US"
            n.matches(reEU) -> "EU"
            n.matches(reKR) -> "KR"
            n.matches(reCN) -> "CN"
            n.matches(reASIA) -> "ASIA"
            else -> n
        }
    }

    /** Mesma versão? Uma região não informada não diferencia (pode ser a mesma). */
    fun same(a: String?, b: String?): Boolean { val x = group(a); val y = group(b); return x.isEmpty() || y.isEmpty() || x == y }

    /** Região escrita no nome do catálogo: "Super Mario World (USA)" → US; "(USA, Europe)" → US. */
    fun fromCatalogName(name: String): String {
        val r = reParen.find(name)?.groupValues?.get(1) ?: return ""
        return r.split(",").map { it.trim() }.map { p ->
            when (p) { "Brazil" -> "BR"; "Japan" -> "JP"; "USA" -> "US"; "Korea" -> "KR"; "China" -> "CN"; "Asia", "Hong Kong", "Taiwan" -> "ASIA"
                "Europe", "UK", "Germany", "France", "Spain", "Italy", "Netherlands", "Sweden", "Australia" -> "EU"; else -> "" }
        }.firstOrNull { it.isNotEmpty() } ?: ""
    }

    /** Console do PriceCharting: "pal-…" é Europa, "jp-…" é Japão, sem prefixo é a versão americana. */
    fun fromPcConsole(slug: String): String = when {
        slug.startsWith("pal-") -> "EU"; slug.startsWith("jp-") -> "JP"; slug.isBlank() -> ""; else -> "US"
    }
}

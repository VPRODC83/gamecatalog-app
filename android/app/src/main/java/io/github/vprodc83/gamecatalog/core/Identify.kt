package io.github.vprodc83.gamecatalog.core

/**
 * Reconhece jogos a partir do texto lido na foto (leitura feita no aparelho pelo ML Kit).
 * Cada bloco de texto vira uma busca no catálogo; blocos que só têm marcas/avisos são ignorados.
 */
object Identify {
    /** Um bloco de texto lido na foto, com a posição (0 a 1) para ordenar e recortar. */
    data class Block(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float)

    data class Found(val match: Catalog.Match, val block: Block?, val alternativas: List<Catalog.Match>)

    private val stop = setOf(
        "nintendo", "super", "sega", "entertainment", "system", "licensed", "by", "official", "seal", "quality", "original", "game", "pak",
        "cartridge", "cartucho", "made", "in", "japan", "brazil", "brasil", "playtronic", "tectoy", "tec", "toy", "cce", "mega", "drive", "master",
        "genesis", "playstation", "sony", "ntsc", "pal", "all", "rights", "reserved", "version", "the", "for", "use", "with", "only", "video",
        "computer", "family", "famicom", "snes", "nes", "sfc", "n64", "ps", "ps1", "ps2", "ps3", "ps4", "tm", "r", "c", "inc", "ltd", "co",
        "patent", "pending", "this", "is", "not", "to", "be", "rented", "sold", "seal", "of", "and", "de", "do", "da", "e", "jogo", "jogos",
        "manual", "instrucoes", "instructions", "warning", "aviso", "atari", "activision", "konami", "capcom", "namco", "taito", "hudson",
        "squaresoft", "square", "enix", "rare", "midway", "acclaim", "ea", "sports", "ubisoft", "gameboy", "boy", "advance", "color", "everyone", "teen", "mature",
        "esrb", "rating", "pending", "dts", "dolby", "digital", "stereo", "player", "players", "memory", "card", "block", "blocks", "disc", "disco", "cd", "dvd", "rom"
    )

    /** Texto do bloco sem as palavras de marca/aviso; vazio quando não sobra nada útil. */
    fun useful(text: String): String {
        val ws = Text.words(text).split(' ').filter { it.isNotEmpty() }
        val keep = ws.filter { it !in stop && !it.all(Char::isDigit) }
        return if (keep.sumOf { it.length } >= 4) ws.joinToString(" ") else ""
    }

    /**
     * Candidatos encontrados na foto. [repos] limita os consoles (vazio: os indicados pela própria foto,
     * ou os da coleção, ou todos). Retorna um resultado por jogo, na ordem em que aparecem na foto.
     */
    fun find(catalog: Catalog, blocks: List<Block>, fullText: String, colecaoRepos: Collection<String>, regiao: String? = null): List<Found> {
        val printed = Platforms.fromPrintedText(fullText)
        val repos = printed.ifEmpty { colecaoRepos.toList() }
        val out = ArrayList<Found>()
        val queries = blocks.mapNotNull { b -> useful(b.text).takeIf { it.isNotEmpty() }?.let { it to b } }
        for ((q, b) in queries) {
            var ms = catalog.search(q, repos, limit = 4, minScore = 0.72, regiao = regiao)
            if (ms.isEmpty() && repos.isNotEmpty() && printed.isEmpty()) ms = catalog.search(q, emptyList(), limit = 4, minScore = 0.8, regiao = regiao)
            if (ms.isNotEmpty()) out.add(Found(ms.first(), b, ms.drop(1)))
        }
        // título partido em vários blocos: tenta o texto inteiro quando nenhum bloco sozinho deu certo
        if (out.isEmpty()) {
            val all = useful(fullText.replace('\n', ' '))
            if (all.isNotEmpty()) catalog.search(all, repos, limit = 4, minScore = 0.7, regiao = regiao).let { ms -> if (ms.isNotEmpty()) out.add(Found(ms.first(), null, ms.drop(1))) }
        }
        // um resultado por jogo; mantém o de maior pontuação, na ordem da foto (de cima para baixo, da esquerda para a direita)
        return out.groupBy { it.match.repo + "|" + Text.norm(it.match.titulo) }.map { (_, g) -> g.maxBy { it.match.score } }
            .sortedWith(compareBy({ ((it.block?.top ?: 0f) * 5).toInt() }, { it.block?.left ?: 0f }))
    }
}

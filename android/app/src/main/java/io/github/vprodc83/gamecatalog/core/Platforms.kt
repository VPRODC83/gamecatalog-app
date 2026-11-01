package io.github.vprodc83.gamecatalog.core

/**
 * Plataformas: liga o código de console que o colecionador usa (SNES, MD, SFC, ATARI CCE…) ao acervo
 * de nomes/capas do libretro-thumbnails, e reconhece o console pelo texto impresso na etiqueta.
 */
object Platforms {
    const val SNES = "Nintendo_-_Super_Nintendo_Entertainment_System"
    const val NES = "Nintendo_-_Nintendo_Entertainment_System"
    const val MD = "Sega_-_Mega_Drive_-_Genesis"
    const val MS = "Sega_-_Master_System_-_Mark_III"
    const val N64 = "Nintendo_-_Nintendo_64"
    const val ATARI = "Atari_-_2600"

    private val alias: Map<String, String> = mapOf(
        "SNES" to SNES, "SUPERNINTENDO" to SNES, "SFC" to SNES, "SUPERFAMICOM" to SNES,
        "NES" to NES, "FAMICOM" to NES, "NINTENDINHO" to NES, "FC" to NES,
        "MD" to MD, "MEGADRIVE" to MD, "GENESIS" to MD, "MS" to MS, "MASTERSYSTEM" to MS, "SMS" to MS,
        "GG" to "Sega_-_Game_Gear", "GAMEGEAR" to "Sega_-_Game_Gear",
        "SEGACD" to "Sega_-_Mega-CD_-_Sega_CD", "MEGACD" to "Sega_-_Mega-CD_-_Sega_CD",
        "SATURN" to "Sega_-_Saturn", "SEGASATURN" to "Sega_-_Saturn", "DC" to "Sega_-_Dreamcast", "DREAMCAST" to "Sega_-_Dreamcast",
        "GB" to "Nintendo_-_Game_Boy", "GAMEBOY" to "Nintendo_-_Game_Boy", "GBC" to "Nintendo_-_Game_Boy_Color", "GAMEBOYCOLOR" to "Nintendo_-_Game_Boy_Color",
        "GBA" to "Nintendo_-_Game_Boy_Advance", "GAMEBOYADVANCE" to "Nintendo_-_Game_Boy_Advance", "GAMEBOYADVANCED" to "Nintendo_-_Game_Boy_Advance",
        "N64" to N64, "NINTENDO64" to N64, "GC" to "Nintendo_-_GameCube", "NGC" to "Nintendo_-_GameCube", "GAMECUBE" to "Nintendo_-_GameCube",
        "WII" to "Nintendo_-_Wii", "WIIU" to "Nintendo_-_Wii_U", "DS" to "Nintendo_-_Nintendo_DS", "NDS" to "Nintendo_-_Nintendo_DS",
        "3DS" to "Nintendo_-_Nintendo_3DS", "N3DS" to "Nintendo_-_Nintendo_3DS",
        "PS1" to "Sony_-_PlayStation", "PSX" to "Sony_-_PlayStation", "PS" to "Sony_-_PlayStation", "PLAYSTATION" to "Sony_-_PlayStation",
        "PS2" to "Sony_-_PlayStation_2", "PLAYSTATION2" to "Sony_-_PlayStation_2", "PS3" to "Sony_-_PlayStation_3", "PLAYSTATION3" to "Sony_-_PlayStation_3",
        "PS4" to "Sony_-_PlayStation_4", "PLAYSTATION4" to "Sony_-_PlayStation_4", "PSP" to "Sony_-_PlayStation_Portable",
        "MVS" to "SNK_-_Neo_Geo", "NEOGEO" to "SNK_-_Neo_Geo", "AES" to "SNK_-_Neo_Geo",
        "ODYSSEY" to "Magnavox_-_Odyssey2", "ODYSSEY2" to "Magnavox_-_Odyssey2", "VIDEOPAC" to "Magnavox_-_Odyssey2",
        "ATARI" to ATARI, "ATARI2600" to ATARI, "2600" to ATARI, "MSX" to "Microsoft_-_MSX",
        "XBOX" to "Microsoft_-_Xbox", "X360" to "Microsoft_-_Xbox_360", "XBOX360" to "Microsoft_-_Xbox_360",
        "PCENGINE" to "NEC_-_PC_Engine_-_TurboGrafx_16", "TURBOGRAFX" to "NEC_-_PC_Engine_-_TurboGrafx_16"
    )

    /** Acervo do libretro para um código de console da coleção ("" quando não se sabe). */
    fun repoOf(console: String?): String {
        val n = Text.norm(console)
        if (n.isEmpty()) return ""
        alias[n]?.let { return it }
        for (p in listOf("ATARI", "NES", "PS2", "PS3", "PS4", "PS1", "MD", "MS", "SNES")) if (n.startsWith(p)) alias[p]?.let { return it }
        return ""
    }

    fun sameFamily(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return true
        val ra = repoOf(a).ifEmpty { Text.norm(a) }; val rb = repoOf(b).ifEmpty { Text.norm(b) }
        return ra == rb
    }

    /** Textos impressos em etiquetas/caixas que indicam o console. */
    private val printed: List<Pair<Regex, String>> = listOf(
        "SUPER NINTENDO|SNES|SUPER FAMICOM|SHVC|SNS-" to SNES,
        "NINTENDO ?64|NUS-" to N64,
        "GAME ?BOY ADVANCE|AGB-" to "Nintendo_-_Game_Boy_Advance",
        "GAME ?BOY COLOR|CGB-" to "Nintendo_-_Game_Boy_Color",
        "GAME ?BOY|DMG-" to "Nintendo_-_Game_Boy",
        "NINTENDO ENTERTAINMENT SYSTEM|\\bNES\\b|FAMILY COMPUTER|HVC-" to NES,
        "MEGA ?DRIVE|GENESIS|SEGA ?16" to MD,
        "MASTER ?SYSTEM" to MS,
        "GAME ?GEAR" to "Sega_-_Game_Gear",
        "SEGA ?CD|MEGA-CD" to "Sega_-_Mega-CD_-_Sega_CD",
        "SATURN" to "Sega_-_Saturn",
        "DREAMCAST" to "Sega_-_Dreamcast",
        "PLAYSTATION ?2|PS2|SLUS-2|SLES-5|SCUS-97" to "Sony_-_PlayStation_2",
        "PLAYSTATION ?3|PS3|BLUS|BLES|BCUS" to "Sony_-_PlayStation_3",
        "PLAYSTATION ?4|PS4|CUSA" to "Sony_-_PlayStation_4",
        "\\bPSP\\b|UMD" to "Sony_-_PlayStation_Portable",
        "PLAYSTATION|SLUS-|SLES-|SCUS-|SCES-" to "Sony_-_PlayStation",
        "GAMECUBE|DOL-" to "Nintendo_-_GameCube",
        "\\bWII ?U\\b|WUP-" to "Nintendo_-_Wii_U",
        "\\bWII\\b|RVL-" to "Nintendo_-_Wii",
        "NINTENDO ?3DS|CTR-" to "Nintendo_-_Nintendo_3DS",
        "NINTENDO ?DS|NTR-" to "Nintendo_-_Nintendo_DS",
        "XBOX ?360" to "Microsoft_-_Xbox_360",
        "ATARI|CCE|DACTAR|POLIVOX|VIDEO COMPUTER SYSTEM" to ATARI,
        "ODYSSEY|VIDEOPAC|PHILIPS" to "Magnavox_-_Odyssey2",
        "NEO ?GEO|SNK" to "SNK_-_Neo_Geo",
        "\\bMSX\\b" to "Microsoft_-_MSX"
    ).map { (re, repo) -> re.toRegex(RegexOption.IGNORE_CASE) to repo }

    /** Consoles cujos nomes ou códigos aparecem no texto lido da foto, do mais específico ao mais geral. */
    fun fromPrintedText(text: String): List<String> = printed.filter { (re, _) -> re.containsMatchIn(text) }.map { it.second }.distinct()
}

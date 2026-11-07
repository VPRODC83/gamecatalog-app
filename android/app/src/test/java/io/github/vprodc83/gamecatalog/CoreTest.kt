package io.github.vprodc83.gamecatalog

import io.github.vprodc83.gamecatalog.core.Catalog
import io.github.vprodc83.gamecatalog.core.Identify
import io.github.vprodc83.gamecatalog.core.Platforms
import io.github.vprodc83.gamecatalog.core.Prices
import io.github.vprodc83.gamecatalog.core.Text
import io.github.vprodc83.gamecatalog.data.Xlsx
import io.github.vprodc83.gamecatalog.google.Sheets
import io.github.vprodc83.gamecatalog.ui.Updates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class CoreTest {
    companion object {
        lateinit var cat: Catalog
        @BeforeClass @JvmStatic fun load() { cat = File("src/main/assets/catalog.json.gz").inputStream().use { Catalog.load(it) } }
    }

    @Test fun text() {
        assertEquals("MEGADRIVE", Text.norm("Mega Drive"))
        assertEquals("pokemon red", Text.words("Pokémon Red (USA)"))
        assertEquals(1.0, Text.score("Super Mario World", "Super Mario World"), 0.0)
        assertTrue(Text.score("mario wrld", "Super Mario World") > 0.6)
        assertTrue(Text.score("zelda", "Sonic the Hedgehog") < 0.5)
    }

    @Test fun platforms() {
        assertEquals(Platforms.repoOf("SNES"), Platforms.repoOf("SFC"))
        assertTrue(Platforms.repoOf("MD").isNotEmpty())
        assertTrue(Platforms.fromPrintedText("SHVC-MW").isNotEmpty())
    }

    @Test fun catalogSearch() {
        val snes = Platforms.repoOf("SNES")
        val r = cat.search("Super Mario World", listOf(snes), limit = 3)
        assertTrue(r.isNotEmpty()); assertEquals("Super Mario World", r.first().tituloLimpo)
        assertNotNull(cat.coverUrl(r.first().repo, r.first().nomeAcervo))
        val br = cat.search("Sonic the Hedgehog", listOf(Platforms.repoOf("MD")), limit = 1, regiao = "BR").first()
        assertFalse(br.nomeAcervo.contains("Beta"))
    }

    @Test fun identifyTwoCartridges() {
        val blocks = listOf(
            Identify.Block("SUPER NINTENDO\nENTERTAINMENT SYSTEM", 0.05f, 0.05f, 0.45f, 0.1f),
            Identify.Block("Donkey Kong Country", 0.05f, 0.2f, 0.45f, 0.3f),
            Identify.Block("Super Mario World", 0.55f, 0.2f, 0.95f, 0.3f),
            Identify.Block("LICENSED BY NINTENDO", 0.05f, 0.9f, 0.45f, 0.95f))
        val f = Identify.find(cat, blocks, blocks.joinToString("\n") { it.text }, emptyList())
        assertEquals(listOf("Donkey Kong Country", "Super Mario World"), f.map { it.match.tituloLimpo })
    }

    @Test fun pricechartingParse() {
        val html = """<td id="used_price"><span class="price js-price">$45.10</span></td><td id="complete_price"><span class="price">$1,120.00</span></td><td id="new_price"><span>N/A</span></td>"""
        val (l, c, n) = Prices.parseProduct(html)
        assertEquals(45.10, l!!, 0.001); assertEquals(1120.0, c!!, 0.001); assertEquals(null, n)
        assertTrue(Prices.links("Super Mario World", "SNES").first().url.contains("super-mario-world-snes"))
    }

    @Test fun xlsxRoundTrip() {
        val sheets = listOf(Xlsx.Sheet("GAMES", listOf(listOf("CONSOLE", "NOME"), listOf("SNES", "Ação & <Aventura> \"1\""))), Xlsx.Sheet("SFC", listOf(listOf("NOME"))))
        val out = ByteArrayOutputStream(); Xlsx.write(sheets, out)
        val back = Xlsx.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(sheets.map { it.name }, back.map { it.name })
        assertEquals("Ação & <Aventura> \"1\"", back[0].rows[1][1])
    }

    @Test fun catalogUncompressed() {
        // o APK guarda o asset sem o .gz: o carregamento precisa aceitar os dois formatos
        val raw = java.util.zip.GZIPInputStream(File("src/main/assets/catalog.json.gz").inputStream()).readBytes()
        val c = Catalog.load(ByteArrayInputStream(raw))
        assertEquals(cat.repos.size, c.repos.size)
    }

    @Test fun clip() {
        assertEquals("Super Mario World", io.github.vprodc83.gamecatalog.ui.cleanClip("Super Mario World - Wikipédia\nmais texto"))
        assertEquals("Donkey Kong Country", io.github.vprodc83.gamecatalog.ui.cleanClip("  \nDonkey Kong Country | eBay"))
    }

    @Test fun consoleNames() {
        assertEquals("SUPER FAMICOM", io.github.vprodc83.gamecatalog.ui.cn("SFC"))
        assertEquals("FAMICOM", io.github.vprodc83.gamecatalog.ui.cn("fc "))
        assertEquals("SNES", io.github.vprodc83.gamecatalog.ui.cn("SNES"))
    }

    @Test fun translationsCoverEveryText() {
        // todo texto tr("…") do código precisa existir em cada idioma completo, com os mesmos {0}, {1}…
        val code = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        val keys = Regex("tr\\(\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(code).map { it.groupValues[1].replace("\\\"", "\"") }.toSet() +
            setOf("Coleção", "Procurados", "Compras", "Identificar", "Ajustes")
        assertTrue("só ${keys.size} textos encontrados", keys.size > 240)
        val dir = File("src/main/assets/i18n")
        for (f in dir.listFiles()!!.filter { it.name != "en-GB.json" }) {
            val o = org.json.JSONObject(f.readText())
            val missing = keys.filter { !o.has(it) }
            assertTrue("${f.name} sem: ${missing.take(5)}", missing.isEmpty())
            for (k in keys) {
                val ph = Regex("\\{\\d}").findAll(k).map { it.value }.sorted().toList()
                assertEquals("${f.name}: $k", ph, Regex("\\{\\d}").findAll(o.getString(k)).map { it.value }.sorted().toList())
            }
        }
        assertEquals(13, dir.listFiles()!!.size)
    }

    @Test fun trFormats() {
        io.github.vprodc83.gamecatalog.ui.I18n.useTable("en-US", mapOf("Versão {0}" to "Version {0}"))
        assertEquals("Version 1.2", io.github.vprodc83.gamecatalog.ui.tr("Versão {0}", "1.2"))
        assertEquals("Sem tradução 3", io.github.vprodc83.gamecatalog.ui.tr("Sem tradução {0}", 3))
        io.github.vprodc83.gamecatalog.ui.I18n.useTable("pt-BR", emptyMap())
    }

    @Test fun snapToTableOptions() {
        val reg = listOf("US", "BR", "JP", "EU", "ASIA"); val con = listOf("FAMICOM", "SFC", "SNES", "MD", "ODYSSEY", "ODYSSEY ")
        assertEquals("US", io.github.vprodc83.gamecatalog.data.Repo.snapTo("USA", reg))
        assertEquals("JP", io.github.vprodc83.gamecatalog.data.Repo.snapTo("jap", reg))
        assertEquals("FAMICOM", io.github.vprodc83.gamecatalog.data.Repo.snapTo("FC", con))
        assertEquals("ODYSSEY", io.github.vprodc83.gamecatalog.data.Repo.snapTo("ODYSSEY", con))
        assertEquals("NEOGEO", io.github.vprodc83.gamecatalog.data.Repo.snapTo("NEOGEO", con))
    }

    @Test fun nameVariantsCleanSheetNotes() {
        val v = Text.nameVariants("MICROMACHINES WORLD SERIES *LACRADO*")
        assertTrue(v.contains("MICROMACHINES WORLD SERIES"))
        val k = Text.nameVariants("KILLER INSTINCT EDIÇÃO DEFINITIVA")
        assertTrue(k.contains("KILLER INSTINCT DEFINITIVE EDITION")); assertTrue(k.contains("KILLER INSTINCT"))
        assertEquals("Tom & Jerry", Text.unescapeHtml("Tom &amp; Jerry"))
    }

    @Test fun libretroFindsCleanedNames() {
        // nome com anotação da planilha e erro de digitação ainda acha a capa
        val snes = Platforms.repoOf("SNES")
        val hit = Text.nameVariants("SUPER MARIO WORL *LACRADO*").firstNotNullOfOrNull { cat.search(it, listOf(snes), limit = 1, minScore = 0.78).firstOrNull() }
        assertEquals("Super Mario World", hit?.tituloLimpo)
    }

    @Test fun priceChartingImage() {
        val html = """<img src='https://storage.googleapis.com/images.pricecharting.com/abc123/240.jpg'><a href="https://storage.googleapis.com/images.pricecharting.com/abc123/1600.jpg">"""
        assertEquals("https://storage.googleapis.com/images.pricecharting.com/abc123/1600.jpg", Prices.parseImage(html))
    }

    @Test fun vgdbIndexMatches() {
        val v = io.github.vprodc83.gamecatalog.core.CoverSources.Vgdb.load(File("src/main/assets/vgdb.json.gz").inputStream())
        assertEquals("https://www.vgdb.com.br/super-nintendo/jogos/super-mario-world/", v.page("SUPER MARIO WORLD", "SNES", Platforms.repoOf("SNES")))
        assertEquals("https://www.vgdb.com.br/xbox-one/jogos/forza-horizon-4/", v.page("FORZA HORIZON 4", "XONE", ""))
    }

    @Test fun serialCodesFromLabelText() {
        val s = io.github.vprodc83.gamecatalog.core.Serials.load(File("src/main/assets/serials.json.gz").inputStream())
        val hits = s.find("SUPER NINTENDO\nSNS-MW-USA\nMADE IN JAPAN\nNUS-NSME-USA")
        assertEquals(listOf("Super Mario World", "Super Mario 64"), hits.map { Catalog.cleanTitle(it.nome) })
        assertEquals("Metal Gear Solid", Catalog.cleanTitle(s.find("SLUS-00594 ©1998 KONAMI").first().nome))
        assertTrue(s.find("LICENSED BY NINTENDO MADE IN USA").isEmpty())
    }

    @Test fun misc() {
        assertEquals("1AbCdEfGhIjKlMnOpQrStUvWxYz0123", Sheets.idFromLink("https://docs.google.com/spreadsheets/d/1AbCdEfGhIjKlMnOpQrStUvWxYz0123/edit?usp=sharing"))
        assertEquals("AA", Sheets.col(26))
        assertTrue(Updates.newer("v0.5.1", "0.5.0")); assertFalse(Updates.newer("v0.5.0", "0.5.0")); assertFalse(Updates.newer("0.4.9", "0.5"))
    }
}

package io.github.vprodc83.gamecatalog.core

import java.text.Normalizer

/** Funções de texto usadas para comparar nomes de jogos digitados à mão, lidos da etiqueta ou do catálogo. */
object Text {
    private val marks = "\\p{Mn}+".toRegex()
    private val paren = "\\([^)]*\\)|\\[[^\\]]*]".toRegex()
    private val nonAlnum = "[^a-z0-9]+".toRegex()

    fun stripAccents(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(marks, "")

    /** Chave sem acentos, maiúscula, só letras e números: "Mega Drive" → "MEGADRIVE". */
    fun norm(s: String?): String = stripAccents(s ?: "").uppercase().replace("[^A-Z0-9]".toRegex(), "")

    /** Palavras normalizadas para comparar: minúsculas, sem acentos, sem parênteses e sem pontuação. */
    fun words(s: String?): String = stripAccents(s ?: "").lowercase()
        .replace("*lacrado*", " ")
        .replace(" · .*$".toRegex(), "")
        .replace(paren, " ")
        .replace(nonAlnum, " ")
        .trim()

    private val noiseRe = "\\*[^*]*\\*|\\[[^\\]]*]|\\([^)]*\\)".toRegex()
    private val noiseWords = listOf("LACRADO", "LACRADA", "SEALED", "SEMINOVO", "USADO", "REPRO", "REPRODUCAO", "COMPLETO", "CIB", "LOOSE",
        "SO O CARTUCHO", "SO O DISCO", "SEM CAIXA", "SEM MANUAL", "GREATEST HITS", "PLATINUM", "PLAYERS CHOICE", "PLAYER S CHOICE",
        "NINTENDO SELECTS", "ESSENTIALS", "CLASSICS", "BEST SELLER", "THE BEST", "PS2 CLASSICS", "XBOX CLASSICS")
    private val editions = listOf("EDICAO DEFINITIVA" to "DEFINITIVE EDITION", "EDICAO COMPLETA" to "COMPLETE EDITION",
        "EDICAO DE JOGO DO ANO" to "GAME OF THE YEAR EDITION", "EDICAO JOGO DO ANO" to "GAME OF THE YEAR EDITION",
        "EDICAO DE COLECIONADOR" to "COLLECTORS EDITION", "EDICAO DO COLECIONADOR" to "COLLECTORS EDITION",
        "EDICAO ESPECIAL" to "SPECIAL EDITION", "EDICAO LIMITADA" to "LIMITED EDITION", "EDICAO DE LUXO" to "DELUXE EDITION",
        "EDICAO DELUXE" to "DELUXE EDITION", "EDICAO GOTY" to "GOTY EDITION", "COLETANEA" to "COLLECTION")

    /**
     * Versões do nome para procurar capas, da mais fiel à mais limpa: tira anotações da planilha
     * ("*LACRADO*", parênteses, "PLATINUM"…), traduz "EDIÇÃO DEFINITIVA" → "DEFINITIVE EDITION" e,
     * por fim, tenta sem a edição.
     */
    fun nameVariants(s: String): List<String> {
        val base = stripAccents(s).uppercase().replace(noiseRe, " ").replace("[^A-Z0-9:&' -]".toRegex(), " ")
        var clean = " $base "
        noiseWords.forEach { w -> clean = clean.replace(" $w ", " ") }
        clean = clean.replace("\\s+".toRegex(), " ").trim()
        var trans = clean; editions.forEach { (pt, en) -> trans = trans.replace(pt, en) }
        val noEd = trans.replace("\\b(DEFINITIVE|COMPLETE|GAME OF THE YEAR|GOTY|COLLECTORS|SPECIAL|LIMITED|DELUXE) EDITION\\b".toRegex(), "").replace("\\s+".toRegex(), " ").trim()
        return listOf(s.trim(), clean, trans, noEd).filter { it.length >= 2 }.distinctBy { norm(it) }
    }

    /** Desfaz entidades HTML simples (&amp; &quot; &#39; &#233;…). */
    fun unescapeHtml(s: String): String = "&(#x?[0-9a-fA-F]+|amp|quot|apos|lt|gt|nbsp);".toRegex().replace(s) { m ->
        when (val e = m.groupValues[1]) {
            "amp" -> "&"; "quot" -> "\""; "apos" -> "'"; "lt" -> "<"; "gt" -> ">"; "nbsp" -> " "
            else -> runCatching { (if (e.startsWith("#x")) e.drop(2).toInt(16) else e.drop(1).toInt()).toChar().toString() }.getOrDefault(m.value)
        }
    }

    /** Semelhança por pares de letras (coeficiente de Dice). */
    fun dice(a: String, b: String): Double {
        if (a.length < 2 || b.length < 2) return if (a == b) 1.0 else 0.0
        val m = HashMap<String, Int>()
        for (i in 0 until a.length - 1) { val k = a.substring(i, i + 2); m[k] = (m[k] ?: 0) + 1 }
        var n = 0
        for (i in 0 until b.length - 1) { val k = b.substring(i, i + 2); val c = m[k] ?: 0; if (c > 0) { n++; m[k] = c - 1 } }
        return 2.0 * n / (a.length + b.length - 2)
    }

    /** Semelhança entre dois textos já normalizados por [words]. */
    fun simWords(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val ta = a.split(' ').toSet(); val tb = b.split(' ').toSet()
        val inter = ta.count { it in tb }
        return maxOf(dice(a, b), inter.toDouble() / (ta + tb).size, 0.9 * inter / ta.size)
    }

    /**
     * Quanto o nome [name] corresponde à busca [q] (0 a 1). Valoriza busca por parte do nome
     * ("chrono trig" → "Chrono Trigger") e palavras abreviadas.
     */
    fun score(q: String, name: String): Double {
        val a = words(q); val b = words(name)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        if (b.contains(a)) return 0.9 + 0.05 * (a.length.toDouble() / b.length)
        val ta = a.split(' '); val tb = b.split(' ')
        if (ta.all { w -> tb.any { it.startsWith(w) } }) return 0.85
        return simWords(a, b)
    }
}

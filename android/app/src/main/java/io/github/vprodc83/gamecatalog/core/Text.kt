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

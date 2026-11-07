package io.github.vprodc83.gamecatalog.core

import org.json.JSONObject
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Códigos de produto impressos em cartuchos e discos (SHVC-MW, SNS-MW-USA, NUS-NSME, DMG-TR, SLUS-00594…)
 * ligados ao jogo exato, a partir das listas públicas do libretro-database (melhoria 15b). Funciona sem internet.
 */
class Serials(private val repos: List<String>, private val table: Map<String, Pair<Int, String>>) {
    data class Hit(val code: String, val repo: String, val nome: String)

    /** Códigos encontrados no texto lido da foto, já ligados aos jogos (sem repetir o mesmo jogo). */
    fun find(text: String): List<Hit> {
        val up = text.uppercase().replace('—', '-').replace('–', '-')
        val out = LinkedHashMap<String, Hit>()
        for (m in CODE.findAll(up)) {
            val segs = m.value.split('-', ' ').filter { it.isNotEmpty() }
            for (k in segs.size downTo 2) {
                val key = Text.norm(segs.take(k).joinToString(""))
                val v = table[key] ?: continue
                val hit = Hit(segs.take(k).joinToString("-"), repos[v.first], v.second)
                out.putIfAbsent(hit.repo + "|" + Catalog.cleanTitle(hit.nome), hit)
                break
            }
        }
        return out.values.toList()
    }

    companion object {
        /** Prefixo de 2 a 5 letras + partes com números/letras: "SNS-MW-USA", "SLUS-00594", "NUS NSME". */
        private val CODE = "\\b[A-Z]{2,5}[- ]?[A-Z0-9]{2,6}(?:[- ][A-Z0-9]{1,5}){0,2}\\b".toRegex()

        fun load(input: InputStream): Serials {
            val b = java.io.BufferedInputStream(input); b.mark(2)
            val gz = b.read() == 0x1f && b.read() == 0x8b; b.reset()
            val o = JSONObject((if (gz) GZIPInputStream(b) else b).bufferedReader().readText())
            val r = o.getJSONArray("repos").let { a -> (0 until a.length()).map { a.getString(it) } }
            val s = o.getJSONObject("s")
            val t = HashMap<String, Pair<Int, String>>(s.length() * 2)
            s.keys().forEach { k -> val a = s.getJSONArray(k); t[k] = a.getInt(0) to a.getString(1) }
            return Serials(r, t)
        }
    }
}

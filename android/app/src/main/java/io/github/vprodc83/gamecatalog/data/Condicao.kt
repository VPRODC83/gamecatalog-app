package io.github.vprodc83.gamecatalog.data

import io.github.vprodc83.gamecatalog.core.Text

/**
 * Condição do exemplar (melhoria 27): CIB (completo, com caixa e manual) ou loose (só o cartucho/disco).
 * Na coleção vem das colunas "CIB?" e "LOOSE?" (marcadas com "S"); nas compras, das colunas I e J da aba de compras.
 */
object Condicao {
    const val CIB = "CIB"
    const val LOOSE = "LOOSE"

    /** Célula marcada? ("S", "Sim", "X", "1"…) */
    fun marcado(v: String?): Boolean = Text.norm(v).replace(" ", "") in setOf("S", "SIM", "X", "Y", "YES", "TRUE", "VERDADEIRO", "1", "OK", "V")

    private fun eh(head: String, qual: String) = Text.norm(head).replace(" ", "").startsWith(qual)
    fun colunaCib(head: List<String>) = head.indexOfFirst { eh(it, CIB) }
    fun colunaLoose(head: List<String>) = head.indexOfFirst { eh(it, LOOSE) }

    /** CIB, LOOSE ou "" (não informado). */
    fun de(item: Item): String {
        val cib = item.flags.any { eh(it.first, CIB) && marcado(it.second) }
        val loose = item.flags.any { eh(it.first, LOOSE) && marcado(it.second) }
        return when { cib && !loose -> CIB; loose && !cib -> LOOSE; else -> "" }
    }

    fun rotulo(c: String) = when (c) { CIB -> "CIB"; LOOSE -> "loose"; else -> "" }
}

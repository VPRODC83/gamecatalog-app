package io.github.vprodc83.gamecatalog.data

import io.github.vprodc83.gamecatalog.core.Text
import org.json.JSONArray
import org.json.JSONObject

/** Em qual lista o jogo está. */
enum class Lista(val titulo: String) {
    COLECAO("Coleção"),      // jogos que você tem (aba da coleção)
    PROCURADO("Procurados"), // jogos que você quer (abas de procurados)
    COMPRADO("Compras");     // comprados, aguardando chegar (aba de compras)
}

/**
 * Um jogo lido da planilha. [aba] e [linha] dizem onde ele está; [raw] são as células da linha
 * como estavam na leitura (para conferir antes de gravar). [wi] é o índice da aba de procurados.
 */
data class Item(
    val lista: Lista,
    val aba: String,
    val linha: Int,
    val raw: List<String>,
    val console: String,
    val nome: String,
    val regiao: String = "",
    val status: String = "",
    val info: String = "",
    val wi: Int = -1,
    val data: String = "",     // compras: data da compra
    val obs: String = "",      // compras: observação
    val flags: List<Pair<String, String>> = emptyList()   // outras colunas da coleção (CIB, CAIXA…)
) {
    val id: String get() = "$aba!$linha"
    val chave: String get() = Text.norm(console) + "|" + Text.norm(nome)

    fun toJson(): JSONObject = JSONObject().put("lista", lista.name).put("aba", aba).put("linha", linha).put("raw", JSONArray(raw))
        .put("console", console).put("nome", nome).put("regiao", regiao).put("status", status).put("info", info).put("wi", wi)
        .put("data", data).put("obs", obs).put("flags", JSONArray(flags.map { JSONArray(listOf(it.first, it.second)) }))

    companion object {
        fun fromJson(o: JSONObject): Item {
            val raw = o.optJSONArray("raw") ?: JSONArray(); val fl = o.optJSONArray("flags") ?: JSONArray()
            return Item(Lista.valueOf(o.optString("lista", "COLECAO")), o.optString("aba"), o.optInt("linha"), (0 until raw.length()).map { raw.optString(it) },
                o.optString("console"), o.optString("nome"), o.optString("regiao"), o.optString("status"), o.optString("info"), o.optInt("wi", -1),
                o.optString("data"), o.optString("obs"), (0 until fl.length()).map { fl.getJSONArray(it).let { p -> p.optString(0) to p.optString(1) } })
        }
    }
}

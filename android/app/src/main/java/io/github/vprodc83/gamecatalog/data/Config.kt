package io.github.vprodc83.gamecatalog.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Como a planilha está organizada. Fica na aba APP_CONFIG (célula A1), no mesmo formato do app web,
 * então os dois apps e todas as pessoas que usam a planilha compartilham a mesma configuração.
 * Índices de coluna começam em 0; -1 = coluna inexistente.
 */
class Config(val raw: JSONObject) {
    data class Colecao(val aba: String, val console: Int, val nome: Int, val regiao: Int, val ultima: Int, val adicionado: Int = -1)
    data class Procurado(val aba: String, val nome: Int, val status: Int, val consoleCol: Int, val console: String, val info: Int, val adicionado: Int = -1)

    val colecao: Colecao get() = raw.getJSONObject("colecao").let { Colecao(it.getString("aba"), it.getInt("console"), it.getInt("nome"), it.optInt("regiao", -1), it.getInt("ultima"), it.optInt("adicionado", -1)) }
    val procurados: List<Procurado> get() = (raw.optJSONArray("procurados") ?: JSONArray()).let { a ->
        (0 until a.length()).map { a.getJSONObject(it).let { w -> Procurado(w.getString("aba"), w.getInt("nome"), w.optInt("status", -1), w.optInt("consoleCol", -1), w.optString("console"), w.optInt("info", -1), w.optInt("adicionado", -1)) } }
    }
    val compras: String get() = raw.optJSONObject("compras")?.optString("aba")?.ifBlank { null } ?: "COMPRAS"
    /** Plataforma escolhida à mão para um código de console (chave = código normalizado). */
    val plataformas: Map<String, String> get() = raw.optJSONObject("plataformas")?.let { o -> o.keys().asSequence().associateWith { o.optString(it) } } ?: emptyMap()

    fun withPlataforma(code: String, repo: String): Config = Config(JSONObject(raw.toString()).also { o ->
        (o.optJSONObject("plataformas") ?: JSONObject().also { o.put("plataformas", it) }).put(code, repo)
    })

    override fun toString(): String = raw.toString()

    companion object {
        const val TAB = "APP_CONFIG"
        const val CAPAS = "CAPAS"
        const val PRECOS = "PRECOS"
        /** Título da coluna com a data em que a linha entrou na lista (melhoria 12). */
        const val ADDED_HEAD = "ADICIONADO EM"
        val BUY_HEAD = listOf("DATA", "CONSOLE", "NOME", "REGIAO", "LISTA", "STATUS", "RECEBIDO_EM", "OBS")

        fun parse(s: String?): Config? = runCatching { Config(JSONObject(s!!)).also { it.colecao } }.getOrNull()

        fun build(colecao: Colecao, procurados: List<Procurado>, compras: String, previous: Config?): Config {
            val o = previous?.let { JSONObject(it.raw.toString()) } ?: JSONObject().put("v", 1)
            o.put("colecao", JSONObject().put("aba", colecao.aba).put("console", colecao.console).put("nome", colecao.nome).put("regiao", colecao.regiao).put("ultima", maxOf(colecao.ultima, colecao.adicionado)).put("adicionado", colecao.adicionado))
            o.put("procurados", JSONArray(procurados.map { JSONObject().put("aba", it.aba).put("nome", it.nome).put("status", it.status).put("consoleCol", it.consoleCol).put("console", it.console).put("info", it.info).put("adicionado", it.adicionado) }))
            o.put("compras", JSONObject().put("aba", compras))
            if (!o.has("plataformas")) o.put("plataformas", JSONObject())
            return Config(o)
        }
    }
}

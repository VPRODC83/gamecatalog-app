package io.github.vprodc83.gamecatalog.google

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Erro do Google com código HTTP (401 = sessão expirada, 403 = sem acesso, 404 = não encontrada). */
class GoogleError(val status: Int, message: String) : IOException(message)

/** Chamadas à API do Google Sheets com o token da conta Google do aparelho. */
class Sheets(private val token: suspend () -> String) {
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build()
    private val json = "application/json; charset=utf-8".toMediaType()
    private val base = "https://sheets.googleapis.com/v4/spreadsheets/"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private suspend fun call(method: String, url: String, body: JSONObject? = null): JSONObject {
        val t = token()
        val req = Request.Builder().url(url).header("Authorization", "Bearer $t")
            .method(method, body?.toString()?.toRequestBody(json)).build()
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            http.newCall(req).execute().use { r ->
                val txt = r.body?.string() ?: ""
                if (!r.isSuccessful) {
                    val msg = runCatching { JSONObject(txt).getJSONObject("error").getString("message") }.getOrDefault(r.message)
                    throw GoogleError(r.code, msg)
                }
                if (txt.isBlank()) JSONObject() else JSONObject(txt)
            }
        }
    }

    data class Meta(val title: String, val tabs: List<String>)

    suspend fun meta(id: String): Meta {
        val o = call("GET", "$base$id?fields=properties.title,sheets.properties(title)")
        val arr = o.optJSONArray("sheets") ?: JSONArray()
        return Meta(o.getJSONObject("properties").optString("title"), (0 until arr.length()).map { arr.getJSONObject(it).getJSONObject("properties").getString("title") })
    }

    private fun rows(o: JSONObject): List<List<String>> {
        val v = o.optJSONArray("values") ?: return emptyList()
        return (0 until v.length()).map { i -> val r = v.getJSONArray(i); (0 until r.length()).map { r.optString(it).trim() } }
    }

    suspend fun get(id: String, range: String): List<List<String>> = rows(call("GET", base + id + "/values/" + enc(range)))

    suspend fun batchGet(id: String, ranges: List<String>): List<List<List<String>>> {
        val q = ranges.joinToString("&") { "ranges=" + enc(it) }
        val arr = call("GET", "$base$id/values:batchGet?$q").optJSONArray("valueRanges") ?: JSONArray()
        return (0 until arr.length()).map { rows(arr.getJSONObject(it)) }
    }

    suspend fun put(id: String, range: String, values: List<List<String>>) {
        call("PUT", base + id + "/values/" + enc(range) + "?valueInputOption=RAW", JSONObject().put("values", JSONArray(values.map { JSONArray(it) })))
    }

    /** Acrescenta linhas no fim; devolve o número da primeira linha gravada (ou null). */
    suspend fun append(id: String, range: String, values: List<List<String>>): Int? {
        val o = call("POST", base + id + "/values/" + enc(range) + ":append?valueInputOption=RAW&insertDataOption=INSERT_ROWS", JSONObject().put("values", JSONArray(values.map { JSONArray(it) })))
        val ur = o.optJSONObject("updates")?.optString("updatedRange") ?: return null
        return "![A-Z]+(\\d+)".toRegex().find(ur)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** Tabela do Google Sheets (Formatar → Converter em tabela) dentro de uma aba. Índices começam em 0; fim exclusivo. */
    data class Table(val sheetId: Int, val tab: String, val tableId: String, val name: String, val startRow: Int, val endRow: Int, val startCol: Int, val endCol: Int)

    suspend fun tables(id: String): List<Table> {
        val arr = call("GET", "$base$id?fields=sheets(properties(sheetId,title),tables(tableId,name,range))").optJSONArray("sheets") ?: JSONArray()
        val out = ArrayList<Table>()
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i); val p = s.getJSONObject("properties"); val ts = s.optJSONArray("tables") ?: continue
            for (k in 0 until ts.length()) {
                val t = ts.getJSONObject(k); val r = t.optJSONObject("range") ?: continue
                out.add(Table(p.getInt("sheetId"), p.getString("title"), t.getString("tableId"), t.optString("name"),
                    r.optInt("startRowIndex", 0), r.optInt("endRowIndex", 0), r.optInt("startColumnIndex", 0), r.optInt("endColumnIndex", 0)))
            }
        }
        return out
    }

    /** Linhas novas DENTRO da tabela: o Google acrescenta no fim do corpo da tabela (antes do rodapé, se houver). */
    suspend fun appendToTable(id: String, t: Table, values: List<List<String>>) {
        val rows = JSONArray(values.map { r -> JSONObject().put("values", JSONArray(r.map { v -> JSONObject().put("userEnteredValue", JSONObject().put("stringValue", v)) })) })
        call("POST", "$base$id:batchUpdate", JSONObject().put("requests", JSONArray().put(JSONObject().put("appendCells",
            JSONObject().put("sheetId", t.sheetId).put("tableId", t.tableId).put("rows", rows).put("fields", "userEnteredValue")))))
    }

    /** Número interno da aba (usado para apagar linhas). */
    suspend fun tabId(id: String, title: String): Int {
        val arr = call("GET", "$base$id?fields=sheets.properties(sheetId,title)").optJSONArray("sheets") ?: JSONArray()
        for (i in 0 until arr.length()) { val p = arr.getJSONObject(i).getJSONObject("properties"); if (p.optString("title") == title) return p.getInt("sheetId") }
        throw GoogleError(404, "Aba $title não encontrada")
    }

    /** Apaga uma linha inteira (as de baixo sobem uma posição). [row] começa em 1. */
    suspend fun deleteRow(id: String, tabId: Int, row: Int) {
        val range = JSONObject().put("sheetId", tabId).put("dimension", "ROWS").put("startIndex", row - 1).put("endIndex", row)
        call("POST", "$base$id:batchUpdate", JSONObject().put("requests", JSONArray().put(JSONObject().put("deleteDimension", JSONObject().put("range", range)))))
    }

    suspend fun addTab(id: String, title: String, hidden: Boolean = false) {
        call("POST", "$base$id:batchUpdate", JSONObject().put("requests", JSONArray().put(JSONObject().put("addSheet", JSONObject().put("properties", JSONObject().put("title", title).put("hidden", hidden))))))
    }

    suspend fun create(title: String, tabs: List<String>): String {
        val o = call("POST", base.removeSuffix("/"), JSONObject().put("properties", JSONObject().put("title", title))
            .put("sheets", JSONArray(tabs.map { JSONObject().put("properties", JSONObject().put("title", it)) })))
        return o.getString("spreadsheetId")
    }

    companion object {
        fun q(tab: String) = "'" + tab.replace("'", "''") + "'"
        fun col(i: Int): String { var n = i + 1; val sb = StringBuilder(); while (n > 0) { val m = (n - 1) % 26; sb.insert(0, 'A' + m); n = (n - 1) / 26 }; return sb.toString() }
        /** ID da planilha a partir do link (docs.google.com/spreadsheets/d/<ID>/...). */
        fun idFromLink(s: String): String? = "/spreadsheets/d/([A-Za-z0-9_-]{20,})".toRegex().find(s)?.groupValues?.get(1)
            ?: s.trim().takeIf { it.matches("[A-Za-z0-9_-]{30,}".toRegex()) }
    }
}

package io.github.vprodc83.gamecatalog.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.util.Locale

/**
 * Idiomas da interface. O texto em português do Brasil é a própria chave; as traduções ficam em
 * assets/i18n/<código>.json. Palavras gravadas na planilha (COMPRADO, RECEBIDO, abas) não são traduzidas.
 */
object I18n {
    data class Lang(val code: String, val nome: String, val rtl: Boolean = false)

    val langs = listOf(
        Lang("pt-BR", "Português (Brasil)"), Lang("pt-PT", "Português (Portugal)"),
        Lang("en-US", "English (US)"), Lang("en-GB", "English (UK)"),
        Lang("es-ES", "Español (España)"), Lang("fr-FR", "Français"), Lang("de-DE", "Deutsch"),
        Lang("ru-RU", "Русский"), Lang("zh-CN", "简体中文"), Lang("ja-JP", "日本語"),
        Lang("it-IT", "Italiano"), Lang("he-IL", "עברית", rtl = true), Lang("ko-KR", "한국어"), Lang("th-TH", "ไทย")
    )

    var lang by mutableStateOf("pt-BR"); private set
    private var table: Map<String, String> = emptyMap()
    private var fallback: Map<String, String> = emptyMap()
    private var appCtx: Context? = null

    val rtl: Boolean get() = langs.firstOrNull { it.code == lang }?.rtl == true

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        val saved = runCatching { ctx.getSharedPreferences("interface", Context.MODE_PRIVATE).getString("idioma", null) }.getOrNull()
        set(saved ?: systemDefault(), save = false)
    }

    /** Idioma do celular, se for um dos oferecidos (senão inglês). */
    fun systemDefault(): String {
        val l = Locale.getDefault()
        val exact = "${l.language}-${l.country}"
        langs.firstOrNull { it.code.equals(exact, true) }?.let { return it.code }
        val lg = if (l.language == "iw") "he" else l.language
        return when (lg) { "pt" -> "pt-BR"; "en" -> "en-US"; else -> langs.firstOrNull { it.code.startsWith("$lg-") }?.code ?: "en-US" }
    }

    fun set(code: String, save: Boolean = true) {
        val ctx = appCtx
        fun load(c: String): Map<String, String> = runCatching {
            val o = JSONObject(ctx!!.assets.open("i18n/$c.json").bufferedReader(Charsets.UTF_8).readText())
            o.keys().asSequence().associateWith { o.getString(it) }
        }.getOrDefault(emptyMap())
        table = if (code == "pt-BR") emptyMap() else load(code)
        // inglês do Reino Unido e português de Portugal completam com a variante mais próxima
        fallback = when (code) { "en-GB" -> load("en-US"); "pt-PT" -> emptyMap(); else -> emptyMap() }
        lang = code
        if (save) runCatching { ctx!!.getSharedPreferences("interface", Context.MODE_PRIVATE).edit().putString("idioma", code).apply() }
    }

    fun get(key: String): String = table[key] ?: fallback[key] ?: key

    /** Para testes: usa uma tabela já carregada. */
    fun useTable(code: String, t: Map<String, String>) { table = t; fallback = emptyMap(); lang = code }
}

/** Texto da interface no idioma escolhido; {0}, {1}… recebem os valores de [args]. */
fun tr(key: String, vararg args: Any?): String {
    @Suppress("UNUSED_VARIABLE") val l = I18n.lang   // faz a tela se redesenhar ao trocar de idioma
    var s = I18n.get(key)
    if (args.isNotEmpty()) args.forEachIndexed { i, a -> s = s.replace("{$i}", a?.toString() ?: "") }
    return s.replace("{{", "{").replace("}}", "}")
}

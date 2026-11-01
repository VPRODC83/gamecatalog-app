package io.github.vprodc83.gamecatalog.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.vprodc83.gamecatalog.core.Catalog
import io.github.vprodc83.gamecatalog.data.Item
import io.github.vprodc83.gamecatalog.data.Repo
import io.github.vprodc83.gamecatalog.google.GoogleAuth
import io.github.vprodc83.gamecatalog.google.GoogleError
import io.github.vprodc83.gamecatalog.google.Sheets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Estado do app compartilhado entre as telas. */
class AppModel(val ctx: Context, val auth: GoogleAuth) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val sheets = Sheets { token() }
    val repo = Repo(ctx.filesDir, sheets)
    var catalog: Catalog? by mutableStateOf(null)
    var catalogError: String? by mutableStateOf(null)
    var loggedIn by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var sharedLink by mutableStateOf<String?>(null)
    var loginError by mutableStateOf<String?>(null)

    private suspend fun token(): String = try { auth.token(interactive = false) } catch (e: GoogleAuth.NeedsLogin) { loggedIn = false; throw e }

    init {
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { (runCatching { ctx.assets.open("catalog.json") }.getOrNull() ?: ctx.assets.open("catalog.json.gz")).use { Catalog.load(it) } }
            }
            catalog = r.getOrNull()
            catalogError = r.exceptionOrNull()?.let { "Não consegui abrir o catálogo de jogos (${it.message}). A busca pela planilha continua funcionando." }
        }
    }

    /** Tenta entrar sem mostrar nada (conta já autorizada antes). */
    fun silentLogin(after: () -> Unit = {}) = scope.launch {
        loggedIn = runCatching { auth.token(interactive = false) }.isSuccess
        if (loggedIn) after()
    }

    fun login(after: () -> Unit) = scope.launch {
        loginError = null; busy = true
        try { auth.token(interactive = true); loggedIn = true; after() }
        catch (e: Exception) { loginError = "Não foi possível entrar. " + (e.message ?: "Tente de novo.") }
        finally { busy = false }
    }

    /** Executa uma operação na planilha com aviso de erro; repete uma vez se o token venceu. */
    fun run(op: suspend () -> Unit) = scope.launch {
        busy = true
        try { op() }
        catch (e: GoogleError) {
            if (e.status == 401) { auth.invalidate(); runCatching { op() }.onFailure { message = Repo.friendly(it) } } else message = Repo.friendly(e)
        }
        catch (e: Exception) { message = Repo.friendly(e) }
        finally { busy = false }
    }

    fun refresh() = run { repo.refresh() }

    // ---- fotos suas (ficam só no aparelho) ----
    private val photosFile = File(ctx.filesDir, "fotos.json")
    private val photos: JSONObject = runCatching { JSONObject(photosFile.readText()) }.getOrDefault(JSONObject())
    var photosVersion by mutableStateOf(0)
    fun photo(chave: String, tipo: String): File? = photos.optString("$chave|$tipo").takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.exists() }
    fun setPhoto(chave: String, tipo: String, f: File?) {
        if (f == null) photos.remove("$chave|$tipo") else photos.put("$chave|$tipo", f.absolutePath)
        photosFile.writeText(photos.toString()); photosVersion++
    }
    fun photoDir(): File = File(ctx.filesDir, "fotos").apply { mkdirs() }

    /** Capa a mostrar: foto sua > capa escolhida na planilha (CAPAS) > capa automática do libretro. */
    fun coverFor(item: Item, tipo: String = "capa"): Any? {
        photo(item.chave, tipo)?.let { return it }
        repo.state.value.capas[item.chave]?.get(tipo)?.let { if (it.origem == "url") return it.valor }
        if (tipo != "capa") return null
        val c = catalog ?: return null
        val key = item.chave + "|" + repo.platformOf(item.console)
        if (autoCovers.containsKey(key)) return autoCovers[key]
        val repoId = repo.platformOf(item.console)
        val url = if (repoId.isEmpty()) null else c.search(item.nome, listOf(repoId), limit = 1, minScore = 0.8, regiao = item.regiao).firstOrNull()?.let { c.coverUrl(it.repo, it.nomeAcervo) }
        autoCovers[key] = url
        return url
    }
    private val autoCovers = HashMap<String, String?>()
}

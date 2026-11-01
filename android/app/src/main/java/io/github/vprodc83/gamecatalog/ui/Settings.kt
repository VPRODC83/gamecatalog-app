package io.github.vprodc83.gamecatalog.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.vprodc83.gamecatalog.data.Repo
import io.github.vprodc83.gamecatalog.data.Xlsx
import io.github.vprodc83.gamecatalog.google.Sheets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Updates {
    const val REPO = "VPRODC83/gamecatalog-app"
    data class Release(val versao: String, val apk: String?, val pagina: String)

    fun appVersion(ctx: Context): String = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "0"

    /** Compara versões "0.5.2" (ignora o "v" e sufixos). */
    fun newer(a: String, b: String): Boolean {
        fun p(s: String) = s.trimStart('v', 'V').substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val x = p(a); val y = p(b)
        for (i in 0 until maxOf(x.size, y.size)) { val d = (x.getOrElse(i) { 0 }) - (y.getOrElse(i) { 0 }); if (d != 0) return d > 0 }
        return false
    }

    /** Última versão publicada no GitHub do autor (só leitura, sem login). */
    fun latest(): Release? {
        val r = OkHttpClient().newCall(Request.Builder().url("https://api.github.com/repos/$REPO/releases/latest").header("Accept", "application/vnd.github+json").build()).execute()
        r.use {
            if (!it.isSuccessful) return null
            val o = JSONObject(it.body?.string() ?: return null)
            val assets = o.optJSONArray("assets")
            val apk = (0 until (assets?.length() ?: 0)).map { k -> assets!!.getJSONObject(k) }.firstOrNull { a -> a.optString("name").endsWith(".apk") }?.optString("browser_download_url")
            return Release(o.optString("tag_name"), apk, o.optString("html_url"))
        }
    }
}

/** Cópia de segurança: todas as abas da planilha num arquivo .xlsx, compartilhado pelo Android. */
suspend fun exportXlsx(m: AppModel): File {
    val id = m.repo.sheetId
    val meta = m.sheets.meta(id)
    val vals = m.sheets.batchGet(id, meta.tabs.map { Sheets.q(it) })
    val sheets = meta.tabs.mapIndexed { i, t -> Xlsx.Sheet(t, vals.getOrElse(i) { emptyList() }) }
    val dir = File(m.ctx.cacheDir, "exportar").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
    val nome = meta.title.replace("[\\\\/:*?\"<>|]".toRegex(), "_").ifBlank { "colecao" }
    val f = File(dir, "$nome ${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.xlsx")
    withContext(Dispatchers.IO) { f.outputStream().use { Xlsx.write(sheets, it) } }
    return f
}

@Composable
fun SettingsScreen(m: AppModel, onChangeSheet: () -> Unit, onMapping: () -> Unit, onSignedOut: () -> Unit) {
    val ctx = LocalContext.current
    val st by m.repo.state.collectAsState()
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var upd by remember { mutableStateOf<String?>(null) }
    var updRel by remember { mutableStateOf<Updates.Release?>(null) }
    val version = remember { Updates.appVersion(ctx) }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionTitle("Planilha")
                Text(st.title.ifBlank { "(sem nome)" }, fontWeight = FontWeight.SemiBold)
                Hint("${st.of(io.github.vprodc83.gamecatalog.data.Lista.COLECAO).size} na coleção · ${st.of(io.github.vprodc83.gamecatalog.data.Lista.PROCURADO).size} procurados · ${st.of(io.github.vprodc83.gamecatalog.data.Lista.COMPRADO).size} a receber")
                if (st.atualizadoEm > 0) Hint("Lida em " + SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(Date(st.atualizadoEm)) + if (st.offline) " (sem internet agora)" else "")
                Chips {
                    OutlinedButton(onClick = { m.refresh() }) { Text("Atualizar agora") }
                    OutlinedButton(onClick = { openUrl(ctx, "https://docs.google.com/spreadsheets/d/${m.repo.sheetId}/edit") }) { Text("Abrir no Google Sheets") }
                    OutlinedButton(onClick = {
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, "Abra no Game Catalog a nossa planilha de jogos: https://docs.google.com/spreadsheets/d/${m.repo.sheetId}/edit"), "Enviar o link da planilha"))
                    }) { Text("Enviar link para alguém") }
                }
                Hint("Para outra pessoa usar a mesma coleção: compartilhe a planilha com o e-mail dela como Editor (no Google Sheets) e mande o link.")
                Chips {
                    TextButton(onClick = onMapping) { Text("Abas e colunas") }
                    TextButton(onClick = onChangeSheet) { Text("Usar outra planilha") }
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionTitle("Cópia de segurança")
                Hint("Gera um arquivo Excel (.xlsx) com todas as abas, para guardar no Drive, mandar por e-mail etc.")
                Chips {
                    OutlinedButton(enabled = !exporting, onClick = {
                        exporting = true
                        scope.launch {
                            try {
                                val f = exportXlsx(m)
                                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                                    .putExtra(Intent.EXTRA_STREAM, fileUri(ctx, f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Salvar a cópia em"))
                            } catch (e: Exception) { m.message = Repo.friendly(e) } finally { exporting = false }
                        }
                    }) { Text("Exportar .xlsx") }
                    if (exporting) CircularProgressIndicator()
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionTitle("Aplicativo")
                Text("Versão $version")
                m.auth.email?.let { Hint("Conta: $it") }
                OutlinedButton(onClick = {
                    upd = "Verificando…"
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { Updates.latest() }.getOrNull() }
                        updRel = r?.takeIf { Updates.newer(it.versao, version) }
                        upd = when { r == null -> "Não consegui verificar agora."; updRel != null -> "Versão ${r.versao} disponível."; else -> "Você está na versão mais nova." }
                    }
                }) { Text("Procurar atualização") }
                upd?.let { Hint(it) }
                updRel?.let { r -> OutlinedButton(onClick = { openUrl(ctx, r.apk ?: r.pagina) }) { Text("Baixar a versão ${r.versao}") } }
                Chips {
                    TextButton(onClick = { openUrl(ctx, "https://vprodc83.github.io/privacidade.html") }) { Text("Privacidade") }
                    TextButton(onClick = { openUrl(ctx, "https://vprodc83.github.io/termos.html") }) { Text("Termos") }
                    TextButton(onClick = { scope.launch { m.auth.signOut(); m.loggedIn = false; onSignedOut() } }) { Text("Sair da conta") }
                }
                Hint("Identificação feita no próprio aparelho (leitura da etiqueta) e pelo Google Lens. Capas: libretro-thumbnails. Valores: PriceCharting.")
            }
        }
    }
}

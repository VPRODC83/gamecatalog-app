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
import androidx.compose.material3.MaterialTheme
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

/** Quanto o app ocupa no celular, separado por tipo. */
object Storage {
    data class Part(val nome: String, val bytes: Long, val detalhe: String = "")
    fun size(f: File?): Long = when { f == null || !f.exists() -> 0; f.isFile -> f.length(); else -> f.listFiles()?.sumOf { size(it) } ?: 0 }
    fun count(f: File?): Int = when { f == null || !f.exists() -> 0; f.isFile -> 1; else -> f.listFiles()?.sumOf { count(it) } ?: 0 }

    fun parts(ctx: Context): List<Part> {
        val fotos = File(ctx.filesDir, "fotos")
        val capas = File(ctx.cacheDir, "image_cache")
        val temp = listOf(File(ctx.cacheDir, "fotos"), File(ctx.cacheDir, "exportar"))
        val planilha = listOf("planilha-cache.json", "ajustes.json", "fotos.json").map { File(ctx.filesDir, it) }
        val apk = File(ctx.applicationInfo.sourceDir)
        val known = size(fotos) + size(capas) + temp.sumOf { size(it) } + planilha.sumOf { size(it) }
        val other = size(ctx.filesDir) + size(ctx.cacheDir) + size(ctx.codeCacheDir) - known
        return listOf(
            Part(tr("Suas fotos (capas e mídias)"), size(fotos), tr("{0} foto(s)", count(fotos))),
            Part(tr("Capas baixadas da internet"), size(capas), tr("podem ser baixadas de novo")),
            Part(tr("Fotos temporárias da identificação"), temp.sumOf { size(it) }, tr("podem ser apagadas")),
            Part(tr("Cópia da planilha no celular"), planilha.sumOf { size(it) }),
            Part(tr("Aplicativo (programa e catálogo de jogos)"), size(apk)),
            Part(tr("Outros dados do app"), maxOf(0, other))
        )
    }

    fun human(b: Long): String = when {
        b >= 1L shl 30 -> String.format(Locale("pt", "BR"), "%.2f GB", b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> String.format(Locale("pt", "BR"), "%.1f MB", b / (1L shl 20).toDouble())
        b >= 1L shl 10 -> String.format(Locale("pt", "BR"), "%.0f KB", b / (1L shl 10).toDouble())
        else -> "$b B"
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
                SectionTitle(tr("Planilha"))
                Text(st.title.ifBlank { tr("(sem nome)") }, fontWeight = FontWeight.SemiBold)
                Hint(tr("{0} na coleção · {1} procurados · {2} a receber", st.of(io.github.vprodc83.gamecatalog.data.Lista.COLECAO).size, st.of(io.github.vprodc83.gamecatalog.data.Lista.PROCURADO).size, st.of(io.github.vprodc83.gamecatalog.data.Lista.COMPRADO).size))
                if (st.atualizadoEm > 0) Hint(tr("Lida em ") + SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(Date(st.atualizadoEm)) + if (st.offline) tr(" (sem internet agora)") else "")
                Chips {
                    OutlinedButton(onClick = { m.refresh() }) { Text(tr("Atualizar agora")) }
                    OutlinedButton(onClick = { openUrl(ctx, "https://docs.google.com/spreadsheets/d/${m.repo.sheetId}/edit") }) { Text(tr("Abrir no Google Sheets")) }
                    OutlinedButton(onClick = {
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, tr("Abra no Game Catalog a nossa planilha de jogos: https://docs.google.com/spreadsheets/d/{0}/edit", m.repo.sheetId)), tr("Enviar o link da planilha")))
                    }) { Text(tr("Enviar link para alguém")) }
                }
                Hint(tr("Para outra pessoa usar a mesma coleção: compartilhe a planilha com o e-mail dela como Editor (no Google Sheets) e mande o link."))
                if ((st.config?.colecao?.adicionado ?: -1) < 0) AddedColumnBox(m)
                Chips {
                    TextButton(onClick = onMapping) { Text(tr("Abas e colunas")) }
                    TextButton(onClick = onChangeSheet) { Text(tr("Usar outra planilha")) }
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionTitle(tr("Cópia de segurança"))
                Hint(tr("Gera um arquivo Excel (.xlsx) com todas as abas, para guardar no Drive, mandar por e-mail etc."))
                Chips {
                    OutlinedButton(enabled = !exporting, onClick = {
                        exporting = true
                        scope.launch {
                            try {
                                val f = exportXlsx(m)
                                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                                    .putExtra(Intent.EXTRA_STREAM, fileUri(ctx, f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), tr("Salvar a cópia em")))
                            } catch (e: Exception) { m.message = Repo.friendly(e) } finally { exporting = false }
                        }
                    }) { Text(tr("Exportar .xlsx")) }
                    if (exporting) CircularProgressIndicator()
                }
            }
        }
        CoverIndexCard(m)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionTitle(tr("Aplicativo"))
                Text(tr("Versão {0}", version))
                m.auth.email?.let { Hint(tr("Conta: {0}", it)) }
                Picker(tr("Idioma"), I18n.lang, I18n.langs.map { it.code to it.nome }) { I18n.set(it) }
                StorageBox(m)
                OutlinedButton(onClick = {
                    upd = tr("Verificando…")
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { Updates.latest() }.getOrNull() }
                        updRel = r?.takeIf { Updates.newer(it.versao, version) }
                        upd = when { r == null -> tr("Não consegui verificar agora."); updRel != null -> tr("Versão {0} disponível.", r.versao); else -> tr("Você está na versão mais nova.") }
                    }
                }) { Text(tr("Procurar atualização")) }
                upd?.let { Hint(it) }
                updRel?.let { r -> OutlinedButton(onClick = { openUrl(ctx, r.apk ?: r.pagina) }) { Text(tr("Baixar a versão {0}", r.versao)) } }
                Chips {
                    TextButton(onClick = { openUrl(ctx, "https://vprodc83.github.io/privacidade.html") }) { Text(tr("Privacidade")) }
                    TextButton(onClick = { openUrl(ctx, "https://vprodc83.github.io/termos.html") }) { Text(tr("Termos")) }
                    TextButton(onClick = { scope.launch { m.auth.signOut(); m.loggedIn = false; onSignedOut() } }) { Text(tr("Sair da conta")) }
                }
                Hint(tr("Identificação feita no próprio aparelho (leitura da etiqueta) e pelo Google Lens. Capas: libretro-thumbnails, Wikipédia, PriceCharting, VGDB, Tec Toy e TheGamesDB. Valores: PriceCharting. Datas de lançamento: Wikidata."))
            }
        }
    }
}

@Composable
private fun StorageBox(m: AppModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var parts by remember { mutableStateOf<List<Storage.Part>?>(null) }
    var tick by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(tick, m.photosVersion) { parts = withContext(Dispatchers.IO) { Storage.parts(ctx) } }
    SectionTitle(tr("Espaço ocupado no celular"))
    val p = parts
    if (p == null) { CircularProgressIndicator(); return }
    p.forEach { part ->
        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) { Text(part.nome); if (part.detalhe.isNotBlank()) Hint(part.detalhe) }
            Text(Storage.human(part.bytes), fontWeight = FontWeight.SemiBold)
        }
    }
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth()) {
        Text(tr("Total"), Modifier.weight(1f), fontWeight = FontWeight.Bold); Text(Storage.human(p.sumOf { it.bytes }), fontWeight = FontWeight.Bold)
    }
    Chips {
        TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { listOf("fotos", "exportar").forEach { File(ctx.cacheDir, it).deleteRecursively() } }; tick++ } }) { Text(tr("Apagar temporárias")) }
        TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { coil3.SingletonImageLoader.get(ctx).let { it.diskCache?.clear(); it.memoryCache?.clear() } }; m.clearAutoCovers(); m.photosVersion++; tick++ } }) { Text(tr("Apagar capas baixadas")) }
    }
    Hint(tr("Suas fotos só são apagadas quando você as remove no jogo. Capas apagadas são baixadas de novo quando aparecem na tela."))
}

/** Melhoria 12: cria a coluna ADICIONADO EM nas tabelas da coleção e dos procurados, depois de mostrar o plano. */
@Composable
private fun AddedColumnBox(m: AppModel) {
    var open by remember { mutableStateOf(false) }
    var plan by remember { mutableStateOf<List<io.github.vprodc83.gamecatalog.data.Repo.AddedPlan>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    Hint(tr("Para ordenar por \"quando foi adicionado\" com a data certa, o app pode criar a coluna ADICIONADO EM e preenchê-la em cada jogo novo."))
    OutlinedButton(onClick = { open = true; plan = null; err = null }) { Text(tr("Criar coluna ADICIONADO EM")) }
    if (open) {
        androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { m.repo.planAddedColumn() }.onSuccess { plan = it }.onFailure { err = Repo.friendly(it) } }
        val p = plan
        ConfirmDialog(tr("Criar coluna ADICIONADO EM"), tr("Criar"), { open = false }, enabled = p != null && p.any { it.problema == null && it.existente < 0 }, onConfirm = {
            open = false; val pl = p ?: return@ConfirmDialog
            m.run { m.repo.createAddedColumn(pl); m.repo.refresh(); m.message = tr("Coluna ADICIONADO EM pronta. Os jogos novos já entram com a data.") }
        }) {
            when {
                err != null -> Text(err!!, color = MaterialTheme.colorScheme.error)
                p == null -> CircularProgressIndicator()
                else -> {
                    Text(tr("O app vai acrescentar uma coluna no fim de cada tabela abaixo. As linhas que já existem não são alteradas (a coluna fica vazia nelas)."))
                    p.forEach { a ->
                        Text(when {
                            a.existente >= 0 -> tr("{0}: a coluna já existe ({1}).", cn(a.aba), Sheets.col(a.existente))
                            a.problema != null -> tr("{0}: não será criada ({1}).", cn(a.aba), a.problema)
                            a.tabela != null -> tr("{0}: nova coluna {1}, dentro da tabela {2}.", cn(a.aba), Sheets.col(a.nova), a.tabela.name)
                            else -> tr("{0}: nova coluna {1}.", cn(a.aba), Sheets.col(a.nova))
                        }, color = if (a.problema != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    }
                    Hint(tr("Se alguém incluir jogos direto no Google Sheets, preencha a data à mão nessa coluna."))
                }
            }
        }
    }
}

/** Melhoria 16: prepara o reconhecimento pela imagem da capa (índice guardado só no celular). */
@Composable
private fun CoverIndexCard(m: AppModel) {
    val mt = m.matcher
    val st by m.repo.state.collectAsState()
    var console by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle(tr("Reconhecimento pela capa"))
            Hint(tr("Compara a foto com capas já conhecidas, no próprio celular. Ajuda quando a etiqueta não tem nome legível."))
            Text(tr("{0} capas preparadas · {1}", mt.count, Storage.human(mt.sizeBytes())))
            if (mt.running) {
                androidx.compose.material3.LinearProgressIndicator(progress = { if (mt.total > 0) mt.done.toFloat() / mt.total else 0f }, modifier = Modifier.fillMaxWidth())
                Hint(tr("{0} de {1}", mt.done, mt.total))
                OutlinedButton(onClick = { mt.cancel() }) { Text(tr("Interromper")) }
            } else {
                OutlinedButton(onClick = { mt.buildFromLists() }) { Text(tr("Preparar com a minha coleção e listas")) }
                val cat = m.catalog
                val opts = st.consoles.mapNotNull { c -> val r = m.repo.platformOf(c); cat?.repo(r)?.takeIf { it.sha.isNotEmpty() }?.let { c to r } }.distinctBy { it.second }
                if (opts.isNotEmpty()) {
                    Picker(tr("Todos os jogos de um console"), console, listOf("" to tr("(escolha)")) + opts.map { it.first to cn(it.first) }) { console = it }
                    opts.firstOrNull { it.first == console }?.let { (c, r) ->
                        val n = cat?.repo(r)?.names?.size ?: 0
                        Hint(tr("Cerca de {0} capas: baixa uns {1} e guarda uns {2}. Só no Wi-Fi.", n, Storage.human(n * 30_000L), Storage.human(n * 1_100L)))
                        OutlinedButton(onClick = { mt.buildConsole(r, c) }) { Text(tr("Preparar {0}", cn(c))) }
                    }
                }
                if (mt.count > 0) TextButton(onClick = { mt.clear() }) { Text(tr("Apagar o que foi preparado")) }
            }
            mt.status?.let { Hint(it) }
        }
    }
}

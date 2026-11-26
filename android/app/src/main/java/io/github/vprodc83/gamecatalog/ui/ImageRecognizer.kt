package io.github.vprodc83.gamecatalog.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.vprodc83.gamecatalog.data.Lista
import io.github.vprodc83.visao.Achado
import io.github.vprodc83.visao.Busca
import io.github.vprodc83.visao.Entrada
import io.github.vprodc83.visao.Features
import io.github.vprodc83.visao.Gray
import io.github.vprodc83.visao.Orb
import io.github.vprodc83.visao.Pacote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Reconhecimento por imagem (melhoria 24): compara a foto com cartuchos, discos e capas conhecidos,
 * tudo no celular. Cada imagem conhecida vira só os seus pontos característicos (alguns KB); os pacotes
 * de cada console são montados no GitHub a partir do LaunchBox Games DB e baixados só para os consoles
 * da sua coleção. As fotos que você confirma também viram referência ("aprendidas").
 */
class ImageRecognizer(private val m: AppModel) {
    /** Um pacote publicado: [tipo] "midia" (cartuchos e discos) ou "capa". */
    data class Pack(val acervo: String, val tipo: String, val arquivo: String, val bytes: Long, val imagens: Int, val data: String)

    private val dir = File(m.ctx.filesDir, "indices").apply { mkdirs() }
    private val learnedFile = File(dir, "aprendidas.gcix")
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
    // pacotes abertos, do usado há mais tempo para o mais recente; fecha os antigos acima de ~6 mil imagens (≈ 45 MB)
    private val loaded = object : LinkedHashMap<String, List<Entrada>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Entrada>>?): Boolean = size > 1 && values.sumOf { it.size } > 3500
    }
    private val learned = ArrayList<Entrada>()

    var manifest by mutableStateOf<List<Pack>>(emptyList()); private set
    var manifestDate by mutableStateOf(""); private set
    var running by mutableStateOf(false); private set
    var done by mutableStateOf(0); private set
    var total by mutableStateOf(0); private set
    var status by mutableStateOf<String?>(null)
    var learnedCount by mutableStateOf(0); private set
    /** Muda quando um pacote entra ou sai (para a tela de Ajustes se redesenhar). */
    var version by mutableStateOf(0); private set

    init {
        m.scope.launch(Dispatchers.IO) {
            runCatching { File(dir, "indices.json").takeIf { it.exists() }?.readText()?.let { parseManifest(it) } }
            runCatching { if (learnedFile.exists()) learnedFile.inputStream().use { learned.addAll(Pacote.read(it)) } }
            withContext(Dispatchers.Main) { learnedCount = learned.size }
        }
    }

    companion object {
        const val BASE = "https://github.com/VPRODC83/gamecatalog-app/releases/download/indices/"
        /** Pontos confirmados a partir dos quais o resultado é seguro / vale como sugestão. */
        const val SEGURO = 20
        const val SUGESTAO = 10
        private const val PX_FOTO = 800
        private const val PONTOS_FOTO = 1500
        private const val PONTOS_APRENDIDA = 400
        fun safe(acervo: String) = acervo.replace("@", "AT_").replace(Regex("[^A-Za-z0-9_.-]"), "_")
    }

    private fun parseManifest(txt: String) {
        val o = JSONObject(txt); val a = o.getJSONArray("pacotes")
        manifest = (0 until a.length()).map { i -> a.getJSONObject(i).let { p -> Pack(p.getString("acervo"), p.optString("tipo", "midia"), p.getString("arquivo"), p.optLong("bytes"), p.optInt("imagens"), p.optString("data")) } }
        manifestDate = o.optString("data")
    }

    fun file(p: Pack) = File(dir, p.arquivo)
    fun installed(p: Pack) = file(p).exists()
    fun installedBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L
    fun packs(acervo: String) = manifest.filter { it.acervo == acervo }
    fun pack(acervo: String, tipo: String) = manifest.firstOrNull { it.acervo == acervo && it.tipo == tipo }
    /** O pacote instalado é diferente do publicado (há versão nova)? */
    fun outdated(p: Pack): Boolean = installed(p) && file(p).length() != p.bytes
    /** Algum pacote desse console está no celular? (procura também arquivos de uma lista antiga) */
    fun installed(acervo: String): Boolean = packs(acervo).any { installed(it) } ||
        (dir.listFiles()?.any { it.name.startsWith(safe(acervo) + "-") && it.name.endsWith(".gcix.gz") } == true)

    /** Acervos (consoles) da coleção e das listas, na forma usada nos pacotes. */
    fun myAcervos(): List<String> {
        val st = m.repo.state.value
        return (st.consoles + st.of(Lista.PROCURADO).map { it.console }).map { m.repo.platformOf(it) }.filter { it.isNotEmpty() }.distinct()
    }

    private fun wifi(): Boolean = runCatching {
        val cm = m.ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    }.getOrDefault(false)

    suspend fun refreshManifest(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(BASE + "indices.json").header("User-Agent", "GameCatalog").build()).execute().use { r ->
                if (!r.isSuccessful) return@runCatching false
                val txt = r.body?.string() ?: return@runCatching false
                withContext(Dispatchers.Main) { parseManifest(txt) }
                File(dir, "indices.json").writeText(txt); true
            }
        }.getOrDefault(false)
    }

    /** Baixa (ou atualiza) os pacotes dados. [soWifi]: não usa dados móveis. */
    fun download(packs: List<Pack>, soWifi: Boolean) {
        if (running) return
        running = true; status = null
        m.scope.launch {
            try {
                val todo = packs.filter { !installed(it) || outdated(it) }
                if (todo.isEmpty()) { status = tr("Tudo em dia."); return@launch }
                total = todo.size; done = 0; var falhas = 0
                for (p in todo) {
                    if (soWifi && !wifi()) { status = tr("Pausado: sem Wi-Fi. Continue quando estiver no Wi-Fi."); break }
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            val tmp = File(dir, p.arquivo + ".tmp")
                            http.newCall(Request.Builder().url(BASE + p.arquivo).header("User-Agent", "GameCatalog").build()).execute().use { r ->
                                if (!r.isSuccessful) return@runCatching false
                                r.body?.byteStream()?.use { i -> tmp.outputStream().use { i.copyTo(it) } } ?: return@runCatching false
                            }
                            // confere antes de trocar o antigo
                            GZIPInputStream(tmp.inputStream()).use { Pacote.read(it) }
                            tmp.renameTo(file(p))
                        }.getOrDefault(false)
                    }
                    if (ok) synchronized(loaded) { loaded.remove(p.arquivo) } else falhas++
                    done++; version++
                }
                if (status == null) status = if (falhas == 0) tr("{0} pacote(s) de imagens prontos.", done) else tr("{0} pacote(s) não baixaram. Tente de novo depois.", falhas)
            } finally { running = false }
        }
    }

    /** O que falta para os consoles da coleção: mídias sempre; capas se ligado em Ajustes. */
    fun missing(capas: Boolean = m.ctx.getSharedPreferences("interface", Context.MODE_PRIVATE).getBoolean("imagens_capas", false)): List<Pack> =
        myAcervos().flatMap { packs(it) }.filter { (it.tipo == "midia" || capas) && (!installed(it) || outdated(it)) }

    /** Na abertura: baixa sozinho, no Wi-Fi, o que faltar para os consoles da coleção (se ligado em Ajustes). */
    fun autoDownload() {
        if (!m.ctx.getSharedPreferences("interface", Context.MODE_PRIVATE).getBoolean("imagens_auto", true) || !wifi()) return
        m.scope.launch {
            if (!refreshManifest()) return@launch
            val faltam = missing()
            if (faltam.isNotEmpty()) download(faltam, soWifi = true)
        }
    }

    /** Fecha os pacotes abertos (são lidos de novo do arquivo na próxima foto). Chamado quando o Android pede memória. */
    fun liberar() { synchronized(loaded) { loaded.clear() } }

    fun delete(p: Pack) { file(p).delete(); synchronized(loaded) { loaded.remove(p.arquivo) }; version++ }

    /** Arquivos de pacote desse console no celular (mídias e capas). */
    private fun files(acervo: String): List<File> = dir.listFiles()?.filter { it.name.startsWith(safe(acervo) + "-") && it.name.endsWith(".gcix.gz") }.orEmpty()

    private fun entries(f: File): List<Entrada> = synchronized(loaded) {
        loaded.getOrPut(f.name) {
            runCatching {
                val (l, bits) = GZIPInputStream(f.inputStream()).use { Pacote.readWithBits(it) }
                Busca.BITS = bits  // todos os pacotes de uma rodada usam o mesmo tamanho de assinatura
                l
            }.getOrDefault(emptyList())
        }
    }

    fun hasAny(): Boolean = learned.isNotEmpty() || myAcervos().any { installed(it) }

    private fun features(bmp: Bitmap, pontos: Int, niveis: Int = Orb.LEVELS): Features {
        val s = minOf(1f, PX_FOTO.toFloat() / maxOf(bmp.width, bmp.height))
        val b = if (s < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true) else bmp
        val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return Orb.extract(Gray.fromArgb(px, b.width, b.height), pontos, niveis)
    }

    /**
     * Procura a foto nos pacotes dos acervos dados (ou de todos os seus consoles) e nas fotos aprendidas.
     * Divide o trabalho entre os núcleos do celular. Devolve um resultado por jogo, do melhor para o pior.
     */
    suspend fun search(bmp: Bitmap, acervos: List<String>): List<Achado> = withContext(Dispatchers.Default) {
        val arquivos = acervos.flatMap { files(it) }
        val aprendidas = synchronized(learned) { learned.toList() }
        if (arquivos.isEmpty() && aprendidas.isEmpty()) return@withContext emptyList()
        val q = features(bmp, PONTOS_FOTO, Orb.LEVELS_FOTO); if (q.n == 0) return@withContext emptyList()
        val c = Busca.Consulta(q)
        val partes = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        suspend fun uma(lista: List<Entrada>): List<Achado> {
            if (lista.isEmpty()) return emptyList()
            val pr = arrayOfNulls<IntArray>(lista.size); val passo = (lista.size + partes - 1) / partes
            coroutineScope { (0 until partes).map { k -> async { Busca.contar(lista, c, k * passo, minOf(lista.size, (k + 1) * passo), pr) } }.awaitAll() }
            return Busca.finalizar(lista, q, pr, finalistas = 30, minimo = SUGESTAO)
        }
        // um pacote por vez (cada um com o seu filtro de molde), e as fotos aprendidas à parte
        val todos = arquivos.flatMap { uma(entries(it)) } + uma(aprendidas)
        todos.sortedByDescending { it.pontos }.distinctBy { Catalog0.norm(it.e.nome) + "|" + it.e.acervo }
    }

    /** Guarda a foto como referência do jogo escolhido: da próxima vez, esse cartucho é reconhecido. */
    fun learn(bmp: Bitmap, nome: String, acervo: String, regiao: String) {
        m.scope.launch(Dispatchers.Default) {
            runCatching {
                val f = Busca.fortes(features(bmp, PONTOS_FOTO), PONTOS_APRENDIDA)
                if (f.n < 30) return@runCatching
                synchronized(learned) {
                    learned.add(Entrada(nome, acervo, regiao, "foto", f))
                    while (learned.size > 2000) learned.removeAt(0)
                    learnedFile.outputStream().use { Pacote.write(it, learned) }
                }
                withContext(Dispatchers.Main) { learnedCount = learned.size }
            }
        }
    }

    fun clearLearned() { synchronized(learned) { learned.clear(); learnedFile.delete() }; learnedCount = 0 }
}

/** Normalização simples de nomes (sem depender do catálogo). */
private object Catalog0 { fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim() }

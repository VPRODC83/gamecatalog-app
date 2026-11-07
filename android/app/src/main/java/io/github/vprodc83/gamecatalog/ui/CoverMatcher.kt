package io.github.vprodc83.gamecatalog.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.Embedding
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import io.github.vprodc83.gamecatalog.core.Catalog
import io.github.vprodc83.gamecatalog.data.Lista
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Reconhecimento pela imagem da capa (melhoria 16), todo no celular: um modelo pequeno e aberto
 * (MobileNet V3, MediaPipe) transforma cada capa numa "impressão" de 1.024 números; a foto é comparada
 * com as impressões guardadas. O índice é montado aos poucos, a partir das capas da coleção e das listas
 * e, se você quiser, de todos os jogos de um console (só no Wi-Fi).
 */
class CoverMatcher(private val m: AppModel) {
    data class Entry(val titulo: String, val repo: String, val console: String, val vec: ByteArray)
    data class Similar(val titulo: String, val repo: String, val console: String, val score: Double)

    private val file = File(m.ctx.filesDir, "capas-impressoes.bin")
    private val entries = ArrayList<Entry>()
    private var embedder: ImageEmbedder? = null
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    var count by mutableStateOf(0); private set
    var running by mutableStateOf(false); private set
    var done by mutableStateOf(0); private set
    var total by mutableStateOf(0); private set
    var status by mutableStateOf<String?>(null)
    private var job: Job? = null

    init { m.scope.launch(Dispatchers.IO) { load() } }

    /** O modelo roda em quase todo celular; se não rodar (aparelho antigo de 32 bits), a função fica desligada. */
    private fun model(): ImageEmbedder? = embedder ?: runCatching {
        ImageEmbedder.createFromOptions(m.ctx, ImageEmbedder.ImageEmbedderOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("modelos/capas.tflite").build())
            .setL2Normalize(true).setQuantize(true).build())
    }.getOrNull().also { embedder = it }

    val available: Boolean get() = model() != null

    fun embed(bmp: Bitmap): ByteArray? = runCatching {
        model()?.embed(BitmapImageBuilder(bmp).build())?.embeddingResult()?.embeddings()?.firstOrNull()?.quantizedEmbedding()
    }.getOrNull()

    private fun cosine(a: ByteArray, b: ByteArray): Double {
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { val x = a[i].toDouble(); val y = b[i].toDouble(); dot += x * y; na += x * x; nb += y * y }
        return if (na == 0.0 || nb == 0.0) 0.0 else dot / Math.sqrt(na * nb)
    }

    /** As capas mais parecidas com a foto (inteira e o centro dela). */
    suspend fun similar(photo: Bitmap, top: Int = 5, min: Double = 0.55): List<Similar> = withContext(Dispatchers.Default) {
        if (entries.isEmpty()) return@withContext emptyList()
        val w = photo.width; val h = photo.height
        val views = listOf(photo, Bitmap.createBitmap(photo, w / 8, h / 8, w * 3 / 4, h * 3 / 4))
        val qs = views.mapNotNull { embed(it) }
        if (qs.isEmpty()) return@withContext emptyList()
        val snap = synchronized(entries) { entries.toList() }
        snap.map { e -> Similar(e.titulo, e.repo, e.console, qs.maxOf { q -> cosine(q, e.vec) }) }
            .filter { it.score >= min }.sortedByDescending { it.score }.distinctBy { it.repo + "|" + it.titulo.lowercase() }.take(top)
    }

    // ---------------- índice ----------------

    private fun load() = runCatching {
        if (!file.exists()) return@runCatching
        DataInputStream(file.inputStream().buffered()).use { inp ->
            val n = inp.readInt()
            repeat(n) {
                val t = inp.readUTF(); val r = inp.readUTF(); val c = inp.readUTF(); val len = inp.readUnsignedShort()
                val v = ByteArray(len); inp.readFully(v); entries.add(Entry(t, r, c, v))
            }
        }
    }.also { count = entries.size }

    private fun save() = runCatching {
        val tmp = File(file.path + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { o ->
            val snap = synchronized(entries) { entries.toList() }
            o.writeInt(snap.size)
            snap.forEach { e -> o.writeUTF(e.titulo); o.writeUTF(e.repo); o.writeUTF(e.console); o.writeShort(e.vec.size); o.write(e.vec) }
        }
        tmp.renameTo(file)
    }

    fun sizeBytes(): Long = file.length()
    fun clear() { synchronized(entries) { entries.clear() }; file.delete(); count = 0 }
    private fun has(titulo: String, repo: String) = synchronized(entries) { entries.any { it.repo == repo && it.titulo.equals(titulo, true) } }

    private fun wifi(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    }.getOrDefault(false)

    private fun download(url: String): Bitmap? = runCatching {
        http.newCall(Request.Builder().url(url).header("User-Agent", "GameCatalog/1.0 (+https://github.com/VPRODC83/gamecatalog-app)").build()).execute().use { r ->
            if (!r.isSuccessful) return null
            val bytes = r.body?.bytes() ?: return null
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var s = 1; while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= 320) s *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
        }
    }.getOrNull()

    /** (título, acervo, console, endereço da capa) para indexar. */
    private data class Todo(val titulo: String, val repo: String, val console: String, val url: String)

    /** Prepara com as capas da coleção, dos procurados e das compras (o que já aparece no app). */
    fun buildFromLists() {
        val st = m.repo.state.value
        val items = (st.of(Lista.COLECAO) + st.of(Lista.PROCURADO) + st.of(Lista.COMPRADO)).distinctBy { it.chave }
        val todo = items.mapNotNull { i -> (m.coverFor(i) as? String)?.takeIf { it.startsWith("http") }?.let { Todo(i.nome, m.repo.platformOf(i.console), i.console, it) } }
        run(todo, onlyWifi = false)
    }

    /** Acrescenta todos os jogos de um console do catálogo (só no Wi-Fi; pode levar muito tempo). */
    fun buildConsole(repo: String, console: String) {
        val c: Catalog = m.catalog ?: return
        val r = c.repo(repo) ?: return
        val todo = r.names.filter { !Catalog.bad(it) }.distinctBy { Catalog.cleanTitle(it) }
            .mapNotNull { n -> c.coverUrl(repo, n)?.let { Todo(Catalog.cleanTitle(n), repo, console, it) } }
        run(todo, onlyWifi = true)
    }

    private fun run(all: List<Todo>, onlyWifi: Boolean) {
        if (running) return
        if (!available) { status = tr("Este celular não consegue rodar o reconhecimento pela capa."); return }
        val todo = all.filter { !has(it.titulo, it.repo) }
        if (todo.isEmpty()) { status = tr("Nada novo para preparar."); return }
        running = true; done = 0; total = todo.size; status = null
        job = m.scope.launch(Dispatchers.IO) {
            var falhas = 0
            for (t in todo) {
                if (!isActive) break
                if (onlyWifi && !wifi(m.ctx)) { withContext(Dispatchers.Main) { status = tr("Pausado: sem Wi-Fi. Continue quando estiver no Wi-Fi.") }; break }
                val bmp = download(t.url)
                val v = bmp?.let { embed(it) }
                if (v != null) synchronized(entries) { entries.add(Entry(t.titulo, t.repo, t.console, v)) } else falhas++
                withContext(Dispatchers.Main) { done++; count = entries.size }
                if (done % 50 == 0) save()
            }
            save()
            withContext(Dispatchers.Main) { running = false; if (status == null) status = tr("{0} capas preparadas ({1} sem imagem).", done - falhas, falhas) }
        }
    }

    fun cancel() { job?.cancel(); running = false; m.scope.launch(Dispatchers.IO) { save() }; status = tr("Interrompido. O que já foi preparado continua valendo.") }
}

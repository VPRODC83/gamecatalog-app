package io.github.vprodc83.montador

import io.github.vprodc83.visao.Busca
import io.github.vprodc83.visao.Entrada
import io.github.vprodc83.visao.Features
import io.github.vprodc83.visao.Gray
import io.github.vprodc83.visao.Orb
import io.github.vprodc83.visao.Pacote
import org.json.JSONArray
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.GZIPOutputStream
import javax.imageio.ImageIO

fun gray(f: File, max: Int): Gray? = runCatching {
    val img = ImageIO.read(f) ?: return null
    val px = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
    Gray.fromArgb(px, img.width, img.height).fit(max)
}.getOrNull()

fun norm(s: String) = s.lowercase().replace("&", "and").replace(Regex("[^a-z0-9]+"), " ").trim()

/** Extrai os pontos de muitas imagens em paralelo. */
fun <T> paralelo(itens: List<T>, f: (T) -> Features?): List<Features?> {
    val ex = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    try { return itens.map { ex.submit<Features?> { f(it) } }.map { it.get() } } finally { ex.shutdown() }
}

/**
 * Uso:
 *  avaliar <idx.json> <dir imagens> <q.json> [manter]   — mede o acerto com fotos reais
 *  montar <lista.json> <dir imagens> <saida.bin.gz> [manter] — monta um pacote
 */
fun main(args: Array<String>) {
    when (args.getOrNull(0)) {
        "avaliar" -> avaliar(File(args[1]), File(args[2]), File(args[3]), args.getOrNull(4)?.toInt() ?: 150)
        "testar" -> testar(File(args[1]), File(args[2]))
        "montar" -> montar(File(args[1]), File(args[2]), File(args[3]), args.getOrNull(4)?.toInt() ?: 150)
        else -> System.err.println("uso: avaliar|montar …")
    }
}

val PX_INDICE = System.getenv("PXI")?.toInt() ?: 480
const val PX_FOTO = 800
const val PONTOS_FOTO = 1500

fun avaliar(idxF: File, dir: File, qF: File, manter: Int) {
    System.getenv("LIM")?.toInt()?.let { Busca.LIMITE_MOLDE = it }
    Busca.BITS = System.getenv("BITS")?.toInt() ?: 128
    val idx = JSONArray(idxF.readText()); val q = JSONArray(qF.readText())
    val t0 = System.currentTimeMillis()
    val nomes = (0 until idx.length()).map { norm(idx.getJSONArray(it).getString(1)) }
    val brutos = paralelo((0 until idx.length()).toList()) { i ->
        val fn = idx.getJSONArray(i).getString(3).substringBeforeLast('.') + ".jpg"
        gray(File(dir, "idx/$fn"), PX_INDICE)?.let { Orb.extract(it, System.getenv("NRAW")?.toInt() ?: 600) }
    }.map { it ?: Features.EMPTY }
    println("extração índice: ${(System.currentTimeMillis() - t0) / 1000}s")
    val t1 = System.currentTimeMillis()
    val feats = when {
        manter <= 0 -> brutos
        System.getenv("MODO") == "forte" -> brutos.map { Busca.fortes(it, manter) }
        else -> Busca.distintivos(brutos, nomes, manter, amostra = 60)
    }
    println("distintivos: ${(System.currentTimeMillis() - t1) / 1000}s; bytes/img ${feats.map { it.n * 36 }.average().toInt()}")
    val lista = feats.mapIndexed { i, f -> Entrada(nomes[i], "", "", "cart", f) }
    var r1 = 0; var r5 = 0; val certos = ArrayList<Int>(); val errados = ArrayList<Int>(); var tempo = 0L
    for (k in 0 until q.length()) {
        val a = q.getJSONArray(k); val alvo = norm(a.getString(0).replace('-', ' '))
        val g = gray(File(a.getString(2)), PX_FOTO) ?: continue
        val ts = System.currentTimeMillis()
        val qf = Orb.extract(g, PONTOS_FOTO, System.getenv("NIV")?.toInt() ?: Orb.LEVELS_FOTO)
        val res = Busca.procurar(lista, qf, minimo = 1)
        tempo += System.currentTimeMillis() - ts
        val ordem = res.map { it.e.nome }.distinct()
        val rk = ordem.indexOf(alvo).let { if (it < 0) 999 else it }
        if (rk == 0) { r1++; certos.add(res.first().pontos) } else { errados.add(res.firstOrNull()?.pontos ?: 0)
            System.err.println("ERRO ${a.getString(1)} alvo='$alvo' (no índice: ${alvo in nomes}) → ${res.take(3).joinToString { it.e.nome + ":" + it.pontos }}") }
        if (rk < 5) r5++
    }
    println("Kotlin manter=$manter top1 ${"%.3f".format(r1.toDouble() / q.length())} top5 ${"%.3f".format(r5.toDouble() / q.length())}  ${tempo / q.length()} ms/foto")
    println("certos ${certos.sorted()}"); println("errados ${errados.sorted()}")
}

/**
 * Monta os pacotes de um console: extrai os pontos de cada imagem e grava só eles (compactado), um
 * arquivo para as mídias (cartuchos e discos) e outro para as capas: <saida>-midia.gcix.gz e <saida>-capa.gcix.gz.
 */
fun montar(listaF: File, dir: File, saida: File, manter: Int) {
    val bits = System.getenv("BITS")?.toInt() ?: 128
    val lista = JSONArray(listaF.readText())
    val itens = (0 until lista.length()).map { lista.getJSONObject(it) }
    val t0 = System.currentTimeMillis()
    val feats = paralelo(itens) { o -> gray(File(dir, "img/" + o.getString("arquivo")), PX_INDICE)?.let { Busca.fortes(Orb.extract(it, 600), manter).truncated(bits / 64) } }
    val entradas = itens.zip(feats).mapNotNull { (o, f) ->
        if (f == null || f.n < 20) null else Entrada(o.getString("nome"), o.getString("acervo"), o.optString("regiao"), o.optString("tipo"), f)
    }
    saida.parentFile?.mkdirs()
    for ((tipo, grupo) in entradas.groupBy { it.tipo }) {
        val f = File(saida.parentFile, saida.name + "-" + tipo + ".gcix.gz")
        GZIPOutputStream(f.outputStream().buffered()).use { Pacote.write(it, grupo, bits) }
        println("${f.name}: ${grupo.size} imagens, ${f.length() / 1024} KB")
    }
    println("${(System.currentTimeMillis() - t0) / 1000}s")
}

/** Mede o acerto lendo o pacote pronto, do mesmo jeito que o app faz. */
fun testar(pacote: File, qF: File) {
    val (lista, bits) = java.util.zip.GZIPInputStream(pacote.inputStream()).use { Pacote.readWithBits(it) }
    Busca.BITS = bits
    val q = JSONArray(qF.readText()); var r1 = 0; var seguros = 0; var segurosCertos = 0; var tempo = 0L
    for (k in 0 until q.length()) {
        val a = q.getJSONArray(k); val alvo = norm(a.getString(0).replace('-', ' '))
        val g = gray(File(a.getString(2)), PX_FOTO) ?: continue
        val ts = System.currentTimeMillis()
        val res = Busca.procurar(lista, Orb.extract(g, PONTOS_FOTO, Orb.LEVELS_FOTO), finalistas = 30, minimo = 10)
        tempo += System.currentTimeMillis() - ts
        val top = res.firstOrNull()
        if (top != null && norm(top.e.nome) == alvo) r1++
        if (top != null && top.pontos >= 20) { seguros++; if (norm(top.e.nome) == alvo) segurosCertos++ }
        if (top != null) System.err.println("PONTOS ${if (norm(top.e.nome) == alvo) "certo" else "errado"} ${top.pontos} ${res.getOrNull(1)?.pontos ?: 0} ${a.getString(0)} -> ${top.e.nome}")
    }
    println("pacote ${pacote.name}: ${lista.size} imagens, $bits bits; acerto ${r1}/${q.length()}; com 20+ pontos: $segurosCertos certos de $seguros; ${tempo / q.length()} ms/foto")
}

package io.github.vprodc83.visao

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.lang.Long.bitCount
import kotlin.random.Random

/** Uma imagem conhecida (cartucho, disco ou capa) de um jogo, já reduzida aos pontos característicos. */
class Entrada(val nome: String, val acervo: String, val regiao: String, val tipo: String, val f: Features)

/** Resultado da comparação: quantos pontos da imagem conhecida aparecem na foto na mesma geometria. */
data class Achado(val e: Entrada, val pontos: Int)

/**
 * Pacote de índice: as entradas de um console. Formato binário simples:
 * "GCIX", versão, quantidade; por entrada: nome, acervo, região, tipo, n pontos, (x,y) em uint16, 4 Longs por ponto.
 */
object Pacote {
    private const val MAGIC = 0x47434958  // GCIX
    const val VERSAO = 2

    /** [bits]: 256 grava a assinatura inteira; 128 grava só a primeira metade (pacote menor). */
    fun write(out: OutputStream, list: List<Entrada>, bits: Int = 256) {
        val o = DataOutputStream(out); val longs = bits / 64
        o.writeInt(MAGIC); o.writeShort(VERSAO); o.writeShort(bits); o.writeInt(list.size)
        for (e in list) {
            o.writeUTF(e.nome); o.writeUTF(e.acervo); o.writeUTF(e.regiao); o.writeUTF(e.tipo)
            o.writeShort(e.f.n)
            for (i in 0 until e.f.n) { o.writeShort(e.f.xy[2 * i].toInt().coerceIn(0, 65535)); o.writeShort(e.f.xy[2 * i + 1].toInt().coerceIn(0, 65535)) }
            for (i in 0 until e.f.n) for (k in 0 until longs) o.writeLong(if (k < e.f.w) e.f.desc[e.f.w * i + k] else 0L)
        }
        o.flush()
    }

    /** Lê um pacote; devolve as entradas e quantos bits de assinatura ele usa. */
    fun readWithBits(inp: InputStream): Pair<List<Entrada>, Int> {
        val d = DataInputStream(inp.buffered())
        require(d.readInt() == MAGIC) { "não é um pacote de índice" }
        val v = d.readShort().toInt(); require(v <= VERSAO) { "pacote de versão nova ($v); atualize o app" }
        val bits = if (v >= 2) d.readShort().toInt() else 256; val longs = bits / 64
        val n = d.readInt(); val out = ArrayList<Entrada>(n)
        repeat(n) {
            val nome = d.readUTF(); val acervo = d.readUTF(); val reg = d.readUTF(); val tipo = d.readUTF()
            val k = d.readUnsignedShort()
            val xy = FloatArray(2 * k) { d.readUnsignedShort().toFloat() }
            val desc = LongArray(longs * k)
            for (i in 0 until k) for (j in 0 until longs) desc[longs * i + j] = d.readLong()
            out.add(Entrada(nome, acervo, reg, tipo, Features(k, xy, desc, FloatArray(k), longs)))
        }
        return out to bits
    }

    fun read(inp: InputStream): List<Entrada> = readWithBits(inp).first
}

object Busca {
    /** Um ponto da foto que combina com mais de 1/LIMITE_MOLDE das imagens conhecidas é molde. */
    var LIMITE_MOLDE = 50
    /** Distância de Hamming entre o ponto i de a e o ponto j de b, parando cedo se passar de [limite]. */
    private fun dist(a: Features, i: Int, b: Features, j: Int, limite: Int): Int {
        val x = a.desc; val y = b.desc; val ia = a.w * i; val jb = b.w * j
        var s = bitCount(x[ia] xor y[jb]); if (s >= limite) return s
        s += bitCount(x[ia + 1] xor y[jb + 1]); if (BITS == 128 || s >= limite) return s
        s += bitCount(x[ia + 2] xor y[jb + 2]); if (s >= limite) return s
        return s + bitCount(x[ia + 3] xor y[jb + 3])
    }

    /** Bits usados da assinatura (256 ou 128); com 128 o índice fica com metade do tamanho por ponto. */
    var BITS = 128
    private val maxDist get() = if (BITS == 256) 64 else 32

    /**
     * Tabela rápida dos pontos da foto: cada ponto entra em [TABELAS] tabelas, pela chave de 10 bits seguidos
     * da assinatura. Um ponto conhecido só é comparado com os da foto que têm alguma chave igual —
     * algumas dezenas em vez de todos (dezenas de vezes mais rápido, quase sem perder pares bons).
     */
    class Consulta(val f: Features) {
        val tabelas = Array(TABELAS) { IntArray(1025) }   // início de cada balde
        val itens = Array(TABELAS) { IntArray(f.n) }
        init {
            for (t in 0 until TABELAS) {
                val cont = IntArray(1025)
                for (j in 0 until f.n) cont[chave(f, j, t) + 1]++
                for (k in 1..1024) cont[k] += cont[k - 1]
                val pos = cont.copyOf()
                for (j in 0 until f.n) { val c = chave(f, j, t); itens[t][pos[c]++] = j }
                tabelas[t] = cont
            }
        }
    }
    private val TABELAS get() = if (BITS == 256) 16 else 10
    /** Chave t: 10 bits seguidos a partir do bit 16·t (cabe nos 256 ou nos 128 bits). */
    private fun chave(f: Features, j: Int, t: Int): Int {
        val bit = if (BITS == 256) t * 16 else t * 12
        val w = bit ushr 6; val o = bit and 63; val d = f.desc; val b = f.w * j
        val v = if (o <= 54) (d[b + w] ushr o) else ((d[b + w] ushr o) or (d[b + w + 1] shl (64 - o)))
        return (v and 1023L).toInt()
    }


    /**
     * Para cada ponto da imagem conhecida, o mais parecido na foto (pela tabela rápida), se for bem
     * melhor que o segundo (teste da razão). Devolve pares (ponto conhecido, ponto da foto).
     */
    fun pares(e: Features, c: Consulta, razao: Double = 0.8): IntArray {
        val q = c.f; val out = ArrayList<Int>(); val visto = IntArray(q.n); var marca = 0
        val mx = maxDist
        for (i in 0 until e.n) {
            marca++
            var b1 = 257; var b2 = 257; var j1 = -1
            for (t in 0 until TABELAS) {
                val k = chave(e, i, t); val ini = c.tabelas[t][k]; val fim = c.tabelas[t][k + 1]
                for (p in ini until fim) {
                    val j = c.itens[t][p]; if (visto[j] == marca) continue; visto[j] = marca
                    val d = dist(e, i, q, j, b2)
                    if (d < b1) { b2 = b1; b1 = d; j1 = j } else if (d < b2) b2 = d
                }
            }
            // sem segundo candidato, compara com a distância típica entre pontos diferentes
            if (b2 == 257) b2 = if (BITS == 256) 100 else 50
            if (j1 >= 0 && b1 < mx && b1 < razao * b2) { out.add(i); out.add(j1) }
        }
        return out.toIntArray()
    }

    /** Versão exata (compara com todos os pontos da foto): mais lenta, usada em testes. */
    fun pares(e: Features, q: Features, razao: Double = 0.8): IntArray = pares(e, Consulta(q), razao)

    /** Quantos pares obedecem a uma mesma perspectiva (homografia), achada por RANSAC. */
    fun geometria(e: Features, q: Features, pr: IntArray, iter: Int = 2000, erro: Double = 8.0, seed: Int = 1): Int {
        val m = pr.size / 2; if (m < 8) return 0
        val sx = DoubleArray(m) { e.xy[2 * pr[2 * it]].toDouble() }; val sy = DoubleArray(m) { e.xy[2 * pr[2 * it] + 1].toDouble() }
        val dx = DoubleArray(m) { q.xy[2 * pr[2 * it + 1]].toDouble() }; val dy = DoubleArray(m) { q.xy[2 * pr[2 * it + 1] + 1].toDouble() }
        val rnd = Random(seed); var best = 0; val e2 = erro * erro; val idx = IntArray(4)
        repeat(iter) {
            for (k in 0 until 4) { var t: Int; do { t = rnd.nextInt(m) } while ((0 until k).any { idx[it] == t }); idx[k] = t }
            val h = homografia(sx, sy, dx, dy, idx) ?: return@repeat
            // sem espelhar e sem encolher/esticar demais: evita encaixes absurdos
            val det = h[0] * h[4] - h[1] * h[3]
            if (det <= 0.01 || det > 100) return@repeat
            var inl = 0
            for (k in 0 until m) {
                val w = h[6] * sx[k] + h[7] * sy[k] + 1.0; if (w <= 1e-6) continue
                val u = (h[0] * sx[k] + h[1] * sy[k] + h[2]) / w - dx[k]; val v = (h[3] * sx[k] + h[4] * sy[k] + h[5]) / w - dy[k]
                if (u * u + v * v < e2) inl++
            }
            if (inl > best) best = inl
        }
        return best
    }

    /** Homografia por 4 pontos (sistema 8×8 por eliminação de Gauss). */
    private fun homografia(sx: DoubleArray, sy: DoubleArray, dx: DoubleArray, dy: DoubleArray, idx: IntArray): DoubleArray? {
        val a = Array(8) { DoubleArray(9) }
        for (k in 0 until 4) {
            val x = sx[idx[k]]; val y = sy[idx[k]]; val u = dx[idx[k]]; val v = dy[idx[k]]
            a[2 * k] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
            a[2 * k + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
        }
        for (c in 0 until 8) {
            var p = c; for (r in c + 1 until 8) if (kotlin.math.abs(a[r][c]) > kotlin.math.abs(a[p][c])) p = r
            if (kotlin.math.abs(a[p][c]) < 1e-9) return null
            val t = a[p]; a[p] = a[c]; a[c] = t
            for (r in 0 until 8) if (r != c) { val f = a[r][c] / a[c][c]; if (f != 0.0) for (k in c until 9) a[r][k] -= f * a[c][k] }
        }
        return DoubleArray(8) { a[it][8] / a[it][it] }
    }

    /**
     * Compara a foto com as entradas: primeiro conta pares em todas, depois confere a geometria nas [finalistas]
     * melhores. Devolve as entradas com pelo menos [minimo] pontos confirmados, da melhor para a pior.
     */
    fun procurar(lista: List<Entrada>, q: Features, finalistas: Int = 25, minimo: Int = 8, parar: () -> Boolean = { false }): List<Achado> {
        if (q.n == 0 || lista.isEmpty()) return emptyList()
        val pr = arrayOfNulls<IntArray>(lista.size); val c = Consulta(q)
        for ((i, e) in lista.withIndex()) { if (parar()) return emptyList(); pr[i] = if (e.f.n >= 8) pares(e.f, c) else IntArray(0) }
        return finalizar(lista, q, pr, finalistas, minimo)
    }

    /**
     * Pontos da foto que combinam com muitos jogos diferentes são o molde (logo do console, selo,
     * plástico): saem antes da contagem. Depois confere a geometria nas melhores.
     */
    fun finalizar(lista: List<Entrada>, q: Features, pr: Array<IntArray?>, finalistas: Int = 25, minimo: Int = 8): List<Achado> {
        val usos = IntArray(q.n)
        for (p in pr) if (p != null) { var k = 1; while (k < p.size) { usos[p[k]]++; k += 2 } }
        val limite = maxOf(4, lista.size / LIMITE_MOLDE)
        val limpos = Array(lista.size) { i ->
            val p = pr[i] ?: IntArray(0); val out = ArrayList<Int>(p.size)
            var k = 0; while (k < p.size) { if (usos[p[k + 1]] <= limite) { out.add(p[k]); out.add(p[k + 1]) }; k += 2 }
            out.toIntArray()
        }
        val top = lista.indices.sortedByDescending { limpos[it].size }.take(finalistas).filter { limpos[it].size / 2 >= minimo }
        return top.map { i -> Achado(lista[i], geometria(lista[i].f, q, limpos[i])) }
            .filter { it.pontos >= minimo }.sortedByDescending { it.pontos }
    }

    /** Pré-seleção já calculada em partes (para dividir o trabalho entre os núcleos do celular). */
    fun contar(lista: List<Entrada>, c: Consulta, de: Int, ate: Int, out: Array<IntArray?>) {
        for (i in de until ate) { val e = lista[i]; out[i] = if (e.f.n >= 8) pares(e.f, c) else IntArray(0) }
    }

    /** Os [manter] pontos mais fortes (resposta de Harris), na ordem do mais forte. */
    fun fortes(f: Features, manter: Int): Features =
        if (f.n <= manter) f.select((0 until f.n).sortedByDescending { f.resp[it] }.toIntArray())
        else f.select((0 until f.n).sortedByDescending { f.resp[it] }.take(manter).toIntArray())

    /**
     * Pontos "de molde": os que se repetem em imagens de outros jogos (logo do console, selo, plástico).
     * Ficam os [manter] mais distintivos de cada imagem; isso melhora o acerto e encolhe o índice.
     */
    fun distintivos(lista: List<Features>, nomes: List<String>, manter: Int, amostra: Int = 160, seed: Int = 1): List<Features> {
        val rnd = Random(seed)
        return lista.indices.map { i ->
            val f = lista[i]; if (f.n <= manter) return@map f
            val outros = (0 until amostra).map { rnd.nextInt(lista.size) }.distinct().filter { nomes[it] != nomes[i] && lista[it].n > 0 }
            val rep = IntArray(f.n)
            for (j in 0 until f.n) for (o in outros) {
                val g = lista[o]; var achou = false
                for (k in 0 until g.n) if (dist(f, j, g, k, 40) < 40) { achou = true; break }
                if (achou) rep[j]++
            }
            val ordem = (0 until f.n).sortedWith(compareBy<Int>({ rep[it] }, { -f.resp[it] })).take(manter).toIntArray()
            f.select(ordem)
        }
    }
}

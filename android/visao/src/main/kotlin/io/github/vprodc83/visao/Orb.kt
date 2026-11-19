package io.github.vprodc83.visao

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Imagem em tons de cinza (0..255), linha a linha. */
class Gray(val w: Int, val h: Int, val px: ByteArray) {
    fun at(x: Int, y: Int) = px[y * w + x].toInt() and 0xFF

    companion object {
        /** De pixels ARGB (Android Bitmap.getPixels ou BufferedImage.getRGB); fundo transparente vira branco. */
        fun fromArgb(argb: IntArray, w: Int, h: Int): Gray {
            val out = ByteArray(w * h)
            for (i in out.indices) {
                val c = argb[i]; val a = (c ushr 24) and 0xFF
                var r = (c shr 16) and 0xFF; var g = (c shr 8) and 0xFF; var b = c and 0xFF
                if (a < 255) { r = (r * a + 255 * (255 - a)) / 255; g = (g * a + 255 * (255 - a)) / 255; b = (b * a + 255 * (255 - a)) / 255 }
                out[i] = ((r * 77 + g * 150 + b * 29) shr 8).toByte()
            }
            return Gray(w, h, out)
        }
    }

    /** Reduz para que o lado maior tenha no máximo [max] pixels (média das áreas, sem serrilhado). */
    fun fit(max: Int): Gray {
        val s = max.toDouble() / maxOf(w, h)
        if (s >= 1.0) return this
        return resize(maxOf(1, (w * s).roundToInt()), maxOf(1, (h * s).roundToInt()), area = true)
    }

    fun resize(nw: Int, nh: Int, area: Boolean = false): Gray {
        val out = ByteArray(nw * nh)
        val sx = w.toDouble() / nw; val sy = h.toDouble() / nh
        if (area && (sx > 1.5 || sy > 1.5)) {
            for (y in 0 until nh) {
                val y0 = (y * sy).toInt(); val y1 = min(h, maxOf(y0 + 1, ((y + 1) * sy).toInt()))
                for (x in 0 until nw) {
                    val x0 = (x * sx).toInt(); val x1 = min(w, maxOf(x0 + 1, ((x + 1) * sx).toInt()))
                    var sum = 0; for (yy in y0 until y1) { val o = yy * w; for (xx in x0 until x1) sum += px[o + xx].toInt() and 0xFF }
                    out[y * nw + x] = (sum / ((y1 - y0) * (x1 - x0))).toByte()
                }
            }
            return Gray(nw, nh, out)
        }
        for (y in 0 until nh) {
            val fy = ((y + 0.5) * sy - 0.5).coerceIn(0.0, h - 1.0); val y0 = fy.toInt(); val y1 = min(h - 1, y0 + 1); val ay = fy - y0
            for (x in 0 until nw) {
                val fx = ((x + 0.5) * sx - 0.5).coerceIn(0.0, w - 1.0); val x0 = fx.toInt(); val x1 = min(w - 1, x0 + 1); val ax = fx - x0
                val v = (at(x0, y0) * (1 - ax) + at(x1, y0) * ax) * (1 - ay) + (at(x0, y1) * (1 - ax) + at(x1, y1) * ax) * ay
                out[y * nw + x] = v.roundToInt().coerceIn(0, 255).toByte()
            }
        }
        return Gray(nw, nh, out)
    }

    /** Equaliza o histograma: fotos escuras, claras ou com reflexo ficam parecidas com o scan. */
    fun equalized(): Gray {
        val hist = IntArray(256); for (b in px) hist[b.toInt() and 0xFF]++
        val lut = IntArray(256); var acc = 0; val n = px.size; var first = 0
        while (first < 256 && hist[first] == 0) first++
        val base = if (first < 256) hist[first] else 0
        for (i in 0 until 256) { acc += hist[i]; lut[i] = if (n - base <= 0) i else (((acc - base).toDouble() / (n - base)) * 255).roundToInt().coerceIn(0, 255) }
        return Gray(w, h, ByteArray(n) { lut[px[it].toInt() and 0xFF].toByte() })
    }

    /** Desfoque gaussiano separável (7×7, sigma 2), usado para comparar pixels na assinatura. */
    fun blurred(): Gray {
        val k = DoubleArray(7) { exp(-((it - 3.0).pow(2)) / (2 * 4.0)) }; val ks = k.sum()
        val ki = IntArray(7) { (k[it] / ks * 4096).roundToInt() }
        val tmp = IntArray(w * h); val out = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0; for (i in -3..3) s += ki[i + 3] * at((x + i).coerceIn(0, w - 1), y); tmp[y * w + x] = s shr 12
        }
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0; for (i in -3..3) s += ki[i + 3] * tmp[(y + i).coerceIn(0, h - 1) * w + x]; out[y * w + x] = (s shr 12).coerceIn(0, 255).toByte()
        }
        return Gray(w, h, out)
    }
}

/**
 * Pontos característicos de uma imagem: posição (na imagem de entrada), força e assinatura binária
 * de 256 bits (4 Longs) por ponto. É o "identificador lógico" da imagem: alguns kilobytes em vez de centenas.
 */
class Features(val n: Int, val xy: FloatArray, val desc: LongArray, val resp: FloatArray, val w: Int = 4) {
    /** Fica só com os pontos dos índices dados, na ordem dada (e com [longs] Longs por assinatura). */
    fun select(idx: IntArray, longs: Int = w): Features {
        val xy2 = FloatArray(idx.size * 2); val d2 = LongArray(idx.size * longs); val r2 = FloatArray(idx.size)
        idx.forEachIndexed { k, i -> xy2[2 * k] = xy[2 * i]; xy2[2 * k + 1] = xy[2 * i + 1]; for (j in 0 until longs) d2[longs * k + j] = desc[w * i + j]; r2[k] = resp[i] }
        return Features(idx.size, xy2, d2, r2, longs)
    }

    /** A mesma lista, só com os primeiros [longs] Longs de cada assinatura (128 bits = 2). */
    fun truncated(longs: Int): Features = if (longs >= w) this else select(IntArray(n) { it }, longs)

    companion object { val EMPTY = Features(0, FloatArray(0), LongArray(0), FloatArray(0)) }
}

/**
 * Extrator no estilo ORB (FAST + orientação + BRIEF girado), escrito do zero para rodar igual no
 * celular e no montador dos índices. Não depende de biblioteca nativa.
 */
object Orb {
    /** Escalas examinadas: 8 nas imagens conhecidas; na foto, mais (de perto, a etiqueta fica bem maior). */
    const val LEVELS = 8
    const val LEVELS_FOTO = 11
    private const val SCALE = 1.2
    private const val FAST_T = 10
    private const val BORDER = 20          // o padrão da assinatura cabe num círculo de raio 15 + desfoque
    private const val R = 15

    private val CIRCLE = intArrayOf(0, -3, 1, -3, 2, -2, 3, -1, 3, 0, 3, 1, 2, 2, 1, 3, 0, 3, -1, 3, -2, 2, -3, 1, -3, 0, -3, -1, -2, -2, -1, -3)

    /**
     * Os 256 pares de pontos comparados na assinatura: o padrão aprendido do ORB original
     * ("bit_pattern_31", de E. Rublee et al., ICCV 2011), copiado do OpenCV (orb.cpp):
     * Software License Agreement (BSD License). Copyright (c) 2009, Willow Garage, Inc. All rights reserved.
     * A redistribuição é permitida desde que este aviso de copyright seja mantido.
     */
    private val PATTERN: IntArray = intArrayOf(
        8,-3,9,5,4,2,7,-12,-11,9,-8,2,7,-12,12,-13,2,-13,2,12,1,-7,1,6,-2,-10,-2,-4,-13,-13,-11,-8,-13,-3,-12,-9,10,4,11,9,-13,-8,-8,-9,-11,7,-9,12,7,7,12,6,-4,-5,-3,0,-13,2,-12,-3,-9,0,-7,5,12,-6,12,-1,-3,6,-2,12,-6,-13,-4,-8,11,-13,12,-8,4,7,5,1,5,-3,10,-3,3,-7,6,12,-8,-7,-6,-2,-2,11,-1,-10,-13,12,-8,10,-7,3,-5,-3,-4,2,-3,7,-10,-12,-6,11,5,-12,6,-7,5,-6,7,-1,1,0,4,-5,9,11,11,-13,4,7,4,12,2,-1,4,4,-4,-12,-2,7,-8,-5,-7,-10,4,11,9,12,0,-8,1,-13,-13,-2,-8,2,-3,-2,-2,3,-6,9,-4,-9,8,12,10,7,0,9,1,3,7,-5,11,-10,-13,-6,-11,0,10,7,12,1,-6,-3,-6,12,10,-9,12,-4,-13,8,-8,-12,-13,0,-8,-4,3,3,7,8,5,7,10,-7,-1,7,1,-12,3,-10,5,6,2,-4,3,-10,-13,0,-13,5,-13,-7,-12,12,-13,3,-11,8,-7,12,-4,7,6,-10,12,8,-9,-1,-7,-6,-2,-5,0,12,-12,5,-7,5,3,-10,8,-13,-7,-7,-4,5,-3,-2,-1,-7,2,9,5,-11,-11,-13,-5,-13,-1,6,0,-1,5,-3,5,2,-4,-13,-4,12,-9,-6,-9,6,-12,-10,-8,-4,10,2,12,-3,7,12,12,12,-7,-13,-6,5,-4,9,-3,4,7,-1,12,2,-7,6,-5,1,-13,11,-12,5,-3,7,-2,-6,7,-8,12,-7,-13,-7,-11,-12,1,-3,12,12,2,-6,3,0,-4,3,-2,-13,-1,-13,1,9,7,1,8,-6,1,-1,3,12,9,1,12,6,-1,-9,-1,3,-13,-13,-10,5,7,7,10,12,12,-5,12,9,6,3,7,11,5,-13,6,10,2,-12,2,3,3,8,4,-6,2,6,12,-13,9,-12,10,3,-8,4,-7,9,-11,12,-4,-6,1,12,2,-8,6,-9,7,-4,2,3,3,-2,6,3,11,0,3,-3,8,-8,7,8,9,3,-11,-5,-6,-4,-10,11,-5,10,-5,-8,-3,12,-10,5,-9,0,8,-1,12,-6,4,-6,6,-11,-10,12,-8,7,4,-2,6,7,-2,0,-2,12,-5,-8,-5,2,7,-6,10,12,-9,-13,-8,-8,-5,-13,-5,-2,8,-8,9,-13,-9,-11,-9,0,1,-8,1,-2,7,-4,9,1,-2,1,-1,-4,11,-6,12,-11,-12,-9,-6,4,3,7,7,12,5,5,10,8,0,-4,2,8,-9,12,-5,-13,0,7,2,12,-1,2,1,7,5,11,7,-9,3,5,6,-8,-13,-4,-8,9,-5,9,-3,-3,-4,-7,-3,-12,6,5,8,0,-7,6,-6,12,-13,6,-5,-2,1,-10,3,10,4,1,8,-4,-2,-2,2,-13,2,-12,12,12,-2,-13,0,-6,4,1,9,3,-6,-10,-3,-5,-3,-13,-1,1,7,5,12,-11,4,-2,5,-7,-13,9,-9,-5,7,1,8,6,7,-8,7,6,-7,-4,-7,1,-8,11,-7,-8,-13,6,-12,-8,2,4,3,9,10,-5,12,3,-6,-5,-6,7,8,-3,9,-8,2,-12,2,8,-11,-2,-10,3,-12,-13,-7,-9,-11,0,-10,-5,5,-3,11,8,-2,-13,-1,12,-1,-8,0,9,-13,-11,-12,-5,-10,-2,-10,11,-3,9,-2,-13,2,-3,3,2,-9,-13,-4,0,-4,6,-3,-10,-4,12,-2,-7,-6,-11,-4,9,6,-3,6,11,-13,11,-5,5,11,11,12,6,7,-5,12,-2,-1,12,0,7,-4,-8,-3,-2,-7,1,-6,7,-13,-12,-8,-13,-7,-2,-6,-8,-8,5,-6,-9,-5,-1,-4,5,-13,7,-8,10,1,5,5,-13,1,0,10,-13,9,12,10,-1,5,-8,10,-9,-1,11,1,-13,-9,-3,-6,2,-1,-10,1,12,-13,1,-8,-10,8,-11,10,-6,2,-13,3,-6,7,-13,12,-9,-10,-10,-5,-7,-10,-8,-8,-13,4,-6,8,5,3,12,8,-13,-4,2,-3,-3,5,-13,10,-12,4,-13,5,-1,-9,9,-4,3,0,3,3,-9,-12,1,-6,1,3,2,4,-8,-10,-10,-10,9,8,-13,12,12,-8,-12,-6,-5,2,2,3,7,10,6,11,-8,6,8,8,-12,-7,10,-6,5,-3,-9,-3,9,-1,-13,-1,5,-3,-7,-3,4,-8,-2,-8,3,4,2,12,12,2,-5,3,11,6,-9,11,-13,3,-1,7,12,11,-1,12,4,-3,0,-3,6,4,-11,4,12,2,-4,2,1,-10,-6,-8,1,-13,7,-11,1,-13,12,-11,-13,6,0,11,-13,0,-1,1,4,-13,3,-9,-2,-9,8,-6,-3,-13,-6,-8,-2,5,-9,8,10,2,7,3,-9,-1,-6,-1,-1,9,5,11,-2,11,-3,12,-8,3,0,3,5,-1,4,0,10,3,-6,4,5,-13,0,-10,5,5,8,12,11,8,9,9,-6,7,-4,8,-12,-10,4,-10,9,7,3,12,4,9,-7,10,-2,7,0,12,-2,-1,-6,0,-11
    )
    private val UMAX: IntArray = IntArray(R + 1) { v -> sqrt((R * R - v * v).toDouble()).toInt() }

    fun extract(src: Gray, nFeatures: Int, levels: Int = LEVELS): Features {
        val LEVELS = levels
        val img = src.equalized()
        val f = 1 / SCALE
        val perLevel = IntArray(LEVELS); var left = nFeatures
        val first = nFeatures * (1 - f) / (1 - f.pow(LEVELS))
        for (l in 0 until LEVELS - 1) { perLevel[l] = minOf(left, (first * f.pow(l)).roundToInt()); left -= perLevel[l] }
        perLevel[LEVELS - 1] = maxOf(0, left)
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>(); val rs = ArrayList<Float>(); val ds = ArrayList<Long>()
        var level = img
        for (l in 0 until LEVELS) {
            if (l > 0) {
                val s = SCALE.pow(l); val nw = (img.w / s).roundToInt(); val nh = (img.h / s).roundToInt()
                if (nw < 2 * BORDER + 8 || nh < 2 * BORDER + 8) break
                level = img.resize(nw, nh)
            }
            val kp = detect(level, perLevel[l])
            if (kp.isEmpty()) continue
            val smooth = level.blurred(); val scale = SCALE.pow(l).toFloat()
            for (p in kp) {
                val x = p % level.w; val y = p / level.w
                val ang = orientation(level, x, y); val ca = cos(ang); val sa = sin(ang)
                val d = LongArray(4)
                for (k in 0 until 256) {
                    val px1 = PATTERN[4 * k]; val py1 = PATTERN[4 * k + 1]; val px2 = PATTERN[4 * k + 2]; val py2 = PATTERN[4 * k + 3]
                    val a = smooth.at(x + (px1 * ca - py1 * sa).roundToInt(), y + (px1 * sa + py1 * ca).roundToInt())
                    val b = smooth.at(x + (px2 * ca - py2 * sa).roundToInt(), y + (px2 * sa + py2 * ca).roundToInt())
                    if (a < b) d[k shr 6] = d[k shr 6] or (1L shl (k and 63))
                }
                xs.add(x * scale); ys.add(y * scale); rs.add(harris(level, x, y)); d.forEach { ds.add(it) }
            }
        }
        val n = xs.size
        return Features(n, FloatArray(2 * n) { if (it % 2 == 0) xs[it / 2] else ys[it / 2] }, ds.toLongArray(), rs.toFloatArray())
    }

    /** Cantos FAST-9 com supressão de não-máximos; devolve os [keep] mais fortes (resposta de Harris). */
    private fun detect(g: Gray, keep: Int): List<Int> {
        if (keep <= 0) return emptyList()
        val w = g.w; val h = g.h; val score = IntArray(w * h)
        val off = IntArray(16) { CIRCLE[2 * it + 1] * w + CIRCLE[2 * it] }
        val st = IntArray(16)
        for (y in BORDER until h - BORDER) {
            val row = y * w
            for (x in BORDER until w - BORDER) {
                val c = row + x; val v = g.px[c].toInt() and 0xFF
                val hi = v + FAST_T; val lo = v - FAST_T
                // teste rápido nos pontos 0, 4, 8, 12
                var b = 0; var d = 0
                for (i in 0 until 16 step 4) { val p = g.px[c + off[i]].toInt() and 0xFF; if (p > hi) b++ else if (p < lo) d++ }
                if (b < 2 && d < 2) continue
                for (i in 0 until 16) { val p = g.px[c + off[i]].toInt() and 0xFF; st[i] = if (p > hi) 1 else if (p < lo) -1 else 0 }
                var run = 0; var best = 0; var sign = 0
                for (i in 0 until 25) { val s = st[i % 16]; if (s != 0 && s == sign) run++ else { sign = s; run = if (s != 0) 1 else 0 }; if (run > best) best = run }
                if (best < 9) continue
                var sum = 0; for (i in 0 until 16) { val p = g.px[c + off[i]].toInt() and 0xFF; val dd = kotlin.math.abs(p - v) - FAST_T; if (dd > 0) sum += dd }
                score[c] = sum
            }
        }
        val cand = ArrayList<Int>()
        for (y in BORDER until h - BORDER) for (x in BORDER until w - BORDER) {
            val c = y * w + x; val s = score[c]; if (s == 0) continue
            if (s >= score[c - 1] && s > score[c + 1] && s >= score[c - w] && s > score[c + w] && s >= score[c - w - 1] && s > score[c + w + 1] && s >= score[c - w + 1] && s > score[c + w - 1]) cand.add(c)
        }
        if (cand.size <= keep) return cand
        val hr = FloatArray(cand.size) { harris(g, cand[it] % w, cand[it] / w) }
        return cand.indices.sortedByDescending { hr[it] }.take(keep).map { cand[it] }
    }

    /** Resposta de Harris num bloco 7×7: separa cantos verdadeiros de bordas retas. */
    private fun harris(g: Gray, x: Int, y: Int): Float {
        var a = 0.0; var b = 0.0; var c = 0.0
        for (dy in -3..3) for (dx in -3..3) {
            val xx = x + dx; val yy = y + dy
            val ix = (g.at(xx + 1, yy - 1) + 2 * g.at(xx + 1, yy) + g.at(xx + 1, yy + 1)) - (g.at(xx - 1, yy - 1) + 2 * g.at(xx - 1, yy) + g.at(xx - 1, yy + 1))
            val iy = (g.at(xx - 1, yy + 1) + 2 * g.at(xx, yy + 1) + g.at(xx + 1, yy + 1)) - (g.at(xx - 1, yy - 1) + 2 * g.at(xx, yy - 1) + g.at(xx + 1, yy - 1))
            a += ix * ix; b += iy * iy; c += ix * iy
        }
        return ((a * b - c * c) - 0.04 * (a + b) * (a + b)).toFloat() / 1e8f
    }

    /** Direção do ponto pelo centro de massa da intensidade (assim a foto pode estar girada). */
    private fun orientation(g: Gray, x: Int, y: Int): Double {
        var m01 = 0L; var m10 = 0L
        for (u in -R..R) m10 += u.toLong() * g.at(x + u, y)
        for (v in 1..R) {
            var vSum = 0L; val d = UMAX[v]
            for (u in -d..d) { val p = g.at(x + u, y + v); val m = g.at(x + u, y - v); vSum += (p - m); m10 += u.toLong() * (p + m) }
            m01 += v * vSum
        }
        return atan2(m01.toDouble(), m10.toDouble())
    }
}

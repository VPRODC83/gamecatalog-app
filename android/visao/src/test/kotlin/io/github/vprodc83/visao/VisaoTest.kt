package io.github.vprodc83.visao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

class VisaoTest {
    /** "Arte" de etiqueta sintética: retângulos, círculos e ruído com semente própria. */
    private fun arte(seed: Int, w: Int = 420, h: Int = 300): Gray {
        val r = Random(seed); val px = ByteArray(w * h) { (r.nextInt(30) + 100).toByte() }
        repeat(60) {
            val x0 = r.nextInt(w - 40); val y0 = r.nextInt(h - 40); val ww = 10 + r.nextInt(80); val hh = 10 + r.nextInt(60); val v = r.nextInt(256)
            val circ = r.nextBoolean()
            for (y in y0 until minOf(h, y0 + hh)) for (x in x0 until minOf(w, x0 + ww)) {
                if (circ) { val dx = (x - x0 - ww / 2.0) / (ww / 2.0); val dy = (y - y0 - hh / 2.0) / (hh / 2.0); if (dx * dx + dy * dy > 1) continue }
                px[y * w + x] = v.toByte()
            }
        }
        return Gray(w, h, px)
    }

    /** Simula uma foto: gira, muda a escala, escurece e põe numa moldura maior com fundo. */
    private fun foto(g: Gray, ang: Double, esc: Double, luz: Double): Gray {
        val W = (g.w * esc * 1.5).roundToInt(); val H = (g.h * esc * 1.5).roundToInt(); val out = ByteArray(W * H) { 60 }
        val c = cos(ang); val s = sin(ang)
        for (y in 0 until H) for (x in 0 until W) {
            val ux = (x - W / 2.0) / esc; val uy = (y - H / 2.0) / esc
            val sx = (ux * c + uy * s + g.w / 2.0).roundToInt(); val sy = (-ux * s + uy * c + g.h / 2.0).roundToInt()
            if (sx in 0 until g.w && sy in 0 until g.h) out[y * W + x] = (g.at(sx, sy) * luz).roundToInt().coerceIn(0, 255).toByte()
        }
        return Gray(W, H, out)
    }

    @Test fun reconheceAMesmaArteGiradaEEscurecida() {
        val lista = (1..40).map { i -> Entrada("jogo $i", "X", "", "midia", Busca.fortes(Orb.extract(arte(i), 600), 300)) }
        val q = Orb.extract(foto(arte(17), 0.35, 1.4, 0.7), 1500)
        val res = Busca.procurar(lista, q, minimo = 8)
        assertTrue("achou algo", res.isNotEmpty())
        assertEquals("jogo 17", res.first().e.nome)
        assertTrue("pontos ${res.first().pontos}", res.first().pontos >= 20)
        assertTrue("segundo bem abaixo", res.getOrNull(1)?.pontos ?: 0 < res.first().pontos / 2)
    }

    @Test fun pacoteGravaELeIgual() {
        val e = Entrada("Super Mario World", "Nintendo_-_Super_Nintendo_Entertainment_System", "US", "midia", Busca.fortes(Orb.extract(arte(3), 600), 300))
        val b = ByteArrayOutputStream(); Pacote.write(b, listOf(e))
        val (lido, bits) = Pacote.readWithBits(ByteArrayInputStream(b.toByteArray()))
        assertEquals(256, bits); assertEquals(1, lido.size)
        val l = lido.first(); assertEquals(e.nome, l.nome); assertEquals(e.acervo, l.acervo); assertEquals(e.f.n, l.f.n)
        assertTrue(e.f.desc.contentEquals(l.f.desc))
        assertTrue("~36 bytes por ponto", b.size() < e.f.n * 37 + 200)
    }
}

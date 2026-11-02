package io.github.vprodc83.gamecatalog.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp

private val Brand = Color(0xFF4A3FB0)
private val BrandLight = Color(0xFF5A4FD0)
private val BrandDeep = Color(0xFF2E2680)
private val Lilac = Color(0xFFD9D5FF)

/** Duração total da abertura (formato aprovado: 5 segundos). */
const val SPLASH_MS = 5000L

/**
 * Abertura animada (formato aprovado em docs/abertura-previa.html): o cartucho cai, a tela dele liga
 * com o som do app, o nome aparece letra por letra, depois a versão, a frase e o rodapé.
 */
@Composable
fun SplashScreen(version: String, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val drop = remember { Animatable(0f) }
    val power = remember { Animatable(0f) }
    val letters = remember { Animatable(0f) }
    val ver = remember { Animatable(0f) }
    val tag = remember { Animatable(0f) }
    val foot = remember { Animatable(0f) }
    val bar = remember { Animatable(0f) }
    val title = "Game Catalog"
    LaunchedEffect(Unit) {
        launch { delay(200); drop.animateTo(1f, tween(800, easing = CubicBezierEasing(.2f, 1.5f, .4f, 1f))) }
        launch { delay(1100); launch(Dispatchers.Default) { Chime.play(ctx) }; power.animateTo(1f, tween(500, easing = LinearEasing)) }
        launch { delay(1700); letters.animateTo(title.length.toFloat() + 1f, tween(60 * title.length + 300, easing = LinearEasing)) }
        launch { delay(2600); ver.animateTo(1f, tween(400)) }
        launch { delay(2900); tag.animateTo(1f, tween(400)) }
        launch { delay(3200); foot.animateTo(1f, tween(400)) }
        launch { delay(3400); bar.animateTo(1f, tween(1600)) }
        delay(SPLASH_MS); onDone()
    }
    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(BrandLight, Brand, BrandDeep), radius = 1600f))) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.78f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            // logo: cartucho com a tela que "liga" (piscada: 0 → 1 → 0,4 → 1)
            val p = power.value
            val glow = when { p < .4f -> p / .4f; p < .55f -> 1f - (p - .4f) / .15f * .6f; else -> .4f + (p - .55f) / .45f * .6f }
            Canvas(Modifier.width(150.dp).height(187.dp).graphicsLayer {
                val d = drop.value; alpha = d.coerceIn(0f, 1f); translationY = (1f - d) * -180f; scaleX = .6f + .4f * d; scaleY = .6f + .4f * d
            }) {
                val u = size.width / 120f
                drawRoundRect(Color(0xFFF6F7F9), Offset(6 * u, 4 * u), Size(108 * u, 142 * u), CornerRadius(16 * u))
                drawRoundRect(Color(0xFF1D2027), Offset(24 * u, 22 * u), Size(72 * u, 58 * u), CornerRadius(4 * u))
                drawRoundRect(Color(0xFF8F84FF).copy(alpha = glow.coerceIn(0f, 1f)), Offset(32 * u, 30 * u), Size(56 * u, 42 * u), CornerRadius(2 * u))
                for (i in 0 until 5) drawRoundRect(Color(0xFFC9CDD5), Offset((26 + 15 * i) * u, 104 * u), Size(10 * u, 26 * u), CornerRadius(u))
            }
            androidx.compose.foundation.layout.Spacer(Modifier.height(24.dp))
            Row {
                title.forEachIndexed { i, c ->
                    val a = (letters.value - i).coerceIn(0f, 1f)
                    Text(c.toString(), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.alpha(a).graphicsLayer { translationY = (1f - a) * 30f })
                }
            }
            Text(tr("versão {0}", version), color = Lilac, fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.alpha(ver.value).padding(top = 4.dp))
            Text(tr("Sua coleção de videogames"), color = Lilac, fontSize = 13.sp, modifier = Modifier.alpha(tag.value).padding(top = 18.dp))
        }
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 28.dp).alpha(foot.value), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("VPRODC83", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text("Claude Opus 5.5", color = Lilac, fontSize = 12.sp)
            Text(tr("© 2026 VPRODC83. Todos os direitos reservados."), color = Lilac, fontSize = 12.sp, textAlign = TextAlign.Center)
            Box(Modifier.padding(top = 14.dp).width(120.dp).height(3.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = .18f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(bar.value).background(Color.White))
            }
        }
    }
}

/** Som original do app: três notas subindo (dó, mi, si), timbre triangular, gerado na hora. */
object Chime {
    fun play(ctx: Context) {
        runCatching {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return  // modo silencioso ou vibrar: sem som
            val rate = 44100
            val total = (rate * 1.4).toInt()
            val buf = ShortArray(total)
            val notes = listOf(Triple(523.25, 0.0, 0.35), Triple(659.25, 0.11, 0.35), Triple(987.77, 0.22, 1.1))
            for ((f, start, len) in notes) {
                val s0 = (start * rate).toInt(); val n = (len * rate).toInt()
                for (k in 0 until n) {
                    val i = s0 + k; if (i >= total) break
                    val t = k.toDouble() / rate
                    val ph = (t * f) % 1.0
                    val tri = 4 * abs(ph - 0.5) - 1          // onda triangular
                    val attack = (t / 0.015).coerceAtMost(1.0)
                    val env = attack * exp(-t * (6.9 / len))   // cai até ~0,1% no fim da nota
                    buf[i] = (buf[i] + tri * env * 0.22 * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()
                }
            }
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(total * 2).build()
            track.write(buf, 0, total); track.play()
            Thread.sleep(1500); track.release()
        }
    }
}


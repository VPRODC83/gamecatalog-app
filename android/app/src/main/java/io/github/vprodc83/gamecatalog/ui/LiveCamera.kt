package io.github.vprodc83.gamecatalog.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import io.github.vprodc83.gamecatalog.core.Catalog
import io.github.vprodc83.gamecatalog.core.Identify
import io.github.vprodc83.gamecatalog.data.Item
import io.github.vprodc83.gamecatalog.data.Lista
import java.util.concurrent.Executors

/** Um jogo reconhecido na imagem ao vivo, com a posição (em pixels da tela) e há quanto tempo foi visto. */
private data class LiveHit(val titulo: String, val repo: String, val x: Float, val y: Float, val seen: Long, val item: Item?, val lista: Lista?)

/**
 * Câmera ao vivo (melhoria 14): aponte para a prateleira; cada jogo reconhecido ganha um selo sobre a imagem
 * (✔ já tenho, ★ procurado, 🛒 comprado, + não tenho). Tocar no selo abre o jogo ou leva para a identificação.
 * A leitura é feita no celular, sem internet.
 */
@Composable
fun LiveCameraDialog(m: AppModel, onOpen: (Item) -> Unit, onPick: (Cand) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; if (!it) onClose() }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (granted) LiveView(m, onOpen = { onClose(); onOpen(it) }, onPick = { onClose(); onPick(it) })
            FilledTonalButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp)) { Text(tr("‹ Voltar")) }
            Text(tr("Aponte para as etiquetas ou lombadas. Toque num selo para abrir o jogo."), color = Color.White.copy(alpha = .85f), style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp).background(Color.Black.copy(alpha = .45f), RoundedCornerShape(8.dp)).padding(8.dp))
        }
    }
}

@Composable
private fun LiveView(m: AppModel, onOpen: (Item) -> Unit, onPick: (Cand) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val density = LocalDensity.current
    var hits by remember { mutableStateOf<List<LiveHit>>(emptyList()) }
    var viewW by remember { mutableStateOf(1f) }; var viewH by remember { mutableStateOf(1f) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    val controller = remember { LifecycleCameraController(ctx) }
    var last by remember { mutableStateOf(0L) }

    DisposableEffect(owner) {
        val cat: Catalog? = m.catalog
        val repos = m.repo.state.value.consoles.map { m.repo.platformOf(it) }.filter { it.isNotEmpty() }.distinct()
        controller.setImageAnalysisAnalyzer(executor, MlKitAnalyzer(listOf(recognizer), CameraController.COORDINATE_SYSTEM_VIEW_REFERENCED, executor) { result ->
            val now = System.currentTimeMillis()
            if (cat == null || now - last < 600) return@MlKitAnalyzer
            last = now
            val text = result.getValue(recognizer) ?: return@MlKitAnalyzer
            val blocks = text.textBlocks.map { b -> val r = b.boundingBox; Identify.Block(b.text, (r?.left ?: 0) / viewW, (r?.top ?: 0) / viewH, (r?.right ?: 0) / viewW, (r?.bottom ?: 0) / viewH) }
            val fresh = ArrayList<LiveHit>()
            // código impresso (exato) e nome lido na etiqueta
            m.serials?.find(text.text)?.forEach { h ->
                val b = text.textBlocks.firstOrNull { it.text.uppercase().contains(h.code.substringBefore('-')) }?.boundingBox
                fresh.add(hit(m, Catalog.cleanTitle(h.nome), h.repo, (b?.exactCenterX() ?: viewW / 2), (b?.top?.toFloat() ?: viewH / 3), now))
            }
            runCatching { Identify.find(cat, blocks, text.text, repos) }.getOrDefault(emptyList()).forEach { f ->
                val b = f.block
                fresh.add(hit(m, f.match.tituloLimpo, f.match.repo, ((b?.left ?: .4f) + (b?.right ?: .6f)) / 2 * viewW, (b?.top ?: .3f) * viewH, now))
            }
            // mantém por 1,5 s o que sumiu de vista, para os selos não piscarem
            val keep = hits.filter { old -> now - old.seen < 1500 && fresh.none { it.titulo == old.titulo && it.repo == old.repo } }
            hits = (fresh.distinctBy { it.repo + it.titulo } + keep).take(12)
        })
        controller.bindToLifecycle(owner)
        onDispose { controller.unbind(); controller.clearImageAnalysisAnalyzer(); executor.shutdown(); recognizer.close() }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { c -> PreviewView(c).apply { this.controller = controller; scaleType = PreviewView.ScaleType.FILL_CENTER } },
            modifier = Modifier.fillMaxSize(), update = { v -> viewW = v.width.toFloat().coerceAtLeast(1f); viewH = v.height.toFloat().coerceAtLeast(1f) })
        hits.forEach { h ->
            val (txt, cor) = when (h.lista) {
                Lista.COLECAO -> "✔ " + tr("Já tenho") to Ok
                Lista.PROCURADO -> "★ " + tr("Procurado") to Info
                Lista.COMPRADO -> "🛒 " + tr("Comprado") to Warn
                null -> "+ " + tr("Não tenho") to MaterialTheme.colorScheme.primary
            }
            Column(Modifier.offset { IntOffset((h.x - with(density) { 80.dp.toPx() }).toInt().coerceAtLeast(0), (h.y - with(density) { 52.dp.toPx() }).toInt().coerceAtLeast(0)) }
                .widthIn(max = 180.dp).background(cor, RoundedCornerShape(10.dp))
                .clickable { if (h.item != null) onOpen(h.item) else onPick(Cand(h.titulo, consoleFor(m, h.repo), m.catalog?.label(h.repo) ?: "", h.repo, origem = "etiqueta")) }
                .padding(horizontal = 10.dp, vertical = 6.dp)) {
                Text(txt, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                Text(h.titulo, color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun hit(m: AppModel, titulo: String, repo: String, x: Float, y: Float, now: Long): LiveHit {
    val console = consoleFor(m, repo)
    val f = m.repo.find(titulo, console.ifBlank { null })
    val best = f.firstOrNull { it.item.lista == Lista.COLECAO } ?: f.firstOrNull { it.item.lista == Lista.COMPRADO } ?: f.firstOrNull()
    return LiveHit(titulo, repo, x, y, now, best?.item, best?.item?.lista)
}

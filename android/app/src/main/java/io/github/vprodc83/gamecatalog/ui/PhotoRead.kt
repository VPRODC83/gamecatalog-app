package io.github.vprodc83.gamecatalog.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import io.github.vprodc83.gamecatalog.core.Identify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

/** Uma palavra lida na foto, com a posição relativa (0 a 1) na imagem em pé. */
data class Word(val text: String, val l: Float, val t: Float, val r: Float, val b: Float)

/** Tudo o que o celular leu numa foto: blocos de texto, palavras, códigos de barras. */
class Reading(val bitmap: Bitmap, val blocks: List<Identify.Block>, val words: List<Word>, val text: String, val barcodes: List<String>)

/** Abre a foto já girada como foi tirada (EXIF) e reduzida para no máximo [max] pixels no lado maior. */
fun decodeUpright(f: File, max: Int = 2048): Bitmap {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(f.path, o)
    // reduz já na leitura (fotos de 50 MP ocupariam centenas de MB e encerrariam o app por falta de memória)
    var s = 1; while (maxOf(o.outWidth, o.outHeight) / s > max) s *= 2
    val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s }) ?: throw IllegalStateException("imagem ilegível")
    val rot = runCatching { when (ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f; ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f } }.getOrDefault(0f)
    val scale = minOf(1f, max.toFloat() / maxOf(bmp.width, bmp.height))
    if (rot == 0f && scale == 1f) return bmp
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot); postScale(scale, scale) }, true)
}

/** Lê texto e códigos de barras da foto, tudo no próprio celular (ML Kit). */
suspend fun readPhoto(f: File): Reading {
    val bmp = withContext(Dispatchers.IO) { decodeUpright(f) }
    val img = InputImage.fromBitmap(bmp, 0)
    val w = bmp.width.toFloat(); val h = bmp.height.toFloat()
    val res = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(img).await()
    val blocks = res.textBlocks.map { b -> val r = b.boundingBox; Identify.Block(b.text, (r?.left ?: 0) / w, (r?.top ?: 0) / h, (r?.right ?: 0) / w, (r?.bottom ?: 0) / h) }
    val words = res.textBlocks.flatMap { it.lines }.flatMap { it.elements }.mapNotNull { e -> e.boundingBox?.let { r -> Word(e.text, r.left / w, r.top / h, r.right / w, r.bottom / h) } }
    val codes = runCatching {
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_UPC_A, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_E).build())
            .process(img).await().mapNotNull { it.rawValue?.filter(Char::isDigit)?.takeIf { v -> v.length >= 8 } }.distinct()
    }.getOrDefault(emptyList())
    return Reading(bmp, blocks, words, res.text, codes)
}

/** Recorte da foto em volta de um bloco de texto (etiqueta → cartucho), para usar como capa provisória. */
fun cropAround(bmp: Bitmap, b: Identify.Block?): Bitmap {
    if (b == null) return bmp
    val cx = (b.left + b.right) / 2; val cy = (b.top + b.bottom) / 2
    val bw = maxOf(b.right - b.left, 0.2f) * 1.8f; val bh = maxOf(b.bottom - b.top, 0.1f) * 3.5f
    val l = ((cx - bw / 2).coerceIn(0f, 1f) * bmp.width).toInt(); val t = ((cy - bh / 2).coerceIn(0f, 1f) * bmp.height).toInt()
    val r = ((cx + bw / 2).coerceIn(0f, 1f) * bmp.width).toInt(); val bo = ((cy + bh / 2).coerceIn(0f, 1f) * bmp.height).toInt()
    return if (r - l < 20 || bo - t < 20) bmp else Bitmap.createBitmap(bmp, l, t, r - l, bo - t)
}

fun saveJpeg(bmp: Bitmap, f: File): File { f.parentFile?.mkdirs(); f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }; return f }

/**
 * A foto com uma moldura em cada palavra lida (melhoria 13). Tocar numa palavra a marca/desmarca;
 * as marcadas, na ordem de leitura, viram a busca.
 */
@Composable
fun WordPhoto(r: Reading, selected: Set<Int>, onToggle: (Int) -> Unit, modifier: Modifier = Modifier) {
    val ratio = r.bitmap.width.toFloat() / r.bitmap.height
    Box(modifier.fillMaxWidth().aspectRatio(ratio.coerceIn(0.4f, 2.5f))) {
        Image(r.bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize().pointerInput(r) {
            detectTapGestures { p ->
                val x = p.x / size.width; val y = p.y / size.height
                val i = r.words.indexOfFirst { w -> x >= w.l - 0.01f && x <= w.r + 0.01f && y >= w.t - 0.01f && y <= w.b + 0.01f }
                if (i >= 0) onToggle(i)
            }
        }) {
            r.words.forEachIndexed { i, w ->
                val on = i in selected
                val tl = Offset(w.l * size.width, w.t * size.height); val sz = Size((w.r - w.l) * size.width, (w.b - w.t) * size.height)
                if (on) drawRoundRect(Color(0x664A3FB0), tl, sz, CornerRadius(4f))
                drawRoundRect(if (on) Color(0xFF8F84FF) else Color(0xCCFFFFFF), tl, sz, CornerRadius(4f), style = Stroke(if (on) 4f else 2f))
            }
        }
    }
}

/**
 * Tira uma foto pelo app de câmera do celular. Como o app declara a permissão de câmera (por causa da câmera ao vivo),
 * o Android exige que ela esteja concedida até para usar o app de câmera; sem isso ele encerra o app.
 * Por isso a permissão é pedida antes, e qualquer falha vira um aviso em vez de travar.
 */
@Composable
fun rememberTakePicture(onShot: (File) -> Unit): (File) -> Unit {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val path = androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val take = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.TakePicture()) { ok ->
        val f = path.value?.let { File(it) }
        if (ok && f != null && f.length() > 0) onShot(f)
    }
    fun go(f: File) {
        runCatching { take.launch(fileUri(ctx, f)) }.onFailure {
            android.widget.Toast.makeText(ctx, tr("Não consegui abrir a câmera deste celular."), android.widget.Toast.LENGTH_LONG).show()
        }
    }
    val perm = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        val f = path.value?.let { File(it) } ?: return@rememberLauncherForActivityResult
        if (ok) go(f) else android.widget.Toast.makeText(ctx, tr("Sem permissão da câmera. Libere nas configurações do Android para fotografar."), android.widget.Toast.LENGTH_LONG).show()
    }
    return { f ->
        f.parentFile?.mkdirs(); path.value = f.path
        if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) go(f)
        else runCatching { perm.launch(android.Manifest.permission.CAMERA) }
    }
}

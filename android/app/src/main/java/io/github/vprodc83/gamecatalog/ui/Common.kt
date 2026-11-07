package io.github.vprodc83.gamecatalog.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import androidx.core.content.FileProvider
import java.io.File

val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF4A3FB0), onPrimary = Color.White, background = Color(0xFFE6E8EC), surface = Color(0xFFF6F7F9),
    surfaceVariant = Color(0xFFFFFDF6), onSurface = Color(0xFF1D2027), onSurfaceVariant = Color(0xFF5D6472), error = Color(0xFFB3261E), outline = Color(0xFFC9CDD5)
)
val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF8F84FF), onPrimary = Color(0xFF14161B), background = Color(0xFF14161B), surface = Color(0xFF1D2028),
    surfaceVariant = Color(0xFF23262F), onSurface = Color(0xFFE8EAF0), onSurfaceVariant = Color(0xFF9AA1B0), error = Color(0xFFFF7A70), outline = Color(0xFF2F3440)
)
val Ok = Color(0xFF1F7A4D); val Warn = Color(0xFF9A6A00); val Info = Color(0xFF1D5FA8)

@Composable fun Hint(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable fun SectionTitle(text: String) =
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.2.sp)

@Composable fun Pill(text: String, color: Color) =
    Text(text, modifier = Modifier.border(1.dp, color, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp),
        color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)

/** Seletor simples (botão que abre a lista de opções). */
@Composable
fun <T> Picker(label: String, value: T, options: List<Pair<T, String>>, modifier: Modifier = Modifier, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(options.firstOrNull { it.first == value }?.second ?: "—", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (v, t) -> DropdownMenuItem(text = { Text(t) }, onClick = { open = false; onPick(v) }) }
        }
    }
}

/**
 * Capa (ou foto). Sem imagem, desenha uma capa provisória (melhoria 20): faixa com o console na cor dele
 * e o nome do jogo no meio, para nenhuma lista ficar com quadros em branco. [title] = nome do jogo.
 */
@Composable
fun Cover(model: Any?, fallback: String, modifier: Modifier = Modifier, title: String? = null) {
    Box(modifier.aspectRatio(3f / 4f).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surface)
        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
        if (title.isNullOrBlank()) {
            // o nome do console diminui até caber inteiro (ex.: SUPER FAMICOM numa miniatura)
            androidx.compose.foundation.text.BasicText(fallback, modifier = Modifier.padding(6.dp),
                style = MaterialTheme.typography.labelSmall.copy(textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant),
                softWrap = true, maxLines = fallback.split(" ").size.coerceIn(1, 3),
                autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 6.sp, maxFontSize = 11.sp, stepSize = 0.5.sp))
        } else PlaceholderCover(fallback, title)
        if (model != null && model != "") AsyncImage(model = model, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
    }
}

/** Cor de cada console (mesma cor sempre). */
fun consoleColor(console: String): Color {
    val palette = listOf(0xFF4A3FB0, 0xFFB3261E, 0xFF1D5FA8, 0xFF1F7A4D, 0xFF9A6A00, 0xFF6D2E8C, 0xFF00796B, 0xFFC2185B, 0xFF37474F, 0xFF5D4037)
    val n = io.github.vprodc83.gamecatalog.core.Text.norm(console)
    val fixed = mapOf("SNES" to 0, "SFC" to 0, "NES" to 1, "FAMICOM" to 1, "MD" to 8, "MS" to 2, "N64" to 3, "PS1" to 8, "PS2" to 2, "PS3" to 8, "PS4" to 2, "PS5" to 2,
        "XBOX" to 3, "X360" to 3, "XONE" to 3, "NSW" to 1, "NSW2" to 1, "GAMEBOY" to 4, "ODYSSEY" to 9, "SATURN" to 8, "DC" to 6)
    return Color(palette[fixed[n] ?: (n.hashCode() and 0x7fffffff) % palette.size])
}

@Composable
private fun PlaceholderCover(console: String, title: String) {
    val c = consoleColor(console)
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(c.copy(alpha = .85f), c)))) {
        Box(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = .28f)).padding(horizontal = 4.dp, vertical = 3.dp), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.text.BasicText(console, style = MaterialTheme.typography.labelSmall.copy(color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                maxLines = 1, softWrap = false, autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 4.sp, maxFontSize = 10.sp, stepSize = 0.5.sp))
        }
        Box(Modifier.fillMaxWidth().weight(1f).padding(5.dp), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.text.BasicText(title, style = MaterialTheme.typography.titleSmall.copy(color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                maxLines = 5, autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 6.sp, maxFontSize = 16.sp, stepSize = 0.5.sp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun Chips(content: @Composable () -> Unit) =
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) { content() }

fun openUrl(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

fun fileUri(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fotos", f)

/**
 * Abre o Google Lens com a foto (pelo "Compartilhar" do Android). Se o app do Google não estiver
 * instalado, mostra a lista de apps que aceitam a imagem.
 */
fun openLens(ctx: Context, uri: Uri): Boolean {
    val send = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    return try { ctx.startActivity(Intent(send).setPackage("com.google.android.googlequicksearchbox")); true }
    catch (_: ActivityNotFoundException) { runCatching { ctx.startActivity(Intent.createChooser(send, tr("Pesquisar a imagem com")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess }
}

fun brl(v: Double?): String = if (v == null) "—" else java.text.NumberFormat.getCurrencyInstance(java.util.Locale("pt", "BR")).format(v)
fun usd(v: Double?): String = if (v == null) "—" else "US$ " + String.format(java.util.Locale("pt", "BR"), "%.2f", v)

/** Nome por extenso dos códigos de console e abas, só para mostrar (a planilha continua com o código). */
private val consoleNames = mapOf("SFC" to "SUPER FAMICOM", "FC" to "FAMICOM")
fun cn(s: String?): String = s.orEmpty().let { consoleNames[it.trim().uppercase()] ?: it }

@Composable fun Spacer8() = androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))

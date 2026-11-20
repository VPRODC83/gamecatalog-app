package io.github.vprodc83.gamecatalog.ui

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.vprodc83.gamecatalog.R

/** Os visuais do app, escolhidos em Ajustes → Visual do app. */
enum class Visual(val chave: String, val titulo: String, val descricao: String) {
    ATUAL("atual", "Atual", "O visual de sempre, claro ou escuro como o celular."),
    CONSOLE("console16", "Console 16-bit", "Plástico cinza, botões em relevo e títulos em pixel."),
    CRT("crt", "Fliperama CRT", "Tela escura de fósforo verde, com linhas de varredura."),
    REVISTA("revista", "Revista anos 90", "Papel com retícula, letras grossas e sombras duras.");

    companion object { fun de(chave: String?) = entries.firstOrNull { it.chave == chave } ?: ATUAL }
}

enum class Textura { NENHUMA, LISTRAS, RETICULA, VARREDURA }

/** Tudo o que muda de um visual para outro, além das cores e fontes do Material. */
class Estilo(
    val cores: ColorScheme,
    val tipografia: Typography,
    val canto: Shape,              // cartões
    val cantoBotao: Shape,
    val borda: Dp,                 // espessura do contorno de cartões e botões (0 = sem)
    val corBorda: Color,
    val sombra: Dp,                // sombra dura (deslocada, sem desfoque); 0 = sem
    val sombraSoEmbaixo: Boolean,  // "relevo" de plástico: só para baixo
    val corSombra: Color,
    val textura: Textura,
    val corTextura: Color,
    val ok: Color, val warn: Color, val info: Color, val outra: Color,
)

/** O visual escolhido (lido e salvo nas preferências "interface"). */
object VisualAtual {
    var visual by mutableStateOf(Visual.ATUAL)
    /** Modo escuro do celular (só vale para o visual Atual; os retrô têm um tema fixo). */
    var escuro by mutableStateOf(false)

    fun carregar(ctx: Context) { visual = Visual.de(ctx.getSharedPreferences("interface", Context.MODE_PRIVATE).getString("visual", null)) }
    fun escolher(ctx: Context, v: Visual) { visual = v; ctx.getSharedPreferences("interface", Context.MODE_PRIVATE).edit().putString("visual", v.chave).apply() }
    val estilo: Estilo get() = estiloDe(visual, escuro)
}

// ---------------- fontes (OFL, em res/font; licenças em assets/licencas) ----------------

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun variavel(res: Int, peso: Int) = Font(res, FontWeight(peso), variationSettings = FontVariation.Settings(FontVariation.weight(peso)))
private val Pixel = FontFamily(variavel(R.font.pixelify_sans, 500), variavel(R.font.pixelify_sans, 600), variavel(R.font.pixelify_sans, 700))
private val Fosforo = FontFamily(Font(R.font.vt323))
private val Manchete = FontFamily(Font(R.font.bungee))
private val Revista = FontFamily(variavel(R.font.rubik, 400), variavel(R.font.rubik, 500), variavel(R.font.rubik, 700), variavel(R.font.rubik, 800))

private fun Typography.mapa(titulo: (TextStyle) -> TextStyle, corpo: (TextStyle) -> TextStyle) = Typography(
    displayLarge = titulo(displayLarge), displayMedium = titulo(displayMedium), displaySmall = titulo(displaySmall),
    headlineLarge = titulo(headlineLarge), headlineMedium = titulo(headlineMedium), headlineSmall = titulo(headlineSmall),
    titleLarge = titulo(titleLarge), titleMedium = titulo(titleMedium), titleSmall = titulo(titleSmall),
    bodyLarge = corpo(bodyLarge), bodyMedium = corpo(bodyMedium), bodySmall = corpo(bodySmall),
    labelLarge = corpo(labelLarge), labelMedium = corpo(labelMedium), labelSmall = corpo(labelSmall),
)

private fun TextStyle.maior(f: Float) = copy(fontSize = fontSize * f, lineHeight = if (lineHeight.isSpecified()) lineHeight * f else lineHeight)
private fun androidx.compose.ui.unit.TextUnit.isSpecified() = this != androidx.compose.ui.unit.TextUnit.Unspecified

private val base = Typography()

// ---------------- os quatro estilos ----------------

val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF4A3FB0), onPrimary = Color.White, background = Color(0xFFE6E8EC), surface = Color(0xFFF6F7F9),
    surfaceVariant = Color(0xFFFFFDF6), onSurface = Color(0xFF1D2027), onSurfaceVariant = Color(0xFF5D6472), error = Color(0xFFB3261E), outline = Color(0xFFC9CDD5)
)
val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF8F84FF), onPrimary = Color(0xFF14161B), background = Color(0xFF14161B), surface = Color(0xFF1D2028),
    surfaceVariant = Color(0xFF23262F), onSurface = Color(0xFFE8EAF0), onSurfaceVariant = Color(0xFF9AA1B0), error = Color(0xFFFF7A70), outline = Color(0xFF2F3440)
)

private val atualClaro = Estilo(LightColors, base, RoundedCornerShape(12.dp), RoundedCornerShape(50), 0.dp, Color.Transparent, 0.dp, false, Color.Transparent,
    Textura.NENHUMA, Color.Transparent, Color(0xFF1F7A4D), Color(0xFF9A6A00), Color(0xFF1D5FA8), Color(0xFF00796B))
private val atualEscuro = Estilo(DarkColors, base, RoundedCornerShape(12.dp), RoundedCornerShape(50), 0.dp, Color.Transparent, 0.dp, false, Color.Transparent,
    Textura.NENHUMA, Color.Transparent, Color(0xFF1F7A4D), Color(0xFF9A6A00), Color(0xFF1D5FA8), Color(0xFF00796B))

private val console16: Estilo by lazy {
    val tinta = Color(0xFF1F1D22); val plastico = Color(0xFFECEBE7)
    Estilo(
        lightColorScheme(
            primary = Color(0xFF4B3FA0), onPrimary = Color.White, primaryContainer = Color(0xFFDCD8F4), onPrimaryContainer = Color(0xFF231B5C),
            secondary = Color(0xFF1D6B66), onSecondary = Color.White, secondaryContainer = Color(0xFFCFCBF0), onSecondaryContainer = Color(0xFF231B5C),
            background = Color(0xFFD8D5CF), onBackground = tinta, surface = Color(0xFFD8D5CF), onSurface = tinta,
            surfaceVariant = Color(0xFFF2F1EE), onSurfaceVariant = Color(0xFF55525C), outline = tinta, outlineVariant = Color(0xFFB9B5AD),
            surfaceContainerLowest = plastico, surfaceContainerLow = plastico, surfaceContainer = Color(0xFFCFCCC5),
            surfaceContainerHigh = plastico, surfaceContainerHighest = plastico, error = Color(0xFFA3252B),
        ),
        base.mapa({ it.copy(fontFamily = Pixel, fontWeight = FontWeight.Bold) }, { it }),
        RoundedCornerShape(8.dp), RoundedCornerShape(6.dp), 2.dp, tinta, 4.dp, true, Color(0xFFA9A59D),
        Textura.LISTRAS, Color(0xFFCFCCC5),
        Color(0xFF1F6B3A), Color(0xFF8A5A00), Color(0xFF1D4F96), Color(0xFF1D6B66),
    )
}

private val crt: Estilo by lazy {
    val fosforo = Color(0xFF7DFFA1); val apagado = Color(0xFF4FAE6C); val fundo = Color(0xFF050B07); val painel = Color(0xFF0B1A10)
    Estilo(
        darkColorScheme(
            primary = fosforo, onPrimary = Color(0xFF06100A), primaryContainer = Color(0xFF14361F), onPrimaryContainer = fosforo,
            secondary = Color(0xFFFFB547), onSecondary = Color(0xFF1A1000), secondaryContainer = Color(0xFF14361F), onSecondaryContainer = fosforo,
            tertiary = Color(0xFFFFB547), background = fundo, onBackground = fosforo, surface = fundo, onSurface = fosforo,
            surfaceVariant = painel, onSurfaceVariant = apagado, outline = Color(0xFF2F6B44), outlineVariant = Color(0xFF1F4A2D),
            surfaceContainerLowest = painel, surfaceContainerLow = painel, surfaceContainer = Color(0xFF07140B),
            surfaceContainerHigh = painel, surfaceContainerHighest = painel, error = Color(0xFFFF6B5E), onError = Color(0xFF1A0000),
            inverseSurface = fosforo, inverseOnSurface = fundo,
        ),
        // a fonte de terminal é pequena: tudo fica 30% maior
        base.mapa({ it.copy(fontFamily = Fosforo, fontWeight = FontWeight.Normal).maior(1.3f) }, { it.copy(fontFamily = Fosforo, fontWeight = FontWeight.Normal).maior(1.3f) }),
        RectangleShape, RectangleShape, 2.dp, Color(0xFF2F6B44), 0.dp, false, Color.Transparent,
        Textura.VARREDURA, Color(0x47000000),
        fosforo, Color(0xFFFFB547), Color(0xFF8FD3FF), Color(0xFF5FE0C8),
    )
}

private val revista: Estilo by lazy {
    val preto = Color(0xFF111111); val papel = Color(0xFFF3EAD6)
    Estilo(
        lightColorScheme(
            primary = Color(0xFFC8102E), onPrimary = Color.White, primaryContainer = Color(0xFFFFD23F), onPrimaryContainer = preto,
            secondary = Color(0xFF1C3FA8), onSecondary = Color.White, secondaryContainer = Color(0xFFFFD23F), onSecondaryContainer = preto,
            tertiary = Color(0xFFFFD23F), background = papel, onBackground = preto, surface = papel, onSurface = preto,
            surfaceVariant = Color(0xFFFFF3C4), onSurfaceVariant = Color(0xFF4A4436), outline = preto, outlineVariant = Color(0xFFBDB197),
            surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White, surfaceContainer = Color(0xFFEDE2C8),
            surfaceContainerHigh = Color.White, surfaceContainerHighest = Color.White, error = Color(0xFFC8102E),
        ),
        base.mapa({ it.copy(fontFamily = Manchete, fontWeight = FontWeight.Normal) }, { it.copy(fontFamily = Revista) }),
        RectangleShape, RectangleShape, 3.dp, preto, 5.dp, false, preto,
        Textura.RETICULA, Color(0xFFDDCFB0),
        Color(0xFF0B6B3A), Color(0xFF8A4B00), Color(0xFF1C3FA8), Color(0xFF0B6B62),
    )
}

fun estiloDe(v: Visual, escuro: Boolean): Estilo = when (v) {
    Visual.ATUAL -> if (escuro) atualEscuro else atualClaro
    Visual.CONSOLE -> console16
    Visual.CRT -> crt
    Visual.REVISTA -> revista
}

// ---------------- tema, fundo e decoração ----------------

/** O tema do app: cores, fontes, textura de fundo e (no CRT) as linhas de varredura por cima de tudo. */
@Composable
fun TemaDoApp(escuro: Boolean, conteudo: @Composable () -> Unit) {
    VisualAtual.escuro = escuro
    val e = VisualAtual.estilo
    MaterialTheme(colorScheme = e.cores, typography = e.tipografia) {
        val d = LocalDensity.current
        val brush = remember(e, d.density) { texturaBrush(e, d) }
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxSize()
                .drawBehind {
                    drawRect(e.cores.background)
                    if (e.textura == Textura.VARREDURA) drawRect(Brush.radialGradient(listOf(Color(0xFF0D1F14), Color(0xFF050B07)), center = Offset(size.width / 2, size.height * .4f), radius = size.maxDimension * .75f))
                    if (brush != null && e.textura != Textura.VARREDURA) drawRect(brush)
                }
                .drawWithContent { drawContent(); if (brush != null && e.textura == Textura.VARREDURA) drawRect(brush) }
        ) {
            // fundo transparente (para a textura aparecer): o texto sem cor definida usa a cor do visual, não o preto padrão
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides e.cores.onBackground) { conteudo() }
        }
    }
}


/** Um "azulejo" pequeno repetido: listras de plástico, retícula de revista ou linhas de varredura. */
private fun texturaBrush(e: Estilo, d: Density): Brush? {
    val px = { v: Float -> v * d.density }
    val (w, h) = when (e.textura) {
        Textura.NENHUMA -> return null
        Textura.LISTRAS -> px(12f) to px(4f)
        Textura.RETICULA -> px(9f) to px(9f)
        Textura.VARREDURA -> 1f to maxOf(3f, px(1.2f))
    }
    val img = ImageBitmap(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1))
    val c = androidx.compose.ui.graphics.Canvas(img)
    CanvasDrawScope().draw(d, LayoutDirection.Ltr, c, Size(w, h)) {
        when (e.textura) {
            Textura.LISTRAS -> drawRect(e.corTextura, size = Size(w / 2, h))
            Textura.RETICULA -> drawCircle(e.corTextura, radius = px(1.4f), center = Offset(w / 2, h / 2))
            Textura.VARREDURA -> drawRect(e.corTextura, size = Size(w, maxOf(1f, h / 3)))
            Textura.NENHUMA -> Unit
        }
    }
    return ShaderBrush(ImageShader(img, TileMode.Repeated, TileMode.Repeated))
}

/** Sombra dura (deslocada e sem desfoque), desenhada atrás do componente com o formato dele. */
fun Modifier.sombraDura(e: Estilo, desloc: Dp, forma: Shape): Modifier = if (desloc <= 0.dp) this else drawBehind {
    val o = forma.createOutline(size, layoutDirection, this)
    val dx = if (e.sombraSoEmbaixo) 0f else desloc.toPx(); val dy = desloc.toPx()
    translate(dx, dy) { drawOutline(o, e.corSombra) }
}

// ---------------- cartão e botões que seguem o visual ----------------

@Composable
fun Card(modifier: Modifier = Modifier, colors: CardColors = CardDefaults.cardColors(), content: @Composable ColumnScope.() -> Unit) {
    val e = VisualAtual.estilo
    if (VisualAtual.visual == Visual.ATUAL) { androidx.compose.material3.Card(modifier, colors = colors, content = content); return }
    androidx.compose.material3.Card(
        modifier.sombraDura(e, e.sombra, e.canto).let { if (e.borda > 0.dp) it.border(e.borda, e.corBorda, e.canto) else it },
        shape = e.canto, colors = colors, elevation = CardDefaults.cardElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp, 0.dp), content = content,
    )
}

@Composable
private fun botaoEstilo(): Triple<Shape, BorderStroke?, Modifier> {
    val e = VisualAtual.estilo
    if (VisualAtual.visual == Visual.ATUAL) return Triple(ButtonDefaults.shape, null, Modifier)
    return Triple(e.cantoBotao, if (e.borda > 0.dp) BorderStroke(e.borda, e.corBorda) else null, Modifier.sombraDura(e, e.sombra * 0.7f, e.cantoBotao))
}

@Composable
fun Button(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, colors: ButtonColors = ButtonDefaults.buttonColors(),
           contentPadding: PaddingValues = ButtonDefaults.ContentPadding, content: @Composable RowScope.() -> Unit) {
    val (forma, borda, extra) = botaoEstilo()
    androidx.compose.material3.Button(onClick, modifier.then(extra), enabled, forma, colors, border = borda, contentPadding = contentPadding, content = content)
}

@Composable
fun OutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, colors: ButtonColors? = null,
                   contentPadding: PaddingValues = ButtonDefaults.ContentPadding, content: @Composable RowScope.() -> Unit) {
    val (forma, borda, extra) = botaoEstilo()
    // nos visuais retrô o botão contornado tem fundo (como uma tecla), para a sombra dura não aparecer através dele
    val cores = colors ?: if (VisualAtual.visual == Visual.ATUAL) ButtonDefaults.outlinedButtonColors()
        else ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)
    androidx.compose.material3.OutlinedButton(onClick, modifier.then(extra), enabled, forma, cores,
        border = borda ?: ButtonDefaults.outlinedButtonBorder(enabled), contentPadding = contentPadding, content = content)
}

@Composable
fun FilledTonalButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
                      contentPadding: PaddingValues = ButtonDefaults.ContentPadding, content: @Composable RowScope.() -> Unit) {
    val (forma, borda, extra) = botaoEstilo()
    androidx.compose.material3.FilledTonalButton(onClick, modifier.then(extra), enabled, forma, colors, border = borda, contentPadding = contentPadding, content = content)
}

// ---------------- menu "Visual do app" (Ajustes) ----------------

/** Cartão em Ajustes que abre o menu de visuais. */
@Composable
fun VisualCard() {
    var aberto by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        androidx.compose.foundation.layout.Column(Modifier.padding(14.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            SectionTitle(tr("Visual do app"))
            androidx.compose.material3.Text(tr(VisualAtual.visual.titulo), fontWeight = FontWeight.SemiBold)
            Hint(tr(VisualAtual.visual.descricao))
            OutlinedButton(onClick = { aberto = true }) { androidx.compose.material3.Text(tr("Escolher o visual")) }
        }
    }
    if (aberto) VisualMenu { aberto = false }
}

/** Menu próprio: os quatro visuais com prévia; tocar aplica na hora. */
@Composable
fun VisualMenu(onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        TemaDoApp(VisualAtual.escuro) {
            androidx.compose.foundation.layout.Column(
                Modifier.fillMaxSize().androidx_statusPad().verticalScrollCompat().padding(16.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp)
            ) {
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.Text(tr("Visual do app"), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    Button(onClick = onClose) { androidx.compose.material3.Text(tr("Pronto")) }
                }
                Hint(tr("Toque num visual para aplicar. Dá para trocar quando quiser; nada na planilha muda."))
                Visual.entries.forEach { v -> OpcaoVisual(v, v == VisualAtual.visual) { VisualAtual.escolher(ctx, v) } }
                Hint(tr("Fontes dos visuais retrô: Pixelify Sans, VT323, Bungee e Rubik (SIL Open Font License)."))
            }
        }
    }
}

@Composable private fun Modifier.androidx_statusPad() = this.then(Modifier.windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing))
@Composable private fun Modifier.verticalScrollCompat() = this.verticalScroll(rememberScrollState())

@Composable
private fun OpcaoVisual(v: Visual, escolhido: Boolean, onPick: () -> Unit) {
    val e = estiloDe(v, VisualAtual.escuro)
    val d = LocalDensity.current
    val brush = remember(e, d.density) { texturaBrush(e, d) }
    val atual = VisualAtual.estilo
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth()
            .border(if (escolhido) 3.dp else 1.dp, if (escolhido) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, atual.canto)
            .clip(atual.canto)
            .selectable(selected = escolhido, onClick = onPick, role = androidx.compose.ui.semantics.Role.RadioButton)
            .padding(10.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        // prévia: fundo, textura, um "cartão" e um botão no estilo do visual
        androidx.compose.foundation.layout.Box(
            Modifier.width(132.dp).height(96.dp).clip(RoundedCornerShape(6.dp))
                .drawBehind { drawRect(e.cores.background); if (brush != null) drawRect(brush) }
                .padding(8.dp)
        ) {
            androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                androidx.compose.material3.Text("Game Catalog", style = e.tipografia.titleSmall, color = e.cores.onBackground, maxLines = 1)
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxWidth().height(22.dp).sombraDura(e, e.sombra * 0.6f, e.canto)
                        .then(if (e.borda > 0.dp) Modifier.border(e.borda * 0.75f, e.corBorda, e.canto) else Modifier)
                        .clip(e.canto).drawBehind { drawRect(e.cores.surfaceContainerHighest) }
                ) {
                    androidx.compose.material3.Text("Super Mario World", style = e.tipografia.labelSmall, color = e.cores.onSurface, maxLines = 1,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                }
                androidx.compose.foundation.layout.Box(
                    Modifier.width(64.dp).height(20.dp).sombraDura(e, e.sombra * 0.5f, e.cantoBotao).clip(e.cantoBotao).drawBehind { drawRect(e.cores.primary) },
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) { androidx.compose.material3.Text("OK", style = e.tipografia.labelSmall, color = e.cores.onPrimary) }
            }
        }
        androidx.compose.foundation.layout.Column(Modifier.weight(1f), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.material3.RadioButton(selected = escolhido, onClick = null)
                androidx.compose.material3.Text(tr(v.titulo), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp))
            }
            Hint(tr(v.descricao))
        }
    }
}

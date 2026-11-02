package io.github.vprodc83.gamecatalog

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import io.github.vprodc83.gamecatalog.data.Config
import io.github.vprodc83.gamecatalog.data.Lista
import io.github.vprodc83.gamecatalog.google.GoogleAuth
import io.github.vprodc83.gamecatalog.ui.AppModel
import io.github.vprodc83.gamecatalog.ui.DetailScreen
import io.github.vprodc83.gamecatalog.ui.I18n
import io.github.vprodc83.gamecatalog.ui.IdentifyScreen
import io.github.vprodc83.gamecatalog.ui.LightColors
import io.github.vprodc83.gamecatalog.ui.ListScreen
import io.github.vprodc83.gamecatalog.ui.SettingsScreen
import io.github.vprodc83.gamecatalog.ui.SplashScreen
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RConfig
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Desenha as telas principais (com uma planilha de exemplo) e salva imagens para conferência. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RConfig(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreensTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File("build/screens").apply { mkdirs() }

    private fun seed(): AppModel {
        val ctx = rule.activity
        val cfg = Config.build(Config.Colecao("GAMES", 0, 1, 2, 3), listOf(Config.Procurado("SFC", 0, 1, -1, "SFC", -1)), "BUYING", null)
        File(ctx.filesDir, "ajustes.json").writeText(JSONObject().put("sheetId", "teste").toString())
        val games = listOf(listOf("CONSOLE", "NOME", "EU/USA/BR/JAP/ASIA", "CIB"), listOf("SNES", "Super Mario World", "USA", "SIM"),
            listOf("SFC", "Rockman X", "JAP", ""), listOf("MD", "Sonic the Hedgehog", "BR", "SIM"))
        val sfc = listOf(listOf("NOME", "STATUS"), listOf("Final Fantasy VI", ""))
        val buy = listOf(listOf("DATA", "CONSOLE", "NOME", "REGIAO", "LISTA", "STATUS", "RECEBIDO_EM", "OBS"), listOf("01/11/2026", "N64", "Zelda Ocarina of Time", "USA", "", "COMPRADO", "", "OLX"))
        val vals = JSONArray(listOf(games, sfc, buy).map { t -> JSONArray(t.map { JSONArray(it) }) })
        File(ctx.filesDir, "planilha-cache.json").writeText(JSONObject().put("sheetId", "teste").put("title", "JOGOS").put("config", cfg.toString()).put("em", 0L).put("vals", vals).toString())
        I18n.init(ctx)
        return AppModel(ctx, GoogleAuth(ctx))
    }

    private fun shot(name: String, rtl: Boolean = false, content: @Composable () -> Unit) {
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                MaterialTheme(colorScheme = LightColors) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Box(Modifier.fillMaxSize()) { content() } } }
            }
        }
        rule.mainClock.advanceTimeBy(7000)
        rule.waitForIdle()
        val v = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { v.draw(android.graphics.Canvas(bmp)) }
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun telas() {
        val m = seed()
        assertTrue(m.repo.state.value.items.isNotEmpty())
        // a cada setContent a composição anterior é substituída; uma tela por teste deixa as imagens independentes
        shot("1-colecao") { ListScreen(m, Lista.COLECAO) {} }
    }

    @Test fun ficha() { val m = seed(); shot("2-ficha") { DetailScreen(m, m.repo.state.value.of(Lista.COLECAO)[1]) {} } }
    @Test fun identificar() { val m = seed(); shot("3-identificar") { IdentifyScreen(m) {} } }
    @Test fun ajustes() { val m = seed(); shot("4-ajustes") { SettingsScreen(m, {}, {}, {}) } }
    @Test fun abertura() { seed(); rule.mainClock.autoAdvance = false; shot("5-abertura") { SplashScreen("0.6.0") {} } }
    @Test fun hebraico() { val m = seed(); I18n.set("he-IL"); shot("6-colecao-hebraico", rtl = true) { ListScreen(m, Lista.COLECAO) {} }; I18n.set("pt-BR") }
    @Test fun japones() { val m = seed(); I18n.set("ja-JP"); shot("7-ajustes-japones") { SettingsScreen(m, {}, {}, {}) }; I18n.set("pt-BR") }
}

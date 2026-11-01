package io.github.vprodc83.gamecatalog

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import io.github.vprodc83.gamecatalog.data.Item
import io.github.vprodc83.gamecatalog.data.Lista
import io.github.vprodc83.gamecatalog.google.GoogleAuth
import io.github.vprodc83.gamecatalog.google.Sheets
import io.github.vprodc83.gamecatalog.ui.AppModel
import io.github.vprodc83.gamecatalog.ui.DarkColors
import io.github.vprodc83.gamecatalog.ui.DetailScreen
import io.github.vprodc83.gamecatalog.ui.IdentifyScreen
import io.github.vprodc83.gamecatalog.ui.LightColors
import io.github.vprodc83.gamecatalog.ui.ListScreen
import io.github.vprodc83.gamecatalog.ui.LoginScreen
import io.github.vprodc83.gamecatalog.ui.MappingScreen
import io.github.vprodc83.gamecatalog.ui.SettingsScreen
import io.github.vprodc83.gamecatalog.ui.SheetScreen

class MainActivity : ComponentActivity() {
    private lateinit var model: AppModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val auth = GoogleAuth(this)
        auth.launcher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { auth.onResult(it.data) }
        model = AppModel(applicationContext, auth)
        handleShare(intent)
        setContent { MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { App(model) } } }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleShare(intent) }

    /** Link da planilha compartilhado com o app (ex.: pelo WhatsApp ou pelo Google Sheets). */
    private fun handleShare(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val t = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        if (Sheets.idFromLink(t) != null) model.sharedLink = t
    }
}

private enum class Tab(val titulo: String) { IDENTIFICAR("Identificar"), COLECAO("Coleção"), PROCURADOS("Procurados"), COMPRAS("Compras"), AJUSTES("Ajustes") }
private enum class Setup { CARREGANDO, LOGIN, PLANILHA, COLUNAS, PRONTO }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(m: AppModel) {
    val st by m.repo.state.collectAsState()
    var setup by remember { mutableStateOf(Setup.CARREGANDO) }
    var tab by remember { mutableStateOf(Tab.IDENTIFICAR) }
    var open by remember { mutableStateOf<Item?>(null) }
    val snack = remember { SnackbarHostState() }

    fun next() {
        setup = when {
            !m.loggedIn -> Setup.LOGIN
            m.repo.sheetId.isBlank() || m.sharedLink?.let { Sheets.idFromLink(it) != m.repo.sheetId } == true -> Setup.PLANILHA
            m.repo.state.value.config == null -> Setup.COLUNAS
            else -> Setup.PRONTO
        }
    }

    LaunchedEffect(Unit) {
        m.silentLogin().join()
        if (m.loggedIn && m.repo.sheetId.isNotBlank()) { m.refresh().join() }
        next()
    }
    LaunchedEffect(m.sharedLink) { if (m.sharedLink != null && setup == Setup.PRONTO) next() }
    LaunchedEffect(m.loggedIn) { if (!m.loggedIn && setup == Setup.PRONTO) setup = Setup.LOGIN }
    LaunchedEffect(m.message) { m.message?.let { snack.showSnackbar(it); m.message = null } }

    when (setup) {
        Setup.CARREGANDO -> Box(Modifier.fillMaxSize()) { LinearProgressIndicator(Modifier.padding(top = 80.dp).fillMaxWidth()) }
        Setup.LOGIN -> Scaffold(snackbarHost = { SnackbarHost(snack) }) { p -> Box(Modifier.padding(p)) { LoginScreen(m) { m.scope.launch { if (m.repo.sheetId.isNotBlank()) m.refresh().join(); next() } } } }
        Setup.PLANILHA -> Scaffold(snackbarHost = { SnackbarHost(snack) }) { p -> Box(Modifier.padding(p)) { SheetScreen(m) { m.sharedLink = null; next() } } }
        Setup.COLUNAS -> Scaffold(snackbarHost = { SnackbarHost(snack) }) { p ->
            BackHandler(enabled = st.config != null) { setup = Setup.PRONTO }
            Box(Modifier.padding(p)) { MappingScreen(m) { next() } }
        }
        Setup.PRONTO -> {
            BackHandler(enabled = open != null || tab != Tab.IDENTIFICAR) { if (open != null) open = null else tab = Tab.IDENTIFICAR }
            Scaffold(
                topBar = {
                    TopAppBar(title = { Text(if (open != null) open!!.lista.titulo else tab.titulo + if (tab == Tab.COLECAO) " (${st.of(Lista.COLECAO).size})" else "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        actions = { IconButton(onClick = { m.refresh() }, enabled = !m.busy) { Icon(Icons.Default.Refresh, "Atualizar") } })
                },
                bottomBar = {
                    NavigationBar {
                        listOf(Tab.IDENTIFICAR to Icons.Default.CameraAlt, Tab.COLECAO to Icons.Default.VideogameAsset, Tab.PROCURADOS to Icons.Default.Favorite,
                            Tab.COMPRAS to Icons.Default.ShoppingCart, Tab.AJUSTES to Icons.Default.Settings).forEach { (t, ic) ->
                            NavigationBarItem(selected = tab == t && open == null, onClick = { tab = t; open = null }, icon = { Icon(ic, null) }, label = { Text(t.titulo, maxLines = 1) })
                        }
                    }
                },
                snackbarHost = { SnackbarHost(snack) }
            ) { p ->
                Box(Modifier.padding(p)) {
                    if (m.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    val o = open
                    if (o != null) DetailScreen(m, o) { open = null }
                    else when (tab) {
                        Tab.IDENTIFICAR -> IdentifyScreen(m) { open = it }
                        Tab.COLECAO -> ListScreen(m, Lista.COLECAO) { open = it }
                        Tab.PROCURADOS -> ListScreen(m, Lista.PROCURADO) { open = it }
                        Tab.COMPRAS -> ListScreen(m, Lista.COMPRADO) { open = it }
                        Tab.AJUSTES -> SettingsScreen(m, onChangeSheet = { setup = Setup.PLANILHA }, onMapping = { setup = Setup.COLUNAS }, onSignedOut = { setup = Setup.LOGIN })
                    }
                }
            }
        }
    }
}


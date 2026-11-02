package io.github.vprodc83.gamecatalog.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.vprodc83.gamecatalog.core.Text as T
import io.github.vprodc83.gamecatalog.data.Config
import io.github.vprodc83.gamecatalog.data.Repo
import io.github.vprodc83.gamecatalog.google.Sheets
import kotlinx.coroutines.launch

@Composable
fun SetupFrame(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Game Catalog", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                content()
            }
        }
    }
}

@Composable
fun LoginScreen(m: AppModel, onDone: () -> Unit) {
    SetupFrame(tr("Bem-vindo!")) {
        Text(tr("O Game Catalog organiza a sua coleção de videogames numa planilha do Google Sheets, que pode ser compartilhada com quem ajuda você a procurar jogos."))
        Text(tr("Entre com a conta Google do celular. O app lê e grava a planilha da coleção que você escolher; nada é enviado para outra pessoa."))
        Button(onClick = { m.login(onDone) }, enabled = !m.busy, modifier = Modifier.fillMaxWidth()) { Text(tr("Entrar com Google")) }
        if (m.busy) CircularProgressIndicator()
        m.loginError?.let { androidx.compose.foundation.text.selection.SelectionContainer { Text(it, color = MaterialTheme.colorScheme.error) } }
        TextButton(onClick = { openUrl(m.ctx, "https://vprodc83.github.io/privacidade.html") }) { Text(tr("Política de privacidade")) }
    }
}

@Composable
fun SheetScreen(m: AppModel, onOpened: () -> Unit) {
    val ctx = LocalContext.current
    var link by remember { mutableStateOf(m.sharedLink ?: "") }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    SetupFrame(tr("Sua planilha da coleção")) {
        m.auth.email?.let { e ->
            Hint(tr("Conectado como {0}. A planilha precisa estar compartilhada com este e-mail como Editor.", e))
            TextButton(onClick = { scope.launch { m.auth.signOut(); m.loggedIn = false; m.login {} } }) { Text(tr("Entrar com outra conta Google")) }
        }
        Text(tr("Abra a sua planilha no app Google Sheets, toque em Compartilhar → Copiar link e cole aqui. Se outra pessoa da casa criou a planilha, peça para ela compartilhar com você como Editor."))
        OutlinedTextField(value = link, onValueChange = { link = it }, label = { Text(tr("Link da planilha")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString()?.let { link = it }
            }) { Text(tr("Colar")) }
            Button(onClick = {
                val id = Sheets.idFromLink(link)
                if (id == null) { err = tr("Esse não parece um link de planilha do Google."); return@Button }
                err = null
                scope.launch { m.busy = true; try { m.sheets.meta(id); m.repo.sheetId = id; m.repo.refresh(); onOpened() } catch (e: Exception) { err = Repo.friendly(e) } finally { m.busy = false } }
            }, enabled = !m.busy) { Text(tr("Usar esta planilha")) }
        }
        HorizontalLine()
        Text(tr("Ainda não tem planilha?"))
        OutlinedButton(onClick = { scope.launch { m.busy = true; try { m.repo.createSheet(); m.repo.refresh(); onOpened() } catch (e: Exception) { err = Repo.friendly(e) } finally { m.busy = false } } }, enabled = !m.busy) {
            Text(tr("Começar uma planilha nova"))
        }
        if (m.busy) CircularProgressIndicator()
        err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable fun HorizontalLine() = androidx.compose.material3.HorizontalDivider()

/** Diz ao app em quais abas e colunas estão a coleção, os procurados e as compras. */
@Composable
fun MappingScreen(m: AppModel, onSaved: () -> Unit) {
    var data by remember { mutableStateOf<Pair<String, Map<String, List<String>>>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { runCatching { m.repo.tabsWithHeaders() }.onSuccess { data = it }.onFailure { err = Repo.friendly(it) } }
    val d = data
    if (d == null) { SetupFrame(tr("Lendo a planilha…")) { err?.let { Text(it, color = MaterialTheme.colorScheme.error) } ?: CircularProgressIndicator() }; return }
    val tabs = d.second.keys.toList()
    fun guessCol(tab: String, re: String, none: Boolean): Int = (d.second[tab] ?: emptyList()).indexOfFirst { T.norm(it).matches(re.toRegex()) }.let { if (it >= 0) it else if (none) -1 else 0 }
    val prev = m.repo.state.value.config
    var colTab by remember { mutableStateOf(prev?.colecao?.aba ?: tabs.firstOrNull { T.norm(it).matches("^(GAMES|COLECAO|JOGOS|COLLECTION).*".toRegex()) } ?: tabs.first()) }
    var cCons by remember(colTab) { mutableStateOf(guessCol(colTab, "^(CONSOLE|PLATAFORMA|SISTEMA|PLATFORM).*", false)) }
    var cNome by remember(colTab) { mutableStateOf(guessCol(colTab, "^(NOME|JOGO|TITULO|NAME|GAME).*", false).let { if (it == cCons) 1 else it }) }
    var cReg by remember(colTab) { mutableStateOf(guessCol(colTab, "^(REGIAO|REGION|REG).*", true)) }
    val wanted = remember { mutableStateListOf<Config.Procurado>().apply {
        addAll(prev?.procurados ?: tabs.filter { T.norm(it).matches("^(SFC|FC|ODYSSEY|PROCURADOS|WANTED|WISHLIST|DESEJOS)$".toRegex()) }.map { t ->
            Config.Procurado(t, guessCol(t, "^(NOME|JOGO|TITULO|NAME).*", false), guessCol(t, "^(STATUS|SITUACAO).*", true), guessCol(t, "^(CONSOLE|PLATAFORMA).*", true),
                if (T.norm(t).matches("^(SFC|FC|NES|SNES|MD|MS|ODYSSEY|N64|PS[0-9]?)$".toRegex())) (if (T.norm(t) == "FC") "FAMICOM" else t.uppercase()) else "", guessCol(t, "^(OBS|INFO|NOTA).*", true))
        })
    } }
    var buyTab by remember { mutableStateOf(prev?.compras ?: tabs.firstOrNull { T.norm(it).matches("^(BUYING|COMPRAS)$".toRegex()) } ?: "COMPRAS") }
    fun cols(tab: String, none: Boolean) = (if (none) listOf(-1 to tr("(nenhuma)")) else emptyList()) + (d.second[tab] ?: emptyList()).mapIndexed { i, h -> i to "${Sheets.col(i)} · ${h.ifBlank { tr("(sem título)") }}" }

    SetupFrame(tr("Como é a sua planilha")) {
        Hint(tr("Isso fica salvo na própria planilha (aba APP_CONFIG) e vale para todas as pessoas e aparelhos que usam ela."))
        SectionTitle(tr("Coleção (jogos que você tem)"))
        Picker(tr("Aba"), colTab, tabs.map { it to it }) { colTab = it }
        Picker(tr("Coluna do console"), cCons, cols(colTab, false)) { cCons = it }
        Picker(tr("Coluna do nome"), cNome, cols(colTab, false)) { cNome = it }
        Picker(tr("Coluna da região"), cReg, cols(colTab, true)) { cReg = it }
        SectionTitle(tr("Procurados (opcional)"))
        wanted.forEachIndexed { i, w ->
            Card { Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Picker(tr("Aba de procurados"), w.aba, tabs.map { it to it }) { wanted[i] = w.copy(aba = it) }
                Picker(tr("Coluna do nome"), w.nome, cols(w.aba, false)) { wanted[i] = w.copy(nome = it) }
                Picker(tr("Coluna do status (onde marcar COMPRADO)"), w.status, cols(w.aba, true)) { wanted[i] = w.copy(status = it) }
                Picker(tr("Coluna do console"), w.consoleCol, cols(w.aba, true)) { wanted[i] = w.copy(consoleCol = it) }
                OutlinedTextField(value = w.console, onValueChange = { wanted[i] = w.copy(console = it) }, label = { Text(tr("ou console fixo da aba (ex.: SFC)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { wanted.removeAt(i) }) { Text(tr("Remover esta aba")) }
            } }
        }
        OutlinedButton(onClick = { wanted.add(Config.Procurado(tabs.first(), 0, -1, -1, "", -1)) }) { Text(tr("Adicionar aba de procurados")) }
        SectionTitle(tr("Lista de compras"))
        OutlinedTextField(value = buyTab, onValueChange = { buyTab = it }, label = { Text(tr("Aba (criada se não existir)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            if (cCons == cNome) { err = tr("Console e nome precisam ser colunas diferentes."); return@Button }
            val heads = d.second[colTab] ?: emptyList()
            val last = maxOf(heads.indexOfLast { it.isNotBlank() }, cCons, cNome, cReg)
            val cfg = Config.build(Config.Colecao(colTab, cCons, cNome, cReg, last), wanted.toList(), buyTab.trim().ifBlank { "COMPRAS" }, prev)
            scope.launch { m.busy = true; try { m.repo.saveConfig(cfg); m.repo.refresh(); onSaved() } catch (e: Exception) { err = Repo.friendly(e) } finally { m.busy = false } }
        }, enabled = !m.busy, modifier = Modifier.fillMaxWidth()) { Text(tr("Salvar e começar")) }
        err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

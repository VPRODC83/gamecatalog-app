package io.github.vprodc83.gamecatalog.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Se o app fechar sozinho, guarda o motivo (só no celular). Na abertura seguinte ele pergunta se você quer
 * enviar o relatório — por WhatsApp, e-mail etc., você escolhe. Nada é enviado sem você tocar em Enviar.
 */
object CrashLog {
    private fun file(ctx: Context) = File(ctx.filesDir, "ultimo-erro.txt")

    fun install(ctx: Context, version: String) {
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = java.io.StringWriter(); e.printStackTrace(java.io.PrintWriter(sw))
                file(app).writeText("Game Catalog $version · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                    SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US).format(Date()) + " · thread ${t.name}\n\n" + sw.toString().take(20000))
            }
            prev?.uncaughtException(t, e)
        }
    }

    /** Erro que o app conseguiu contornar: fica registrado para envio, sem fechar o app. */
    fun note(ctx: Context, e: Throwable) = runCatching {
        android.util.Log.e("GameCatalog", "erro em segundo plano", e)
        val f = file(ctx.applicationContext); if (f.exists()) return@runCatching
        val sw = java.io.StringWriter(); e.printStackTrace(java.io.PrintWriter(sw))
        f.writeText("Game Catalog · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL} · erro contornado (o app não fechou)\n" +
            SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US).format(Date()) + "\n\n" + sw.toString().take(20000))
    }

    fun pending(ctx: Context): String? = file(ctx).takeIf { it.exists() }?.readText()
    fun clear(ctx: Context) { file(ctx).delete() }
}

@Composable
fun CrashReportDialog() {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf(runCatching { CrashLog.pending(ctx) }.getOrNull()) }
    val t = text ?: return
    AlertDialog(onDismissRequest = { CrashLog.clear(ctx); text = null },
        title = { Text(tr("O app teve um erro da última vez")) },
        text = { Text(tr("Guardei o motivo. Enviar o relatório ajuda a corrigir o problema (não contém dados da planilha).")) },
        confirmButton = { TextButton(onClick = {
            CrashLog.clear(ctx); text = null
            runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Game Catalog: erro").putExtra(Intent.EXTRA_TEXT, t), tr("Enviar o relatório")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }) { Text(tr("Enviar o relatório")) } },
        dismissButton = { TextButton(onClick = { CrashLog.clear(ctx); text = null }) { Text(tr("Agora não")) } })
}

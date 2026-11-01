package io.github.vprodc83.gamecatalog.google

import android.app.Activity
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.tasks.await

/**
 * Login com a conta Google do aparelho (a mesma da Play Store) e permissão para ler e gravar
 * as planilhas do Google Sheets. O Android mostra a lista de contas e o pedido de permissão.
 */
class GoogleAuth(private val activity: Activity) {
    private val client = Identity.getAuthorizationClient(activity)
    private val request = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/spreadsheets"), Scope("email")))
        .build()
    private var token: String? = null
    private var expires = 0L
    var email: String? = null; private set
    var launcher: ActivityResultLauncher<IntentSenderRequest>? = null
    private var pending: CompletableDeferred<AuthorizationResult?>? = null

    /** Resultado da tela do Google (chamado pela Activity). */
    fun onResult(data: Intent?) {
        val r = runCatching { client.getAuthorizationResultFromIntent(data) }.getOrNull()
        pending?.complete(r); pending = null
    }

    /**
     * Devolve um token válido. Sem [interactive], não mostra nenhuma tela: se o Google precisar
     * perguntar algo (primeiro login, conta trocada), lança [NeedsLogin].
     */
    suspend fun token(interactive: Boolean): String {
        token?.let { if (System.currentTimeMillis() < expires) return it }
        var r = client.authorize(request).await()
        if (r.hasResolution()) {
            if (!interactive) throw NeedsLogin()
            val l = launcher ?: throw NeedsLogin()
            val d = CompletableDeferred<AuthorizationResult?>(); pending = d
            l.launch(IntentSenderRequest.Builder(r.pendingIntent!!.intentSender).build())
            r = d.await() ?: throw NeedsLogin()
        }
        val t = r.accessToken ?: throw NeedsLogin()
        token = t; expires = System.currentTimeMillis() + 45 * 60_000
        email = runCatching { r.toGoogleSignInAccount()?.email }.getOrNull() ?: email
        return t
    }

    /** Força um token novo na próxima chamada (ex.: depois de um 401). */
    fun invalidate() { token = null; expires = 0 }

    suspend fun signOut() {
        token?.let { t -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { com.google.android.gms.auth.GoogleAuthUtil.clearToken(activity, t) } } }
        invalidate(); email = null
    }

    class NeedsLogin : Exception("É preciso entrar com a conta Google.")
}

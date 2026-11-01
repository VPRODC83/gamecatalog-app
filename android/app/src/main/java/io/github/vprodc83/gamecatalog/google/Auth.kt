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
    private var pending: CompletableDeferred<Result<AuthorizationResult>>? = null

    /** Resultado da tela do Google (chamado pela Activity). */
    fun onResult(resultCode: Int, data: Intent?) {
        val r = runCatching { client.getAuthorizationResultFromIntent(data) }
            .recoverCatching { e -> throw LoginError.from(e, resultCode) }
        pending?.complete(r); pending = null
    }

    /**
     * Devolve um token válido. Sem [interactive], não mostra nenhuma tela: se o Google precisar
     * perguntar algo (primeiro login, conta trocada), lança [NeedsLogin].
     */
    suspend fun token(interactive: Boolean): String {
        token?.let { if (System.currentTimeMillis() < expires) return it }
        var r = try { client.authorize(request).await() } catch (e: Exception) { if (interactive) throw LoginError.from(e, null) else throw NeedsLogin() }
        if (r.hasResolution()) {
            if (!interactive) throw NeedsLogin()
            val l = launcher ?: throw NeedsLogin()
            val d = CompletableDeferred<Result<AuthorizationResult>>(); pending = d
            l.launch(IntentSenderRequest.Builder(r.pendingIntent!!.intentSender).build())
            r = d.await().getOrThrow()
        }
        val t = r.accessToken ?: throw LoginError("O Google não devolveu a permissão de acesso às planilhas. Toque em Entrar de novo e, na tela de permissões, marque a caixa das planilhas.")
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

    /** Erro do login explicado, com o código do Google para diagnóstico. */
    class LoginError(msg: String) : Exception(msg) {
        companion object {
            fun from(e: Throwable, resultCode: Int?): LoginError {
                val code = (e as? com.google.android.gms.common.api.ApiException)?.statusCode
                val tag = buildString { append("código "); append(code ?: "?"); if (resultCode != null) append(", resultado $resultCode") }
                val txt = when (code) {
                    10 -> "O Google recusou a configuração do app (cliente Android no Google Cloud: nome do pacote ou SHA-1 diferente, ou criado em outro projeto). Alterações no Google Cloud podem levar alguns minutos para valer."
                    16, 12501 -> "O login foi cancelado ou bloqueado pelo Google. Se a sua conta não está nos Usuários de teste do Google Cloud, o Google bloqueia a entrada."
                    7 -> "Sem conexão com o Google. Confira a internet."
                    8 -> "Erro interno do Google Play Services. Tente de novo em instantes."
                    17 -> "O Google Play Services do celular não oferece este login. Atualize o Google Play Services."
                    null -> if (resultCode == 0) "A tela do Google foi fechada antes de terminar (ou o Google bloqueou a conta: confira os Usuários de teste)." else "Falha no login do Google: ${e.message}"
                    else -> "Falha no login do Google: ${e.message}"
                }
                return LoginError("$txt ($tag)")
            }
        }
    }
}

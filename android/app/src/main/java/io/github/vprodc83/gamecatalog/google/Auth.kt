package io.github.vprodc83.gamecatalog.google

import io.github.vprodc83.gamecatalog.ui.tr
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
        val t = r.accessToken ?: throw LoginError(tr("O Google não devolveu a permissão de acesso às planilhas. Toque em Entrar de novo e, na tela de permissões, marque a caixa das planilhas."))
        // na tela de permissões do Google a caixa das planilhas pode ficar desmarcada: sem ela, nada funciona
        if (r.grantedScopes.none { it.contains("spreadsheets") }) {
            invalidate()
            throw LoginError(tr("A permissão para ver e editar as planilhas não foi concedida. Toque em Entrar com Google de novo e, na tela de permissões do Google, marque a caixa das planilhas (Google Sheets)."))
        }
        token = t; expires = System.currentTimeMillis() + 45 * 60_000
        email = runCatching { r.toGoogleSignInAccount()?.email }.getOrNull() ?: email ?: fetchEmail(t)
        lastEmail = email
        return t
    }

    /** Força um token novo na próxima chamada (ex.: depois de um 401). */
    fun invalidate() { token = null; expires = 0 }

    /** Sai de verdade: retira a permissão dada ao app, para o Google voltar a perguntar a conta e as permissões. */
    suspend fun signOut() {
        email?.let { e ->
            runCatching {
                client.revokeAccess(com.google.android.gms.auth.api.identity.RevokeAccessRequest.builder()
                    .setAccount(android.accounts.Account(e, "com.google"))
                    .setScopes(listOf(Scope("https://www.googleapis.com/auth/spreadsheets"), Scope("email"))).build()).await()
            }
        }
        token?.let { t -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { com.google.android.gms.auth.GoogleAuthUtil.clearToken(activity, t) } } }
        invalidate(); email = null; lastEmail = null
    }

    /** E-mail da conta que entrou (para mostrar e para explicar erros de acesso). */
    private suspend fun fetchEmail(t: String): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val c = java.net.URL("https://www.googleapis.com/oauth2/v3/userinfo").openConnection() as java.net.HttpURLConnection
            c.setRequestProperty("Authorization", "Bearer $t"); c.connectTimeout = 10000; c.readTimeout = 10000
            c.inputStream.use { org.json.JSONObject(it.bufferedReader().readText()).optString("email").ifBlank { null } }
        }.getOrNull()
    }

    companion object { @Volatile var lastEmail: String? = null }

    class NeedsLogin : Exception(tr("É preciso entrar com a conta Google."))

    /** Erro do login explicado, com o código do Google para diagnóstico. */
    class LoginError(msg: String) : Exception(msg) {
        companion object {
            fun from(e: Throwable, resultCode: Int?): LoginError {
                val code = (e as? com.google.android.gms.common.api.ApiException)?.statusCode
                val tag = buildString { append(tr("código ")); append(code ?: "?"); if (resultCode != null) append(tr(", resultado {0}", resultCode)) }
                val txt = when (code) {
                    10 -> tr("O Google recusou a configuração do app (cliente Android no Google Cloud: nome do pacote ou SHA-1 diferente, ou criado em outro projeto). Alterações no Google Cloud podem levar alguns minutos para valer.")
                    16, 12501 -> tr("O login foi cancelado ou bloqueado pelo Google. Se a sua conta não está nos Usuários de teste do Google Cloud, o Google bloqueia a entrada.")
                    7 -> tr("Sem conexão com o Google. Confira a internet.")
                    8 -> tr("Erro interno do Google Play Services. Tente de novo em instantes.")
                    17 -> tr("O Google Play Services do celular não oferece este login. Atualize o Google Play Services.")
                    null -> if (resultCode == 0) tr("A tela do Google foi fechada antes de terminar (ou o Google bloqueou a conta: confira os Usuários de teste).") else tr("Falha no login do Google: {0}", e.message)
                    else -> tr("Falha no login do Google: {0}", e.message)
                }
                return LoginError("$txt ($tag)")
            }
        }
    }
}

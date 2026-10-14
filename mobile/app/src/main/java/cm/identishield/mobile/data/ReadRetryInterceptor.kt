package cm.identishield.mobile.data

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import javax.net.ssl.SSLException

/**
 * ADR-039 (D9) : une connexion gardée en réserve puis fermée par le serveur fait échouer la requête
 * suivante. Les LECTURES de la liste fermée ci-dessous sont rejouées UNE fois, sur une connexion
 * neuve ; rien d'autre n'est jamais rejoué (SPEC §7.4 : un envoi reçu par le serveur mais dont la
 * réponse s'est perdue ne doit pas repartir). Le ticket (POST /nonce) est une lecture : un nonce
 * émis puis jamais utilisé est sans effet.
 */
class ReadRetryInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return try {
            chain.proceed(request)
        } catch (e: IOException) {
            if (!isReplayableRead(request) || !isConnectionFailure(e)) throw e
            chain.proceed(request)
        }
    }

    companion object {
        /** Chemins POST sans effet (relatifs à l'URL de base) ; tout GET est une lecture. */
        private val REPLAYABLE_POST = setOf("nonce")

        fun isReplayableRead(request: Request): Boolean =
            request.method == "GET" || (request.method == "POST" && request.url.pathSegments.lastOrNull() in REPLAYABLE_POST)

        /** Panne de connexion (connexion périmée, coupée, refusée) ; ni délai dépassé ni erreur TLS. */
        private fun isConnectionFailure(e: IOException): Boolean = e !is InterruptedIOException && e !is SSLException
    }
}

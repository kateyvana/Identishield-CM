package cm.identishield.mobile.data

import android.content.Context
import androidx.core.content.edit
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.util.UUID

/**
 * Identifiant d'installation (ADR-030 ; décision de la propriétaire du 2026-10-24, carte B3b) : un
 * UUID v4 aléatoire, créé une fois à l'installation, conservé dans les préférences privées — donc hors
 * sauvegarde et hors transfert (`allowBackup=false`, MOB-S-09) : réinstaller l'application en crée un
 * autre, c'est voulu, le serveur cumule l'installation et l'origine réseau pour freiner les
 * réenrôlements. Ce n'est NI un secret NI un facteur : une étiquette.
 */
fun interface InstallationId {
    fun get(): String
}

class PrefsInstallationId(
    context: Context,
) : InstallationId {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun get(): String =
        prefs.getString(KEY, null) ?: UUID.randomUUID().toString().also { fresh ->
            prefs.edit(commit = true) { putString(KEY, fresh) }
        }

    companion object {
        const val FILE = "isc-installation"
        private const val KEY = "id"
    }
}

/**
 * Pose l'en-tête `X-ISC-Installation` sur les routes où le contrat le déclare : enrôlement (start,
 * recover, complete) et défi de session. Nulle part ailleurs : l'étiquette ne circule que là où elle
 * sert au freinage.
 */
class InstallationHeaderInterceptor(
    private val installation: InstallationId,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!appliesTo(request)) return chain.proceed(request)
        return chain.proceed(request.newBuilder().header(HEADER, installation.get()).build())
    }

    companion object {
        const val HEADER = "X-ISC-Installation"

        /** Chemins (relatifs à l'URL de base) portant l'en-tête, d'après contracts/mobile-api.yaml. */
        private val ROUTES = setOf("enrolment/start", "enrolment/recover", "enrolment/complete", "session/challenge")

        fun appliesTo(request: Request): Boolean = request.url.pathSegments.takeLast(2).joinToString("/") in ROUTES
    }
}

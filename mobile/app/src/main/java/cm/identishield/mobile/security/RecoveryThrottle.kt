package cm.identishield.mobile.security

import android.content.Context
import androidx.core.content.edit
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Compteur d'échecs de clé de récupération et fin d'attente, SUR CET APPAREIL. Jamais la clé elle-même. */
data class ThrottleState(
    val failures: Int = 0,
    val lockedUntil: Instant? = null,
)

interface ThrottleStore {
    fun load(): ThrottleState

    fun save(state: ThrottleState)
}

/**
 * Délai croissant local du flux D (ISC-MOB-SPEC §8.3, ES-21) : REENROL_ATTEMPTS essais libres, puis
 * LOCK_BASE doublé à chaque échec, plafonné par LOCK_MAX — la MÊME règle que le serveur, qui reste
 * l'autorité (Retry-After). Seul CET appareil attend ; le compte n'est jamais bloqué. Le temps vient
 * d'une horloge injectée : aucun test n'attend en temps réel.
 */
class RecoveryThrottle(
    private val store: ThrottleStore,
    private val clock: Clock,
    private val freeAttempts: Int,
    private val base: Duration,
    private val max: Duration,
) {
    /** Attente restante, ou null si une tentative est permise maintenant. */
    fun remaining(): Duration? {
        val until = store.load().lockedUntil ?: return null
        val left = Duration.between(clock.instant(), until)
        return if (left.isNegative || left.isZero) null else left
    }

    fun lockedUntil(): Instant? = remaining()?.let { store.load().lockedUntil }

    /**
     * Une clé refusée par le serveur. Renvoie la fin d'attente si cet échec impose un délai (le plus
     * long entre la règle locale et le Retry-After du serveur), sinon null (« Clé incorrecte »).
     */
    fun recordFailure(serverRetryAfter: Duration? = null): Instant? {
        val failures = store.load().failures + 1
        val now = clock.instant()
        var until: Instant? = null
        if (failures >= freeAttempts) {
            until = now.plus(delayFor(failures))
        }
        if (serverRetryAfter != null) {
            val server = now.plus(serverRetryAfter)
            if (until == null || server.isAfter(until)) until = server
        }
        store.save(ThrottleState(failures, until))
        return until
    }

    /** Le serveur impose une attente (429) sans nouvel échec compté ; sans Retry-After : LOCK_BASE. */
    fun serverWait(retryAfter: Duration?): Instant {
        val state = store.load()
        val server = clock.instant().plus(retryAfter ?: base)
        val until = state.lockedUntil?.takeIf { it.isAfter(server) } ?: server
        store.save(state.copy(lockedUntil = until))
        return until
    }

    /** Clé acceptée : le compteur de cet appareil repart de zéro. */
    fun recordSuccess() = store.save(ThrottleState())

    /** LOCK_BASE × 2^(échecs − REENROL_ATTEMPTS), plafonné par LOCK_MAX. */
    internal fun delayFor(failures: Int): Duration {
        val extra = (failures - freeAttempts).coerceAtLeast(0)
        var delay = base
        repeat(extra) {
            delay = delay.multipliedBy(2)
            if (delay >= max) return max // seul plafond ; arrête aussi le doublement avant tout débordement
        }
        return delay
    }
}

/** Préférences privées (sauvegarde désactivée, MOB-S-09) : deux nombres, aucun secret. */
class PrefsThrottleStore(
    context: Context,
) : ThrottleStore {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun load(): ThrottleState {
        val until = prefs.getLong(KEY_LOCKED_UNTIL, NONE)
        return ThrottleState(prefs.getInt(KEY_FAILURES, 0), if (until == NONE) null else Instant.ofEpochMilli(until))
    }

    override fun save(state: ThrottleState) {
        prefs.edit(commit = true) {
            putInt(KEY_FAILURES, state.failures)
            putLong(KEY_LOCKED_UNTIL, state.lockedUntil?.toEpochMilli() ?: NONE)
        }
    }

    companion object {
        const val FILE = "isc-recovery-throttle"
        private const val KEY_FAILURES = "failures"
        private const val KEY_LOCKED_UNTIL = "lockedUntil"
        private const val NONE = -1L
    }
}

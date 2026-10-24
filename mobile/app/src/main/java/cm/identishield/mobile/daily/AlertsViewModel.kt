package cm.identishield.mobile.daily

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cm.identishield.mobile.data.Alert
import cm.identishield.mobile.data.AlertRepository
import cm.identishield.mobile.data.ApiResult
import cm.identishield.mobile.data.SessionHolder
import cm.identishield.mobile.data.SignedAction
import cm.identishield.mobile.security.DeviceKeyManager
import cm.identishield.mobile.security.LocalUnlock
import cm.identishield.mobile.security.SignedPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Duration

/** Attente injectable entre deux interrogations : aucun test n'attend en temps réel. */
fun interface Sleeper {
    suspend fun sleep(duration: Duration)
}

/** Écran du flux F : rien (accueil), liste (E-23) ou détail (E-24). */
sealed interface AlertsScreen {
    data object None : AlertsScreen

    data object Listing : AlertsScreen

    data class Detail(
        val alert: Alert,
        val problem: Problem? = null,
        val busy: Boolean = false,
        val accountState: String? = null, // renseigné après une réponse
    ) : AlertsScreen
}

/**
 * Flux F (ISC-MOB-SPEC §9) : alertes de sécurité du compte, interrogées à l'ouverture puis toutes
 * les POLL_FOREGROUND au premier plan seulement (décision du propriétaire, M-08 : le jeton ne vit
 * qu'en mémoire). Réponse binaire SIGNÉE, sur le téléphone lié seulement, avec un nonce neuf :
 * accountId|ALERT_ACK|alertId|nonce ou accountId|ALERT_NOT_ME|alertId|nonce. Une alerte n'affiche
 * jamais de lien, de code ni de champ de saisie (MOB-S-15).
 */
class AlertsViewModel(
    private val repository: AlertRepository,
    private val keys: DeviceKeyManager,
    private val session: SessionHolder,
    private val pollInterval: Duration,
    private val sleeper: Sleeper,
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope = scope ?: viewModelScope
    private val _alerts = MutableStateFlow<List<Alert>>(emptyList())
    val alerts: StateFlow<List<Alert>> = _alerts.asStateFlow()
    private val _screen = MutableStateFlow<AlertsScreen>(AlertsScreen.None)
    val screen: StateFlow<AlertsScreen> = _screen.asStateFlow()
    private val _sessionLost = MutableStateFlow(false)

    /** Vrai quand un 401 révèle une session expirée : l'accueil revient à E-10. */
    val sessionLost: StateFlow<Boolean> = _sessionLost.asStateFlow()
    private var inFlight = false

    /**
     * À l'ouverture, puis toutes les POLL_FOREGROUND, tant qu'un écran du quotidien est au premier
     * plan (L-B4-2, carte B6) ; l'appelant arrête la boucle dès l'arrière-plan. Requête automatique :
     * le serveur ne la compte pas comme activité de l'usager.
     */
    suspend fun pollWhileForeground() {
        while (true) {
            refresh()
            sleeper.sleep(pollInterval)
        }
    }

    suspend fun refresh() {
        val token = session.token ?: return
        when (val r = repository.alerts(token)) {
            is ApiResult.Ok -> _alerts.value = r.value.items
            is ApiResult.Refused -> if (r.status == HTTP_UNAUTHORIZED) loseSession()
            ApiResult.Unreachable -> Unit // l'alerte attend la prochaine interrogation (SPEC §8.4)
        }
    }

    /** Alertes qui attendent une réponse : bandeau et compteur de l'accueil. */
    fun pending(): List<Alert> = _alerts.value.filter { it.status == STATUS_OPEN && it.actions.isNotEmpty() }

    fun openList() {
        _screen.value = AlertsScreen.Listing
    }

    fun open(alert: Alert) {
        _screen.value = AlertsScreen.Detail(alert)
    }

    fun back() {
        if (inFlight) return
        _screen.value =
            when (_screen.value) {
                is AlertsScreen.Detail -> AlertsScreen.Listing
                else -> AlertsScreen.None
            }
    }

    fun close() {
        if (!inFlight) _screen.value = AlertsScreen.None
    }

    /** « C'est moi » ([notMe] = faux) ou « Ce n'est pas moi » ([notMe] = vrai), signé localement. */
    fun answer(
        notMe: Boolean,
        unlock: LocalUnlock,
    ) {
        val current = _screen.value as? AlertsScreen.Detail ?: return
        val alert = current.alert
        val action = if (notMe) ACTION_NOT_ME else ACTION_ACK
        if (alert.status != STATUS_OPEN || action !in alert.actions) return // informative : aucune action
        val token = session.token ?: return loseSession()
        val accountId = session.accountId ?: return loseSession()
        if (inFlight) return
        inFlight = true
        scope.launch {
            try {
                _screen.value = current.copy(problem = null, busy = true)
                val nonce =
                    when (val r = repository.nonce(token)) {
                        is ApiResult.Ok -> r.value.nonce
                        is ApiResult.Refused -> return@launch if (r.status == HTTP_UNAUTHORIZED) loseSession() else fail(current, DailyError.UNEXPECTED)
                        ApiResult.Unreachable -> return@launch fail(current, DailyError.UNREACHABLE)
                    }
                val payload = {
                    if (notMe) SignedPayload.alertNotMe(accountId, alert.alertId, nonce) else SignedPayload.alertAck(accountId, alert.alertId, nonce)
                }
                val signature =
                    when (val s = signLocally(keys, unlock, payload)) {
                        is Signed.Done -> s.base64
                        is Signed.Failed -> return@launch fail(current, s.error)
                    }
                val signed = SignedAction(nonce, signature)
                val r = if (notMe) repository.deny(token, alert.alertId, signed) else repository.acknowledge(token, alert.alertId, signed)
                when (r) {
                    is ApiResult.Ok -> {
                        val answered = alert.copy(status = r.value.status, actions = emptyList())
                        _alerts.value = _alerts.value.map { if (it.alertId == alert.alertId) answered else it }
                        _screen.value = AlertsScreen.Detail(answered, accountState = r.value.accountState)
                    }
                    is ApiResult.Refused -> if (r.status == HTTP_UNAUTHORIZED) loseSession() else fail(current, DailyError.UNEXPECTED)
                    ApiResult.Unreachable -> fail(current, DailyError.UNREACHABLE)
                }
            } finally {
                inFlight = false
            }
        }
    }

    /** L'accueil a pris acte de la session perdue. */
    fun sessionLossHandled() {
        _sessionLost.value = false
    }

    private fun fail(
        current: AlertsScreen.Detail,
        error: DailyError,
    ) {
        _screen.value = current.copy(problem = Problem(error), busy = false)
    }

    private fun loseSession() {
        _alerts.value = emptyList()
        _screen.value = AlertsScreen.None
        _sessionLost.value = true
    }

    class Factory(
        private val repository: AlertRepository,
        private val keys: DeviceKeyManager,
        private val session: SessionHolder,
        private val pollInterval: Duration,
        private val sleeper: Sleeper,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AlertsViewModel(repository, keys, session, pollInterval, sleeper) as T
    }

    companion object {
        const val STATUS_OPEN = "OPEN"
        const val ACTION_ACK = "ACK"
        const val ACTION_NOT_ME = "NOT_ME"
        private const val HTTP_UNAUTHORIZED = 401
    }
}

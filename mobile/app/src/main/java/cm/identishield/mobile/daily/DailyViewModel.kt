package cm.identishield.mobile.daily

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import cm.identishield.mobile.data.Account
import cm.identishield.mobile.data.ApiResult
import cm.identishield.mobile.data.DailyRepository
import cm.identishield.mobile.data.DeferredOperation
import cm.identishield.mobile.data.Holder
import cm.identishield.mobile.data.SessionHolder
import cm.identishield.mobile.data.SessionRequest
import cm.identishield.mobile.data.SignedAction
import cm.identishield.mobile.data.Transaction
import cm.identishield.mobile.data.TransferRequest
import cm.identishield.mobile.security.BoundDeviceStore
import cm.identishield.mobile.security.DeviceKeyManager
import cm.identishield.mobile.security.LocalUnlock
import cm.identishield.mobile.security.SignedPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Erreurs affichées dans les flux B et C (SPEC §6, §7.4) ; les textes vivent dans strings.xml. */
enum class DailyError {
    CREDENTIALS_INVALID, // E-10 : réponse uniforme (ES-22) ; E-15 : « Code PIN incorrect » (SPEC §7.4)
    THROTTLED, // Retry-After : délai, jamais « compte bloqué » (ES-21)
    KEY_REVOKED, // un autre téléphone a été lié : message simple, flux D en M-08
    KEY_INVALIDATED, // nouvelle biométrie sur le téléphone (MOB-S-03) : flux D en M-08
    ACCOUNT_FROZEN,
    UNLOCK_CANCELLED,
    UNREACHABLE,
    SESSION_EXPIRED, // 401 en lecture : retour à E-10
    INSUFFICIENT_FUNDS,
    NUMBER_INVALID,
    BENEFICIARY_UNKNOWN,
    CANCEL_UNAVAILABLE, // l'envoi différé n'est plus annulable (déjà exécuté ou annulé)
    UNEXPECTED,
}

data class Problem(
    val error: DailyError,
    val retryAfterSeconds: Long? = null,
)

/** Issue d'un envoi qui n'aboutit pas (E-18), ou dont l'issue est inconnue. */
enum class Outcome {
    FROZEN, // décision FREEZE, ou compte gelé
    REJECTED, // signature ou nonce refusé : « Réessayez. »
    REJECTED_KEY, // clé révoquée : « Réessayez depuis votre téléphone lié. »
    UNKNOWN, // réponse perdue APRÈS l'envoi : jamais de renvoi, l'historique fait foi (SPEC §7.4)
}

/** Un état par écran des flux B et C. */
sealed interface DailyStep {
    data object Starting : DailyStep

    /** Pas de liaison locale complète (numéro, device_id et clé) : retour au flux A. */
    data object NotBound : DailyStep

    data class Unlock(
        val msisdn: String,
        val problem: Problem? = null,
    ) : DailyStep // E-10

    data object Opening : DailyStep // E-04

    data class Home(
        val account: Account,
        val transactions: List<Transaction>,
        val problem: Problem? = null,
    ) : DailyStep // E-11

    data class History(
        val items: List<Transaction>,
    ) : DailyStep // E-19

    /** [draft] : numéro en cours de saisie, restitué après rotation, mort du processus ou expiration de session (D4). */
    data class Beneficiary(
        val recents: List<Transaction>,
        val problem: Problem? = null,
        val draft: String = "",
    ) : DailyStep // E-12

    /** [draft] : chiffres du montant en cours de saisie (D4). */
    data class Amount(
        val holder: Holder,
        val balanceXaf: Long,
        val draft: String = "",
    ) : DailyStep // E-13

    data class Confirm(
        val holder: Holder,
        val amountXaf: Long,
    ) : DailyStep // E-14

    data class Authorize(
        val holder: Holder,
        val amountXaf: Long,
        val problem: Problem? = null,
        val busy: Boolean = false,
    ) : DailyStep // E-15

    data class Sent(
        val holder: Holder,
        val amountXaf: Long,
        val transferId: String,
    ) : DailyStep // E-16

    data class Deferred(
        val transferId: String,
        val amountXaf: Long,
        val msisdn: String,
        val holderName: String?,
        val executeAt: String?,
        val cancelled: Boolean = false,
        val problem: Problem? = null,
    ) : DailyStep // E-17

    data class Stopped(
        val outcome: Outcome,
    ) : DailyStep // E-18
}

/**
 * Flux B (ouverture de session) et C (envoi signé). Le serveur décide (P-2) ; l'application signe
 * après déverrouillage LOCAL et rapporte. Le jeton de session ne vit qu'en mémoire (MOB-S-10).
 */
class DailyViewModel(
    private val repository: DailyRepository,
    private val keys: DeviceKeyManager,
    private val device: BoundDeviceStore,
    private val session: SessionHolder,
    scope: CoroutineScope? = null,
    private val savedState: SavedStateHandle? = null, // D4 : position du flux C, jamais un secret
) : ViewModel() {
    private val scope: CoroutineScope = scope ?: viewModelScope
    private val _step = MutableStateFlow<DailyStep>(DailyStep.Starting)
    val step: StateFlow<DailyStep> = _step.asStateFlow()

    private var account: Account? = null // solde affiché en E-13 ; jamais persisté
    private var inFlight = false

    init {
        // D4 : chaque écran du flux C note sa position (bénéficiaire, montant ; jamais le PIN ni le jeton).
        this.scope.launch { step.collect { rememberPosition(it) } }
    }

    /**
     * E-01 → E-10, ou E-11 directement si une session vient d'être ouverte (fin du flux A).
     * Sans effet une fois le flux engagé : une activité recréée (rotation) ne remet rien à zéro.
     */
    fun start() {
        if (_step.value != DailyStep.Starting && _step.value != DailyStep.NotBound) return
        val token = session.token
        if (token != null) exclusive { openHome(token) } else lock()
    }

    // ---- E-10 : ouverture de session ----------------------------------------------------------

    /** Défi du serveur → déverrouillage local → signature des octets UTF-8 du défi → session. */
    fun unlock(
        pin: String,
        unlock: LocalUnlock,
    ) {
        if (_step.value !is DailyStep.Unlock) return
        val bound = device.load() ?: return lock()
        exclusive {
            _step.value = DailyStep.Opening
            val challenge =
                when (val r = repository.challenge(bound.deviceId)) {
                    is ApiResult.Ok -> r.value.challenge
                    is ApiResult.Refused -> return@exclusive lock(problemOf(r))
                    ApiResult.Unreachable -> return@exclusive lock(Problem(DailyError.UNREACHABLE))
                }
            val signature =
                when (val s = signLocally(keys, unlock) { SignedPayload.sessionChallenge(challenge) }) {
                    is Signed.Done -> s.base64
                    is Signed.Failed -> return@exclusive lock(Problem(s.error))
                }
            val request = SessionRequest(bound.msisdn, pin, bound.deviceId, challenge, signature)
            when (val r = repository.openSession(request)) {
                is ApiResult.Ok -> {
                    session.token = r.value.sessionToken
                    openHome(r.value.sessionToken)
                }
                is ApiResult.Refused -> lock(problemOf(r))
                ApiResult.Unreachable -> lock(Problem(DailyError.UNREACHABLE))
            }
        }
    }

    // ---- E-11, E-19 : lectures avec le jeton ---------------------------------------------------

    fun goHome() {
        clearPosition() // retour volontaire à l'accueil : la saisie en cours est abandonnée (D4)
        withToken { openHome(it) }
    }

    fun openHistory() =
        withToken { token ->
            when (val r = repository.transactions(token)) {
                is ApiResult.Ok -> _step.value = DailyStep.History(r.value.items)
                else -> readFailed(r)
            }
        }

    /** E-11 → E-17 : un envoi différé, à annuler tant qu'il n'est pas exécuté. */
    fun openDeferred(operation: DeferredOperation) {
        if (_step.value !is DailyStep.Home) return
        _step.value =
            DailyStep.Deferred(operation.transferId, operation.amountXaf, operation.beneficiaryMsisdn, null, operation.executeAt)
    }

    // ---- E-12 → E-15 : préparation de l'envoi --------------------------------------------------

    /** E-12 : les « récents » sont relus dans /transactions à chaque fois, sans cache. */
    fun startSend() =
        withToken { token ->
            when (val r = repository.transactions(token)) {
                is ApiResult.Ok -> _step.value = DailyStep.Beneficiary(recentBeneficiaries(r.value.items))
                else -> readFailed(r)
            }
        }

    /** E-12 : forme locale (9 chiffres) ; le serveur dit si le numéro existe et donne le titulaire. */
    fun chooseBeneficiary(raw: String) {
        val current = _step.value as? DailyStep.Beneficiary ?: return
        val digits = raw.filter { !it.isWhitespace() }
        if (digits.length != NATIONAL_NUMBER_LENGTH || !digits.all { it.isDigit() }) {
            _step.value = current.copy(problem = Problem(DailyError.NUMBER_INVALID))
            return
        }
        withToken { token ->
            when (val r = repository.holder(token, digits)) {
                is ApiResult.Ok -> _step.value = DailyStep.Amount(r.value, account?.balanceXaf ?: 0)
                is ApiResult.Refused ->
                    when {
                        r.status == HTTP_UNAUTHORIZED -> lock(Problem(DailyError.SESSION_EXPIRED))
                        r.status == HTTP_NOT_FOUND -> _step.value = current.copy(problem = Problem(DailyError.BENEFICIARY_UNKNOWN))
                        r.status == HTTP_BAD_REQUEST -> _step.value = current.copy(problem = Problem(DailyError.NUMBER_INVALID))
                        else -> _step.value = current.copy(problem = problemOf(r))
                    }
                ApiResult.Unreachable -> _step.value = current.copy(problem = Problem(DailyError.UNREACHABLE))
            }
        }
    }

    /** E-12 : le numéro tapé, gardé dans l'état (D4). */
    fun updateBeneficiaryDraft(value: String) {
        val s = _step.value as? DailyStep.Beneficiary ?: return
        _step.value = s.copy(draft = value)
    }

    /** E-13 : les chiffres tapés, gardés dans l'état (D4). */
    fun updateAmountDraft(value: String) {
        val s = _step.value as? DailyStep.Amount ?: return
        _step.value = s.copy(draft = value)
    }

    /** E-13 → E-14. Le solde n'est qu'indicatif : le serveur refuse un solde insuffisant (SPEC §7.4). */
    fun submitAmount(amountXaf: Long) {
        val current = _step.value as? DailyStep.Amount ?: return
        if (amountXaf < 1) return
        _step.value = DailyStep.Confirm(current.holder, amountXaf)
    }

    /** E-14 → E-15. */
    fun confirmTransfer() {
        val current = _step.value as? DailyStep.Confirm ?: return
        _step.value = DailyStep.Authorize(current.holder, current.amountXaf)
    }

    // ---- E-15 : l'envoi signé ------------------------------------------------------------------

    /**
     * Nonce NEUF à chaque tentative (ES-10) → chaîne ADR-018 → déverrouillage local → signature →
     * /transfers. Une réponse perdue APRÈS l'envoi n'est jamais rejouée : l'issue est inconnue.
     */
    fun authorize(
        pin: String,
        unlock: LocalUnlock,
    ) {
        val current = _step.value as? DailyStep.Authorize ?: return
        val owner = account ?: return lock(Problem(DailyError.SESSION_EXPIRED))
        withToken { token ->
            _step.value = current.copy(problem = null, busy = true)
            val nonce =
                when (val r = repository.nonce(token)) {
                    is ApiResult.Ok -> r.value.nonce
                    is ApiResult.Refused -> {
                        if (r.status == HTTP_UNAUTHORIZED) {
                            lock(Problem(DailyError.SESSION_EXPIRED))
                        } else {
                            _step.value = current.copy(problem = problemOf(r))
                        }
                        return@withToken
                    }
                    ApiResult.Unreachable -> {
                        _step.value = current.copy(problem = Problem(DailyError.UNREACHABLE))
                        return@withToken
                    }
                }
            val signature =
                when (
                    val s =
                        signLocally(keys, unlock) {
                            SignedPayload.transfer(owner.accountId, TRANSFER_TYPE, current.amountXaf, current.holder.beneficiaryId, nonce)
                        }
                ) {
                    is Signed.Done -> s.base64
                    is Signed.Failed -> {
                        _step.value = current.copy(problem = Problem(s.error))
                        return@withToken
                    }
                }
            val request = TransferRequest(TRANSFER_TYPE, current.amountXaf, current.holder.beneficiaryId, nonce, signature, pin)
            _step.value =
                when (val r = repository.transfer(token, request)) {
                    is ApiResult.Ok -> decisionStep(current, r.value.transferId, r.value.decision, r.value.executeAt)
                    is ApiResult.Refused -> transferRefused(current, r)
                    ApiResult.Unreachable -> DailyStep.Stopped(Outcome.UNKNOWN)
                }
        }
    }

    // ---- E-17 : annulation signée --------------------------------------------------------------

    /** Nonce neuf → accountId|CANCEL|transferId|nonce → déverrouillage local → annulation. */
    fun cancelDeferred(unlock: LocalUnlock) {
        val current = _step.value as? DailyStep.Deferred ?: return
        if (current.cancelled) return
        val owner = account ?: return lock(Problem(DailyError.SESSION_EXPIRED))
        withToken { token ->
            val nonce =
                when (val r = repository.nonce(token)) {
                    is ApiResult.Ok -> r.value.nonce
                    is ApiResult.Refused -> {
                        if (r.status == HTTP_UNAUTHORIZED) {
                            lock(Problem(DailyError.SESSION_EXPIRED))
                        } else {
                            _step.value = current.copy(problem = problemOf(r))
                        }
                        return@withToken
                    }
                    ApiResult.Unreachable -> {
                        _step.value = current.copy(problem = Problem(DailyError.UNREACHABLE))
                        return@withToken
                    }
                }
            val signature =
                when (val s = signLocally(keys, unlock) { SignedPayload.cancel(owner.accountId, current.transferId, nonce) }) {
                    is Signed.Done -> s.base64
                    is Signed.Failed -> {
                        _step.value = current.copy(problem = Problem(s.error))
                        return@withToken
                    }
                }
            _step.value =
                when (val r = repository.cancel(token, current.transferId, SignedAction(nonce, signature))) {
                    is ApiResult.Ok -> current.copy(cancelled = true, problem = null)
                    is ApiResult.Refused ->
                        when (r.status) {
                            HTTP_UNAUTHORIZED -> return@withToken lock(Problem(DailyError.SESSION_EXPIRED))
                            HTTP_NOT_FOUND -> current.copy(problem = Problem(DailyError.CANCEL_UNAVAILABLE))
                            else -> current.copy(problem = problemOf(r))
                        }
                    // Réponse perdue : l'annulation a peut-être eu lieu ; l'historique fait foi.
                    ApiResult.Unreachable -> current.copy(problem = Problem(DailyError.UNREACHABLE))
                }
        }
    }

    /** Session perdue ailleurs (401 sur les alertes, flux F) : retour à E-10. */
    fun sessionLost() {
        if (_step.value is DailyStep.Unlock || _step.value is DailyStep.NotBound) return
        lock(Problem(DailyError.SESSION_EXPIRED))
    }

    /** E-27a « Sécuriser à nouveau ce téléphone » : on quitte le flux B pour le flux D. */
    fun leaveForReenrolment() {
        session.token = null
        session.accountId = null
        account = null
        _step.value = DailyStep.NotBound
    }

        /** Retour arrière : jamais pendant un appel ; jamais vers un écran de saisie après un envoi. */
    fun back() {
        if (inFlight) return
        when (val s = _step.value) {
            is DailyStep.Confirm -> _step.value = DailyStep.Amount(s.holder, account?.balanceXaf ?: 0)
            is DailyStep.Authorize -> _step.value = DailyStep.Confirm(s.holder, s.amountXaf)
            is DailyStep.Amount -> startSend()
            is DailyStep.Beneficiary, is DailyStep.History, is DailyStep.Sent,
            is DailyStep.Deferred, is DailyStep.Stopped,
            -> goHome()
            else -> Unit
        }
    }

    // ---- interne -------------------------------------------------------------------------------

    private suspend fun openHome(token: String) {
        val a = repository.account(token)
        val t = repository.transactions(token)
        if (a is ApiResult.Ok && t is ApiResult.Ok) {
            account = a.value
            session.accountId = a.value.accountId // signature des réponses aux alertes (flux F)
            val position = savedPosition() // lue AVANT l'accueil, qui l'efface
            _step.value = DailyStep.Home(a.value, t.value.items)
            if (position != null) resume(token, position, t.value.items)
        } else {
            readFailed(if (a !is ApiResult.Ok) a else t)
        }
    }

    /** Une lecture qui échoue : 401 = session expirée → E-10 (écart documenté) ; sinon message. */
    private fun readFailed(r: ApiResult<*>) {
        val problem =
            when (r) {
                is ApiResult.Refused -> if (r.status == HTTP_UNAUTHORIZED) Problem(DailyError.SESSION_EXPIRED) else problemOf(r)
                else -> Problem(DailyError.UNREACHABLE)
            }
        val home = _step.value as? DailyStep.Home
        if (home != null && problem.error != DailyError.SESSION_EXPIRED) {
            _step.value = home.copy(problem = problem)
        } else {
            lock(problem)
        }
    }

    // ---- D4 : position du flux C, restituée après reconnexion ------------------------------------

    private data class Position(
        val stage: String,
        val msisdn: String,
        val amount: String,
    )

    private fun rememberPosition(step: DailyStep) {
        val saved = savedState ?: return
        val position =
            when (step) {
                is DailyStep.Beneficiary -> Position(STAGE_BENEFICIARY, step.draft, "")
                is DailyStep.Amount -> Position(STAGE_AMOUNT, step.holder.msisdn, step.draft)
                is DailyStep.Confirm -> Position(STAGE_CONFIRM, step.holder.msisdn, step.amountXaf.toString())
                is DailyStep.Authorize -> Position(STAGE_AUTHORIZE, step.holder.msisdn, step.amountXaf.toString())
                // Verrou et attente : la position est gardée pour la reprise ; tout autre écran l'efface.
                is DailyStep.Unlock, DailyStep.Opening, DailyStep.Starting -> return
                else -> null
            }
        saved[KEY_STAGE] = position?.stage
        saved[KEY_MSISDN] = position?.msisdn
        saved[KEY_AMOUNT] = position?.amount
    }

    private fun clearPosition() {
        val saved = savedState ?: return
        saved[KEY_STAGE] = null
        saved[KEY_MSISDN] = null
        saved[KEY_AMOUNT] = null
    }

    private fun savedPosition(): Position? {
        val saved = savedState ?: return null
        val stage = saved.get<String>(KEY_STAGE) ?: return null
        return Position(stage, saved.get<String>(KEY_MSISDN).orEmpty(), saved.get<String>(KEY_AMOUNT).orEmpty())
    }

    /** La saisie reprend là où la session s'est perdue ; le titulaire est redemandé au serveur (jamais conservé). */
    private suspend fun resume(
        token: String,
        position: Position,
        transactions: List<Transaction>,
    ) {
        if (position.stage == STAGE_BENEFICIARY) {
            _step.value = DailyStep.Beneficiary(recentBeneficiaries(transactions), draft = position.msisdn)
            return
        }
        val holder = (repository.holder(token, position.msisdn) as? ApiResult.Ok)?.value ?: return
        val amount = position.amount.toLongOrNull()
        _step.value =
            when {
                position.stage == STAGE_AMOUNT -> DailyStep.Amount(holder, account?.balanceXaf ?: 0, draft = position.amount)
                amount == null -> return
                position.stage == STAGE_CONFIRM -> DailyStep.Confirm(holder, amount)
                else -> DailyStep.Authorize(holder, amount) // le PIN est redemandé : il n'est jamais gardé
            }
    }

    private fun decisionStep(
        current: DailyStep.Authorize,
        transferId: String,
        decision: String,
        executeAt: String?,
    ): DailyStep =
        when (decision) {
            DECISION_ALLOW -> DailyStep.Sent(current.holder, current.amountXaf, transferId)
            DECISION_STEP_UP ->
                DailyStep.Deferred(transferId, current.amountXaf, current.holder.msisdn, current.holder.holderName, executeAt)
            DECISION_FREEZE -> DailyStep.Stopped(Outcome.FROZEN)
            DECISION_REJECTED -> DailyStep.Stopped(Outcome.REJECTED)
            else -> DailyStep.Stopped(Outcome.UNKNOWN)
        }

    private fun transferRefused(
        current: DailyStep.Authorize,
        r: ApiResult.Refused,
    ): DailyStep =
        when {
            r.status == 0 -> DailyStep.Stopped(Outcome.UNKNOWN) // réponse illisible APRÈS l'envoi
            r.reason == REASON_KEY_REVOKED -> DailyStep.Stopped(Outcome.REJECTED_KEY)
            r.reason == REASON_DECISION_REJECTED -> DailyStep.Stopped(Outcome.REJECTED)
            r.reason == REASON_ACCOUNT_FROZEN -> DailyStep.Stopped(Outcome.FROZEN)
            else -> current.copy(problem = problemOf(r), busy = false)
        }

    private fun problemOf(r: ApiResult.Refused): Problem =
        when (r.reason) {
            REASON_CREDENTIALS -> Problem(DailyError.CREDENTIALS_INVALID)
            REASON_THROTTLED -> Problem(DailyError.THROTTLED, r.retryAfterSeconds)
            REASON_KEY_REVOKED -> Problem(DailyError.KEY_REVOKED)
            REASON_ACCOUNT_FROZEN -> Problem(DailyError.ACCOUNT_FROZEN)
            REASON_INSUFFICIENT_FUNDS -> Problem(DailyError.INSUFFICIENT_FUNDS)
            else -> Problem(DailyError.UNEXPECTED)
        }

    /** E-10 : numéro mémorisé seulement. Le jeton et le compte en mémoire sont oubliés. */
    private fun lock(problem: Problem? = null) {
        session.token = null
        session.accountId = null
        account = null
        val bound = device.load()
        _step.value =
            if (bound == null || !keys.hasDeviceKey()) DailyStep.NotBound else DailyStep.Unlock(bound.msisdn, problem)
    }

    private fun withToken(block: suspend (String) -> Unit) {
        val token = session.token ?: return lock(Problem(DailyError.SESSION_EXPIRED))
        exclusive { block(token) }
    }

    /** Un seul appel serveur à la fois : un double appui ne rejoue jamais une étape. */
    private fun exclusive(block: suspend () -> Unit) {
        if (inFlight) return
        inFlight = true
        scope.launch {
            try {
                block()
            } finally {
                inFlight = false
            }
        }
    }

    class Factory(
        private val repository: DailyRepository,
        private val keys: DeviceKeyManager,
        private val device: BoundDeviceStore,
        private val session: SessionHolder,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(
            modelClass: Class<T>,
            extras: CreationExtras,
        ): T = DailyViewModel(repository, keys, device, session, savedState = extras.createSavedStateHandle()) as T
    }

    companion object {
        /** Plan de numérotation camerounais : 9 chiffres (forme seulement ; plage côté serveur). */
        const val NATIONAL_NUMBER_LENGTH = 9
        const val TRANSFER_TYPE = "P2P" // seul type du contrat (TransferRequest.type)
        const val DECISION_ALLOW = "ALLOW"
        const val DECISION_STEP_UP = "STEP_UP"
        const val DECISION_FREEZE = "FREEZE"
        const val DECISION_REJECTED = "REJECTED"
        const val REASON_CREDENTIALS = "CREDENTIALS_INVALID"
        const val REASON_THROTTLED = "THROTTLED"
        const val REASON_KEY_REVOKED = "KEY_REVOKED"
        const val REASON_ACCOUNT_FROZEN = "ACCOUNT_FROZEN"
        const val REASON_INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS"
        const val REASON_DECISION_REJECTED = "DECISION_REJECTED"
        private const val HTTP_BAD_REQUEST = 400
        private const val KEY_STAGE = "flowC.stage"
        private const val KEY_MSISDN = "flowC.msisdn"
        private const val KEY_AMOUNT = "flowC.amount"
        private const val STAGE_BENEFICIARY = "BENEFICIARY"
        private const val STAGE_AMOUNT = "AMOUNT"
        private const val STAGE_CONFIRM = "CONFIRM"
        private const val STAGE_AUTHORIZE = "AUTHORIZE"
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_NOT_FOUND = 404

        /** E-12 : bénéficiaires déjà payés (envois exécutés), du plus récent au plus ancien, sans doublon. */
        fun recentBeneficiaries(items: List<Transaction>): List<Transaction> =
            items.filter { it.direction == DIRECTION_OUT && it.status == STATUS_EXECUTED }.distinctBy { it.counterpartyMsisdn }

        const val DIRECTION_OUT = "OUT"
        const val STATUS_EXECUTED = "EXECUTED"
    }
}

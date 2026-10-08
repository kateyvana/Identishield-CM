package cm.identishield.mobile.enrolment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cm.identishield.mobile.data.Account
import cm.identishield.mobile.data.ApiResult
import cm.identishield.mobile.data.CompleteRequest
import cm.identishield.mobile.data.ConfirmCharacter
import cm.identishield.mobile.data.ConfirmRequest
import cm.identishield.mobile.data.EnrolmentRepository
import cm.identishield.mobile.data.SessionHolder
import cm.identishield.mobile.security.BoundDevice
import cm.identishield.mobile.security.BoundDeviceStore
import cm.identishield.mobile.security.DeviceKeyManager
import cm.identishield.mobile.security.GeneratedKey
import cm.identishield.mobile.security.InsecureKeyStorageException
import cm.identishield.mobile.security.LocalUnlock
import cm.identishield.mobile.security.RecoveryThrottle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.util.Base64

/** Erreurs affichées sur les écrans des flux A et D (SPEC §5.3, §8.4) ; les textes vivent dans strings.xml. */
enum class EnrolError {
    NUMBER_INVALID, // format local (9 chiffres) ou plage refusée par le serveur (REQUEST_INVALID)
    CREDENTIALS_INVALID, // compte inexistant OU PIN faux : un seul message (ES-22)
    OTP_INVALID,
    OTP_EXPIRED,
    SESSION_EXPIRED, // ENROLMENT_EXPIRED : l'enrôlement a expiré (ENROL_TTL), recommencer
    UNREACHABLE, // MOB-NF-03 : message clair, jamais de mise en file
    RECOVERY_INVALID, // « Clé incorrecte » : même message pour tout le monde (ES-22, SPEC §8.3)
    REENROL_FROZEN, // ré-enrôlements gelés après « ce n'est pas moi » (ES-27, REENROL_FREEZE)
    DEVICE_REFUSED, // puce logicielle ou attestation refusée : E-27b (M-08)
    UNLOCK_CANCELLED,
    CONFIRM_MISMATCH,
    UNEXPECTED,
}

/** Étapes 3 d'E-07 : clé créée, déverrouillage local, enregistrement auprès du serveur. */
enum class SecuringStage { CREATING_KEY, UNLOCKING, REGISTERING }

/** Un état par écran des flux A et D (E-02 à E-09, E-11, E-20 à E-22, E-26). */
sealed interface EnrolStep {
    data object Welcome : EnrolStep // E-02

    data class Number(
        val error: EnrolError? = null,
    ) : EnrolStep // E-03

    data class Pin(
        val msisdn: String,
        val error: EnrolError? = null,
    ) : EnrolStep // E-06

    data class Checking(
        val msisdn: String,
    ) : EnrolStep // E-04

    data class Otp(
        val msisdn: String,
        val error: EnrolError? = null,
        val resendGeneration: Int = 0,
    ) : EnrolStep // E-05

    data class Securing(
        val stage: SecuringStage,
        val error: EnrolError? = null,
    ) : EnrolStep // E-07

    data class RecoveryKey(
        val code: String,
        val lastDisplay: Boolean,
    ) : EnrolStep // E-08

    data class Confirm(
        val positions: List<Int>,
        val error: EnrolError? = null,
    ) : EnrolStep // E-09

    data class Home(
        val account: Account,
    ) : EnrolStep // E-11

    data class Recovery(
        val error: EnrolError? = null,
    ) : EnrolStep // E-20

    data class RecoveryWait(
        val until: Instant,
    ) : EnrolStep // E-21

    data object KeyLost : EnrolStep // E-26

    data class Rebound(
        val msisdn: String,
    ) : EnrolStep // E-22
}

/**
 * Flux A — premier enrôlement — et flux D — ré-enrôlement par clé de récupération (M-08). Le serveur
 * décide (P-2) : c'est LUI qui répond RECOVERY ; l'application ne devine jamais pourquoi.
 * Garde-fous : la clé n'est créée QU'APRÈS la validation de l'OTP ou de la clé de récupération par le
 * serveur (ES-25) ; en flux D, l'ancienne clé locale est détruite AVANT la création de la nouvelle ; le
 * PIN et les clés de récupération ne vivent qu'en mémoire ; la clé saisie n'est jamais conservée.
 */
class EnrolmentViewModel(
    private val repository: EnrolmentRepository,
    private val keys: DeviceKeyManager,
    scope: CoroutineScope? = null,
    private val boundDevice: BoundDeviceStore? = null, // numéro + device_id pour le flux B (M-07)
    private val session: SessionHolder? = null, // jeton ouvert en E-09, repris par le flux B (M-07)
    private val throttle: RecoveryThrottle? = null, // délai croissant local du flux D (M-08) ; absent = refus
) : ViewModel() {
    private val scope: CoroutineScope = scope ?: viewModelScope
    private val _step = MutableStateFlow<EnrolStep>(EnrolStep.Welcome)
    val step: StateFlow<EnrolStep> = _step.asStateFlow()

    // Secrets en mémoire seulement, jamais persistés (MOB-S-10).
    private var msisdn: String? = null
    private var pin: String? = null
    private var enrolmentId: String? = null
    private var attestationChallenge: String? = null
    private var recovery: IssuedCode? = null
    private var redisplayUsed = false
    private var inFlight = false
    private var rebinding = false // flux D : la clé de récupération a été validée par le serveur
    private var beforeKeyLost: EnrolStep? = null

    private data class IssuedCode(
        val enrolmentId: String,
        val code: String,
        val positions: List<Int>,
    )

    fun begin() {
        _step.value = EnrolStep.Number()
    }

    /** E-27a « Sécuriser à nouveau ce téléphone » : numéro mémorisé, on reprend au PIN (E-06). */
    fun beginWithNumber(number: String) {
        msisdn = number
        _step.value = EnrolStep.Pin(number)
    }

    fun back() {
        _step.value =
            when (val s = _step.value) {
                is EnrolStep.Number -> EnrolStep.Welcome
                is EnrolStep.Pin -> EnrolStep.Number()
                is EnrolStep.Otp -> EnrolStep.Pin(s.msisdn)
                is EnrolStep.Recovery, is EnrolStep.RecoveryWait -> msisdn?.let { EnrolStep.Pin(it) } ?: EnrolStep.Number()
                EnrolStep.KeyLost -> beforeKeyLost ?: EnrolStep.Recovery()
                else -> s // pas de retour pendant la liaison ni après
            }
    }

    /** E-03 : contrôle de FORME seulement (9 chiffres) ; la plage 677 est vérifiée par le serveur. */
    fun submitNumber(raw: String) {
        val digits = raw.filter { it.isDigit() }
        if (digits.length != NATIONAL_NUMBER_LENGTH || digits.length != raw.count { !it.isWhitespace() }) {
            _step.value = EnrolStep.Number(EnrolError.NUMBER_INVALID)
            return
        }
        msisdn = digits
        _step.value = EnrolStep.Pin(digits)
    }

    /** E-06 → E-04 : numéro + PIN ; le serveur dit l'étape suivante. */
    fun submitPin(value: String) {
        val number = msisdn ?: return begin()
        start(number, value, resend = false)
    }

    /** E-05 : « Renvoyer le code » relance /enrolment/start (nouvel OTP, nouvel enrôlement). */
    fun resendOtp() {
        val number = msisdn ?: return begin()
        val secret = pin ?: return begin()
        start(number, secret, resend = true)
    }

    private fun start(
        number: String,
        secret: String,
        resend: Boolean,
    ) {
        val previous = _step.value
        exclusive {
            pin = secret
            if (!resend) _step.value = EnrolStep.Checking(number)
            when (val r = repository.start(number, secret)) {
                is ApiResult.Ok ->
                    when (r.value.next) {
                        NEXT_OTP -> {
                            enrolmentId = r.value.enrolmentId
                            val generation = ((previous as? EnrolStep.Otp)?.resendGeneration ?: -1) + 1
                            _step.value = EnrolStep.Otp(number, resendGeneration = generation)
                        }
                        NEXT_RECOVERY -> {
                            // Flux D : décidé par le SERVEUR (clé enregistrée, pas de signature valide).
                            enrolmentId = r.value.enrolmentId
                            _step.value = recoveryEntry()
                        }
                        else -> _step.value = EnrolStep.Pin(number, EnrolError.UNEXPECTED)
                    }
                is ApiResult.Refused ->
                    _step.value =
                        when (r.reason) {
                            REASON_CREDENTIALS -> EnrolStep.Pin(number, EnrolError.CREDENTIALS_INVALID)
                            REASON_REQUEST -> EnrolStep.Number(EnrolError.NUMBER_INVALID)
                            REASON_ACCOUNT_FROZEN -> EnrolStep.Pin(number, EnrolError.REENROL_FROZEN)
                            else -> EnrolStep.Pin(number, EnrolError.UNEXPECTED)
                        }
                ApiResult.Unreachable ->
                    _step.value =
                        if (resend) {
                            (previous as? EnrolStep.Otp)?.copy(error = EnrolError.UNREACHABLE)
                                ?: EnrolStep.Pin(number, EnrolError.UNREACHABLE)
                        } else {
                            EnrolStep.Pin(number, EnrolError.UNREACHABLE)
                        }
            }
        }
    }

    // @implements ES-25
    /** E-05 : l'OTP est validé par le SERVEUR ; seul un succès ouvre la liaison (ES-25). */
    fun submitOtp(otp: String) {
        val current = _step.value as? EnrolStep.Otp ?: return
        val id = enrolmentId ?: return begin()
        exclusive {
            when (val r = repository.verifyOtp(id, otp)) {
                is ApiResult.Ok -> {
                    attestationChallenge = r.value.attestationChallenge
                    _step.value = EnrolStep.Securing(SecuringStage.CREATING_KEY)
                }
                is ApiResult.Refused ->
                    _step.value =
                        when (r.reason) {
                            REASON_OTP_INVALID -> current.copy(error = EnrolError.OTP_INVALID)
                            REASON_OTP_EXPIRED -> current.copy(error = EnrolError.OTP_EXPIRED)
                            REASON_ENROLMENT_EXPIRED -> EnrolStep.Number(EnrolError.SESSION_EXPIRED)
                            else -> current.copy(error = EnrolError.UNEXPECTED)
                        }
                ApiResult.Unreachable -> _step.value = current.copy(error = EnrolError.UNREACHABLE)
            }
        }
    }

    /**
     * E-07 : la clé naît dans la puce avec le défi d'attestation du serveur, puis le déverrouillage
     * LOCAL signe ce défi ; seuls la clé publique, l'attestation et la signature partent au serveur.
     */
    fun secureDevice(unlock: LocalUnlock) {
        if (_step.value !is EnrolStep.Securing) return
        val challengeB64 = attestationChallenge ?: return begin() // jamais de clé sans OTP validé
        val id = enrolmentId ?: return begin()
        exclusive {
            val challenge = Base64.getDecoder().decode(challengeB64)
            val bound =
                try {
                    createAndSign(challenge, unlock)
                } catch (e: CancellationException) {
                    keys.delete()
                    throw e
                } catch (e: Exception) {
                    // Puce logicielle (MOB-S-02), panne du Keystore ou clé invalidée : jamais de
                    // plantage, et jamais de clé orpheline qu'E-01 prendrait pour un appareil lié.
                    keys.delete()
                    val stage = (_step.value as? EnrolStep.Securing)?.stage ?: SecuringStage.CREATING_KEY
                    val error = if (e is InsecureKeyStorageException) EnrolError.DEVICE_REFUSED else EnrolError.UNEXPECTED
                    _step.value = EnrolStep.Securing(stage, error)
                    return@exclusive
                }
            if (bound == null) {
                keys.delete() // pas de clé orpheline (SPEC §5.3)
                _step.value = EnrolStep.Securing(SecuringStage.UNLOCKING, EnrolError.UNLOCK_CANCELLED)
                return@exclusive
            }
            val (key, signature) = bound
            _step.value = EnrolStep.Securing(SecuringStage.REGISTERING)
            val request =
                CompleteRequest(
                    enrolmentId = id,
                    publicKey = key.publicKey,
                    attestationChain = key.attestationChain,
                    challengeSignature = Base64.getEncoder().encodeToString(signature),
                )
            when (val r = repository.complete(request)) {
                is ApiResult.Ok -> {
                    // L'appareil est lié dès cette réponse : on mémorise ce que le flux B exige.
                    val number = msisdn
                    val deviceId = r.value.deviceId
                    if (number != null && deviceId != null) boundDevice?.save(BoundDevice(number, deviceId))
                    recovery = IssuedCode(r.value.enrolmentId, r.value.recoveryCode, r.value.confirmPositions)
                    _step.value =
                        if (rebinding && number != null) {
                            EnrolStep.Rebound(number) // E-22, puis la nouvelle clé (E-08)
                        } else {
                            EnrolStep.RecoveryKey(r.value.recoveryCode, lastDisplay = false)
                        }
                }
                is ApiResult.Refused -> {
                    keys.delete() // le serveur n'a pas enregistré la clé : elle ne vaut rien
                    _step.value =
                        EnrolStep.Securing(
                            SecuringStage.REGISTERING,
                            when (r.reason) {
                                REASON_ATTESTATION_REJECTED, REASON_DEVICE_UNSUPPORTED -> EnrolError.DEVICE_REFUSED
                                REASON_ENROLMENT_EXPIRED -> EnrolError.SESSION_EXPIRED
                                else -> EnrolError.UNEXPECTED
                            },
                        )
                }
                ApiResult.Unreachable -> {
                    keys.delete() // « Connexion perdue. Recommencez. » ; la clé locale est détruite
                    _step.value = EnrolStep.Securing(SecuringStage.REGISTERING, EnrolError.UNREACHABLE)
                }
            }
        }
    }

    /**
     * Crée la clé puis la fait signer le défi après déverrouillage local ; null si l'usager annule.
     * Flux D : l'ancienne clé locale est détruite AVANT la création de la nouvelle (une seule clé).
     */
    private suspend fun createAndSign(
        challenge: ByteArray,
        unlock: LocalUnlock,
    ): Pair<GeneratedKey, ByteArray>? {
        _step.value = EnrolStep.Securing(SecuringStage.CREATING_KEY)
        if (rebinding) keys.delete()
        val key = keys.generate(challenge)
        _step.value = EnrolStep.Securing(SecuringStage.UNLOCKING)
        val signature = unlock.unlockAndSign(keys.newSigner(), challenge) ?: return null
        return key to signature
    }

    // ---- Flux D : clé de récupération (E-20, E-21, E-22, E-26) -----------------------------------

    /** E-20, ou E-21 directement si cet appareil doit encore attendre. */
    private fun recoveryEntry(error: EnrolError? = null): EnrolStep =
        throttle?.lockedUntil()?.let { EnrolStep.RecoveryWait(it) } ?: EnrolStep.Recovery(error)

    /**
     * E-20 : la clé saisie part au serveur pour CETTE tentative et n'est conservée nulle part. Échec :
     * « Clé incorrecte », puis délai croissant sur CET appareil ; jamais de blocage du compte (ES-21).
     */
    fun submitRecoveryCode(raw: String) {
        if (_step.value !is EnrolStep.Recovery) return
        val gate = throttle ?: return run { _step.value = EnrolStep.Recovery(EnrolError.UNEXPECTED) } // défaut fermé
        // Pas de garde sur le délai ici : l'état RecoveryWait refuse déjà toute saisie, et
        // recoveryEntry() consulte le délai avant d'afficher E-20 (prouvé par les tests).
        val id = enrolmentId ?: return begin()
        val code = raw.uppercase().filter { it.isLetterOrDigit() }
        exclusive {
            when (val r = repository.recover(id, code)) {
                is ApiResult.Ok -> {
                    gate.recordSuccess()
                    rebinding = true
                    attestationChallenge = r.value.attestationChallenge // ES-25 : liaison après validation
                    _step.value = EnrolStep.Securing(SecuringStage.CREATING_KEY)
                }
                is ApiResult.Refused ->
                    _step.value =
                        when (r.reason) {
                            REASON_RECOVERY_INVALID ->
                                gate.recordFailure(r.retryAfterSeconds?.let(Duration::ofSeconds))?.let { EnrolStep.RecoveryWait(it) }
                                    ?: EnrolStep.Recovery(EnrolError.RECOVERY_INVALID)
                            REASON_THROTTLED -> EnrolStep.RecoveryWait(gate.serverWait(r.retryAfterSeconds?.let(Duration::ofSeconds)))
                            REASON_ACCOUNT_FROZEN -> EnrolStep.Recovery(EnrolError.REENROL_FROZEN)
                            REASON_ENROLMENT_EXPIRED -> msisdn?.let { EnrolStep.Pin(it, EnrolError.SESSION_EXPIRED) } ?: EnrolStep.Number()
                            else -> EnrolStep.Recovery(EnrolError.UNEXPECTED)
                        }
                ApiResult.Unreachable -> _step.value = EnrolStep.Recovery(EnrolError.UNREACHABLE)
            }
        }
    }

    /** Attente restante sur cet appareil (E-21), lue sur l'horloge injectée. */
    fun waitRemaining(): Duration? = throttle?.remaining()

    /** E-21 → E-20 quand l'attente est écoulée. */
    fun recoveryWaitOver() {
        if (_step.value is EnrolStep.RecoveryWait && throttle?.remaining() == null) _step.value = EnrolStep.Recovery()
    }

    /** « J'ai perdu ma clé » (E-20, E-21) → E-26, écran d'orientation (QM-05). */
    fun lostKey() {
        val current = _step.value
        if (current !is EnrolStep.Recovery && current !is EnrolStep.RecoveryWait) return
        beforeKeyLost = current
        _step.value = EnrolStep.KeyLost
    }

    /** E-22 → E-08 : la nouvelle clé de récupération, affichée une seule fois. */
    fun showNewKey() {
        if (_step.value !is EnrolStep.Rebound) return
        val issued = recovery ?: return
        _step.value = EnrolStep.RecoveryKey(issued.code, lastDisplay = false)
    }

    /** E-08 → E-09 : « Je l'ai écrite sur papier ». */
    fun keyWrittenDown() {
        val issued = recovery ?: return
        _step.value = EnrolStep.Confirm(issued.positions)
    }

    /** E-09 : 4 caractères demandés par le serveur ; en cas d'erreur, la clé est réaffichée une fois. */
    fun confirmCharacters(characters: Map<Int, String>) {
        val issued = recovery ?: return
        val current = _step.value as? EnrolStep.Confirm ?: return
        val request =
            ConfirmRequest(
                enrolmentId = issued.enrolmentId,
                characters = issued.positions.map { ConfirmCharacter(it, characters[it].orEmpty().uppercase()) },
            )
        exclusive {
            when (val r = repository.confirm(request)) {
                is ApiResult.Ok -> loadHome(r.value.sessionToken)
                is ApiResult.Refused ->
                    _step.value =
                        if (r.reason == REASON_RECOVERY_INVALID && !redisplayUsed) {
                            redisplayUsed = true
                            EnrolStep.RecoveryKey(issued.code, lastDisplay = true)
                        } else if (r.reason == REASON_RECOVERY_INVALID) {
                            current.copy(error = EnrolError.CONFIRM_MISMATCH)
                        } else {
                            current.copy(error = EnrolError.UNEXPECTED)
                        }
                ApiResult.Unreachable -> _step.value = current.copy(error = EnrolError.UNREACHABLE)
            }
        }
    }

    private suspend fun loadHome(sessionToken: String) {
        // Fin du flux A : les secrets en mémoire sont effacés (P-8, P-9).
        pin = null
        recovery = null
        attestationChallenge = null
        enrolmentId = null
        session?.token = sessionToken // mémoire vive seulement (MOB-S-10)
        when (val r = repository.account(sessionToken)) {
            is ApiResult.Ok -> _step.value = EnrolStep.Home(r.value)
            else -> _step.value = EnrolStep.Number(EnrolError.UNEXPECTED)
        }
    }

    /** Un seul appel serveur à la fois : un double appui ne rejoue jamais une étape (OTP à usage unique). */
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

    /** Pour les tests : aucun secret ne survit à l'arrivée sur l'accueil. */
    internal fun holdsSecrets(): Boolean = pin != null || recovery != null || attestationChallenge != null

    class Factory(
        private val repository: EnrolmentRepository,
        private val keys: DeviceKeyManager,
        private val boundDevice: BoundDeviceStore,
        private val session: SessionHolder,
        private val throttle: RecoveryThrottle,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            EnrolmentViewModel(repository, keys, boundDevice = boundDevice, session = session, throttle = throttle) as T
    }

    companion object {
        /** Plan de numérotation camerounais : 9 chiffres (forme seulement ; plage côté serveur). */
        const val NATIONAL_NUMBER_LENGTH = 9
        const val NEXT_OTP = "OTP"
        const val NEXT_RECOVERY = "RECOVERY"
        const val REASON_CREDENTIALS = "CREDENTIALS_INVALID"
        const val REASON_REQUEST = "REQUEST_INVALID"
        const val REASON_OTP_INVALID = "OTP_INVALID"
        const val REASON_OTP_EXPIRED = "OTP_EXPIRED"
        const val REASON_ENROLMENT_EXPIRED = "ENROLMENT_EXPIRED"
        const val REASON_RECOVERY_INVALID = "RECOVERY_INVALID"
        const val REASON_THROTTLED = "THROTTLED"
        const val REASON_ACCOUNT_FROZEN = "ACCOUNT_FROZEN"
        const val REASON_ATTESTATION_REJECTED = "ATTESTATION_REJECTED"
        const val REASON_DEVICE_UNSUPPORTED = "DEVICE_UNSUPPORTED"
    }
}

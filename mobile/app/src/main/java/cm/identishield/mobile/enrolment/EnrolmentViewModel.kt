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
import cm.identishield.mobile.security.DeviceKeyManager
import cm.identishield.mobile.security.GeneratedKey
import cm.identishield.mobile.security.InsecureKeyStorageException
import cm.identishield.mobile.security.LocalUnlock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Base64

/** Erreurs affichées sur les écrans du flux A (SPEC §5.3) ; les textes vivent dans strings.xml. */
enum class EnrolError {
    NUMBER_INVALID, // format local (9 chiffres) ou plage refusée par le serveur (REQUEST_INVALID)
    CREDENTIALS_INVALID, // compte inexistant OU PIN faux : un seul message (ES-22)
    OTP_INVALID,
    OTP_EXPIRED,
    SESSION_EXPIRED, // ENROLMENT_EXPIRED : l'enrôlement a expiré (ENROL_TTL), recommencer
    UNREACHABLE, // MOB-NF-03 : message clair, jamais de mise en file
    REENROLMENT_NOT_AVAILABLE, // compte déjà lié : flux D, carte M-08
    DEVICE_REFUSED, // puce logicielle ou attestation refusée (E-27, carte M-08)
    UNLOCK_CANCELLED,
    CONFIRM_MISMATCH,
    UNEXPECTED,
}

/** Étapes 3 d'E-07 : clé créée, déverrouillage local, enregistrement auprès du serveur. */
enum class SecuringStage { CREATING_KEY, UNLOCKING, REGISTERING }

/** Un état par écran du flux A (E-02 à E-09, E-11). */
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
}

/**
 * Flux A — premier enrôlement. Le serveur décide (P-2) ; l'application exécute.
 * Garde-fous : la clé n'est créée QU'APRÈS la validation de l'OTP par le serveur (ES-25) ; le PIN
 * et la clé de récupération ne vivent qu'en mémoire et sont effacés à l'arrivée sur l'accueil (P-9).
 */
class EnrolmentViewModel(
    private val repository: EnrolmentRepository,
    private val keys: DeviceKeyManager,
    scope: CoroutineScope? = null,
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

    private data class IssuedCode(
        val enrolmentId: String,
        val code: String,
        val positions: List<Int>,
    )

    fun begin() {
        _step.value = EnrolStep.Number()
    }

    fun back() {
        _step.value =
            when (val s = _step.value) {
                is EnrolStep.Number -> EnrolStep.Welcome
                is EnrolStep.Pin -> EnrolStep.Number()
                is EnrolStep.Otp -> EnrolStep.Pin(s.msisdn)
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
                        NEXT_RECOVERY -> _step.value = EnrolStep.Pin(number, EnrolError.REENROLMENT_NOT_AVAILABLE)
                        else -> _step.value = EnrolStep.Pin(number, EnrolError.UNEXPECTED)
                    }
                is ApiResult.Refused ->
                    _step.value =
                        when (r.reason) {
                            REASON_CREDENTIALS -> EnrolStep.Pin(number, EnrolError.CREDENTIALS_INVALID)
                            REASON_REQUEST -> EnrolStep.Number(EnrolError.NUMBER_INVALID)
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
                    recovery = IssuedCode(r.value.enrolmentId, r.value.recoveryCode, r.value.confirmPositions)
                    _step.value = EnrolStep.RecoveryKey(r.value.recoveryCode, lastDisplay = false)
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

    /** Crée la clé puis la fait signer le défi après déverrouillage local ; null si l'usager annule. */
    private suspend fun createAndSign(
        challenge: ByteArray,
        unlock: LocalUnlock,
    ): Pair<GeneratedKey, ByteArray>? {
        _step.value = EnrolStep.Securing(SecuringStage.CREATING_KEY)
        val key = keys.generate(challenge)
        _step.value = EnrolStep.Securing(SecuringStage.UNLOCKING)
        val signature = unlock.unlockAndSign(keys.newSigner(), challenge) ?: return null
        return key to signature
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
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = EnrolmentViewModel(repository, keys) as T
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
        const val REASON_ATTESTATION_REJECTED = "ATTESTATION_REJECTED"
        const val REASON_DEVICE_UNSUPPORTED = "DEVICE_UNSUPPORTED"
    }
}

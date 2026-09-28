package cm.identishield.mobile.data

import kotlinx.serialization.Serializable

// Corps des routes du flux A, calqués sur contracts/mobile-api.yaml (additionalProperties: false :
// la désérialisation est STRICTE, un champ inconnu est une dérive du contrat, donc une erreur).

@Serializable
data class StartRequest(
    val msisdn: String,
    val pin: String,
    val deviceId: String? = null,
    val challenge: String? = null,
    val signature: String? = null,
)

@Serializable
data class StartResponse(
    val next: String,
    val enrolmentId: String? = null,
    val sessionToken: String? = null,
    val expiresInSeconds: Int? = null,
    val demoOtp: String? = null,
)

@Serializable
data class OtpRequest(
    val enrolmentId: String,
    val otp: String,
)

@Serializable
data class BindResponse(
    val next: String,
    val attestationChallenge: String,
)

@Serializable
data class CompleteRequest(
    val enrolmentId: String,
    val publicKey: String,
    val attestationChain: List<String>,
    val challengeSignature: String,
)

@Serializable
data class RecoveryCodeIssued(
    val enrolmentId: String,
    val recoveryCode: String,
    val confirmPositions: List<Int>,
    val deviceId: String? = null,
)

@Serializable
data class ConfirmCharacter(
    val position: Int,
    val character: String,
)

@Serializable
data class ConfirmRequest(
    val enrolmentId: String,
    val characters: List<ConfirmCharacter>,
)

@Serializable
data class SessionResponse(
    val sessionToken: String,
    val expiresInSeconds: Int,
)

@Serializable
data class DeferredOperation(
    val transferId: String,
    val amountXaf: Long,
    val beneficiaryMsisdn: String,
    val executeAt: String,
)

@Serializable
data class Account(
    val accountId: String,
    val msisdn: String,
    val holderName: String,
    val balanceXaf: Long,
    val state: String,
    val deferredOperations: List<DeferredOperation>,
)

@Serializable
data class ErrorBody(
    val reason: String,
)

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

// Flux B (session) et C (envoi signé), même règle de désérialisation stricte.

@Serializable
data class ChallengeResponse(
    val challenge: String,
)

@Serializable
data class SessionRequest(
    val msisdn: String,
    val pin: String,
    val deviceId: String,
    val challenge: String,
    val signature: String,
)

@Serializable
data class NonceResponse(
    val nonce: String,
)

@Serializable
data class Transaction(
    val transferId: String,
    val direction: String,
    val amountXaf: Long,
    val counterpartyMsisdn: String,
    val counterpartyName: String? = null,
    val status: String,
    val at: String,
)

@Serializable
data class TransactionList(
    val items: List<Transaction>,
)

@Serializable
data class Holder(
    val msisdn: String,
    val beneficiaryId: String,
    val holderName: String,
    val newBeneficiary: Boolean,
)

@Serializable
data class TransferRequest(
    val type: String,
    val amountXaf: Long,
    val beneficiaryId: String,
    val nonce: String,
    val signature: String,
    val pin: String,
)

@Serializable
data class TransferResponse(
    val transferId: String,
    val decision: String,
    val status: String,
    val reason: String? = null,
    val executeAt: String? = null,
    val balanceXaf: Long,
)

@Serializable
data class SignedAction(
    val nonce: String,
    val signature: String,
)

@Serializable
data class CancelResponse(
    val transferId: String,
    val status: String,
)

// Flux D (ré-enrôlement) et F (alertes), M-08 : même désérialisation stricte.

@Serializable
data class RecoverRequest(
    val enrolmentId: String,
    val recoveryCode: String,
)

@Serializable
data class Alert(
    val alertId: String,
    val type: String,
    val at: String,
    val summary: String,
    val actions: List<String>,
    val status: String,
)

@Serializable
data class AlertList(
    val items: List<Alert>,
)

@Serializable
data class AlertActionResponse(
    val alertId: String,
    val status: String,
    val accountState: String,
)

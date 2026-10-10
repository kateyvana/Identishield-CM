package cm.identishield.mobile.data

/**
 * Jeton de session de l'application : mémoire vive seulement, jamais écrit (ISC-MOB-SPEC §3,
 * MOB-S-10). Partagé entre la fin du flux A (E-09 ouvre une session) et le flux B.
 */
class SessionHolder {
    var token: String? = null

    /** Compte de la session ouverte, pour signer les réponses aux alertes (flux F) ; mémoire seule. */
    var accountId: String? = null
}

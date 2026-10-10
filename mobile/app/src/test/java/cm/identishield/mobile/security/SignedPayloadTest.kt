package cm.identishield.mobile.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Octets exactement signés (ADR-018 et son amendement M-07) : la chaîne fait foi, octet pour octet. */
class SignedPayloadTest {
    private val nonce = "0123456789abcdef0123456789abcdef"

    private fun utf8(s: String) = s.toByteArray(Charsets.UTF_8)

    @Test
    fun sessionChallenge_isTheUtf8OfTheStringAsReceived_neverDecoded() {
        // Une chaîne qui a l'air d'être du base64 n'est PAS décodée : on signe ses caractères.
        assertArrayEquals(utf8("AAEC"), SignedPayload.sessionChallenge("AAEC"))
        assertArrayEquals(utf8("défi-é_1"), SignedPayload.sessionChallenge("défi-é_1"))
        assertThrows(UnsignableFieldException::class.java) { SignedPayload.sessionChallenge("") }
    }

    @Test
    fun transfer_isTheFixedOrderChain_adr018() {
        assertEquals(
            "acc-000123|P2P|25000|acc-000456|$nonce",
            String(SignedPayload.transfer("acc-000123", "P2P", 25000, "acc-000456", nonce), Charsets.UTF_8),
        )
    }

    @Test
    fun cancel_isAccountCancelTransferNonce() {
        assertEquals(
            "acc-000123|CANCEL|tr-42|$nonce",
            String(SignedPayload.cancel("acc-000123", "tr-42", nonce), Charsets.UTF_8),
        )
    }

    @Test
    fun alertAnswers_areAccountActionAlertIdNonce() {
        assertEquals("acc-000123|ALERT_ACK|al-1|$nonce", String(SignedPayload.alertAck("acc-000123", "al-1", nonce), Charsets.UTF_8))
        assertEquals("acc-000123|ALERT_NOT_ME|al-1|$nonce", String(SignedPayload.alertNotMe("acc-000123", "al-1", nonce), Charsets.UTF_8))
        assertThrows(UnsignableFieldException::class.java) { SignedPayload.alertAck("acc-000123", "al|1", nonce) }
        assertThrows(UnsignableFieldException::class.java) { SignedPayload.alertNotMe("", "al-1", nonce) }
        assertThrows(UnsignableFieldException::class.java) { SignedPayload.alertNotMe("acc-000123", "al-1", nonce.uppercase()) }
    }

    @Test
    fun aSeparatorInAnyField_isRefused_failClosed() {
        assertThrows(UnsignableFieldException::class.java) { SignedPayload.transfer("acc|1", "P2P", 1, "b", nonce) }
        assertThrows(UnsignableFieldException::class.java) { SignedPayload.transfer("a", "P2P|X", 1, "b", nonce) }
        assertThrows(UnsignableFieldException::class.java) { SignedPayload.transfer("a", "P2P", 1, "b|c", nonce) }
        assertThrows(UnsignableFieldException::class.java) { SignedPayload.cancel("a", "tr|1", nonce) }
        assertThrows(UnsignableFieldException::class.java) { SignedPayload.cancel("a|b", "tr-1", nonce) }
    }

    @Test
    fun emptyEdgeSpacedOrMultilineFields_areRefused() {
        assertThrows(UnsignableFieldException::class.java) { SignedPayload.transfer("", "P2P", 1, "b", nonce) }
        assertThrows(UnsignableFieldException::class.java) { SignedPayload.transfer(" a", "P2P", 1, "b", nonce) }
        assertThrows(UnsignableFieldException::class.java) { SignedPayload.transfer("a", "P2P", 1, "b\n", nonce) }
        assertThrows(UnsignableFieldException::class.java) { SignedPayload.cancel("a", "tr\r1", nonce) }
    }

    @Test
    fun amountAndNonce_mustHaveTheAdr018Form() {
        assertThrows(UnsignableFieldException::class.java) { SignedPayload.transfer("a", "P2P", 0, "b", nonce) }
        assertThrows(UnsignableFieldException::class.java) { SignedPayload.transfer("a", "P2P", -5, "b", nonce) }
        assertThrows(UnsignableFieldException::class.java) { SignedPayload.transfer("a", "P2P", 1, "b", nonce.uppercase()) }
        assertThrows(UnsignableFieldException::class.java) { SignedPayload.transfer("a", "P2P", 1, "b", nonce.drop(1)) }
        assertThrows(UnsignableFieldException::class.java) { SignedPayload.cancel("a", "tr-1", "$nonce|") }
    }

    /** Même vecteur et même empreinte dans CanonicalPayloadTest (serveur, M-09) : mêmes octets des deux côtés. */
    @Test
    fun transfer_isByteIdenticalToTheServerCanonical() {
        val bytes = SignedPayload.transfer("acc-000123", "P2P", 25000, "bén-0045é", nonce)
        val sha256 =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
        assertEquals("a24d5dd54b7423e70e23105c15a2bed33b07d640c37951f867a19457f5f2aafd", sha256)
    }
}

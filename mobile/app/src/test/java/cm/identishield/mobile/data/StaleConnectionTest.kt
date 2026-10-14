package cm.identishield.mobile.data

import cm.identishield.mobile.BuildConfig
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * D9 (ADR-039) : reproduit le CLOSE_WAIT de la passe du 2026-10-08. Le serveur répond puis FERME la
 * connexion ; le client la garde en réserve (côté téléphone, la socket est en CLOSE_WAIT). La requête
 * suivante la réutilise et échoue. Une LECTURE est rejouée une fois sur une connexion neuve ; une
 * ACTION ne l'est jamais (le serveur ne la reçoit qu'au plus une fois).
 */
class StaleConnectionTest {
    private lateinit var server: MockWebServer
    private lateinit var daily: RetrofitDailyRepository

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        daily = RetrofitDailyRepository(buildMobileApi(server.url("/mobile/v1/").toString()))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun closedAfterResponse(body: String) =
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body)
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END),
        )

    private fun ok(body: String) =
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body))

    private val account =
        """{"accountId":"acc-1","msisdn":"677000200","holderName":"Test","balanceXaf":0,"state":"TRUSTED","deferredOperations":[]}"""

    @Test
    fun aReadAfterTheServerClosedThePooledConnection_isReplayedOnce_onAFreshConnection() =
        runBlocking {
            closedAfterResponse("""{"challenge":"defi"}""")
            ok(account)
            assertTrue(daily.challenge("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0") is ApiResult.Ok)
            assertTrue("lecture rejouée sur une connexion neuve", daily.account("jeton") is ApiResult.Ok)
            assertEquals(2, server.requestCount)
        }

    @Test
    fun theTicket_isAReadToo() =
        runBlocking {
            closedAfterResponse(account)
            ok("""{"nonce":"0123456789abcdef0123456789abcdef"}""")
            assertTrue(daily.account("jeton") is ApiResult.Ok)
            assertTrue("POST /nonce rejoué : un ticket non utilisé est sans effet", daily.nonce("jeton") is ApiResult.Ok)
        }

    @Test
    fun anAction_isNeverReplayed_evenOnAStaleConnection() =
        runBlocking {
            closedAfterResponse("""{"nonce":"0123456789abcdef0123456789abcdef"}""")
            ok("""{"transferId":"tr-1","decision":"ALLOW","status":"EXECUTED","balanceXaf":0}""")
            assertTrue(daily.nonce("jeton") is ApiResult.Ok)
            val r = daily.transfer("jeton", TransferRequest("P2P", 5000, "acc-000456", "0123456789abcdef0123456789abcdef", "c2ln", "1234"))
            assertEquals("un envoi n'est jamais rejoué", ApiResult.Unreachable, r)
            assertEquals("le serveur n'a reçu que le ticket", 1, server.requestCount)
        }

    @Test
    fun theAppKeepsAConnectionInReserve_lessThanTheServerDoes() {
        assertTrue(
            "APP_CONN_KEEPALIVE < SERVER_CONN_KEEPALIVE (ADR-039)",
            BuildConfig.APP_CONN_KEEPALIVE_SECONDS < BuildConfig.SERVER_CONN_KEEPALIVE_SECONDS,
        )
    }
}

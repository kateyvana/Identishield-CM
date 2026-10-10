package cm.identishield.mobile.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Routes des flux B et C contre MockWebServer, sur la JVM. */
class DailyRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repo: RetrofitDailyRepository

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        repo = RetrofitDailyRepository(buildMobileApi(server.url("/mobile/v1/").toString()))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun json(
        code: Int,
        body: String,
    ) = server.enqueue(MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body))

    @Test
    fun challenge_isRequestedForThisDevice() =
        runBlocking {
            json(200, """{"challenge":"ab-c_1"}""")
            assertEquals(ApiResult.Ok(ChallengeResponse("ab-c_1")), repo.challenge("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"))
            assertEquals(
                "/mobile/v1/session/challenge?deviceId=0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0",
                server.takeRequest().path,
            )
        }

    @Test
    fun openSession_sendsNumberPinDeviceChallengeAndSignature() =
        runBlocking {
            json(200, """{"sessionToken":"jeton","expiresInSeconds":300}""")
            val r = repo.openSession(SessionRequest("677000123", "1234", "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0", "ab-c_1", "c2ln"))
            assertEquals(ApiResult.Ok(SessionResponse("jeton", 300)), r)
            val sent = server.takeRequest()
            assertEquals("POST", sent.method)
            assertEquals(
                """{"msisdn":"677000123","pin":"1234","deviceId":"0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0","challenge":"ab-c_1","signature":"c2ln"}""",
                sent.body.readUtf8(),
            )
        }

    @Test
    fun nonce_isAPostWithTheBearerToken_andNoBody() =
        runBlocking {
            json(200, """{"nonce":"0123456789abcdef0123456789abcdef"}""")
            assertEquals(ApiResult.Ok(NonceResponse("0123456789abcdef0123456789abcdef")), repo.nonce("jeton"))
            val sent = server.takeRequest()
            assertEquals("POST", sent.method)
            assertEquals("/mobile/v1/nonce", sent.path)
            assertEquals("Bearer jeton", sent.getHeader("Authorization"))
            assertEquals(0L, sent.bodySize)
        }

    @Test
    fun transfer_sendsTheSignedFieldsAndPin_andReadsTheDecision() =
        runBlocking {
            json(
                200,
                """{"transferId":"tr-1","decision":"STEP_UP","status":"DEFERRED","reason":"DECISION_DEFERRED","executeAt":"2026-10-01T16:00:00Z","balanceXaf":152300}""",
            )
            val r = repo.transfer("jeton", TransferRequest("P2P", 25000, "acc-000456", "0123456789abcdef0123456789abcdef", "c2ln", "1234"))
            assertEquals("STEP_UP", (r as ApiResult.Ok).value.decision)
            assertEquals("2026-10-01T16:00:00Z", r.value.executeAt)
            assertEquals(
                """{"type":"P2P","amountXaf":25000,"beneficiaryId":"acc-000456","nonce":"0123456789abcdef0123456789abcdef","signature":"c2ln","pin":"1234"}""",
                server.takeRequest().body.readUtf8(),
            )
        }

    @Test
    fun cancel_postsTheSignedActionOnTheTransfer() =
        runBlocking {
            json(200, """{"transferId":"tr-1","status":"CANCELLED"}""")
            assertEquals(
                ApiResult.Ok(CancelResponse("tr-1", "CANCELLED")),
                repo.cancel("jeton", "tr-1", SignedAction("0123456789abcdef0123456789abcdef", "c2ln")),
            )
            val sent = server.takeRequest()
            assertEquals("/mobile/v1/transfers/tr-1/cancel", sent.path)
            assertEquals("""{"nonce":"0123456789abcdef0123456789abcdef","signature":"c2ln"}""", sent.body.readUtf8())
        }

    @Test
    fun holder_andTransactions_areReadWithTheToken() =
        runBlocking {
            json(200, """{"msisdn":"677000456","beneficiaryId":"acc-000456","holderName":"Jean-Paul NKOA","newBeneficiary":true}""")
            json(
                200,
                """{"items":[{"transferId":"tr-1","direction":"OUT","amountXaf":8000,"counterpartyMsisdn":"677000789","counterpartyName":"Marie ABENA","status":"EXECUTED","at":"2026-09-24T09:12:00Z"}]}""",
            )
            assertEquals(true, (repo.holder("jeton", "677000456") as ApiResult.Ok).value.newBeneficiary)
            assertEquals("/mobile/v1/beneficiaries/677000456/holder", server.takeRequest().path)
            assertEquals("Marie ABENA", (repo.transactions("jeton") as ApiResult.Ok).value.items.single().counterpartyName)
        }

    @Test
    fun throttled_carriesRetryAfter_es21() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(429)
                    .setHeader("Content-Type", "application/json")
                    .setHeader("Retry-After", "90")
                    .setBody("""{"reason":"THROTTLED"}"""),
            )
            assertEquals(ApiResult.Refused(429, "THROTTLED", 90), repo.holder("jeton", "677000456"))
        }

    /**
     * SPEC §7.4 : jamais de renvoi automatique d'une opération d'argent. La connexion, réutilisée
     * depuis le pool (cas réel d'une connexion périmée), est coupée APRÈS que le serveur a lu l'envoi :
     * le client ne doit pas le rejouer sur une nouvelle connexion (le nonce est déjà consommé).
     */
    @Test
    fun connectionDroppedAfterTheRequest_isNeverRetried() =
        runBlocking {
            json(200, """{"nonce":"0123456789abcdef0123456789abcdef"}""")
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            json(200, """{"transferId":"tr-2","decision":"ALLOW","status":"EXECUTED","balanceXaf":0}""")
            repo.nonce("jeton") // la connexion entre dans le pool
            val r = repo.transfer("jeton", TransferRequest("P2P", 25000, "acc-000456", "0123456789abcdef0123456789abcdef", "c2ln", "1234"))
            assertEquals(ApiResult.Unreachable, r)
            assertEquals("nonce + UN envoi : aucun renvoi", 2, server.requestCount)
            assertTrue(r !is ApiResult.Ok)
        }
}

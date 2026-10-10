package cm.identishield.mobile.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Routes des flux D et F contre MockWebServer, sur la JVM (M-08). */
class RecoveryAndAlertRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var enrolment: RetrofitEnrolmentRepository
    private lateinit var alerts: RetrofitAlertRepository

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val api = buildMobileApi(server.url("/mobile/v1/").toString())
        enrolment = RetrofitEnrolmentRepository(api)
        alerts = RetrofitAlertRepository(api)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun json(
        code: Int,
        body: String,
        retryAfter: Int? = null,
    ) {
        val response = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
        if (retryAfter != null) response.setHeader("Retry-After", retryAfter.toString())
        server.enqueue(response)
    }

    @Test
    fun recover_postsTheCodeForThisAttempt_andReadsTheBindChallenge() =
        runBlocking {
            json(200, """{"next":"BIND","attestationChallenge":"AAECAwQFBgcICQoLDA0ODw=="}""")
            val r = enrolment.recover("e-d", "K7PQ3MZX9DHTW2RC8NBV")
            assertEquals(ApiResult.Ok(BindResponse("BIND", "AAECAwQFBgcICQoLDA0ODw==")), r)
            val sent = server.takeRequest()
            assertEquals("/mobile/v1/enrolment/recover", sent.path)
            assertEquals("""{"enrolmentId":"e-d","recoveryCode":"K7PQ3MZX9DHTW2RC8NBV"}""", sent.body.readUtf8())
        }

    @Test
    fun recover_throttled_carriesRetryAfter_andInvalidIsUniform() =
        runBlocking {
            json(429, """{"reason":"THROTTLED"}""", retryAfter = 120)
            assertEquals(ApiResult.Refused(429, "THROTTLED", 120), enrolment.recover("e-d", "AAAA"))
            json(401, """{"reason":"RECOVERY_INVALID"}""")
            assertEquals(ApiResult.Refused(401, "RECOVERY_INVALID"), enrolment.recover("e-d", "AAAA"))
        }

    @Test
    fun alerts_areReadWithTheToken_strictlyToTheContract() =
        runBlocking {
            json(
                200,
                """{"items":[{"alertId":"al-1","type":"SIM_CHANGED","at":"2026-10-08T13:02:00Z","summary":"La carte SIM de votre numéro a été changée.","actions":["ACK","NOT_ME"],"status":"OPEN"}]}""",
            )
            val r = alerts.alerts("jeton") as ApiResult.Ok
            assertEquals(listOf("ACK", "NOT_ME"), r.value.items.single().actions)
            assertEquals("Bearer jeton", server.takeRequest().getHeader("Authorization"))
            json(200, """{"items":[{"alertId":"al-1","type":"SIM_CHANGED","at":"x","summary":"s","actions":[],"status":"OPEN","link":"http://x"}]}""")
            assertEquals("un champ hors contrat (un lien !) est refusé", ApiResult.Refused(0, RetrofitEnrolmentRepository.CONTRACT_VIOLATION), alerts.alerts("jeton"))
        }

    @Test
    fun ack_andNotMe_postTheSignedActionOnTheRightRoute() =
        runBlocking {
            val action = SignedAction("0123456789abcdef0123456789abcdef", "c2ln")
            json(200, """{"alertId":"al-1","status":"ACKNOWLEDGED","accountState":"TRUSTED"}""")
            assertEquals(ApiResult.Ok(AlertActionResponse("al-1", "ACKNOWLEDGED", "TRUSTED")), alerts.acknowledge("jeton", "al-1", action))
            val ack = server.takeRequest()
            assertEquals("/mobile/v1/alerts/al-1/ack", ack.path)
            assertEquals("""{"nonce":"0123456789abcdef0123456789abcdef","signature":"c2ln"}""", ack.body.readUtf8())
            json(200, """{"alertId":"al-1","status":"NOT_ME","accountState":"FROZEN"}""")
            assertEquals("FROZEN", (alerts.deny("jeton", "al-1", action) as ApiResult.Ok).value.accountState)
            assertEquals("/mobile/v1/alerts/al-1/not-me", server.takeRequest().path)
        }
}

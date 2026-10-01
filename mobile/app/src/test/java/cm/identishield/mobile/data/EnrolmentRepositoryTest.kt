package cm.identishield.mobile.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Couche réseau contre MockWebServer, sur la JVM (network_security_config ne s'applique pas ici :
 * la posture TLS-only de l'application n'en dépend pas, ADR-038).
 */
class EnrolmentRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repo: RetrofitEnrolmentRepository

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        repo = RetrofitEnrolmentRepository(buildMobileApi(server.url("/mobile/v1/").toString()))
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
    fun start_postsNumberAndPin_andReadsTheNextStep() =
        runBlocking {
            json(200, """{"next":"OTP","enrolmentId":"e-1"}""")
            assertEquals(ApiResult.Ok(StartResponse(next = "OTP", enrolmentId = "e-1")), repo.start("677000200", "1234"))
            val sent = server.takeRequest()
            assertEquals("/mobile/v1/enrolment/start", sent.path)
            assertEquals("""{"msisdn":"677000200","pin":"1234"}""", sent.body.readUtf8()) // aucun champ nul envoyé
        }

    @Test
    fun refusal_carriesTheReasonCodeOfTheContract() =
        runBlocking {
            json(401, """{"reason":"CREDENTIALS_INVALID"}""")
            assertEquals(ApiResult.Refused(401, "CREDENTIALS_INVALID"), repo.start("677000200", "0000"))
        }

    @Test
    fun responseOutsideTheContract_isRejected() =
        runBlocking {
            json(200, """{"next":"OTP","enrolmentId":"e-1","inattendu":true}""")
            assertEquals(ApiResult.Refused(0, RetrofitEnrolmentRepository.CONTRACT_VIOLATION), repo.start("677000200", "1234"))
        }

    @Test
    fun account_isReadWithTheBearerToken() =
        runBlocking {
            json(
                200,
                """{"accountId":"acc-1","msisdn":"677000200","holderName":"Test","balanceXaf":152300,"state":"TRUSTED","deferredOperations":[]}""",
            )
            val r = repo.account("jeton-de-session")
            assertEquals(152300L, (r as ApiResult.Ok).value.balanceXaf)
            assertEquals("Bearer jeton-de-session", server.takeRequest().getHeader("Authorization"))
        }

    @Test
    fun unreachableServer_isReportedAsSuch() =
        runBlocking {
            server.shutdown()
            assertEquals(ApiResult.Unreachable, repo.start("677000200", "1234"))
        }
}

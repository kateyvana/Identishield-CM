package cm.identishield.mobile.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * En-tête X-ISC-Installation (ADR-030 ; décision de la propriétaire du 2026-10-24, B3b) : posé sur
 * les quatre routes où le contrat le déclare (start, recover, complete, défi de session), sur
 * aucune autre ; valeur stable d'une requête à l'autre.
 *
 * @covers ES-21
 */
class InstallationHeaderTest {
    private val installation = InstallationId { "6f0a2c1e-1234-4abc-9def-0123456789ab" }
    private lateinit var server: MockWebServer
    private lateinit var api: MobileApi

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        api = buildMobileApi(server.url("/mobile/v1/").toString(), defaultHttpClient(installation))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun json(body: String) = server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body))

    @Test
    fun theHeader_isOnTheFourDeclaredRoutes_withTheSameValue() =
        runBlocking {
            json("""{"next":"OTP","enrolmentId":"e-1"}""")
            RetrofitEnrolmentRepository(api).start("677000200", "1234")
            json("""{"next":"BIND","attestationChallenge":"ZGVmaQ=="}""")
            RetrofitEnrolmentRepository(api).recover("e-1", "K7MQ3X")
            json("""{"enrolmentId":"e-1","recoveryCode":"Q4TN8X","deviceId":"1a2b3c4d-5e6f-4a1b-8c2d-3e4f5a6b7c8d"}""")
            RetrofitEnrolmentRepository(api).complete(CompleteRequest("e-1", "cGs=", listOf("Y2VydA=="), "c2ln", "K7MQ3X"))
            json("""{"challenge":"9f86d081884c7d659a2feaa0c55ad015"}""")
            RetrofitDailyRepository(api).challenge("1a2b3c4d-5e6f-4a1b-8c2d-3e4f5a6b7c8d")
            repeat(4) { assertEquals("6f0a2c1e-1234-4abc-9def-0123456789ab", server.takeRequest().getHeader("X-ISC-Installation")) }
        }

    @Test
    fun theHeader_isNowhereElse() =
        runBlocking {
            json("""{"accountId":"acc-1","msisdn":"677000200","holderName":"T","balanceXaf":0,"state":"TRUSTED","deferredOperations":[]}""")
            RetrofitDailyRepository(api).account("jeton")
            assertNull(server.takeRequest().getHeader("X-ISC-Installation"))
            json("""{"enrolmentId":"e-1","otp":"x"}""")
            RetrofitEnrolmentRepository(api).verifyOtp("e-1", "123456")
            assertNull(server.takeRequest().getHeader("X-ISC-Installation"))
        }
}

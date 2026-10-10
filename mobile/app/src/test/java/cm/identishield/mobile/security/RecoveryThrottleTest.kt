package cm.identishield.mobile.security

import cm.identishield.mobile.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Horloge réglable : le temps n'avance que lorsque le test le décide (jamais de temps réel). */
class MutableClock(
    var now: Instant = Instant.parse("2026-10-08T10:00:00Z"),
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = now

    fun advance(d: Duration) {
        now = now.plus(d)
    }
}

class MemoryThrottleStore : ThrottleStore {
    var state = ThrottleState()
    val saved = mutableListOf<ThrottleState>()

    override fun load() = state

    override fun save(state: ThrottleState) {
        this.state = state
        saved += state
    }
}

/**
 * Délai croissant du flux D, calé sur les paramètres nommés de config/ruleset.yaml (BuildConfig) :
 * REENROL_ATTEMPTS essais libres, puis LOCK_BASE doublé à chaque échec, plafonné par LOCK_MAX.
 *
 * @covers ES-21
 */
class RecoveryThrottleTest {
    private val attempts = BuildConfig.REENROL_ATTEMPTS
    private val base = Duration.ofSeconds(BuildConfig.LOCK_BASE_SECONDS)
    private val max = Duration.ofSeconds(BuildConfig.LOCK_MAX_SECONDS)
    private val clock = MutableClock()
    private val store = MemoryThrottleStore()

    private fun throttle() = RecoveryThrottle(store, clock, attempts, base, max)

    @Test
    fun freeAttempts_thenTheBaseDelay_onThisDevice() {
        val t = throttle()
        repeat(attempts - 1) { assertNull("essai libre : « Clé incorrecte »", t.recordFailure()) }
        assertNull(t.remaining())
        val until = t.recordFailure()
        assertEquals(clock.now.plus(base), until)
        assertEquals(base, t.remaining())
    }

    @Test
    fun theDelayDoubles_afterEachFailure_andIsCappedByLockMax() {
        val t = throttle()
        repeat(attempts) { t.recordFailure() }
        var expected = base
        repeat(40) {
            clock.advance(t.remaining() ?: Duration.ZERO)
            assertNull("l'attente écoulée, un nouvel essai est permis", t.remaining())
            expected = expected.multipliedBy(2).let { if (it > max) max else it }
            assertEquals(clock.now.plus(expected), t.recordFailure())
        }
        assertEquals("plafond LOCK_MAX atteint et tenu", max, t.remaining())
    }

    @Test
    fun theServerRetryAfter_winsWhenLonger_neverShorter() {
        val t = throttle()
        repeat(attempts - 1) { t.recordFailure() }
        val longer = base.multipliedBy(10)
        assertEquals(clock.now.plus(longer), t.recordFailure(longer))
        clock.advance(longer)
        assertEquals("la règle locale tient si le serveur est plus court", clock.now.plus(base.multipliedBy(2)), t.recordFailure(Duration.ofSeconds(1)))
    }

    @Test
    fun aServer429_waitsWithoutCountingAFailure() {
        val t = throttle()
        val wait = Duration.ofSeconds(BuildConfig.LOCK_BASE_SECONDS * 3)
        assertEquals(clock.now.plus(wait), t.serverWait(wait))
        assertEquals(0, store.state.failures)
        clock.advance(wait)
        assertEquals("sans Retry-After : LOCK_BASE", clock.now.plus(base), t.serverWait(null))
    }

    @Test
    fun theWait_survivesARestart_andSuccessResetsIt() {
        repeat(attempts) { throttle().recordFailure() }
        assertEquals("un nouveau RecoveryThrottle relit la même mémoire", base, throttle().remaining())
        throttle().recordSuccess()
        assertEquals(ThrottleState(), store.state)
        assertNull(throttle().remaining())
    }
}

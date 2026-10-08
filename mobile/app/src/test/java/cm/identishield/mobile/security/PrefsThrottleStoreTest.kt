package cm.identishield.mobile.security

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class PrefsThrottleStoreTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun keepsOnlyTwoNumbers_neverTheRecoveryCode() {
        assertEquals(ThrottleState(), PrefsThrottleStore(context).load())
        val state = ThrottleState(3, Instant.parse("2026-10-08T10:01:00Z"))
        PrefsThrottleStore(context).save(state)
        assertEquals(state, PrefsThrottleStore(context).load())
        val stored = context.getSharedPreferences(PrefsThrottleStore.FILE, Context.MODE_PRIVATE).all
        assertEquals("un compteur et une échéance, rien d'autre (MOB-S-10)", setOf("failures", "lockedUntil"), stored.keys)
        stored.values.forEach { assertEquals(true, it is Number) }
    }
}

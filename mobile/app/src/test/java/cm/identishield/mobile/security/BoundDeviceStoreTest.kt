package cm.identishield.mobile.security

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment

@RunWith(AndroidJUnit4::class)
class BoundDeviceStoreTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun keepsOnlyNumberAndDeviceId_acrossInstances() {
        assertNull(PrefsBoundDeviceStore(context).load())
        PrefsBoundDeviceStore(context).save(BoundDevice("677000123", "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"))
        assertEquals(BoundDevice("677000123", "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"), PrefsBoundDeviceStore(context).load())
        val stored = context.getSharedPreferences(PrefsBoundDeviceStore.FILE, Context.MODE_PRIVATE).all
        assertEquals("rien d'autre que le numéro et le device_id (MOB-S-10)", setOf("msisdn", "deviceId"), stored.keys)
    }
}

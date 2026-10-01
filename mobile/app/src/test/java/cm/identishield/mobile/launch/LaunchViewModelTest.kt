package cm.identishield.mobile.launch

import org.junit.Assert.assertEquals
import org.junit.Test

/** MOB-F-01 : E-01 oriente selon la présence d'une clé locale ; l'application ne décide rien d'autre. */
class LaunchViewModelTest {
    @Test
    fun startsInCheckingState() {
        val vm = LaunchViewModel { false }
        assertEquals(LaunchState.Checking, vm.state.value)
    }

    @Test
    fun noKeyLeadsToNumberAndPin() {
        val vm = LaunchViewModel { false }
        vm.check()
        assertEquals(LaunchState.NoKey, vm.state.value)
    }

    @Test
    fun keyPresentLeadsToUnlock() {
        val vm = LaunchViewModel { true }
        vm.check()
        assertEquals(LaunchState.KeyPresent, vm.state.value)
    }
}

package cm.identishield.mobile.launch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** État de l'écran E-01. La navigation vers E-03 ou E-10 arrive avec M-06 et M-07. */
sealed interface LaunchState {
    data object Checking : LaunchState

    /** Aucune clé : l'usager va saisir son numéro puis son PIN (E-03). */
    data object NoKey : LaunchState

    /** Clé présente : déverrouillage (E-10). */
    data object KeyPresent : LaunchState
}

/** E-01 : l'application ne décide rien (P-2) ; elle constate seulement la présence d'une clé locale. */
class LaunchViewModel(
    private val keyChecker: DeviceKeyChecker,
) : ViewModel() {
    private val _state = MutableStateFlow<LaunchState>(LaunchState.Checking)
    val state: StateFlow<LaunchState> = _state.asStateFlow()

    fun check() {
        _state.value = if (keyChecker.hasDeviceKey()) LaunchState.KeyPresent else LaunchState.NoKey
    }

    /** Injection manuelle par constructeur (ADR-037) : pas de cadre d'injection. */
    class Factory(
        private val keyChecker: DeviceKeyChecker,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = LaunchViewModel(keyChecker) as T
    }
}

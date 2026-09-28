package cm.identishield.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import cm.identishield.mobile.launch.AndroidKeystoreKeyChecker
import cm.identishield.mobile.launch.LaunchScreen
import cm.identishield.mobile.launch.LaunchViewModel
import cm.identishield.mobile.ui.theme.IdentiShieldTheme

class MainActivity : ComponentActivity() {
    // Injection manuelle (ADR-037) : les dépendances sont construites ici et passées par constructeur.
    private val launchViewModel: LaunchViewModel by viewModels {
        LaunchViewModel.Factory(AndroidKeystoreKeyChecker())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        launchViewModel.check()
        setContent { IdentiShieldTheme { LaunchScreen() } }
    }
}

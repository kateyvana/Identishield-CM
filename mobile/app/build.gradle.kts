import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

// Paramètres nommés lus à la construction dans config/ruleset.yaml, source unique (ES-15) :
// jamais de valeur en dur dans le code de l'application.
val rulesetText = providers.fileContents(layout.projectDirectory.file("../../config/ruleset.yaml")).asText

fun rulesetSeconds(name: String): Int {
    val match =
        Regex("^\\s*$name:\\s*PT(\\d+)S\\b", RegexOption.MULTILINE).find(rulesetText.get())
            ?: throw GradleException("$name absent de config/ruleset.yaml (forme attendue : PT<n>S)")
    return match.groupValues[1].toInt()
}

// URL de base de l'API mobile (HTTPS obligatoire, ADR-038). Défaut : faux serveur vu depuis
// l'émulateur (10.0.2.2, port MOBILE_MOCK_PORT du Makefile). Téléphone : -Pisc.apiBaseUrl=...
val apiBaseUrl = providers.gradleProperty("isc.apiBaseUrl").orElse("https://10.0.2.2:8089/mobile/v1/")

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "cm.identishield.mobile"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "cm.identishield.mobile"
        // Android 11 : liaison de la signature au déverrouillage local du téléphone, biométrie ou
        // code (MOB-S-03) — décision QM-10, ADR-037.
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val baseUrl = apiBaseUrl.get()
        if (!baseUrl.startsWith("https://")) {
            throw GradleException("MOB-S-08 / ADR-038 : isc.apiBaseUrl doit être en https:// (reçu : $baseUrl)")
        }
        buildConfigField("String", "API_BASE_URL", "\"$baseUrl\"")
        buildConfigField("int", "OTP_RESEND_DELAY_SECONDS", rulesetSeconds("OTP_RESEND_DELAY").toString())
    }

    buildTypes {
        release {
            isDebuggable = false // MOB-S-14
            optimization {
                enable = true // minification R8 (MOB-S-14)
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true // tests d'interface Compose sur la JVM (Robolectric)
            all {
                // Robolectric (API 36) lit les internes de FileDescriptor via SharedSecrets ; JDK 21
                // les ferme par défaut. Ouverture limitée à la JVM des tests, jamais à l'application.
                it.jvmArgs(
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                )
            }
        }
    }

    lint {
        abortOnError = true
    }
}

/**
 * MOB-S-08 : confiance réseau générée à la construction. La CA du bac à sable (SPIKE-4) est créée
 * au premier démarrage de chaque pile (ADR-021) : elle n'est jamais versionnée. Elle est fournie
 * par `-Pisc.sandboxCa=<chemin>` ou la variable `ISC_SANDBOX_CA`.
 * - Trafic en clair interdit dans tous les cas ; aucune autorité système ni utilisateur.
 * - CA présente : seule cette CA est de confiance (épinglage).
 * - CA absente : debug ne fait confiance à AUCUNE autorité (échec fermé) ; release REFUSE de se
 *   construire.
 */
abstract class SandboxTrustTask : DefaultTask() {
    companion object {
        /** Marqueur du sujet de la CA de développement du faux serveur (tools/mobile-mock/gen-dev-tls.sh). */
        const val DEV_CA_MARKER = "ISC-DEV-MOBILE-MOCK"
    }

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sandboxCa: RegularFileProperty

    @get:Input
    abstract val requireCa: Property<Boolean>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        val xmlDir = File(out, "xml").apply { mkdirs() }
        val ca = sandboxCa.orNull?.asFile
        if (ca != null) {
            // La CA fournie doit être un vrai certificat X.509 ; la CA de DÉVELOPPEMENT du faux
            // serveur (marqueur OU=ISC-DEV-MOBILE-MOCK, ADR-038) est REFUSÉE pour un build release.
            val subject =
                ca.inputStream().use {
                    (CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate)
                        .subjectX500Principal.name
                }
            if (requireCa.get() && subject.contains("OU=$DEV_CA_MARKER")) {
                throw GradleException(
                    "ADR-038 : la CA de développement du faux serveur ($subject) est interdite en release. " +
                        "Fournir la CA du bac à sable.",
                )
            }
        }
        val anchors =
            if (ca != null) {
                val rawDir = File(out, "raw").apply { mkdirs() }
                ca.copyTo(File(rawDir, "sandbox_ca.pem"), overwrite = true)
                "<trust-anchors>\n            <certificates src=\"@raw/sandbox_ca\" />\n        </trust-anchors>"
            } else {
                if (requireCa.get()) {
                    throw GradleException(
                        "MOB-S-08 : build release sans CA du bac à sable. Fournir -Pisc.sandboxCa=<chemin> " +
                            "ou ISC_SANDBOX_CA.",
                    )
                }
                logger.warn("MOB-S-08 : aucune CA du bac à sable fournie — ce build ne fait confiance à AUCUNE autorité.")
                "<trust-anchors />"
            }
        File(xmlDir, "network_security_config.xml").writeText(
            """
            |<?xml version="1.0" encoding="utf-8"?>
            |<!-- Généré par SandboxTrustTask (MOB-S-08) : ne pas éditer. -->
            |<network-security-config>
            |    <base-config cleartextTrafficPermitted="false">
            |        $anchors
            |    </base-config>
            |</network-security-config>
            |
            """.trimMargin(),
        )
    }
}

val sandboxCaPath =
    providers.gradleProperty("isc.sandboxCa").orElse(providers.environmentVariable("ISC_SANDBOX_CA"))

androidComponents {
    onVariants { variant ->
        val suffix = variant.name.replaceFirstChar { it.uppercase() }
        val trust =
            tasks.register<SandboxTrustTask>("generate${suffix}SandboxTrust") {
                sandboxCa.set(layout.file(sandboxCaPath.map { File(it) }))
                requireCa.set(variant.buildType == "release")
            }
        variant.sources.res?.addGeneratedSourceDirectory(trust, SandboxTrustTask::outputDir)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.okhttp.mockwebserver)
    // Espresso 3.5.0, tiré par Compose, appelle InputManager.getInstance(), absent d'Android 17.
    testImplementation(libs.androidx.test.espresso.core)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
}

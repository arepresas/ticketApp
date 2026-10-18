import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

        // Machine-local configuration. `local.properties` is gitignored
        // (`.rules/security.md`: no client ids or secrets in source), so the
        // values below are read at configure time and injected into
        // `BuildConfig` — never hard-coded per environment.
        // 
        // Declared after `plugins {}` on purpose: in Gradle Kotlin DSL the
        // plugins block must precede every other statement in the script.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

        // Resolve a build-time setting: `local.properties` → Gradle property → env → placeholder.
fun setting(key: String, env: String, placeholder: String): String =
    localProperties.getProperty(key)
        ?: providers.gradleProperty(key).orNull
        ?: providers.environmentVariable(env).orNull
        ?: placeholder

/**
 * Same resolution order as [setting], but without the placeholder: used
 * for secrets, where "absent" and "empty string" must both mean
 * "not configured" rather than silently becoming a valid value.
 */
fun settingOrNull(key: String, env: String): String? =
    localProperties.getProperty(key)
        ?: providers.gradleProperty(key).orNull
        ?: providers.environmentVariable(env).orNull

// Release signing material. Resolved from Gradle properties (typically
// ~/.gradle/gradle.properties or injected by CI) or the environment,
// never from a file in the repo — `.gitignore` blocks *.jks /
// *.keystore, and `.rules/security.md` puts keystore material well
// beyond "no client ids per environment".
val releaseStoreFile: String? = settingOrNull("ticketapp.storeFile", "TICKETAPP_STORE_FILE")
val releaseStorePassword: String? = settingOrNull("ticketapp.storePassword", "TICKETAPP_STORE_PASSWORD")
val releaseKeyAlias: String? = settingOrNull("ticketapp.keyAlias", "TICKETAPP_KEY_ALIAS")
val releaseKeyPassword: String? = settingOrNull("ticketapp.keyPassword", "TICKETAPP_KEY_PASSWORD")

android {
    namespace = "com.ticketapp.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ticketapp.android"
        // API 26 (Oreo) is the oldest level where Credential Manager,
        // EncryptedSharedPreferences and the modern WebView APIs this
        // module relies on are all available without version shims.
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

                // The single origin this client talks to. It is the *front*'s
        // public origin, not the BFF's: nginx (prod) and the Vite dev
        // proxy (local) already proxy `/api/*` to the BFF, so the
        // WebView and the native auth calls share one base URL and
        // the API stays same-origin exactly as ADR 0008 requires.
        buildConfigField(
            "String",
            "WEB_ORIGIN",
            "\"${setting("ticketapp.webOrigin", "TICKETAPP_WEB_ORIGIN", "https://ticketapp-front.fly.dev")}\"",
        )
                // OAuth **web** client id — the same value the BFF verifies
        // `GOOGLE_CLIENT_ID` against and the SPA builds with as
        // `VITE_GOOGLE_CLIENT_ID`. Credential Manager needs it as the
        // audience of the id_token it returns. Public by OAuth
        // design, but environment-specific, so it stays out of source.
        buildConfigField(
            "String",
            "GOOGLE_SERVER_CLIENT_ID",
            "\"${setting("ticketapp.googleServerClientId", "TICKETAPP_GOOGLE_SERVER_CLIENT_ID", "REPLACE_ME.apps.googleusercontent.com")}\"",
        )
    }

    buildTypes {
        debug {
            // Allows `http://10.0.2.2:5173` (emulator → host Vite dev
            // server). Scoped by res/xml/network_security_config.xml to
            // loopback only — never a blanket `usesCleartextTraffic`.
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            // Signing is opt-in and entirely optional: without a
            // keystore the build still succeeds and produces an
            // unsigned APK (fine for CI and for `assembleRelease` on a
            // dev machine), so a missing secret degrades instead of
            // breaking every build. Set these four properties in
            // ~/.gradle/gradle.properties or as TICKETAPP_* env vars
            // to produce a signed artifact.
            if (releaseStoreFile != null) {
                signingConfig = signingConfigs.create("release") {
                    storeFile = file(releaseStoreFile)
                    storePassword = releaseStorePassword
                    keyAlias = releaseKeyAlias
                    keyPassword = releaseKeyPassword

                    // v2 is what API 24+ installs verify against and
                    // minSdk 26 guarantees it. v3 is added explicitly:
                    // it carries the proof-of-rotation record, so
                    // rotating the upload key later does not force a
                    // key-reset for users.
                    enableV1Signing = false
                    enableV2Signing = true
                    enableV3Signing = true
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)

    implementation(libs.androidx.webkit)

implementation(libs.okhttp)
implementation(libs.kotlinx.serialization.json)

testImplementation(libs.junit)
testImplementation(libs.mockwebserver)
testImplementation(libs.kotlinx.coroutines.test)
}
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Public client config lives in local.properties (gitignored): balance.supabaseUrl, balance.supabaseKey, balance.googleWebClientId.
val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun localString(key: String) = "\"${local.getProperty(key, "")}\""

android {
    namespace = "id.sndyy.balancenative"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "id.sndyy.balancenative"
        // Credential Manager, encrypted key storage and notification channels all want 26+.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "SUPABASE_URL", localString("balance.supabaseUrl"))
        buildConfigField("String", "SUPABASE_KEY", localString("balance.supabaseKey"))
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", localString("balance.googleWebClientId"))
        // Profile → Developer (dummy data for load testing). On in Dev and fast, off in release.
        buildConfigField("boolean", "DEV_TOOLS", "true")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release key from local.properties (balance.release.*). Without it (CI) release falls back to the
    // debug key, so assembleRelease still works; a build for people to install needs the real one.
    val releaseStore = local.getProperty("balance.release.storeFile")?.let(::file)?.takeIf { it.exists() }
    signingConfigs {
        if (releaseStore != null) create("release") {
            storeFile = releaseStore
            storePassword = local.getProperty("balance.release.storePassword")
            keyAlias = local.getProperty("balance.release.keyAlias")
            keyPassword = local.getProperty("balance.release.keyPassword")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            resValue("string", "app_name", "Balance Native Dev")
        }
        release {
            resValue("string", "app_name", "Balance Native")
            buildConfigField("boolean", "DEV_TOOLS", "false")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "src/main/keepRules/rules.keep")
        }
        // Release speed (R8, not debuggable) for judging how the app feels. Same app id and debug key as
        // the Dev build, so it installs over it, keeps its data, and Google sign-in keeps working.
        create("fast") {
            initWith(getByName("release"))
            applicationIdSuffix = ".dev"
            resValue("string", "app_name", "Balance Native Dev")
            buildConfigField("boolean", "DEV_TOOLS", "true")
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    // Installs the Compose/library baseline profiles on sideloaded builds, so the first open is compiled, not interpreted.
    implementation(libs.androidx.profileinstaller)
    testImplementation(libs.junit)
    // Android's org.json is a stub on the JVM; the parser tests need the real one.
    testImplementation(libs.org.json)
    // Real SQLite and android.util.Xml for the ledger-merge, AI-review and xlsx tests.
    testImplementation(libs.robolectric)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

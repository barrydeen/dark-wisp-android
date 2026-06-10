import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.objectbox)
}

android {
    val baseApplicationId = rootProject.extra["baseApplicationId"] as String
    val debugApplicationIdSuffix = rootProject.extra["debugApplicationIdSuffix"] as String

    namespace = "com.darkwisp.app"
    compileSdk = 35

    val localProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    fun prop(name: String): String? =
        localProps.getProperty(name)?.takeIf { it.isNotBlank() } ?: System.getenv(name)

    defaultConfig {
        applicationId = baseApplicationId
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        resValue("string", "app_name", "Dark Wisp")

        ndk {
            abiFilters += "arm64-v8a"
        }

        buildConfigField("String", "BREEZ_API_KEY", "\"${prop("breez.api.key") ?: ""}\"")
        buildConfigField("String", "BREEZ_SDK_VERSION", "\"${libs.versions.breez.sdk.spark.get()}\"")
    }

    signingConfigs {
        create("release") {
            prop("RELEASE_STORE_FILE")?.let { path ->
                storeFile = rootProject.file(path)
                storePassword = prop("RELEASE_STORE_PASSWORD")
                keyAlias = prop("RELEASE_KEY_ALIAS")
                keyPassword = prop("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = debugApplicationIdSuffix
            resValue("string", "app_name", "Dark Wisp Debug")
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed directly by Gradle when keystore props exist (local.properties or env);
            // contributors without the keystore still get app-release-unsigned.apk
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        // Direct-APK distribution only (Zapstore/GitHub): compressed native libs give the
        // smallest download (44MB vs 82MB measured on 1.0.0 vs 1.0.2). The uncompressed
        // 16KB page-size packaging is only required for Google Play; revisit if Play
        // distribution or 16KB-kernel devices ever matter.
        // Also load-bearing for kmp-tor: its exec loader needs libtor.so extracted to
        // nativeLibraryDir (W^X: app storage is non-executable on targetSdk 29+).
        jniLibs.useLegacyPackaging = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation("junit:junit:4.13.2")

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.secp256k1.kmp)
    implementation(libs.secp256k1.kmp.jni.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.video)
    implementation(libs.security.crypto)
    implementation(libs.bouncycastle)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.biometric)
    implementation(libs.splashscreen)
    implementation(libs.profileinstaller)
    implementation(libs.zxing.core)
    implementation(libs.mlkit.translate)
    implementation(libs.mlkit.language.id)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.objectbox.android)
    implementation(libs.objectbox.kotlin)
    implementation(libs.breez.sdk.spark)
    implementation(libs.kmp.tor.runtime)
    implementation(libs.kmp.tor.runtime.service.ui)
    implementation(libs.kmp.tor.resource.exec)
    implementation(libs.mlkit.barcode)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.camerax.mlkit)
}

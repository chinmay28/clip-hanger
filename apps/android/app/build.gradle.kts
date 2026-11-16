plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// A thin client only: the footage lives on the machine the cameras record to,
// and the server must run beside it, so unlike SAND Vault there is no server
// to build into the APK. No Go, no native libraries.

android {
    namespace = "io.github.chinmay28.cliphanger"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.chinmay28.cliphanger"
        // Android 10: scoped storage and MediaStore.Downloads, so saving a
        // clip needs no storage permission.
        minSdk = 29
        targetSdk = 35
        // Stamped by the release workflow: the release's version as the name,
        // its commit count — which only ever grows — as the code, so each
        // release installs over the last. A local build is 1 / "dev".
        versionCode = (findProperty("appVersionCode") as String?)?.toIntOrNull()?.takeIf { it > 0 } ?: 1
        versionName = (findProperty("appVersionName") as String?) ?: "dev"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the release build sideloads as it
            // stands. Anybody publishing to a store signs it with their own.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        disable += setOf(
            // Plain HTTP is how Clip Hanger runs on a home network, and a
            // private CA is how it runs behind TLS at home: both are the
            // point (see network_security_config.xml).
            "InsecureBaseConfiguration",
            "AcceptsUserCertificates",
            // allowBackup is off; there is nothing for fullBackupContent to say.
            "DataExtractionRules",
            // Versions move on purpose, together, not whenever lint notices.
            "GradleDependency",
        )
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.google.android.material:material:1.12.0")
    // The native clip player — the same Media3 release SAND TV plays films
    // with. ExoPlayer hands the streams to the phone's hardware decoders and
    // reads Matroska, MPEG-TS, FLV and AVI itself (see core/Ladder).
    val media3 = "1.7.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
}

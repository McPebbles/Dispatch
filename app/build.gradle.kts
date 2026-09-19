import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Release signing.
 *
 * Two supported sources, checked in this order:
 *   1. keystore.properties in the repo root (local builds, git-ignored)
 *   2. environment variables (CI):
 *        KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD
 *
 * If neither is present the release build still runs but produces an
 * *unsigned* APK, so `./gradlew assembleRelease` stays usable for inspection.
 */
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

fun signingValue(propKey: String, envKey: String): String? =
    keystoreProps.getProperty(propKey) ?: System.getenv(envKey)

val ksFile: String? = signingValue("storeFile", "KEYSTORE_FILE")
val ksPassword: String? = signingValue("storePassword", "KEYSTORE_PASSWORD")
val ksAlias: String? = signingValue("keyAlias", "KEY_ALIAS")
val ksKeyPassword: String? = signingValue("keyPassword", "KEY_PASSWORD")

val canSignRelease: Boolean = !ksFile.isNullOrBlank() &&
    !ksPassword.isNullOrBlank() &&
    !ksAlias.isNullOrBlank() &&
    !ksKeyPassword.isNullOrBlank()

android {
    namespace = "com.dispatch.reader"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dispatch.reader"
        minSdk = 30
        targetSdk = 36
        versionCode = (project.property("appVersionCode") as String).toInt()
        versionName = project.property("appVersionName") as String
        resourceConfigurations += setOf("en")
    }

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = file(ksFile!!)
                storePassword = ksPassword
                keyAlias = ksAlias
                keyPassword = ksKeyPassword
                // minSdk is 30, so the legacy JAR signature is dead weight.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (canSignRelease) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/*.version",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json"
            )
        }
    }

    dependenciesInfo {
        // Do not embed Google's signed dependency metadata blob in the artifact.
        includeInApk = false
        includeInBundle = false
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // AndroidX only. Nothing here pulls in Google Play services, Firebase, an
    // advertising ID library, or any analytics SDK.
    //
    // There is deliberately no networking library (HttpURLConnection), no XML
    // library (javax.xml SAX, which ships in both the Android runtime and the
    // JVM, so the feed parser is testable without Robolectric), no image
    // loading library (img/ImageStore.kt) and no database library (Room would
    // mean an annotation processor; data/Db.kt is a SQLiteOpenHelper).
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    // The navigation drawer. AndroidX, not Material Components: the panel is a
    // plain LinearLayout with a RecyclerView in it, so nothing here needs
    // com.google.android.material and its theme requirements.
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")

    // Background refresh. WorkManager is AndroidX and uses its own
    // JobScheduler-backed implementation — no Play services, no GCMNetworkManager.
    implementation("androidx.work:work-runtime-ktx:2.10.1")

    // Unit tests only. Everything under feed/, data/ (the pure parts) and
    // widget/WidgetMix.kt is free of android.* imports so it runs on the JVM.
    testImplementation("junit:junit:4.13.2")
}

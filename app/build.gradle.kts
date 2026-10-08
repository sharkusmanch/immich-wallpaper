import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Dev prefill: read from local.properties (never committed); empty when absent.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val devServerUrl: String = localProps.getProperty("immich.server.url", "") ?: ""
val devApiKey: String = localProps.getProperty("immich.api.key", "") ?: ""

android {
    namespace = "dev.immichwall"
    compileSdk = 35

    defaultConfig {
        // Fork ID so this build installs beside upstream's dev.immichwall. The namespace
        // (and with it every package and the generated R class) stays dev.immichwall.
        applicationId = "io.github.sharkusmanch.immichwall"
        minSdk = 34
        targetSdk = 35
        versionCode = 3
        versionName = "1.1.1"

        buildConfigField("String", "DEV_SERVER_URL", "\"${devServerUrl}\"")
        buildConfigField("String", "DEV_API_KEY", "\"${devApiKey}\"")
    }

    // Optional release signing, read from local.properties (never committed):
    //   release.store.file=release.keystore
    //   release.store.password=...
    //   release.key.alias=...
    //   release.key.password=...
    // Absent → the release build is simply unsigned (still buildable by anyone).
    val releaseStoreFile: String = localProps.getProperty("release.store.file", "") ?: ""
    if (releaseStoreFile.isNotBlank()) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = localProps.getProperty("release.store.password", "")
                keyAlias = localProps.getProperty("release.key.alias", "")
                keyPassword = localProps.getProperty("release.key.password", "")
            }
        }
    }

    // CI signing, read from the environment. One stable key signs debug and release so
    // every build installs over the previous one; a fresh debug key per runner would not.
    val ciStoreFile: String = System.getenv("SIGNING_KEYSTORE_FILE").orEmpty()
    if (ciStoreFile.isNotBlank()) {
        signingConfigs {
            create("ci") {
                storeFile = file(ciStoreFile)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_STORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (ciStoreFile.isNotBlank()) {
                signingConfig = signingConfigs.getByName("ci")
            } else if (releaseStoreFile.isNotBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Never ship dev credentials in a release build.
            buildConfigField("String", "DEV_SERVER_URL", "\"\"")
            buildConfigField("String", "DEV_API_KEY", "\"\"")
        }
        debug {
            // inherits defaultConfig DEV_* fields
            if (ciStoreFile.isNotBlank()) {
                signingConfig = signingConfigs.getByName("ci")
            }
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

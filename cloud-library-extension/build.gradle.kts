import java.util.Properties

plugins {
    id("com.android.application") version "8.13.2"
    id("org.jetbrains.kotlin.android") version "2.3.10"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.10"
}

android {
    namespace = "eu.kanade.tachiyomi.extension.all.cloudlibrary"
    compileSdk = 35

    // Prefer repo-root keystore.properties when built via `./gradlew -p cloud-library-extension`
    val keystorePropertiesFile = listOf(
        rootProject.file("keystore.properties"),
        rootProject.file("../keystore.properties"),
    ).firstOrNull { it.exists() }

    if (keystorePropertiesFile != null) {
        val keystoreProperties = Properties().apply {
            load(keystorePropertiesFile.inputStream())
        }
        signingConfigs {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("RELEASE_STORE_FILE"))
                storePassword = keystoreProperties.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = keystoreProperties.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = keystoreProperties.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "eu.kanade.tachiyomi.extension.all.cloudlibrary"
        minSdk = 21
        targetSdk = 35
        // Lib version is derived from versionName before the last dot (1.4).
        versionCode = 1
        versionName = "1.4.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (signingConfigs.findByName("release") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/*.kotlin_module")
        }
    }
}

dependencies {
    // Provided by the host app at runtime
    compileOnly("com.github.tachiyomiorg:extensions-lib:1.4.4")
    compileOnly("io.reactivex:rxjava:1.3.8")
    compileOnly("com.github.mihonapp:injekt:91edab2317")

    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
}

plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0"
}

android {
    namespace = "eu.kanade.tachiyomi.extension.all.cloudlibrary"
    compileSdk = 35

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

    implementation("com.squareup.okhttp3:okhttp:5.0.0-alpha.14")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("androidx.preference:preference-ktx:1.2.1")
}

plugins {
    id("mihon.library")
    kotlin("android")
    kotlin("plugin.serialization")
}

android {
    namespace = "mihon.core.cloudstorage"
}

dependencies {
    api(libs.okhttp.core)
    api(kotlinx.coroutines.core)
    api(kotlinx.serialization.json)
}

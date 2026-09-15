plugins {
    alias(libs.plugins.kotlin.serialization)
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Yandex Music entities are exposed through the shared catalog models of the spotify module
    // (SpotifyTrack/SpotifyAlbum/…), so every screen, queue and resolver works with both sources.
    implementation(project(":spotify"))
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.serialization.json)
    testImplementation(libs.junit)
}

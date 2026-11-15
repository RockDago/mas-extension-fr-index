plugins {
    id("com.android.library")
    kotlin("android")
    id("kotlinx-serialization")
}

android {
    compileSdk = AndroidConfig.COMPILE_SDK

    defaultConfig {
        minSdk = AndroidConfig.MIN_SDK
    }

    namespace = "eu.kanade.tachiyomi.lib.${name.replace("-", "")}"
}

kotlin {
    jvmToolchain(17)
}

versionCatalogs
    .named("libs")
    .findBundle("common")
    .ifPresent { common ->
        dependencies {
            compileOnly(common)
        }
    }

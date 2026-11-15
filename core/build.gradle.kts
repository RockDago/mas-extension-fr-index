plugins {
    id("extensions.android.library")
    id("extensions.kotlin")
}

android {
    namespace = AndroidConfig.coreNamespace

    buildFeatures {
        resValues = false
    }
}

dependencies {
    compileOnly(versionCatalogs.named("libs").findBundle("common").get())
}
plugins {
    id("extensions.android.library")
    id("extensions.kotlin")
}

android {
    namespace = "eu.kanade.tachiyomi.lib.${name.replace("-", "")}"

    buildFeatures {
        resValues = false
    }
}

dependencies {
    compileOnly(project(":core"))
    compileOnly(versionCatalogs.named("libs").findBundle("common").get())
}
plugins {
    id("extensions.aniyomi.library")
    kotlin("android")
    id("kotlinx-serialization")
}

android {
    namespace = "eu.kanade.tachiyomi.multisrc.${project.name}"

    sourceSets {
        named("main") {
            manifest.srcFile("AndroidManifest.xml")
            java.setSrcDirs(listOf("src"))
            res.setSrcDirs(listOf("res"))
            assets.setSrcDirs(listOf("assets"))
        }
    }

    buildFeatures {
        resValues = false
    }

    /*
    kotlinOptions {
        freeCompilerArgs += "-opt-in=kotlinx.serialization.ExperimentalSerializationApi"
    }
    */
}

/*
versionCatalogs
    .named("libs")
    .findBundle("common")
    .ifPresent { common ->
        dependencies {
            compileOnly(common)
            implementation(project(":core"))
        }
    }
*/
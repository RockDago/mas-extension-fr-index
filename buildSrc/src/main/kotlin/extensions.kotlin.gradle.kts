plugins {
    kotlin("android")
    id("kotlinx-serialization")
}

kotlin {
    jvmToolchain(17)

    compilerOptions {
        optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
        freeCompilerArgs.addAll(
            "-Xcontext-parameters",
            "-Xmulti-dollar-interpolation",
            "-Xjvm-default=all-compatibility",
        )
    }
}
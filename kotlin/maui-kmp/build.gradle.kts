plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
}

android {
    namespace = "de.voize.mauikmp"
    compileSdk = 33

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility(JavaVersion.VERSION_1_8)
        targetCompatibility(JavaVersion.VERSION_1_8)
    }
}

kotlin {
    jvmToolchain(17)

    jvm()
    androidTarget {
        publishLibraryVariants("release")
    }


    iosX64()
    iosArm64()
    iosSimulatorArm64()
    wasmJs {
        nodejs()
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }

        all {
            languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }
    }
}

publishing {
    publications.withType<MavenPublication> {
        pom {
            name.set("maui-kmp")
            description.set("Toolkit for combining Kotlin Multiplatform and MAUI")
        }
    }
}

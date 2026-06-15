plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.gradle.plugin.android)
    implementation(libs.gradle.plugin.fat.aar)
}

// The root buildscript puts the Kotlin Gradle Plugin (2.3.20, from the version catalog) on every
// subproject's classpath. That is newer than the Kotlin embedded in `kotlin-dsl` (Gradle 8.9 →
// 1.9.23, api/language 1.8), and the strict kotlin-dsl version check escalates the mismatch to a
// hard compile failure. Pin this binary-plugin module's api/language version to 2.1 (as the Kotlin
// plugin itself recommends) so it compiles against the catalog's compiler without that conflict.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
    }
}

gradlePlugin {
    plugins {
        create("mauiKmp") {
            id = "de.voize.mauikmp.plugin.mauikmp"
            implementationClass = "de.voize.mauikmp.plugin.MauiKmp"
        }
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    withSourcesJar()
}

repositories {
    mavenCentral()
    exclusiveContent {
        forRepository {
            maven {
                setUrl("https://jitpack.io")
            }
        }

        filter {
            includeGroup("com.github.aasitnikov")
        }
    }
}

publishing {
    publications.withType<MavenPublication> {
        pom {
            name.set("maui-kmp-gradle-plugin")
            description.set("Gradle plugin for MAUI KMP")
        }
    }
}

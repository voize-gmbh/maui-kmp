plugins {
    `java-gradle-plugin`
    `kotlin-dsl`
}

dependencies {
    implementation(libs.gradle.plugin.android)
    implementation(libs.gradle.plugin.fat.aar)
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

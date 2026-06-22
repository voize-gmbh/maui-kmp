import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import com.google.devtools.ksp.gradle.KspAATask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinxSerialization)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.ksp)
}

// The framework baseName determines the ObjC class-name prefix Kotlin/Native uses (baseName
// "shared" → prefix "Shared"), and must match the `maui.kmp.csharp.ios.frameworkPrefix` KSP arg
// below. Change both together if you need a different prefix.
val frameworkName = "shared"

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    val xcf = XCFramework(frameworkName)
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = frameworkName
            // Dynamic (not static): when consuming the XCFramework directly via NativeReference
            // (without an intermediate Xcode wrapper project), a static .a gets dead-stripped by
            // the linker because the ObjC methods are only invoked at runtime via objc_msgSend —
            // the linker sees no static references and removes them. A dynamic framework preserves
            // all symbols. (Adding -ObjC would keep them but also force-loads the bundled
            // Compose/skiko objects, which fails to link.)
            isStatic = false
            xcf.add(this)
        }
    }
    
    sourceSets {
        
        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
        }
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)

            implementation(libs.maui.kmp)
        }
    }
}

android {
    namespace = "com.mauikmpexample.kotlin"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

ksp {
    // frameworkPrefix must be the ObjC class prefix produced by Kotlin/Native, which is
    // frameworkName.replaceFirstChar { it.uppercaseChar() } — i.e. "shared" → "Shared".
    arg("maui.kmp.csharp.ios.namespace", "Voize")
    arg("maui.kmp.csharp.ios.frameworkPrefix", frameworkName.replaceFirstChar { it.uppercaseChar() })

    // Sync error contract: every synchronous @MauiBinding function/constructor must be
    // annotated @Throws(Exception::class) so thrown exceptions reach the C# host as a catchable
    // NSError instead of terminating the process. The async adapter types below are exempt (they
    // marshal errors via callbacks), both as classes and as function return types.
    arg(
        "maui.kmp.ios.asyncAdapterTypes",
        listOf(
            "com.mauikmpexample.kotlin.shared.binding.Task",
            "com.mauikmpexample.kotlin.shared.binding.CompletableTask",
            "com.mauikmpexample.kotlin.shared.binding.ObservableFlow",
            "com.mauikmpexample.kotlin.shared.binding.ObservableBooleanFlow",
        ).joinToString(","),
    )
    // Opt out globally with: arg("maui.kmp.ios.requireThrowsOnSyncBindings", "false")

    // Emit deprecated `new SharedX(...)` shims for @Throws constructors so existing call sites keep
    // compiling during a migration (with an [Obsolete] warning). Off by default → `new SharedX()` is
    // a hard compile error instead. Enabled here to exercise the shim path in the example.
    arg("maui.kmp.ios.emitDeprecatedConstructorShims", "true")

    // Name of the generated static wrapper/factory class. Defaults to "MauiKmpThrowsWrappers";
    // overridden here to exercise the rename option.
    arg("maui.kmp.ios.throwsWrapperClassName", "VoizeSdk")

    // To use custom values, uncomment and modify:
    // arg("maui.kmp.csharp.ios.namespace", "MyCompany.Mobile")
    // arg("maui.kmp.csharp.ios.frameworkPrefix", "Native")
}

dependencies {
    debugImplementation(compose.uiTooling)
    add("kspCommonMainMetadata", libs.maui.kmp.ksp)
    add("kspAndroid", libs.maui.kmp.ksp)
    add("kspIosX64", libs.maui.kmp.ksp)
    add("kspIosArm64", libs.maui.kmp.ksp)
    add("kspIosSimulatorArm64", libs.maui.kmp.ksp)
}

tasks.withType<KspAATask>().configureEach {
    if(name != "kspCommonMainKotlinMetadata") {
        dependsOn("kspCommonMainKotlinMetadata")
    } else {
        finalizedBy("copyGeneratedMauiIosFiles")
    }
}

// this is needed if K2 is disabled
tasks.withType<KotlinCompilationTask<*>>().configureEach {
    if(name != "kspCommonMainKotlinMetadata") {
        dependsOn("kspCommonMainKotlinMetadata")
    } else {
        finalizedBy("copyGeneratedMauiIosFiles")
    }
}


tasks.register<Copy>("copyGeneratedMauiIosFiles") {
    dependsOn("kspKotlinIosArm64")
    from("build/generated/ksp/iosArm64/iosArm64Main/resources/maui-kmp")
    into("../../maui/binding/generated")
    finalizedBy("verifyGeneratedMauiIosBindings")
}

/**
 * Regression guard for the `kotlinx.datetime.Instant` binding collapse.
 *
 * This module deliberately depends on the `kotlinx-datetime:…-0.6.x-compat` artifact (so
 * `kotlinx.datetime.Instant` is a distinct class on the classpath) while the `@MauiBinding` API uses
 * only `kotlin.time.Instant` — i.e. the class is present but UNREACHABLE. Kotlin/Native exports ObjC
 * classes by reachability, so it never exports `SharedKotlinx_datetimeInstant`; emitting that binding
 * would force-require a missing `_OBJC_CLASS_$_SharedKotlinx_datetimeInstant` symbol and break linking.
 *
 * Asserts on the REAL generated `ApiDefinitions.cs` (produced by the Kotlin/Native iosArm64 KSP run,
 * the only place the C# generator runs — gated behind `platforms.isIOS()`, so a JVM
 * `kotlin-compile-testing` unit test cannot reach it). Reverting the fix makes this task fail:
 *  - reachability guard (`emitKotlinxDatetimeInstantBinding`) reappears as the orphan
 *    `SharedKotlinx_datetimeInstant` binding interface.
 *  - The library-typealias filter reappears as a dead `using Instant = …;` alias directive (plain 0.7.x).
 */
tasks.register("verifyGeneratedMauiIosBindings") {
    description = "Fails if ApiDefinitions.cs reintroduces an orphan kotlinx.datetime.Instant binding (SDK-113/116)."
    val apiDefinitions = project.file("../../maui/binding/generated/ApiDefinitions.cs")
    doLast {
        require(apiDefinitions.exists()) {
            "Generated ApiDefinitions.cs not found at $apiDefinitions — run copyGeneratedMauiIosFiles first."
        }
        val text = apiDefinitions.readText()
        val problems = buildList {
            if (text.contains("SharedKotlinx_datetimeInstant")) {
                add("found 'SharedKotlinx_datetimeInstant' — orphan kotlinx.datetime.Instant binding (reachability-guard regression)")
            }
            if (Regex("""using\s+Instant\s*=""").containsMatchIn(text)) {
                add("found 'using Instant = …;' — dead kotlinx.datetime.Instant alias directive (typealias-filter regression)")
            }
        }
        require(problems.isEmpty()) {
            "ApiDefinitions.cs failed the kotlinx.datetime.Instant binding regression check:\n" +
                problems.joinToString("\n") { "  - $it" }
        }
        logger.lifecycle("verifyGeneratedMauiIosBindings: ApiDefinitions.cs OK (kotlinx.datetime.Instant collapses onto SharedKotlinInstant)")
    }
}

/**
 * Builds the debug XCFramework (device + simulator slices) and copies it next to the iOS binding
 * project so its `<NativeReference>` can resolve `$frameworkName.xcframework`. Run this before
 * building the MAUI app. The assemble task is auto-created by the `XCFramework(frameworkName)` DSL.
 */
tasks.register<Copy>("copyXCFrameworkToBinding") {
    dependsOn("assemble${frameworkName.replaceFirstChar { it.uppercaseChar() }}DebugXCFramework")
    from("build/XCFrameworks/debug")
    into("../../maui/binding")
}

/**
 * Compiles the iOS binding project and packs it as a NuGet package.
 *
 * Output goes to the default MSBuild location: `example/maui/binding/bin/Release/`.
 * The app project consumes the package via `<PackageReference>` using `example/maui/nuget.config`
 * (which lists `binding/bin/Release` as a local feed).
 *
 * After running this task, delete `~/.nuget/packages/mauikmpexample.ios/` to force NuGet to
 * re-extract the fresh package on the next restore (version is fixed at 0.1.0).
 * The `just pack` recipe does this automatically.
 */
tasks.register("packIosBinding") {
    dependsOn("copyXCFrameworkToBinding", "copyGeneratedMauiIosFiles")
    description = "Compiles the iOS binding and packs it as a NuGet package (output: binding/bin/Release/)."
    doLast {
        exec {
            workingDir(project.file("../../maui/binding"))
            commandLine("dotnet", "pack", "-p:FrameworkType=Debug", "-c", "Release")
        }
    }
}

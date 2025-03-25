package de.voize.mauikmp.ksp.processor

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAnnotationsByType
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.PlatformInfo
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.Modifier
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonClassDiscriminator

internal const val generatedCommonFilePath = "maui-kmp/"

internal fun kspDependencies(
    aggregating: Boolean,
    originatingKSFiles: Iterable<KSFile>,
): Dependencies = Dependencies(aggregating, *originatingKSFiles.toList().toTypedArray())

@OptIn(ExperimentalSerializationApi::class)
internal fun KSDeclaration.getDiscriminatorKeyForSealedClass(): String {
    val defaultDiscriminatorKey = "type"
    return getJsonClassDiscriminatorAnnotationOrNull()?.discriminator
        ?: defaultDiscriminatorKey
}

@OptIn(KspExperimental::class, ExperimentalSerializationApi::class)
private fun KSDeclaration.getJsonClassDiscriminatorAnnotationOrNull(): JsonClassDiscriminator? =
    getAnnotationsByType(JsonClassDiscriminator::class).singleOrNull()

internal fun KSClassDeclaration.isSealedClassSubclass() =
    this.superTypes.any { com.google.devtools.ksp.symbol.Modifier.SEALED in it.resolve().declaration.modifiers }

internal fun KSClassDeclaration.getSealedSuperclass(): KSDeclaration? =
    if (isSealedClassSubclass()) {
        superTypes
            .map { it.resolve().declaration }
            .single { com.google.devtools.ksp.symbol.Modifier.SEALED in it.modifiers }
    } else {
        null
    }

internal const val JvmPlatform = "JVM"
internal const val NativePlatform = "Native"

internal fun List<PlatformInfo>.isCommon(): Boolean {
    val platformNames = this.map { it.platformName }
    return JvmPlatform in platformNames && NativePlatform in platformNames
}

internal fun List<PlatformInfo>.isIOS(): Boolean {
    val platformNames = this.map { it.platformName }
    return JvmPlatform !in platformNames && NativePlatform in platformNames
}

internal fun List<PlatformInfo>.isAndroid(): Boolean {
    val platformNames = this.map { it.platformName }
    return JvmPlatform in platformNames && NativePlatform !in platformNames
}

internal fun KSDeclaration.isAnnotatedWithMauiBinding(wellKnownTypes: MauiModuleGenerator.WellKnownTypes) =
    annotations.any { it.annotationType.resolve() == wellKnownTypes.mauiBindingAnnotationType }

internal fun KSDeclaration.isAnnotatedWithMauiBindingIgnore(wellKnownTypes: MauiModuleGenerator.WellKnownTypes) =
    annotations.any { it.annotationType.resolve() == wellKnownTypes.mauiBindingIgnoreAnnotationType }

internal fun KSClassDeclaration.getMauiFunctionsAndConstructors(
    wellKnownTypes: MauiModuleGenerator.WellKnownTypes,
): Sequence<KSFunctionDeclaration> {
    val isDataClass = Modifier.DATA in modifiers
    return getDeclaredFunctions().filter {
        Modifier.INTERNAL !in it.modifiers &&
            Modifier.PRIVATE !in it.modifiers &&
            (
                isDataClass || it.isAnnotatedWithMauiBinding(wellKnownTypes)
            ) &&
            !it.isAnnotatedWithMauiBindingIgnore(wellKnownTypes)
    }
}

internal fun KSClassDeclaration.getMauiProperties(wellKnownTypes: MauiModuleGenerator.WellKnownTypes): Sequence<KSPropertyDeclaration> {
    val isDataClass = Modifier.DATA in modifiers
    return getDeclaredProperties().filter {
        Modifier.INTERNAL !in it.modifiers &&
            Modifier.PRIVATE !in it.modifiers &&
            (
                isDataClass || it.isAnnotatedWithMauiBinding(wellKnownTypes)
            ) &&
            !it.isAnnotatedWithMauiBindingIgnore(wellKnownTypes)
    }
}

internal fun KSDeclaration.resolveTypeAliases(): KSDeclaration =
    when (this) {
        is KSTypeAlias ->
            this.type
                .resolve()
                .declaration
                .resolveTypeAliases()

        else -> this
    }

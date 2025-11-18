package de.voize.mauikmp.plugin

import groovy.json.JsonSlurper
import java.io.ByteArrayOutputStream
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolvedConfiguration
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.Usage
import org.gradle.kotlin.dsl.named

fun Configuration.configureConfiguration(project: Project) {
    attributes {
        attribute(KotlinPlatformType.attribute, KotlinPlatformType.androidJvm)
        attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, project.objects.named(Category.LIBRARY))
    }
}

data class MavenCoordinates(
    val group: String,
    val name: String,
    val version: String,
)

data class MavenCoordinatesWithoutVersion(
    val group: String,
    val name: String,
)

fun MavenCoordinates.withoutVersion(): MavenCoordinatesWithoutVersion =
    MavenCoordinatesWithoutVersion(
        group = group,
        name = name,
    )

fun MavenCoordinatesWithoutVersion.withVersion(version: String): MavenCoordinates =
    MavenCoordinates(
        group = group,
        name = name,
        version = version,
    )

data class NuGetPackageReference(
    val id: String,
    val version: String,
    val mavenCoordinates: String,
)

fun Project.getNuGetPackageForMavenDependency(searchTerm: String): List<Map<String, String>> {
    val searchResults =
        ByteArrayOutputStream()
            .use { stdout ->
                exec {
                    commandLine(
                        "dotnet",
                        "package",
                        "search",
                        searchTerm,
                        "--format",
                        "json",
                        "--prerelease",
                        "--verbosity",
                        "minimal",
                    )
                    standardOutput = stdout
                    errorOutput = System.err
                }
                JsonSlurper().parse(stdout.toByteArray()) as Map<String, Any>
            }.getValue("searchResult") as List<Map<String, Any>>

    return searchResults.flatMap { it.getValue("packages") as List<Map<String, String>> }
}

fun Project.getNuGetPackageForMavenDependency(
    group: String,
    name: String,
    version: String,
): NuGetPackageReference {
    val searchTerm = "tags:\"artifact_versioned=$group:$name:$version\""
    val packagesWithMatchingVersion = getNuGetPackageForMavenDependency(searchTerm)

    val packages =
        packagesWithMatchingVersion.ifEmpty {
            logger.warn("No matching version found for group: $group:$name:$version. Using latest version.")
            getNuGetPackageForMavenDependency(searchTerm = "tags:\"artifact=$group:$name\"")
        }

    if (packages.isEmpty()) {
        throw IllegalStateException("No matching NuGet package found for group: $group:$name:$version")
    } else {
        val packageInfo = packages.first()
        val nuGetPackageId = packageInfo.getValue("id")
        val nuGetPackageVersion = packageInfo.getValue("latestVersion")
        return NuGetPackageReference(
            id = nuGetPackageId,
            version = nuGetPackageVersion,
            mavenCoordinates = "$group:$name:$version",
        )
    }
}

fun ResolvedConfiguration.getMavenCoordinatesForNuGetMapping(): List<MavenCoordinates> =
    resolvedArtifacts.map {
        val module = it.moduleVersion.id
        MavenCoordinates(
            group = module.group,
            name = module.name,
            version = module.version,
        )
    }

fun MavenCoordinatesWithoutVersion.getMavenCoordinatesInNuGetEcosystem(extension: MauiKmpExtension): MavenCoordinatesWithoutVersion =
    extension.mavenArtifactToNugetPackageTagOverrider.get()[this] ?: this

fun List<Map<String, String>>.hasNuGetPackage(
    module: MavenCoordinates,
    extension: MauiKmpExtension,
): Boolean {
    val coordinatesWithoutVersion = module.withoutVersion()
    val actualModule = coordinatesWithoutVersion.getMavenCoordinatesInNuGetEcosystem(extension)
    return any { mapping ->
        val group = mapping.getValue("groupId")
        val name = mapping.getValue("artifactId")
        actualModule.group == group && actualModule.name == name
    }
}

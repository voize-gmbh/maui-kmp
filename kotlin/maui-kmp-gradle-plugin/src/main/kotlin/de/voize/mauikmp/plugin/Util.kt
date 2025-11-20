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

fun Project.getNuGetPackageForMavenDependency(
    searchTerm: String,
    withPrereleases: Boolean,
): List<Map<String, String>> {
    val searchResults =
        ByteArrayOutputStream()
            .use { stdout ->
                exec {
                    commandLine(
                        listOf(
                            "dotnet",
                            "package",
                            "search",
                            searchTerm,
                            "--format",
                            "json",
                            "--prerelease",
                            "--verbosity",
                            "minimal",
                        ) +
                            if (withPrereleases) {
                                listOf("--prerelease")
                            } else {
                                emptyList()
                            },
                    )
                    standardOutput = stdout
                    errorOutput = System.err
                }
                JsonSlurper().parse(stdout.toByteArray()) as Map<String, Any>
            }.getValue("searchResult") as List<Map<String, Any>>

    return searchResults.flatMap { it.getValue("packages") as List<Map<String, String>> }
}

fun Project.listNuGetPackageWithName(
    name: String,
    withPrereleases: Boolean,
): List<Map<String, String>> {
    val searchResults =
        ByteArrayOutputStream()
            .use { stdout ->
                exec {
                    commandLine(
                        listOf(
                            "dotnet",
                            "package",
                            "search",
                            name,
                            "--format",
                            "json",
                            "--verbosity",
                            "minimal",
                            "--exact-match",
                            "--take",
                            "1000",
                        ) +
                            if (withPrereleases) {
                                listOf("--prerelease")
                            } else {
                                emptyList()
                            },
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
    val mavenCoordinates = "$group:$name:$version"
    val packageWithMatchingVersion =
        (
            getNuGetPackageForMavenDependency(searchTerm, withPrereleases = false).firstOrNull()
                ?: getNuGetPackageForMavenDependency(searchTerm, withPrereleases = true).firstOrNull()
        )?.let {
            NuGetPackageReference(
                id = it.getValue("id"),
                version = it.getValue("latestVersion"),
                mavenCoordinates = mavenCoordinates,
            )
        }

    val bestPackage =
        packageWithMatchingVersion ?: run {
            val packageWithAnyVersion =
                getNuGetPackageForMavenDependency(
                    searchTerm = "tags:\"artifact=$group:$name\"",
                    withPrereleases = true,
                ).firstOrNull()?.let {
                    NuGetPackageReference(
                        id = it.getValue("id"),
                        version = it.getValue("latestVersion"),
                        mavenCoordinates = mavenCoordinates,
                    )
                }
            packageWithAnyVersion?.let {
                logger.warn(
                    "NuGet package search did not return exact match for group: $group:$name:$version." +
                        " Trying to manually find matching version.",
                )
                val nuGetPackageId = it.id
                val allVersions =
                    // fist list stable versions, then prerelease versions
                    listNuGetPackageWithName(nuGetPackageId, withPrereleases = false) +
                        listNuGetPackageWithName(nuGetPackageId, withPrereleases = true)
                val matchingVersion =
                    allVersions
                        // use first to get the lowest version in case of multiple matches, this is the lower bound we need, nuget will resolve higher versions automatically
                        // if we use the highest version here, this may lead to issues if this uses newer transitive dependencies than the ones resolved by gradle
                        // these issues can also happen if we use the lowest version, but are less likely
                        // these issues must be resolved by the user then by overriding the nuget package reference versions
                        .firstOrNull {
                            it
                                .getValue("version")
                                .startsWith(version) // do a prefix match TODO find better way to match versions
                        }?.let {
                            return NuGetPackageReference(
                                id = it.getValue("id"),
                                version = it.getValue("version"),
                                mavenCoordinates = mavenCoordinates,
                            )
                        }
                if (matchingVersion != null) {
                    matchingVersion
                } else {
                    logger.warn("No matching version found for group: $group:$name:$version. Using latest version.")
                    it
                }
            }
        }

    if (bestPackage == null) {
        throw IllegalStateException("No matching NuGet package found for group: $group:$name:$version")
    } else {
        return bestPackage
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

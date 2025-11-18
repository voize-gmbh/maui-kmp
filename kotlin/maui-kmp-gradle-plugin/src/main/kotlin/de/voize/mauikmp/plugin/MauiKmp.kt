package de.voize.mauikmp.plugin

import com.android.build.api.attributes.BuildTypeAttr
import com.android.build.gradle.tasks.BundleAar
import com.kezong.fataar.FatAarExtension
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.resources.TextResource
import org.gradle.kotlin.dsl.add
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.exclude
import org.gradle.kotlin.dsl.named
import java.io.File
import org.gradle.api.Task

interface MauiKmpExtension {
    val mauiNuGetConfigurationName: Property<String>
    val dependencyNotation: Property<String>
    val csprojFile: Property<File>
    val bundleTask: Property<BundleAar>
    val mavenArtifactToNugetPackageTagOverrider: MapProperty<MavenCoordinatesWithoutVersion, MavenCoordinatesWithoutVersion>
    val overrideNuGetPackageReferences: ListProperty<NuGetPackageReference>
    val androidMinSdk: Property<Int>
}

class MauiKmp : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create<MauiKmpExtension>("mauiKmp")
        extension.mauiNuGetConfigurationName.convention("mauiNuGetConfiguration")
        project.afterEvaluate {
            extension.bundleTask.convention(project.tasks.named<BundleAar>("bundleReleaseAar"))
        }
        extension.mavenArtifactToNugetPackageTagOverrider.convention(emptyMap())
        extension.overrideNuGetPackageReferences.convention(emptyList())

        doConfiguration(project, extension)
    }

    private fun doConfiguration(
        project: Project,
        extension: MauiKmpExtension,
    ) {
        val fatArrExcludesFile = project.layout.buildDirectory.file("fat-aar-excludes.json")
        val embedConfigurationName = "embed"

        // also apply fat aar plugin
        project.plugins.apply("com.kezong.fat-aar")
        val fatAarExtension = project.extensions.getByType(FatAarExtension::class.java)
        fatAarExtension.transitive = true
        project.configurations.getByName(embedConfigurationName) {
            configureConfiguration(project)
        }
        project.dependencies.apply {
            attributesSchema {
                attribute(BuildTypeAttr.ATTRIBUTE) {
                    disambiguationRules.add(AndroidBuildTypeAttributeDisambiguationRule::class)
                }
                KotlinPlatformType.setupAttributesMatchingStrategy(this)
            }

            val excludeFile = fatArrExcludesFile.get().asFile
            project.configurations.named(embedConfigurationName).configure {
                if (excludeFile.exists()) {
                    val excludes = JsonSlurper().parse(excludeFile) as List<Map<String, String>>
                    resolutionStrategy {
                        excludes.forEach { exclude ->
                            exclude(
                                group = exclude.getValue("group"),
                                module = exclude.getValue("name"),
                            )
                        }
                    }
                } else {
                    // if the file does not exist, we remove all dependencies, because we don't know what to exclude
                    withDependencies {
                        clear()
                    }
                }
            }
        }

        project.afterEvaluate {
            val mauiNuGetConfiguration =
                project.configurations.create(extension.mauiNuGetConfigurationName.get()) {
                    isCanBeResolved = true
                    configureConfiguration(project)
                }

            project.dependencies.apply {
                project.configurations.named(extension.mauiNuGetConfigurationName.get()).configure {
                    withDependencies {
                        add(
                            project.dependencies.create(
                                extension.dependencyNotation.get(),
                            ),
                        )
                    }
                }
            }

            registerTasks(project, extension, mauiNuGetConfiguration, fatArrExcludesFile)
        }
    }

    private fun registerTasks(
        project: Project,
        extension: MauiKmpExtension,
        mauiNuGetConfiguration: Configuration,
        fatArrExcludesFile: Provider<RegularFile>,
    ) {
        val dotnetAndroidMapping: TextResource =
            project.resources.text.fromUri(
                "https://raw.githubusercontent.com/dotnet/android-libraries/refs/heads/main/config.json",
            )

        project.tasks.create<Task>("generatePackageReferences") {
            inputs.files(mauiNuGetConfiguration, dotnetAndroidMapping)
            outputs.file(extension.csprojFile)

            doLast {
                val json = JsonSlurper().parse(dotnetAndroidMapping.asFile()) as List<Map<String, Any>>
                val artifactMappings = json.single().getValue("artifacts") as List<Map<String, String>>
                // let gradle resolve the dependencies and resolve versions constraints
                val packageReferences =
                    (
                        extension.overrideNuGetPackageReferences.get() +
                            mauiNuGetConfiguration.resolvedConfiguration
                                .getMavenCoordinatesForNuGetMapping()
                                .mapNotNull { module ->
                                    if (artifactMappings.hasNuGetPackage(module, extension)) {
                                        val mavenCoordinatesForNuGet =
                                            module
                                                .withoutVersion()
                                                .getMavenCoordinatesInNuGetEcosystem(extension)
                                                .withVersion(module.version)
                                        project.getNuGetPackageForMavenDependency(
                                            mavenCoordinatesForNuGet.group,
                                            mavenCoordinatesForNuGet.name,
                                            mavenCoordinatesForNuGet.version,
                                        )
                                    } else {
                                        null
                                    }
                                }
                    ).groupBy { it.id }.values.sortedBy { it.first().id }.map { packageReferences ->
                        val packageReference = packageReferences.first()
                        "  <PackageReference Include=\"${packageReference.id}\" Version=\"${packageReference.version}\" /> <!-- ${
                            packageReferences.joinToString {
                                it.mavenCoordinates
                            }
                        } -->"
                    }

                val archiveFile =
                    extension.bundleTask
                        .get()
                        .archiveFile
                        .get()
                val pathToFatAar = archiveFile.asFile.relativeTo(extension.csprojFile.get().parentFile!!)
                val csprojContent =
                    """
        <!-- auto generated -->
        <Project Sdk="Microsoft.NET.Sdk">
          <PropertyGroup>
            <Version>${project.version}</Version>
            <TargetFramework>net9.0-android</TargetFramework>
            <SupportedOSPlatformVersion>${extension.androidMinSdk.get()}</SupportedOSPlatformVersion>
            <Nullable>enable</Nullable>
            <ImplicitUsings>enable</ImplicitUsings>
          </PropertyGroup>
          <ItemGroup>
            <AndroidLibrary Include="$pathToFatAar">
              <Link>${pathToFatAar.name}</Link>
            </AndroidLibrary>
          </ItemGroup>
          <ItemGroup>
            <!-- auto generated -->
${packageReferences.joinToString(separator = "\n").prependIndent("            ")}
          </ItemGroup>
        </Project>
        
                    """.trimIndent()
                extension.csprojFile.get().writeText(csprojContent)
            }
        }

        project.tasks.create("generateFatExcludes") {
            inputs.files(mauiNuGetConfiguration, dotnetAndroidMapping)
            outputs.file(fatArrExcludesFile)
            doLast {
                val json = JsonSlurper().parse(dotnetAndroidMapping.asFile()) as List<Map<String, Any>>
                val artifactMappings = json.single().getValue("artifacts") as List<Map<String, String>>
                // let gradle resolve the dependencies and resolve versions constraints
                val excludes =
                    mauiNuGetConfiguration.resolvedConfiguration
                        .getMavenCoordinatesForNuGetMapping()
                        .mapNotNull { module ->
                            module.takeIf {
                                artifactMappings.hasNuGetPackage(module, extension)
                            }
                        }.toSet()
                val jsonOutput = JsonOutput.toJson(excludes)
                fatArrExcludesFile.get().asFile.writeText(jsonOutput)
            }
        }
    }
}

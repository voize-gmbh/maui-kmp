package de.voize.mauikmp.ksp.processor

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.Origin

/**
 * Builds a tree of namespaces and declarations from a list of types.
 */
internal object MauiNamespaceTree {
    internal data class NamespaceNode(
        val name: String,
        val children: List<NamespaceNode>,
        val declarations: List<KSDeclaration>,
    )

    internal data class BuildResult(
        val rootNamespace: NamespaceNode,
        val originatingFiles: List<KSFile>,
        // Qualified names of EVERY type reachable from the @MauiBinding surface (the full BFS result,
        // before filtering out library/default types). This mirrors what Kotlin/Native exports to ObjC:
        // a hardcoded well-known stub must only be emitted when its type is actually reachable here,
        // otherwise it becomes an orphan binding with no class in the framework and breaks linking.
        val reachableQualifiedNames: Set<String>,
    )

    internal fun build(
        declarations: List<KSDeclaration>,
        wellKnownTypes: MauiModuleGenerator.WellKnownTypes,
    ): BuildResult {
        val allUsedTypes = findAllUsedTypes(declarations, wellKnownTypes)
        val customDeclarations = filterTypesForGeneration(allUsedTypes)

        val rootNamespace = buildNamespaceTree("", customDeclarations)
        val declarationsOriginatingFiles = customDeclarations.mapNotNull { it.containingFile }
        return BuildResult(
            rootNamespace = rootNamespace,
            originatingFiles = declarationsOriginatingFiles,
            reachableQualifiedNames = allUsedTypes.mapNotNull { it.qualifiedName?.asString() }.toSet(),
        )
    }

    // Breath-first search to find all used types(property types, sealed types, function parameters, function return types etc), given initial types
    private fun findAllUsedTypes(
        types: List<KSDeclaration>,
        wellKnownTypes: MauiModuleGenerator.WellKnownTypes,
    ): Set<KSDeclaration> {
        val toBeProcessed = types.toMutableList()
        val processed = mutableSetOf<KSDeclaration>()

        fun scheduleForProcessing(type: KSType) {
            if (type.isError) {
                return
            }
            toBeProcessed.add(type.declaration)
            type.arguments.forEach {
                val type = it.type
                // if not a type variable
                if (type != null) {
                    scheduleForProcessing(type.resolve())
                }
            }
        }

        fun scheduleForProcessing(declaration: KSDeclaration) {
            toBeProcessed.add(declaration)
        }

        while (toBeProcessed.isNotEmpty()) {
            val declaration = toBeProcessed.removeAt(0)

            if (declaration !in processed) {
                processed.add(declaration)

                when (declaration) {
                    is KSClassDeclaration -> {
                        when (declaration.classKind) {
                            ClassKind.CLASS -> {
                                if (com.google.devtools.ksp.symbol.Modifier.SEALED in declaration.modifiers) {
                                    // sealed class
                                    declaration.getSealedSubclasses().forEach {
                                        scheduleForProcessing(it)
                                    }
                                }
                            }

                            else -> Unit
                        }
                        declaration.superTypes.forEach {
                            scheduleForProcessing(it.resolve())
                        }
                        declaration.getMauiFunctionsAndConstructors(wellKnownTypes).forEach {
                            scheduleForProcessing(it)
                        }
                        declaration.getMauiProperties(wellKnownTypes).forEach {
                            scheduleForProcessing(it)
                        }
                    }

                    is KSTypeAlias -> {
                        scheduleForProcessing(declaration.type.resolve())
                    }

                    is KSFunctionDeclaration -> {
                        val returnType =
                            (
                                declaration.returnType ?: error(
                                    "Type resolution error",
                                )
                            ).resolve()

                        val types = declaration.parameters.map { it.type.resolve() } + returnType
                        types.forEach(::scheduleForProcessing)
                    }

                    is KSPropertyDeclaration -> {
                        scheduleForProcessing(declaration.type.resolve())
                    }

                    is KSTypeParameter -> {
                        declaration.bounds.map { it.resolve() }.forEach(::scheduleForProcessing)
                    }

                    else -> {
                        error("Unsupported declaration: $declaration")
                    }
                }
            }
        }
        return processed
    }

    internal fun filterTypesForGeneration(types: Set<KSDeclaration>): Collection<KSDeclaration> {
        val defaultTypes =
            setOf(
                "kotlin.Any",
                "kotlin.Boolean",
                "kotlin.Byte",
                "kotlin.Char",
                "kotlin.Double",
                "kotlin.Float",
                "kotlin.Int",
                "kotlin.Long",
                "kotlin.Number",
                "kotlin.Short",
                "kotlin.String",
                "kotlin.Unit",
                "kotlin.collections.List",
                "kotlin.collections.Map",
                "kotlin.collections.Set",
                "kotlin.Enum",
            )
        val customTypes =
            types.filter {
                it.qualifiedName?.asString() !in defaultTypes
            }

        return customTypes.filter { declaration ->
            when (declaration) {
                is KSClassDeclaration -> declaration.origin == Origin.KOTLIN
                is KSTypeParameter -> false
                // Keep only typealiases declared in the user's own source (Origin.KOTLIN): those are
                // emitted as a local C# `using <Alias> = <Binding>;` directive (see the KSTypeAlias
                // branch in generateDeclaration) and referenced by that alias name in signatures.
                // Drop library typealiases (Origin.KOTLIN_LIB) such as the deprecated
                // `kotlinx.datetime.Instant = kotlin.time.Instant`: their underlying type is already
                // collected by the BFS and resolved by getCSharpObjectCTypeName (which special-cases
                // kotlinx.datetime.Instant -> SharedKotlinInstant), so a `using` directive for the alias
                // would be dead and misleading — it aliases an unrelated binding name that nothing
                // references (e.g. `using Instant = Voize.SharedKotlinInstant;`). NOTE: the orphan
                // SharedKotlinx_datetimeInstant interface / link failure is prevented separately by the
                // `emitKotlinxDatetimeInstantBinding` reachability guard in generateKotlinDefaultTypes;
                // this only removes the stray alias directive.
                is KSTypeAlias -> declaration.origin == Origin.KOTLIN
                is KSFunctionDeclaration -> declaration.parentDeclaration == null // only keep top-level functions
                is KSPropertyDeclaration -> declaration.parentDeclaration == null // only keep top-level properties
                else -> true
            }
        }
    }

    private fun buildNamespaceTree(
        currentNamespace: String,
        declarations: Collection<KSDeclaration>,
    ): NamespaceNode {
        val (children, declarationsInNamespace) =
            declarations.partition {
                it.qualifiedName
                    ?.asString()
                    ?.removePrefix("$currentNamespace.")
                    ?.contains('.') ?: false
            }

        return NamespaceNode(
            name = currentNamespace.substringAfterLast('.'),
            children =
                children
                    .groupBy {
                        it.qualifiedName
                            ?.asString()
                            ?.removePrefix("$currentNamespace.")
                            ?.substringBefore('.')
                            ?: error("Expected a qualified name for $it")
                    }.map { (name, declarations) ->
                        buildNamespaceTree("$currentNamespace.$name".removePrefix("."), declarations)
                    },
            declarations = declarationsInNamespace,
        )
    }
}

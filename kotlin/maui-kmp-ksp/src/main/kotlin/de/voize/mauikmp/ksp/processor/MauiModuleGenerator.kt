package de.voize.mauikmp.ksp.processor

import com.google.devtools.ksp.getClassDeclarationByName
import com.google.devtools.ksp.isConstructor
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.PlatformInfo
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.KSTypeArgument
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.BYTE
import com.squareup.kotlinpoet.CHAR
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.DOUBLE
import com.squareup.kotlinpoet.FLOAT
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.MAP
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.NUMBER
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.SHORT
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.UNIT
import com.squareup.kotlinpoet.asTypeName
import com.squareup.kotlinpoet.buildCodeBlock
import com.squareup.kotlinpoet.joinToCode
import com.squareup.kotlinpoet.ksp.addOriginatingKSFile
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo
import de.voize.mauikmp.ksp.processor.csharp.AliasUsingDirectiveSpec
import de.voize.mauikmp.ksp.processor.csharp.CSharp
import de.voize.mauikmp.ksp.processor.csharp.InterfaceDeclarationSpec
import de.voize.mauikmp.ksp.processor.csharp.NamespaceSpec

class MauiModuleGenerator(
    private val codeGenerator: CodeGenerator,
    private val platforms: List<PlatformInfo>,
    private val options: Map<String, String>,
    private val logger: KSPLogger,
) {
    internal data class MauiClass(
        val wrappedClassDeclaration: KSClassDeclaration,
        val mauiConstructors: List<KSFunctionDeclaration>,
        val mauiMethods: List<KSFunctionDeclaration>,
        val isInternal: Boolean,
    )

    private var invoked = false

    private fun String.iOSModuleClassName() = this + "IOS"

    private fun String.androidModuleClassName() = this + "Android"

    private fun String.moduleProviderClassName() = this + "Provider"

    data class ProcessResult(
        val deferredSymbols: List<KSAnnotated>,
    )

    data class WellKnownTypes(
        val mauiBindingAnnotationType: KSType,
        val mauiBindingIgnoreAnnotationType: KSType,
        val anyDeclaration: KSClassDeclaration,
    )

    internal fun process(resolver: Resolver): ProcessResult {
        val mauiBindingAnnotationType =
            resolver
                .getClassDeclarationByName("$toolkitPackageName.annotation.MauiBinding")
                ?.asType(emptyList())
                ?: error("Could not find MauiBinding annotation")
        val mauiBindingIgnoreAnnotationType =
            resolver
                .getClassDeclarationByName("$toolkitPackageName.annotation.MauiBindingIgnore")
                ?.asType(emptyList())
                ?: error("Could not find MauiBindingIgnore annotation")

        val anyDeclaration = resolver.getClassDeclarationByName<Any>() ?: error("Could not find kotlin.Any")
        val wellKnownTypes =
            WellKnownTypes(
                mauiBindingAnnotationType,
                mauiBindingIgnoreAnnotationType,
                anyDeclaration,
            )

        val functionSymbols = mutableListOf<KSFunctionDeclaration>()
        val classSymbols = mutableListOf<KSClassDeclaration>()
        val propertySymbols = mutableListOf<KSPropertyDeclaration>()

        resolver
            .getSymbolsWithAnnotation("$toolkitPackageName.annotation.MauiBinding")
            .forEach { annotatedNode ->
                when (annotatedNode) {
                    is KSFunctionDeclaration -> {
                        if (!annotatedNode.isConstructor() && annotatedNode.typeParameters.isNotEmpty()) {
                            error("Type parameters are not supported for MAUI functions $annotatedNode at ${annotatedNode.location}")
                        }
                        functionSymbols.add(annotatedNode)
                    }

                    is KSClassDeclaration -> {
                        classSymbols.add(annotatedNode)
                    }

                    is KSPropertyDeclaration -> {
                        if (annotatedNode.typeParameters.isNotEmpty()) {
                            error("Type parameters are not supported for MAUI properties $annotatedNode at ${annotatedNode.location}")
                        }
                        propertySymbols.add(annotatedNode)
                    }

                    else -> throw IllegalArgumentException("Unsupported annotated node $annotatedNode at ${annotatedNode.location}")
                }
            }

        val (topLevelFunctions, classFunctions) = functionSymbols.partition { it.parentDeclaration == null }
        val (topLevelProperties, classProperties) = propertySymbols.partition { it.parentDeclaration == null }

        val functionsByClass =
            classFunctions.groupBy { annotatedNode ->
                annotatedNode.parentDeclaration.let {
                    when (it) {
                        is KSClassDeclaration -> it
                        else -> throw IllegalArgumentException(
                            "MauiBinding functions must be declared in a class, object or top-level: $annotatedNode at ${annotatedNode.location}",
                        )
                    }
                }
            }
        // TODO validate functions are in annotated classes

        val (validSymbols, invalidSymbols) = (classSymbols + topLevelFunctions + topLevelProperties).partition { it.validate() }

        val (rootNamespace, originatingKSFiles) = MauiNamespaceTree.build(validSymbols, wellKnownTypes)

        // generateKotlin(rootNamespace, mauiBindingAnnotationType)

        if (
            invalidSymbols.isEmpty() &&
            !invoked &&
            platforms.isIOS()
        ) {
            generateiOSAppDefinition(rootNamespace, originatingKSFiles, wellKnownTypes)

            invoked = true
        }

        return ProcessResult(
            deferredSymbols = invalidSymbols,
        )
    }

    /**
     * TODO should be config parameter
     */
    val csharpIOSBindingPrefix = "Shared"
    private val csharpIOSBindingNamespace = "Voize"

    private val KotlinAnyClassName =
        CSharp.ClassName(
            simpleName = "${csharpIOSBindingPrefix}Base",
            namespace = csharpIOSBindingNamespace,
            isNullable = false,
            attributes = emptyList(),
        )

    private val KotlinUnitClassName =
        CSharp.ClassName(
            simpleName = "${csharpIOSBindingPrefix}KotlinUnit",
            namespace = csharpIOSBindingNamespace,
            isNullable = false,
            attributes = emptyList(),
        )

    private fun NamespaceSpec.Builder.generateKotlinDefaultTypes() {
        // Kotlin types
        val kotlinBaseClassName = KotlinAnyClassName.simpleName
        addDeclaration(
            InterfaceDeclarationSpec(
                attributes = listOf("BaseType(typeof(NSObject))"),
                identifier = kotlinBaseClassName,
                interfaceTypeList = listOf(INativeObjectClassName),
                rawBody =
                    """
                    [Export ("description")]
                    string ToString ();
                    """.trimIndent(),
            ),
        )
        addDeclaration(
            InterfaceDeclarationSpec(
                attributes = listOf("BaseType (typeof($kotlinBaseClassName))"),
                identifier = "${csharpIOSBindingPrefix}KotlinUnit",
                interfaceTypeList = listOf(KotlinAnyClassName),
                rawBody = "",
            ),
        )
        val kotlinComparable = "${csharpIOSBindingPrefix}KotlinComparable"
        addDeclaration(
            InterfaceDeclarationSpec(
                attributes = listOf("Protocol"),
                identifier = kotlinComparable,
                interfaceTypeList = emptyList(),
                rawBody = "",
            ),
        )
        addDeclaration(
            InterfaceDeclarationSpec(
                attributes = listOf("BaseType (typeof($kotlinBaseClassName))"),
                identifier = "${csharpIOSBindingPrefix}KotlinEnum",
                interfaceTypeList = emptyList(),
                rawBody = "",
            ),
        )
        addDeclaration(
            InterfaceDeclarationSpec(
                attributes = listOf("BaseType(typeof($kotlinBaseClassName))"),
                identifier = "${csharpIOSBindingPrefix}KotlinThrowable",
                interfaceTypeList = emptyList(),
                rawBody =
                    """
                    [Export ("initWithMessage:")]
                    [DesignatedInitializer]
                    $csharpObjCRuntimeNamespace.NativeHandle Constructor ([NullAllowed] string message);

                    [NullAllowed, Export ("message")]
                    string Message { get; }

                    [Export ("asError")]
                    NSError AsError { get; }
                    """.trimIndent(),
            ),
        )
        val kotlinLocalDateClassName = "${csharpIOSBindingPrefix}Kotlinx_datetimeLocalDate"
        val kotlinLocalDateCompanion = "${kotlinLocalDateClassName}Companion"
        addDeclaration(
            InterfaceDeclarationSpec(
                attributes = listOf("BaseType (typeof($kotlinBaseClassName))"),
                identifier = kotlinLocalDateClassName,
                interfaceTypeList = emptyList(),
                rawBody =
                    """
                    [Export ("initWithYear:monthNumber:dayOfMonth:")]
                    [DesignatedInitializer]
                    $csharpObjCRuntimeNamespace.NativeHandle Constructor (int year, int monthNumber, int dayOfMonth);

                    [Export ("year")]
                    int Year { get; }

                    [Export ("monthNumber")]
                    int MonthNumber { get; }

                    [Export ("dayOfMonth")]
                    int DayOfMonth { get; }

                    [Static]
                    [Export ("companion")]
                    $kotlinLocalDateCompanion Companion { [Bind ("companion")] get; }
                    """.trimIndent(),
            ),
        )
        addDeclaration(
            InterfaceDeclarationSpec(
                attributes = listOf("BaseType (typeof($kotlinBaseClassName))"),
                identifier = kotlinLocalDateCompanion,
                interfaceTypeList = emptyList(),
                rawBody =
                    """
                    [Export ("fromEpochDaysEpochDays:")]
                    $kotlinLocalDateClassName FromEpochDays (int epochDays);
                    """.trimIndent(),
            ),
        )
        val kotlinInstantClassName = "${csharpIOSBindingPrefix}Kotlinx_datetimeInstant"
        val kotlinInstantCompanionClassName = "${kotlinInstantClassName}Companion"

        addDeclaration(
            InterfaceDeclarationSpec(
                attributes = listOf("BaseType (typeof($kotlinBaseClassName))"),
                identifier = kotlinInstantClassName,
                interfaceTypeList = emptyList(),
                rawBody =
                    """
                    [Export ("toEpochMilliseconds")]
                    long ToEpochMilliseconds ();

                    [Static]
                    [Export ("companion")]
                    $kotlinInstantCompanionClassName Companion { [Bind ("companion")] get; }
                    """.trimIndent(),
            ),
        )
        addDeclaration(
            InterfaceDeclarationSpec(
                attributes = listOf("BaseType (typeof($kotlinBaseClassName))"),
                identifier = kotlinInstantCompanionClassName,
                interfaceTypeList = emptyList(),
                rawBody =
                    """
                    [Export ("fromEpochMillisecondsEpochMilliseconds:")]
                    $kotlinInstantClassName FromEpochMilliseconds (long epochMilliseconds);

                    [Export ("fromEpochSecondsEpochSeconds:nanosecondAdjustment:")]
                    $kotlinInstantClassName FromEpochSeconds (long epochSeconds, int nanosecondAdjustment);

                    [Export ("fromEpochSecondsEpochSeconds:nanosecondAdjustment_:")]
                    $kotlinInstantClassName FromEpochSeconds (long epochSeconds, long nanosecondAdjustment);

                    [Export ("DISTANT_FUTURE")]
                    $kotlinInstantClassName DISTANT_FUTURE { get; }

                    [Export ("DISTANT_PAST")]
                    $kotlinInstantClassName DISTANT_PAST { get; }
                    """.trimIndent(),
            ),
        )
        val kotlinLocalTimeClassName = "${csharpIOSBindingPrefix}Kotlinx_datetimeLocalTime"
        val kotlinLocalTimeCompanionClassName = "${kotlinLocalTimeClassName}Companion"
        addDeclaration(
            InterfaceDeclarationSpec(
                attributes = listOf("BaseType (typeof($kotlinBaseClassName))"),
                identifier = kotlinLocalTimeClassName,
                interfaceTypeList = emptyList(),
                rawBody =
                    """
                    [Export ("initWithHour:minute:second:nanosecond:")]
                    [DesignatedInitializer]
                    $csharpObjCRuntimeNamespace.NativeHandle Constructor (int hour, int minute, int second, int nanosecond);
                        
                    [Export ("hour")]
                    int Hour { get; }

                    [Export ("minute")]
                    int Minute { get; }

                    [Export ("nanosecond")]
                    int Nanosecond { get; }

                    [Export ("second")]
                    int Second { get; }
                    
                    [Static]
                    [Export ("companion")]
                    $kotlinLocalTimeCompanionClassName Companion { [Bind ("companion")] get; }
                    """.trimIndent(),
            ),
        )
        addDeclaration(
            InterfaceDeclarationSpec(
                attributes = listOf("BaseType (typeof($kotlinBaseClassName))"),
                identifier = kotlinLocalTimeCompanionClassName,
                interfaceTypeList = emptyList(),
                rawBody =
                    """
                    [Export ("fromMillisecondOfDayMillisecondOfDay:")]
                    $kotlinLocalTimeClassName FromMillisecondOfDay (int millisecondOfDay);

                    [Export ("fromNanosecondOfDayNanosecondOfDay:")]
                    $kotlinLocalTimeClassName FromNanosecondOfDay (long nanosecondOfDay);

                    [Export ("fromSecondOfDaySecondOfDay:")]
                    $kotlinLocalTimeClassName FromSecondOfDay (int secondOfDay);
                    """.trimIndent(),
            ),
        )
        val kotlinLocalDateTime = "${csharpIOSBindingPrefix}Kotlinx_datetimeLocalDateTime"
        addDeclaration(
            InterfaceDeclarationSpec(
                attributes = listOf("BaseType (typeof($kotlinBaseClassName))"),
                identifier = kotlinLocalDateTime,
                interfaceTypeList = emptyList(),
                rawBody =
                    """
                    [Export ("initWithYear:monthNumber:dayOfMonth:hour:minute:second:nanosecond:")]
                    [DesignatedInitializer]
                    $csharpObjCRuntimeNamespace.NativeHandle Constructor (int year, int monthNumber, int dayOfMonth, int hour, int minute, int second, int nanosecond);
                    
                    [Export ("date")]
                    $kotlinLocalDateClassName Date { get; }
                    
                    [Export ("time")]
                    $kotlinLocalTimeClassName Time { get; }
                    """.trimIndent(),
            ),
        )
    }

    private fun generateiOSAppDefinition(
        namespace: MauiNamespaceTree.NamespaceNode,
        originatingKSFiles: List<KSFile>,
        wellKnownTypes: WellKnownTypes,
    ) {
        val fileContent =
            buildString {
                append("using Foundation;\n")

                NamespaceSpec
                    .builder(csharpIOSBindingNamespace)
                    .apply {
                        generateKotlinDefaultTypes()
                        generateiOSAppDefinition(namespace, wellKnownTypes)
                    }.build()
                    .writeTo(this)
            }

        codeGenerator
            .createNewFileByPath(
                kspDependencies(true, originatingKSFiles),
                "${generatedCommonFilePath}ApiDefinitions",
                extensionName = "cs",
            ).use {
                it.bufferedWriter().use { writer ->
                    writer.write(fileContent)
                }
            }
    }

    private fun NamespaceSpec.Builder.generateDeclaration(
        declaration: KSDeclaration,
        wellKnownTypes: WellKnownTypes,
    ) {
        try {
            when (declaration) {
                is KSClassDeclaration -> {
                    when (declaration.classKind) {
                        ClassKind.CLASS, ClassKind.OBJECT -> {
                            val isObject = declaration.classKind == ClassKind.OBJECT
                            val superTypes = declaration.superTypes.map { it.resolve().declaration }
                            val superClass =
                                superTypes
                                    .map { it.resolveTypeAliases() }
                                    .filterIsInstance<KSClassDeclaration>()
                                    .singleOrNull() ?: wellKnownTypes.anyDeclaration

                            val identifier =
                                declaration.getCSharpObjectCNamespace() + declaration.getCSharpObjectCName()

                            addDeclaration(
                                InterfaceDeclarationSpec(
                                    attributes =
                                        listOf(
                                            "BaseType (typeof(${
                                                buildString {
                                                    superClass
                                                        .asStarProjectedType()
                                                        .getCSharpObjectCTypeName()
                                                        .writeTo(this, withAttributes = false)
                                                }
                                            }))",
                                        ),
                                    identifier = identifier,
                                    interfaceTypeList = listOf(INativeObjectClassName),
                                    rawBody =
                                        buildString {
                                            val (constructors, functions) =
                                                declaration
                                                    .getMauiFunctionsAndConstructors(
                                                        wellKnownTypes,
                                                    ).partition { it.isConstructor() }

                                            if (isObject && !declaration.isCompanionObject) {
                                                val attributes =
                                                    listOf(
                                                        "Static",
                                                        "Export (\"${getObjectCObjectConstructorExportName(declaration)}\")",
                                                    )
                                                append("    ${attributes.cSharpAttributesToString()}\n")
                                                append("    $identifier ${declaration.getCSharpObjectCName()} ();\n")
                                            } else {
                                                constructors.forEach { constructor ->
                                                    val isPrimary = constructor == declaration.primaryConstructor
                                                    if (constructor.parameters.isEmpty()) {
                                                        val attributes =
                                                            listOf("Static", "Export (\"new\")") +
                                                                if (isPrimary) {
                                                                    listOf("DesignatedInitializer")
                                                                } else {
                                                                    emptyList()
                                                                }
                                                        append("    ${attributes.cSharpAttributesToString()}\n")
                                                        append("    $identifier New ();\n")
                                                    } else {
                                                        val attributes =
                                                            listOf("Export (\"${getObjectCExportName(constructor)}\")") +
                                                                if (isPrimary) {
                                                                    listOf("DesignatedInitializer")
                                                                } else {
                                                                    emptyList()
                                                                }
                                                        append("    ${attributes.cSharpAttributesToString()}\n")
                                                        append("    ")
                                                        NativeHandleClassName.writeTo(this, withAttributes = false)
                                                        append(" Constructor (")
                                                        constructor.parameters.joinTo(
                                                            this,
                                                            separator = ", ",
                                                        ) {
                                                            buildString {
                                                                val type = it.type.resolve()
                                                                type
                                                                    .getCSharpObjectCTypeName(
                                                                        isBindingParameterOrReturnType = true,
                                                                    ).writeTo(this, withAttributes = true)
                                                                append(" ")
                                                                append(it.name!!.asString())
                                                            }
                                                        }
                                                        append(");\n")
                                                    }
                                                    append("\n")
                                                }
                                            }

                                            functions.forEach { function ->
                                                val returnType = function.returnType!!.resolve()
                                                val returnTypeName =
                                                    returnType.getCSharpObjectCTypeName(isBindingParameterOrReturnType = true)
                                                val attributes =
                                                    listOf("Export (\"${getObjectCExportName(function)}\")") +
                                                        returnTypeName.attributes
                                                append("    ${attributes.cSharpAttributesToString()}\n")
                                                append("    ")
                                                returnTypeName.writeTo(this, withAttributes = false)
                                                append(" ")
                                                append(function.getCSharpObjectCName())
                                                append("(")
                                                function.parameters.joinTo(
                                                    this,
                                                    separator = ", ",
                                                ) {
                                                    buildString {
                                                        val type = it.type.resolve()
                                                        type
                                                            .getCSharpObjectCTypeName(isBindingParameterOrReturnType = true)
                                                            .writeTo(this, withAttributes = true)
                                                        append(" ")
                                                        append(it.name!!.asString())
                                                    }
                                                }
                                                append(");\n")
                                                append("\n")
                                            }
                                            val properties = declaration.getMauiProperties(wellKnownTypes)
                                            properties.forEach { property ->
                                                val type = property.type.resolve()
                                                val typeName =
                                                    type.getCSharpObjectCTypeName(isBindingParameterOrReturnType = true)
                                                val attributes =
                                                    listOf("Export (\"${property.getObjectCName()}\")") +
                                                        typeName.attributes
                                                append("    ${attributes.cSharpAttributesToString()}\n")
                                                append("    ")
                                                typeName.writeTo(this, withAttributes = false)
                                                append(" ")
                                                append(property.getCSharpObjectCName())
                                                append(" { ")
                                                if (property.isMutable) {
                                                    append("get; set; ")
                                                } else {
                                                    append("get; ")
                                                }
                                                append("}\n")
                                                append("\n")
                                            }
                                            declaration.declarations
                                                .filterIsInstance<KSClassDeclaration>()
                                                .filter {
                                                    it.isCompanionObject &&
                                                        it.isAnnotatedWithMauiBinding(
                                                            wellKnownTypes,
                                                        )
                                                }.forEach { companion ->
                                                    val companionType =
                                                        companion.getCSharpObjectCNamespace() + companion.getCSharpObjectCName()
                                                    val attributes = listOf("Static", "Export (\"companion\")")
                                                    append("    ${attributes.cSharpAttributesToString()}\n")
                                                    append("    $companionType Companion { [Bind (\"companion\")] get; }\n")
                                                }
                                        },
                                ),
                            )
                        }

                        ClassKind.ENUM_CLASS -> {
                            val identifier =
                                declaration.getCSharpObjectCNamespace() + declaration.getCSharpObjectCName()
                            addDeclaration(
                                InterfaceDeclarationSpec(
                                    attributes = listOf("BaseType (typeof(${csharpIOSBindingPrefix}KotlinEnum))"),
                                    identifier = identifier,
                                    interfaceTypeList = listOf(INativeObjectClassName),
                                    rawBody =
                                        buildString {
                                            declaration.declarations
                                                .filterIsInstance<KSClassDeclaration>()
                                                .filter { it.classKind == ClassKind.ENUM_ENTRY }
                                                .forEach { enumEntry ->
                                                    append("    [Static]\n")
                                                    append("    [Export (\"${getObjectCEnumEntryExportName(enumEntry)}\")]\n")
                                                    append(
                                                        "    $identifier ${enumEntry.getCSharpObjectCName()} { get; }\n",
                                                    )
                                                }
                                        },
                                ),
                            )
                        }

                        else -> error("Unsupported class kind ${declaration.classKind}")
                    }
                }

                is KSFunctionDeclaration -> {
                    error("Top-level functions must be generated grouped by file")
                }

                is KSPropertyDeclaration -> {
                    // generateAppDefinition(declaration)
                }

                is KSTypeAlias -> {
                    addUsingDirective(
                        AliasUsingDirectiveSpec(
                            identifier = declaration.getCSharpObjectCName(),
                            namespaceOrTypeName = declaration.type.resolve().getCSharpObjectCTypeName(),
                        ),
                    )
                }

                else -> {
                    error("Unsupported declaration: $declaration at ${declaration.location}")
                }
            }
        } catch (t: Throwable) {
            throw IllegalArgumentException("Error processing declaration: $declaration at ${declaration.location}", t)
        }
    }

    private fun KSFile.toDummyClassName(): String {
        // replace file extension with first uppercase letter and remove dot
        return fileName
            .fold(("" to false)) { (acc, uppercase), c ->
                when {
                    c == '.' -> acc to true
                    uppercase -> acc + c.uppercaseChar() to false
                    else -> acc + c to false
                }
            }.first
            .replaceFirstChar { it.uppercaseChar() }
    }

    private fun NamespaceSpec.Builder.generateTopLevelFunctions(
        file: KSFile,
        topLevelDeclarations: List<KSDeclaration>,
    ) {
        addDeclaration(
            InterfaceDeclarationSpec(
                attributes = listOf("BaseType (typeof(${KotlinAnyClassName.simpleName}))"),
                identifier = "${csharpIOSBindingPrefix}${file.toDummyClassName()}",
                interfaceTypeList = emptyList(),
                rawBody =
                    buildString {
                        topLevelDeclarations.forEach { declaration ->
                            when (declaration) {
                                is KSFunctionDeclaration -> {
                                    val returnType = declaration.returnType!!.resolve()
                                    val returnTypeName =
                                        returnType.getCSharpObjectCTypeName(isBindingParameterOrReturnType = true)
                                    val attributes =
                                        listOf("Static", "Export (\"${getObjectCExportName(declaration)}\")") +
                                            returnTypeName.attributes
                                    append("    ${attributes.cSharpAttributesToString()}\n")
                                    append("    ")
                                    returnTypeName.writeTo(this, withAttributes = false)
                                    append(" ")
                                    append(declaration.getCSharpObjectCName())
                                    append("(")
                                    declaration.parameters.joinTo(
                                        this,
                                        separator = ", ",
                                    ) {
                                        buildString {
                                            val type = it.type.resolve()
                                            type
                                                .getCSharpObjectCTypeName(isBindingParameterOrReturnType = true)
                                                .writeTo(this, withAttributes = true)
                                            append(" ")
                                            append(it.name!!.asString())
                                        }
                                    }
                                    append(");\n")
                                    append("\n")
                                }

                                is KSPropertyDeclaration -> {
                                    val type = declaration.type.resolve()
                                    val typeName =
                                        type.getCSharpObjectCTypeName(isBindingParameterOrReturnType = true)
                                    val attributes =
                                        listOf(
                                            "Static",
                                            "Export (\"${declaration.getObjectCName()}\")",
                                        ) + typeName.attributes
                                    append("    ${attributes.cSharpAttributesToString()}\n")
                                    append("    ")
                                    typeName.writeTo(this, withAttributes = false)
                                    append(" ")
                                    append(declaration.getCSharpObjectCName())
                                    append(" { ")
                                    if (declaration.isMutable) {
                                        append("get; set; ")
                                    } else {
                                        append("get; ")
                                    }
                                    append("}\n")
                                    append("\n")
                                }

                                else -> error("Unsupported top-level declaration: $declaration")
                            }
                        }
                    },
            ),
        )
    }

    private fun NamespaceSpec.Builder.generateiOSAppDefinition(
        namespace: MauiNamespaceTree.NamespaceNode,
        wellKnownTypes: WellKnownTypes,
    ) {
        val (topLevelFunctionsAndProperties, declarations) =
            namespace.declarations.partition {
                it is KSFunctionDeclaration || it is KSPropertyDeclaration
            }
        declarations.forEach { declaration ->
            generateDeclaration(declaration, wellKnownTypes)
        }

        topLevelFunctionsAndProperties
            .groupBy {
                it.containingFile
            }.forEach { (file, functionsAndProperties) ->
                require(file != null) {
                    "Top-level functionsAndProperties must be from a source file: $functionsAndProperties"
                }
                generateTopLevelFunctions(file, functionsAndProperties)
            }

        namespace.children.forEach { child ->
            generateiOSAppDefinition(child, wellKnownTypes)
        }
    }

    private fun KSType.nullableAsCSharpAttributes(): List<String> = if (isMarkedNullable) listOf("NullAllowed") else emptyList()

    private fun KSType.bindAsAsCSharpAttributes(): List<String> {
        val className =
            when (declaration.qualifiedName?.asString()) {
                "kotlin.Boolean" -> BoolTypeName
                "kotlin.Byte" -> ByteTypeName
                "kotlin.Char" -> CharTypeName
                "kotlin.Double" -> DoubleTypeName
                "kotlin.Float" -> FloatTypeName
                "kotlin.Int" -> IntTypeName
                "kotlin.Long" -> LongTypeName
                "kotlin.Short" -> ShortTypeName
                "kotlin.String" -> StringTypeName
                else -> null
            }?.copy(
                isNullable = isMarkedNullable,
            )

        return if (className != null) {
            val csharpTypeName =
                buildString {
                    className.writeTo(this, withAttributes = false)
                }
            listOf("$csharpObjCRuntimeNamespace.BindAs (typeof ($csharpTypeName))")
        } else {
            emptyList()
        }
    }

    private fun getObjectCExportName(function: KSFunctionDeclaration): String {
        val functionName =
            if (function.isConstructor()) {
                if (function.parameters.isEmpty()) "init" else "initWith"
            } else {
                function.getObjectCName()
            }
        val params =
            function.parameters
                .joinToString(separator = "") { it.name!!.getShortName() + ":" }
                .replaceFirstChar { it.uppercaseChar() }
        return functionName + params
    }

    private fun KSDeclaration.getObjectCName(): String {
        val name = simpleName.asString()
        val lowercaseName = name.lowercase()
        return when {
            lowercaseName == "default" && this !is KSFunctionDeclaration && this !is KSPropertyDeclaration -> name + "_" // reserved keyword only if not a function
            lowercaseName == "description" -> name + "_" // reserved keyword
            lowercaseName.startsWith("init") -> "do" + name.replaceFirstChar { it.uppercaseChar() } // init is reserved for constructors
            else -> name
        }
    }

    private fun KSDeclaration.getCSharpName(): String = simpleName.asString()

    // object constructors
    private fun getObjectCObjectConstructorExportName(enumClassEntry: KSClassDeclaration): String =
        enumClassEntry
            .getObjectCName()
            .replaceFirstChar {
                it.lowercase()
            }

    // for enums
    private fun getObjectCEnumEntryExportName(enumClassEntry: KSClassDeclaration): String {
        // convert UPPER_SNAKE_CASE to lowerCamelCase
        return enumClassEntry
            .getObjectCName()
            .lowercase()
            .fold(("" to false)) { (text, uppercase), c ->
                when {
                    c == '_' -> (if (uppercase) text + "_" else text) to true
                    uppercase -> text + c.uppercaseChar() to false
                    else -> text + c to false
                }
            }.let { (text, uppercase) ->
                if (uppercase) "${text}_" else text
            }
    }

    private fun KSTypeArgument.getCSharpObjectCTypeName(
        wrapped: Boolean,
        depth: Int = 0,
    ): CSharp.TypeName {
        val type = this.type
        return if (type != null) {
            type.resolve().getCSharpObjectCTypeName(
                wrapped = wrapped,
                depth = depth,
            )
        } else {
            // Since K2 KSP returns type null from [KSClassDeclaration.asStarProjectedType] for type arguments, workaround for https://github.com/google/ksp/issues/2464
            logger.warn("Could not resolve type argument, falling back to Any: $this at ${this.location}")
            KotlinAnyClassName
        }
    }

    private fun KSType.getCSharpObjectCTypeName(
        wrapped: Boolean = false,
        isBindingParameterOrReturnType: Boolean = false,
        depth: Int = 0,
    ): CSharp.TypeName {
        try {
            val type = this

            require(depth < 10) {
                "Depth limit reached for type: $this"
            }
            TypeSpec.classBuilder("").superinterfaces

            fun KSTypeArgument.resolveTypeArgument(): CSharp.TypeName = getCSharpObjectCTypeName(wrapped = true, depth = depth + 1)

            fun resolveTypeArgument(index: Int): CSharp.TypeName {
                val argument = type.arguments[index]
                return argument.resolveTypeArgument()
            }

            val typeName =
                if (wrapped || (isBindingParameterOrReturnType && type.isMarkedNullable)) {
                    // use NSNumber for wrapped or nullable primitive types
                    when (type.declaration.qualifiedName?.asString()) {
                        "kotlin.Any" -> KotlinAnyClassName
                        "kotlin.Boolean" -> NSNumberClassName
                        "kotlin.Byte" -> NSNumberClassName
                        "kotlin.Char" -> NSNumberClassName
                        "kotlin.Double" -> NSNumberClassName
                        "kotlin.Float" -> NSNumberClassName
                        "kotlin.Int" -> NSNumberClassName
                        "kotlin.Long" -> NSNumberClassName
                        "kotlin.Number" -> NSNumberClassName
                        "kotlin.Short" -> NSNumberClassName
                        "kotlin.String" -> if (wrapped) NSStringClassName else StringTypeName // don't use NSString for binding parameters
                        "kotlin.Unit" -> if (wrapped) KotlinUnitClassName else VoidTypeName // don't use KotlinUnit for binding parameters
                        "kotlin.time.Duration" -> NSNumberClassName // value class
                        else -> null
                    }?.copy(
                        isNullable = type.isMarkedNullable,
                    )
                } else {
                    when (type.declaration.qualifiedName?.asString()) {
                        "kotlin.Any" -> KotlinAnyClassName
                        "kotlin.Boolean" -> BoolTypeName
                        "kotlin.Byte" -> ByteTypeName
                        "kotlin.Char" -> CharTypeName
                        "kotlin.Double" -> DoubleTypeName
                        "kotlin.Float" -> FloatTypeName
                        "kotlin.Int" -> IntTypeName
                        "kotlin.Long" -> LongTypeName
                        "kotlin.Number" -> NSNumberClassName
                        "kotlin.Short" -> ShortTypeName
                        "kotlin.String" -> StringTypeName
                        "kotlin.Unit" -> VoidTypeName
                        "kotlin.time.Duration" -> LongTypeName // value class
                        else -> null
                    }?.copy(
                        isNullable = type.isMarkedNullable,
                    )
                } ?: when (type.declaration.qualifiedName?.asString()) {
                    "kotlin.Throwable" -> "${csharpIOSBindingPrefix}KotlinThrowable"
                    "kotlinx.datetime.LocalDateTime" -> "${csharpIOSBindingPrefix}Kotlinx_datetimeLocalDateTime"
                    "kotlinx.datetime.LocalDate" -> "${csharpIOSBindingPrefix}Kotlinx_datetimeLocalDate"
                    "kotlinx.datetime.LocalTime" -> "${csharpIOSBindingPrefix}Kotlinx_datetimeLocalTime"
                    "kotlinx.datetime.Instant" -> "${csharpIOSBindingPrefix}Kotlinx_datetimeInstant"
                    else -> null
                }?.let {
                    CSharp.ClassName(
                        simpleName = it,
                        namespace = csharpIOSBindingNamespace,
                        isNullable = type.isMarkedNullable,
                    )
                } ?: when {
                    type.declaration.packageName
                        .asString()
                        .startsWith("platform.") -> {
                        CSharp.ClassName(
                            simpleName = type.declaration.simpleName.asString(),
                            namespace =
                                type.declaration.packageName
                                    .asString()
                                    .removePrefix("platform."),
                            isNullable = type.isMarkedNullable,
                        )
                    }

                    else -> null
                } ?: when (type.declaration.qualifiedName?.asString()) {
                    // converted to NSArray and used as Arrays
                    "kotlin.collections.List", "kotlin.collections.Set" ->
                        CSharp.ParameterizedTypeName(
                            rawType =
                                if (wrapped) {
                                    NSArrayClassName
                                } else {
                                    ArrayClassName
                                }.copy(
                                    isNullable = type.isMarkedNullable,
                                ),
                            typeArguments = listOf(resolveTypeArgument(0)),
                        )

                    "kotlin.collections.Map" ->
                        CSharp.ParameterizedTypeName(
                            rawType =
                                CSharp.ClassName(
                                    simpleName = "NSDictionary",
                                    namespace = csharpFoundationNamespace,
                                    isNullable = type.isMarkedNullable,
                                ),
                            typeArguments =
                                listOf(
                                    resolveTypeArgument(0),
                                    resolveTypeArgument(1),
                                ),
                        )

                    else -> null
                } ?: run {
                    val typeArguments =
                        type.arguments.map {
                            it.resolveTypeArgument()
                        }
                    when (val declaration = type.declaration) {
                        is KSTypeAlias -> {
                            CSharp.ParameterizedTypeName(
                                rawType =
                                    CSharp.ClassName(
                                        simpleName = declaration.getCSharpObjectCName(),
                                        namespace = "", // local reference (only type alias in the current source are supported)
                                        isNullable = type.isMarkedNullable,
                                    ),
                                typeArguments = typeArguments,
                            )
                        }

                        is KSClassDeclaration ->
                            when {
                                type.isFunctionType -> {
                                    if (typeArguments.last() ==
                                        CSharp.ClassName(
                                            simpleName = "${csharpIOSBindingPrefix}KotlinUnit",
                                            namespace = csharpIOSBindingNamespace,
                                            isNullable = false,
                                        )
                                    ) {
                                        CSharp.ParameterizedTypeName(
                                            rawType =
                                                ActionClassName.copy(
                                                    isNullable = type.isMarkedNullable,
                                                ),
                                            typeArguments = typeArguments.dropLast(1),
                                        )
                                    } else {
                                        CSharp.ParameterizedTypeName(
                                            rawType =
                                                FuncClassName.copy(
                                                    isNullable = type.isMarkedNullable,
                                                ),
                                            typeArguments = typeArguments,
                                        )
                                    }
                                }

                                type.isSuspendFunctionType ->
                                    CSharp.ClassName(
                                        simpleName =
                                            type.declaration.getCSharpObjectCNamespace() +
                                                type.declaration.getCSharpObjectCName(),
                                        namespace = csharpIOSBindingNamespace,
                                        isNullable = type.isMarkedNullable,
                                    )
                                // else -> type.declaration.getCSharpObjectCNamespace() + type.declaration.getCSharpObjectCName() + (if (type.arguments.isNotEmpty()) "<${typeArguments}>" else "")
                                else ->
                                    CSharp.ClassName(
                                        simpleName = type.declaration.getCSharpObjectCNamespace() + type.declaration.getCSharpObjectCName(),
                                        namespace = csharpIOSBindingNamespace,
                                        isNullable = type.isMarkedNullable,
                                    ) // do not include type arguments for custom classes
                            }

                        is KSTypeParameter ->
                            declaration.bounds
                                .first()
                                .resolve()
                                .getCSharpObjectCTypeName(
                                    wrapped = wrapped,
                                    depth = depth + 1,
                                    isBindingParameterOrReturnType = isBindingParameterOrReturnType,
                                ).let {
                                    if (it is CSharp.ClassName &&
                                        it.simpleName == KotlinAnyClassName.simpleName &&
                                        it.namespace == csharpIOSBindingNamespace
                                    ) {
                                        // extend upper bound to NSObject if kotlin Any
                                        CSharp.ClassName(
                                            simpleName = "NSObject",
                                            namespace = csharpFoundationNamespace,
                                            isNullable = it.isNullable,
                                            attributes = it.attributes,
                                        )
                                    } else {
                                        it
                                    }
                                } // replace type parameter reference with upper bound

                        else -> error("Unsupported type to convert to C#: $type")
                    }
                }

            return if (isBindingParameterOrReturnType) {
                // if this is a binding parameter or return type and is foundation wrapper type,
                // we need to add bindAs and the nullable attribute
                // and unset the nullable property of the type name itself
                when (typeName) {
                    is CSharp.ClassName -> {
                        typeName.copy(
                            isNullable = false,
                            attributes =
                                typeName.attributes +
                                    if (typeName.namespace == csharpFoundationNamespace) {
                                        type.bindAsAsCSharpAttributes()
                                    } else {
                                        emptyList()
                                    } + type.nullableAsCSharpAttributes(),
                        )
                    }

                    is CSharp.ParameterizedTypeName -> {
                        typeName.copy(
                            rawType =
                                typeName.rawType.copy(
                                    isNullable = false,
                                    attributes =
                                        typeName.rawType.attributes +
                                            if (typeName.rawType.namespace == csharpFoundationNamespace) {
                                                type.bindAsAsCSharpAttributes()
                                            } else {
                                                emptyList()
                                            } + type.nullableAsCSharpAttributes(),
                                ),
                        )
                    }
                }
            } else {
                typeName
            }
        } catch (t: Throwable) {
            throw IllegalArgumentException("Error converting type to C#: $this", t)
        }
    }

    private fun KSDeclaration.getCSharpObjectCNamespace(): String {
        val parent = parentDeclaration
        return if (parent != null) {
            parent.getCSharpObjectCNamespace() + parent.getCSharpObjectCName()
        } else {
            csharpIOSBindingPrefix
        }
    }

    private fun KSDeclaration.getCSharpObjectCName(): String = getCSharpName().replaceFirstChar { it.uppercaseChar() }

    private fun generateKotlin(
        namespace: MauiNamespaceTree.NamespaceNode,
        wellKnownTypes: WellKnownTypes,
    ) {
        namespace.declarations.forEach { declaration ->
            when (declaration) {
                is KSClassDeclaration -> {
                    generateKotlinClassWrapper(declaration, wellKnownTypes)
                }

                is KSFunctionDeclaration -> {
                    TODO("Generate function")
                    // generate(declaration)
                }
            }
        }

        namespace.children.forEach { child ->
            generateKotlin(child, wellKnownTypes)
        }
    }

    private fun generateKotlinClassWrapper(
        classDeclaration: KSClassDeclaration,
        wellKnownTypes: WellKnownTypes,
    ) {
        val (mauiConstructors, mauiMethods) =
            classDeclaration
                .getMauiFunctionsAndConstructors(
                    wellKnownTypes,
                ).partition { it.isConstructor() }
        val isInternal = classDeclaration.modifiers.contains(Modifier.INTERNAL)

        val mauiClass =
            MauiClass(
                wrappedClassDeclaration = classDeclaration,
                mauiConstructors = mauiConstructors + listOfNotNull(classDeclaration.primaryConstructor),
                mauiMethods = mauiMethods,
                isInternal = isInternal,
            )
        try {
            val packageName = classDeclaration.packageName.asString()
            val wrappedClassName = classDeclaration.simpleName.asString()

            if (platforms.isAndroid()) {
                createAndroidMauiClass(
                    mauiClass,
                    packageName,
                    wrappedClassName,
                )
            }

            if (platforms.isIOS()) {
                createIOSMauiClass(
                    mauiClass,
                    packageName,
                    wrappedClassName,
                )
            }

            if (platforms.isCommon()) {
                // TODO generate common module
            }
        } catch (e: Throwable) {
            throw IllegalArgumentException(
                "Error processing maui binding class ${mauiClass.wrappedClassDeclaration.simpleName.asString()} at ${mauiClass.wrappedClassDeclaration.location}",
                e,
            )
        }
    }

    private fun wrapConstructor(
        wrappedConstructorParameters: List<KSValueParameter>,
        wrappedClassDeclaration: KSClassDeclaration,
        toTypeName: (KSType) -> TypeName,
    ): Pair<List<ParameterSpec>, CodeBlock> {
        val constructorInvocationArguments =
            wrappedConstructorParameters
                .map { constructorParameter ->
                    CodeBlock.of("%N", constructorParameter.name?.asString())
                    // todo the invocation depends on how the parameters are unwrapped
                }.joinToCode()
        val wrappedClassType = wrappedClassDeclaration.asType(emptyList()).toTypeName()
        val constructorInvocation =
            CodeBlock.of("%T(%L)", wrappedClassType, constructorInvocationArguments)

        val constructorParameters = wrappedConstructorParameters.map { it.toParameterSpec(toTypeName) }
        return constructorParameters to constructorInvocation
    }

    private fun createAndroidMauiClass(
        mauiClass: MauiClass,
        packageName: String,
        wrappedClassName: String,
    ) {
        val className = wrappedClassName.androidModuleClassName()
        val wrappedClassVarName = "wrappedClass"

        val functionSpecs =
            mauiClass.mauiMethods.map { functionDeclaration ->
                androidMauiFunctionSpec(
                    functionDeclaration,
                    wrappedClassVarName,
                )
            }

        val classSpec =
            TypeSpec
                .classBuilder(className)
                .apply {
                    if (mauiClass.isInternal) {
                        addModifiers(KModifier.INTERNAL)
                    }
                    primaryConstructor(
                        FunSpec
                            .constructorBuilder()
                            .addModifiers(KModifier.INTERNAL)
                            .addParameter(
                                ParameterSpec
                                    .builder(wrappedClassVarName, ClassName(packageName, wrappedClassName))
                                    .build(),
                            ).build(),
                    )

                    addProperty(
                        PropertySpec
                            .builder(wrappedClassVarName, ClassName(packageName, wrappedClassName))
                            .addModifiers(KModifier.PRIVATE)
                            .initializer(wrappedClassVarName)
                            .build(),
                    )

                    addFunctions(
                        mauiClass.mauiConstructors.map {
                            val (parameters, constructorInvocation) =
                                wrapConstructor(
                                    it.parameters,
                                    mauiClass.wrappedClassDeclaration,
                                    toTypeName = ::getKotlinMauiAndroidTypeName,
                                )
                            FunSpec
                                .constructorBuilder()
                                .addParameters(parameters)
                                .callThisConstructor(constructorInvocation)
                                .build()
                        },
                    )

                    addFunctions(functionSpecs)
                    val containingFile = mauiClass.wrappedClassDeclaration.containingFile
                    if (containingFile != null) {
                        addOriginatingKSFile(containingFile)
                    }
                }.build()

        val fileSpec =
            FileSpec
                .builder(packageName, className)
                .addType(classSpec)
                .build()

        fileSpec.writeTo(codeGenerator, false)
    }

    private fun createIOSMauiClass(
        mauiClass: MauiClass,
        packageName: String,
        wrappedClassName: String,
    ) {
        val className = wrappedClassName.iOSModuleClassName()
        val wrappedClassVarName = "wrappedClass"

        val classSpec =
            TypeSpec
                .classBuilder(className)
                .apply {
                    if (mauiClass.isInternal) {
                        addModifiers(KModifier.INTERNAL)
                    }

                    primaryConstructor(
                        FunSpec
                            .constructorBuilder()
                            .addModifiers(KModifier.INTERNAL)
                            .addParameter(
                                ParameterSpec
                                    .builder(wrappedClassVarName, ClassName(packageName, wrappedClassName))
                                    .build(),
                            ).build(),
                    )
                    superclass(ClassName("platform.darwin", "NSObject"))
                    addProperty(
                        PropertySpec
                            .builder(wrappedClassVarName, ClassName(packageName, wrappedClassName))
                            .addModifiers(KModifier.PRIVATE)
                            .initializer(wrappedClassVarName)
                            .build(),
                    )

                    val constructors =
                        mauiClass.mauiConstructors.map {
                            val (parameters, constructorInvocation) =
                                wrapConstructor(
                                    it.parameters,
                                    mauiClass.wrappedClassDeclaration,
                                    toTypeName = ::getKotlinMauiIOSTypeName,
                                )
                            FunSpec
                                .constructorBuilder()
                                .addParameters(parameters)
                                .callThisConstructor(constructorInvocation)
                                .build()
                        }
                    addFunctions(constructors)

                    val functionSpecs =
                        mauiClass.mauiMethods.map { functionDeclaration ->
                            iosMauiFunctionSpec(
                                functionDeclaration,
                                wrappedClassVarName,
                            )
                        }
                    addFunctions(functionSpecs)
                    val containingFile = mauiClass.wrappedClassDeclaration.containingFile
                    if (containingFile != null) {
                        addOriginatingKSFile(containingFile)
                    }
                }.build()

        val fileSpec =
            FileSpec
                .builder(packageName, className)
                .addType(classSpec)
                .build()

        fileSpec.writeTo(codeGenerator, false)
    }

    private fun androidMauiFunctionSpec(
        functionDeclaration: KSFunctionDeclaration,
        wrappedClassVarName: String,
    ): FunSpec {
        val parameters = functionDeclaration.parameters.map { it.toParameterSpec(::getKotlinMauiAndroidTypeName) }

        return FunSpec
            .builder(functionDeclaration.simpleName.asString())
            .apply {
                addParameters(parameters)
                returns(getKotlinMauiAndroidTypeName(functionDeclaration.returnType!!.resolve()))
                // TODO convert return value
                addCode(
                    buildCodeBlock {
                        add(
                            "return %N.%N(%L)",
                            wrappedClassVarName,
                            functionDeclaration.simpleName.asString(),
                            parameters.map(::transformMauiAndroidValueToKotlinValue).joinToCode(),
                        )
                    },
                )
            }.build()
    }

    private fun iosMauiFunctionSpec(
        functionDeclaration: KSFunctionDeclaration,
        wrappedClassVarName: String,
    ): FunSpec {
        val parameters = functionDeclaration.parameters.map { it.toParameterSpec(::getKotlinMauiIOSTypeName) }

        return FunSpec
            .builder(functionDeclaration.simpleName.asString())
            .apply {
                addParameters(parameters)
                returns(getKotlinMauiIOSTypeName(functionDeclaration.returnType!!.resolve()))
                addCode(
                    buildCodeBlock {
                        add(
                            "return %N.%N(%L)",
                            wrappedClassVarName,
                            functionDeclaration.simpleName.asString(),
                            parameters
                                .map(::transformMauiIOSValueToKotlinValue)
                                .joinToCode(),
                        )
                    },
                )
            }.build()
    }

    private fun transformMauiAndroidValueToKotlinValue(parameter: ParameterSpec): CodeBlock {
        val isNullable = parameter.type.isNullable
        return when (val type = parameter.type) {
            is ClassName ->
                when (type.canonicalName) {
                    STRING.canonicalName, BOOLEAN.canonicalName, INT.canonicalName, BYTE.canonicalName,
                    SHORT.canonicalName, CHAR.canonicalName, DOUBLE.canonicalName,
                    FLOAT.canonicalName, LONG.canonicalName, NUMBER.canonicalName,
                    ->
                        CodeBlock.of(
                            "%N",
                            parameter.name,
                        )

                    else -> error("unsupported type $type")
                }

            is ParameterizedTypeName ->
                when (type.rawType) {
                    List::class.asTypeName() ->
                        CodeBlock.of(
                            if (isNullable) "%N?.toArrayList()?.%M<%T>()" else "%N.toArrayList().%M<%T>()",
                            parameter.name,
                            MemberName("kotlin.collections", "filterIsInstance"),
                            type.typeArguments.single(),
                        )

                    Map::class.asTypeName() ->
                        CodeBlock.of(
                            if (isNullable) "(%N?.toHashMap() as %T)" else "(%N.toHashMap() as %T)",
                            parameter.name,
                            type,
                        )

                    else -> error("unsupported type $type")
                }

            else -> error("unsupported type $type")
        }
    }

    private fun getKotlinMauiAndroidTypeName(type: KSType): TypeName {
        fun resolveTypeArgument(index: Int): TypeName {
            val argument = type.arguments[index]
            val type = argument.type
            return if (type != null) {
                getKotlinMauiAndroidTypeName(
                    type.resolve(),
                )
            } else {
                error("Could not resolve type argument: $argument at ${argument.location}")
            }
        }

        return when (type.declaration.qualifiedName?.asString()) {
            "kotlin.Any" -> ANY
            "kotlin.Boolean" -> BOOLEAN
            "kotlin.Byte" -> BYTE
            "kotlin.Char" -> CHAR
            "kotlin.Double" -> DOUBLE
            "kotlin.Float" -> FLOAT
            "kotlin.Int" -> INT
            "kotlin.Long" -> LONG
            "kotlin.Number" -> NUMBER
            "kotlin.Short" -> SHORT
            "kotlin.String" -> STRING
            "kotlin.Unit" -> UNIT
            else -> null
        } ?: when (type.declaration.qualifiedName?.asString()) {
            "kotlin.Array", "kotlin.collections.List", "kotlin.collections.Set" ->
                LIST.parameterizedBy(
                    resolveTypeArgument(0),
                )

            "kotlin.collections.Map" ->
                MAP.parameterizedBy(
                    resolveTypeArgument(0),
                    resolveTypeArgument(1),
                )

            else -> error("unsupported type $type")
        }.copy(nullable = type.isMarkedNullable)
    }

    /**
     * Transforms a Maui iOS type to a Kotlin type.
     */
    private fun transformMauiIOSValueToKotlinValue(parameter: ParameterSpec): CodeBlock {
        val isNullable = parameter.type.isNullable
        return when (val type = parameter.type) {
            is ClassName -> {
                when (type.canonicalName) {
                    INT.canonicalName ->
                        CodeBlock.of(
                            if (isNullable) "%N?.toInt()" else "%N.toInt()",
                            parameter.name,
                        )

                    LONG.canonicalName ->
                        CodeBlock.of(
                            if (isNullable) "%N?.toLong()" else "%N.toLong()",
                            parameter.name,
                        )

                    FLOAT.canonicalName ->
                        CodeBlock.of(
                            if (isNullable) "%N?.toFloat()" else "%N.toFloat()",
                            parameter.name,
                        )

                    else -> CodeBlock.of("%N", parameter.name)
                }
            }

            else -> CodeBlock.of("%N", parameter.name)
        }
    }

    private fun getKotlinMauiIOSTypeName(type: KSType): TypeName {
        fun resolveTypeArgument(index: Int): TypeName {
            val argument = type.arguments[index]
            val type = argument.type
            if (type != null) {
                return getKotlinMauiIOSTypeName(
                    type.resolve(),
                )
            } else {
                error("Could not resolve type argument")
            }
        }

        return when (type.declaration.qualifiedName?.asString()) {
            "kotlin.Any" -> ANY
            "kotlin.Boolean" -> BOOLEAN
            "kotlin.Byte" -> BYTE
            "kotlin.Char" -> CHAR
            "kotlin.Double" -> DOUBLE
            "kotlin.Float" -> FLOAT
            "kotlin.Int" -> INT
            "kotlin.Long" -> LONG
            "kotlin.Number" -> NUMBER
            "kotlin.Short" -> SHORT
            "kotlin.String" -> STRING
            "kotlin.Unit" -> UNIT
            else -> null
        } ?: when (type.declaration.qualifiedName?.asString()) {
            "kotlin.Array", "kotlin.collections.List", "kotlin.collections.Set" ->
                LIST.parameterizedBy(
                    resolveTypeArgument(0),
                )

            "kotlin.collections.Map" ->
                MAP.parameterizedBy(
                    resolveTypeArgument(0),
                    resolveTypeArgument(1),
                )

            else -> error("unsupported type $type")
        }.copy(nullable = type.isMarkedNullable)
    }
}

internal fun KSValueParameter.toParameterSpec(toTypeName: (KSType) -> TypeName): ParameterSpec =
    ParameterSpec
        .builder(
            this.name?.asString() ?: error("Parameter must have a name"),
            try {
                toTypeName(this.type.resolve())
            } catch (e: Throwable) {
                throw IllegalArgumentException("Could get type of $this at ${this.location}", e)
            },
        ).build()

fun List<String>.cSharpAttributesToString(): String = if (isEmpty()) "" else "[${joinToString(", ")}]"

private fun listOfCode(code: List<CodeBlock>) = CodeBlock.of("%M(%L)", ListOfMember, code.joinToCode())

internal val toolkitPackageName = "de.voize.mauikmp"
internal val toolkitUtilPackageName = "$toolkitPackageName.util"

internal val csharpSystemNamespace = "System"
internal val csharpFoundationNamespace = "Foundation"
internal val csharpObjCRuntimeNamespace = "ObjCRuntime"

internal val INativeObjectClassName =
    CSharp.ClassName(
        simpleName = "INativeObject",
        namespace = csharpObjCRuntimeNamespace,
        isNullable = false,
        attributes = emptyList(),
    )
internal val NativeHandleClassName =
    CSharp.ClassName(
        simpleName = "NativeHandle",
        namespace = csharpObjCRuntimeNamespace,
        isNullable = false,
        attributes = emptyList(),
    )
internal val NSNumberClassName =
    CSharp.ClassName(
        simpleName = "NSNumber",
        namespace = csharpFoundationNamespace,
        isNullable = false,
        attributes = emptyList(),
    )
internal val NSStringClassName =
    CSharp.ClassName(
        simpleName = "NSString",
        namespace = csharpFoundationNamespace,
        isNullable = false,
        attributes = emptyList(),
    )
internal val NSArrayClassName =
    CSharp.ClassName(
        simpleName = "NSArray",
        namespace = csharpFoundationNamespace,
        isNullable = false,
        attributes = emptyList(),
    )

// this is a special placeholder class for array types in C# which written []
internal val ArrayClassName =
    CSharp.ClassName(
        simpleName = "Array",
        namespace = csharpSystemNamespace,
        isNullable = false,
        attributes = emptyList(),
    )

internal val FuncClassName =
    CSharp.ClassName(
        simpleName = "Func",
        namespace = csharpSystemNamespace,
        isNullable = false,
        attributes = emptyList(),
    )

internal val ActionClassName =
    CSharp.ClassName(
        simpleName = "Action",
        namespace = csharpSystemNamespace,
        isNullable = false,
        attributes = emptyList(),
    )

private fun primitiveTypeName(name: String) =
    CSharp.ClassName(
        simpleName = name,
        namespace = "",
        isNullable = false,
        attributes = emptyList(),
    )

internal val BoolTypeName = primitiveTypeName("bool")
internal val StringTypeName = primitiveTypeName("string")
internal val IntTypeName = primitiveTypeName("int")
internal val DoubleTypeName = primitiveTypeName("double")
internal val FloatTypeName = primitiveTypeName("float")
internal val LongTypeName = primitiveTypeName("long")
internal val ShortTypeName = primitiveTypeName("short")
internal val ByteTypeName = primitiveTypeName("byte")
internal val CharTypeName = primitiveTypeName("char")
internal val VoidTypeName = primitiveTypeName("void")

private val CoroutineScopeClassName = ClassName("kotlinx.coroutines", "CoroutineScope")

private val ListOfMember = MemberName("kotlin.collections", "listOf")
private val JsonClassName = ClassName("kotlinx.serialization.json", "Json")
private val EncodeToStringMember = MemberName("kotlinx.serialization", "encodeToString")
private val DecodeFromStringMember = MemberName("kotlinx.serialization", "decodeFromString")

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
import com.google.devtools.ksp.symbol.Origin
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
import de.voize.mauikmp.ksp.processor.csharp.toCSharpMemberName

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

    // Whether to emit a standalone `SharedKotlinx_datetimeInstant` binding. True only when
    // kotlinx.datetime.Instant is a distinct class (kotlinx-datetime < 0.7.0, or the 0.7.x
    // `-0.6.x-compat` artifact) — NOT the deprecated `typealias Instant = kotlin.time.Instant` (plain
    // >= 0.7.0, which K/N collapses onto SharedKotlinInstant) — AND it is actually reachable from the
    // processed @MauiBinding surface. Reachability matters because K/N exports ObjC classes by
    // reachability, not classpath presence: a consumer that migrated to kotlin.time.Instant but still has
    // kotlinx.datetime on the classpath (compat artifact) would otherwise get an orphan binding whose
    // missing ObjC class breaks linking. Computed in process(); drives both the stub emission
    // and the type-name mapping so they never disagree.
    private var emitKotlinxDatetimeInstantBinding = false

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

        // Detect whether kotlinx.datetime.Instant is its own class (kotlinx-datetime < 0.7.0, or the
        // 0.7.x `-0.6.x-compat` artifact) or a deprecated typealias of kotlin.time.Instant (plain >= 0.7.0).
        // Comparing the resolved declaration's qualified name is robust to both KSP behaviours: when it is a
        // typealias, getClassDeclarationByName either returns null or resolves through to kotlin.time.Instant
        // — neither matches the target name. Whether to actually emit a separate binding also depends on
        // reachability, folded in after the namespace tree is built (see emitKotlinxDatetimeInstantBinding).
        val instantIsDistinctClass =
            resolver
                .getClassDeclarationByName("kotlinx.datetime.Instant")
                ?.qualifiedName
                ?.asString() == "kotlinx.datetime.Instant"

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

        functionSymbols.forEach { functionSymbol ->
            require(Modifier.SUSPEND !in functionSymbol.modifiers) {
                "MauiBinding functions must not be suspend functions: $functionSymbol at ${functionSymbol.location}"
            }
        }

        val (topLevelFunctions, classFunctions) = functionSymbols.partition { it.parentDeclaration == null }
        val (topLevelProperties, classProperties) = propertySymbols.partition { it.parentDeclaration == null }

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

        val buildResult = MauiNamespaceTree.build(validSymbols, wellKnownTypes)
        val rootNamespace = buildResult.rootNamespace
        val originatingKSFiles = buildResult.originatingFiles

        // Only emit the standalone kotlinx.datetime.Instant binding when it is a distinct class AND the
        // processed @MauiBinding API actually references it — i.e. exactly when Kotlin/Native will export
        // its ObjC class. With the `-0.6.x-compat` artifact the class is on the classpath but a consumer
        // that migrated to kotlin.time.Instant never references it, so emitting it would produce an orphan
        // SharedKotlinx_datetimeInstant whose missing _OBJC_CLASS_$ symbol breaks linking.
        emitKotlinxDatetimeInstantBinding =
            instantIsDistinctClass &&
            "kotlinx.datetime.Instant" in buildResult.reachableQualifiedNames

        // generateKotlin(rootNamespace, mauiBindingAnnotationType)

        if (
            invalidSymbols.isEmpty() &&
            !invoked &&
            platforms.isIOS()
        ) {
            enforceSyncBindingErrorContract(classSymbols, topLevelFunctions, wellKnownTypes)
            generateIosAppDefinition(rootNamespace, originatingKSFiles, wellKnownTypes)
            throwsWrapperGenerator.generate(rootNamespace, originatingKSFiles, wellKnownTypes)

            invoked = true
        }

        return ProcessResult(
            deferredSymbols = invalidSymbols,
        )
    }

    /**
     * Enforces the synchronous error contract on the iOS pass:
     * 1. Every annotated `@Throws` must list at least one exception class (an empty `@Throws()`
     *    does not make Kotlin/Native bridge anything) and should not list `CancellationException`
     *    (cancellation must be rethrown, not turned into an `NSError`). Always checked.
     * 2. If [requireThrowsOnSyncBindings] is on, every synchronous `@MauiBinding`
     *    function/constructor must be annotated `@Throws`, so a thrown exception reaches the C#
     *    host as a catchable `NSError` instead of terminating the process. Adapter classes and
     *    functions returning an adapter type (see [asyncAdapterTypeNames]) are exempt, as are data
     *    classes and enums (pure data carriers, their members do not run throwing user logic).
     * 3. A member annotated `@MauiBinding(canThrow = false)` opts out of (2) per-member — the author
     *    asserts it never throws. Combining `canThrow = false` with `@Throws` is contradictory and
     *    reported as an error.
     */
    private fun enforceSyncBindingErrorContract(
        classSymbols: List<KSClassDeclaration>,
        topLevelFunctions: List<KSFunctionDeclaration>,
        wellKnownTypes: WellKnownTypes,
    ) {
        fun returnsAsyncAdapter(function: KSFunctionDeclaration): Boolean {
            if (function.isConstructor()) return false
            val returnTypeName =
                function.returnType
                    ?.resolve()
                    ?.declaration
                    ?.qualifiedName
                    ?.asString()
            return returnTypeName in asyncAdapterTypeNames
        }

        fun check(
            function: KSFunctionDeclaration,
            owner: String,
        ) {
            val throwsClasses = function.throwsExceptionClassNames()
            val kind = if (function.isConstructor()) "constructor" else "function"

            // canThrow = false is the author asserting the member never throws: skip the @Throws
            // requirement entirely. Pairing it with @Throws is contradictory, so flag that.
            if (!function.mauiBindingCanThrow(wellKnownTypes)) {
                if (throwsClasses != null) {
                    logger.error(
                        "MauiBinding $kind '$owner' declares @MauiBinding(canThrow = false) but is " +
                            "also annotated @Throws; these contradict each other. Keep @Throws if it " +
                            "can throw (errors become catchable NSErrors), or canThrow = false if it " +
                            "never throws — not both.",
                        function,
                    )
                }
                return
            }

            if (throwsClasses != null) {
                if (throwsClasses.isEmpty()) {
                    logger.error(
                        "@Throws on MauiBinding $kind '$owner' must list at least one exception " +
                            "class (use @Throws(Exception::class)); an empty @Throws() does not make " +
                            "Kotlin/Native bridge exceptions to NSError.",
                        function,
                    )
                }
                // Match FQN exactly — a suffix check would fire on user-defined MyCancellationException.
                if (throwsClasses.any { it == "kotlinx.coroutines.CancellationException" || it == "kotlin.coroutines.CancellationException" }) {
                    logger.warn(
                        "@Throws on MauiBinding $kind '$owner' lists CancellationException; " +
                            "cancellation should be rethrown, not bridged to NSError.",
                        function,
                    )
                }
                return
            }

            if (!requireThrowsOnSyncBindings || returnsAsyncAdapter(function)) return

            logger.error(
                "MauiBinding synchronous $kind '$owner' must be annotated @Throws(Exception::class) " +
                    "so a thrown exception reaches the C# host as a catchable NSError instead of " +
                    "terminating the process. If it returns an async adapter (Task/ObservableFlow), " +
                    "declare that type in the KSP option 'maui.kmp.ios.asyncAdapterTypes'; or set " +
                    "'maui.kmp.ios.requireThrowsOnSyncBindings=false' to opt out globally.",
                function,
            )
        }

        classSymbols.forEach { declaration ->
            // canThrow = false on a class has no effect — it is only meaningful on functions/constructors.
            if (!declaration.mauiBindingCanThrow(wellKnownTypes)) {
                logger.warn(
                    "@MauiBinding(canThrow = false) on class '${declaration.simpleName.asString()}' " +
                        "has no effect; place canThrow = false on individual functions or constructors instead.",
                    declaration,
                )
            }
            if (declaration.qualifiedName?.asString() in asyncAdapterTypeNames) return@forEach
            if (Modifier.DATA in declaration.modifiers) return@forEach
            if (declaration.classKind == ClassKind.ENUM_CLASS) return@forEach
            declaration.getMauiFunctionsAndConstructors(wellKnownTypes).forEach { function ->
                val memberName = if (function.isConstructor()) "<init>" else function.simpleName.asString()
                check(function, "${declaration.simpleName.asString()}.$memberName")
            }
        }
        topLevelFunctions.forEach { function ->
            check(function, function.simpleName.asString())
        }
    }

    /**
     * KSP configuration parameters for C# binding generation
     */
    val csharpIOSBindingPrefix = options["maui.kmp.csharp.ios.frameworkPrefix"] 
        ?: error("Missing required KSP option: maui.kmp.csharp.ios.frameworkPrefix")
    private val csharpIOSBindingNamespace = options["maui.kmp.csharp.ios.namespace"]
        ?: error("Missing required KSP option: maui.kmp.csharp.ios.namespace")

    /**
     * When true (default), the iOS pass fails the build if a synchronous `@MauiBinding`
     * function/constructor lacks `@Throws(Exception::class)`. Without it Kotlin/Native does not
     * bridge a thrown exception to an `NSError`, so it terminates the process instead of being
     * catchable in the C# host — the exact P1 this enforcement guards against. Opt out with
     * `maui.kmp.ios.requireThrowsOnSyncBindings=false`.
     */
    private val requireThrowsOnSyncBindings =
        options["maui.kmp.ios.requireThrowsOnSyncBindings"]?.toBooleanStrictOrNull() ?: true

    /**
     * When true, emit a deprecated compatibility constructor for every `@Throws` constructor (in
     * `ThrowsWrappers.cs`) so existing `new SharedX(...)` call sites keep compiling during a
     * migration, with an `[Obsolete]` warning pointing at the factory. Default off: `new SharedX()`
     * becomes a hard compile error instead (the binding interface gets `[DisableDefaultCtor]`
     * regardless, so it is never the old silent half-initialized object). Opt in with
     * `maui.kmp.ios.emitDeprecatedConstructorShims=true`.
     */
    private val emitDeprecatedConstructorShims =
        options["maui.kmp.ios.emitDeprecatedConstructorShims"]?.toBooleanStrictOrNull() ?: false

    /**
     * Name of the generated static class that holds the idiomatic `@Throws` wrappers (and the
     * `CreateX` constructor factories). Defaults to `MauiKmpThrowsWrappers`; a consumer can set its
     * own, e.g. `VoizeSdk`, via `maui.kmp.ios.throwsWrapperClassName`. The name also appears in the
     * deprecated-shim `[Obsolete]` messages so they point at the right factory.
     */
    private val throwsWrapperClassName =
        options["maui.kmp.ios.throwsWrapperClassName"] ?: "MauiKmpThrowsWrappers"

    /**
     * Fully-qualified names of the consumer's async adapter types (e.g. `Task` / `ObservableFlow`).
     * They are exempt from [requireThrowsOnSyncBindings] both as classes (their own
     * `subscribe`/`registerCallbacks`/… marshal errors via callbacks, not by throwing) and as
     * return types (a function returning one defers its work into the adapter). The toolkit cannot
     * know these types, so the consumer declares them via `maui.kmp.ios.asyncAdapterTypes` (comma
     * separated).
     */
    private val asyncAdapterTypeNames: Set<String> =
        options["maui.kmp.ios.asyncAdapterTypes"]
            .orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    /**
     * Emits `ThrowsWrappers.cs` (idiomatic wrappers for @Throws-annotated bindings) as a separate
     * generated file. Lives in its own class to keep this generator focused on `ApiDefinitions.cs`;
     * the [CSharpThrowsWrapperGenerator.Names] implementation delegates back to the private
     * name/type mapping here so both files always agree.
     */
    private val throwsWrapperGenerator by lazy {
        CSharpThrowsWrapperGenerator(
            codeGenerator = codeGenerator,
            bindingNamespace = csharpIOSBindingNamespace,
            wrapperClassName = throwsWrapperClassName,
            emitDeprecatedConstructorShims = emitDeprecatedConstructorShims,
            names =
                object : CSharpThrowsWrapperGenerator.Names {
                    override fun classIdentifier(declaration: KSClassDeclaration): String =
                        declaration.getCSharpObjectCNamespace() + declaration.getCSharpObjectCName()

                    override fun methodName(function: KSFunctionDeclaration): String = function.getCSharpObjectCName()

                    override fun errorParameterIndex(function: KSFunctionDeclaration): Int =
                        this@MauiModuleGenerator.errorParameterIndex(function)

                    override fun typeName(type: KSType): CSharp.TypeName {
                        // Match the types bgen exposes on the compiled binding method (the wrapper
                        // calls it and passes/returns these): nullable primitives surface as their
                        // BindAs target (`int?`), nullable strings as `string?`, collections as
                        // `NSString[]`/`NSDictionary<…>`, etc. — which the default (unwrapped) mapping
                        // reproduces. Type aliases must be expanded: ApiDefinitions.cs resolves them
                        // via top-level `using` alias directives, but this standalone file has none.
                        return type.expandTypeAliases().getCSharpObjectCTypeName()
                    }
                },
        )
    }

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
                interfaceTypeList = listOf(KotlinAnyClassName),
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
                interfaceTypeList = listOf(KotlinAnyClassName),
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
        // Only emit a separate SharedKotlinx_datetimeInstant binding when kotlinx.datetime.Instant is a
        // distinct class AND reachable from the @MauiBinding surface (see emitKotlinxDatetimeInstantBinding).
        // Otherwise a separate interface would be dead code with no class in the binary (link failure /
        // runtime crash): either because it is the deprecated typealias of kotlin.time.Instant (plain
        // >= 0.7.0) or because the compat-artifact class is present but unused. The type renderer mirrors
        // this decision (see getCSharpObjectCTypeName) so references resolve to the right binding either way.
        if (emitKotlinxDatetimeInstantBinding) {
            val kotlinInstantClassName = "${csharpIOSBindingPrefix}Kotlinx_datetimeInstant"
            val kotlinInstantCompanionClassName = "${kotlinInstantClassName}Companion"
            addDeclaration(
                InterfaceDeclarationSpec(
                    attributes = listOf("BaseType (typeof($kotlinBaseClassName))"),
                    identifier = kotlinInstantClassName,
                    interfaceTypeList = listOf(KotlinAnyClassName),
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
        }

        // kotlin.time.Instant (stdlib; also the expansion target of the deprecated kotlinx.datetime.Instant alias)
        val kotlinTimeInstantClassName = "${csharpIOSBindingPrefix}KotlinInstant"
        val kotlinTimeInstantCompanionClassName = "${kotlinTimeInstantClassName}Companion"
        addDeclaration(
            InterfaceDeclarationSpec(
                attributes = listOf("BaseType (typeof($kotlinBaseClassName))"),
                identifier = kotlinTimeInstantClassName,
                interfaceTypeList = listOf(KotlinAnyClassName),
                rawBody =
                    """
                    [Export ("toEpochMilliseconds")]
                    long ToEpochMilliseconds ();

                    [Static]
                    [Export ("companion")]
                    $kotlinTimeInstantCompanionClassName Companion { [Bind ("companion")] get; }
                    """.trimIndent(),
            ),
        )
        addDeclaration(
            InterfaceDeclarationSpec(
                attributes = listOf("BaseType (typeof($kotlinBaseClassName))"),
                identifier = kotlinTimeInstantCompanionClassName,
                interfaceTypeList = emptyList(),
                rawBody =
                    """
                    [Export ("fromEpochMillisecondsEpochMilliseconds:")]
                    $kotlinTimeInstantClassName FromEpochMilliseconds (long epochMilliseconds);

                    [Export ("fromEpochSecondsEpochSeconds:nanosecondAdjustment:")]
                    $kotlinTimeInstantClassName FromEpochSeconds (long epochSeconds, int nanosecondAdjustment);

                    [Export ("fromEpochSecondsEpochSeconds:nanosecondAdjustment_:")]
                    $kotlinTimeInstantClassName FromEpochSeconds (long epochSeconds, long nanosecondAdjustment);

                    [Export ("DISTANT_FUTURE")]
                    $kotlinTimeInstantClassName DISTANT_FUTURE { get; }

                    [Export ("DISTANT_PAST")]
                    $kotlinTimeInstantClassName DISTANT_PAST { get; }
                    """.trimIndent(),
            ),
        )
        // kotlin.time.Clock (stdlib protocol, K/N ObjC name: SharedKotlinClock).
        // The singleton Clock.System is exposed by K/N as `id<SharedKotlinClock>` — an object
        // conforming to the protocol with NO backing/registered ObjC class. A plain
        // `GetNSObject<SharedKotlinClock>` throws InvalidCastException at runtime because the
        // native object's real ObjC class is not registered to the (synthetic-named) model class.
        //
        // Fix: declare Clock as `[BaseType, Protocol, Model]` so the concrete SharedKotlinClock
        // model class exists, and annotate every direct-Clock return value and parameter with
        // `[ForcedType]` (see renderBindingParameterList and the function/return emission).
        // ForcedType makes the runtime instantiate the binding type around the native handle
        // WITHOUT a class-registry match — which is exactly what an unbound protocol conformer
        // needs. (`[Protocolize]` is a no-op in modern .NET-for-iOS bgen and does not help.)
        // LIMITATION: a Clock delivered *through a block parameter* (e.g. `(Clock) -> Unit`) is
        // not force-typed — the block type emission has no place to attach [ForcedType] to its
        // generic argument — so such an inbound Clock would still throw InvalidCastException.
        // No binding exercises that today; revisit the block emission if one ever does.
        val kotlinTimeClockClassName = "${csharpIOSBindingPrefix}KotlinClock"
        addDeclaration(
            InterfaceDeclarationSpec(
                attributes = listOf("BaseType (typeof($kotlinBaseClassName))", "Protocol", "Model"),
                identifier = kotlinTimeClockClassName,
                interfaceTypeList = emptyList(),
                rawBody =
                    """
                    [Export ("now")]
                    $kotlinTimeInstantClassName Now ();
                    """.trimIndent(),
            ),
        )
        val kotlinLocalTimeClassName = "${csharpIOSBindingPrefix}Kotlinx_datetimeLocalTime"
        val kotlinLocalTimeCompanionClassName = "${kotlinLocalTimeClassName}Companion"
        addDeclaration(
            InterfaceDeclarationSpec(
                attributes = listOf("BaseType (typeof($kotlinBaseClassName))"),
                identifier = kotlinLocalTimeClassName,
                interfaceTypeList = listOf(KotlinAnyClassName),
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
                interfaceTypeList = listOf(KotlinAnyClassName),
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

    private fun generateIosAppDefinition(
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
                        generateIosAppDefinition(namespace, wellKnownTypes)
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

                            val (constructors, functions) =
                                declaration
                                    .getMauiFunctionsAndConstructors(wellKnownTypes)
                                    .partition { it.isConstructor() }

                            // bgen auto-emits a parameterless `new X()` (calling the plain ObjC
                            // `init`) for any [BaseType] that lacks [DisableDefaultCtor]. That
                            // default ctor must be suppressed in two cases:
                            //   1. a @Throws constructor is present — K/N drops the plain `init`
                            //      selector (only `initAndReturnError:` remains), so the default
                            //      `new X()` is broken AND it would bypass the throwing ctor's
                            //      validation. The no-arg `canThrow = false` ctor (if any) is exposed
                            //      as a static `New()` factory instead. (SDK-104 behavior.)
                            //   2. every exposed constructor takes required parameters (e.g. a data
                            //      class `Test(name, list, …)`) — K/N has no no-arg `init`, so
                            //      `new SharedTest()` returns a half-initialized object whose non-null
                            //      Kotlin fields are null at runtime.
                            // Construction must then go through the real parameterized / factory /
                            // throwing constructor. `object` singletons get a static factory, no ctor.
                            val hasThrowingConstructor =
                                constructors.any { it.hasThrowsAnnotation() }
                            val hasUsableNoArgConstructor =
                                constructors.any { it.parameters.isEmpty() && !it.hasThrowsAnnotation() }
                            val disableDefaultCtor =
                                !isObject &&
                                    constructors.isNotEmpty() &&
                                    (hasThrowingConstructor || !hasUsableNoArgConstructor)

                            addDeclaration(
                                InterfaceDeclarationSpec(
                                    attributes =
                                        listOfNotNull(
                                            "BaseType (typeof(${
                                                buildString {
                                                    superClass
                                                        .asStarProjectedType()
                                                        .getCSharpObjectCTypeName()
                                                        .writeTo(this)
                                                }
                                            }))",
                                            "DisableDefaultCtor".takeIf { disableDefaultCtor },
                                        ),
                                    identifier = identifier,
                                    interfaceTypeList = listOf(INativeObjectClassName),
                                    rawBody =
                                        buildString {
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
                                                    val throwing = constructor.hasThrowsAnnotation()
                                                    if (constructor.parameters.isEmpty() && !throwing) {
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
                                                        // A @Throws constructor is exported by K/N with the NSError
                                                        // out-param: `init`/`initWith…:` becomes
                                                        // `initAndReturnError:`/`initWith…:error:`. The `+new` convention
                                                        // (alloc + plain init) has no plain init to delegate to, so a
                                                        // throwing no-arg ctor must also be emitted as a `Constructor`.
                                                        val exportName =
                                                            if (throwing) {
                                                                getThrowingObjectCExportName(constructor)
                                                            } else {
                                                                getObjectCExportName(constructor)
                                                            }
                                                        val attributes =
                                                            listOf("Export (\"$exportName\")") +
                                                                if (isPrimary) {
                                                                    listOf("DesignatedInitializer")
                                                                } else {
                                                                    emptyList()
                                                                }
                                                        append("    ${attributes.cSharpAttributesToString()}\n")
                                                        append("    ")
                                                        NativeHandleClassName.writeTo(this)
                                                        append(" Constructor (")
                                                        append(renderBindingParameterList(constructor, throwing))
                                                        append(");\n")
                                                    }
                                                    append("\n")
                                                }
                                            }

                                            functions.forEach { function ->
                                                val returnType = function.returnType!!.resolve()
                                                val returnTypeName =
                                                    returnType.getCSharpObjectCTypeName(isBindingParameterOrReturnType = true)
                                                // A function annotated @Throws is exported by K/N with an NSError
                                                // out-param appended to the selector (and Unit-returning ones become
                                                // BOOL). Mirror that here only for annotated functions so the binding
                                                // matches the actual ObjC selector; non-annotated stay as before.
                                                val throwing = function.hasThrowsAnnotation()
                                                val isUnit = returnType.declaration.qualifiedName?.asString() == "kotlin.Unit"
                                                val exportName =
                                                    if (throwing) getThrowingObjectCExportName(function) else getObjectCExportName(function)
                                                // kotlin.time.Clock is an unbound ObjC protocol conformer; force the
                                                // runtime to wrap the returned native handle into the model class
                                                // without a class-registry match (see Clock declaration above).
                                                val isForcedReturnType =
                                                    returnType.declaration.qualifiedName?.asString() == "kotlin.time.Clock"
                                                val attributes =
                                                    listOf("Export (\"$exportName\")") +
                                                        if (throwing && isUnit) emptyList() else returnTypeName.attributes
                                                append("    ${attributes.cSharpAttributesToString()}\n")
                                                if (isForcedReturnType) append("    [return: ForcedType]\n")
                                                append("    ")
                                                if (throwing && isUnit) append("bool") else returnTypeName.writeTo(this)
                                                append(" ")
                                                append(function.getCSharpObjectCName())
                                                append("(")
                                                append(renderBindingParameterList(function, throwing))
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
                                                typeName.writeTo(this)
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

    private fun KSFile.toObjectCDummyClassName(): String {
        // replace file extension with first uppercase letter and remove dot
        // replace dots with underscores
        val name =
            fileName
                .substringBeforeLast('.')
                .replaceFirstChar { it.uppercaseChar() }
                .replace('.', '_')
        val extension =
            fileName
                .substringAfterLast('.', "")
                .replaceFirstChar { it.uppercaseChar() }
        return name + extension
    }

    private fun NamespaceSpec.Builder.generateTopLevelFunctions(
        file: KSFile,
        topLevelDeclarations: List<KSDeclaration>,
    ) {
        addDeclaration(
            InterfaceDeclarationSpec(
                attributes = listOf("BaseType (typeof(${KotlinAnyClassName.simpleName}))"),
                identifier = "${csharpIOSBindingPrefix}${file.toObjectCDummyClassName()}",
                interfaceTypeList = emptyList(),
                rawBody =
                    buildString {
                        topLevelDeclarations.forEach { declaration ->
                            when (declaration) {
                                is KSFunctionDeclaration -> {
                                    val returnType = declaration.returnType!!.resolve()
                                    val returnTypeName =
                                        returnType.getCSharpObjectCTypeName(isBindingParameterOrReturnType = true)
                                    // A top-level @Throws function is exported by K/N with an NSError
                                    // out-param (and Unit-returning ones become BOOL) exactly like a
                                    // class member. The previous implementation skipped this entirely,
                                    // so the generated selector lacked the `error:` segment present in
                                    // the binary -> unrecognized-selector crash on first call. Mirror
                                    // the class-member throwing path (see functions.forEach above).
                                    val throwing = declaration.hasThrowsAnnotation()
                                    val isUnit = returnType.declaration.qualifiedName?.asString() == "kotlin.Unit"
                                    val exportName =
                                        if (throwing) {
                                            getThrowingObjectCExportName(declaration)
                                        } else {
                                            getObjectCExportName(declaration)
                                        }
                                    val attributes =
                                        listOf("Static", "Export (\"$exportName\")") +
                                            if (throwing && isUnit) emptyList() else returnTypeName.attributes
                                    append("    ${attributes.cSharpAttributesToString()}\n")
                                    append("    ")
                                    if (throwing && isUnit) append("bool") else returnTypeName.writeTo(this)
                                    append(" ")
                                    append(declaration.getCSharpObjectCName())
                                    append("(")
                                    append(renderBindingParameterList(declaration, throwing))
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
                                    typeName.writeTo(this)
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

    private fun NamespaceSpec.Builder.generateIosAppDefinition(
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
            generateIosAppDefinition(child, wellKnownTypes)
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
                    className.writeTo(this)
                    className.writeNullableTo(this)
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

    // --- @Throws / NSError support  ---
    // A sync Kotlin function that throws kills the process unless it is @Throws-annotated: only
    // then does Kotlin/Native bridge the Exception to an ObjC `NSError` out-param (catchable in C#).
    // K/N also rewrites the selector for those functions, so the three helpers below mirror exactly
    // what K/N emits — otherwise the C# binding wouldn't link to the framework. The matching
    // idiomatic C# wrappers (hiding the out-param) are emitted separately by CSharpThrowsWrapperGenerator.

    /**
     * Expands type aliases to their underlying type. K/N resolves the underlying function type when
     * deciding block-param position, so `typealias OnResult = (String)->Unit` must still be
     * recognised as a function type. Also used in Names.typeName for the same reason.
     */
    private fun KSType.expandTypeAliases(): KSType {
        var resolved = this
        while (resolved.declaration is KSTypeAlias) {
            resolved = (resolved.declaration as KSTypeAlias).type.resolve()
        }
        return resolved
    }

    /**
     * The position of the `NSError` out-parameter in a `@Throws`-annotated function/constructor.
     * Kotlin/Native inserts it immediately before the *trailing run* of function-type (block/closure)
     * parameters — the contiguous block parameters at the END of the list, which ObjC renders as
     * trailing closures — or appends it at the end when the last parameter is not a block.
     *
     * This is subtle: a block parameter that is followed by a non-block parameter is NOT a trailing
     * closure, so the error is appended at the end instead of before it. Compare:
     *   registerCallbacks(onSuccess: block, onError: block)      -> registerCallbacksAndReturnError:onSuccess:onError:
     *   subscribe(onNext: block, …, dispatcher: NON-block)       -> subscribeOnNext:…:dispatcher:error:
     * Both have a block at index 0, but only the first is all-trailing-blocks. Using "first block
     * anywhere" (the previous implementation) put the error before `onNext` in subscribe and produced
     * a selector that does not exist in the binary -> unrecognized-selector crash.
     *
     * Type aliases are expanded so `typealias OnResult = (String)->Unit` is treated as a block.
     */
    private fun errorParameterIndex(function: KSFunctionDeclaration): Int {
        val params = function.parameters
        var index = params.size
        while (index > 0) {
            val type = params[index - 1].type.resolve().expandTypeAliases()
            if (type.isFunctionType || type.isSuspendFunctionType) index-- else break
        }
        return index
    }

    /**
     * Renders the C# binding parameter list for a function/constructor. When [throwing], the
     * `out NSError error` parameter is inserted at [errorParameterIndex] so the C# parameter order
     * matches the ObjC selector Kotlin/Native produces (see [getThrowingObjectCExportName]).
     */
    private fun renderBindingParameterList(
        function: KSFunctionDeclaration,
        throwing: Boolean,
    ): String {
        val rendered =
            function.parameters
                .map { parameter ->
                    buildString {
                        val resolvedType = parameter.type.resolve()
                        // kotlin.time.Clock is an unbound ObjC protocol conformer (see its
                        // declaration above): force the runtime to wrap an inbound native handle
                        // into the model class without a class-registry match. Inert for the
                        // outbound (C# -> native) direction, required if a Clock is ever passed in.
                        if (resolvedType.declaration.qualifiedName?.asString() == "kotlin.time.Clock") {
                            append("[ForcedType] ")
                        }
                        val typeName =
                            resolvedType.getCSharpObjectCTypeName(isBindingParameterOrReturnType = true)
                        typeName.writeAttributesTo(this)
                        typeName.writeTo(this)
                        append(" ")
                        append(parameter.name!!.toCSharpMemberName())
                    }
                }.toMutableList()
        if (throwing) {
            rendered.add(errorParameterIndex(function), "out NSError error")
        }
        return rendered.joinToString(", ")
    }

    /**
     * The ObjC selector Kotlin/Native produces for a function/constructor annotated with `@Throws`.
     * The error out-parameter is placed at [errorParameterIndex]; when it is the first argument the
     * keyword is `AndReturnError` (fused onto the base name), otherwise a plain `error:` segment.
     *
     *   throwError()                        -> throwErrorAndReturnError:
     *   nullableRoundtrip(value)            -> nullableRoundtripValue:error:
     *   withCallback(onResult: block)       -> withCallbackAndReturnError:onResult:
     *   withParameterizedCallback(t, block) -> withParameterizedCallbackT:error:onEach:
     *   init()                              -> initAndReturnError:
     *   init(test)                          -> initWithTest:error:
     */
    private fun getThrowingObjectCExportName(function: KSFunctionDeclaration): String {
        val params = function.parameters
        val errorPos = errorParameterIndex(function)
        val baseName =
            if (function.isConstructor()) {
                // "initWith" only when the first selector segment is a real parameter. When the error
                // is first (errorPos == 0 — e.g. a ctor whose first param is a block), K/N emits
                // "init" + "AndReturnError", not "initWith" (there is no parameter name to "With" to).
                if (params.isEmpty() || errorPos == 0) "init" else "initWith"
            } else {
                function.getObjectCName()
            }

        // argument labels in order, with the error argument inserted at errorPos
        val labels = mutableListOf<String?>() // null marks the error argument
        params.forEachIndexed { index, parameter ->
            if (index == errorPos) labels.add(null)
            labels.add(parameter.name!!.getShortName())
        }
        if (errorPos >= params.size) labels.add(null)

        return buildString {
            labels.forEachIndexed { index, label ->
                if (index == 0) {
                    append(baseName)
                    if (label == null) {
                        append("AndReturnError")
                    } else {
                        append(label.replaceFirstChar { it.uppercaseChar() })
                    }
                } else {
                    append(label ?: "error")
                }
                append(":")
            }
        }
    }

    private fun KSDeclaration.getObjectCName(): String {
        val name = simpleName.asString()
        val lowercaseName = name.lowercase()
        return when {
            lowercaseName == "default" && this !is KSFunctionDeclaration && this !is KSPropertyDeclaration -> name + "_" // reserved keyword in objc only if not a function
            lowercaseName == "description" -> name + "_" // reserved keyword in objc
            lowercaseName.startsWith("init") -> "do" + name.replaceFirstChar { it.uppercaseChar() } // init is reserved in objc for constructors
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

    /**
     * @param isBindingParameterOrReturnType this parameter is used to determine if the type is a binding parameter or return type.
     *          this is important because binding parameters and return types must use wrapped types if nullable
     */
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
                    // Map kotlinx.datetime.Instant to its own binding only when we actually emit that
                    // binding (distinct class AND reachable — see emitKotlinxDatetimeInstantBinding).
                    // Otherwise it collapses onto SharedKotlinInstant: either it is the deprecated typealias
                    // of kotlin.time.Instant (plain >= 0.7.0) that K/N expands to the same ObjC class, or it
                    // is the compat-artifact class that is on the classpath but unused. Mirrors the
                    // stub-emission decision in generateKotlinDefaultTypes so references never dangle.
                    "kotlinx.datetime.Instant" ->
                        if (emitKotlinxDatetimeInstantBinding) {
                            "${csharpIOSBindingPrefix}Kotlinx_datetimeInstant"
                        } else {
                            "${csharpIOSBindingPrefix}KotlinInstant"
                        }
                    "kotlin.time.Instant" -> "${csharpIOSBindingPrefix}KotlinInstant"
                    "kotlin.time.Clock" -> "${csharpIOSBindingPrefix}KotlinClock"
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
                                },
                            typeArguments = listOf(resolveTypeArgument(0)),
                            isNullable = type.isMarkedNullable,
                        )

                    "kotlin.collections.Map" ->
                        CSharp.ParameterizedTypeName(
                            rawType =
                                CSharp.ClassName(
                                    simpleName = "NSDictionary",
                                    namespace = csharpFoundationNamespace,
                                    isNullable = false,
                                ),
                            typeArguments =
                                listOf(
                                    resolveTypeArgument(0),
                                    resolveTypeArgument(1),
                                ),
                            isNullable = type.isMarkedNullable,
                        )

                    else -> null
                } ?: run {
                    val typeArguments =
                        type.arguments.map {
                            it.resolveTypeArgument()
                        }
                    when (val declaration = type.declaration) {
                        is KSTypeAlias -> {
                            // Only user-source typealiases are supported: they are emitted as a local
                            // `using <Alias> = …;` directive (so the namespace is empty) and dropped from
                            // generation otherwise. A library typealias (Origin.KOTLIN_LIB) that is not
                            // special-cased above by qualified name (e.g. kotlinx.datetime.Instant) would
                            // produce a dangling local reference to an alias that is never declared. Fail
                            // loudly here instead of emitting an uncompilable ApiDefinitions.cs.
                            require(declaration.origin == Origin.KOTLIN) {
                                "Unsupported library typealias '${declaration.qualifiedName?.asString()}' at " +
                                    "${declaration.location}: special-case it in getCSharpObjectCTypeName " +
                                    "(by qualified name) or expose its underlying type directly."
                            }
                            CSharp.ParameterizedTypeName(
                                rawType =
                                    CSharp.ClassName(
                                        simpleName = declaration.getCSharpObjectCName(),
                                        namespace = "", // local reference (only type alias in the current source are supported)
                                        isNullable = false,
                                    ),
                                typeArguments = typeArguments,
                                isNullable = type.isMarkedNullable,
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
                                            rawType = ActionClassName,
                                            typeArguments = typeArguments.dropLast(1),
                                            isNullable = type.isMarkedNullable,
                                        )
                                    } else {
                                        CSharp.ParameterizedTypeName(
                                            rawType = FuncClassName,
                                            typeArguments = typeArguments,
                                            isNullable = type.isMarkedNullable,
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

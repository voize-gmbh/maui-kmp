package de.voize.mauikmp.ksp.processor

import com.google.devtools.ksp.isConstructor
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import de.voize.mauikmp.ksp.processor.csharp.CSharp
import de.voize.mauikmp.ksp.processor.csharp.toCSharpMemberName

/**
 * Generates `ThrowsWrappers.cs`, a companion to `ApiDefinitions.cs` with idiomatic C# wrappers
 * for every `@Throws`-annotated `@MauiBinding` function and constructor.
 *
 * For those, Kotlin/Native bridges thrown `Exception`s to an `NSError` out-parameter, so the raw
 * binding signature is `bool Foo(out NSError error)` instead of `void Foo()`. The wrappers hide
 * that transport detail and restore the original call-site shape:
 *
 * - methods become extension methods: `obj.Foo()` throws [Foundation.NSErrorException] on error
 * - constructors become static factories on the wrapper class (`MauiKmpThrowsWrappers` by
 *   default, configurable via `maui.kmp.ios.throwsWrapperClassName`): `<WrapperClass>.CreateFoo(...)`
 *
 * Non-annotated functions keep their unchanged raw binding and get no wrapper.
 *
 * The output is a regular C# source file (not a binding api definition): the Gradle copy task
 * places it next to `ApiDefinitions.cs` and the binding project compiles it via the default
 * `Compile` glob, like the hand-written support files.
 */
internal class CSharpThrowsWrapperGenerator(
    private val codeGenerator: CodeGenerator,
    private val bindingNamespace: String,
    private val wrapperClassName: String,
    private val emitDeprecatedConstructorShims: Boolean,
    private val names: Names,
) {
    /**
     * Name/type resolution delegated to [MauiModuleGenerator] so the wrappers always agree with
     * the identifiers and signatures emitted into `ApiDefinitions.cs`.
     */
    internal interface Names {
        /** C# binding type identifier, e.g. `SharedInteropTest`. */
        fun classIdentifier(declaration: KSClassDeclaration): String

        /** C# method name, e.g. `ThrowError`. */
        fun methodName(function: KSFunctionDeclaration): String

        /**
         * Effective public C# type as exposed by the compiled binding (primitives stay unboxed,
         * nullability expressed via [CSharp.TypeName.isNullable], no binding attributes).
         */
        fun typeName(type: KSType): CSharp.TypeName

        /**
         * Position of the `out NSError error` argument in the binding call — Kotlin/Native places it
         * before the first block parameter, so the wrapper must pass it at the same index.
         */
        fun errorParameterIndex(function: KSFunctionDeclaration): Int
    }

    private data class ClassWrappers(
        val identifier: String,
        val methods: List<KSFunctionDeclaration>,
        val constructors: List<KSFunctionDeclaration>,
    )

    internal fun generate(
        rootNamespace: MauiNamespaceTree.NamespaceNode,
        originatingKSFiles: List<KSFile>,
        wellKnownTypes: MauiModuleGenerator.WellKnownTypes,
    ) {
        val classes = mutableListOf<ClassWrappers>()
        collect(rootNamespace, wellKnownTypes, classes)
        if (classes.isEmpty()) return

        codeGenerator
            .createNewFileByPath(
                kspDependencies(true, originatingKSFiles),
                "${generatedCommonFilePath}ThrowsWrappers",
                extensionName = "cs",
            ).use {
                it.bufferedWriter().use { writer ->
                    writer.write(render(classes))
                }
            }
    }

    private fun collect(
        node: MauiNamespaceTree.NamespaceNode,
        wellKnownTypes: MauiModuleGenerator.WellKnownTypes,
        into: MutableList<ClassWrappers>,
    ) {
        node.declarations
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.classKind == ClassKind.CLASS || it.classKind == ClassKind.OBJECT }
            .forEach { declaration ->
                val (constructors, functions) =
                    declaration
                        .getMauiFunctionsAndConstructors(wellKnownTypes)
                        .partition { it.isConstructor() }
                val throwingMethods = functions.filter { it.hasThrowsAnnotation() }
                val throwingConstructors =
                    if (declaration.classKind == ClassKind.CLASS) {
                        constructors.filter { it.hasThrowsAnnotation() }
                    } else {
                        emptyList()
                    }
                if (throwingMethods.isNotEmpty() || throwingConstructors.isNotEmpty()) {
                    into.add(
                        ClassWrappers(
                            identifier = names.classIdentifier(declaration),
                            methods = throwingMethods,
                            constructors = throwingConstructors,
                        ),
                    )
                }
            }
        node.children.forEach { collect(it, wellKnownTypes, into) }
    }

    private fun render(classes: List<ClassWrappers>): String =
        buildString {
            // Explicit usings + nullable context so the file compiles regardless of the consumer
            // binding project's <ImplicitUsings> / <Nullable> settings (it is compiled as ordinary
            // C#, unlike ApiDefinitions.cs which bgen consumes).
            append("using System;\n")
            append("using Foundation;\n")
            append("\n")
            append("#nullable enable\n")
            append("\n")
            append("namespace $bindingNamespace\n")
            append("{\n")
            append("    /// <summary>\n")
            append("    /// Idiomatic wrappers for the @Throws-annotated Kotlin functions in this binding.\n")
            append("    /// Kotlin/Native bridges their thrown Exceptions to an NSError out-parameter; these\n")
            append("    /// wrappers hide it and throw <see cref=\"NSErrorException\"/> instead, so call sites\n")
            append("    /// keep the original signature: <c>obj.Foo(...)</c> inside a try/catch.\n")
            append("    /// Generated by maui-kmp; do not edit.\n")
            append("    /// </summary>\n")
            append("    public static class $wrapperClassName\n")
            append("    {\n")
            classes.forEach { cls ->
                cls.methods.forEach { renderMethodWrapper(cls.identifier, it) }
                cls.constructors.forEach { renderConstructorFactory(cls.identifier, it) }
            }
            append("    }\n")

            // Optional deprecated compatibility shims: re-add the old `new SharedX(...)` (which
            // [DisableDefaultCtor] otherwise removes) as partial-class constructors that delegate to
            // the throwing initializer. Lets existing call sites keep compiling during a migration,
            // with an [Obsolete] warning pointing at the factory.
            if (emitDeprecatedConstructorShims) {
                classes.forEach { cls ->
                    cls.constructors.forEach { renderConstructorCompatShim(cls.identifier, it) }
                }
            }
            append("}\n")
        }

    private fun StringBuilder.renderMethodWrapper(
        identifier: String,
        function: KSFunctionDeclaration,
    ) {
        val methodName = names.methodName(function)
        val returnType = function.returnType!!.resolve()
        val isUnit = returnType.declaration.qualifiedName?.asString() == "kotlin.Unit"

        append("        /// <summary>\n")
        append("        /// Calls <c>$methodName</c> on <see cref=\"$identifier\"/>; throws\n")
        append("        /// <see cref=\"NSErrorException\"/> if the Kotlin function threw an Exception.\n")
        append("        /// A <c>kotlin.Error</c> still terminates the process (never catchable).\n")
        append("        /// </summary>\n")
        append("        public static ")
        if (isUnit) {
            append("void")
        } else {
            val returnTypeName = names.typeName(returnType)
            returnTypeName.writeTo(this)
            returnTypeName.writeNullableTo(this)
        }
        append(" $methodName(this $identifier self")
        function.parameters.forEach { parameter ->
            append(", ")
            val typeName = names.typeName(parameter.type.resolve())
            typeName.writeTo(this)
            typeName.writeNullableTo(this)
            append(" ")
            append(parameter.name!!.toCSharpMemberName())
        }
        append(")\n")
        append("        {\n")
        append("            ")
        if (!isUnit) append("var result = ")
        val methodArgs = function.parameters.map { it.name!!.toCSharpMemberName() }.toMutableList()
        methodArgs.add(names.errorParameterIndex(function), "out var error")
        append("self.$methodName(${methodArgs.joinToString(", ")});\n")
        append("            if (error != null)\n")
        append("            {\n")
        append("                throw new NSErrorException(error);\n")
        append("            }\n")
        if (!isUnit) append("            return result;\n")
        append("        }\n")
        append("\n")
    }

    private fun StringBuilder.renderConstructorFactory(
        identifier: String,
        constructor: KSFunctionDeclaration,
    ) {
        append("        /// <summary>\n")
        append("        /// Creates a <see cref=\"$identifier\"/>; throws <see cref=\"NSErrorException\"/>\n")
        append("        /// if the Kotlin constructor threw an Exception.\n")
        append("        /// A <c>kotlin.Error</c> still terminates the process (never catchable).\n")
        append("        /// </summary>\n")
        append("        public static $identifier Create$identifier(")
        constructor.parameters.forEachIndexed { index, parameter ->
            if (index > 0) append(", ")
            val typeName = names.typeName(parameter.type.resolve())
            typeName.writeTo(this)
            typeName.writeNullableTo(this)
            append(" ")
            append(parameter.name!!.toCSharpMemberName())
        }
        append(")\n")
        append("        {\n")
        append("            // When the Kotlin constructor throws, the native init returns nil and the binding\n")
        append("            // constructor's InitializeHandle would throw a bare Exception BEFORE the NSError\n")
        append("            // out-parameter is assigned — losing the original error. Disabling ThrowOnInitFailure\n")
        append("            // around the call lets the constructor complete so the NSError survives and can be\n")
        append("            // rethrown as a catchable NSErrorException.\n")
        append("            // NOTE: ThrowOnInitFailure is a process-global static, so this toggle is not\n")
        append("            // thread-safe — a concurrent failing NSObject init on another thread would observe\n")
        append("            // the relaxed setting. Acceptable here: throwing initializers are rare and typically\n")
        append("            // run on the main thread. Revisit with a lock if that assumption stops holding.\n")
        append("            var throwOnInitFailure = ObjCRuntime.Class.ThrowOnInitFailure;\n")
        append("            ObjCRuntime.Class.ThrowOnInitFailure = false;\n")
        append("            $identifier instance;\n")
        append("            NSError? error;\n")
        append("            try\n")
        append("            {\n")
        val ctorArgs = constructor.parameters.map { it.name!!.toCSharpMemberName() }.toMutableList()
        ctorArgs.add(names.errorParameterIndex(constructor), "out error")
        append("                instance = new $identifier(${ctorArgs.joinToString(", ")});\n")
        append("            }\n")
        append("            finally\n")
        append("            {\n")
        append("                ObjCRuntime.Class.ThrowOnInitFailure = throwOnInitFailure;\n")
        append("            }\n")
        append("            if (error != null || instance.Handle == ObjCRuntime.NativeHandle.Zero)\n")
        append("            {\n")
        append("                instance.Dispose();\n")
        append("                throw error != null\n")
        append("                    ? new NSErrorException(error)\n")
        append("                    : new InvalidOperationException(\"$identifier initializer returned nil without an NSError.\");\n")
        append("            }\n")
        append("            return instance;\n")
        append("        }\n")
        append("\n")
    }

    /**
     * Emits a deprecated compatibility constructor as a partial of the binding class, restoring the
     * pre-@Throws `new SharedX(...)` signature. It delegates to the throwing initializer and discards
     * the NSError (`out _`): a non-throwing ctor behaves exactly as before; a throwing one surfaces a
     * bare exception via ThrowOnInitFailure instead of a catchable NSErrorException — which is why
     * the [Obsolete] message points callers at the factory. Only emitted when the consumer opts in.
     */
    private fun StringBuilder.renderConstructorCompatShim(
        identifier: String,
        constructor: KSFunctionDeclaration,
    ) {
        val ctorArgs = constructor.parameters.map { it.name!!.toCSharpMemberName() }.toMutableList()
        ctorArgs.add(names.errorParameterIndex(constructor), "out _")

        append("    public partial class $identifier\n")
        append("    {\n")
        append("        /// <summary>\n")
        append("        /// DEPRECATED. Compatibility constructor delegating to the throwing initializer.\n")
        append("        /// Migrate to <c>$wrapperClassName.Create$identifier(...)</c>, which surfaces\n")
        append("        /// initialization failures as a catchable <see cref=\"NSErrorException\"/>.\n")
        append("        /// </summary>\n")
        append(
            "        [Obsolete(\"Use $wrapperClassName.Create$identifier(...); this constructor " +
                "cannot surface initialization errors and will be removed.\", false)]\n",
        )
        append("        public $identifier(")
        constructor.parameters.forEachIndexed { index, parameter ->
            if (index > 0) append(", ")
            val typeName = names.typeName(parameter.type.resolve())
            typeName.writeTo(this)
            typeName.writeNullableTo(this)
            append(" ")
            append(parameter.name!!.toCSharpMemberName())
        }
        append(") : this(${ctorArgs.joinToString(", ")}) { }\n")
        append("    }\n")
        append("\n")
    }
}

package de.voize.mauikmp.ksp.processor.csharp

import de.voize.mauikmp.ksp.processor.NSArrayClassName
import de.voize.mauikmp.ksp.processor.cSharpAttributesToString

object CSharp {
    sealed interface TypeName : Annotatable {
        fun writeTo(
            out: Appendable,
            withAttributes: Boolean,
        )
    }

    data class ClassName(
        val simpleName: String,
        val namespace: String,
        val isNullable: Boolean,
        override val attributes: List<String> = emptyList(),
    ) : TypeName {
        override fun writeTo(
            out: Appendable,
            withAttributes: Boolean,
        ) {
            if (withAttributes && attributes.isNotEmpty()) {
                out.append(attributes.cSharpAttributesToString())
                out.append(" ")
            }
            if (namespace.isNotEmpty()) {
                out.append(namespace)
                out.append(".")
            }
            out.append(simpleName)
            if (isNullable) {
                out.append("?")
            }
        }
    }

    data class ParameterizedTypeName(
        val rawType: ClassName,
        val typeArguments: List<TypeName>,
    ) : TypeName {
        override fun writeTo(
            out: Appendable,
            withAttributes: Boolean,
        ) {
            if (rawType.simpleName == NSArrayClassName.simpleName) {
                if (withAttributes && attributes.isNotEmpty()) {
                    out.append(attributes.cSharpAttributesToString())
                    out.append(" ")
                }
                typeArguments.single().writeTo(out, withAttributes = false)
                out.append("[]")
            } else {
                rawType.writeTo(out, withAttributes)
                if (typeArguments.isNotEmpty()) {
                    out.append("<")
                    typeArguments.forEachIndexed { index, typeName ->
                        typeName.writeTo(out, withAttributes = false)
                        if (index < typeArguments.size - 1) {
                            out.append(", ")
                        }
                    }
                    out.append(">")
                }
            }
        }

        override val attributes: List<String>
            get() = rawType.attributes
    }
}

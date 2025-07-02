package de.voize.mauikmp.ksp.processor.csharp

import de.voize.mauikmp.ksp.processor.ArrayClassName
import de.voize.mauikmp.ksp.processor.cSharpAttributesToString

object CSharp {
    sealed interface TypeName : Annotatable {
        val isNullable: Boolean

        fun writeTo(out: Appendable)

        fun writeAttributesTo(out: Appendable) {
            if (attributes.isNotEmpty()) {
                out.append(attributes.cSharpAttributesToString())
                out.append(" ")
            }
        }

        fun writeNullableTo(out: Appendable) {
            if (isNullable) {
                out.append("?")
            }
        }
    }

    data class ClassName(
        val simpleName: String,
        val namespace: String,
        override val isNullable: Boolean,
        override val attributes: List<String> = emptyList(),
    ) : TypeName {
        override fun writeTo(out: Appendable) {
            if (namespace.isNotEmpty()) {
                out.append(namespace)
                out.append(".")
            }
            out.append(simpleName)
        }
    }

    data class ParameterizedTypeName(
        val rawType: ClassName,
        val typeArguments: List<TypeName>,
        override val isNullable: Boolean,
    ) : TypeName {
        override fun writeTo(out: Appendable) {
            if (rawType.simpleName == ArrayClassName.simpleName && rawType.namespace == ArrayClassName.namespace) {
                val elementType = typeArguments.single()
                elementType.writeTo(out)
                elementType.writeNullableTo(out)
                out.append("[]")
            } else {
                rawType.writeTo(out)
                if (typeArguments.isNotEmpty()) {
                    out.append("<")
                    typeArguments.forEachIndexed { index, typeName ->
                        typeName.writeTo(out)
                        typeName.writeNullableTo(out)
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

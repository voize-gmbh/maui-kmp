package de.voize.mauikmp.ksp.processor.csharp

import de.voize.mauikmp.ksp.processor.cSharpAttributesToString

data class InterfaceDeclarationSpec(
    val attributes: List<String>,
    val identifier: String,
    val interfaceTypeList: List<CSharp.TypeName>,
    val rawBody: String,
) {
    fun writeTo(out: Appendable) {
        out.append("  ${attributes.cSharpAttributesToString()}\n")
        out.append("  interface $identifier")
        if (interfaceTypeList.isNotEmpty()) {
            out.append(" : ")
            out.append(
                interfaceTypeList.joinToString(", ") {
                    buildString {
                        it.writeTo(this, withAttributes = false)
                    }
                },
            )
        }
        out.append("\n")
        out.append("  {\n")
        out.append("  $rawBody\n")
        out.append("  }\n")
    }
}

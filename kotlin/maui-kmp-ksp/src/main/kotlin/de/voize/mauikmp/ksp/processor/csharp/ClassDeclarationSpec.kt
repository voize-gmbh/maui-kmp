package de.voize.mauikmp.ksp.processor.csharp

import de.voize.mauikmp.ksp.processor.cSharpAttributesToString

data class ClassDeclarationSpec(
    val attributes: List<String>,
    val identifier: String,
    val classType: String?,
    val interfaceTypeList: List<String>,
    val rawBody: String,
) {
    fun writeTo(out: Appendable) {
        out.append("  ${attributes.cSharpAttributesToString()}\n")
        out.append("  class $identifier")
        if (classType != null) {
            out.append(" : $classType")
            if (interfaceTypeList.isNotEmpty()) {
                out.append(", ${interfaceTypeList.joinToString(", ")}")
            }
        } else {
            if (interfaceTypeList.isNotEmpty()) {
                out.append(" : ${interfaceTypeList.joinToString(", ")}")
            }
        }
        out.append("\n")
        out.append("  {\n")
        out.append("  $rawBody\n")
        out.append("  }\n")
    }
}

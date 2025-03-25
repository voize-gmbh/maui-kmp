package de.voize.mauikmp.ksp.processor.csharp

data class NamespaceUsingDirectiveSpec(
    val namespace: String,
) {
    fun writeTo(out: Appendable) {
        out.append("  using $namespace;\n")
    }
}

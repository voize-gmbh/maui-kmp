package de.voize.mauikmp.ksp.processor.csharp

data class AliasUsingDirectiveSpec(
    val identifier: String,
    val namespaceOrTypeName: CSharp.TypeName,
) {
    fun writeTo(out: Appendable) {
        out.append("  using $identifier = ")
        namespaceOrTypeName.writeTo(out)
        namespaceOrTypeName.writeNullableTo(out)
        out.append(";\n")
    }
}

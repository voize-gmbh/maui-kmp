package de.voize.mauikmp.ksp.processor.csharp

data class NamespaceSpec(
    val name: String,
    val children: List<NamespaceSpec>,
    val namespaceUsingDirectives: List<NamespaceUsingDirectiveSpec>,
    val aliasUsingDirectives: List<AliasUsingDirectiveSpec>,
    val classDeclarations: List<ClassDeclarationSpec>,
    val interfaceDeclarations: List<InterfaceDeclarationSpec>,
) {
    companion object {
        fun builder(name: String): Builder = Builder(name)
    }

    class Builder(
        private val name: String,
    ) {
        private val children = mutableListOf<NamespaceSpec>()
        private val namespaceUsingDirectiveSpecs = mutableListOf<NamespaceUsingDirectiveSpec>()
        private val aliasUsingDirectiveSpecs = mutableListOf<AliasUsingDirectiveSpec>()
        private val classDeclarations = mutableListOf<ClassDeclarationSpec>()
        private val interfaceDeclarations = mutableListOf<InterfaceDeclarationSpec>()

        fun addChild(child: NamespaceSpec) {
            children.add(child)
        }

        fun addUsingDirective(usingDirective: NamespaceUsingDirectiveSpec) {
            namespaceUsingDirectiveSpecs.add(usingDirective)
        }

        fun addUsingDirective(usingDirective: AliasUsingDirectiveSpec) {
            aliasUsingDirectiveSpecs.add(usingDirective)
        }

        fun addDeclaration(declaration: ClassDeclarationSpec) {
            classDeclarations.add(declaration)
        }

        fun addDeclaration(declaration: InterfaceDeclarationSpec) {
            interfaceDeclarations.add(declaration)
        }

        fun build(): NamespaceSpec =
            NamespaceSpec(
                name,
                children,
                namespaceUsingDirectiveSpecs,
                aliasUsingDirectiveSpecs,
                classDeclarations,
                interfaceDeclarations,
            )
    }

    fun writeTo(out: Appendable) {
        out.append("namespace $name\n")
        out.append("{\n")
        // Write using directives first
        namespaceUsingDirectives.forEach { it.writeTo(out) }
        aliasUsingDirectives.forEach { it.writeTo(out) }

        classDeclarations.forEach { it.writeTo(out) }
        interfaceDeclarations.forEach { it.writeTo(out) }
        children.forEach { it.writeTo(out) }
        out.append("}\n")
    }
}

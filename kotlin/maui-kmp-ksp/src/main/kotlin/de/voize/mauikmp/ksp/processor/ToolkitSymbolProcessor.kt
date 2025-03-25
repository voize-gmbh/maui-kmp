package de.voize.mauikmp.ksp.processor

import com.google.auto.service.AutoService
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.PlatformInfo
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated

class ToolkitSymbolProcessor(
    codeGenerator: CodeGenerator,
    private val platforms: List<PlatformInfo>,
    options: Map<String, String>,
    logger: KSPLogger,
) : SymbolProcessor {
    private val mauiModuleGenerator =
        MauiModuleGenerator(
            codeGenerator,
            platforms,
            options,
            logger,
        )

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val rnModulesProcessResult = mauiModuleGenerator.process(resolver)

        // TODO implement C# generator

        return rnModulesProcessResult.deferredSymbols
    }
}

@AutoService(SymbolProcessorProvider::class)
class ToolkitSymbolProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        ToolkitSymbolProcessor(
            environment.codeGenerator,
            environment.platforms,
            environment.options,
            environment.logger,
        )
}

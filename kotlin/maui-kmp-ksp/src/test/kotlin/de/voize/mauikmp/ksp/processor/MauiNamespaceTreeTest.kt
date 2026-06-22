package de.voize.mauikmp.ksp.processor

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSName
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.Origin
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [MauiNamespaceTree.filterTypesForGeneration].
 *
 * The iOS C# generation (`ApiDefinitions.cs`) only runs for the Kotlin/Native platform, which
 * kotlin-compile-testing cannot drive on the JVM. So instead of asserting on generated output we
 * test the filtering decision directly with mocked KSP symbols — this is the seam the SDK-113 fix
 * changed, and it is platform-independent.
 */
class MauiNamespaceTreeTest {
    private inline fun <reified T : KSDeclaration> mockDeclaration(
        fqn: String,
        declOrigin: Origin,
    ): T {
        val ksName = mockk<KSName> { every { asString() } returns fqn }
        return mockk<T> {
            every { qualifiedName } returns ksName
            every { origin } returns declOrigin
        }
    }

    /**
     * A library `typealias` (e.g. the deprecated `kotlinx.datetime.Instant = kotlin.time.Instant`,
     * which has [Origin.KOTLIN_LIB]) must be dropped. Its underlying type is collected elsewhere and
     * mapped by `getCSharpObjectCTypeName`, so generating anything for the alias is dead and misleading.
     *
     * Reverting the `is KSTypeAlias -> declaration.origin == Origin.KOTLIN` line (back to the previous
     * `else -> true`) makes this assertion fail.
     */
    @Test
    fun `library typealias is dropped`() {
        val libraryAlias = mockDeclaration<KSTypeAlias>("kotlinx.datetime.Instant", Origin.KOTLIN_LIB)

        val result = MauiNamespaceTree.filterTypesForGeneration(setOf(libraryAlias))

        assertFalse("library typealias must not be generated", libraryAlias in result)
    }

    /**
     * A typealias declared in the user's own source ([Origin.KOTLIN]) is still kept — it is emitted as a
     * local C# `using <Alias> = <Binding>;` directive and referenced by that alias name in signatures.
     */
    @Test
    fun `user-source typealias is kept`() {
        val userAlias = mockDeclaration<KSTypeAlias>("com.example.TestTypeAlias", Origin.KOTLIN)
        val userClass = mockDeclaration<KSClassDeclaration>("com.example.Foo", Origin.KOTLIN)

        val result = MauiNamespaceTree.filterTypesForGeneration(setOf(userAlias, userClass))

        assertTrue("user-source typealias must be kept", userAlias in result)
        assertTrue("user-source class must be kept", userClass in result)
    }
}

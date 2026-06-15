package com.mauikmpexample.kotlin.shared

import de.voize.mauikmp.annotation.MauiBinding
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonClassDiscriminator

@MauiBinding
class E2ETest  @MauiBinding @Throws(Exception::class) constructor(private val test: Test)  {
    
    init {
        println(test)
    }

    @MauiBinding
    val string: String = "Hello World"

    @MauiBinding
    val nullableString: String? = null

    @MauiBinding
    @Throws(Exception::class)
    fun testDefaultTypes(
        string: String,
        int: Int,
        long: Long,
        float: Float,
        double: Double,
        boolean: Boolean,
        byte: Byte,
        char: Char,
        short: Short,
    ): String {
        return "Hello World"
    }

    @MauiBinding
    @Throws(Exception::class)
    fun testDefaultTypesNullable(
        string: String?,
        int: Int?,
        long: Long?,
        float: Float?,
        double: Double?,
        boolean: Boolean?,
        byte: Byte?,
        // NOTE: `Char?` is intentionally omitted — the maui-kmp KSP binds it as
        // `System.Nullable<char>`, which the .NET registrar cannot bridge to NSNumber and crashes
        // the app at startup. Tracked as a follow-up toolkit bug (surfaced by this example app).
        short: Short?,
    ): String? {
        return null
    }

    @MauiBinding
    @Throws(Exception::class)
    fun testListAndMap(
        list: List<String>,
        map: Map<String, String>,
        nestedList: List<List<String>>,
        nestedMap: Map<String, Map<String, String>>,
        nestedListInMap: Map<String, List<String>>,
        nestedMapInList: List<Map<String, String>>,
        complexList: List<Test>,
        complexMap: Map<String, Test>,
    ): List<Int> {
        return emptyList()
    }

    @MauiBinding
    @Throws(Exception::class)
    fun testListAndMapNullable(
        list: List<String>?,
        map: Map<String, String>?,
        nestedList: List<List<String>>?,
        nestedMap: Map<String, Map<String, String>>?,
        complexList: List<Test>?,
        complexMap: Map<String, Test>?,
        listNullable: List<String?>,
        mapNullable: Map<String, String?>,
        nestedListNullable: List<List<String?>?>,
        nestedMapNullable: Map<String, Map<String, String?>?>,
        complexListNullable: List<Test?>,
        complexMapNullable: Map<String, Test?>,
    ): List<Int?> {
        return emptyList()
    }

    @MauiBinding
    @Throws(Exception::class)
    fun example(input: TestSealedType, testEnum: Enum?): Test {
        return Test("Erik", listOf(), mapOf(), 30)
    }

    @MauiBinding
    @Throws(Exception::class)
    fun testSealedClassProperties(test: TestSealedClassProperties): TestSealedClassProperties {
        return test
    }


    @MauiBinding
    @Throws(Exception::class)
    fun testKotlinDateTime(
        duration: Duration,
        durationOrNull: Duration?,
        instant: Instant,
        localDateTime: LocalDateTime,
        test: DateTimeTest,
        instantOrNull: Instant?
    ): Duration {
        return 5.seconds
    }

     @MauiBinding
     @Throws(Exception::class)
    fun testKotlinDateTimeList(
        duration: List<Duration>,
        instant: List<Instant>,
        localDateTime: List<LocalDateTime>,
        test: List<DateTimeTest>,
        instantOrNull: List<Instant?>
    ): List<Duration> {
        return listOf(5.seconds)
    }

    @MauiBinding
    @Throws(Exception::class)
    fun getDateTimeTest(): DateTimeTest {
        error("Not implemented")
    }

    @MauiBinding
    @Throws(Exception::class)
    fun testTypeAlias(test: TestTypeAlias): TestTypeAlias {
        return test
    }

    @MauiBinding
    @Throws(Exception::class)
    fun testSealedSubtype(test: TestSealedType.Option1): TestSealedType.Option1 {
        return test
    }

    @MauiBinding
    @Throws(Exception::class)
    fun testSealedCustomDiscriminator(test: TestSealedTypeWithCustomDiscriminator) {

    }

    @MauiBinding
    @Throws(Exception::class)
    fun testMapWithEnumKey(map: Map<Enum, String>): Map<Enum, String> {
        return map
    }

    /**
     * End-to-end test for the `kotlin.time.Instant` → `SharedKotlinInstant` binding mapping.
     *
     * `kotlin.time.Instant` (stdlib, stable since Kotlin 2.2.0) is distinct from
     * `kotlinx.datetime.Instant` (external library). The KSP maps it to a separate C# type
     * (`${prefix}KotlinInstant`). A roundtrip through the boundary verifies: the selector
     * matches the K/N header, the C# type resolves correctly, and the epoch-millis value survives.
     */
    @MauiBinding
    @Throws(Exception::class)
    fun testKotlinTimeInstant(instant: kotlin.time.Instant): kotlin.time.Instant {
        return instant
    }

    @MauiBinding
    @Throws(Exception::class)
    fun testKotlinTimeInstantNullable(instant: kotlin.time.Instant?): kotlin.time.Instant? {
        return instant
    }

    @MauiBinding(canThrow = false)
    fun nowFromClock(clock: kotlin.time.Clock): kotlin.time.Instant {
        return clock.now()
    }

    @MauiBinding(canThrow = false)
    fun systemClock(): kotlin.time.Clock {
        return kotlin.time.Clock.System
    }

    @MauiBinding(canThrow = false)
    fun clockSystemNow(): kotlin.time.Instant {
        return kotlin.time.Clock.System.now()
    }

    /**
     * Returns a data class carrying a `kotlin.time.Instant`. Exposing it here makes the KSP
     * discover `InstantData` transitively and emit its binding (with the designated
     * `initWithLabel:timestamp:` constructor). Because its only constructor takes required
     * parameters, the binding gets `[DisableDefaultCtor]` — `new SharedInstantData()` must not
     * compile; consumers must pass a label and an Instant.
     */
    @MauiBinding(canThrow = false)
    fun echoInstantData(data: InstantData): InstantData {
        return data
    }
}

/**
 * A data-class DTO (discovered transitively via [E2ETest.echoInstantData]) whose constructor
 * requires a non-null `kotlin.time.Instant`. Used to verify the DisableDefaultCtor fix end-to-end.
 */
data class InstantData(
    val label: String,
    val timestamp: kotlin.time.Instant,
)


@Serializable
data class Test(
    val name: String,
    val list: List<Nested>,
    val map: Map<String, Nested>,
    val long: Long,
    @SerialName("bar")
    val foo: Byte = 1,
) {
    @Serializable
    data class Nested(
        val name: String,
        val age: Int
    )
}

@Serializable
data class TestSealedClassProperties(
    val sealed: TestSealedType,
    val sealedSubclassStandalone: TestSealedType.Option1,
    val sealedSubclassStandaloneObject: TestSealedType.Option3,
)

@Serializable
sealed class TestSealedType {
    @Serializable
    @SerialName("option1")
    data class Option1(
        val name: String,
        val nested: Nested,
    ) : TestSealedType() {
        @Serializable
        data class Nested(
            val nullable: String?
        )
    }

    @Serializable
    @SerialName("option2")
    data class Option2(
        val number: Int,
        val nonNested: NonNested,
    ) : TestSealedType()

    @Serializable
    @SerialName("option3")
    object Option3 : TestSealedType()
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("customType")
sealed class TestSealedTypeWithCustomDiscriminator {
    @Serializable
    @SerialName("option1")
    data class Option1(
        val name: String,
        val nested: Nested,
    ) : TestSealedTypeWithCustomDiscriminator() {
        @Serializable
        data class Nested(
            val nullable: String?
        )
    }

    @Serializable
    @SerialName("option2")
    data class Option2(
        val number: Int,
        val nonNested: NonNested,
    ) : TestSealedTypeWithCustomDiscriminator()

    @Serializable
    @SerialName("option3")
    object Option3 : TestSealedTypeWithCustomDiscriminator()
}

@Serializable
data class NonNested(
    val bar: String,
)

@Serializable
enum class Enum {
    Option1,
    OPTION2,
    OPTION_3,
}

typealias TestTypeAlias = Test

@Serializable
data class DateTimeTest(
    val instant: Instant,
    val localDateTime: LocalDateTime,
    val duration: Duration,
    val map: Map<String, Instant>,
    val instantOrNull: Instant?,
)

@MauiBinding
class ClassWithConstructor @MauiBinding @Throws(Exception::class) constructor(
    val string: String,
    val int: Int,
    val long: Long,
    val float: Float,
    val double: Double,
    val boolean: Boolean,
    val byte: Byte,
    val char: Char,
    val short: Short,
) {
    @MauiBinding
    @Throws(Exception::class)
    fun testDefaultTypes(): String {
        return "Hello World"
    }
}

@MauiBinding
open class GenericClass<T: String?>(val value: T) {
    @MauiBinding
    @Throws(Exception::class)
    fun process(other: T): T {
        return other
    }
}

@MauiBinding
class GenericSubclass<T :String>(value: T) : GenericClass<T>(value)

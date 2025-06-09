package com.mauikmpexample.kotlin.shared

import de.voize.mauikmp.annotation.MauiBinding
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonClassDiscriminator

@MauiBinding
class E2ETest() {

    @MauiBinding
    constructor(test: Test) : this() {
        println(test)
    }

    @MauiBinding
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
    fun testDefaultTypesNullable(
        string: String?,
        int: Int?,
        long: Long?,
        float: Float?,
        double: Double?,
        boolean: Boolean?,
        byte: Byte?,
        char: Char?,
        short: Short?,
    ): String? {
        return null
    }

    @MauiBinding
    fun testListAndMap(
        list: List<String>,
        map: Map<String, String>,
        nestedList: List<List<String>>,
        nestedMap: Map<String, Map<String, String>>,
        complexList: List<Test>,
        complexMap: Map<String, Test>,
    ): List<Int> {
        return emptyList()
    }

    @MauiBinding
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
    fun example(input: TestSealedType, testEnum: Enum?): Test {
        return Test("Erik", listOf(), mapOf(), 30)
    }

    @MauiBinding
    fun testSealedClassProperties(test: TestSealedClassProperties): TestSealedClassProperties {
        return test
    }


    @MauiBinding
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
    fun getDateTimeTest(): DateTimeTest {
        error("Not implemented")
    }

    @MauiBinding
    fun testFlow(): Flow<Int> {
        return flowOf(1, 2, 3)
    }

    @MauiBinding
    fun testFlowNullable(): Flow<Int?> {
        return flowOf(1, 2, null)
    }

    @MauiBinding
    fun testFlowComplex(): Flow<Test> {
        return flowOf(Test("Erik", listOf(), mapOf(), 30))
    }

    @MauiBinding
    fun testFlowParameterized(arg1: Int): Flow<FlowTest> {
        return flowOf()
    }

    @MauiBinding
    fun testFlowParameterized2(arg1: Int, arg2: String): Flow<FlowTest> {
        return flowOf()
    }

    @MauiBinding
    fun testFlowParameterizedComplex(arg1: Test): Flow<FlowTest> {
        return flowOf()
    }

    @MauiBinding
    fun testFlowParameterizedComplex2(arg1: List<Test>, arg2: Map<String, Test>): Flow<FlowTest> {
        return flowOf()
    }

    @MauiBinding
    fun testFlowParameterizedMany(
        arg1: Int,
        arg2: String,
        arg3: List<String>,
        arg4: Map<String, Test>
    ): Flow<FlowTest> {
        return flowOf()
    }

    @MauiBinding
    fun testFlowReturnInstant(): Flow<Instant> {
        return flowOf()
    }

    @MauiBinding
    fun testTypeAlias(test: TestTypeAlias): TestTypeAlias {
        return test
    }

    @MauiBinding
    fun testSealedSubtype(test: TestSealedType.Option1): TestSealedType.Option1 {
        return test
    }

    @MauiBinding
    fun testSealedCustomDiscriminator(test: TestSealedTypeWithCustomDiscriminator) {

    }

    @MauiBinding
    fun testMapWithEnumKey(map: Map<Enum, String>): Map<Enum, String> {
        return map
    }
}


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

@Serializable
object FlowTest

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
class ClassWithConstructor @MauiBinding constructor(
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
    fun testDefaultTypes(): String {
        return "Hello World"
    }
}

@MauiBinding
open class GenericClass<T: String?>(val value: T) {
    @MauiBinding
    fun process(other: T): T {
        return other
    }
}

@MauiBinding
class GenericSubclass<T :String>(value: T) : GenericClass<T>(value)

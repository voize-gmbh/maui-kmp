using Foundation;
namespace Voize
{
  using TestTypeAlias = Voize.SharedTest;
  [BaseType(typeof(NSObject))]
  interface SharedBase : ObjCRuntime.INativeObject
  {
  [Export ("description")]
string ToString ();
  }
  [BaseType (typeof(SharedBase))]
  interface SharedKotlinUnit : Voize.SharedBase
  {
  
  }
  [Protocol]
  interface SharedKotlinComparable
  {
  
  }
  [BaseType (typeof(SharedBase))]
  interface SharedKotlinEnum
  {
  
  }
  [BaseType(typeof(SharedBase))]
  interface SharedKotlinThrowable
  {
  [Export ("initWithMessage:")]
[DesignatedInitializer]
ObjCRuntime.NativeHandle Constructor ([NullAllowed] string message);

[NullAllowed, Export ("message")]
string Message { get; }

[Export ("asError")]
NSError AsError { get; }
  }
  [BaseType (typeof(SharedBase))]
  interface SharedKotlinx_datetimeLocalDate
  {
  [Export ("initWithYear:monthNumber:dayOfMonth:")]
[DesignatedInitializer]
ObjCRuntime.NativeHandle Constructor (int year, int monthNumber, int dayOfMonth);

[Export ("year")]
int Year { get; }

[Export ("monthNumber")]
int MonthNumber { get; }

[Export ("dayOfMonth")]
int DayOfMonth { get; }

[Static]
[Export ("companion")]
SharedKotlinx_datetimeLocalDateCompanion Companion { [Bind ("companion")] get; }
  }
  [BaseType (typeof(SharedBase))]
  interface SharedKotlinx_datetimeLocalDateCompanion
  {
  [Export ("fromEpochDaysEpochDays:")]
SharedKotlinx_datetimeLocalDate FromEpochDays (int epochDays);
  }
  [BaseType (typeof(SharedBase))]
  interface SharedKotlinx_datetimeInstant
  {
  [Export ("toEpochMilliseconds")]
long ToEpochMilliseconds ();

[Static]
[Export ("companion")]
SharedKotlinx_datetimeInstantCompanion Companion { [Bind ("companion")] get; }
  }
  [BaseType (typeof(SharedBase))]
  interface SharedKotlinx_datetimeInstantCompanion
  {
  [Export ("fromEpochMillisecondsEpochMilliseconds:")]
SharedKotlinx_datetimeInstant FromEpochMilliseconds (long epochMilliseconds);

[Export ("fromEpochSecondsEpochSeconds:nanosecondAdjustment:")]
SharedKotlinx_datetimeInstant FromEpochSeconds (long epochSeconds, int nanosecondAdjustment);

[Export ("fromEpochSecondsEpochSeconds:nanosecondAdjustment_:")]
SharedKotlinx_datetimeInstant FromEpochSeconds (long epochSeconds, long nanosecondAdjustment);

[Export ("DISTANT_FUTURE")]
SharedKotlinx_datetimeInstant DISTANT_FUTURE { get; }

[Export ("DISTANT_PAST")]
SharedKotlinx_datetimeInstant DISTANT_PAST { get; }
  }
  [BaseType (typeof(SharedBase))]
  interface SharedKotlinx_datetimeLocalTime
  {
  [Export ("initWithHour:minute:second:nanosecond:")]
[DesignatedInitializer]
ObjCRuntime.NativeHandle Constructor (int hour, int minute, int second, int nanosecond);
    
[Export ("hour")]
int Hour { get; }

[Export ("minute")]
int Minute { get; }

[Export ("nanosecond")]
int Nanosecond { get; }

[Export ("second")]
int Second { get; }

[Static]
[Export ("companion")]
SharedKotlinx_datetimeLocalTimeCompanion Companion { [Bind ("companion")] get; }
  }
  [BaseType (typeof(SharedBase))]
  interface SharedKotlinx_datetimeLocalTimeCompanion
  {
  [Export ("fromMillisecondOfDayMillisecondOfDay:")]
SharedKotlinx_datetimeLocalTime FromMillisecondOfDay (int millisecondOfDay);

[Export ("fromNanosecondOfDayNanosecondOfDay:")]
SharedKotlinx_datetimeLocalTime FromNanosecondOfDay (long nanosecondOfDay);

[Export ("fromSecondOfDaySecondOfDay:")]
SharedKotlinx_datetimeLocalTime FromSecondOfDay (int secondOfDay);
  }
  [BaseType (typeof(SharedBase))]
  interface SharedKotlinx_datetimeLocalDateTime
  {
  [Export ("initWithYear:monthNumber:dayOfMonth:hour:minute:second:nanosecond:")]
[DesignatedInitializer]
ObjCRuntime.NativeHandle Constructor (int year, int monthNumber, int dayOfMonth, int hour, int minute, int second, int nanosecond);

[Export ("date")]
SharedKotlinx_datetimeLocalDate Date { get; }

[Export ("time")]
SharedKotlinx_datetimeLocalTime Time { get; }
  }
  [BaseType (typeof(Voize.SharedBase))]
  interface SharedE2ETest : ObjCRuntime.INativeObject
  {
      [Export ("initWithTest:")]
    ObjCRuntime.NativeHandle Constructor (Voize.SharedTest test);

    [Export ("testDefaultTypesString:int:long:float:double:boolean:byte:char:short:")]
    string TestDefaultTypes(string string, int int, long long, float float, double double, bool boolean, byte byte, char char, short short);

    [Export ("testDefaultTypesNullableString:int:long:float:double:boolean:byte:char:short:"), NullAllowed]
    string TestDefaultTypesNullable([NullAllowed] string string, [ObjCRuntime.BindAs (typeof (int?)), NullAllowed] Foundation.NSNumber int, [ObjCRuntime.BindAs (typeof (long?)), NullAllowed] Foundation.NSNumber long, [ObjCRuntime.BindAs (typeof (float?)), NullAllowed] Foundation.NSNumber float, [ObjCRuntime.BindAs (typeof (double?)), NullAllowed] Foundation.NSNumber double, [ObjCRuntime.BindAs (typeof (bool?)), NullAllowed] Foundation.NSNumber boolean, [ObjCRuntime.BindAs (typeof (byte?)), NullAllowed] Foundation.NSNumber byte, [ObjCRuntime.BindAs (typeof (char?)), NullAllowed] Foundation.NSNumber char, [ObjCRuntime.BindAs (typeof (short?)), NullAllowed] Foundation.NSNumber short);

    [Export ("testListAndMapList:map:nestedList:nestedMap:complexList:complexMap:")]
    Foundation.NSNumber[] TestListAndMap(Foundation.NSString[] list, Foundation.NSDictionary<Foundation.NSString, Foundation.NSString> map, Foundation.NSString[][] nestedList, Foundation.NSDictionary<Foundation.NSString, Foundation.NSDictionary<Foundation.NSString, Foundation.NSString>> nestedMap, Voize.SharedTest[] complexList, Foundation.NSDictionary<Foundation.NSString, Voize.SharedTest> complexMap);

    [Export ("testListAndMapNullableList:map:nestedList:nestedMap:complexList:complexMap:listNullable:mapNullable:nestedListNullable:nestedMapNullable:complexListNullable:complexMapNullable:")]
    Foundation.NSNumber?[] TestListAndMapNullable([NullAllowed] Foundation.NSString[] list, [NullAllowed] Foundation.NSDictionary<Foundation.NSString, Foundation.NSString> map, [NullAllowed] Foundation.NSString[][] nestedList, [NullAllowed] Foundation.NSDictionary<Foundation.NSString, Foundation.NSDictionary<Foundation.NSString, Foundation.NSString>> nestedMap, [NullAllowed] Voize.SharedTest[] complexList, [NullAllowed] Foundation.NSDictionary<Foundation.NSString, Voize.SharedTest> complexMap, Foundation.NSString?[] listNullable, Foundation.NSDictionary<Foundation.NSString, Foundation.NSString?> mapNullable, Foundation.NSString?[][] nestedListNullable, Foundation.NSDictionary<Foundation.NSString, Foundation.NSDictionary?<Foundation.NSString, Foundation.NSString?>> nestedMapNullable, Voize.SharedTest?[] complexListNullable, Foundation.NSDictionary<Foundation.NSString, Voize.SharedTest?> complexMapNullable);

    [Export ("exampleInput:testEnum:")]
    Voize.SharedTest Example(Voize.SharedTestSealedType input, [NullAllowed] Voize.SharedEnum testEnum);

    [Export ("testSealedClassPropertiesTest:")]
    Voize.SharedTestSealedClassProperties TestSealedClassProperties(Voize.SharedTestSealedClassProperties test);

    [Export ("testKotlinDateTimeDuration:durationOrNull:instant:localDateTime:test:instantOrNull:")]
    long TestKotlinDateTime(long duration, [NullAllowed] Foundation.NSNumber durationOrNull, Voize.SharedKotlinx_datetimeInstant instant, Voize.SharedKotlinx_datetimeLocalDateTime localDateTime, Voize.SharedDateTimeTest test, [NullAllowed] Voize.SharedKotlinx_datetimeInstant instantOrNull);

    [Export ("testKotlinDateTimeListDuration:instant:localDateTime:test:instantOrNull:")]
    Foundation.NSNumber[] TestKotlinDateTimeList(Foundation.NSNumber[] duration, Voize.SharedKotlinx_datetimeInstant[] instant, Voize.SharedKotlinx_datetimeLocalDateTime[] localDateTime, Voize.SharedDateTimeTest[] test, Voize.SharedKotlinx_datetimeInstant?[] instantOrNull);

    [Export ("getDateTimeTest")]
    Voize.SharedDateTimeTest GetDateTimeTest();

    [Export ("testFlow")]
    Voize.SharedFlow TestFlow();

    [Export ("testFlowNullable")]
    Voize.SharedFlow TestFlowNullable();

    [Export ("testFlowComplex")]
    Voize.SharedFlow TestFlowComplex();

    [Export ("testFlowParameterizedArg1:")]
    Voize.SharedFlow TestFlowParameterized(int arg1);

    [Export ("testFlowParameterized2Arg1:arg2:")]
    Voize.SharedFlow TestFlowParameterized2(int arg1, string arg2);

    [Export ("testFlowParameterizedComplexArg1:")]
    Voize.SharedFlow TestFlowParameterizedComplex(Voize.SharedTest arg1);

    [Export ("testFlowParameterizedComplex2Arg1:arg2:")]
    Voize.SharedFlow TestFlowParameterizedComplex2(Voize.SharedTest[] arg1, Foundation.NSDictionary<Foundation.NSString, Voize.SharedTest> arg2);

    [Export ("testFlowParameterizedManyArg1:arg2:arg3:arg4:")]
    Voize.SharedFlow TestFlowParameterizedMany(int arg1, string arg2, Foundation.NSString[] arg3, Foundation.NSDictionary<Foundation.NSString, Voize.SharedTest> arg4);

    [Export ("testFlowReturnInstant")]
    Voize.SharedFlow TestFlowReturnInstant();

    [Export ("testTypeAliasTest:")]
    TestTypeAlias TestTypeAlias(TestTypeAlias test);

    [Export ("testSealedSubtypeTest:")]
    Voize.SharedTestSealedTypeOption1 TestSealedSubtype(Voize.SharedTestSealedTypeOption1 test);

    [Export ("testSealedCustomDiscriminatorTest:")]
    void TestSealedCustomDiscriminator(Voize.SharedTestSealedTypeWithCustomDiscriminator test);

    [Export ("testMapWithEnumKeyMap:")]
    Foundation.NSDictionary<Voize.SharedEnum, Foundation.NSString> TestMapWithEnumKey(Foundation.NSDictionary<Voize.SharedEnum, Foundation.NSString> map);


  }
  [BaseType (typeof(Voize.SharedBase))]
  interface SharedTest : ObjCRuntime.INativeObject
  {
      [Export ("initWithName:list:map:long:foo:"), DesignatedInitializer]
    ObjCRuntime.NativeHandle Constructor (string name, Voize.SharedTestNested[] list, Foundation.NSDictionary<Foundation.NSString, Voize.SharedTestNested> map, long long, byte foo);

    [Export ("name")]
    string Name { get; }

    [Export ("list")]
    Voize.SharedTestNested[] List { get; }

    [Export ("map")]
    Foundation.NSDictionary<Foundation.NSString, Voize.SharedTestNested> Map { get; }

    [Export ("long")]
    long Long { get; }

    [Export ("foo")]
    byte Foo { get; }


  }
  [BaseType (typeof(Voize.SharedBase))]
  interface SharedTestSealedType : ObjCRuntime.INativeObject
  {
  
  }
  [BaseType (typeof(SharedKotlinEnum))]
  interface SharedEnum : ObjCRuntime.INativeObject
  {
      [Static]
    [Export ("option1")]
    SharedEnum Option1 { get; }
    [Static]
    [Export ("option2")]
    SharedEnum OPTION2 { get; }
    [Static]
    [Export ("option3")]
    SharedEnum OPTION_3 { get; }

  }
  [BaseType (typeof(Voize.SharedBase))]
  interface SharedTestSealedClassProperties : ObjCRuntime.INativeObject
  {
      [Export ("initWithSealed:sealedSubclassStandalone:sealedSubclassStandaloneObject:"), DesignatedInitializer]
    ObjCRuntime.NativeHandle Constructor (Voize.SharedTestSealedType sealed, Voize.SharedTestSealedTypeOption1 sealedSubclassStandalone, Voize.SharedTestSealedTypeOption3 sealedSubclassStandaloneObject);

    [Export ("sealed")]
    Voize.SharedTestSealedType Sealed { get; }

    [Export ("sealedSubclassStandalone")]
    Voize.SharedTestSealedTypeOption1 SealedSubclassStandalone { get; }

    [Export ("sealedSubclassStandaloneObject")]
    Voize.SharedTestSealedTypeOption3 SealedSubclassStandaloneObject { get; }


  }
  [BaseType (typeof(Voize.SharedBase))]
  interface SharedDateTimeTest : ObjCRuntime.INativeObject
  {
      [Export ("initWithInstant:localDateTime:duration:map:instantOrNull:"), DesignatedInitializer]
    ObjCRuntime.NativeHandle Constructor (Voize.SharedKotlinx_datetimeInstant instant, Voize.SharedKotlinx_datetimeLocalDateTime localDateTime, long duration, Foundation.NSDictionary<Foundation.NSString, Voize.SharedKotlinx_datetimeInstant> map, [NullAllowed] Voize.SharedKotlinx_datetimeInstant instantOrNull);

    [Export ("instant")]
    Voize.SharedKotlinx_datetimeInstant Instant { get; }

    [Export ("localDateTime")]
    Voize.SharedKotlinx_datetimeLocalDateTime LocalDateTime { get; }

    [Export ("duration")]
    long Duration { get; }

    [Export ("map")]
    Foundation.NSDictionary<Foundation.NSString, Voize.SharedKotlinx_datetimeInstant> Map { get; }

    [Export ("instantOrNull"), NullAllowed]
    Voize.SharedKotlinx_datetimeInstant InstantOrNull { get; }


  }
  [BaseType (typeof(Voize.SharedBase))]
  interface SharedFlowTest : ObjCRuntime.INativeObject
  {
      [Static, Export ("flowTest")]
    SharedFlowTest FlowTest ();

  }
  [BaseType (typeof(Voize.SharedBase))]
  interface SharedTestSealedTypeWithCustomDiscriminator : ObjCRuntime.INativeObject
  {
  
  }
  [BaseType (typeof(Voize.SharedBase))]
  interface SharedNonNested : ObjCRuntime.INativeObject
  {
      [Export ("initWithBar:"), DesignatedInitializer]
    ObjCRuntime.NativeHandle Constructor (string bar);

    [Export ("bar")]
    string Bar { get; }


  }
  [BaseType (typeof(Voize.SharedTestSealedType))]
  interface SharedTestSealedTypeOption1 : ObjCRuntime.INativeObject
  {
      [Export ("initWithName:nested:"), DesignatedInitializer]
    ObjCRuntime.NativeHandle Constructor (string name, Voize.SharedTestSealedTypeOption1Nested nested);

    [Export ("name")]
    string Name { get; }

    [Export ("nested")]
    Voize.SharedTestSealedTypeOption1Nested Nested { get; }


  }
  [BaseType (typeof(Voize.SharedTestSealedType))]
  interface SharedTestSealedTypeOption2 : ObjCRuntime.INativeObject
  {
      [Export ("initWithNumber:nonNested:"), DesignatedInitializer]
    ObjCRuntime.NativeHandle Constructor (int number, Voize.SharedNonNested nonNested);

    [Export ("number")]
    int Number { get; }

    [Export ("nonNested")]
    Voize.SharedNonNested NonNested { get; }


  }
  [BaseType (typeof(Voize.SharedTestSealedType))]
  interface SharedTestSealedTypeOption3 : ObjCRuntime.INativeObject
  {
      [Static, Export ("option3")]
    SharedTestSealedTypeOption3 Option3 ();

  }
  [BaseType (typeof(Voize.SharedBase))]
  interface SharedTestSealedTypeOption1Nested : ObjCRuntime.INativeObject
  {
      [Export ("initWithNullable:"), DesignatedInitializer]
    ObjCRuntime.NativeHandle Constructor ([NullAllowed] string nullable);

    [Export ("nullable"), NullAllowed]
    string Nullable { get; }


  }
  [BaseType (typeof(Voize.SharedTestSealedTypeWithCustomDiscriminator))]
  interface SharedTestSealedTypeWithCustomDiscriminatorOption1 : ObjCRuntime.INativeObject
  {
      [Export ("initWithName:nested:"), DesignatedInitializer]
    ObjCRuntime.NativeHandle Constructor (string name, Voize.SharedTestSealedTypeWithCustomDiscriminatorOption1Nested nested);

    [Export ("name")]
    string Name { get; }

    [Export ("nested")]
    Voize.SharedTestSealedTypeWithCustomDiscriminatorOption1Nested Nested { get; }


  }
  [BaseType (typeof(Voize.SharedTestSealedTypeWithCustomDiscriminator))]
  interface SharedTestSealedTypeWithCustomDiscriminatorOption2 : ObjCRuntime.INativeObject
  {
      [Export ("initWithNumber:nonNested:"), DesignatedInitializer]
    ObjCRuntime.NativeHandle Constructor (int number, Voize.SharedNonNested nonNested);

    [Export ("number")]
    int Number { get; }

    [Export ("nonNested")]
    Voize.SharedNonNested NonNested { get; }


  }
  [BaseType (typeof(Voize.SharedTestSealedTypeWithCustomDiscriminator))]
  interface SharedTestSealedTypeWithCustomDiscriminatorOption3 : ObjCRuntime.INativeObject
  {
      [Static, Export ("option3")]
    SharedTestSealedTypeWithCustomDiscriminatorOption3 Option3 ();

  }
  [BaseType (typeof(Voize.SharedBase))]
  interface SharedTestSealedTypeWithCustomDiscriminatorOption1Nested : ObjCRuntime.INativeObject
  {
      [Export ("initWithNullable:"), DesignatedInitializer]
    ObjCRuntime.NativeHandle Constructor ([NullAllowed] string nullable);

    [Export ("nullable"), NullAllowed]
    string Nullable { get; }


  }
  [BaseType (typeof(Voize.SharedBase))]
  interface SharedTestNested : ObjCRuntime.INativeObject
  {
      [Export ("initWithName:age:"), DesignatedInitializer]
    ObjCRuntime.NativeHandle Constructor (string name, int age);

    [Export ("name")]
    string Name { get; }

    [Export ("age")]
    int Age { get; }


  }
}

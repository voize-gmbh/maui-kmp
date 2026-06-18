# CHANGELOG

## unreleased

## v0.4.1

- Fixed double binding on kotlin.time
- Fixed binding error parameter order on observable

## v0.4.0

- Updated to Kotlin 2.3.20 and KSP 2.3.6
- Sync error propagation: a `@Throws(Exception::class)` `@MauiBinding` function/constructor
  now bridges thrown `Exception`s to an `NSError` (catchable in C#) instead of terminating the process
- Generate `<throwsWrapperClassName>.cs` with idiomatic wrappers: methods hide the `out NSError` and
  throw `NSErrorException`; throwing constructors get `CreateX(...)` factories
- Build now fails if a synchronous `@MauiBinding` (non-adapter) lacks `@Throws` — opt out globally
  with `maui.kmp.ios.requireThrowsOnSyncBindings=false`, or per-member with `@MauiBinding(canThrow = false)`
  (asserts the member never throws; keeps the plain `new SharedX()` path, no factory/`[DisableDefaultCtor]`)
- `[DisableDefaultCtor]` is now emitted for any class whose exposed constructors all require
  parameters (e.g. data classes), not just `@Throws` constructors — bgen no longer emits a broken
  parameterless `new SharedX()` that called a non-existent plain `init` and returned a
  half-initialized object
- Map `kotlin.time.Instant` to `${prefix}KotlinInstant` (distinct from `kotlinx.datetime.Instant`),
  with its `Companion` factories (`fromEpochMilliseconds`/`fromEpochSeconds`) and `DISTANT_*`
- Support `kotlin.time.Clock`: bound as a `[Protocol, Model]` with `[ForcedType]` on direct Clock
  return values and parameters, so the unbound `Clock.System` protocol conformer resolves instead of
  throwing `InvalidCastException`
- New KSP options: `maui.kmp.ios.asyncAdapterTypes`, `maui.kmp.ios.requireThrowsOnSyncBindings`,
  `maui.kmp.ios.emitDeprecatedConstructorShims`, `maui.kmp.ios.throwsWrapperClassName`
- Async adapter errors are delivered per-subscription via `onError`; cross-cutting observability is
  left to the host (the example broadcasts them through an `ErrorBus` publisher in `Extra.cs`)
- **BREAKING (iOS):** all sync selectors change; `new SharedX()` is removed for `@Throws`
  constructors and for any class whose constructors all require parameters — consumers must
  regenerate the binding and migrate construction (see README)

## v0.3.1

- Added instant from kotlin

## v0.3.0

- Add dependencyConfiguration to mauiKmp plugin configuration

## v0.2.1

- Implement nuget fetching workaround for finding matching versions

## v0.2.0

- Implement configuration via KSP arguments
- Update to net9.0-android target framework for android binding project
- Sort dependencies in generated android csproj file
- Also fetch prerelease versions from dotnet nuget
- Update target framework to net9.0-ios17.0 in example project

## v0.1.15

- Fix top-level function dummy class name if contains dot
- Enforce non-suspend MauiBinding functions

## v0.1.14

- Update Nexus repository URLs for publishing

## v0.1.13

- Fix ios type generation for nested collections
- Escape csharp identifier if it is a reserved keyword
- Correctly handle nullable parameterized types
- Cleanup maui ios example project

## v0.1.12

- Add workaround for ksp k2 bug

## v0.1.11

- Support KSP2

## v0.1.10

- Enhance error messages for unsupported type parameters in MAUI functions and properties

## v0.1.9

- Fix maven artifact version in comment was nuget version

## v0.1.8

- Fix gradle task already exists bug

## v0.1.7

- Add source jar to published gradle plugin artifacts

## v0.1.6

- Fix release pipeline

## v0.1.5

- Add gradle plugin to generate android fat aar and csproj

## v0.1.4

- Fix kotlin Duration is converted to NSNumber instead of long in bindings

## v0.1.3

- Remove non nullable primitives conversion to and from NSNumber in bindings

## v0.1.2

- Fix void return type is not used for bindings

## v0.1.1

- Fix NSNumber types should use BindAs bindings

## v0.1.0

- Generate iOS Maui bindings ApiDefinitions.cs file based on annotated kotlin source code

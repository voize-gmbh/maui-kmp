# CHANGELOG

## unreleased
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

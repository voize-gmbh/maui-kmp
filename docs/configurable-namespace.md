# Configurable Namespace for C# Binding Generation

This document explains how to configure the namespace for C# binding generation in the MAUI KMP project.

## Overview

The KSP processor now supports configurable namespace and prefix parameters for C# binding generation, instead of using hardcoded values.

## Configuration Parameters

### `mauikmp.csharpIOSBindingNamespace`
- **Required**: Yes
- **Description**: The namespace used for generated C# bindings
- **Usage**: This will be the namespace that wraps all generated C# interfaces

### `mauikmp.csharpIOSBindingPrefix`
- **Required**: Yes
- **Description**: The prefix used for generated C# class names
- **Usage**: This prefix is prepended to all generated C# interface names and should match the prefix configured in the Kotlin framework generation to ensure consistency between the Kotlin Objective-C headers and the C# bindings

## How to Configure

Add the following to your `build.gradle.kts` file:

```kotlin
ksp {
    arg("mauikmp.csharpIOSBindingNamespace", "YourNamespace")
    arg("mauikmp.csharpIOSBindingPrefix", "YourPrefix")
}
```

## Example

### Before (required configuration)
```kotlin
// In build.gradle.kts
ksp {
    arg("mauikmp.csharpIOSBindingNamespace", "MyCompany.Mobile")
    arg("mauikmp.csharpIOSBindingPrefix", "Native")
}
```

### After (generated C# bindings)
```csharp
namespace MyCompany.Mobile
{
    interface NativeBase : ObjCRuntime.INativeObject
    {
        // ...
    }
}
```

## Multiple Targets

When configuring for multiple targets, the same configuration applies to all KSP tasks:

```kotlin
dependencies {
    add("kspCommonMainMetadata", libs.maui.kmp.ksp)
    add("kspAndroid", libs.maui.kmp.ksp)
    add("kspIosX64", libs.maui.kmp.ksp)
    add("kspIosArm64", libs.maui.kmp.ksp)
    add("kspIosSimulatorArm64", libs.maui.kmp.ksp)
}

ksp {
    arg("mauikmp.csharpIOSBindingNamespace", "YourNamespace")
    arg("mauikmp.csharpIOSBindingPrefix", "YourPrefix")
}
```
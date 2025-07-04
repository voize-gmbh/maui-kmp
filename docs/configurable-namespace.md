# Configurable Namespace for C# Binding Generation

This document explains how to configure the namespace for C# binding generation in the MAUI KMP project.

## Overview

The KSP processor now supports configurable namespace and prefix parameters for C# binding generation, instead of using hardcoded values.

## Configuration Parameters

### `mauikmp.csharpIOSBindingNamespace`
- **Default**: `"Voize"`
- **Description**: The namespace used for generated C# bindings
- **Usage**: This will be the namespace that wraps all generated C# interfaces

### `mauikmp.csharpIOSBindingPrefix`
- **Default**: `"Shared"`
- **Description**: The prefix used for generated C# class names
- **Usage**: This prefix is prepended to all generated C# interface names

## How to Configure

Add the following to your `build.gradle.kts` file:

```kotlin
ksp {
    arg("mauikmp.csharpIOSBindingNamespace", "YourNamespace")
    arg("mauikmp.csharpIOSBindingPrefix", "YourPrefix")
}
```

## Example

### Before (hardcoded)
```csharp
namespace Voize
{
    interface SharedBase : ObjCRuntime.INativeObject
    {
        // ...
    }
}
```

### After (configurable)
```kotlin
// In build.gradle.kts
ksp {
    arg("mauikmp.csharpIOSBindingNamespace", "MyCompany.Mobile")
    arg("mauikmp.csharpIOSBindingPrefix", "Native")
}
```

```csharp
namespace MyCompany.Mobile
{
    interface NativeBase : ObjCRuntime.INativeObject
    {
        // ...
    }
}
```

## Backward Compatibility

If no configuration is provided, the processor will use the default values:
- Namespace: `"Voize"`
- Prefix: `"Shared"`

This ensures that existing projects continue to work without any changes.

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
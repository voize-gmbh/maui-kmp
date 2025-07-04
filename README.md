# maui-kmp

This toolkit allows you to combine MAUI with Kotlin Multiplatform (KMP) by generating bindings for iOS and Android from Kotlin common code.

## Features

Currently the following features are supported:
- Generate iOS Maui bindings ApiDefinitions.cs file based on annotated kotlin source code
- Generate android csproj file android binding project
- Generate android fat aar via gradle plugin
- **Configurable namespace and prefix for C# binding generation**

## Configuration

### C# Binding Namespace Configuration

You can configure the namespace and prefix used for generated C# bindings by adding KSP arguments in your `build.gradle.kts`:

```kotlin
ksp {
    arg("mauikmp.csharpIOSBindingNamespace", "YourNamespace")
    arg("mauikmp.csharpIOSBindingPrefix", "YourPrefix")
}
```

**Parameters:**
- `mauikmp.csharpIOSBindingNamespace`: The namespace for generated C# bindings (**required**)
- `mauikmp.csharpIOSBindingPrefix`: The prefix for generated C# class names (**required**, should match Kotlin framework prefix)

**Example:**
```kotlin
ksp {
    arg("mauikmp.csharpIOSBindingNamespace", "MyCompany.Mobile")
    arg("mauikmp.csharpIOSBindingPrefix", "Native")
}
```

This will generate C# bindings like:
```csharp
namespace MyCompany.Mobile
{
    interface NativeBase : ObjCRuntime.INativeObject
    {
        // ...
    }
}
```

## Setup

The gradle plugin uses https://github.com/aasitnikov/fat-aar-android to generate fat aar file.
You need to add the following to your buildscript repositories:
```kotlin
maven {
    setUrl("https://jitpack.io")
    content {
        includeGroup("com.github.aasitnikov")
    }
}
```

# maui-kmp

This toolkit allows you to combine MAUI with Kotlin Multiplatform (KMP) by generating bindings for iOS and Android from Kotlin common code.

## Features

Currently the following features are supported:
- Generate iOS Maui bindings ApiDefinitions.cs file based on annotated kotlin source code
- Generate android csproj file android binding project
- Generate android fat aar via gradle plugin

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

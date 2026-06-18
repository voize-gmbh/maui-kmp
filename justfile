# maui-kmp — recipes for the iOS example app (SDK-94).
#
# The example builds against the LOCAL toolkit (gradle composite build): see
# example/kotlin/gradle.properties (`useLocalToolkit=true`). So changes to the plugin / KSP / lib
# under kotlin/ are picked up automatically by these recipes — no publishing needed.
#
# Build pipeline (mirrors voize-sdk production):
#   bindings  → KSP generates ApiDefinitions.cs
#   framework → Kotlin/Native assembles XCFramework
#   pack      → dotnet pack produces MauiKmpExample.iOS.0.1.0.nupkg in binding/bin/Release/
#   run/build → app restores from local NuGet feed, builds, and optionally launches on simulator

kotlin_dir  := "example/kotlin"
app_dir     := "example/maui/app"
binding_dir := "example/maui/binding"
app_id      := "de.voize.mauikmp.example"

# List recipes.
default:
    @just --list

# Staleness sources this guards against:
#   - binding/app bin+obj: incremental dotnet build does NOT re-merge Info.plist changes
#   - NuGet package cache: version is fixed at 0.1.0, so restore would reuse a stale copy
#   - simulator install: iOS caches the launch screen until the app is uninstalled
# Remove all dotnet build outputs and caches that can go stale.
clean:
    rm -rf {{binding_dir}}/bin {{binding_dir}}/obj
    rm -rf {{app_dir}}/bin {{app_dir}}/obj
    rm -rf ~/.nuget/packages/mauikmpexample.ios
    -xcrun simctl uninstall booted {{app_id}} 2>/dev/null

# Regenerate the C# bindings (ApiDefinitions.cs) from the @MauiBinding Kotlin sources.
bindings:
    cd {{kotlin_dir}} && ./gradlew :composeApp:copyGeneratedMauiIosFiles

# Build the Kotlin/Native XCFramework (device + simulator) and copy it next to the binding project.
framework:
    cd {{kotlin_dir}} && ./gradlew :composeApp:copyXCFrameworkToBinding

# Mirrors the voize-sdk production command: dotnet pack -p:FrameworkType=Debug -c Release.
# Runs `clean` first so dotnet never reuses stale outputs (Gradle's own up-to-date checks are
# reliable and stay incremental). Clears the NuGet package cache again after packing so the
# next restore picks up the fresh .nupkg.
# Pack the iOS binding as a NuGet package into binding/bin/Release/ (always from a clean slate).
pack: clean bindings framework
    mkdir -p {{binding_dir}}/bin/Release
    cd {{kotlin_dir}} && ./gradlew :composeApp:packIosBinding
    rm -rf ~/.nuget/packages/mauikmpexample.ios

# Full build: pack NuGet, restore from local feed, build MAUI app. Always from a clean slate.
build: pack
    cd {{app_dir}} && dotnet build -c Debug

# Build and launch the app on a booted iOS simulator. Always from a clean slate.
run: pack
    cd {{app_dir}} && dotnet build -c Debug -f net10.0-ios
    cd {{app_dir}} && dotnet build -c Debug -f net10.0-ios -t:Run

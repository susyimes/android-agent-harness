# Third-party notices

## Gradle Wrapper

The repository includes standard Gradle Wrapper bootstrap files generated with Gradle 8.11.1. Gradle is distributed under the Apache License 2.0. The wrapper is build tooling and is not derived from the read-only application reference.

No runtime third-party library is declared by `harness-core`. The Android Gradle Plugin, Kotlin Gradle plugin, Kotlin standard library, Android SDK stubs, and JUnit are resolved as build/test dependencies under their respective licenses.

`web4agent-android` uses the Android platform `WebView` APIs and the device's
installed Android System WebView implementation. It introduces no bundled
browser engine or additional runtime library.

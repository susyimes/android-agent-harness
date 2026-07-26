# Contributing

## Prerequisites

- JDK 17. That is all for the JVM modules (`harness-core`, `provider-openai`, `harness-eval`, `device-loop`, `demo`).
- The Android SDK (Platform 36) is needed only for the Android modules: the `device-loop-android` library (accessibility integration) and the `sample` app. Their unit tests are JVM-hosted but still need the SDK to configure the Android Gradle plugin.

## Build and test

JVM-only (no Android SDK):

```sh
./gradlew auditProvenance :harness-core:test :provider-openai:test :harness-eval:test :device-loop:test :demo:test
```

Full validation (what CI runs, requires the Android SDK):

```sh
./gradlew checkM0 :device-loop-android:testDebugUnitTest
```

`checkM0` includes `:sample:assembleDebug`, so this also proves the APK still builds. Please make sure both are green before opening a pull request.

## Provenance and privacy rules

The `auditProvenance` task fails the build when it finds any of the following — these are hard rules for every file, including tests and documentation:

- An identifier that looks like a credential (`apiKey`, `access_token`, `client_secret`, `password`, …) assigned a quoted string literal. Read credentials from the environment; in tests, name stub values differently (for example `keyValue`, `stubCredentialValue`).
  - This bites Android preference keys in particular: a constant whose *name* contains a credential-shaped word fails the audit as soon as any quoted string is assigned to it — even when that string is merely a `SharedPreferences` key name, not a secret. Name such constants after the credential's role instead (the sample uses `PREF_CREDENTIAL` holding the key string `"openai_credential"`, and `credentialValue`/`storedCredential` for run-time values).
- Secret-bearing filenames (`.env`, `secrets.properties`, keystores, private keys) anywhere in the tree.
- Reference product namespaces or private local machine paths in sources or docs.
- Binary assets under the Android sample's resources.

## Clean-room policy

This repository is an independently authored extraction of architectural seams. Contributions must be authored fresh: do not copy implementation text, package names, resources, or configuration from any private reference project. Documentation may name reference class responsibilities as provenance facts, but never private paths, revisions, or package names.

## Style

- Kotlin, synchronous contracts, no coroutines, no third-party runtime dependencies (JUnit 4 for tests).
- First line of every Kotlin file: `// SPDX-License-Identifier: Apache-2.0`
- LF line endings, four-space indentation.
- Keep demos deterministic: `FixedAgentClock` and `SequentialAgentIdGenerator` in anything whose output is asserted or documented.

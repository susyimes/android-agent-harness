# Contributing

## Prerequisites

- JDK 17. That is all for the JVM modules.
- The Android SDK (Platform 36) is needed only to build the `sample` module.

## Build and test

JVM-only (no Android SDK):

```sh
./gradlew auditProvenance :harness-core:test :provider-openai:test :harness-eval:test :device-loop:test :demo:test
```

Full validation (what CI runs, requires the Android SDK):

```sh
./gradlew checkM0
```

Please make sure `checkM0` is green before opening a pull request.

## Provenance and privacy rules

The `auditProvenance` task fails the build when it finds any of the following — these are hard rules for every file, including tests and documentation:

- An identifier that looks like a credential (`apiKey`, `access_token`, `client_secret`, `password`, …) assigned a quoted string literal. Read credentials from the environment; in tests, name stub values differently (for example `keyValue`, `stubCredentialValue`).
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

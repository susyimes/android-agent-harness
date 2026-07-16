# Android Agent Harness

Android Agent Harness is a small, provider-neutral M0 for running a bounded agent turn on Android. It separates five contracts: model provider, selected context, registered tools, session storage, and deterministic harness execution.

The repository contains no network provider, credentials, private data, product configuration, Android permissions, or product assets. The sample is deliberately local: a scripted provider asks a generic uppercase tool to transform user-entered text and then displays the deterministic transcript.

## Requirements

- JDK 17
- Android SDK Platform 36
- No API key, account, device, NDK, or external service

## Validate M0

On Windows:

```powershell
$env:GRADLE_USER_HOME = "$PWD\.gradle-user-home"
.\gradlew.bat checkM0 --no-daemon
```

On macOS or Linux:

```sh
GRADLE_USER_HOME="$PWD/.gradle-user-home" ./gradlew checkM0 --no-daemon
```

`checkM0` runs the JVM contract tests, the provenance/privacy audit, and `:sample:assembleDebug`.

To install the sample on a connected Android device or emulator:

```powershell
.\gradlew.bat :sample:installDebug
```

## Modules

- `harness-core`: Kotlin/JVM contracts and the bounded deterministic runner. It has no Android, network, JSON, storage, or coroutine dependency.
- `sample`: a minimal Android Activity with an in-memory session, static public context, scripted provider, and one uppercase tool.

## Contract shape

Construct `DeterministicAgentHarness` with explicit implementations of:

- `AgentProvider`
- `AgentContextProvider`
- `AgentToolRegistry`
- `AgentSessionStore`
- `AgentClock` and `AgentIdGenerator`

The harness saves the user message, loads and stably sorts context, exposes a stably sorted tool catalog, executes provider-requested tools in declared order, stops on provider text, and rejects runs that exceed the configured provider-step or per-step tool-call limits.

See [extraction and compatibility](docs/EXTRACTION_AND_COMPATIBILITY.md) and [provenance/privacy inventory](docs/PROVENANCE_PRIVACY.md) before adapting a real provider or persistence layer.

## M0 non-goals

M0 intentionally omits network clients, streaming, JSON Schema, Android service lifecycles, durable storage, permissions, confirmation UX, multimodal input, concurrent tool execution, and product adapters. Those belong in separately reviewed milestones.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).


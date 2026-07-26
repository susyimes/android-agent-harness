# Android Agent Harness

[![CI](https://github.com/susyimes/android-agent-harness/actions/workflows/ci.yml/badge.svg)](https://github.com/susyimes/android-agent-harness/actions/workflows/ci.yml)

Android Agent Harness is a minimal, provider-neutral agent runtime extracted from the architectural seams of `mirror-android`. It keeps the reusable path and leaves product code behind:

```text
Android UI / JVM demo
        ↓
AgentHarnessRunner
        ↓
AgentOrchestrator
   ├─ AgentContextCoordinator → context adapters
   ├─ AgentProvider           → model/transport adapter
   ├─ AgentToolOrchestrator   → scoped registry → tool adapters
   └─ AgentSessionStore       → persistence adapter
```

## Quickstart — 60 seconds, no Android SDK

All you need is JDK 17. No API key, account, device, emulator, or Android SDK:

```sh
git clone https://github.com/susyimes/android-agent-harness.git
cd android-agent-harness
./gradlew :demo:run          # Windows: .\gradlew.bat :demo:run
```

The deterministic output proves the full context → provider → tool → provider → transcript path:

```text
OUTPUT=Harness result: ANDROID
PROVIDER_STEPS=2
TRACE=ContextLoaded -> ProviderInvoked(1) -> ToolExecuted(uppercase) -> ProviderInvoked(2) -> Completed(2)
TRANSCRIPT=USER:android | TOOL:ANDROID | ASSISTANT:Harness result: ANDROID
```

To supply different public demo text:

```sh
./gradlew :demo:run --args="hello android"
```

## Requirements

- JDK 17 for everything JVM (`harness-core`, `demo`, all tests)
- Android SDK Platform 36 only for building the Android `sample`
- No API key, account, device, NDK, or external service

## Validate the repository

JVM-only validation (no Android SDK required):

```sh
./gradlew auditProvenance :harness-core:test :demo:test
```

Full validation including the Android sample build (requires the Android SDK):

```sh
./gradlew checkM0
```

`checkM0` runs the provenance/privacy audit, the JVM unit/end-to-end tests, the demo tests, and `:sample:assembleDebug`.

To install the thin sample UI on a connected Android device or emulator:

```sh
./gradlew :sample:installDebug
```

## Modules

- `harness-core`: pure Kotlin/JVM contracts and four explicit runtime boundaries. It has no Android, network, JSON, storage framework, or coroutine dependency.
- `demo`: executable JVM proof of the full context → provider → tool → provider → persisted transcript path.
- `sample`: minimal Android input/run/result UI over the same core; no permission or network capability.

## Runtime contracts

`AgentHarnessRunner` is the minimal composition root. Applications provide adapters only for capabilities they need:

- `AgentProvider`: model or scripted decision boundary.
- `AgentContextProvider`: product-owned context sources. `AgentContextCoordinator` applies trust, priority, item-count, and content-size policy before a provider sees them.
- `AgentTool`: one executable capability. `AgentToolOrchestrator` exposes and executes the same profile-scoped catalog, preserving a single capability boundary.
- `AgentSessionStore`: in-memory by default; applications can adapt durable storage.
- `AgentClock` and `AgentIdGenerator`: production defaults are available, while deterministic fakes keep tests repeatable.

`AgentOrchestrator` saves the user turn, obtains a controlled context bundle, invokes the provider, executes ordered tool calls, persists tool results, reinvokes the provider, and saves final assistant text. Provider steps and per-step tool calls are bounded.

`DeterministicAgentHarness` remains as a source-compatible facade for the original bootstrap constructor.

See [extraction and compatibility](docs/EXTRACTION_AND_COMPATIBILITY.md) and [provenance/privacy inventory](docs/PROVENANCE_PRIVACY.md) before adapting a real provider, Android capability, or persistence layer.

## Deliberate minimum boundary

The minimal runtime omits streaming, network clients, JSON Schema, Android service lifecycles, durable storage, route gates, retrieval/reranking, EvidencePack, confirmation UX, multimodal input, concurrent tool execution, and product adapters. Those are extension points, not hidden dependencies.

## Roadmap

- **M1 (done)** — public runnable baseline: JVM demo, deterministic tests, provenance audit, CI.
- **M2** — optional OpenAI-compatible provider adapter so the same bounded loop runs against a real model with your own key.
- **M3** — controlled-context showcase scenarios: trust rejection, priority competition, and budget trimming made visible in the trace.
- **M4** — evaluation harness (baseline vs candidate comparison over a markdown workspace) and a minimal observe → act → finish device-loop contract with a high-risk pause protocol.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

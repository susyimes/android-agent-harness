# Android Agent Harness

[![CI](https://github.com/susyimes/android-agent-harness/actions/workflows/ci.yml/badge.svg)](https://github.com/susyimes/android-agent-harness/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/susyimes/android-agent-harness)](https://github.com/susyimes/android-agent-harness/releases)

<p align="center">
  <img src="docs/assets/demo.svg" alt="Animated demo: quickstart output, controlled-context scenarios, and the high-risk pause protocol" width="860">
</p>

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

## More demos

The same demo binary has four subcommands beyond the default scripted turn.

```sh
./gradlew :demo:run --args="scenarios"
```

Controlled-context showcase: five deterministic scenarios proving that what the provider may see (trust, priority, content budget), what it may call (tool profile), and how long it may run (step limit) are policy decisions recorded in the trace — untrusted context is dropped before the provider ever sees it, no matter how high its priority.

```sh
export OPENAI_API_KEY=...                        # your own key, read from the environment only
export OPENAI_BASE_URL=https://api.deepseek.com  # optional; DeepSeek example
export OPENAI_MODEL=deepseek-chat                # optional; DeepSeek example
./gradlew :demo:run --args="live"
```

Live: one bounded turn against a real OpenAI-compatible endpoint with three locally implemented tools. `OPENAI_BASE_URL` defaults to the OpenAI endpoint and `OPENAI_MODEL` to `gpt-4o-mini`. Keys never enter the repository: the credential is read from the environment at run time, and the `auditProvenance` task fails the build on any embedded credential-like assignment. Without a key set, the demo prints setup instructions and exits normally without any network traffic.

```sh
./gradlew :demo:run --args="eval"
```

Eval: governed evolution — a candidate overlay over a markdown workspace is compared against the baseline on fixed cases and is promoted only when it improves at least one case and regresses none; a single regression vetoes promotion.

```sh
./gradlew :demo:run --args="phone"
```

Phone: an observe → act → finish loop over a deterministic fake device. The Pay button is configured high-risk, so the first tap pauses for explicit approval instead of executing; the device receives exactly one tap, and only after the approval.

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
- `provider-openai`: zero-dependency adapter for OpenAI-compatible chat-completions endpoints (hand-written JSON codec, JDK HTTP client, environment-only credentials).
- `harness-eval`: governed-evolution evaluation — a candidate overlay over a markdown workspace must beat the baseline on fixed cases before promotion.
- `device-loop`: minimal observe → act → finish device-operation contract over a fake device, with an explicit high-risk pause protocol.
- `demo`: executable JVM proof of the full context → provider → tool → provider → persisted transcript path, plus the `scenarios`/`live`/`eval`/`phone` subcommands.
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

The core runtime (`harness-core`) omits streaming, network clients, JSON Schema, Android service lifecycles, durable storage, route gates, retrieval/reranking, EvidencePack, confirmation UX, multimodal input, concurrent tool execution, and product adapters. Those are extension points, not hidden dependencies — `provider-openai`, `harness-eval`, and `device-loop` are examples of such extensions living outside the core.

## Roadmap

- **M1 (done)** — public runnable baseline: JVM demo, deterministic tests, provenance audit, CI.
- **M2 (done)** — `provider-openai`: the same bounded loop against a real model with your own key (`live` subcommand).
- **M3 (done)** — controlled-context showcase: trust rejection, priority competition, budget trimming, tool-profile boundary, and bounded runs made visible in the trace (`scenarios` subcommand).
- **M4 (done)** — `harness-eval` (baseline vs candidate over a markdown workspace) and `device-loop` (observe → act → finish with a high-risk pause protocol; `eval` and `phone` subcommands).

See [ROADMAP.md](ROADMAP.md) for details and what comes next.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

# Android Agent Harness

[![CI](https://github.com/susyimes/android-agent-harness/actions/workflows/ci.yml/badge.svg)](https://github.com/susyimes/android-agent-harness/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/susyimes/android-agent-harness)](https://github.com/susyimes/android-agent-harness/releases)

<p align="center">
  <img src="docs/assets/demo.svg" alt="Android Agent Harness deterministic demo and safety protocol" width="860">
</p>

Android Agent Harness is a provider-neutral Kotlin SDK for building bounded agents on the JVM and Android. It combines a small runtime, cancellable host API, controlled context, typed tools, durable local sessions, and an optional accessibility-backed Phone Use component.

The repository also contains an installable Android reference app with:

- Home, Chat, Agent House, and Settings screens;
- offline demo, experimental Codex account login, Kimi Plan, Ark Plan, and custom OpenAI-compatible providers;
- per-provider model selection and Android Keystore-backed secret storage;
- persistent conversations and local Agent context;
- model-selected Phone Use with explicit human approval for high-risk actions;
- a real Stop control and an 80-step ceiling after Phone Use activates.

This is a bounded agent SDK and reference host, not a background autonomous-agent service. Heartbeat, Dream, persona proposals, proactive scheduling, and unattended background execution are intentionally not implemented.

## Architecture

```text
Android app / JVM host
        │
        ▼
AgentSdk ── run / events / cancel / session fence
        │
        ▼
AgentHarnessRunner
   ├─ AgentContextCoordinator ── trust, priority, size policy
   ├─ AgentProvider           ── model or scripted transport
   ├─ AgentToolOrchestrator   ── typed, profile-scoped tools
   └─ AgentSessionStore       ── transactional persistence

Optional components
   ├─ FileAgentSessionStore   ── app-private durable sessions
   ├─ Agent House             ── core files, skills, memories
   └─ AndroidPhoneAgent       ── model-routed accessibility tools
```

The core never grants capabilities from prompt text. The host owns provider credentials, context policy, tool registration, risk classification, approval UI, persistence location, and Android service enablement.

## Quickstart

The deterministic JVM demo needs only JDK 17:

```sh
git clone https://github.com/susyimes/android-agent-harness.git
cd android-agent-harness
./gradlew :demo:run
```

On Windows:

```powershell
.\gradlew.bat :demo:run
```

Expected output:

```text
OUTPUT=Harness result: ANDROID
PROVIDER_STEPS=2
TRACE=ContextLoaded -> ProviderInvoked(1) -> ToolExecuted(uppercase) -> ProviderInvoked(2) -> Completed(2)
TRANSCRIPT=USER:android | TOOL:ANDROID | ASSISTANT:Harness result: ANDROID
```

No Android SDK, account, API key, device, emulator, or network call is needed for this demo.

## Use it as an SDK

Development coordinates use group `dev.androidagent.harness` and version `0.5.0-SNAPSHOT`.

| Artifact | Type | Purpose |
| --- | --- | --- |
| `harness-core` | JAR | Contracts and synchronous bounded runtime |
| `agent-sdk` | JAR | Async host API, events, cancellation, transactions, sessions, Agent House |
| `provider-openai` | JAR | Compatible chat-completions and experimental Codex transports |
| `device-loop` | JAR | Host-neutral observe/act/finish contract |
| `device-loop-android` | AAR | Accessibility-backed Android device surface |
| `agent-sdk-android` | AAR | Safe model-routed Phone Use composition |

Publish all six artifacts to the repository-local Maven directory:

```sh
./gradlew publishSdk
```

Then add the local repository and the artifacts your host needs:

```groovy
repositories {
    maven { url = uri("../android-agent-harness/build/sdk-repository") }
    mavenCentral()
    google()
}

dependencies {
    implementation "dev.androidagent.harness:agent-sdk:0.5.0-SNAPSHOT"
    implementation "dev.androidagent.harness:provider-openai:0.5.0-SNAPSHOT"
    // Android Phone Use only:
    implementation "dev.androidagent.harness:agent-sdk-android:0.5.0-SNAPSHOT"
}
```

Run one turn:

```kotlin
val sdk = AgentSdk(sessionStore)
val handle = sdk.run(
    AgentRunRequest(
        sessionId = "chat-1",
        userInput = "Summarize the current task",
        providerFactory = OpenAiProviderFactories.compatible(providerConfig),
        contextProviders = contextProviders,
        tools = tools,
        listener = AgentRunListener { event -> render(event) }
    )
)

when (val outcome = handle.await()) {
    is AgentRunOutcome.Success -> showAnswer(outcome.result.output)
    is AgentRunOutcome.Failure -> showError(outcome.error)
    is AgentRunOutcome.Cancelled -> showStopped(outcome.reason)
}
```

Bind a Stop control to the same handle:

```kotlin
stopButton.setOnClickListener {
    handle.cancel("Stopped by user.")
}
```

Cancellation marks the run terminal, invokes the provider cancel hook, interrupts the worker, fences late results, and discards the incomplete conversation turn. External effects already performed by a tool or device action cannot be rolled back.

`AgentSdk` also guarantees:

- one active run per session id;
- isolated provider connections per run;
- structured Started, Trace, and Finished events;
- listener-failure isolation;
- transactional conversation commits;
- cancellation checks after provider I/O and before each SDK-controlled tool effect.

See [SDK Quickstart](docs/SDK_QUICKSTART.md) for the complete integration contract.

## Sessions and Agent House

`FileAgentSessionStore` is a small app-private persistence adapter:

```kotlin
val sessionStore = FileAgentSessionStore(File(appDataDirectory, "agent-sessions"))
val sdk = AgentSdk(sessionStore)

val recent = sdk.listSessions()
sdk.deleteSession(recent.first().id)
```

It hashes session ids before using them as file names, bounds decoded input, replaces complete files atomically where supported, and refuses to delete an active session. Session contents are not encrypted by this adapter.

`FileAgentHouseRepository` provides eight core Markdown files plus skills and daily memories:

```kotlin
val house = FileAgentHouseRepository(File(appDataDirectory, "agent-house"))
val houseContext = AgentHouseContextProvider(house)
val houseTools = AgentHouseWriteTools(house).tools()

sdk.run(
    request.copy(
        contextProviders = listOf(houseContext),
        tools = request.tools + houseTools
    )
)
```

The Agent House write boundary is deliberate:

- `agent_memory_append` writes idempotent daily notes with `AGENT` trust and source metadata;
- `agent_skill_write` creates or revises only disabled Agent drafts;
- an Agent draft enters later context only after the user reviews and enables it;
- Agent writes cannot silently overwrite an enabled or user-owned skill;
- both write tools reject credential-like content and enforce size/count quotas;
- House text cannot grant tools, approve risk, or override host policy.

The Android app lets the user edit identity and collaboration preferences, review Agent-written memories, and enable or disable skill drafts. Skills and memories are written by the Agent during conversation rather than through a manual “create” shortcut.

## Android reference app

Build and install the debug app:

```sh
./gradlew :sample:assembleDebug
./gradlew :sample:installDebug
```

Requirements:

- JDK 17;
- Android SDK Platform 36 to build Android modules;
- Android 8.0 / API 26 or newer to install the sample.

The APK is debug-signed and intended for sideloading and development. CI builds a `sample-debug-apk` artifact from `main`, and releases may attach a ready-to-install APK.

### Providers

| Provider | Authentication | Models |
| --- | --- | --- |
| Offline demo | None | Deterministic local scripted provider |
| Codex (experimental) | Browser PKCE, device-code fallback | Responses transport selected by the sample |
| Kimi Plan | Coding Plan API key | Kimi K3, Kimi K2.7 Code, Kimi K2.6 |
| Ark Plan | Plan API key | All presets listed below |
| Custom compatible | Host-configured credential | Any supplied compatible model id |

Ark Plan presets:

```text
doubao-seed-2.0-pro
doubao-seed-2.0-lite
doubao-seed-2.0-mini
doubao-seed-2.1-turbo
doubao-seed-evolving
glm-5.2
kimi-k3
kimi-k2.7-code
kimi-k2.6
minimax-m3
minimax-m2.7
deepseek-v4-pro
deepseek-v4-flash
```

Presets are defaults, not SDK allow-lists. A host can supply a newer compatible model id directly.

Provider endpoints used by the sample:

- Kimi Plan: `https://api.kimi.com/coding/v1`
- Ark Plan: `https://ark.cn-beijing.volces.com/api/plan/v3`

API keys and Codex tokens are stored separately with Android Keystore-backed encryption. The experimental Codex login is an isolated sample adapter, not an officially documented third-party Android authentication surface.

## Model-routed Phone Use

There is no fixed Chat/Phone mode selector and no keyword router. An online model sees the optional device-tool descriptions during normal planning and decides whether the task needs them.

The execution policy is:

1. A normal turn starts with an 8-step budget.
2. A direct model answer stays a normal chat turn.
3. The first actual `device_observe`, `device_act`, or `device_finish` call activates Phone Use.
4. Activation is sticky for that turn, expands the ceiling to 80 steps, and tightens execution to one selected tool call per provider step.
5. The user can press Stop at any time.

`AgentHarnessTraceEvent.ToolLoopActivated` records the transition for the host.

Phone Use currently exposes semantic accessibility operations:

- observe the foreground accessibility tree;
- tap a node;
- enter text into an editable node;
- press Back;
- swipe;
- scroll until text is visible;
- launch an app by display name or package;
- wait for the screen to settle;
- finish with evidence visible in the current screen snapshot.

Pressing Home is refused because it invalidates the observed task chain. `launch_app` requires an explicit `app` argument; the model cannot omit it and expect the tool layer to infer a package.

### Phone Use safety boundary

- The user must enable the accessibility service manually in Android Settings.
- The app reads the tree only while a user-started turn has activated Phone Use.
- Snapshots are text-only: visible accessibility labels, roles, text, and view-id suffixes. There is no screenshot, screen recording, or vision fallback.
- High-risk controls such as payment, transfer, purchase, delete, uninstall, or order confirmation pause for a human approval overlay.
- Model-supplied confirmation flags are ignored. Only the user's tap on Allow approves the action.
- Deny, timeout, or a missing approval surface is refusal.
- Risk matching is best effort and keyword/context based; unlabeled, canvas-drawn, foreign-language, and WebView-heavy interfaces can evade semantic matching.
- Disabling the service immediately removes observation and action access.

An Android host must supply both a `RiskPolicy` and a real human-backed `ApprovalGate`. The SDK has no allow-all production default.

## Modules

| Module | Responsibility |
| --- | --- |
| `harness-core` | Dependency-light contracts, context policy, bounded orchestration, trace events |
| `agent-sdk` | Host facade, lifecycle, stop semantics, persistence, Agent House |
| `provider-openai` | OpenAI-compatible and experimental Codex transports |
| `harness-eval` | Baseline-vs-candidate governed evaluation |
| `device-loop` | Host-neutral device-operation and high-risk pause protocol |
| `device-loop-android` | Accessibility tree mapping, Android actions, approval overlay |
| `agent-sdk-android` | Model-routed Phone Use composition |
| `sdk-consumer-smoke` | Independent public-API consumer verification |
| `demo` | Deterministic, live, context-policy, evaluation, and fake-phone demos |
| `sample` | Installable Android reference host |

## Verification

JVM and SDK packaging gate:

```sh
./gradlew checkSdk
```

Full repository gate, including Android sample tests, lint, and APK assembly:

```sh
./gradlew checkM0
```

Provenance and credential audit:

```sh
./gradlew auditProvenance
```

The CI workflow runs the repository gates on every change to `main`.

## Storage and privacy

- Provider secrets in the sample use Android Keystore-backed encryption.
- Chat sessions and Agent House content live in the app-private directory but are plaintext at the file-adapter layer.
- Android backup is disabled for the sample.
- Device snapshots are sent only to the selected online provider after Phone Use activates.
- Credentials must never be placed in Agent House content, logs, source files, or demo arguments.
- A debuggable APK is not a production secret boundary; use low-limit, revocable development credentials.

Replace the file adapters with an encrypted database when a product needs encryption, schema migration, retention, cross-process locking, search, or managed backup.

## Current scope

Implemented:

- bounded provider → tool → provider execution;
- controlled context with trust, priority, and size limits;
- typed provider tool schemas;
- cancellable transactional turns;
- durable sessions;
- Agent-written memory and review-gated skill drafts;
- provider/model selection;
- Android accessibility Phone Use with human approval;
- installable reference UI.

Not implemented:

- streaming responses;
- multimodal or screenshot input;
- concurrent tool execution;
- background autonomy;
- Heartbeat or Dream loops;
- persona proposals or proactive scheduling;
- encrypted/database-backed session and House adapters;
- remote Maven Central publication.

See [Roadmap](ROADMAP.md), [Extraction and Compatibility](docs/EXTRACTION_AND_COMPATIBILITY.md), and [Provenance and Privacy](docs/PROVENANCE_PRIVACY.md) for design boundaries and future work.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

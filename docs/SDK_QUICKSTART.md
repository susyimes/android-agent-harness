# SDK quickstart

The SDK has two layers:

- `agent-sdk` is a pure JVM host facade over the bounded harness runtime. It also contains optional file-backed session and Agent House adapters.
- `agent-sdk-android` is an optional Android composition. It adds model-routed, accessibility-backed Phone Use, but does not own authentication, UI, secret storage, or service enablement.

Provider and capability adapters stay separate so an application only ships what it uses.

## Artifacts

The current development coordinates use group `dev.androidagent.harness` and version `0.5.0-SNAPSHOT`.

| Artifact | Packaging | Purpose |
| --- | --- | --- |
| `harness-core` | JAR | Low-level contracts and synchronous bounded runtime |
| `agent-sdk` | JAR | Host lifecycle, events, cancellation, concurrency, transactions |
| `provider-openai` | JAR | Compatible chat-completions and experimental Codex transports |
| `device-loop` | JAR | Host-neutral observe/act/finish capability |
| `device-loop-android` | AAR | Accessibility-backed Android device surface |
| `agent-sdk-android` | AAR | Safe model-routed Phone Use composition |

Build all publications into the repository-local Maven directory:

```sh
./gradlew publishSdk
```

For a Gradle consumer using that directory:

```groovy
repositories {
    maven { url = uri("../android-agent-harness/build/sdk-repository") }
    mavenCentral()
    google()
}

dependencies {
    implementation "dev.androidagent.harness:agent-sdk:0.5.0-SNAPSHOT"
    implementation "dev.androidagent.harness:provider-openai:0.5.0-SNAPSHOT"
}
```

Use `agent-sdk-android` as well when the host enables Phone Use. It carries the SDK and Android device-loop API transitively.

## Run one turn

The host owns the SDK lifetime and supplies a new provider connection factory rather than a singleton network client:

```kotlin
import dev.androidagent.harness.provider.openai.OpenAiCompatibleConfig
import dev.androidagent.harness.provider.openai.OpenAiProviderFactories
import dev.androidagent.harness.sdk.AgentRunEvent
import dev.androidagent.harness.sdk.AgentRunListener
import dev.androidagent.harness.sdk.AgentRunOutcome
import dev.androidagent.harness.sdk.AgentRunRequest
import dev.androidagent.harness.sdk.AgentSdk

val providerFactory = OpenAiProviderFactories.compatible(
    OpenAiCompatibleConfig(
        baseUrl = "https://example.invalid/v1",
        model = "your-model",
        keyValue = credentialFromSecureStorage,
        parallelToolCalls = false
    )
)

val sdk = AgentSdk()
val handle = sdk.run(
    AgentRunRequest(
        sessionId = "chat-1",
        userInput = "Summarize the task",
        providerFactory = providerFactory,
        listener = AgentRunListener { event ->
            when (event) {
                is AgentRunEvent.Started -> showRunning(event.runId)
                is AgentRunEvent.Trace -> appendTrace(event.event)
                is AgentRunEvent.Finished -> renderOutcome(event.outcome)
            }
        }
    )
)

when (val outcome = handle.await()) {
    is AgentRunOutcome.Success -> showAnswer(outcome.result.output)
    is AgentRunOutcome.Failure -> showError(outcome.error)
    is AgentRunOutcome.Cancelled -> showStopped(outcome.reason)
}
```

Callbacks are synchronous and may arrive from the caller or SDK worker thread. A UI host must marshal them onto its UI dispatcher. Listener exceptions are isolated and never change the Agent result.

Close `AgentSdk` with the application/component that owns it. Closing cancels all active runs.

## Stop semantics

Bind the host Stop control to the returned handle:

```kotlin
stopButton.setOnClickListener {
    handle.cancel("Stopped by user.")
}
```

The first accepted call returns `true`; later calls return `false`. Cancellation:

1. marks the run terminal immediately for the host;
2. invokes the turn-scoped provider transport's cancel hook;
3. interrupts the worker and checks an independent cancellation signal after provider I/O and before each tool execution;
4. discards the staged conversation turn;
5. ignores any late provider result or event.

A provider factory should make its cancel hook idempotent. Uncooperative custom providers cannot be forcibly killed by the JVM, but late returns cannot commit session state or begin another SDK-controlled tool action.

## Sessions and persistence

Pass a host-owned `AgentSessionStore` to `AgentSdk` for durable history. Only one run may use a session id at a time; a concurrent attempt throws `AgentSessionBusyException`.

Each run sees a transactional view:

- success commits the complete user/tool/assistant turn;
- provider failure, protocol failure, limit exhaustion, and cancellation preserve the last committed session;
- a durable-store commit failure completes as `AgentRunErrorKind.PERSISTENCE` and releases the session instead of stranding the handle;
- external side effects already performed by a tool are not a database transaction and cannot be rolled back.

For a small single-process app, the bundled file catalog is enough to make that contract concrete:

```kotlin
val sessionStore = FileAgentSessionStore(File(appDataDirectory, "agent-sessions"))
val sdk = AgentSdk(sessionStore)

val recent = sdk.listSessions()
sdk.deleteSession(recent.first().id)
// sdk.clearSessions()
```

`FileAgentSessionStore` hashes arbitrary session ids before using them as file names, bounds decoded input, and replaces complete files atomically where the file system supports it. It is synchronized for one process and intentionally does **not** encrypt message content. Point it at an app-private directory, and replace it with a product database when you need encryption, schema migration, retention, cross-process locking, search, or backup policy.

The management calls refuse to delete an active session, preserving the same per-session concurrency fence as `run`.

## Agent House context

`FileAgentHouseRepository` is a portable local workspace for eight generic core files, Agent-written skill drafts, and dated memories:

```kotlin
val house = FileAgentHouseRepository(File(appDataDirectory, "agent-house"))
house.renameHouse("My Agent")

val houseContext = AgentHouseContextProvider(house)
val houseWriteTools = AgentHouseWriteTools(house).tools()
sdk.run(
    request.copy(
        contextProviders = listOf(houseContext),
        tools = request.tools + houseWriteTools
    )
)
```

`agent_memory_append` performs an idempotent append and records the memory as Agent-trust context rather than silently turning it into a user fact. `agent_skill_write` creates or revises only a disabled Agent draft; the host or user must enable it before it appears in later model context. Both reject credential-like content.

The context adapter uses logical ids rather than storage paths, includes enabled skills only, orders content deterministically, preserves application/user/Agent provenance, and applies per-item plus total character budgets. House text cannot grant a tool or approval: the host's context policy, tool profile, risk policy, and approval gate remain authoritative.

## Tools and typed arguments

Tool code still receives `Map<String, String>`, keeping the core independent of a JSON library. Non-string provider values are normalized: scalar values become text and arrays/objects become JSON text.

Describe provider-facing types with `AgentToolArgumentSchema`:

```kotlin
val spec = AgentToolSpec(
    name = "set_volume",
    description = "Sets media volume.",
    requiredArguments = setOf("level"),
    argumentSchemas = mapOf(
        "level" to AgentToolArgumentSchema(
            type = AgentToolArgumentType.INTEGER,
            description = "Volume from 0 through 100."
        )
    )
)
```

Both bundled model transports render string, integer, number, boolean, array, nested object, enum, required-property, and additional-property metadata.

## Provider presets and credentials

`OpenAiEndpointPresets.KIMI_PLAN` and `OpenAiEndpointPresets.ARK_PLAN` expose reusable endpoint/model defaults. Ark includes every Plan model currently supported by the sample. Presets are not allow-lists: a host may pass a newer model id directly through `OpenAiCompatibleConfig`.

Credentials remain host data. Load them from environment variables, Android Keystore-backed storage, or another product-owned secret service, and do not put them in logs or persisted Agent context.

`OpenAiProviderFactories.codex` wraps the experimental Responses adapter. The Android sample's browser/device login remains an isolated example rather than part of the SDK contract because third-party Android Codex login is not a documented official integration surface. A production host must own credential acquisition, refresh, logout, and policy review.

## Android Phone Use

`AndroidPhoneAgent` exposes normal host tools and the three device tools to the provider together. It does not classify the user's text. The model's first actual `device_observe`, `device_act`, or `device_finish` call activates a sticky Phone Use loop:

```kotlin
val request = AndroidPhoneAgent(
    surface = deviceSurface,
    configuration = AndroidPhoneAgentConfiguration(
        riskPolicy = productRiskPolicy,
        approvalGate = humanApprovalGate
    )
).request(
    sessionId = sessionId,
    userInput = userInput,
    providerFactory = providerFactory,
    additionalTools = normalHostTools
)
```

The initial budget defaults to 8 steps and 4 calls per step. A direct model answer never activates Phone Use. After a device-tool call, the ceiling expands to 80 and execution tightens to one selected call per step, with device calls preferred whenever a provider returns mixed calls. `AgentHarnessTraceEvent.ToolLoopActivated` makes this transition visible to the host.

`AndroidPhoneAgentConfiguration` deliberately requires both:

- a `RiskPolicy` defining what the product considers high risk;
- a real human-backed `ApprovalGate`.

There is no allow-all production default. The activated composition defaults to one action per provider step, an 80-step ceiling, Home disabled, and a semantic accessibility snapshot rather than screenshots.

The host must also:

- declare and explain its accessibility service;
- let the user enable it manually in Android settings;
- display provider/data disclosure before enabling Phone Use;
- surface the approval UI over the app being controlled;
- disable sending when the selected provider is not ready;
- use `AndroidPhoneAgent.isAvailable()` to render permission status. A host may still start a normal model turn while unavailable; an attempted device tool receives a structured permission failure.

Use `AndroidPhoneAgent.fromHarnessAccessibilityService(...)` with the bundled service, or construct `AndroidPhoneAgent` with a product-owned `DeviceSurface`.

## Verification

```sh
./gradlew checkSdk
```

This runs core/SDK/provider/device tests, Android unit tests and release lint, the independent consumer smoke test against the locally published Maven coordinates, the provenance audit, and local JAR/AAR publication. `checkM0` additionally builds, tests, and lints the installable sample.

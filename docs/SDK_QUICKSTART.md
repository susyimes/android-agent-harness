# SDK quickstart

Android Agent Harness v0.5.0 is split into platform-neutral JVM artifacts and optional Android AARs. A host can start with `agent-sdk` and add only the context, state, scheduling, feedback, data, Phone Use, Web4Agent, or voice capabilities it needs.

The central rule is simple: there is one run kernel. User chat and scheduled work both enter `AgentSdk`; adapters never advance a second provider/tool loop.

The JVM artifacts require JDK 17. Every Android AAR and the sample use
`minSdk 29` (Android 10) and compile against API 36.

## 1. Publish or resolve the artifacts

The repository uses group `dev.androidagent.harness` and version `0.5.0`.

```sh
./gradlew publishSdk
```

This writes JARs, AARs, sources, Gradle metadata, and POM files under `build/sdk-repository`.

```groovy
repositories {
    maven { url = uri("../android-agent-harness/build/sdk-repository") }
    google()
    mavenCentral()
}

dependencies {
    implementation "dev.androidagent.harness:agent-sdk:0.5.0"
    implementation "dev.androidagent.harness:context-engine:0.5.0"
    implementation "dev.androidagent.harness:agent-state:0.5.0"
    implementation "dev.androidagent.harness:provider-openai:0.5.0"
}
```

Optional artifacts:

| Artifact | Use it when the host needs |
| --- | --- |
| `agent-approval` | General durable/external/device effect approval |
| `agent-scheduling` | Schedule, occurrence, lease, Cron, or LongTask semantics |
| `agent-feedback` | Heartbeat, Dream, Proactive, Home Brief, or Self Check |
| `harness-eval` | Baseline/candidate evaluation |
| `device-loop` | A strict platform-neutral device protocol |
| `agent-sdk-android` | Android Phone Use composition |
| `agent-permission-android` | Android capability and permission snapshots |
| `agent-data-android` | Stats, Todo, State/House, file, location, calendar, notification data |
| `agent-scheduling-android` | WorkManager, Receiver, foreground carrier, and Android stores |
| `agent-voice-android` | STT, ephemeral audio, TTS, and transcript contracts |
| `device-loop-android` | Accessibility, visual observation, sensors, and overlay approval |
| `web4agent-android` | Visible WebView sessions, DOM reads/inspection, JavaScript, actions, console, and capture |

The Android AAR manifests stay minimal. The host declares only the services and permissions it actually enables.

## 2. Run one bounded turn

The host owns the SDK lifetime and supplies a provider factory. The factory must create a run-scoped connection; do not share a mutable streaming connection across concurrent runs.

```kotlin
val providerFactory = OpenAiProviderFactories.compatible(
    OpenAiCompatibleConfig(
        baseUrl = "https://example.invalid/v1",
        model = "your-model",
        keyValue = credentialFromSecureStorage,
        parallelToolCalls = false
    )
)

val sdk = AgentSdk(sessionStore)
val handle = sdk.run(
    AgentRunRequest(
        sessionId = "chat-1",
        userInput = "Summarize the task",
        providerFactory = providerFactory,
        listener = AgentRunListener { event ->
            mainDispatcher.execute { render(event) }
        }
    )
)

when (val outcome = handle.await()) {
    is AgentRunOutcome.Success -> showAnswer(outcome.result.output)
    is AgentRunOutcome.Failure -> showError(outcome.error)
    is AgentRunOutcome.Cancelled -> showStopped(outcome.reason)
    is AgentRunOutcome.Expired -> showExpired(outcome.reason)
}
```

Listener callbacks are synchronous and may arrive on the caller or SDK worker thread. Listener failures are isolated from the run result.

Close the `AgentSdk` instance with the application or component that owns it. Closing cancels active runs.

## 3. Define the run policy

`AgentRunPolicy` is host-owned and records why a run exists and which bounded authority it has.

```kotlin
val policy = AgentRunPolicy(
    trigger = AgentRunTrigger.USER,
    budget = AgentRunBudget(
        maxProviderSteps = 8,
        maxToolCalls = 24,
        maxWallClockMillis = 2 * 60_000L,
        maxRepeatedFailures = 3,
        maxInputTokens = 16_000,
        maxOutputTokens = 4_000
    ),
    toolProfileId = "chat-safe",
    contextPolicyId = "ccp-v2",
    writePolicyId = "candidate-only",
    approvalPolicyId = "conservative"
)
```

The run ends when any active bound is exceeded. The sample uses an 8-step normal ceiling and expands to at most 80 provider steps only after a real device tool call activates Phone Use.

## 4. Stop and deadline semantics

```kotlin
stopButton.setOnClickListener {
    handle.cancel("Stopped by user.")
}
```

The first accepted cancellation returns `true`; later calls return `false`. Cancellation:

1. publishes a terminal host outcome;
2. invokes the provider cancel hook;
3. interrupts the worker;
4. checks the independent cancellation signal after provider I/O and before every SDK-controlled effect;
5. rejects late streaming deltas and results;
6. discards the staged conversation turn.

An effect that already occurred outside the session transaction is recorded but cannot be magically rolled back.

## 5. Compile context through CCP V2

Register sources by stable id and pass request-specific options:

```kotlin
val request = AgentRunRequest(
    sessionId = "chat-1",
    userInput = "What needs attention today?",
    providerFactory = providerFactory,
    contextSources = listOf(
        NamedContextSource("approved-state", approvedStateSource),
        NamedContextSource("todo", todoSource),
        NamedContextSource("permissions", permissionSource)
    ),
    contextEngineOptions = AgentContextEngineOptions(
        requestedSourceIds = setOf("approved-state", "todo", "permissions"),
        requiredCapabilities = setOf("todo-read"),
        privacyCeiling = ContextPrivacy.INTERNAL,
        tokenBudget = 6_000,
        outputReserve = 1_500
    )
)
```

The engine:

- derives a `ContextNeedSpec`;
- collects typed candidates;
- filters by requested source, capability, privacy, and validity;
- resolves logical duplicates and conflicts by authority and revision;
- selects within item and token budgets;
- emits an auditable `EvidencePack`;
- asks `RouteGate` whether to continue, ask the user, answer locally, or block;
- renders host-policy context separately from untrusted data.

`RouteGate` is not an effect approval gate.

## 6. Register typed tools and approvals

A tool declares capability independently from model input:

```kotlin
val capability = AgentToolCapability(
    sideEffect = AgentToolSideEffect.LOCAL_DURABLE_WRITE,
    risk = AgentToolRisk.MEDIUM,
    dataScopes = setOf("todo"),
    idempotency = AgentToolIdempotency.IDEMPOTENT_WITH_KEY,
    targetArgumentNames = setOf("todo_id")
)
```

For a mutating call, construct an `AgentEffectIntent` from the exact target and canonical argument hash, then ask `AgentApprovalCoordinator` before execution. The returned token is valid only for the bound run, call, hash, side-effect scope, and expiry.

The conservative default policy is:

- no effect, local read, and local draft write: no runtime approval;
- local durable write, external write, and device action: explicit approval;
- policy errors or missing approval UI: deny.

Hosts may replace that policy. The sample app deliberately exposes a persisted product setting with No approval (the sample default), Risk-based, and Strict modes. No approval returns `NOT_REQUIRED` for every effect; it does not bypass Android runtime, special, or accessibility permissions.

Tool output is normalized to `AgentToolResultEnvelope`. Keep provider-visible summaries bounded; place large or sensitive bytes behind an opaque `AgentRawPayloadStore` reference with a TTL.

## 7. Persist sessions and traces

```kotlin
val sessions = FileAgentSessionStore(File(appDataDir, "agent-sessions"))
val traces = TraceSink { event -> auditJournal.append(event) }
val sdk = AgentSdk(sessions)
```

Only one run may use a session id at a time. Successful runs commit the staged user/tool/assistant turn transactionally. Failure, cancellation, expiry, and limit exits do not commit it.

`AgentEvent` includes lifecycle transitions, context/route decisions, provider start/delta/complete, tool and approval events, device activation, checkpoints, candidates, and terminal state. It never exposes hidden model reasoning.

Use `AgentTraceReplayEvaluator` to check an exported event list without re-running a provider or effect.

## 8. Govern memory, skill, and persona state

Use `AgentStateVault` as the durable state boundary. Models write through candidate sinks:

- `MemoryCandidateSink`;
- `SkillDraftSink`;
- `PersonaProposalSink`.

A durable asset change follows:

```text
candidate
  → validation and dedupe
  → evaluation
  → exact candidate hash approval
  → promoted revision
  → optional governed rollback revision
```

Unapproved candidates and experimental revisions never enter `AgentApprovedStateContextSource`.

`AgentStateMaintenance` provides export, bounded observational retention, and explicit domain deletion. Automatic retention does not remove durable documents, candidates, evaluations, effects, or revision history.

The legacy Agent House file adapter remains available. `AgentHouseStateMigrator` proposes existing Agent memory as candidates rather than silently treating it as approved State.

### Remote AgentBrief

`agent-state` can create a compact AgentBrief immediately before CCP selects
context. The remote call uses a fresh connection from the same provider
factory; it has no tools and cannot write State Vault directly.

```kotlin
val briefSource = RemoteAgentBriefContextSource(
    vault = stateVault,
    providerFactory = providerFactory,
    options = RemoteAgentBriefOptions(timeoutMillis = 4_000)
)

val request = AgentRunRequest(
    sessionId = "chat-1",
    userInput = "Continue the current task",
    providerFactory = providerFactory,
    contextSources = listOf(
        NamedContextSource("remote-agent-brief", briefSource)
    )
)
```

The compiler always has a deterministic local summary. A successful
`FinalText` replaces that summary within the configured character budget.
Tool calls, blank output, provider errors, and timeouts use the local summary.
On timeout the connection is cancelled and any late text is discarded before
State Vault persistence.

## 9. Schedule reliable work

`ScheduleSpec` describes cadence, timezone, constraints, execution window, missed-run policy, revision, delivery policy, reason, and policy ids. `GovernedScheduleService` applies or deletes an exact revision through the approval protocol.

`agent-scheduling-android` maps one occurrence to one unique WorkManager job. At dispatch it rechecks:

- schedule existence, enabled state, and revision;
- execution window and runtime constraints;
- occurrence lease and duplicate-completion state;
- current permission/credential/policy authorization snapshot.

The Worker calls a host `PeriodicRunner`; the sample implementation enters `AgentSdk`. It never embeds another Agent loop.

Cron is a scheduled run target. LongTask uses bounded bursts and durable checkpoints. Stop cancels the current run, marks the checkpoint terminal, and prevents a hidden re-enqueue for that occurrence.

## 10. Feedback and proactive policy

`agent-feedback` is side-effect free. It produces typed signals, findings, opportunities, activation requests, and local summaries.

- Heartbeat inspects typed Todo, permission, candidate, and failure counts.
- Dream reflects on bounded outcome/candidate history and may emit a pending reflection candidate.
- Proactive scores evidence-backed opportunities and applies initiative, quiet hours, and daily cap.
- Home Brief and Self Check have deterministic local fallbacks.

The host may notify or start a run only after its own delivery and approval policy permits it.

## 11. Strict Phone Use

`StrictDeviceProtocol` enforces:

```text
observe(snapshot)
  → exactly one validated action
  → observe(snapshot)
  → action or finish(with visible evidence)
```

An action invalidates the snapshot even when the platform reports failure. Stale ids, action-before-observe, consecutive actions, and finish-without-evidence are protocol errors.

`AndroidPhoneAgent` exposes accessibility tools only when the host enables the service and registers them in the selected profile. Device effects use the same approval protocol. A host policy may allow them directly or require a human-backed Android surface; the sample's setting applies the selected policy to the generic coordinator bound to the returned request.

## 12. Add Web4Agent

Add the optional AAR:

```groovy
implementation "dev.androidagent.harness:web4agent-android:0.5.0"
```

Build one process-local runtime and register its complete tool bundle:

```kotlin
val webRuntime = Web4AgentRuntime.getInstance(applicationContext)
val webPayloads = EphemeralWebPayloadStore()
val webTools = Web4AgentToolSet(
    runtime = webRuntime,
    approvals = approvalCoordinator,
    rawPayloadStore = webPayloads
)

val request = androidPhoneAgent.request(
    sessionId = sessionId,
    userInput = userInput,
    providerFactory = providerFactory,
    additionalContextProviders = listOf(Web4AgentGuidance.contextProvider()),
    additionalTools = webTools.tools(),
    additionalActivationToolNames = webTools.toolNames
)
```

The nine registered tools are `web4agent_open`, `observe`, `read`, `inspect`,
`eval`, `act`, `console`, `capture`, and `finish` (all names retain the
`web4agent_` prefix). They bind to `AgentToolInvocation.sessionId`; opening a
page launches a visible `Web4AgentBrowserActivity` for that same session.

The default runtime allows HTTPS and inline HTML, JavaScript, and DOM storage.
It blocks cleartext HTTP, mixed content, local file/content access, third-party
cookies, popups, and autoplay. Install a different
`Web4AgentConfiguration` before the first session only when the host explicitly
accepts the wider boundary.

`web4agent_act` and `web4agent_eval` authorize and consume an exact approval
token before execution. Page content is untrusted external evidence; include
`Web4AgentGuidance.contextProvider()` or equivalent host policy so DOM text and
console messages cannot override user/host instructions. Structured readers
apply best-effort password/common-secret field redaction, but hosts should not
treat that as general DLP. Session ids isolate controllers and WebViews, not
the host app's standard Android WebView cookie/site-storage profile.

Capture bytes are written to the supplied `AgentRawPayloadStore` with exact
run/session/tool-call scope and TTL. They are not inserted into provider-visible
text.

## 13. Streaming, attachments, and voice

The OpenAI-compatible transport supports true SSE deltas. The SDK emits bounded `ProviderDelta` events and accepts only one terminal response.

`AttachmentRef` carries an opaque id, media type, display metadata, and provider payload. The host owns file selection, temporary access, size limits, and cleanup.

`agent-voice-android` provides:

- Android speech-to-text;
- ephemeral audio recording;
- a streaming transcription port;
- Android text-to-speech;
- an optional transcript repository.

Raw audio is not persisted by default.

## 14. Data and deletion

Use separate storage domains for sessions, House, State, Todo, schedules/checkpoints, credentials, and operational journals. Deleting one domain must not silently delete another.

The sample demonstrates:

- explicit export initiated by the user;
- bounded retention for observational State;
- exact revision/hash approval for Todo, State, House, and schedule deletion/reset;
- cancellation of future WorkManager work;
- separate credential deletion in provider settings.

## 15. Verification

```sh
# Unit, ABI, independent Maven consumer, AAR lint/publication, and audit gate
./gradlew checkSdk

# Full repository and sample build gate
./gradlew checkM0

# Connected-device UI/navigation gate
./gradlew checkConnectedSample
```

Kotlin 2.2.21 exposes the ABI tasks as `checkLegacyAbi` / `updateLegacyAbi`; `checkSdkAbi` aggregates the committed baselines. Android AAR compatibility is checked through a separate Maven-coordinate consumer because this Kotlin version produces no useful Android ABI dump.

Never run `updateLegacyAbi` merely to make a compatibility failure disappear. Review the API diff and versioning impact first.

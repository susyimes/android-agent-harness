# Extraction boundary and compatibility map

## Minimal boundary

This repository is an independently authored extraction of architectural seams, not a source-code fork. It keeps only the path needed to prove a bounded Android-agent turn:

1. `AgentHarnessRunner` wires explicit adapters and starts a run.
2. `AgentOrchestrator` saves the user message and requests a controlled context bundle.
3. `AgentContextCoordinator` gathers context adapters, rejects duplicate ids, sorts by declared priority, applies trust policy, and enforces item/content budgets.
4. The provider receives an immutable session snapshot, selected context, and the profile-scoped tool catalog.
5. `AgentToolOrchestrator` validates ordered calls and executes only tools visible in the selected profile.
6. Tool results are appended to the session before the provider is invoked again.
7. Final assistant text is saved, or a configured provider/tool-call limit ends the run.

The core is Kotlin/JVM so every runtime boundary can be tested without Android state. The JVM demo is an executable proof. The Android sample is an installable consumer of the same core: it adds exactly the capabilities its features need — the `INTERNET` permission for the user-configured endpoint, app-private preferences for user-entered settings, and the opt-in accessibility service from `device-loop-android` for phone mode — while `harness-core` itself stays free of all of them.

## Current alignment map

This map was refreshed on 2026-07-18 against the then-current private `mirror-android` revision (pinned in a private audit log). It describes architectural responsibility, not package, API, source, binary, or serialized-data compatibility. The `device-loop-android` and upgraded `sample` rows were added on 2026-07-27 from the same responsibility map, without re-opening the reference.

| Extracted boundary | Current `mirror-android` seam | Minimum retained responsibility |
| --- | --- | --- |
| `AgentHarnessRunner` | Application/harness composition around the Agent path | Construct the portable runtime from explicit provider, context, tool, session, time, and id adapters. It does not copy House evaluation or Android lifecycle behavior. |
| `AgentOrchestrator` | `agent.orchestrator.AgentOrchestrator` | Own one run: session mutation, context handoff, provider/tool loop, final persistence, and bounded failure. Streaming, retry UI, debug persistence, memory writeback, and Android cancellation are omitted. |
| `AgentContextCoordinator` | `agent.context.AgentContextCoordinator` | Keep context collection as a distinct control-plane boundary with trust, priority, and budgets. NeedSpec, retrieval, reranking, EvidencePack, prompt rendering, and route gate remain product extensions. |
| `AgentProvider` | `AgentChatClient` and its provider/model calls | Translate selected context, session messages, and tool specs to/from a model transport. Credentials and endpoints remain outside the core. |
| `AgentToolOrchestrator` | `agent.tool.AgentToolOrchestrator` | Keep the provider-visible catalog and executable capability profile consistent; validate bounded ordered calls and return inspectable results. Phone loops, formula tools, envelopes, images, and progress streaming are omitted. |
| `AgentTool` / `AgentToolRegistry` / `AgentToolProfile` | `AgentTool`, `AgentContextAwareTool`, `AgentToolRegistry`, and `AgentToolProfile` | Adapt individual capabilities and make the profile-scoped registry the runtime authority boundary. |
| `AgentSessionStore` | `MirrorChatRepository`, `ChatSession`, and `ChatMessage` | Load/save immutable session snapshots. Durable migration, retention, deletion, and atomic I/O belong to an application adapter. |
| JVM `demo` | Debug/chat/harness entry surfaces | Prove the portable flow and show only input, run, result, trace, and transcript. No product resources, navigation, permissions, or branding are retained. |
| Android `sample` (installable chat + phone mode) | The credential-configured live chat surface over the harness | Let the user configure endpoint, model, and credential at run time (app-private preferences, nothing bundled), run live or offline scripted turns through the same composition root, and opt into the device loop with a dialog approval gate and an app-declared risk policy. No product resources, navigation, branding, accounts, or provider defaults are reproduced. |
| `provider-openai` module | The chat-client/provider transport boundary | Adapt the provider contract to an OpenAI-compatible endpoint with environment-only credentials. Product endpoints, headers, provider defaults, and OAuth flows are not reproduced. |
| `harness-eval` module | The house baseline/candidate evaluation responsibility | Evaluate candidate overlays of interpretable markdown assets against a baseline on fixed cases before promotion. The product workspace format, memory files, and promotion UI are not reproduced. |
| `device-loop` module | The accessibility closed-loop responsibility (observe → act → finish with high-risk confirmation) | Keep observation semantic, actions single-step, and dangerous actions paused until explicit confirmation — over a fake device. The module itself contains no accessibility service, screen capture, or real device access. |
| `device-loop-android` module | The accessibility closed-loop integration seam (service enablement, tree reading, action dispatch) | Bridge the `device-loop` contract onto a real accessibility tree: a pull-only service that does nothing on events, a deterministic mapper from the foreground window to bounded semantic nodes with synthetic ids, and tap/set-text execution against the last snapshot. No product node identity, event-driven automation, gesture vocabulary, or screen capture is reproduced. |

## Why these four runtime layers are separate

```text
AgentHarnessRunner       composition and launch
  └─ AgentOrchestrator   one bounded run and session lifecycle
       ├─ AgentContextCoordinator   data selection boundary
       ├─ AgentProvider             reasoning/transport boundary
       └─ AgentToolOrchestrator     capability boundary
            └─ AgentToolRegistry    executable adapter set
```

Keeping these responsibilities separate allows an Android application to replace storage, model transport, local context, or tools independently without importing `mirror-android` product code.

## Deliberate incompatibilities

- Package names are new (`dev.androidagent.harness`); no reference package is reused.
- Contracts are synchronous. Coroutine, callback, or streaming adapters can wrap the portable boundary.
- Tool arguments are `Map<String, String>` rather than JSON objects.
- Context is structured and passed separately from conversation messages.
- The context coordinator performs deterministic policy selection, not the reference V2 NeedSpec/EvidencePack/reranking pipeline.
- The in-memory session store is not compatible with existing on-device files.
- Tool results are text-only; images, opaque evidence refs, and large-result envelopes require an explicit extension.
- Tool profiles are caller-declared generic allowlists, not the product's CHAT/HARNESS/PROACTIVE policy table.
- The sample keeps no application singleton, receiver, telemetry, file store, or product resources; its only capabilities are the `INTERNET` permission for the user-configured endpoint, app-private preferences for user-entered settings, and the user-enabled accessibility service for phone mode.
- The target `AgentHarnessRunner` is the portable composition root; the baseline/candidate evaluation responsibility lives in the separate `harness-eval` module over a generic markdown workspace, not the product workspace format.
- `device-loop` operates a deterministic fake device; the real accessibility-service integration is the separate `device-loop-android` module.
- `provider-openai` speaks the public OpenAI-compatible protocol only; no product provider configuration is reproduced.
- Device nodes carry synthetic per-snapshot ids (`n1`, `n2`, … in traversal order), not any product node-identity scheme; an id is only meaningful against the most recent snapshot and goes stale with it.
- Observation is text-only: the accessibility node tree rendered as bounded semantic lines. There is no screen capture, screenshot, or vision input of any kind — pixels never reach a provider.
- There is no gesture recording or replay vocabulary; the only synthesized gesture is a single tap at a node's center, used as a fallback when nothing in the clickable chain accepts an accessibility click.
- Device turns are single-action steps only: phone mode runs with one `device_act` per provider step (`maxToolCallsPerStep = 1`), observing between actions, so every state change is attributable to one reviewed step.

## Safe adapter sequence

1. Implement an `AgentProvider` without embedding credentials; inject authenticated transport at the application boundary.
2. Implement narrow `AgentContextProvider` adapters and configure allowed trust plus budgets.
3. Add tools one at a time and place them in an explicit `AgentToolProfile`; perform permission and confirmation checks inside the adapter.
4. Add a durable `AgentSessionStore` only after defining migration, retention, deletion, and atomicity.
5. Keep deterministic clock/id fakes in tests and run `checkM0` plus `runDemo` after changes.

No change is required in `mirror-android` to consume or evolve this extracted runtime.

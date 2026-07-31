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

The core is Kotlin/JVM so every runtime boundary can be tested without Android
state. The JVM demo is an executable proof. The Android sample is an
installable consumer of the same core: it adds exactly the capabilities its
features need — network access for user-selected provider/web endpoints,
app-private preferences for user-entered settings, the opt-in accessibility
service from `device-loop-android`, and the visible WebView surface from
`web4agent-android` — while `harness-core` itself stays free of all of them.

## Current alignment map

This map was refreshed on 2026-07-31 against the then-current private reference
revision (pinned in a private audit log). It describes independently authored
responsibility and product-flow alignment, not package, API, source, binary,
visual-asset, or serialized-data compatibility.

| Extracted boundary | Current `mirror-android` seam | Minimum retained responsibility |
| --- | --- | --- |
| `AgentHarnessRunner` | Application/harness composition around the Agent path | Construct the portable runtime from explicit provider, context, tool, session, time, and id adapters. It does not copy Android lifecycle behavior. |
| `AgentOrchestrator` / `AgentToolLoopActivation` | `agent.orchestrator.AgentOrchestrator` | Own one run: session mutation, context handoff, provider/tool loop, model-call-driven Phone Use activation, final persistence, and bounded failure. Streaming, retry UI, debug persistence, memory writeback, and Android cancellation are omitted. |
| `AgentContextCoordinator` | `agent.context.AgentContextCoordinator` | Keep context collection as a distinct control-plane boundary with trust, priority, and budgets. NeedSpec, retrieval, reranking, EvidencePack, prompt rendering, and route gate remain product extensions. |
| `AgentProvider` | `AgentChatClient` and its provider/model calls | Translate selected context, session messages, and tool specs to/from a model transport. Credentials and endpoints remain outside the core. |
| `AgentToolOrchestrator` | `agent.tool.AgentToolOrchestrator` | Keep the provider-visible catalog and executable capability profile consistent; validate bounded ordered calls and return inspectable results. Phone loops, formula tools, envelopes, images, and progress streaming are omitted. |
| `AgentTool` / `AgentToolRegistry` / `AgentToolProfile` | `AgentTool`, `AgentContextAwareTool`, `AgentToolRegistry`, and `AgentToolProfile` | Adapt individual capabilities and make the profile-scoped registry the runtime authority boundary. |
| `AgentSessionStore` / `AgentSessionCatalog` | `MirrorChatRepository`, `ChatSession`, and `ChatMessage` | Load/save immutable session snapshots. The optional file adapter adds atomic single-process persistence plus list/delete/clear; encryption, migration, retention, and database search remain application policy. |
| `AgentHouseRepository` / `AgentHouseContextProvider` / `AgentHouseWriteTools` | House core-file, skill, and memory responsibilities | Manage newly authored generic Markdown assets behind a portable storage contract, let the model append Agent-trust memory and disabled skill drafts, and inject a bounded, deterministic provenance-aware snapshot. Product workspace formats, prompts, assets, evaluation policy, and migration are not reproduced. |
| JVM `demo` | Debug/chat/harness entry surfaces | Prove the portable flow and show only input, run, result, trace, and transcript. No product resources, navigation, permissions, or branding are retained. |
| Android `sample` (home, chat, House, settings, Phone Use) | The credential-configured Agent product shell over the harness | Provide independently authored navigation and state hierarchy, recent conversations, generic House editing, provider/model configuration, Codex/Kimi/Ark options, and model-driven Phone Use. No product source, resource, branding, prompt, data format, or visual asset is reproduced. |
| `provider-openai` module | The chat-client/provider transport boundary | Adapt the provider contract to an OpenAI-compatible endpoint with environment-only credentials. Product endpoints, headers, provider defaults, and OAuth flows are not reproduced. |
| `harness-eval` module | The house baseline/candidate evaluation responsibility | Evaluate candidate overlays of interpretable markdown assets against a baseline on fixed cases before promotion. The product workspace format, memory files, and promotion UI are not reproduced. |
| `device-loop` module | The accessibility closed-loop responsibility (observe → act → finish with high-risk confirmation) | Keep observation semantic, actions single-step, and dangerous actions paused until explicit confirmation — over a fake device. The module itself contains no accessibility service, screen capture, or real device access. |
| `device-loop-android` module | The accessibility closed-loop integration seam (service enablement, tree reading, action dispatch) | Bridge the `device-loop` contract onto a real accessibility tree: a pull-only service that does nothing on events, a deterministic mapper from the foreground window to bounded semantic nodes with synthetic ids, and tap/set-text execution against the last snapshot. No product node identity, event-driven automation, gesture vocabulary, or screen capture is reproduced. |
| `web4agent-android` module | The visible WebView capability responsibility | Provide a new session-keyed browser Activity and public Android WebView implementation for open/observe/read/inspect/eval/act/console/capture/finish over the existing Harness tool and exact-approval contracts. No reference source, package, bridge, MiniApp file format, storage layout, prompt, UI resource, or product data is reproduced. |

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
- The bundled file session and Agent House formats are new and are not compatible with existing product databases or workspaces.
- Tool results use bounded text plus typed envelopes. Web captures are opaque,
  exact-scope TTL payloads; this release does not silently convert a capture
  into provider image input.
- Tool profiles are caller-declared generic allowlists, not the product's CHAT/HARNESS/PROACTIVE policy table.
- The sample keeps process-local holders for its app-private session/House
  adapters and visible WebView sessions, but has no telemetry, copied product
  resource, or cross-application storage. Network, scheduling, audio,
  notifications, and Accessibility remain explicit documented Android
  capabilities.
- The target `AgentHarnessRunner` is the portable composition root; the baseline/candidate evaluation responsibility lives in the separate `harness-eval` module over a generic markdown workspace, not the product workspace format.
- `device-loop` operates a deterministic fake device; the real accessibility-service integration is the separate `device-loop-android` module.
- `provider-openai` speaks the public OpenAI-compatible protocol only; no product provider configuration is reproduced.
- Device nodes carry synthetic per-snapshot ids (`n1`, `n2`, … in traversal order), not any product node-identity scheme; an id is only meaningful against the most recent snapshot and goes stale with it.
- Phone Use observation remains a bounded text accessibility tree. Separately,
  Web4Agent may create a temporary WebView PNG in a host raw-payload store; its
  pixels do not enter provider-visible text or become model vision input
  automatically.
- There is no gesture recording or replay vocabulary; the only synthesized gesture is a single tap at a node's center, used as a fallback when nothing in the clickable chain accepts an accessibility click.
- Device tools are provider-visible during normal planning; the first actual model device-tool call activates a sticky Phone Use budget. Activated turns are single-action steps only (`maxToolCallsPerStep = 1`), observing between actions, so every state change is attributable to one reviewed step.
- Web4Agent tools are also provider-visible during normal planning. The first
  actual Web4Agent call activates the same bounded one-tool-per-step expansion;
  each page session is keyed by Agent session id and every action/eval remains
  attributable to one tool call and exact approval intent.

## Safe adapter sequence

1. Implement an `AgentProvider` without embedding credentials; inject authenticated transport at the application boundary.
2. Implement narrow `AgentContextProvider` adapters and configure allowed trust plus budgets.
3. Add tools one at a time and place them in an explicit `AgentToolProfile`; perform permission and confirmation checks inside the adapter.
4. Use the bundled file store only for a single-process app whose plaintext app-private retention policy is acceptable; otherwise adapt the catalog to an encrypted database with explicit migration and retention.
5. Keep deterministic clock/id fakes in tests and run `checkM0` plus `runDemo` after changes.

No change is required in `mirror-android` to consume or evolve this extracted runtime.

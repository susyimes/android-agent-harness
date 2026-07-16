# Extraction boundary and compatibility map

## M0 boundary

This repository is a narrow, independently authored extraction of architectural seams, not a source-code fork. It keeps only the minimum path needed to prove a deterministic Android agent turn:

1. A caller supplies a session id and user text.
2. A context provider returns explicitly selected context items.
3. A provider sees an immutable session snapshot, the selected context, and registered tool specifications.
4. The provider returns either final text or an ordered list of tool calls.
5. The harness executes registered tools, appends tool results to the session, and asks the provider again.
6. A configured provider-step limit ends non-terminating runs.

The core is Kotlin/JVM so its contracts can be tested without Android runtime state. The Android sample is a separate consumer and adds no permission or network capability.

## Compatibility mapping to the read-only reference

This is a conceptual adapter map, not source, binary, package, or serialized-data compatibility.

| M0 contract | Read-only `mirror-android` seam | Adapter responsibility |
| --- | --- | --- |
| `AgentProvider` | `AgentChatClient` provider/model calls | Translate `AgentProviderRequest` messages, context, and `AgentToolSpec` values into the chosen provider protocol; translate provider text/tool calls back. Credentials remain outside the core. |
| `AgentContextProvider` | `AgentMirrorContextProvider` and the `agent.context` control-plane providers | Select and redact context before returning `AgentContextItem`; do not pass an entire local data store or Android `Context`. |
| `AgentTool` / `AgentToolRegistry` | `AgentTool`, `AgentContextAwareTool`, and `AgentToolRegistry` | Adapt JSON arguments to the M0 string map (or introduce a separately reviewed typed payload); register only capabilities appropriate for the run profile. |
| `AgentSessionStore` | `MirrorChatRepository`, `ChatSession`, and `ChatMessage` | Map roles and tool-call ids, choose durable storage if needed, and preserve atomicity outside the core contract. |
| `DeterministicAgentHarness` | `AgentHarnessRunner` plus `AgentToolOrchestrator` | Build a bounded run around the provider and tool adapters. Streaming, comparisons, overlays, evaluation, and persistence are intentionally outside M0. |
| `AgentClock` / `AgentIdGenerator` | direct wall-clock and UUID use in the reference harness | Inject production implementations or deterministic fakes; never hide time/randomness inside tests. |

## Deliberate incompatibilities

- Package names are new (`dev.androidagent.harness`); no reference package is reused.
- Contracts are synchronous in M0. A coroutine or callback adapter can wrap them without changing the deterministic runner.
- Tool arguments are `Map<String, String>` rather than JSON objects. This keeps M0 dependency-free and makes sample inputs inspectable.
- Context is structured data passed separately from conversation messages. A provider adapter decides how to render it.
- The in-memory session store is a sample implementation, not a format compatible with any existing on-device files.
- Tool results are text-only. Images, opaque evidence references, and large-result envelopes require a later explicit contract.
- The sample has no Android application-context singleton, services, receivers, accessibility APIs, file access, or permissions.

## Safe adapter sequence

1. Implement an `AgentProvider` without embedding credentials; inject authenticated transport from the application boundary.
2. Implement an `AgentContextProvider` that returns the least context required and labels its source/trust.
3. Add tools one at a time to an explicit `AgentToolRegistry`; perform permission and user-confirmation checks inside the adapter boundary.
4. Add a durable `AgentSessionStore` only after defining migration, retention, and deletion behavior.
5. Keep deterministic clock/id fakes in tests and run `checkM0` after each adapter change.

No adapter or parity change was made to `D:\mirror-android` in M0.


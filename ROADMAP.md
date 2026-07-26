# Roadmap

## Landed

### M1 — public runnable baseline
Four explicit runtime boundaries in `harness-core` (orchestrator, context coordinator, tool orchestrator, session store), a deterministic JVM demo (`:demo:run`), end-to-end and architecture tests, the `auditProvenance` guard, and CI covering both the no-Android-SDK JVM path and the full `checkM0`.

### M2 — real model, same discipline
`provider-openai`: a zero-dependency adapter for OpenAI-compatible chat-completions endpoints (hand-written JSON codec, JDK HTTP client). Credentials come from the environment only; the audit task fails the build on any embedded credential-like assignment. The `live` demo subcommand runs one bounded turn with three locally implemented tools against OpenAI, DeepSeek, or any compatible endpoint.

### M3 — the controlled context plane, made visible
The `scenarios` subcommand: five deterministic scenarios proving that what a provider may see (trust, priority, content budget), what it may call (tool profile), and how long it may run (step limits) are policy decisions recorded in the trace — including `droppedItemIds`, so every exclusion is auditable.

### M4 — governance beyond a single turn
- `harness-eval`: candidate changes to interpretable markdown assets are evaluated against a baseline on fixed cases; a single regression vetoes promotion (`eval` subcommand).
- `device-loop`: the minimal observe → one act → observe again → finish contract over a fake device, with an explicit high-risk pause protocol — a dangerous action never executes without `confirmed=true` after user approval (`phone` subcommand).

## Beyond M4

- Streaming provider responses behind the same bounded-step contract.
- JSON-schema tool arguments (today: flat `Map<String, String>`).
- A durable `AgentSessionStore` adapter with defined migration, retention, and deletion semantics.
- A real Android accessibility-service integration of the device-loop contract.
- A web-devtools capability component.

Policy for the last two: production subsystems are extracted into their own separately reviewed components or repositories — authored afresh against public contracts, never copied into this repository.

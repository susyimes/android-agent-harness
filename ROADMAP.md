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

### M5 — phone use on a real device
- `device-loop-android`: the device-loop contract over a real Android accessibility surface — a pull-only accessibility service (snapshots are read on demand, never event-driven), a deterministic screen mapper that renders the foreground window as semantic nodes with synthetic ids (unit-tested on the JVM behind the `UiNodeReader` seam), and a device surface executing taps and text entry.
- A human approval gate: in the installed app, every high-risk action pauses on a blocking on-screen dialog; the model-supplied `confirmed` argument is ignored, so only the user's tap on Allow can approve, and timeout or dismissal counts as denial.
- An installable APK: the `sample` is now a debug-signed, sideload-only chat app — paste your own OpenAI-compatible credential for live chat, run the offline scripted provider with no credential at all, and optionally enable accessibility-backed Phone Use manually in system settings. CI builds and uploads the APK on every `main` build.

### M6 — provider-ready Android sample
- A redesigned chat surface with warm layered cards, clear status hierarchy, message bubbles, and a provider/model picker sized for one-handed use.
- Explicit provider choices: offline demo, experimental Codex account login, Kimi Plan, Ark Plan, and a custom OpenAI-compatible endpoint. Each provider keeps its own model and endpoint settings.
- Browser PKCE and device-code fallback for the experimental Codex adapter, plus token refresh and logout. The adapter is isolated because third-party Android account login is not a documented official Codex surface.
- Android Keystore-backed encryption for API keys and login tokens, including one-time migration and removal of the sample's legacy plaintext custom credential.
- Phone mode is available only when the selected online provider is ready, so changing providers cannot accidentally reuse another provider's credential or session.

### M7 — productized host SDK
- `agent-sdk`: a host-facing async facade with run handles, structured Started/Trace/Finished events, safe listener isolation, bounded worker concurrency, and one active run per session.
- Cancellation is a real protocol boundary: it aborts turn-scoped provider I/O, interrupts the worker, rechecks a cancellation signal after provider calls and before every tool side effect, and fences all late results.
- `TransactionalAgentSessionStore`: only a complete successful turn commits. Failure or user stop discards partial messages while documenting that external tool/device effects cannot be rolled back.
- Typed `AgentToolArgumentSchema` metadata is rendered by both OpenAI-compatible and experimental Codex transports while the core retains its dependency-free normalized-string execution ABI.
- `OpenAiProviderFactories` creates an isolated cancellable transport per run; Kimi Plan and all Ark Plan model presets are reusable defaults, not hard allow-lists.
- `agent-sdk-android`: model-routed Phone Use composition requiring a host-supplied risk policy and human approval gate. The SDK has no permissive production default.
- Maven-style JAR/AAR publications, source artifacts, an independent consumer smoke module, `checkSdk`, and a complete host integration guide.

### M8 — product shell and durable local context
- `FileAgentSessionStore`: an app-private, dependency-free session catalog with hashed file names, bounded decoding, atomic replace, and list/delete/clear management APIs.
- Agent House: generic core Markdown files, Agent-written disabled skill drafts, and idempotently appended daily memories behind a portable repository; bounded context preserves application/user/Agent provenance without leaking storage paths.
- Model-driven Phone Use: device tools are visible during ordinary planning, but only an actual model device-tool call activates the sticky one-action-per-step loop and expands its ceiling from 8 to 80 steps.
- The sample now has home, chat, session, Agent House/editor, and settings surfaces, plus persistent recent conversations and provider/accessibility status.

### M9 — visible Web4Agent capability

- `web4agent-android`: an independently authored optional AAR with
  session-isolated visible WebViews and model-visible
  open/observe/read/inspect/eval/act/console/capture/finish tools.
- Structured DOM observation assigns stable element ids; broad reads cover
  text, HTML, links, forms, tables, and metadata; precise inspection supports
  CSS selectors, XPath, and text queries.
- JavaScript and page actions bind to the exact Harness approval protocol.
  Secure defaults keep HTTPS/inline HTML and disable cleartext, mixed content,
  local file/content access, third-party cookies, popups, and autoplay.
- The sample APK registers the complete tool bundle, launches the browser
  surface for the same chat session, exposes a manual workbench entry, and runs
  an inline DOM/JavaScript/action/capture loop in connected-device tests.
- Web captures use short-lived host raw-payload storage and never enter
  provider-visible text as image bytes.

## Beyond M9

- Streaming provider responses behind the same bounded-step contract.
- Optional encrypted/database session and House adapters with application-defined migration and retention.
- Binary API compatibility validation and a release pipeline to a remote Maven repository.
Policy for additional production subsystems: extract them into their own
separately reviewed modules or repositories, authored afresh against public
contracts and never copied into this repository.

# web4agent-android

`web4agent-android` is the optional, model-visible WebView capability layer for
Android Agent Harness. It is an AAR, not another Agent runtime: its tools execute
inside the same `AgentSdk` provider/tool loop as every other Harness capability.

## Tool surface

- `web4agent_open`: open one HTTPS URL, search query, or inline HTML document in
  a visible browser tied to the Agent session.
- `web4agent_observe`: return bounded page text, framework hints, and stable
  interactive-element ids.
- `web4agent_read`: read text, HTML, links, forms, tables, or metadata.
- `web4agent_inspect`: inspect DOM by CSS selector, XPath, or visible text.
- `web4agent_eval`: execute host-approved free-form JavaScript.
- `web4agent_act`: click, type, scroll, navigate, reload, or wait.
- `web4agent_console`: read bounded console and eval notes.
- `web4agent_capture`: create a short-lived, host-scoped PNG payload.
- `web4agent_finish`: close or leave the visible session open.

The recommended loop is:

```text
open → observe → read/inspect → act or eval → observe → finish
```

## Host composition

```kotlin
val runtime = Web4AgentRuntime.getInstance(context)
val payloads = EphemeralWebPayloadStore()
val web = Web4AgentToolSet(
    runtime = runtime,
    approvals = hostApprovalCoordinator,
    rawPayloadStore = payloads
)

val request = androidPhoneAgent.request(
    sessionId = sessionId,
    userInput = userInput,
    providerFactory = providerFactory,
    additionalContextProviders = listOf(Web4AgentGuidance.contextProvider()),
    additionalTools = web.tools(),
    additionalActivationToolNames = web.toolNames
)
```

The sample APK uses this exact composition. A tool call launches
`Web4AgentBrowserActivity`, so the Agent and user share the same visible
session controller and WebView. Android WebView cookies and site storage remain
part of the host app's standard WebView profile; controller isolation does not
create a separate browser profile.

Strict hosts should not treat `startActivity()` returning as proof that the
visible surface attached. Prepare and retain a one-shot generation instead:

```kotlin
val lease = runtime.preparePresentation(sessionId, hostGeneration)
runtime.show(lease)

lease.acknowledgement.thenAccept { acknowledgement ->
    // ATTACHED, CANCELLED, or REJECTED
}
```

The Activity verifies the Harness lease id and generation under the runtime
lifecycle lock before it may create or attach a controller. The original
`show(sessionId)` remains source/binary compatible and internally uses a lease,
but it cannot expose acknowledgement to its caller.

Harness launch Intents use `NEW_TASK` without `REORDER_TO_FRONT`; Android API
levels do not consistently deliver a replacement generation to a reordered
Activity. Exact-generation leases add a short-lived `MULTIPLE_TASK`,
`EXCLUDE_FROM_RECENTS` surface so an older finishing root Activity cannot absorb
the new admission. When a new same-session Activity wins admission, the runtime
atomically detaches and finishes the previous surface before attaching the
shared controller to the new one.

`Web4AgentToolSet` feature-detects `Web4AgentAcknowledgedPresenter`. The runtime
implements it and waits off Android main for `ATTACHED` before `web4agent_open`
creates/opens the session. A product presenter adapter can implement the same
optional interface while retaining its exact lease for Stop. Legacy presenter
implementations keep fire-and-return behavior.

## Default policy

The default configuration enables JavaScript and DOM storage but allows only
HTTPS navigation and inline HTML. Cleartext HTTP, mixed content, local
file/content access, third-party cookies, popup windows, and autoplay remain
disabled. `Web4AgentConfiguration.compatible()` is an explicit host opt-in for
cleartext/mixed-content/third-party-cookie compatibility; Android network
security policy still applies.

Page content is untrusted external evidence. DOM text, attributes, console
messages, and JavaScript results must never override host/user instructions or
receive credentials/private context. Page actions and free-form JavaScript are
bound to the Harness exact-approval protocol. Structured DOM readers redact
password and common secret-like form fields and attributes on a best-effort
basis; this is not a general data-loss-prevention system.

Captures are stored only through the host-provided `AgentRawPayloadStore`. The
tool result exposes an opaque TTL reference and metadata; it does not put image
bytes into provider-visible text.

## Stop and close semantics

Call `Web4AgentRuntime.closeAndAwaitQuiescence(lease, reason)` before publishing
a stopped run. It synchronously cancels pending Activity/controller admission
and closes the session only if the supplied lease is still the latest
same-session generation. Its stage completes immediately for a never-attached
launch, or after an admitted Activity detaches. A queued late Activity is
rejected before controller creation. `close(sessionId)` remains a compatible
non-awaiting convenience that cancels every generation for that session. Exact
act/eval executes a page guard and then rechecks session closure, WebView
identity, lease ownership, and page epoch atomically immediately before effect
dispatch. If close or session replacement wins, the call returns
`SESSION_CLOSED`, `occurred=false`, and performs no JavaScript, click, or native
navigation.

Close does not pretend to undo an effect whose dispatch already won that
linearization point. Keep every entered Web tool invocation in the host's
run-level in-flight-effect barrier, and publish `STOPPED` only after those
invocations complete. Reusing the same session id never transfers an old
session's lease to its replacement.

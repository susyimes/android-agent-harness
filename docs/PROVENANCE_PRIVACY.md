# Provenance and privacy inventory

Inventory refreshed: 2026-07-27 (Asia/Shanghai)

## Reference scope

The private `mirror-android` repository was treated as read-only architectural evidence, pinned at two clean revisions (original inventory on 2026-07-16; alignment refresh on 2026-07-18). No reference file was modified. Revision identifiers, repository location, and internal file paths of the private reference are recorded in a private audit log rather than in this public document.

The inspected material was limited to these declaration surfaces:

- the current architecture overview document
- root build, settings, and Gradle wrapper configuration (build structure only; no properties values were opened)
- the chat client, runtime context, chat model, and chat repository declaration surfaces (contracts and provider boundary)
- the run orchestrator, context coordinator/model, tool registry/orchestrator/model, and house/harness-runner declaration surfaces

No top-level license covering the reference application code was found during the inventory. Accordingly, no implementation text, package name, resource, data layout, asset, UI asset, or product configuration was copied. The target contracts, coordinator/orchestrator implementations, demo, and thin sample UI were authored afresh from the required responsibilities and high-level boundary map.

## Excluded material

The following were neither opened nor copied:

- `secrets.properties`, `local.properties`, `.env`, tokens, credentials, keystores, signing material, or account data
- app-private files, chat/session data, memory, todos, usage statistics, sensor/location data, debug traces, caches, or user content
- product API endpoints, provider defaults, model identifiers, headers, allowlists, or deployment configuration
- icons, images, audio, fonts, layouts, branding, prompts, personas, skills, native libraries, model files, or other product-only assets
- generated build directories, IDE metadata, local Maven contents, third-party source trees, and Git history contents

## Target inventory

| Category | Minimal contents | Privacy/provenance result |
| --- | --- | --- |
| Core source | Generic adapters, context/tool coordinators, run orchestrator, composition root, in-memory store, policy, and trace models | No reference namespace or implementation copied |
| JVM demo | Scripted provider, static public context, profile-scoped uppercase tool, deterministic trace, plus scenario/eval/phone showcases and the env-configured live mode | Executable proof with no embedded credentials, user data, or product dependency; the live mode reads credentials from the environment at run time only |
| OpenAI-compatible provider | Hand-written JSON codec, JDK HTTP transport, protocol mapping | Authored afresh against the public chat-completions protocol; no product endpoint, header, model default, or credential value |
| Evaluation harness | Generic markdown workspace, overlay semantics, case comparison, promotion verdict | Authored afresh; no product workspace format, file names, or persona/memory content |
| Device loop | Fake device model, risk policy, observe/act/finish tools, pause protocol | Authored afresh; no accessibility service, screen data, or product action vocabulary |
| Accessibility device surface (`device-loop-android`) | Pull-only accessibility service, JVM-testable screen mapper with synthetic per-snapshot node ids, tap/set-text device surface, XML service declaration | Authored afresh against public Android accessibility APIs; no product node identity, service configuration, recorded gestures, or screen captures. Text-only observation; no image ever reaches a provider |
| SDK local adapters | Hashed-name atomic session files; generic House core files, skills, daily memories, and bounded context conversion | Authored afresh with new formats and generic default text. No reference workspace, prompt, user data, or serialized format is included |
| Android sample | Independently authored XML/programmatic home, chat, House/editor, and settings UI; run-time provider/model/credential settings; offline provider; dialog approval gate; app-declared risk-label patterns | No copied layout/resource, database, telemetry, branding, or binary asset. Ships no credential; see the run-time material section below. Its only manifest permission is `INTERNET`, used solely for the selected endpoint |
| Configuration | Public Gradle plugin/SDK versions and JVM settings | No endpoint, credential field, signing config, or local machine path |
| Test data | Fixed ids, timestamps, and strings such as `android` | Synthetic only; no user or device data |
| Documentation | Compatibility names and inventory refresh dates for auditability | Contains reference class names only as provenance facts — no private paths, revisions, or package names — never as runtime configuration |
| License | Apache-2.0 for this repository | Explicit open-source grant |
| Gradle wrapper | Standard files generated by Gradle 8.11.1 | Third-party build bootstrap only; see `THIRD_PARTY_NOTICES.md` |

## Run-time user-supplied and user-granted material

Four categories of material exist only at run time on the user's device and are never part of the repository, the APK, or any release artifact:

- **Provider credentials and Codex tokens are user-supplied data, never shipped.** The sample encrypts each value with a randomized AES-GCM payload whose key lives in Android Keystore, stores only the encrypted payload in app-private preferences, redacts profile rendering, and sends a credential only to the provider selected for that turn. The app migrates and removes the old plaintext custom-credential preference if present. The in-app clear/logout controls or uninstall remove the associated application data. This remains a debuggable sample rather than a production secret boundary; use revocable, low-limit credentials.
- **Provider choice, model, endpoint, and last-session id are app-private settings.** They are not secrets, but a custom endpoint determines where the selected provider request is sent.
- **Conversation and House content are local plaintext application data.** Complete committed sessions are stored under the app-private files directory; the House stores user-edited generic core Markdown plus Agent-written skill drafts and daily memories there as well. Agent memory keeps Agent provenance, and a generated skill remains disabled until the user enables it. The sample disables Android cloud backup and device transfer. These files are deleted by the relevant UI controls or uninstall, are not encrypted by the bundled file adapters, and may contain sensitive user text. Enabled House content and recent daily memories are sent as bounded context to the provider selected for a turn.
- **The accessibility permission is a user-granted capability, not bundled data.** The APK declares the service, but declaring it grants nothing: the service is inert until the user manually enables it in the system accessibility settings, and the user can revoke it there at any time. While enabled, it reads the foreground window's node tree only after a turn the user started activates Phone Use and requests a snapshot; the snapshot is text-only, size-bounded, held in memory for the turn, and never persisted by the harness. No screen content is captured as pixels, stored on disk, or included in any artifact this repository produces.

The `auditProvenance` guard enforces the repository side of this boundary: any credential-shaped identifier assigned a quoted string literal fails the build, so a credential literal cannot enter the tree even disguised as a constant or test fixture.

## Automated guard

The repository-native `auditProvenance` task fails when it finds:

- common secret/config/signing filenames anywhere outside ignored build/cache directories;
- embedded credential-like assignments, reference product namespaces, or private local paths in core, demo, sample, build, or documentation files; or
- non-XML binary resources in the Android sample.

This guard complements the manual inventory; it is not a general-purpose secret scanner. Publication should still review `git diff --cached` and the complete tracked-file list.

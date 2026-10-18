# ADR 0009: Android client — native shell around the existing SPA

- Status: accepted
- Date: 2026-10-18
- Deciders: arepresas
- Related: 0008 (same-origin API), security, front, bff

## Context

The product has a web front (`front/`) and a BFF (`bff/`). A third
surface — Android — is wanted, and the requirement is that it *uses*
the front and the back that already exist rather than growing a second
implementation of either.

Two facts about the existing system decide the shape of the client.

1. **Google Identity Services cannot sign in inside an Android WebView.**
   The SPA authenticates with GIS in `popup` mode (`GoogleLoginButton.svelte`).
   Google blocks OAuth flows in embedded user agents
   (`disallowed_useragent`), so a WebView-only app has no way to log
   in. A working client must obtain the credential natively.

2. **The BFF's only browser edge contract is same-origin, and it is
   satisfied by the front's origin, not the BFF's.** ADR 0008 shipped no
   CORS config on purpose: the SPA calls relative paths and nginx
   (`front/Dockerfile`) reverse-proxies `/api/` to the BFF over Fly
   private networking. Authentication is `Authorization: Bearer` with no
   cookie anywhere, which is also why CSRF is void.

Together those two mean the client's two halves are not symmetric: the
**login has to be native** (the WebView cannot do it), and the
**application surface does not have to be** (the WebView is strictly
better at it — the SPA already implements the dashboard, uploads,
charts and an 82 KB ticket-detail screen).

## Decisions

### D1. Hybrid shell: native login, WebView application

The app is a single-activity Kotlin/Compose shell with two states:
a native sign-in screen, and a WebView hosting the deployed SPA.

Rejected alternatives:

- **WebView-only.** Cannot log in (fact 1). Non-starter.
- **Fully native client** (Compose UI against all ~17 BFF endpoints).
  Reimplements the dashboard, charts, multipart upload with OCR
  preview, product autocomplete and the detail screen — duplicating
  `front/` in a second language, and giving the two front-ends
  independent behaviour to keep in sync. Rejected until the SPA stops
  being a viable target.
- **Capacitor/Cordova.** Same WebView-plus-plugin shape as D1 but with
  a plugin runtime and its own dependency tree for exactly two native
  capabilities (Google credential + secure token storage), both of
  which this module implements in ~150 lines.

### D2. The client targets the front's origin, not the BFF's

One configurable base URL — `BuildConfig.WEB_ORIGIN` — is used by both
the WebView and the three native HTTP calls. In production it is the
front's nginx, which already proxies `/api/*`; in development it is the
Vite dev server, which proxies `/api` to a locally-running BFF.

**This is why ADR 0008 needs no amendment.** CORS is a browser
enforcement mechanism; an Android HTTP client is not a browser and does
not consult `Access-Control-Allow-Origin`. No allow-list entry is added
to `SecurityConfig`, no wildcard is introduced, and the API keeps its
single-origin topology. ADR 0008 §D3 remains satisfied: nothing about
the browser posture changed.

### D3. Session hand-off via a document-start script

The SPA reads `sessionStorage['ticketapp.session']` during its boot
sequence (`store.svelte.ts` → `initAuth()` → `GET /api/auth/me`).
Writing that key after load is too late — the store has already
decided the visitor is signed out.

`WebViewCompat.addDocumentStartJavaScript` runs before any page-authored
script, so the shell seeds the BFF token there and the SPA boots
already authenticated. On system WebViews older than 105 the feature is
absent; the client falls back to seeding in `onPageFinished` and
reloading once.

**Consequence:** the client is coupled to one string in the front —
the `ticketapp.session` key. A rename in `store.svelte.ts` silently
breaks mobile login, and no compile-time check catches it. This is
accepted deliberately: the alternative is a front-side change that
exists only for Android, which is worse. The key is named once, in
`SessionBridge`, with a comment pointing at its source.

### D4. Token at rest: Keystore + AES-GCM, not EncryptedSharedPreferences

`androidx.security.crypto` is in maintenance — `MasterKey` and
`EncryptedSharedPreferences` are deprecated in 1.1.0. The token is
encrypted with a non-exportable `AndroidKeyStore` AES-256-GCM key into
an app-private file instead.

The key is not gated on device authentication on purpose: it would put
a biometric prompt (and a lockout failure mode) in front of every cold
start. The threat addressed is a stolen, unlocked, non-rooted device,
which Keystore already covers.

### D5. The SPA keeps ownership of its own session lifetime

Logout from inside the SPA (header menu, or a 401 bubbling into
`auth:expired`) does not notify the shell through any supported API —
the front is a web app first. A document-start script patches
`Storage.prototype.removeItem` / `clear` and reports the removal over a
`@JavascriptInterface`, and the shell mirrors it locally.

Without this, the next cold start would re-inject a token the user had
explicitly signed out of. That is the one piece of behaviour this
architecture has to re-derive rather than share.

### D6. Not part of the Maven reactor

Android builds with AGP/Gradle; Maven has no Android packaging
lifecycle. `app-android/` is a standalone Gradle project, a sibling of
`front/`, with its own lockable dependency graph and its own CI step.
Adding it to the root `pom.xml` is not merely unnecessary — it would be
impossible.

## Consequences

### Negative

- **Two build systems, two dependency audits.** `sonar-project.properties`
  and Dependabot cover the Maven tree; `app-android/` gets a Dependabot
  entry but no Sonar coverage report, so its quality gate is the
  `android` CI workflow rather than SonarQube.
- **A shipped WebView is a shipped browser surface.** Mitigations are in
  place (origin-pinned navigation, off-origin links handed to the
  system browser, TLS errors cancelled, mixed content refused,
  `allowFileAccess = false`, renderer crashes contained by
  `onRenderProcessGone`), but the trust boundary is the system
  WebView, not an in-app renderer.
- **Frontend release cadence now gates the mobile app.** A breaking
  change in the SPA's session contract is a breaking change for
  Android, and vice versa.
- **No offline mode.** The SPA is fetched at runtime; without network
  the client shows the system's error page rather than a cached shell.
- **`SessionStore` and `GoogleSignIn` have no unit tests** — both need
  a real Android context (Keystore, account chooser). The JVM suite
  covers the BFF contract, the origin gate and the injected scripts;
  instrumented tests are the follow-up.

### Positive

- No change to `bff/`, `front/`, or the root `pom.xml`. The diff that
  introduced this ADR was 100% additive.
- ADR 0008's single-origin API and no-CSRF posture are preserved by
  construction rather than by a new allow-list entry.
- Roughly 700 lines of Kotlin against ~17 BFF endpoints; the ticket and
  dashboard surfaces are not reimplemented.
- Release APK is 1.6 MB after R8 — the application logic lives in the
  already-deployed front.
- 39 JVM unit tests, zero-emulator, run in CI on every PR.

## Implementation notes

Recorded after the module shipped, because these are the details a
future reader is most likely to get wrong.

- **AGP 9 ships built-in Kotlin support.** Applying the
  `org.jetbrains.kotlin.android` plugin is a hard error since 9.0, not
  a deprecation. The Compose and serialization plugins still apply —
  they transform code rather than provide a toolchain.
- **`GetGoogleIdOption` / `GoogleIdTokenCredential` are not in
  `credentials-play-services-auth`.** They live in
  `com.google.android.libraries.identity.googleid:googleid`.
  `credentials-play-services-auth` only ships provider controllers.
- **`mipmap-anydpi-v26` cannot be collapsed to `mipmap-anydpi`** despite
  what lint's `ObsoleteSdkInt` suggests: AAPT2 as shipped with AGP 9.4
  fails the resource merge with "resource mipmap/ic_launcher not
  found", leaving the app with no launcher icon. Ignored for that one
  folder in `app/lint.xml`.
- **Document-start script injection is the load-bearing part of D3.**
  It needs `WebViewFeature.DOCUMENT_START_SCRIPT` (WebView 105+); the
  fallback for older WebViews is seed-after-load plus one reload, which
  briefly shows the signed-out landing page.

## See also

- ADR 0008 — the same-origin/no-CSRF decision D2 depends on.
- `front/src/lib/auth/store.svelte.ts` — the session contract D3 hooks.
- `app-android/README.md` — build and configuration.
# app-android

Native Android shell around the existing web app (ADR 0009).

The app does **not** reimplement TicketApp. It contributes the two
things a browser tab cannot do on Android:

1. a **Google sign-in** — Google Identity Services cannot complete a
   popup flow inside an Android WebView, so the credential request runs
   natively through Credential Manager;
2. a **session that survives a cold start** — the BFF JWT is encrypted
   on the device and injected into the SPA's `sessionStorage` before
   its own JavaScript runs.

Everything else — dashboard, ticket upload, charts, ticket detail — is
the deployed Svelte SPA from `front/`, served through this app's WebView
and talking to the BFF through the same `/api` proxy the browser uses.

---

## Requirements

| Tool | Version | Notes |
|------|---------|-------|
| JDK | 17+ | Gradle 9.8.1 runs on the repo's JDK 25 |
| Android SDK | platform 37, build-tools 37.0.0 | `compileSdk`/`targetSdk` 37, `minSdk` 26 |
| Gradle | 9.8.1 | via the bundled wrapper — no local install needed |

Android Studio picks all of this up automatically. The module is a
standalone Gradle project: it is **not** part of the root Maven reactor
(Maven has no Android packaging lifecycle).

## Configure

`local.properties` is gitignored and machine-local. Start from the
template:

```bash
cp local.properties.example local.properties
```

Then fill in three keys:

```properties
sdk.dir=/path/to/Android/sdk

# Origin the app loads. This is the FRONT's public origin, not the
# BFF's — nginx (prod) and the Vite dev proxy both forward /api to it.
ticketapp.webOrigin=https://ticketapp-front.fly.dev

# OAuth WEB client id: the same value as the BFF's GOOGLE_CLIENT_ID
# and the front's VITE_GOOGLE_CLIENT_ID. An Android client id would
# mint a token the BFF's audience check rejects.
ticketapp.googleServerClientId=1234567890-abc.apps.googleusercontent.com
```

Both app-specific keys also read from Gradle properties or the
environment (`TICKETAPP_WEB_ORIGIN`, `TICKETAPP_GOOGLE_SERVER_CLIENT_ID`),
which is how CI injects them without a properties file.

`sdk.dir` has no portable default — Android Studio writes it for you
when you open the project.

The build works with the placeholder `REPLACE_ME...` client id (it
produces an APK); the login screen refuses to call Google with it, so
a misconfigured build fails loudly at sign-in rather than silently.

### Google Cloud setup

Two client entries are needed, and they are not interchangeable:

1. **Web client** — already exists for the BFF and the SPA. Its id is
   `GOOGLE_CLIENT_ID` (BFF), `VITE_GOOGLE_CLIENT_ID` (front) and
   `ticketapp.googleServerClientId` (here). All three are the same
   value: the BFF verifies the token's audience against it.
2. **Android client** — new. Register the app's SHA-1:

   ```bash
   keytool -list -v -keystore ~/.android/debug.keystore \
     -alias androiddebugkey -storepass android -keypass android
   ```

   Package name: `com.ticketapp.android`.

   Use the **debug** keystore's SHA-1 for local runs and the release
   keystore's for anything shipped — they are separate entries, and a
   build signed by an unlisted key fails at the account chooser with
   no useful error.

## Build and run

```bash
./gradlew testDebugUnitTest   # 39 unit tests
./gradlew lintDebug           # static analysis
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug        # onto a connected device
./gradlew assembleRelease     # R8 + resource shrinking (~1.6 MB)
```

### Release signing

Signing is opt-in and the build never hard-fails on a missing keystore:
without one you get `app-release-unsigned.apk`, which is the right
default for CI and local work.

To produce a signed APK, set four properties in
`~/.gradle/gradle.properties` (outside the repo) or as env vars:

```properties
ticketapp.storeFile=/absolute/path/to/upload.jks
ticketapp.storePassword=…
ticketapp.keyAlias=upload
ticketapp.keyPassword=…
```

| Property | Environment variable |
|----------|---------------------|
| `ticketapp.storeFile` | `TICKETAPP_STORE_FILE` |
| `ticketapp.storePassword` | `TICKETAPP_STORE_PASSWORD` |
| `ticketapp.keyAlias` | `TICKETAPP_KEY_ALIAS` |
| `ticketapp.keyPassword` | `TICKETAPP_KEY_PASSWORD` |

Then `./gradlew assembleRelease` emits `app-release.apk` (no
"unsigned" in the name) signed with APK Signature Scheme **v2 + v3**.
v2 is what every API 24+ device verifies against; v3 carries the
proof-of-rotation record so rotating the upload key later does not
force a key-reset on users.

Verify a signed artifact with:

```bash
$ANDROID_HOME/build-tools/37.0.0/apksigner verify --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

Keystores are blocked by `.gitignore` (`*.jks`, `*.keystore`) and must
never be committed.

## Running against local services

Start Postgres, the BFF and the Vite dev server on the host (see the
root `README.md`), then point the emulator at the host loopback:

```properties
ticketapp.webOrigin=http://10.0.2.2:5173
```

`10.0.2.2` is the AVD's alias for the host machine. For a USB-attached
device use `adb reverse tcp:5173 tcp:5173` and `http://localhost:5173`.

Cleartext HTTP is permitted **only** for `10.0.2.2`, `localhost` and
`127.0.0.1` (`res/xml/network_security_config.xml`). Everything else,
including the production origin, is TLS-only — the WebView carries a
session token, so a blanket `usesCleartextTraffic` would be a real hole.

## How the session hand-off works

```
native login ──POST /api/auth/google──> BFF ──> { token, user }
                                              │
                       Keystore AES-256-GCM  │ encrypted on device
                                              ▼
                            WebView: document-start script
                            sessionStorage['ticketapp.session'] = token
                                              │
                            SPA boots -> GET /api/auth/me ──> dashboard
```

Two details that are easy to break when editing:

- **The injection must run at document start.** `evaluateJavascript`
  and `onPageFinished` are both too late — `initAuth()` has already
  run by then. `SessionBridge` uses
  `WebViewCompat.addDocumentStartJavaScript`, with a reload-once
  fallback for WebViews older than 105.
- **Logout must propagate back.** The SPA clears its own storage
  without telling anyone, so `SessionBridgeCallback` observes
  `Storage.prototype.removeItem` / `clear` and reports it to the shell.
  Without it, the next cold start re-authenticates a user who signed
  out.

The storage key `ticketapp.session` is defined by
`front/src/lib/auth/store.svelte.ts`. Renaming it there breaks Android
login silently — see ADR 0009 §D3.

## Architecture

```
┌─ Compose shell ─────────────────────────────────────────┐
│                                                        │
│  AppViewModel ──► GoogleSignIn ──► CredentialManager    │
│       │           (native; GIS is blocked in a WebView) │
│       │                                                │
│       ├──► BffAuthClient ──► POST {origin}/api/auth/google
│       │                      GET  {origin}/api/auth/me
│       │                      POST {origin}/api/auth/logout
│       │                                                │
│       ├──► SessionStore (AndroidKeyStore AES-256-GCM)  │
│       │                                                │
│       └──► WebAppScreen ──► WebView ──► the real front  │
│                            │                           │
│                            └─ document-start script:    │
│                               sessionStorage[          │
│                               'ticketapp.session'] = JWT│
└────────────────────────────────────────────────────────┘
```

| File | Role |
|------|------|
| `ui/MainActivity.kt` | Renders splash / login / WebView off `AppState` |
| `ui/AppViewModel.kt` | State machine, session restore, sign-in/out |
| `ui/LoginScreen.kt` | The one natively-drawn screen |
| `auth/GoogleSignIn.kt` | Credential Manager → raw Google id_token |
| `auth/SessionStore.kt` | Encrypted token at rest |
| `net/BffAuthClient.kt` | The three auth endpoints |
| `net/Dto.kt` | Wire shapes mirroring `AuthController` records |
| `web/SessionBridge.kt` | Document-start seeding + logout observer |
| `web/WebAppScreen.kt` | WebView settings, navigation policy |
| `web/OriginPolicy.kt` | Single-origin gate for navigation |
| `web/JsQuote.kt` | JS string escaping for injected scripts |

## Tests

39 JVM unit tests, no emulator required:

```bash
./gradlew testDebugUnitTest
```

| Suite | Covers |
|-------|--------|
| `BffAuthClientTest` | Wire contract against the real BFF shapes: request paths, Bearer header, 401 → `null` on `fetchMe`, best-effort logout. Runs against `MockWebServer`, so a BFF DTO rename fails here instead of in a user's hands. |
| `OriginPolicyTest` | The navigation gate, including the near-misses that matter: `…fly.dev.evil.tld`, `evil.…fly.dev`, scheme downgrade, port change, unparseable input. |
| `JsQuoteTest` | Escape table for the injected script, including a round-trip property: whatever goes in must come back out of the JS literal unchanged. |
| `SessionBridgeScriptTest` | Both injected scripts reference the SPA's storage key, and the observer is idempotent and only fires when the key is actually gone. |

`SessionStore` and `GoogleSignIn` are not unit tested — both need a
real Android context (Keystore, account chooser). Instrumented tests
are the natural follow-up.

## CI

`.github/workflows/android.yml` runs on every PR and push to `main`:
unit tests → lint → both APKs, with the reports and APKs uploaded as
artifacts. It pins the same platform (37) the module compiles against.

The module is deliberately **out of SonarQube's scope** — its source
roots are explicit lists in `sonar-project.properties`, and wiring
Kotlin in without a coverage report would register the whole module as
new uncovered code. ADR 0009 records this; its quality gate is the
CI workflow above.

## Security notes

- The JWT is a full-account bearer credential. It is encrypted at rest
  with a non-exportable Keystore key and never logged.
- WebView navigation is pinned to `WEB_ORIGIN`; off-origin links open
  in the system browser, so no third-party page can execute in a
  context holding the injected token.
- TLS errors cancel, mixed content is refused, `allowFileAccess` is
  off, file/content access is disabled.
- `onRenderProcessGone` contains a renderer kill instead of letting it
  crash the app.
- Cloud backup and device transfer are disabled — the ciphertext is
  bound to this device's Keystore and worthless elsewhere.
- No CORS allow-list entry was added to the BFF and none is needed: an
  Android HTTP client is not subject to browser CORS enforcement.

## Not covered yet

- **Instrumented tests** for `SessionStore` and `GoogleSignIn`.
- **No offline mode.** The SPA is fetched at runtime; without network
  the user sees the system's error page.
- **No app icon on the Play Store listing** — the adaptive icon is a
  placeholder receipt, not artwork.
- **No camera capture** for receipt upload (the SPA's file picker
  already works; capture would be a native addition).
- **Not in SonarQube** (see CI section).
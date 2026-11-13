# voice/auth: Sign in with ChatGPT

GPT-Live voice calls go through OpenAI's Codex backend (`chatgpt.com/backend-api/codex/...`) and are paid for by
the user's ChatGPT Plus/Pro subscription. To get the token, we use the same OAuth client and flow as the Codex CLI
("Sign in with ChatGPT").

## API for the live transport

```kotlin
val creds: Credentials? = ChatGptAuth.credentials(ctx)          // suspend; null = not signed in / unusable
// creds.accessToken -> "Authorization: Bearer <accessToken>"
// creds.accountId   -> "ChatGPT-Account-ID: <accountId>"
// After an HTTP 401 from chatgpt.com, call ChatGptAuth.credentials(ctx, forceRefresh = true) once and retry.
```

- `credentials()` refreshes on its own when the access token's JWT `exp` is less than 5 minutes away (the same
  window Codex uses). If the refresh fails but the old token is still valid, you get the old token.
- Account API: `ChatGptAuth.state: StateFlow<AuthState>` (`SignedOut`, `SigningIn`,
  `SignedIn(email, plan, accountId)`, `Error(message)`; `Error` now only comes from a permanently failed refresh),
  `isSignedIn(ctx)`, `signOut(ctx)`, `importCodexAuthJson(ctx, json)`, `refreshNow(ctx)`, `info(ctx)` (contains no
  secrets), and `init(ctx)`. `init(ctx)` loads the stored sign-in into `state`. Every method that takes a `ctx`
  calls it for you, so call it directly only if you need `state` before calling anything else.
- Sign-in API: `ChatGptAuth.login: StateFlow<LoginState>` is the progress of the current browser attempt:
  `Idle`, `Starting(id)`, `Waiting(id, port, authorizeUrl, deadlineMs)`, `Exchanging(id)`,
  `Succeeded(id, email, plan)`, `Failed(id, reason: LoginFailure, detail)`. Calls: `startLogin(ctx)` (ignored while
  an attempt is running, so double taps are harmless), `cancelLogin(ctx)`, `failLogin(ctx, problem)`,
  `clearLoginResult()`, `pendingLogin()` / `resumeLogin(ctx, pending)` (process-death recovery, see below).
  A failed or cancelled attempt never touches an existing sign-in.
- `ChatGptAuth.onSignedOut` is a hook that `VoiceBackends.install` sets: after sign-out a forced `chatgpt` backend
  setting goes back to `auto`, and a running ChatGPT voice session is stopped.
- UI: `LoginActivity.start(ctx)` opens the sign-in screen (account details when signed in).
  `autoStart = true` also starts the browser sign-in right away; `onboarding = true` adds a "Not now" button.
- `CodexOAuth(issuer, http)` is a class whose issuer defaults to `https://auth.openai.com`; tests point it at a
  MockWebServer. `LoginFlow` holds the pure decision logic (callback checks, plan rules, error messages).
- Never log `accessToken` or the refresh token. `Credentials.toString()` redacts the token. `PendingLogin.toString()`
  hides the verifier and state.

## Screens (LoginActivity, plain Views, `res/layout/activity_login.xml`)
Header (mic bubble, "Voice Control", one-paragraph explanation), then one of:
- **Choose** (signed out): "Sign in with ChatGPT" (primary), "Use an OpenAI API key instead" (reveals a key field;
  "Save key and use it" checks the key starts with `sk-`, calls `AppSettings.setOpenAiApiKey` and sets the voice
  backend to `openai_key`, then shows "API key saved" with Continue). "Not now" in onboarding mode.
- **Waiting**: spinner, "Waiting for you to finish signing in in the browser…", port and a 5-minute countdown,
  Cancel and "Open browser again" (reopens the same authorize URL; the listener and state stay the same).
- **Finishing sign-in…** while the code is exchanged.
- **Success**: ✓ "You're signed in", email, plan ("ChatGPT Pro"), the voice backend now in use, Continue.
- **Error banner** above Choose (or above the account card when a sign-in already exists), with a "Try again"
  button. Banner titles: Sign-in cancelled · Sign-in was declined (`error=access_denied`) · OpenAI reported a sign-in
  error (other `error=` values, or a missing code) · Sign-in couldn't be verified (state mismatch) · Can't reach
  OpenAI (network) · Token exchange failed (non-2xx from `/oauth/token`) · No ChatGPT account found (no account id in
  the tokens) · Sign-in port is busy (1455 and 1457 taken) · Sign-in timed out (5 min) · No browser found · Your
  ChatGPT plan has no voice (plan `free`/`guest`; the tokens are not kept).
- **Account** (signed in, "Manage" on MainActivity): email, plan, backend in use, Done, Switch account (a new
  browser sign-in; the old tokens are replaced only when it succeeds), Sign out (with a confirmation dialog).

MainActivity opens the screen in onboarding mode on a cold start when there is neither a ChatGPT sign-in nor an API
key (`VoiceBackends.needsSetup`), once per process. The overlay's "Voice isn't set up" tap opens it directly.

## Flow (LoginActivity → ChatGptAuth.startLogin)
1. Start `LoginKeepAliveService`, a `specialUse` foreground service (subtype: local OAuth callback listener). It
   stops when the attempt ends and after 6 minutes at the latest. Without it, Android 14+'s cached-app freezer
   suspends our process as soon as the browser is in front, and the loopback server never answers. We saw this on
   the emulator: the process showed `cch-act isFrozen=true` and the callback connection sat in the accept queue.
   It used to be a `shortService`, but Android stops those after ~3 minutes and the timeout is now 5 minutes. Its
   notification opens the sign-in screen.
2. Bind `127.0.0.1:1455`. If the port is busy, send `GET /cancel` to whoever holds it, retry for about 2 s, then
   fall back to `127.0.0.1:1457` with `redirect_uri` on 1457. This copies Codex. Both ports busy → "Sign-in port is
   busy".
3. Generate PKCE (64 random bytes → base64url verifier, S256 challenge) and `state` (32 random bytes, base64url),
   publish `Waiting` with the authorize URL.
4. LoginActivity opens the URL in a **Custom Tab** (androidx.browser; `CustomTabsClient.getPackageName` picks the
   default browser if it supports Custom Tabs), else with `ACTION_VIEW`. The tab is launched from the Activity, so
   it sits in our task directly above LoginActivity. The activity remembers which attempt it opened the browser for,
   so rotation or coming back doesn't open a second tab.
5. The browser is redirected to `http://127.0.0.1:<port>/auth/callback?code=…&state=…`. Check `state` first (a
   wrong state can't report anything else), then `error=` (`access_denied` → "declined", others → "OpenAI reported
   a sign-in error"), then `code`; exchange the code (form POST, unchanged since the real sign-in on 2026-11-13).
   The browser gets a "You're signed in" / error page with a "Back to Voice Control" button
   (`intent://done#Intent;scheme=com.brainstormity.voicecontrol.auth;package=…;end`).
6. Save the tokens (unless the plan has no voice), publish `Succeeded`, and relaunch LoginActivity with
   `NEW_TASK | CLEAR_TOP | SINGLE_TOP`: CLEAR_TOP finishes the Custom Tab above it, SINGLE_TOP delivers `onNewIntent`
   to the existing screen. When the browser was a separate Chrome task (ACTION_VIEW fallback), that task just ends
   up behind ours. Android allowed this start on Android 16 even with no overlay permission and no accessibility
   service (logged `BAL_ALLOW_FOREGROUND`, because of the keep-alive FGS). If a future Android blocks it, the result
   page button goes through `AuthReturnActivity`, an invisible trampoline that does the same CLEAR_TOP relaunch
   (a plain NEW_TASK start from Chrome would stack a second LoginActivity above the tab).
7. On timeout (5 min), Cancel, Back, `/cancel`, a state mismatch, an `error=` callback, or a failed exchange:
   `login = Failed(reason, detail)`. Any tokens that were already stored are kept.

**Rotation / Activity recreation:** the attempt lives in `ChatGptAuth` (process singleton), so the screen just
re-renders from `login`. **Process death while the browser is in front:** LoginActivity's `onSaveInstanceState`
stores the `PendingLogin` (verifier, state, port, authorize URL, deadline). When Android recreates the activity in
a new process, `resumeLogin` binds the same port again (`bindExact`, no fallback, since the redirect URI names it)
with the same state and verifier, without opening a new tab. A redirect that hit the dead port can be reloaded in
the browser, or the user taps "Open browser again" and OpenAI's session redirects straight back.

Other paths on the loopback server get a 404, for example Chrome's `/favicon.ico`.

## Endpoints and parameters (checked against openai/codex @ `c899e21c`)
Base: `https://github.com/openai/codex/blob/c899e21cda29fdd422898ffa54893f67ece34039/codex-rs/login/src/`

| What | Value | Source |
|---|---|---|
| Issuer | `https://auth.openai.com` | [server.rs#L78](https://github.com/openai/codex/blob/c899e21cda29fdd422898ffa54893f67ece34039/codex-rs/login/src/server.rs#L78) |
| Port / fallback | `1455` / `1457` | [server.rs#L79-L81](https://github.com/openai/codex/blob/c899e21cda29fdd422898ffa54893f67ece34039/codex-rs/login/src/server.rs#L79-L81) |
| redirect_uri | `http://127.0.0.1:{port}/auth/callback` (note: **127.0.0.1**, not `localhost`) | [server.rs#L197](https://github.com/openai/codex/blob/c899e21cda29fdd422898ffa54893f67ece34039/codex-rs/login/src/server.rs#L197) |
| Authorize URL | `{issuer}/oauth/authorize` with `response_type=code, client_id, redirect_uri, code_challenge, code_challenge_method=S256, state, scope` and then `id_token_add_organizations=true, codex_cli_simplified_flow=true, originator=codex_cli_rs` | [server.rs#L580-L622](https://github.com/openai/codex/blob/c899e21cda29fdd422898ffa54893f67ece34039/codex-rs/login/src/server.rs#L580-L622), [oauth/authorization.rs#L21-L48](https://github.com/openai/codex/blob/c899e21cda29fdd422898ffa54893f67ece34039/codex-rs/login/src/oauth/authorization.rs#L21-L48) |
| Scope | `openid profile email offline_access api.connectors.read api.connectors.invoke` | [server.rs#L613](https://github.com/openai/codex/blob/c899e21cda29fdd422898ffa54893f67ece34039/codex-rs/login/src/server.rs#L613) |
| originator | `codex_cli_rs` (DEFAULT_ORIGINATOR) | [auth/default_client.rs#L42](https://github.com/openai/codex/blob/c899e21cda29fdd422898ffa54893f67ece34039/codex-rs/login/src/auth/default_client.rs#L42) |
| PKCE | 64 random bytes → base64url-no-pad verifier; challenge = base64url(SHA-256(verifier)) | [oauth/pkce.rs#L14-L29](https://github.com/openai/codex/blob/c899e21cda29fdd422898ffa54893f67ece34039/codex-rs/login/src/oauth/pkce.rs#L14-L29) |
| client_id | `app_EMoamEEZ73f0CkXaXp7hrann` | [auth/manager.rs#L1897](https://github.com/openai/codex/blob/c899e21cda29fdd422898ffa54893f67ece34039/codex-rs/login/src/auth/manager.rs#L1897) |
| Code exchange | `POST {issuer}/oauth/token`, **form-encoded**: `grant_type=authorization_code, client_id, code, redirect_uri, code_verifier` → `{id_token, access_token, refresh_token}` | [server.rs#L799-L832](https://github.com/openai/codex/blob/c899e21cda29fdd422898ffa54893f67ece34039/codex-rs/login/src/server.rs#L799-L832), [oauth/client.rs#L79-L95](https://github.com/openai/codex/blob/c899e21cda29fdd422898ffa54893f67ece34039/codex-rs/login/src/oauth/client.rs#L79-L95) |
| Refresh | `POST https://auth.openai.com/oauth/token`, **JSON** body `{grant_type:"refresh_token", client_id, refresh_token, refresh_reason}`, 45 s timeout → `{id_token?, access_token?, refresh_token?}` | [auth/manager.rs#L241](https://github.com/openai/codex/blob/c899e21cda29fdd422898ffa54893f67ece34039/codex-rs/login/src/auth/manager.rs#L241), [#L1808-L1895](https://github.com/openai/codex/blob/c899e21cda29fdd422898ffa54893f67ece34039/codex-rs/login/src/auth/manager.rs#L1808-L1895), [oauth/client.rs#L97-L136](https://github.com/openai/codex/blob/c899e21cda29fdd422898ffa54893f67ece34039/codex-rs/login/src/oauth/client.rs#L97-L136) |
| Refresh window | 5 min before `exp` (plus a scheduled refresh every 8 days) | [auth/manager.rs#L232-L233](https://github.com/openai/codex/blob/c899e21cda29fdd422898ffa54893f67ece34039/codex-rs/login/src/auth/manager.rs#L232-L233), [#L3660-L3678](https://github.com/openai/codex/blob/c899e21cda29fdd422898ffa54893f67ece34039/codex-rs/login/src/auth/manager.rs#L3660-L3678) |
| Permanent refresh errors | 401, 400 `invalid_grant`, `refresh_token_expired`, `refresh_token_reused`, `refresh_token_invalidated` → sign in again | [auth/manager.rs#L1838-L1885](https://github.com/openai/codex/blob/c899e21cda29fdd422898ffa54893f67ece34039/codex-rs/login/src/auth/manager.rs#L1838-L1885) |
| Claims | `email` (or `https://api.openai.com/profile`.email). `https://api.openai.com/auth` has `chatgpt_account_id` and `chatgpt_plan_type` | [token_data.rs#L87-L215](https://github.com/openai/codex/blob/c899e21cda29fdd422898ffa54893f67ece34039/codex-rs/login/src/token_data.rs#L87) |
| auth.json | `{auth_mode?, OPENAI_API_KEY, tokens:{id_token (raw JWT), access_token, refresh_token, account_id}, last_refresh}` | [auth/storage.rs#L51-L77](https://github.com/openai/codex/blob/c899e21cda29fdd422898ffa54893f67ece34039/codex-rs/login/src/auth/storage.rs#L51-L77) |

Some of the values I had from memory were wrong for the current Codex: the redirect host is now `127.0.0.1`, the
scope adds `api.connectors.*`, and the refresh body is JSON with `refresh_reason`, not form-encoded.

**Refresh tokens rotate.** Each refresh returns a new refresh token, and Codex treats `refresh_token_reused` as a
permanent error. So:
- refreshes are serialized behind a mutex, and the new token is written with `commit()` before it's used.
- if you import a Mac's `~/.codex/auth.json` and then refresh on the phone, the Mac's copy goes stale. The Codex CLI
  on the Mac will then fail its next refresh and need `codex login`. A phone that signs in through `LoginActivity`
  gets its own token family and has no such conflict.

## Storage
Tokens are stored as one JSON blob in the private SharedPreferences file `vc_chatgpt_auth`, encrypted with AES-256-GCM.
The key is in the Android Keystore (alias `vc_chatgpt_auth_key`, non-exportable). I didn't use
EncryptedSharedPreferences because `androidx.security:security-crypto` is deprecated and the code needed here is
about 40 lines. `allowBackup=false` already keeps the blob out of backups. If the blob can't be decrypted, it is
deleted and the user is treated as signed out. Sign-out deletes the blob locally. The token is not revoked on the
server.

## Verified on the emulator (2026-11-13)
- Import of `~/.codex/auth.json` works, both from `/data/local/tmp` (the pushed file is world-readable) and with
  `run-as`. `AUTH_STATUS` showed `SignedIn` with the account id, `plan=prolite`, the email, and the access-token
  expiry.
- `AUTH_PROBE` against `POST https://chatgpt.com/backend-api/codex/realtime/calls?intent=quicksilver&architecture=avas`
  returned **HTTP 400**
  `{"error":{"message":"Offer did not have an audio media section.","type":"invalid_request_error","code":"invalid_offer"}}`.
  The response also had a `location: /v1/realtime/calls/rtc_…` header. Auth and plan passed; only the dummy SDP was
  rejected.
- Refresh request format: the real token endpoint answers a dummy refresh token with 401 `invalid_refresh_token`,
  and a wrong client_id with 401 `invalid_client`. So the JSON shape and client_id are accepted. A full refresh with
  the imported token was deliberately not run, because it would make the Mac's Codex CLI sign-in stale (see above).
  To run it: `AUTH_REFRESH`.
- Browser flow: `AUTH_LOGIN` → the keep-alive FGS runs (`fg-service-short`, not frozen), the listener is on
  127.0.0.1:1455, and Chrome shows OpenAI's "Welcome back" login page at `auth.openai.com/log-in` with the Codex
  terms footer, which shows the client_id, redirect_uri, and scope are accepted. Sending
  `http://127.0.0.1:1455/auth/callback?code=fake&state=wrong` through Chrome showed the error page, brought
  LoginActivity to the front with "State mismatch", and kept the stored account. The final code-for-token exchange
  after a real login wasn't run, because that needs the user's password.

A real ChatGPT sign-in (code exchange included) was later completed by the user on a phone with this flow.

## Verified on the emulator (T6 login UI, 2026-11-13, Android 16, emulator-5554)
- Cold start signed out → MainActivity opens the onboarding sign-in screen. Sign in → Custom Tab
  (`org.chromium.chrome.browser.customtabs.CustomTabActivity`) in our task above LoginActivity, OpenAI's
  "Welcome back" page. Closing the tab → Waiting screen with countdown.
- Rotation to landscape and back while waiting: same attempt, same listener, no second tab.
- Wrong-state callback through Chrome → app back in front, "Sign-in couldn't be verified".
- `AUTH_CALLBACK` with the real state and a bogus code → the real `auth.openai.com/oauth/token` answered
  `HTTP 401 token_expired` → "Token exchange failed"; the Custom Tab was cleared by the CLEAR_TOP relaunch.
- Double tap on Try again → one attempt, one tab. Open browser again → same attempt. Cancel → "Sign-in cancelled".
- Port 1455 held by a foreign listener (toybox `nc`) → `/cancel` ignored → fallback to 1457, and OpenAI showed its
  login page for the 1457 redirect URI. `error=access_denied` callback → "Sign-in was declined".
- Process killed (`run-as … kill -9`) while the tab was in front → back from the tab: "resuming a sign-in on port
  1455", and a callback with the original state passed the state check and reached the token endpoint.
- "Don't keep activities" on: the result still arrives in the recreated LoginActivity.
- Overlay permission denied and our accessibility service off: the post-callback relaunch still brought the app
  to the front (`BAL_ALLOW_FOREGROUND`).
- Result page's "Back to Voice Control" button → `AuthReturnActivity` → the existing LoginActivity (no duplicate).
- API key path saves the key, sets `openai_key`, and MainActivity's radio buttons follow. Import → MainActivity row
  "email · ChatGPT Pro Lite" → Manage → account card → Sign out (confirm) → store empty; a `chatgpt` backend setting
  was reset to `auto`. Bubble tap while unconfigured → sign-in screen.
- JVM tests: `CodexOAuthTest` (MockWebServer token endpoint: exact code-exchange form body, JSON refresh body,
  token/claim parsing, error mapping and redaction), `LoginFlowTest`, `LoopbackServerTest` (real sockets: callback
  parsing, 404s, `/cancel`, timeout, fallback port, takeover from an older login, `bindExact`).

## Debug hooks (debug builds only: `src/debug/.../debug/AuthDebugReceiver.kt`, log tag `VC/AuthDebug`)
```bash
P=com.brainstormity.voicecontrol
# Import (option A: via /data/local/tmp; delete it afterwards)
adb push ~/.codex/auth.json /data/local/tmp/codex-auth.json
adb shell am broadcast -p $P -a $P.AUTH_IMPORT --es path /data/local/tmp/codex-auth.json
adb shell rm /data/local/tmp/codex-auth.json
# Import (option B: straight into the app sandbox, no world-readable copy; the app deletes it after import)
adb exec-in run-as $P sh -c 'mkdir -p files && cat > files/codex-auth.json' < ~/.codex/auth.json
adb shell am broadcast -p $P -a $P.AUTH_IMPORT --es path codex-auth.json

adb shell am broadcast -p $P -a $P.AUTH_STATUS    # state, account id, plan, email, access expiry, last refresh
adb shell am broadcast -p $P -a $P.AUTH_REFRESH   # forced refresh: ok/error + new expiry (rotates the refresh token!)
adb shell am broadcast -p $P -a $P.AUTH_PROBE     # GPT-Live call-creation probe: HTTP status + first 300 chars
adb shell am broadcast -p $P -a $P.AUTH_LOGIN     # opens LoginActivity and starts the browser sign-in
adb shell am broadcast -p $P -a $P.AUTH_SIGNOUT
# Play OpenAI's redirect for the pending sign-in (state filled in from the app unless overridden; never logged):
adb shell am broadcast -p $P -a $P.AUTH_CALLBACK --es code bogus            # via the browser -> "Token exchange failed"
adb shell am broadcast -p $P -a $P.AUTH_CALLBACK --es error access_denied   # -> "Sign-in was declined"
adb shell am broadcast -p $P -a $P.AUTH_CALLBACK --es code x --es state wrong --ez direct true  # raw socket, no browser
adb logcat -s VC/Auth:V VC/AuthDebug:V
```
`-p $P` makes the broadcast explicit, so it also reaches a process that isn't running. Without `-p`, the broadcast
only arrives while the process is alive, through the runtime-registered copy (same pattern as `DebugReceiver`). A
freshly installed app is in the "stopped" state and ignores even explicit broadcasts until one of its activities
has been opened, for example with `adb shell am start -n $P/.ui.MainActivity` (LoginActivity itself is no longer
exported; only the `AuthReturnActivity` trampoline is).

## Files
- `ChatGptAuth.kt`: public API, state, refresh, login orchestration, import (`AuthState`, `Credentials`)
- `CodexOAuth.kt`: constants, PKCE, authorize URL, code exchange and refresh HTTP calls (OkHttp; issuer injectable)
- `LoginFlow.kt`: `LoginState`, `LoginFailure`, `PendingLogin`, and the pure callback / plan / error logic
- `LoopbackServer.kt`: ServerSocket-based callback listener (ports injectable for tests, `bindExact` for resume)
- `TokenStore.kt`: Keystore-encrypted storage
- `Jwt.kt`: claim parsing (`exp`, account id, plan, email), pure JVM
- `LoginActivity.kt` + `res/layout/activity_login.xml`: sign-in / onboarding / account screen, Custom Tabs
- `AuthReturnActivity.kt`: invisible trampoline for the result page's "Back to Voice Control" link
- `LoginKeepAliveService.kt`: `specialUse` foreground service that runs during sign-in
- Tests: `app/src/test/.../voice/auth/{CodexOAuthTest,LoginFlowTest,LoopbackServerTest}.kt`

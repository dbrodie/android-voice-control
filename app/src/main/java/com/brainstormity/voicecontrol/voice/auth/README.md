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
- Other API: `ChatGptAuth.state: StateFlow<AuthState>` (`SignedOut`, `SigningIn`, `SignedIn(email, plan, accountId)`,
  `Error(message)`), `isSignedIn(ctx)`, `startLogin(ctx)`, `cancelLogin(ctx)`, `signOut(ctx)`,
  `importCodexAuthJson(ctx, json)`, `refreshNow(ctx)`, `info(ctx)` (contains no secrets), and `init(ctx)`.
  `init(ctx)` loads the stored sign-in into `state`. Every method that takes a `ctx` calls it for you, so call it
  directly only if you need `state` before calling anything else.
- UI: `LoginActivity.start(ctx)` opens the sign-in screen. `LoginActivity.start(ctx, autoStart = true)` also starts
  the browser sign-in right away. The integration can put a "Sign in with ChatGPT" button on MainActivity that
  calls this.
- Never log `accessToken` or the refresh token. `Credentials.toString()` redacts the token.

## Flow (LoginActivity → ChatGptAuth.startLogin)
1. Start `LoginKeepAliveService`, a `shortService` foreground service that runs for at most about 3 minutes. Without
   it, Android 14+'s cached-app freezer suspends our process as soon as Chrome is in front, and the loopback
   server never answers. We saw this on the emulator: the process showed `cch-act isFrozen=true` and the callback
   connection sat in the accept queue. Inside the full app, a bound accessibility service also keeps the process
   alive, but the keep-alive also covers a first-run sign-in before accessibility is enabled.
2. Bind `127.0.0.1:1455`. If the port is busy, send `GET /cancel` to whoever holds it, retry for about 2 s, then
   fall back to `127.0.0.1:1457`. This copies Codex.
3. Generate PKCE (64 random bytes → base64url verifier, S256 challenge) and `state` (32 random bytes, base64url).
4. Open the authorize URL with `ACTION_VIEW` (no Custom Tabs dependency).
5. The browser is redirected to `http://127.0.0.1:1455/auth/callback?code=…&state=…`. Check `state`, `error`, and
   `code`, then exchange the code for tokens. The page shown in the browser is "You're signed in" (or an error
   page). It has a "Back to Voice Control" link
   (`intent://done#Intent;scheme=com.brainstormity.voicecontrol.auth;package=…;end`).
6. Save the tokens, set `state = SignedIn`, and bring `LoginActivity` to the front. Bringing it to the front is
   allowed because of the bound accessibility service, the SYSTEM_ALERT_WINDOW grant, or the visible overlay. If
   `LoginActivity` isn't the root of its task, it finishes after 1.5 s, so the user lands back on the screen that
   opened it.
7. On timeout (3 min), cancel, `/cancel`, a state mismatch, an OAuth error, or a failed exchange: `state = Error(msg)`
   or the previous state. Any tokens that were already stored are kept.

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
adb logcat -s VC/Auth:V VC/AuthDebug:V
```
`-p $P` makes the broadcast explicit, so it also reaches a process that isn't running. Without `-p`, the broadcast
only arrives while the process is alive, through the runtime-registered copy (same pattern as `DebugReceiver`). A
freshly installed app is in the "stopped" state and ignores even explicit broadcasts until one of its activities
has been opened, for example with `adb shell am start -n $P/.voice.auth.LoginActivity`.

## Files
- `ChatGptAuth.kt`: public API, state, refresh, login orchestration, import (`AuthState`, `Credentials`)
- `CodexOAuth.kt`: constants, PKCE, authorize URL, code exchange and refresh HTTP calls (OkHttp)
- `LoopbackServer.kt`: ServerSocket-based callback listener
- `TokenStore.kt`: Keystore-encrypted storage
- `Jwt.kt`: claim parsing (`exp`, account id, plan, email)
- `LoginActivity.kt`: sign-in screen (plain Views)
- `LoginKeepAliveService.kt`: `shortService` foreground service that runs during sign-in

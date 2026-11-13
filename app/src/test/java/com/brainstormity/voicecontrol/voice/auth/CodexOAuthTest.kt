package com.brainstormity.voicecontrol.voice.auth

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * [CodexOAuth] against a MockWebServer playing auth.openai.com's token endpoint: the exact code-exchange and
 * refresh requests Codex sends, response parsing, the id_token claims we read, and error mapping.
 */
class CodexOAuthTest {
    private lateinit var server: MockWebServer
    private lateinit var oauth: CodexOAuth

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
        oauth = CodexOAuth(issuer = server.url("/").toString(), http = http)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun b64url(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())

    /** Unsigned JWT with the claims auth.openai.com puts in a ChatGPT id_token. */
    private fun jwt(claims: JSONObject) = b64url("""{"alg":"RS256","typ":"JWT"}""") + "." + b64url(claims.toString()) + ".sig"

    private val idToken = jwt(
        JSONObject()
            .put("email", "user@example.com")
            .put("exp", 4_102_444_800L) // 2100-01-01
            .put(
                "https://api.openai.com/auth",
                JSONObject().put("chatgpt_account_id", "acc-123").put("chatgpt_plan_type", "pro"),
            )
    )
    private val accessToken = jwt(
        JSONObject()
            .put("exp", 4_102_444_800L)
            .put("https://api.openai.com/auth", JSONObject().put("chatgpt_account_id", "acc-123"))
            .put("https://api.openai.com/profile", JSONObject().put("email", "user@example.com"))
    )

    private fun tokenJson(withRefresh: Boolean = true) = JSONObject()
        .put("id_token", idToken)
        .put("access_token", accessToken)
        .apply { if (withRefresh) put("refresh_token", "rt-new") }
        .put("token_type", "Bearer")
        .put("expires_in", 864000)
        .toString()

    @Test
    fun exchangeCode_sendsCodexFormRequest_andParsesTokens() {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(tokenJson()))
        val pkce = CodexOAuth.generatePkce()
        val redirect = CodexOAuth.redirectUri(1455)

        val r = oauth.exchangeCode("ac_code/with+odd=chars", redirect, pkce)

        val req = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", req.method)
        assertEquals("/oauth/token", req.path)
        assertEquals("application/x-www-form-urlencoded", req.getHeader("Content-Type")?.substringBefore(';'))
        val body = req.body.readUtf8()
        // Exact wire format (the one a real sign-in was verified with), form-encoded (':' '/' '+' '=' escaped).
        assertEquals(
            "grant_type=authorization_code" +
                "&client_id=app_EMoamEEZ73f0CkXaXp7hrann" +
                "&code=ac_code%2Fwith%2Bodd%3Dchars" +
                "&redirect_uri=http%3A%2F%2F127.0.0.1%3A1455%2Fauth%2Fcallback" +
                "&code_verifier=${pkce.verifier}",
            body,
        )
        val fields = body.split('&').associate { p ->
            URLDecoder.decode(p.substringBefore('='), "UTF-8") to URLDecoder.decode(p.substringAfter('='), "UTF-8")
        }
        assertEquals(setOf("grant_type", "code", "redirect_uri", "client_id", "code_verifier"), fields.keys)
        assertEquals("authorization_code", fields["grant_type"])
        assertEquals("app_EMoamEEZ73f0CkXaXp7hrann", fields["client_id"])
        assertEquals("ac_code/with+odd=chars", fields["code"])
        assertEquals("http://127.0.0.1:1455/auth/callback", fields["redirect_uri"])
        assertEquals(pkce.verifier, fields["code_verifier"])

        assertEquals(idToken, r.idToken)
        assertEquals(accessToken, r.accessToken)
        assertEquals("rt-new", r.refreshToken)
        // The claims the app shows / sends.
        assertEquals("user@example.com", Jwt.email(r.idToken))
        assertEquals("pro", Jwt.planType(r.idToken))
        assertEquals("acc-123", Jwt.accountId(r.idToken))
        assertEquals("acc-123", Jwt.accountId(r.accessToken))
        assertEquals("user@example.com", Jwt.email(r.accessToken)) // via the profile claim
        assertEquals(4_102_444_800_000L, Jwt.expiresAtMs(r.accessToken))
    }

    @Test
    fun exchangeCode_usesFallbackPortRedirect() {
        server.enqueue(MockResponse().setBody(tokenJson()))
        oauth.exchangeCode("c", CodexOAuth.redirectUri(1457), CodexOAuth.generatePkce())
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A1457%2Fauth%2Fcallback"))
    }

    @Test
    fun refresh_sendsCodexJsonRequest_andParsesRotatedTokens() {
        server.enqueue(MockResponse().setBody(tokenJson()))

        val r = oauth.refresh("rt-old", "proactive_expiry")

        val req = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", req.method)
        assertEquals("/oauth/token", req.path)
        assertEquals("application/json", req.getHeader("Content-Type")?.substringBefore(';'))
        val json = JSONObject(req.body.readUtf8())
        assertEquals(setOf("client_id", "grant_type", "refresh_reason", "refresh_token"), json.keySet())
        assertEquals("app_EMoamEEZ73f0CkXaXp7hrann", json.getString("client_id"))
        assertEquals("refresh_token", json.getString("grant_type"))
        assertEquals("proactive_expiry", json.getString("refresh_reason"))
        assertEquals("rt-old", json.getString("refresh_token"))
        assertEquals("rt-new", r.refreshToken)
        assertEquals("acc-123", Jwt.accountId(r.idToken))
    }

    @Test
    fun refresh_withoutNewRefreshToken_keepsNull() {
        server.enqueue(MockResponse().setBody(tokenJson(withRefresh = false)))
        assertNull(oauth.refresh("rt", "forced").refreshToken)
    }

    @Test
    fun badCode_mapsToTokenExchangeProblem_andRedactsSecrets() {
        // What auth.openai.com answers for a bogus code (the app shows "Token exchange failed").
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"error":"invalid_grant","error_description":"Invalid authorization code: secret-code"}"""
            )
        )
        val pkce = CodexOAuth.generatePkce()
        try {
            oauth.exchangeCode("secret-code", CodexOAuth.redirectUri(1455), pkce)
            fail("expected TokenEndpointException")
        } catch (e: CodexOAuth.TokenEndpointException) {
            assertEquals(400, e.status)
            assertEquals("invalid_grant", e.errorCode)
            assertTrue(e.isPermanent)
            assertFalse(e.message!!, e.message!!.contains("secret-code"))
            assertTrue(e.message!!, e.message!!.contains("[redacted]"))
            val p = LoginFlow.exchangeProblem(e)
            assertEquals(LoginFailure.TOKEN_EXCHANGE, p.reason)
            assertTrue(p.detail, p.detail.contains("HTTP 400 invalid_grant"))
        }
    }

    @Test
    fun nestedErrorObject_isParsed() {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody("""{"error":{"code":"refresh_token_reused","message":"already used"}}""")
        )
        try {
            oauth.refresh("rt", "forced")
            fail("expected TokenEndpointException")
        } catch (e: CodexOAuth.TokenEndpointException) {
            assertEquals(401, e.status)
            assertEquals("refresh_token_reused", e.errorCode)
            assertTrue(e.isPermanent)
        }
    }

    @Test
    fun serverErrorIsNotPermanent() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("upstream down"))
        try {
            oauth.refresh("rt", "forced")
            fail("expected TokenEndpointException")
        } catch (e: CodexOAuth.TokenEndpointException) {
            assertEquals(503, e.status)
            assertFalse(e.isPermanent)
        }
    }

    @Test
    fun droppedConnection_mapsToNetworkProblem() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        try {
            oauth.exchangeCode("c", CodexOAuth.redirectUri(1455), CodexOAuth.generatePkce())
            fail("expected IOException")
        } catch (e: CodexOAuth.TokenEndpointException) {
            fail("a dropped connection is not an HTTP answer: $e")
        } catch (e: IOException) {
            assertEquals(LoginFailure.NETWORK, LoginFlow.exchangeProblem(e).reason)
        }
    }

    @Test
    fun nonJsonSuccess_isAnError() {
        server.enqueue(MockResponse().setBody("<html>captive portal</html>"))
        try {
            oauth.exchangeCode("c", CodexOAuth.redirectUri(1455), CodexOAuth.generatePkce())
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("non-JSON"))
        }
    }

    @Test
    fun pkce_isS256OfVerifier_andAuthorizeUrlCarriesCodexParams() {
        val pkce = CodexOAuth.generatePkce()
        assertEquals(86, pkce.verifier.length) // 64 bytes base64url, no padding
        val expected = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(pkce.verifier.toByteArray()))
        assertEquals(expected, pkce.challenge)
        assertEquals(pkce.challenge, CodexOAuth.pkceFor(pkce.verifier).challenge)

        val state = CodexOAuth.generateState()
        val url = CodexOAuth().authorizeUrl(CodexOAuth.redirectUri(1457), pkce, state)
        val uri = URI(url)
        assertEquals("https://auth.openai.com/oauth/authorize", url.substringBefore('?'))
        val q = uri.rawQuery.split('&').associate { p ->
            URLDecoder.decode(p.substringBefore('='), "UTF-8") to URLDecoder.decode(p.substringAfter('='), "UTF-8")
        }
        assertEquals("code", q["response_type"])
        assertEquals("app_EMoamEEZ73f0CkXaXp7hrann", q["client_id"])
        assertEquals("http://127.0.0.1:1457/auth/callback", q["redirect_uri"])
        assertEquals(pkce.challenge, q["code_challenge"])
        assertEquals("S256", q["code_challenge_method"])
        assertEquals(state, q["state"])
        assertEquals("openid profile email offline_access api.connectors.read api.connectors.invoke", q["scope"])
        assertEquals("true", q["id_token_add_organizations"])
        assertEquals("true", q["codex_cli_simplified_flow"])
        assertEquals("codex_cli_rs", q["originator"])
        assertFalse("the verifier must never leave the app in the URL", url.contains(pkce.verifier))
    }
}

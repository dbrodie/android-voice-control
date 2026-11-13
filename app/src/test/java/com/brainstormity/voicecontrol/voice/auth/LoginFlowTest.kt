package com.brainstormity.voicecontrol.voice.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Callback validation, plan rules and error mapping of the sign-in flow. */
class LoginFlowTest {
    private val st = "expected-state"

    @Test
    fun validCallback_passes() {
        assertNull(LoginFlow.checkCallback(mapOf("code" to "abc", "state" to st), st))
    }

    @Test
    fun wrongOrMissingState_isStateMismatch_evenWithErrorParams() {
        assertEquals(LoginFailure.STATE_MISMATCH, LoginFlow.checkCallback(mapOf("code" to "abc", "state" to "wrong"), st)!!.reason)
        assertEquals(LoginFailure.STATE_MISMATCH, LoginFlow.checkCallback(mapOf("code" to "abc"), st)!!.reason)
        // A forged callback must not be able to show an OpenAI error either.
        assertEquals(LoginFailure.STATE_MISMATCH, LoginFlow.checkCallback(mapOf("error" to "access_denied", "state" to "x"), st)!!.reason)
    }

    @Test
    fun errorParams_areReported() {
        val denied = LoginFlow.checkCallback(
            mapOf("error" to "access_denied", "error_description" to "User cancelled", "state" to st), st
        )!!
        assertEquals(LoginFailure.DENIED, denied.reason)
        assertTrue(denied.detail, denied.detail.contains("User cancelled"))

        val other = LoginFlow.checkCallback(mapOf("error" to "server_error", "state" to st, "code" to "abc"), st)!!
        assertEquals(LoginFailure.OAUTH_ERROR, other.reason)
        assertTrue(other.detail, other.detail.contains("server_error"))
    }

    @Test
    fun missingCode_isAnError() {
        assertEquals(LoginFailure.OAUTH_ERROR, LoginFlow.checkCallback(mapOf("state" to st), st)!!.reason)
        assertEquals(LoginFailure.OAUTH_ERROR, LoginFlow.checkCallback(mapOf("state" to st, "code" to ""), st)!!.reason)
    }

    @Test
    fun plans() {
        assertTrue(LoginFlow.planHasVoice("plus"))
        assertTrue(LoginFlow.planHasVoice("pro"))
        assertTrue(LoginFlow.planHasVoice("prolite"))
        assertTrue(LoginFlow.planHasVoice("team"))
        assertTrue(LoginFlow.planHasVoice(null)) // unknown: let the server decide
        assertTrue(LoginFlow.planHasVoice("some_new_plan"))
        assertFalse(LoginFlow.planHasVoice("free"))
        assertFalse(LoginFlow.planHasVoice("FREE"))

        assertEquals("ChatGPT Pro", LoginFlow.planDisplayName("pro"))
        assertEquals("ChatGPT Pro Lite", LoginFlow.planDisplayName("prolite"))
        assertEquals("ChatGPT Plus", LoginFlow.planDisplayName("plus"))
        assertEquals("ChatGPT Some New Plan", LoginFlow.planDisplayName("some_new_plan"))
        assertNull(LoginFlow.planDisplayName(null))

        val p = LoginFlow.noVoiceProblem("a@b.c", "free")
        assertEquals(LoginFailure.NO_VOICE_PLAN, p.reason)
        assertTrue(p.detail, p.detail.contains("a@b.c") && p.detail.contains("ChatGPT Free"))
    }

    @Test
    fun exchangeErrors_mapToReasons() {
        assertEquals(LoginFailure.NETWORK, LoginFlow.exchangeProblem(UnknownHostException("auth.openai.com")).reason)
        assertEquals(LoginFailure.NETWORK, LoginFlow.exchangeProblem(SocketTimeoutException("timeout")).reason)
        assertEquals(
            LoginFailure.TOKEN_EXCHANGE,
            LoginFlow.exchangeProblem(CodexOAuth.TokenEndpointException(400, "invalid_grant", "x")).reason,
        )
        assertEquals(LoginFailure.INTERNAL, LoginFlow.exchangeProblem(IllegalStateException("boom")).reason)
    }

    @Test
    fun pendingLogin_redirectUriFollowsPort_andToStringHidesSecrets() {
        val p = PendingLogin("verifier-secret", "state-secret", 1457, "https://auth.openai.com/oauth/authorize?x", 1L)
        assertEquals("http://127.0.0.1:1457/auth/callback", p.redirectUri)
        assertFalse(p.toString().contains("secret"))
    }
}

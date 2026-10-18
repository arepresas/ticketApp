package com.ticketapp.android.net

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Contract tests for [BffAuthClient] against the three BFF endpoints.
 *
 * These lock the wire shape to what `bff/.../auth/AuthController`
 * actually returns and rejects — the one place in this module where a
 * silent BFF change would otherwise surface as "the app stopped
 * logging in" with no failing test.
 */
class BffAuthClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: BffAuthClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = BffAuthClient(
            webOrigin = server.url("/").toString().trimEnd('/'),
            httpClient = OkHttpClient(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `signIn posts the idToken to the auth google endpoint`() = runTest {
        server.enqueue(
            jsonResponse(
                """
                {"token":"jwt-abc","user":{"id":7,"email":"a@b.c","name":"Ada","pictureUrl":"http://p/1"}}
                """.trimIndent(),
            ),
        )

        val session = client.signInWithGoogle("google-id-token")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/auth/google", request.path)
        // No Authorization header: this endpoint is permitAll, and the
        // Google credential is single-use.
        assertEquals(null, request.getHeader("Authorization"))

        val body = request.body.readUtf8()
        assertTrue("body should carry the idToken, was: $body", body.contains("\"idToken\":\"google-id-token\""))

        assertEquals("jwt-abc", session.token)
        assertEquals(7L, session.user.id)
        assertEquals("a@b.c", session.user.email)
        assertEquals("Ada", session.user.name)
        assertEquals("http://p/1", session.user.pictureUrl)
    }

    @Test
    fun `signIn accepts a user without a picture`() = runTest {
        server.enqueue(
            jsonResponse("""{"token":"t","user":{"id":1,"email":"e","name":"n"}}"""),
        )

        val session = client.signInWithGoogle("id")

        assertNull(session.user.pictureUrl)
    }

    @Test
    fun `signIn fails when the BFF rejects the Google token`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"code":"invalid_google_token"}""")
                .addHeader("Content-Type", "application/json"),
        )

        // The failure must propagate: the caller keeps the user on the
        // login screen instead of proceeding with no session.
        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking { client.signInWithGoogle("bad") }
        }
    }

    @Test
    fun `fetchMe sends the token as a bearer header`() = runTest {
        server.enqueue(
            jsonResponse("""{"id":7,"email":"a@b.c","name":"Ada","pictureUrl":null}"""),
        )

        val user = client.fetchMe("jwt-abc")

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/auth/me", request.path)
        assertEquals("Bearer jwt-abc", request.getHeader("Authorization"))

        assertEquals(7L, user?.id)
    }

    @Test
    fun `fetchMe returns null on 401 so the caller can clear the session`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        // Not an exception: a dead token is an expected outcome of a
        // cold start, and the ViewModel clears the store on null.
        assertNull(client.fetchMe("revoked-token"))
    }

    @Test
    fun `fetchMe returns null on 403 too`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))

        assertNull(client.fetchMe("forbidden"))
    }

    @Test
    fun `fetchMe propagates a server error as an IOException`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking { client.fetchMe("token") }
        }
    }

    @Test
    fun `logout posts the bearer token and reports success`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        assertTrue(client.logout("jwt-abc"))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/auth/logout", request.path)
        assertEquals("Bearer jwt-abc", request.getHeader("Authorization"))
    }

    @Test
    fun `logout reports failure instead of throwing`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        // Best-effort by contract: the caller clears locally regardless,
        // so this must not throw.
        assertTrue(!client.logout("jwt-abc"))
    }

    @Test
    fun `logout tolerates an unreachable server`() = runTest {
        server.shutdown()

        assertTrue(!client.logout("jwt-abc"))
    }

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setBody(body)
        .addHeader("Content-Type", "application/json")
}

/** Local alias so the import list stays readable in the assertions above. */
private typealias IOException = java.io.IOException
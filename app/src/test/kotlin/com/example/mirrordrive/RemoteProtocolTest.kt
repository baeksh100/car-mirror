package com.example.mirrordrive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteProtocolTest {
    private val key = "abc234"

    private fun route(line: String): RemoteRoute {
        val (path, q) = parseRequestLine(line)!!
        return routeRemote(path, q, key)
    }

    @Test fun parsesRequestLine() {
        assertEquals("/cmd/home" to mapOf("k" to "abc"), parseRequestLine("GET /cmd/home?k=abc HTTP/1.1"))
        assertEquals("/" to emptyMap<String, String>(), parseRequestLine("GET / HTTP/1.1"))
        assertNull(parseRequestLine("POST / HTTP/1.1"))
        assertNull(parseRequestLine(""))
        assertNull(parseRequestLine(null))
    }

    @Test fun decodesQuery() {
        assertEquals(mapOf("p" to "com.a b", "k" to ""), parseQuery("p=com.a%20b&k"))
    }

    @Test fun routesCommandsWithKey() {
        assertEquals(RemoteRoute.Command(RemoteCommand.HOME), route("GET /cmd/home?k=$key HTTP/1.1"))
        assertEquals(RemoteRoute.Command(RemoteCommand.BACK), route("GET /cmd/back?k=$key&t=1 HTTP/1.1"))
        assertEquals(RemoteRoute.Page, route("GET /?k=$key HTTP/1.1"))
        assertEquals(RemoteRoute.OpenApp("com.kakao.navi"), route("GET /open?k=$key&p=com.kakao.navi HTTP/1.1"))
        assertEquals(RemoteRoute.NotFound, route("GET /cmd/explode?k=$key HTTP/1.1"))
    }

    @Test fun wrongOrMissingKeyIsForbidden() {
        assertEquals(RemoteRoute.Forbidden, route("GET /cmd/home HTTP/1.1"))
        assertEquals(RemoteRoute.Forbidden, route("GET /cmd/home?k=nope HTTP/1.1"))
        assertEquals(RemoteRoute.Forbidden, routeRemote("/cmd/home", mapOf("k" to ""), ""))
    }

    @Test fun badPackageNameIsRejected() {
        assertEquals(RemoteRoute.NotFound, route("GET /open?k=$key&p=../etc HTTP/1.1"))
    }

    @Test fun touchIconNeedsNoKey() {
        assertEquals(RemoteRoute.TouchIcon, route("GET /apple-touch-icon.png HTTP/1.1"))
    }

    @Test fun keyShape() {
        val k = newRemoteKey(java.util.Random(1))
        assertEquals(6, k.length)
        assertTrue(k.all { it in "abcdefghijkmnpqrstuvwxyz23456789" })
        assertEquals("http://172.20.10.2:8765/?k=$k", remoteUrl("172.20.10.2", k))
    }

    @Test fun escapesJson() {
        assertEquals("\"a\\\"b\\\\c\\u000a\"", jsonStr("a\"b\\c\n"))
    }
}

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

    @Test fun routesHomeChoice() {
        assertEquals(RemoteRoute.SetHome("com.car.launcher"), route("GET /sethome?k=$key&p=com.car.launcher HTTP/1.1"))
        assertEquals(RemoteRoute.SetHome(null), route("GET /sethome?k=$key&p= HTTP/1.1"))
        assertEquals(RemoteRoute.SetHome(null), route("GET /sethome?k=$key HTTP/1.1"))
        assertEquals(RemoteRoute.NotFound, route("GET /sethome?k=$key&p=a/b HTTP/1.1"))
    }

    @Test fun routesText() {
        assertEquals(RemoteRoute.TypeText("안녕 하세요+1", false),
            route("GET /text?k=$key&t=%EC%95%88%EB%85%95%20%ED%95%98%EC%84%B8%EC%9A%94%2B1 HTTP/1.1"))
        assertEquals(RemoteRoute.TypeText("x", true), route("GET /text?k=$key&t=x&mode=replace HTTP/1.1"))
        assertEquals(RemoteRoute.NotFound, route("GET /text?k=$key HTTP/1.1"))
        assertEquals(RemoteRoute.Command(RemoteCommand.ENTER), route("GET /cmd/enter?k=$key HTTP/1.1"))
    }

    @Test fun insertsAtCursor() {
        assertEquals("ab강남c" to 4, insertText("abc", false, 2, 2, "강남", false))
        assertEquals("abc강남" to 5, insertText("abc", false, -1, -1, "강남", false))
        assertEquals("a강남" to 3, insertText("abc", false, 1, 3, "강남", false))
        assertEquals("강남" to 2, insertText("검색", true, 0, 0, "강남", false))
        assertEquals("강남" to 2, insertText("abc", false, 1, 1, "강남", true))
        assertEquals("x" to 1, insertText(null, false, 5, 9, "x", false))
    }

    @Test fun backspaces() {
        assertEquals("ac" to 1, backspaceText("abc", false, 2, 2))
        assertEquals("ab" to 2, backspaceText("abc", false, -1, -1))
        assertEquals("a" to 1, backspaceText("abc", false, 1, 3))
        assertEquals("abc" to 0, backspaceText("abc", false, 0, 0))
        assertEquals("" to 0, backspaceText("검색", true, -1, -1))
        assertEquals("a" to 1, backspaceText("a\uD83D\uDE00", false, -1, -1))
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

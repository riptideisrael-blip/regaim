package com.regaim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedReader
import java.io.IOException
import java.io.StringReader

class SseTest {
    private fun r(s: String) = BufferedReader(StringReader(s.trimIndent()))

    private val full = """
        event: message_start
        data: {"type":"message_start","message":{"id":"m1"}}

        event: ping
        data: {"type": "ping"}

        event: content_block_delta
        data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"{\"title\":\"אורי"}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":" והלילה\"}"}}

        event: message_delta
        data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}

        event: message_stop
        data: {"type":"message_stop"}
    """

    @Test fun tellsWhyItStopped() {
        assertEquals("end_turn", Sse.readFull(r(full)).stopReason)
        val cut = full.replace("end_turn", "max_tokens")
        val got = Sse.readFull(r(cut))
        assertEquals("max_tokens", got.stopReason)
        assertTrue(got.finished)
    }

    @Test fun joinsTheTextAndSeesTheEnd() {
        var seen = 0
        val (text, done) = Sse.read(r(full)) { seen = it }
        assertEquals("{\"title\":\"אורי והלילה\"}", text)
        assertTrue(done)
        assertEquals(text.length, seen)
    }

    @Test fun aCutStreamIsNotFinished() {
        val cut = full.substringBefore("event: message_delta")
        val (text, done) = Sse.read(r(cut))
        assertFalse(done)
        assertTrue(text.startsWith("{\"title\""))
    }

    @Test fun overloadedIsRetryableOtherErrorsAreNot() {
        try {
            Sse.read(r("""data: {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""))
            fail()
        } catch (_: IOException) { }
        try {
            Sse.read(r("""data: {"type":"error","error":{"type":"invalid_request_error","message":"bad"}}"""))
            fail()
        } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("bad")) }
    }
}

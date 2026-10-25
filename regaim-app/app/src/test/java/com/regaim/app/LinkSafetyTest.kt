package com.regaim.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkSafetyTest {
    private val ours = "https://acqgmcmvxijyijhgivnt.supabase.co"

    @Test fun inviteServerOnlyOurs() {
        assertTrue(LinkSafety.sameServer(ours, ours))
        assertTrue(LinkSafety.sameServer("$ours/rest/v1/", ours))
        assertFalse(LinkSafety.sameServer("https://acqgmcmvxijyijhgivnt.supabase.co.evil.com", ours))
        assertFalse(LinkSafety.sameServer("https://evil.com/acqgmcmvxijyijhgivnt.supabase.co", ours))
        assertFalse(LinkSafety.sameServer("https://user@evil.com", ours))
        assertFalse(LinkSafety.sameServer("http://acqgmcmvxijyijhgivnt.supabase.co", ours))
        assertFalse(LinkSafety.sameServer("", ours))
    }

    @Test fun signInMustBeStartedHere() {
        val t = 1_000_000_000L
        assertTrue(LinkSafety.redirectExpected(t, t + 60_000))
        assertFalse(LinkSafety.redirectExpected(0, t))
        assertFalse(LinkSafety.redirectExpected(t, t + 16 * 60_000))
        assertFalse(LinkSafety.redirectExpected(t, t - 1))
    }

    @Test fun inviteCode() {
        assertTrue(LinkSafety.inviteCodeValid("0123456789abcdef0123456789ABCDEF"))
        assertFalse(LinkSafety.inviteCodeValid("0123"))
        assertFalse(LinkSafety.inviteCodeValid("0123456789abcdef0123456789abcdeg"))
    }
}

package com.regaim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CvvTest {
    @Test fun theSecurityCodeNeverGoesOut() {
        for (s in listOf("CVV 848", "cvv:848", "CVV: 848", "CVV-848", "CVV #848", "CVV2 848", "CVC2 1234", "CVV=848", "848 CVV", "קוד אבטחה 848", "קוד האבטחה: 848", "3 ספרות בגב 848")) {
            val out = FamilyShare.withoutCvv("כרטיס מתנה $s תוקף 12/27")
            assertFalse("$s -> $out", out.contains("848") || out.contains("1234"))
        }
        // Other numbers stay (the amount, the card's expiry, a coupon code).
        assertEquals("שובר 200 ₪ תוקף 12/27 קוד ABC123", FamilyShare.withoutCvv("שובר 200 ₪ תוקף 12/27 קוד ABC123"))
    }
}

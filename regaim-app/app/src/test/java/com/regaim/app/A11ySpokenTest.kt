package com.regaim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class A11ySpokenTest {
    @Test fun emojiAroundWordsIsLeftOut() {
        assertEquals("לחבר את בן או בת הזוג", A11y.spoken("💞 לחבר את בן או בת הזוג"))
        assertEquals("נשמר", A11y.spoken("נשמר ✓"))
        assertEquals("לשמור", A11y.spoken("👍  לשמור"))
    }

    @Test fun plainTextAndSymbolsAloneStay() {
        assertNull(A11y.spoken("הפקדה חודשית"))
        assertNull(A11y.spoken("✕"))
        assertNull(A11y.spoken("116 ₪"))
    }
}

package com.regaim.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PriceTextTest {
    @Test fun familiesDontSeePrices() {
        assertEquals("🎨 לצייר את נעם", PriceText.hide("🎨 לצייר את נעם (כ-25 סנט)"))
        assertEquals("🎨 Draw Noam", PriceText.hide("🎨 Draw Noam (about 25 cents)"))
        assertEquals("0 מתוך 6 סצנות", PriceText.hide("0 מתוך 6 סצנות · כ-3.0$"))
        assertEquals("הציור נשאר, ורק התנועה נעשית מחדש.", PriceText.hide("הציור נשאר, ורק התנועה נעשית מחדש. בערך 70 סנט."))
        assertEquals("השאר נשארות.", PriceText.hide("השאר נשארות. עלות משוערת: כ-2.4$."))
        assertEquals("The rest stay.", PriceText.hide("The rest stay. About $2.4."))
        assertEquals("בוחרים מקום:", PriceText.hide("בוחרים מקום (כ-25 סנט לתמונה):"))
        assertEquals("", PriceText.hide("העלויות משוערות (ציור והנפשה של 5 שניות לסצנה)."))
        // Lines without a price stay as they are.
        assertEquals("זה משתמש בקרדיט סרט אחד.", PriceText.hide("זה משתמש בקרדיט סרט אחד."))
    }

    @Test fun theAdminSeesThem() = assertEquals("לצייר (כ-25 סנט)", PriceText.forViewer("לצייר (כ-25 סנט)", true))
}

package com.regaim.app

/**
 * Prices are shown to the admin only: for families, "(about 25 cents)", "· about $3.0", "About 70 cents." and the like
 * are taken out of a line (no Android here, unit-tested).
 */
object PriceText {
    private val inParens = Regex("\\s*\\((?:כ-|כ־|בערך |about |~)[^()]*?(?:סנט|cents|¢|\\$)[^()]*\\)")
    private val dotted = Regex("\\s*·\\s*(?:כ-|about )\\$?[0-9]+(?:\\.[0-9]+)?\\$?")
    private val sentence = Regex("\\s*(?:בערך|About|about) [0-9]+(?:\\.[0-9]+)? (?:סנט|cents)\\.?")
    private val estimate = Regex("\\s*(?:עלות משוערת: )?כ-[0-9]+(?:\\.[0-9]+)?\\$\\.?|\\s*About \\$[0-9]+(?:\\.[0-9]+)?\\.?")
    private val trailingNote = Regex("^העלויות משוערות.*$|^Costs are estimates.*$")

    fun hide(s: String): String = s.replace(inParens, "").replace(dotted, "").replace(sentence, "").replace(estimate, "").replace(trailingNote, "").trim()

    fun forViewer(s: String, admin: Boolean) = if (admin) s else hide(s)
}

package com.regaim.app

/**
 * The QA build (automatic tests on an emulator): nothing that costs money or reaches the real families. Every call to
 * a paid AI service (drawing, animation, voice, Claude, Gemini) stops here with a clear message, the app doesn't
 * offer updates, and crash reports aren't sent. The real app is never in this mode.
 */
object TestMode {
    val on: Boolean get() = BuildConfig.TEST_MODE

    class Blocked : IllegalStateException(L.t("מצב בדיקה: שירותי AI בתשלום כבויים", "Test mode: paid AI services are off"))

    /** Stops a paid call in the QA build. */
    fun guard() { if (on) throw Blocked() }
}

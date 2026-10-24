package com.regaim.app

import android.app.Activity
import androidx.appcompat.app.AlertDialog

/**
 * Before a talk with Pitzki starts (the microphone listens to the child): what happens and what is (not) kept.
 * The same words from every door into the talk: Pitzki's screen and the home screen.
 */
object PitzkiTalkIntro {
    fun ask(a: Activity, memberId: String) {
        AlertDialog.Builder(a)
            .setTitle(L.t("שיחה עם פיצקי", "Talking with Pitzki"))
            .setMessage(L.t("פיצקי ישאל כמה שאלות קצרות (צבעים, חיות, בטיחות, נימוס…), יקשיב ויענה, ואחר כך תור הילד לספר לו דברים ולשאול אותו. אפשר לענות בקול או בלחיצה על תמונה. כ-5 דקות, עם הורה ליד. " +
                "מה שהילד אומר עובר רק דרך זיהוי הדיבור של הטלפון, ולא נשמר. פיצקי עונה מתוך תשובות שנכתבו מראש, בלי AI, ולא חוזר על מילים לא יפות. בסוף תראו מה דיברו.",
                "Pitzki asks a few short questions (colors, animals, safety, manners…), listens and answers; then it's the child's turn to tell him things and ask him. Answer out loud or tap a picture. About 5 minutes, with a parent nearby. " +
                "What the child says goes only through the phone's speech recognition and isn't saved. Pitzki answers from replies written ahead, with no AI, and never repeats rude words. You'll see what was said at the end."))
            .setPositiveButton(L.t("להתחיל", "Start")) { _, _ -> PitzkiChatActivity.open(a, memberId) }
            .setNegativeButton(L.t("לא עכשיו", "Not now"), null)
            .show()
    }
}

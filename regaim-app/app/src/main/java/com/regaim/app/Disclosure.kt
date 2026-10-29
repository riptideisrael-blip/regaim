package com.regaim.app

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AlertDialog

/**
 * "What happens with the camera, microphone and location": shown right before the app first asks for them for the
 * automatic recording (Google Play asks apps to explain this clearly, in the app, before the permission request).
 * Shown again until the parent agrees.
 */
object Disclosure {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("regaim", Context.MODE_PRIVATE)
    fun accepted(ctx: Context) = prefs(ctx).getBoolean("disclosure_capture_v2", false)

    /** Runs [then] once the parent agreed (now or before). */
    fun beforeCapture(a: Activity, then: () -> Unit) {
        if (accepted(a)) { then(); return }
        AlertDialog.Builder(a)
            .setTitle(L.t("לפני שמתחילים: מצלמה, מיקרופון ומיקום", "Before we start: camera, microphone and location"))
            .setMessage(L.t(
                "כשההקלטה האוטומטית פועלת, Moments משתמש במצלמה ובמיקרופון גם כשהמסך כבוי או כשאתם באפליקציה אחרת. כל עוד היא פועלת תופיע התראה קבועה.\n\n" +
                    "• מצלמה ומיקרופון: הטלפון שומר בזיכרון זמני רק את השניות האחרונות, ומוחק אותן כל הזמן. הקול נבדק בטלפון עצמו, בלי אינטרנט, כדי לזהות צחוק ורגעים שמחים. רק כשמזוהה רגע, נשמר קליפ קצר בטלפון.\n\n" +
                    "• מיקום משוער: נשמר עם הרגע כדי לכתוב איפה הוא קרה (שם הרחוב והעיר הקרובים). את שם המקום מקבלים משירות המיקום של Android (Google).\n\n" +
                    "• זיהוי מילים (אם פעיל, אפשר לכבות בהגדרות): כדי לשמור רגע כשאומרים מילה שבחרתם, הקול עובר לשירות זיהוי הדיבור של הטלפון. כשהטלפון יכול זה קורה בלי אינטרנט, ואחרת דרך השירות של Google. לא מגיע לשרת שלנו ולא נשמר אצלנו.\n\n" +
                    "• וידאו וקול לא עולים לשרת שלנו מעצמם. כל רגע מחכה לאישור שלכם. אחרי שאישרתם, אם תיאור ה-AI פעיל ומישהו מהמשפחה זוהה ברגע, נשלחות כמה תמונות קטנות ממנו ל-Claude של Anthropic כדי לכתוב לו תיאור.\n\n" +
                    "• אפשר לעצור את ההקלטה בכל רגע, מההתראה או מהאפליקציה.",
                "While automatic recording is on, Moments uses the camera and microphone even when the screen is off or you're in another app. A notification stays on the whole time it runs.\n\n" +
                    "• Camera and microphone: the phone keeps only the last few seconds in temporary memory and keeps deleting them. The sound is checked on the phone itself, without internet, to recognize laughter and happy moments. Only when a moment is recognized is a short clip saved on the phone.\n\n" +
                    "• Approximate location: saved with the moment to note where it happened (the nearest street and town). The place name comes from Android's location service (Google).\n\n" +
                    "• Listening for words (if on; can be turned off in Settings): to save a moment when a word you chose is said, the sound goes to the phone's speech recognition service. On the phone when the phone can, otherwise through Google's service. It doesn't reach our server and isn't kept by us.\n\n" +
                    "• Video and sound aren't uploaded to our server by themselves. Every moment waits for your approval. Once you approve, if AI descriptions are on and someone from the family is recognized in it, a few small pictures from it are sent to Anthropic's Claude to describe it.\n\n" +
                    "• You can stop recording at any time, from the notification or the app.",
            ))
            .setPositiveButton(L.t("מסכימים, להמשיך", "I agree, continue")) { _, _ ->
                prefs(a).edit().putBoolean("disclosure_capture_v2", true).apply()
                then()
            }
            .setNegativeButton(L.t("לא עכשיו", "Not now"), null)
            .show()
    }
}

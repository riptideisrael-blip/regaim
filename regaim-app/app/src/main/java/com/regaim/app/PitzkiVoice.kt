package com.regaim.app

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Pitzki's voice: Hebrew lines and English color words, from the phone's own voices (or a recorded line when one
 * was added to the app). Every line calls [done] exactly once — after it was said, or after a fair reading time when
 * there's no voice (muted, or no Hebrew voice on the phone), so the lesson never gets stuck.
 */
class PitzkiVoice(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var he: TextToSpeech? = null
    private var en: TextToSpeech? = null
    @Volatile var heOk = false
        private set
    @Volatile var enOk = false
        private set
    var muted = false
    private var pending: Runnable? = null
    private var pendingId = ""
    private var player: MediaPlayer? = null
    private var seq = 0

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) { main.post { finish(utteranceId.orEmpty()) } }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) { main.post { finish(utteranceId.orEmpty()) } }
        override fun onError(utteranceId: String?, errorCode: Int) { main.post { finish(utteranceId.orEmpty()) } }
    }

    init {
        he = TextToSpeech(ctx.applicationContext) init@{ status ->
            val t = he ?: return@init
            if (status == TextToSpeech.SUCCESS) {
                val r = t.setLanguage(Locale("he", "IL"))
                heOk = r >= TextToSpeech.LANG_AVAILABLE
                bestVoice(t, "he", "iw")
                t.setSpeechRate(0.88f)
                t.setPitch(1.3f) // higher: a young, childlike voice for Pitzki
                t.setOnUtteranceProgressListener(listener)
            }
        }
        en = TextToSpeech(ctx.applicationContext) init@{ status ->
            val t = en ?: return@init
            if (status == TextToSpeech.SUCCESS) {
                val r = t.setLanguage(Locale.US)
                enOk = r >= TextToSpeech.LANG_AVAILABLE
                bestVoice(t, "en")
                t.setSpeechRate(0.72f)
                t.setPitch(1.25f)
                t.setOnUtteranceProgressListener(listener)
            }
        }
    }

    /**
     * The clearest voice the phone has for the language: the highest quality, and one that works without the internet
     * when it's as good.
     */
    private fun bestVoice(t: TextToSpeech, vararg langs: String) {
        try {
            val vs = t.voices.orEmpty().filter { v -> v.locale.language in langs && !v.features.orEmpty().contains("notInstalled") }
            val best = vs.maxWithOrNull(compareBy<android.speech.tts.Voice>({ it.quality }, { if (it.isNetworkConnectionRequired) 0 else 1 }))
            if (best != null) t.voice = best
        } catch (_: Exception) { /* the default voice stays */ }
    }

    private fun spoken(text: String) = spokenText(text)

    companion object {
        /**
         * The niqqud of every word Pitzki says, so his voice reads each one right (חוּם the color, not חַם hot).
         * Whole words only; the subtitles keep the plain text. New words: add them here.
         */
        private val NIQQUD: Map<String, String> = mapOf(
            "פיצקי" to "פִּיצְקִי",
            "שדון" to "שֵׁדוֹן",
            "אבן" to "אֶבֶן",
            "אדום" to "אָדוֹם",
            "אומרים" to "אוֹמְרִים",
            "אותם" to "אוֹתָם",
            "איזה" to "אֵיזֶה",
            "איפה" to "אֵיפֹה",
            "איתי" to "אִיתִּי",
            "אלוף" to "אַלּוּף",
            "אלופה" to "אַלּוּפָה",
            "אלופים" to "אַלּוּפִים",
            "אפור" to "אָפוֹר",
            "ארבעה" to "אַרְבָּעָה",
            "ארנב" to "אַרְנָב",
            "את" to "אַתְּ",
            "אתה" to "אַתָּה",
            "אתם" to "אַתֶּם",
            "באנגלית" to "בְּאַנְגְּלִית",
            "בהרפתקה" to "בַּהַרְפַּתְקָה",
            "בוא" to "בּוֹא",
            "בואי" to "בּוֹאִי",
            "בואו" to "בּוֹאוּ",
            "בין" to "בֵּין",
            "ביער" to "בַּיַּעַר",
            "בנחל" to "בַּנַּחַל",
            "בצורת" to "בְּצוּרַת",
            "בשביל" to "בַּשְּׁבִיל",
            "גדול" to "גָּדוֹל",
            "האבן" to "הָאֶבֶן",
            "האבנים" to "הָאֲבָנִים",
            "האגם" to "הָאֲגַם",
            "האדום" to "הָאָדוֹם",
            "האפור" to "הָאָפוֹר",
            "האפורה" to "הָאֲפוֹרָה",
            "הארנב" to "הָאַרְנָב",
            "הבאה" to "הַבָּאָה",
            "הגזע" to "הַגֶּזַע",
            "הגפן" to "הַגֶּפֶן",
            "הוורוד" to "הַוָּרוֹד",
            "הופ" to "הוֹפּ",
            "החום" to "הַחוּם",
            "החיפושית" to "הַחִיפּוּשִׁית",
            "היא" to "הִיא",
            "היום" to "הַיּוֹם",
            "הירוק" to "הַיָּרוֹק",
            "הכבוד" to "הַכָּבוֹד",
            "הכחול" to "הַכָּחוֹל",
            "הכחולה" to "הַכְּחוּלָּה",
            "הלבן" to "הַלָּבָן",
            "הנה" to "הִנֵּה",
            "הנכונה" to "הַנְּכוֹנָה",
            "הסגול" to "הַסָּגוֹל",
            "הסגולים" to "הַסְּגוֹלִים",
            "העלה" to "הֶעָלֶה",
            "הענבים" to "הָעֲנָבִים",
            "הענף" to "הָעָנָף",
            "העצים" to "הָעֵצִים",
            "הפלמינגו" to "הַפְלָמִינְגּוֹ",
            "הפרח" to "הַפֶּרַח",
            "הצהוב" to "הַצָּהוֹב",
            "הציפור" to "הַצִּיפּוֹר",
            "הרגליים" to "הָרַגְלַיִם",
            "השחור" to "הַשָּׁחוֹר",
            "השחורה" to "הַשְּׁחוֹרָה",
            "התפוח" to "הַתַּפּוּחַ",
            "התשובה" to "הַתְּשׁוּבָה",
            "ומבריקה" to "וּמַבְרִיקָה",
            "ונחפש" to "וּנְחַפֵּשׂ",
            "ונלמד" to "וְנִלְמַד",
            "ורוד" to "וָרוֹד",
            "ורך" to "וָרַךְ",
            "זה" to "זֶה",
            "זכרנו" to "זָכַרְנוּ",
            "חום" to "חוּם",
            "חיפושית" to "חִיפּוּשִׁית",
            "טוב" to "טוֹב",
            "טיול" to "טִיּוּל",
            "יד" to "יָד",
            "יופי" to "יוֹפִי",
            "יחד" to "יַחַד",
            "יער" to "יַעַר",
            "יפה" to "יָפֶה",
            "ירוק" to "יָרוֹק",
            "יש" to "יֵשׁ",
            "כבר" to "כְּבָר",
            "כחול" to "כָּחוֹל",
            "כיף" to "כֵּיף",
            "כל" to "כָּל",
            "כמעט" to "כִּמְעַט",
            "לא" to "לֹא",
            "לאבן" to "לָאֶבֶן",
            "לארנב" to "לָאַרְנָב",
            "לב" to "לֵב",
            "לבן" to "לָבָן",
            "לגזע" to "לַגֶּזַע",
            "לגלות" to "לְגַלּוֹת",
            "להגיד" to "לְהַגִּיד",
            "לחיפושית" to "לַחִיפּוּשִׁית",
            "לטיול" to "לְטִיּוּל",
            "לי" to "לִי",
            "ליד" to "לְיַד",
            "ליער" to "לַיַּעַר",
            "למדנו" to "לָמַדְנוּ",
            "לעלה" to "לֶעָלֶה",
            "לענבים" to "לָעֲנָבִים",
            "לפלמינגו" to "לַפְלָמִינְגּוֹ",
            "לפרח" to "לַפֶּרַח",
            "לציפור" to "לַצִּיפּוֹר",
            "לתפוח" to "לַתַּפּוּחַ",
            "מאוד" to "מְאוֹד",
            "מה" to "מָה",
            "מוכן" to "מוּכָן",
            "מוכנה" to "מוּכָנָה",
            "מוכנים" to "מוּכָנִים",
            "מצאנו" to "מָצָאנוּ",
            "נבדוק" to "נִבְדּוֹק",
            "נגיד" to "נַגִּיד",
            "נכון" to "נָכוֹן",
            "נמצא" to "נִמְצָא",
            "נמשיך" to "נַמְשִׁיךְ",
            "נסה" to "נַסֵּה",
            "נסי" to "נַסִּי",
            "נסו" to "נַסּוּ",
            "נפלא" to "נִפְלָא",
            "נצא" to "נֵצֵא",
            "נקפוץ" to "נִקְפּוֹץ",
            "נתראה" to "נִתְרָאֶה",
            "סגול" to "סָגוֹל",
            "עוד" to "עוֹד",
            "עומד" to "עוֹמֵד",
            "עכשיו" to "עַכְשָׁיו",
            "על" to "עַל",
            "עלה" to "עָלֶה",
            "ענבים" to "עֲנָבִים",
            "עץ" to "עֵץ",
            "עשרה" to "עֲשָׂרָה",
            "פלמינגו" to "פְלָמִינְגּוֹ",
            "פנימה" to "פְּנִימָה",
            "פרח" to "פֶּרַח",
            "צבע" to "צֶבַע",
            "צבעים" to "צְבָעִים",
            "צהוב" to "צָהוֹב",
            "ציפור" to "צִיפּוֹר",
            "קופץ" to "קוֹפֵץ",
            "קטן" to "קָטָן",
            "קטנה" to "קְטַנָּה",
            "רק" to "רַק",
            "שוב" to "שׁוּב",
            "שחור" to "שָׁחוֹר",
            "שישה" to "שִׁישָּׁה",
            "שלו" to "שֶׁלּוֹ",
            "שלום" to "שָׁלוֹם",
            "שלנו" to "שֶׁלָּנוּ",
            "שמעתי" to "שָׁמַעְתִּי",
            "שמש" to "שֶׁמֶשׁ",
            "שני" to "שְׁנֵי",
            "תגיד" to "תַּגִּיד",
            "תגידי" to "תַּגִּידִי",
            "תגידו" to "תַּגִּידוּ",
            "תלחץ" to "תִּלְחַץ",
            "תלחצי" to "תִּלְחֲצִי",
            "תלחצו" to "תִּלְחֲצוּ",
            "תן" to "תֵּן",
            "תני" to "תְּנִי",
            "תנו" to "תְּנוּ",
            "תפוח" to "תַּפּוּחַ",
            "תצביע" to "תַּצְבִּיעַ",
            "תצביעי" to "תַּצְבִּיעִי",
            "תצביעו" to "תַּצְבִּיעוּ",
            "תראה" to "תִּרְאֶה",
            "תראי" to "תִּרְאִי",
            "תראו" to "תִּרְאוּ",
            "חברים" to "חֲבֵרִים",
            "מצאתם" to "מְצָאתֶם",
            "שבאתם" to "שֶׁבָּאתֶם",
            "וואו" to "וָאוּ",
            "או" to "אוֹ",
            "אחד" to "אֶחָד",
            "אחרי" to "אַחֲרֵי",
            "אחת" to "אַחַת",
            "איזו" to "אֵיזוֹ",
            "אפשר" to "אֶפְשָׁר",
            "ארבע" to "אַרְבַּע",
            "בזהירות" to "בִּזְהִירוּת",
            "בירוק" to "בַּיָּרוֹק",
            "בנסיעה" to "בַּנְּסִיעָה",
            "בסבלנות" to "בְּסַבְלָנוּת",
            "בעלייה" to "בָּעֲלִיָּה",
            "ברכבת" to "בָּרַכֶּבֶת",
            "ברמזור" to "בָּרַמְזוֹר",
            "גדולה" to "גְּדוֹלָה",
            "הארוכה" to "הָאֲרוּכָּה",
            "הגדול" to "הַגָּדוֹל",
            "הגענו" to "הִגַּעְנוּ",
            "הדרך" to "הַדֶּרֶךְ",
            "החלון" to "הַחַלּוֹן",
            "הקו" to "הַקַּו",
            "הקטן" to "הַקָּטָן",
            "הרכבת" to "הָרַכֶּבֶת",
            "השני" to "הַשֵּׁנִי",
            "התור" to "הַתּוֹר",
            "ואיך" to "וְאֵיךְ",
            "ולאט" to "וְלָאַט",
            "ומה" to "וּמָה",
            "ומתי" to "וּמָתַי",
            "ונושמים" to "וְנוֹשְׁמִים",
            "ועכשיו" to "וְעַכְשָׁיו",
            "וקטן" to "וְקָטָן",
            "וקטר" to "וְקַטָּר",
            "חזק" to "חָזָק",
            "חיכיתם" to "חִיכִּיתֶם",
            "חמש" to "חָמֵשׁ",
            "ילד" to "יֶלֶד",
            "ילדה" to "יַלְדָּה",
            "ילדים" to "יְלָדִים",
            "ישרה" to "יְשָׁרָה",
            "כאן" to "כָּאן",
            "ככה" to "כָּכָה",
            "כמה" to "כַּמָּה",
            "כרטיס" to "כַּרְטִיס",
            "כשיהיה" to "כְּשֶׁיִּהְיֶה",
            "כשמחכים" to "כְּשֶׁמְּחַכִּים",
            "כשמקבלים" to "כְּשֶׁמְּקַבְּלִים",
            "לאט" to "לְאַט",
            "לה" to "לָהּ",
            "להן" to "לָהֶן",
            "לחץ" to "לְחַץ",
            "לינשוף" to "לַיַּנְשׁוּף",
            "לירוק" to "לַיָּרוֹק",
            "לנו" to "לָנוּ",
            "לפני" to "לִפְנֵי",
            "לקופה" to "לַקּוּפָּה",
            "לרכבת" to "לָרַכֶּבֶת",
            "לתחנה" to "לַתַּחֲנָה",
            "מאחורי" to "מֵאֲחוֹרֵי",
            "מגיעה" to "מַגִּיעָה",
            "מהר" to "מַהֵר",
            "מחזיקים" to "מַחֲזִיקִים",
            "מחכים" to "מְחַכִּים",
            "מנומס" to "מְנוּמָּס",
            "מנומסים" to "מְנוּמָּסִים",
            "מנומסת" to "מְנוּמֶּסֶת",
            "מסע" to "מַסָּע",
            "מקום" to "מָקוֹם",
            "מתי" to "מָתַי",
            "נוסעים" to "נוֹסְעִים",
            "נוסעת" to "נוֹסַעַת",
            "נושמים" to "נוֹשְׁמִים",
            "נושפים" to "נוֹשְׁפִים",
            "נלך" to "נֵלֵךְ",
            "נלמד" to "נִלְמַד",
            "ננופף" to "נְנוֹפֵף",
            "ננשום" to "נִנְשׁוֹם",
            "נספור" to "נִסְפּוֹר",
            "עד" to "עַד",
            "עולים" to "עוֹלִים",
            "עוצרים" to "עוֹצְרִים",
            "עושים" to "עוֹשִׂים",
            "עלייה" to "עֲלִיָּה",
            "עמוק" to "עָמוֹק",
            "עשר" to "עֶשֶׂר",
            "פרות" to "פָּרוֹת",
            "קטר" to "קַטָּר",
            "רבה" to "רַבָּה",
            "רכבות" to "רַכָּבוֹת",
            "רמזור" to "רַמְזוֹר",
            "שבע" to "שֶׁבַע",
            "שואפים" to "שׁוֹאֲפִים",
            "שיגיע" to "שֶׁיַּגִּיעַ",
            "של" to "שֶׁל",
            "שלוש" to "שָׁלוֹשׁ",
            "שמונה" to "שְׁמוֹנֶה",
            "שש" to "שֵׁשׁ",
            "שתיים" to "שְׁתַּיִם",
            "תודה" to "תּוֹדָה",
            "תור" to "תּוֹר",
            "תשע" to "תֵּשַׁע",
            // The talk with Pitzki (words a voice may read the wrong way).
            "חיוך" to "חִיּוּךְ",
            "שמחה" to "שִׂמְחָה",
            "שמח" to "שָׂמֵחַ",
            "עצוב" to "עָצוּב",
            "עצובה" to "עֲצוּבָה",
            "פרי" to "פְּרִי",
            "ער" to "עֵר",
            "עגול" to "עָגֹל",
            "עיגול" to "עִיגּוּל",
            "משולש" to "מְשֻׁלָּשׁ",
            "ריבוע" to "רִיבּוּעַ",
            "חמוץ" to "חָמוּץ",
            "מתוק" to "מָתוֹק",
            "טעם" to "טַעַם",
            "גזר" to "גֶּזֶר",
            "לגזר" to "לְגֶזֶר",
            "הגזר" to "הַגֶּזֶר",
            "פיל" to "פִּיל",
            "הפיל" to "הַפִּיל",
            "עכבר" to "עַכְבָּר",
            "העכבר" to "הָעַכְבָּר",
            "והעכבר" to "וְהָעַכְבָּר",
            "ברווז" to "בַּרְוָז",
            "ציפורים" to "צִפּוֹרִים",
            "כוכבים" to "כּוֹכָבִים",
            "וכוכבים" to "וְכוֹכָבִים",
            "כוכב" to "כּוֹכָב",
            "ירח" to "יָרֵחַ",
            "כביש" to "כְּבִישׁ",
            "הכביש" to "הַכְּבִישׁ",
            "לכביש" to "לַכְּבִישׁ",
            "חוצים" to "חוֹצִים",
            "שחוצים" to "שֶׁחוֹצִים",
            "כשחוצים" to "כְּשֶׁחוֹצִים",
            "לחצות" to "לַחֲצוֹת",
            "מבוגר" to "מְבֻגָּר",
            "למבוגר" to "לִמְבֻגָּר",
            "סליחה" to "סְלִיחָה",
            "בבקשה" to "בְּבַקָּשָׁה",
            "מחבקים" to "מְחַבְּקִים",
            "חיבוק" to "חִיבּוּק",
            "משתפים" to "מְשַׁתְּפִים",
            "מגיע" to "מַגִּיעַ",
            "מותר" to "מֻתָּר",
            "לבד" to "לְבַד",
            "אסור" to "אָסוּר",
            "עפה" to "עָפָה",
            "שוחה" to "שׂוֹחָה",
            "קסמים" to "קְסָמִים",
            "מחבואים" to "מַחְבּוֹאִים",
            "ממש" to "מַמָּשׁ",
            "בסדר" to "בְּסֵדֶר",
            "נורא" to "נוֹרָא",
            "ננסה" to "נְנַסֶּה",
            "תנסה" to "תְּנַסֶּה",
            "תנסי" to "תְּנַסִּי",
            "תנסו" to "תְּנַסּוּ",
            "אופס" to "אוּפְּס",
            "שתי" to "שְׁתֵּי",
            "חבר" to "חָבֵר",
            "החבר" to "הֶחָבֵר",
            "רעיון" to "רַעְיוֹן",
            "מצחיק" to "מַצְחִיק",
            "יודע" to "יוֹדֵעַ",
            "יודעת" to "יוֹדַעַת",
            "יודעים" to "יוֹדְעִים",
            "אוהב" to "אוֹהֵב",
            "אוהבת" to "אוֹהֶבֶת",
            "אוהבים" to "אוֹהֲבִים",
            "כמו" to "כְּמוֹ",
            "עם" to "עִם",
            "אם" to "אִם",
            "מי" to "מִי",
            "יותר" to "יוֹתֵר",
            "גדל" to "גָּדֵל",
            "דג" to "דָּג",
            "הדג" to "הַדָּג",
            "והדג" to "וְהַדָּג",
            "פרה" to "פָּרָה",
            "מהפרה" to "מֵהַפָּרָה",
            "מהעץ" to "מֵהָעֵץ",
            "הדבש" to "הַדְּבַשׁ",
            "לדבש" to "לַדְּבַשׁ",
            "הלימון" to "הַלִּימוֹן",
            "ללימון" to "לַלִּימוֹן",
            "חתול" to "חָתוּל",
            "כלב" to "כֶּלֶב",
            "הכלב" to "הַכֶּלֶב",
            "עוגה" to "עוּגָה",
            "בננה" to "בָּנָנָה",
            "הבננה" to "הַבָּנָנָה",
            "לבננה" to "לַבָּנָנָה",
            "עיניים" to "עֵינַיִם",
            "ידיים" to "יָדַיִם",
            "אצבעות" to "אֶצְבָּעוֹת",
            "ביד" to "בַּיָּד",
            "לילה" to "לַיְלָה",
            "בלילה" to "בַּלַּיְלָה",
            "ביום" to "בַּיּוֹם",
            "שוטפים" to "שׁוֹטְפִים",
            "סבון" to "סַבּוֹן",
            "עצים" to "עֵצִים",
            "מכוניות" to "מְכוֹנִיּוֹת",
            "כובע" to "כּוֹבַע",
            "הכובע" to "הַכּוֹבַע",
            "צורה" to "צוּרָה",
            "פרצוף" to "פַּרְצוּף",
            "הפרצוף" to "הַפַּרְצוּף",
            "השמח" to "הַשָּׂמֵחַ",
            "העצוב" to "הֶעָצוּב",
            "צוחקים" to "צוֹחֲקִים",
            "עוזרים" to "עוֹזְרִים",
            "מסדרים" to "מְסַדְּרִים",
            "מסדרת" to "מְסַדֶּרֶת",
            "צעצוע" to "צַעֲצוּעַ",
            "צעצועים" to "צַעֲצוּעִים",
            "המגלשה" to "הַמַּגְלֵשָׁה",
            "בתור" to "בַּתּוֹר",
            "ומחכים" to "וּמְחַכִּים",
            "כסא" to "כִּסֵּא",
            "בכיסא" to "בְּכִסֵּא",
            "הבטיחות" to "הַבְּטִיחוּת",
            "חגורה" to "חֲגוֹרָה",
            "וחוגרים" to "וְחוֹגְרִים",
            "במעבר" to "בְּמַעֲבַר",
            "החצייה" to "הַחֲצִיָּה",
            "הפסים" to "הַפַּסִּים",
            "הלבנים" to "הַלְּבָנִים",
            "ותמיד" to "וְתָמִיד",
            "להירגע" to "לְהֵרָגַע",
            "בובה" to "בֻּבָּה",
            "ומספרים" to "וּמְסַפְּרִים",
            "מפחדים" to "מְפַחֲדִים",
            "להרגיש" to "לְהַרְגִּישׁ",
            "השמיים" to "הַשָּׁמַיִם",
            "בשמיים" to "בַּשָּׁמַיִם",
            "לשמיים" to "לַשָּׁמַיִם",
            "הדשא" to "הַדֶּשֶׁא",
            "לדשא" to "לַדֶּשֶׁא",
            "לתות" to "לַתּוּת",
            "התות" to "הַתּוּת",
            "הגלגל" to "הַגַּלְגַּל",
            "לגלגל" to "לַגַּלְגַּל",
            "השמש" to "הַשֶּׁמֶשׁ",
            "לשמש" to "לַשֶּׁמֶשׁ",
            "לגג" to "לַגַּג",
            "טעים" to "טָעִים",
            "מחממת" to "מְחַמֶּמֶת",
            "נשחק" to "נְשַׂחֵק",
            "בכדור" to "בַּכַּדּוּר",
            "תפוחים" to "תַּפּוּחִים",
            "מוציאים" to "מוֹצִיאִים",
            "ממנו" to "מִמֶּנּוּ",
            "היינו" to "הָיִינוּ",
            "הייתי" to "הָיִיתִי",
            "מתחבא" to "מִתְחַבֵּא",
            "מתחבאת" to "מִתְחַבֵּאת",
            "מתחבאים" to "מִתְחַבְּאִים",
            "להתראות" to "לְהִתְרָאוֹת",
            "לדבר" to "לְדַבֵּר",
            "איתכם" to "אִתְּכֶם",
            "נדבר" to "נְדַבֵּר",
            "מהיער" to "מֵהַיַּעַר",
            "קוקו" to "קוּקוּ",
            "קליק" to "קְלִיק",
        )
        /** A Hebrew word with any niqqud it already has (a voweled word is left as it is). */
        private val HEBREW_WORD = Regex("[\u05B0-\u05C7\u05D0-\u05EA]+")
        private val HAS_NIQQUD = Regex("[\u05B0-\u05C7]")

        /**
         * How this child's name is said (the parent wrote it with niqqud, e.g. אוֹרִי and not אוּרִי): the name as
         * written -> as said. Set by the screen for the chosen child.
         */
        @Volatile var sayName: Pair<String, String>? = null

        /** The line with niqqud for Pitzki's voice (and the child's name the way the family says it). */
        fun spokenText(text: String): String {
            val name = sayName
            return HEBREW_WORD.replace(text.replace("צ'וף", "צ'וּף")) { m ->
                val w = m.value
                when {
                    HAS_NIQQUD.containsMatchIn(w) -> w
                    name != null && w == name.first && name.second.isNotBlank() -> name.second
                    else -> NIQQUD[w] ?: w
                }
            }.let { fixSound(it) }
        }

        /**
         * Words the voice says wrong even with niqqud, written the way they sound: "לְאַט" came out wrong ("lat"),
         * so it's written with a clear "le-" ("לֶאַט", the same sound a child hears).
         */
        // "יוֹפִי" came out "yafi": the plain word, which the voice knows; the dog's "הַב הַב" sounded harsh: "אַב אַב".
        // (Only what's heard: the subtitles stay as written.)
        private fun fixSound(text: String): String = text
            .replace("יוֹפִי", "יופי").replace("יֹפִי", "יופי")
            .replace("הַב הַב", "אַב אַב").replace("הב הב", "אַב אַב")
    }

    private fun finish(id: String) {
        if (id != pendingId) return
        val p = pending ?: return
        pending = null
        pendingId = ""
        p.run()
    }

    /** About how long a line takes to read, when it isn't heard. */
    private fun readingMs(text: String) = (900L + text.length * 75L).coerceAtMost(6000L)

    /** Hebrew line. [recording]: the id of a recorded line to play instead, if the app has it. */
    fun say(text: String, recording: String = "", done: () -> Unit) = speak(he, heOk, text, recording, done)

    /** An English word, slow and clear. */
    fun word(text: String, done: () -> Unit) = speak(en, enOk, text, "", done)

    private fun speak(tts: TextToSpeech?, ok: Boolean, text: String, recording: String, done: () -> Unit) {
        stop()
        val id = "p${++seq}"
        pendingId = id
        pending = Runnable { done() }
        if (muted) { main.postDelayed({ finish(id) }, readingMs(text)); return }
        // Pitzki's own voice (made ahead, kept on the phone): the same for Hebrew lines and the English words.
        PitzkiStore.voiceFile(ctx, spokenText(text))?.let { f -> if (playFile(f, id, text)) return }
        if (recording.isNotEmpty() && PitzkiStore.hasRecording(ctx, recording)) {
            try {
                val fd = ctx.assets.openFd("pitzki/voice/$recording.mp3")
                val mp = MediaPlayer()
                mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                fd.close()
                mp.setOnCompletionListener { finish(id) }
                mp.setOnErrorListener { _, _, _ -> finish(id); true }
                mp.prepare()
                mp.start()
                player = mp
                main.postDelayed({ finish(id) }, readingMs(text) + 6000L)
                return
            } catch (_: Exception) { /* fall back */ }
        }
        // Not made yet (a new child's name, a line not ready): made now in his voice, if it comes within a few
        // seconds; only otherwise the phone's voice (which isn't Pitzki's).
        val app = ctx.applicationContext
        live.execute {
            val f = try {
                val job = live.submit<java.io.File?> { PitzkiStore.voiceFor(app, text) }
                job.get(8, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: Exception) { null }
            main.post {
                if (pendingId != id) return@post
                if (f != null && f.exists() && playFile(f, id, text)) return@post
                phone(tts, ok, text, id)
            }
        }
    }

    /** Things made in the background for a line that isn't on the phone yet. */
    private val live = java.util.concurrent.Executors.newCachedThreadPool()

    private fun playFile(f: java.io.File, id: String, text: String): Boolean = try {
        val mp = MediaPlayer()
        mp.setDataSource(f.absolutePath)
        mp.setOnCompletionListener { finish(id) }
        mp.setOnErrorListener { _, _, _ -> finish(id); true }
        mp.prepare()
        mp.start()
        player = mp
        // A safety net: the line ends even if the phone never says it's done.
        main.postDelayed({ finish(id) }, maxOf(readingMs(text), mp.duration.toLong()) + 4000L)
        true
    } catch (_: Exception) { false }

    /** The phone's own voice (when his isn't available). */
    private fun phone(tts: TextToSpeech?, ok: Boolean, text: String, id: String) {
        main.postDelayed({ finish(id) }, readingMs(text) + 6000L)
        if (tts == null || !ok) { main.postDelayed({ finish(id) }, readingMs(text)); return }
        val say = if (tts === he) spoken(text) else text
        if (tts.speak(say, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) main.postDelayed({ finish(id) }, readingMs(text))
    }

    /** Stops what's being said; its [done] is NOT called (the lesson was paused or moved on). */
    fun stop() {
        pending = null
        pendingId = ""
        try { he?.stop() } catch (_: Exception) {}
        try { en?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
    }

    fun shutdown() {
        stop()
        try { he?.shutdown() } catch (_: Exception) {}
        try { en?.shutdown() } catch (_: Exception) {}
        he = null
        en = null
    }
}

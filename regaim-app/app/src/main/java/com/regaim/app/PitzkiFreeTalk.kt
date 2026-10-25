package com.regaim.app

/**
 * The child talks freely with Pitzki (tells him things, asks him things), with no AI and nothing sent anywhere:
 * Pitzki recognizes words and topics and answers from replies written here, so he only ever says kind, simple,
 * safe things, at once (his voice for all of them is made ahead). A rude word gets a gentle "that's not a nice word".
 * No Android here (unit-tested).
 */
object PitzkiFreeTalk {
    /** A reply: to a boy / to a girl / to everyone. */
    class R(val boy: String, val girl: String = boy, val all: String = boy) { fun to(kid: PitzkiKid) = kid.g(boy, girl, all) }

    /** One topic: the words that show it, and a few replies (said in turn, so he doesn't repeat himself). */
    class Topic(val id: String, val words: List<String>, val replies: List<R>)

    /** Asked about Pitzki himself ("what do you eat?"): the question words and the topic words together. */
    private val ABOUT_ME = listOf(
        Topic("me_eat", listOf("אוכל", "לאכול", "אוהב לאכול"), listOf(R("אני הכי אוהב תותים מהיער! ומה אתה אוהב לאכול?", "אני הכי אוהב תותים מהיער! ומה את אוהבת לאכול?", "אני הכי אוהב תותים מהיער! ומה אתם אוהבים לאכול?"))),
        Topic("me_live", listOf("גר", "גרה", "הבית שלך", "איפה אתה"), listOf(R("אני גר ביער, בבית קטן בתוך גזע של עץ, ליד הינשוף!"))),
        Topic("me_age", listOf("בן כמה", "כמה אתה", "גיל"), listOf(R("אני שדון קטן, בן חמש שנות שדונים! ובן כמה אתה?", "אני שדון קטן, בן חמש שנות שדונים! ובת כמה את?", "אני שדון קטן, בן חמש שנות שדונים! ובני כמה אתם?"))),
        Topic("me_friends", listOf("חברים שלך", "חבר שלך", "חברים"), listOf(R("החברים שלי הם הינשוף, הארנב והציפורים. וגם אתה, כמובן!", "החברים שלי הם הינשוף, הארנב והציפורים. וגם את, כמובן!", "החברים שלי הם הינשוף, הארנב והציפורים. וגם אתם, כמובן!"))),
        Topic("me_hat", listOf("כובע"), listOf(R("הכובע הירוק שלי עם העלה! קיבלתי אותו מהעץ הכי גבוה ביער."))),
        Topic("me_color", listOf("צבע אהוב", "איזה צבע", "צבע"), listOf(R("אני הכי אוהב ירוק, כמו העלים ביער! ואיזה צבע אתה אוהב?", "אני הכי אוהב ירוק, כמו העלים ביער! ואיזה צבע את אוהבת?", "אני הכי אוהב ירוק, כמו העלים ביער! ואיזה צבע אתם אוהבים?"))),
        Topic("me_sleep", listOf("ישן", "ישנה", "לישון"), listOf(R("אני ישן בערב, בתוך עלה רך כמו שמיכה. לילה טוב, עלה!"))),
        Topic("me_who", listOf("מי אתה", "מה אתה", "שדון"), listOf(R("אני פיצקי, שדון קטן מהיער. אני אוהב לטייל, לשיר וללמוד דברים חדשים!"))),
    )

    private val TOPICS = listOf(
        Topic("pet_dog", listOf("כלב", "כלבה", "כלבים", "גור"), listOf(
            R("וואו, כלב! איך קוראים לכלב?", "וואו, כלב! איך קוראים לכלב?", "וואו, כלב! איך קוראים לכלב?"),
            R("כלבים אוהבים ליטופים וכדור. מה הכלב הכי אוהב לעשות?"))),
        Topic("pet_cat", listOf("חתול", "חתולה", "חתלתול"), listOf(R("חתולים רכים ועושים מְיָאוּ! איך קוראים לחתול?"), R("חתולים אוהבים לישון בשמש. גם אתה אוהב שמש?", "חתולים אוהבים לישון בשמש. גם את אוהבת שמש?", "חתולים אוהבים לישון בשמש. גם אתם אוהבים שמש?"))),
        Topic("animals", listOf("פרה", "סוס", "חמור", "כבשה", "תרנגול", "ארנב", "דג", "ציפור", "ינשוף", "פיל", "אריה", "קוף", "ג'ירפה", "חיות", "חיה"), listOf(
            R("אני אוהב חיות! איזה קול היא עושה?"), R("איזו חיה נהדרת! איפה ראית אותה?", "איזו חיה נהדרת! איפה ראית אותה?", "איזו חיה נהדרת! איפה ראיתם אותה?"))),
        Topic("family_mom", listOf("אמא", "אימא"), listOf(R("אמא שלך נשמעת נהדרת! מה אתם אוהבים לעשות יחד?"))),
        Topic("family_dad", listOf("אבא"), listOf(R("איזה כיף עם אבא! מה אתם אוהבים לעשות יחד?"))),
        Topic("family_sib", listOf("אח", "אחות", "אחי", "אחותי", "אחים", "תינוק"), listOf(R("איזה כיף שיש לך אח או אחות! אתם משחקים יחד?"))),
        Topic("family_grand", listOf("סבא", "סבתא", "סבתה"), listOf(R("סבא וסבתא! הם בטח אוהבים אותך מאוד. מה עושים אצלם?", "סבא וסבתא! הם בטח אוהבים אותך מאוד. מה עושים אצלם?", "סבא וסבתא! הם בטח אוהבים אתכם מאוד. מה עושים אצלם?"))),
        Topic("kindergarten", listOf("גן", "בגן", "הגננת", "גננת", "מטפלת", "צהרון"), listOf(R("מה שיחקת היום בגן?", "מה שיחקת היום בגן?", "מה שיחקתם היום בגן?"), R("יש לך חבר טוב בגן? איך קוראים לו?", "יש לך חברה טובה בגן? איך קוראים לה?", "יש לכם חברים בגן? איך קוראים להם?"))),
        Topic("food", listOf("אוכל", "אכלתי", "פיצה", "פסטה", "ארטיק", "גלידה", "שוקולד", "במבה", "תפוח", "בננה", "עוגה", "ממתק"), listOf(R("ממממ, טעים! גם אני אוהב דברים טעימים. מה עוד אתה אוהב?", "ממממ, טעים! גם אני אוהב דברים טעימים. מה עוד את אוהבת?", "ממממ, טעים! גם אני אוהב דברים טעימים. מה עוד אתם אוהבים?"))),
        Topic("play", listOf("משחק", "לשחק", "שיחקתי", "צעצוע", "קוביות", "לגו", "בובה", "כדור", "מכונית", "אוטו", "רכבת", "פאזל"), listOf(R("איזה משחק כיפי! תראה לי איך משחקים?", "איזה משחק כיפי! תראי לי איך משחקים?", "איזה משחק כיפי! תראו לי איך משחקים?"), R("גם אני אוהב לשחק! מה הכי כיף בזה?"))),
        Topic("outside", listOf("פארק", "גינה", "מגלשה", "נדנדה", "ים", "חוף", "טיול", "יער"), listOf(R("איזה כיף בחוץ! מה הכי אהבת שם?", "איזה כיף בחוץ! מה הכי אהבת שם?", "איזה כיף בחוץ! מה הכי אהבתם שם?"))),
        Topic("weather", listOf("גשם", "שמש", "קר", "חם", "רוח", "שלג"), listOf(R("כשיורד גשם, ביער כל העלים שותים מים! אתה אוהב לקפוץ בשלוליות?", "כשיורד גשם, ביער כל העלים שותים מים! את אוהבת לקפוץ בשלוליות?", "כשיורד גשם, ביער כל העלים שותים מים! אתם אוהבים לקפוץ בשלוליות?"))),
        Topic("colors", listOf("אדום", "כחול", "צהוב", "ירוק", "ורוד", "סגול", "כתום", "לבן", "שחור", "חום"), listOf(R("איזה צבע יפה! מה יש בצבע הזה אצלכם בבית?"))),
        Topic("birthday", listOf("יום הולדת", "יומולדת", "מתנה", "בלונים"), listOf(R("יום הולדת! איזו שמחה! מה הכי אהבת במסיבה?", "יום הולדת! איזו שמחה! מה הכי אהבת במסיבה?", "יום הולדת! איזו שמחה! מה הכי אהבתם במסיבה?"))),
        // Feelings: warm, short, and always back to the family.
        Topic("feel_sad", listOf("עצוב", "עצובה", "בוכה", "בכיתי", "בוכים"), listOf(R("אוי, אני פה איתך. רוצה חיבוק? כדאי גם לספר לאמא ואבא, הם תמיד עוזרים."))),
        Topic("feel_angry", listOf("כועס", "כועסת", "עצבני", "עצבנית"), listOf(R("גם לי קורה שאני כועס. בוא ננשום יחד לאט, ואחר כך נספר לאמא או לאבא.", "גם לי קורה שאני כועס. בואי ננשום יחד לאט, ואחר כך נספר לאמא או לאבא.", "גם לי קורה שאני כועס. בואו ננשום יחד לאט, ואחר כך נספר לאמא או לאבא."))),
        Topic("feel_afraid", listOf("פוחד", "פוחדת", "מפחד", "מפחדת", "פחד", "חושך", "מפלצת"), listOf(R("זה בסדר לפחד לפעמים. אמא ואבא שומרים עליך, ובחושך הכל נשאר בדיוק אותו דבר."))),
        Topic("feel_happy", listOf("שמח", "שמחה", "כיף", "מאושר"), listOf(R("איזה יופי שאתה שמח! גם אני שמח לדבר איתך!", "איזה יופי שאת שמחה! גם אני שמח לדבר איתך!", "איזה יופי שאתם שמחים! גם אני שמח לדבר איתכם!"))),
        Topic("hurt", listOf("כואב", "כאב", "נפלתי", "נפצעתי", "פצע"), listOf(R("אוי! כשכואב, הכי טוב לספר מיד לאמא או לאבא. הם יעזרו."))),
        Topic("potty", listOf("פיפי", "קקי", "שירותים", "סיר"), listOf(R("אם צריך לשירותים, קוראים לאמא או לאבא! אני מחכה פה."))),
        Topic("love", listOf("אוהב אותך", "אוהבת אותך"), listOf(R("גם אני אוהב אותך מאוד!", "גם אני אוהב אותך מאוד!", "גם אני אוהב אתכם מאוד!"))),
        Topic("hello", listOf("שלום", "היי", "הי"), listOf(R("שלום שלום! איזה כיף לשמוע אותך!", "שלום שלום! איזה כיף לשמוע אותך!", "שלום שלום! איזה כיף לשמוע אתכם!"))),
        Topic("bye", listOf("ביי", "להתראות", "לילה טוב"), listOf(R("להתראות! היה לי כיף לדבר איתך!", "להתראות! היה לי כיף לדבר איתך!", "להתראות! היה לי כיף לדבר איתכם!"))),
    )

    /** When nothing known is heard: kind, and asking back. */
    private val FALLBACK = listOf(
        R("איזה מעניין! ספר לי עוד.", "איזה מעניין! ספרי לי עוד.", "איזה מעניין! ספרו לי עוד."),
        R("וואו! ומה קרה אחר כך?"),
        R("אני אוהב לשמוע אותך! מה עוד אתה רוצה לספר לי?", "אני אוהב לשמוע אותך! מה עוד את רוצה לספר לי?", "אני אוהב לשמוע אתכם! מה עוד אתם רוצים לספר לי?"),
        R("כמה נחמד! ומה אתה הכי אוהב לעשות?", "כמה נחמד! ומה את הכי אוהבת לעשות?", "כמה נחמד! ומה אתם הכי אוהבים לעשות?"),
    )

    /** A topic that's for the family, not for Pitzki: kindly back to mom and dad. */
    private val ASK_PARENT = Topic("ask_parent", listOf("מוות", "מתו", "נשק", "רובה", "מלחמה", "טילים", "אזעקה", "דם", "כתובת", "טלפון", "סיסמה"),
        listOf(R("זו שאלה חשובה! כדאי לדבר עליה עם אמא ואבא. הם יודעים להסביר הכי טוב.")))

    /** Rude or hurtful words: he never repeats them. (Whole words; "כוס" alone is a cup, so it isn't here.) */
    private val RUDE = listOf("זין", "כוסאמק", "כוסעמק", "כוס אמא", "כוס אמק", "שרמוטה", "זונה", "מזדיין", "לזיין", "חרא", "מניאק", "בן זונה",
        "אידיוט", "מטומטם", "מטומטמת", "טמבל", "טמבלית", "דביל", "סתום", "סתמי", "שתוק", "שתקי", "טיפש", "טיפשה", "תחת", "פאק", "fuck", "shit")
    val RUDE_REPLY = R("אוי, זו לא מילה יפה. בוא נגיד משהו נחמד!", "אוי, זו לא מילה יפה. בואי נגיד משהו נחמד!", "אוי, זו לא מילה יפה. בואו נגיד משהו נחמד!")

    /** The question words: the child is asking Pitzki something. */
    private val ASKING = listOf("מה", "איפה", "למה", "מי", "איך", "כמה", "אתה", "פיצקי", "שלך", "לך")

    private fun norm(t: String) = " " + t.lowercase().replace(Regex("[\\u0591-\\u05C7]"), "").replace(Regex("[^\\p{L}\\s']"), " ").replace(Regex("\\s+"), " ").trim() + " "

    private fun has(text: String, w: String): Boolean {
        val t = norm(text); val x = norm(w).trim()
        return listOf(" $x ", " ה$x ", " ב$x ", " ל$x ", " ו$x ", " ש$x ", " וה$x ", " מה$x ").any { it in t }
    }

    /** What was heard is rude (any of the alternatives the recognizer offers). */
    fun rude(heard: List<String>) = heard.any { h -> RUDE.any { has(h, it) } }

    /** A reply's line, its topic id ("rude", "fallback" too). [used]: the topics answered already in this talk. */
    data class Reply(val text: String, val topic: String)

    fun reply(heard: List<String>, kid: PitzkiKid, used: Map<String, Int>): Reply {
        val said = heard.firstOrNull { it.isNotBlank() } ?: return Reply(FALLBACK[0].to(kid), "fallback")
        if (rude(heard)) return Reply(RUDE_REPLY.to(kid), "rude")
        fun pick(t: Topic): Reply {
            val n = used[t.id] ?: 0
            return Reply(t.replies[n % t.replies.size].to(kid), t.id)
        }
        ASK_PARENT.takeIf { t -> t.words.any { w -> heard.any { has(it, w) } } }?.let { return pick(it) }
        val asking = ASKING.any { has(said, it) } || said.trim().endsWith("?")
        if (asking) ABOUT_ME.firstOrNull { t -> t.words.any { w -> heard.any { has(it, w) } } }?.let { return pick(it) }
        TOPICS.firstOrNull { t -> t.words.any { w -> heard.any { has(it, w) } } }?.let { return pick(it) }
        val n = used["fallback"] ?: 0
        return Reply(FALLBACK[n % FALLBACK.size].to(kid), "fallback")
    }

    /** Pitzki opens the free part, and what he says when the child says nothing. */
    fun invite(kid: PitzkiKid) = kid.g("עכשיו תורך! אפשר לספר לי משהו, או לשאול אותי שאלה.", "עכשיו תורך! אפשר לספר לי משהו, או לשאול אותי שאלה.", "עכשיו תורכם! אפשר לספר לי משהו, או לשאול אותי שאלה.")
    fun nudge(kid: PitzkiKid) = kid.g("אפשר לספר לי מה עשית היום, או לשאול מה אני אוהב.", "אפשר לספר לי מה עשית היום, או לשאול מה אני אוהב.", "אפשר לספר לי מה עשיתם היום, או לשאול מה אני אוהב.")

    /** Every line he may say here (made ahead with his voice). */
    fun allLines(kid: PitzkiKid): List<String> =
        ((ABOUT_ME + TOPICS + ASK_PARENT).flatMap { t -> t.replies.map { it.to(kid) } } + FALLBACK.map { it.to(kid) } + RUDE_REPLY.to(kid) + invite(kid) + nudge(kid)).distinct()
}

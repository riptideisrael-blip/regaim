package com.regaim.app

/**
 * "Talk with Pitzki" (a pilot for ~3-year-olds): Pitzki asks questions from a prepared bank (8 subjects, 8 questions
 * each), listens, and answers warmly with one small thing to learn; the child can also answer by tapping a picture.
 * His answers come from the bank. The AI is used only for open questions ("what do you like to eat?") to react in a
 * natural way, under strict rules, a few times per talk; anything unclear is never guessed. Nothing here is saved
 * except which questions were asked lately (so the next talk asks others).
 *
 * Texts: "{n}" is the child's name and a comma (or nothing); "[a/b/c]" is said to a boy / a girl / everyone.
 */
object PitzkiChat {
    /** A picture answer: what's shown, what's said, the English word (if taught), and other ways a child may say it. */
    class C(val emoji: String, val label: String, val en: String = "", vararg more: String) {
        val words: List<String> = listOf(label) + more
    }

    /**
     * One question. [choices]: the pictures (the first 3 are shown; all are understood); [right]: the right one, or -1
     * when every answer is good (a favorite, a word to repeat). [fact]: what Pitzki teaches after the answer.
     * [follow]: one more small thing to say together (optional). [open]: an answer outside the pictures gets a natural
     * reaction from the AI. [level]: 1 easy, 2 a bit harder.
     */
    class Q(
        val topic: String, val id: String, val level: Int, val ask: String,
        val choices: List<C> = emptyList(), val right: Int = -1, val fact: String,
        val follow: String = "", val open: Boolean = false,
        /** Every answer gets the same warm words (no "right!"): "your parents love you". */
        val plain: Boolean = false,
    )

    val TOPICS = listOf("colors", "words", "feelings", "food", "road", "animals", "kindness", "forest")

    private val RED = C("🔴", "אדום", "red", "אדומה")
    private val BLUE = C("🔵", "כחול", "blue", "כחולה")
    private val YELLOW = C("🟡", "צהוב", "yellow", "צהובה")
    private val GREEN = C("🟢", "ירוק", "green", "ירוקה")
    private val ORANGE = C("🟠", "כתום", "orange", "כתומה")
    private val PURPLE = C("🟣", "סגול", "purple", "סגולה")
    private val PINK = C("🩷", "ורוד", "pink", "ורודה")
    private val BLACK = C("⚫", "שחור", "black", "שחורה")
    private val WHITE = C("⚪", "לבן", "white", "לבנה")
    private val BROWN = C("🟤", "חום", "brown", "חומה")
    private val ALL_COLORS = listOf(RED, BLUE, YELLOW, GREEN, ORANGE, PURPLE, PINK, BLACK, WHITE, BROWN)
    private val CIRCLE = C("⚪", "עיגול", "circle", "עגול", "עגולה")
    private val TRIANGLE = C("🔺", "משולש", "triangle")
    private val SQUARE = C("⬛", "ריבוע", "square", "מרובע")
    private val YES = C("✅", "כן", "", "נכון", "יודע", "יודעת", "בטח")
    private val NO = C("🚫", "לא", "", "אסור", "לא מותר")
    private val TWO = C("2️⃣", "שתיים", "", "שניים", "2", "שתי")
    private val ONE = C("1️⃣", "אחת", "", "אחד", "1")
    private val THREE = C("3️⃣", "שלוש", "", "שלושה", "3")
    private val FIVE = C("5️⃣", "חמש", "", "חמישה", "5")

    val BANK: List<Q> = listOf(
        // 1. Colors and shapes
        Q("colors", "c_fav", 1, "{n}איזה צבע [אתה/את/אתם] הכי [אוהב/אוהבת/אוהבים]?", listOf(RED, BLUE, YELLOW) + ALL_COLORS.drop(3), -1,
            "אני הכי אוהב ירוק, כמו הכובע שלי!", follow = "[תגיד/תגידי/תגידו] איתי באנגלית: {en}!"),
        Q("colors", "c_banana", 1, "איזה צבע יש לבננה?", listOf(YELLOW, RED, BLUE), 0, "הבננה צהובה! צהוב באנגלית זה yellow.", "[תגיד/תגידי/תגידו] איתי: yellow!"),
        Q("colors", "c_sky", 1, "איזה צבע יש לשמיים?", listOf(BLUE, GREEN, PURPLE), 0, "השמיים כחולים! כחול באנגלית זה blue.", "[תגיד/תגידי/תגידו] איתי: blue!"),
        Q("colors", "c_grass", 1, "איזה צבע יש לדשא?", listOf(GREEN, YELLOW, RED), 0, "הדשא ירוק, כמו הכובע שלי! ירוק באנגלית זה green."),
        Q("colors", "c_strawberry", 1, "איזה צבע יש לתות?", listOf(RED, BLUE, GREEN), 0, "התות אדום! אדום באנגלית זה red.", "[תגיד/תגידי/תגידו] איתי: red!"),
        Q("colors", "c_wheel", 2, "איזו צורה יש לגלגל?", listOf(CIRCLE, TRIANGLE, SQUARE), 0, "הגלגל עגול, כמו עיגול! עיגול באנגלית זה circle."),
        Q("colors", "c_sun", 2, "איזו צורה יש לשמש?", listOf(CIRCLE, SQUARE, TRIANGLE), 0, "השמש עגולה, כמו עיגול גדול וחם!"),
        Q("colors", "c_roof", 2, "איזו צורה יש לגג של בית קטן?", listOf(TRIANGLE, CIRCLE, SQUARE), 0, "לגג יש צורה של משולש! משולש באנגלית זה triangle."),
        // 2. Words in Hebrew and English
        Q("words", "w_dog", 1, "כלב באנגלית זה dog! [תגיד/תגידי/תגידו] איתי: dog!", fact = "dog! איזה יופי!"),
        Q("words", "w_cat", 1, "חתול באנגלית זה cat! [תגיד/תגידי/תגידו] איתי: cat!", fact = "cat! מיאו!"),
        Q("words", "w_apple", 1, "תפוח באנגלית זה apple! [תגיד/תגידי/תגידו] איתי: apple!", fact = "apple! תפוח טעים!"),
        Q("words", "w_ball", 1, "כדור באנגלית זה ball! [תגיד/תגידי/תגידו] איתי: ball!", fact = "ball! בוא נשחק בכדור!"),
        Q("words", "w_sun", 1, "שמש באנגלית זה sun! [תגיד/תגידי/תגידו] איתי: sun!", fact = "sun! השמש מחממת אותנו."),
        Q("words", "w_hello", 1, "באנגלית אומרים שלום: hello! [תגיד/תגידי/תגידו] לי hello!", fact = "hello! גם אני אומר לך hello!"),
        Q("words", "w_apple_is", 2, "מה זה apple: תפוח, או בננה?", listOf(C("🍎", "תפוח", "apple", "תפוחים"), C("🍌", "בננה", "banana")), 0,
            "apple זה תפוח!"),
        Q("words", "w_dog_is", 2, "מה זה dog: כלב, או חתול?", listOf(C("🐶", "כלב", "dog", "כלבלב"), C("🐱", "חתול", "cat", "חתולה")), 0, "dog זה כלב! הב הב!"),
        // 3. Feelings, love and feeling safe
        Q("feelings", "f_love", 1, "[אתה/את/אתם] [יודע/יודעת/יודעים] שההורים [שלך/שלך/שלכם] אוהבים [אותך/אותך/אתכם] מאוד מאוד?", listOf(YES, NO), -1,
            "הם אוהבים [אותך/אותך/אתכם] הכי בעולם, עד השמיים!", plain = true),
        Q("feelings", "f_smile", 1, "[תַּרְאֶה/תַּרְאִי/תַּרְאוּ] לִי חִיּוּךְ גָּדוֹל שֶׁל שִׂמְחָה!", fact = "איזה חיוך יפה! כשאני שמח, אני מחייך ככה!"),
        Q("feelings", "f_happy", 1, "איפה הפרצוף השמח?", listOf(C("😊", "שמח", "happy", "שמחה", "מחייך"), C("😢", "עצוב", "", "עצובה"), C("😠", "כועס", "", "כועסת")), 0,
            "זה פרצוף שמח! שמח באנגלית זה happy."),
        Q("feelings", "f_sad", 1, "איפה הפרצוף העצוב?", listOf(C("😢", "עצוב", "sad", "עצובה", "בוכה"), C("😊", "שמח", "", "שמחה"), C("😮", "מופתע", "", "מופתעת")), 0,
            "זה פרצוף עצוב. כשעצוב, אפשר לבקש חיבוק מאבא או מאמא."),
        Q("feelings", "f_friend", 1, "מה עושים כשחבר עצוב?", listOf(C("🤗", "מחבקים", "", "חיבוק", "לחבק"), C("😂", "צוחקים", "", "צוחק")), 0,
            "מחבקים אותו, ושואלים: מה קרה?"),
        Q("feelings", "f_scared", 2, "כשקצת מפחדים, למי הולכים?", listOf(C("👨‍👩‍👦", "לאבא או לאמא", "", "אבא", "אמא", "הורים"), C("🌳", "לעץ", "", "עץ")), 0,
            "הולכים לאבא או לאמא, והם עוזרים לנו להרגיש טוב."),
        Q("feelings", "f_angry", 2, "כשכועסים, מה עוזר להירגע?", listOf(C("🧸", "לחבק בובה", "", "בובה", "דובי", "חיבוק"), C("👊", "להרביץ", "", "מכות")), 0,
            "מחבקים בובה, ומספרים לאבא או לאמא מה קרה."),
        Q("feelings", "f_joy", 2, "מה משמח [אותך/אותך/אתכם]?", fact = "גם אותי זה משמח! ואותי משמח לשחק עם החברים שלי ביער.", open = true),
        // 4. Food, fruit and tastes
        Q("food", "food_fav", 1, "מה [אתה/את/אתם] הכי [אוהב/אוהבת/אוהבים] לאכול?", fact = "וואו, גם אני! אני אוכל את זה כשאני נח ביער.", open = true),
        Q("food", "food_fruit", 1, "מה זה פרי: תפוח, או עוגה?", listOf(C("🍎", "תפוח", "", "תפוחים"), C("🍰", "עוגה", "", "עוגות")), 0, "תפוח הוא פרי! אני אוהב תפוחים."),
        Q("food", "food_honey", 1, "איזה טעם יש לדבש: מתוק, או חמוץ?", listOf(C("🍯", "מתוק", "", "מתוקה"), C("🍋", "חמוץ", "", "חמוצה")), 0, "הדבש מתוק, ממממ!"),
        Q("food", "food_lemon", 2, "ואיזה טעם יש ללימון: מתוק, או חמוץ?", listOf(C("🍋", "חמוץ", "", "חמוצה"), C("🍯", "מתוק", "", "מתוקה")), 0, "הלימון חמוץ! אוי, איזה פרצוף עושים!"),
        Q("food", "food_carrot", 1, "איזה צבע יש לגזר?", listOf(ORANGE, BLUE, PURPLE), 0, "הגזר כתום! והארנב החבר שלי מאוד אוהב גזר."),
        Q("food", "food_milk", 1, "מאיפה מגיע החלב: מהפרה, או מהעץ?", listOf(C("🐄", "מהפרה", "", "פרה"), C("🌳", "מהעץ", "", "עץ")), 0, "החלב מגיע מהפרה. מוווו!"),
        Q("food", "food_wash", 2, "מה עושים לפני שאוכלים?", listOf(C("🧼", "שוטפים ידיים", "", "ידיים", "לשטוף", "שוטף", "סבון"), C("🛏️", "הולכים לישון", "", "לישון")), 0,
            "שוטפים ידיים עם סבון, ואז אוכלים."),
        Q("food", "food_banana", 2, "בננה באנגלית זה banana! [תגיד/תגידי/תגידו] איתי: banana!", fact = "banana! כמעט כמו בעברית!"),
        // 5. Road safety
        Q("road", "r_alone", 1, "מותר לחצות את הכביש לבד?", listOf(NO, YES), 0, "את הכביש חוצים רק עם מבוגר, ומחזיקים לו חזק את היד."),
        Q("road", "r_hand", 1, "כשחוצים כביש, את היד של מי מחזיקים?", listOf(C("👨‍👩‍👦", "של אבא או אמא", "", "אבא", "אמא", "מבוגר"), C("🐶", "של הכלב", "", "כלב")), 0,
            "מחזיקים חזק את היד של אבא, של אמא, או של מבוגר שאנחנו מכירים."),
        Q("road", "r_ball", 1, "הכדור התגלגל לכביש. מה עושים?", listOf(C("🙋", "קוראים למבוגר", "", "מבוגר", "אבא", "אמא", "קוראים"), C("🏃", "רצים אחריו", "", "רצים", "רץ")), 0,
            "לא רצים לכביש! קוראים למבוגר, והוא יביא את הכדור."),
        Q("road", "r_red", 1, "מה עושים ברמזור אדום?", listOf(C("🛑", "עוצרים", "", "עוצר", "לעצור", "מחכים", "עומדים"), C("🏃", "רצים", "", "רץ", "הולכים")), 0, "באדום עוצרים ומחכים."),
        Q("road", "r_green", 2, "ובאיזה צבע הולכים, יחד עם מבוגר?", listOf(GREEN, RED), 0, "בירוק הולכים, ותמיד יד ביד עם מבוגר."),
        Q("road", "r_wait", 2, "לפני שחוצים כביש, מה עושים?", listOf(C("🛑", "עוצרים ומחכים", "", "עוצרים", "מחכים", "מסתכלים"), C("🏃", "רצים מהר", "", "רצים")), 0,
            "עוצרים, מסתכלים, ומחכים שהמבוגר יגיד שאפשר לעבור."),
        Q("road", "r_car", 2, "איפה יושבים במכונית?", listOf(C("💺", "בכיסא בטיחות", "", "כיסא", "בוסטר", "חגורה"), C("🛋️", "על הרצפה", "", "רצפה")), 0,
            "יושבים בכיסא הבטיחות וחוגרים חגורה. קליק!"),
        Q("road", "r_zebra", 2, "איפה חוצים את הכביש?", listOf(C("🦓", "במעבר חצייה", "", "מעבר", "פסים", "זברה"), C("🌳", "בין העצים", "", "עצים")), 0,
            "במעבר החצייה, עם הפסים הלבנים, ותמיד עם מבוגר."),
        // 6. Animals and their sounds
        Q("animals", "a_fav", 1, "איזו חיה [אתה/את/אתם] הכי [אוהב/אוהבת/אוהבים]?", fact = "אני אוהב את הארנב, החבר שלי! ארנב באנגלית זה rabbit.", open = true),
        Q("animals", "a_dog", 1, "איך עושה כלב?", listOf(C("🔊", "הב הב", "dog", "הב", "האו", "וף"), C("🔊", "מיאו", "", "מיאו מיאו")), 0, "הב הב! כלב באנגלית זה dog."),
        Q("animals", "a_cat", 1, "איך עושה חתול?", listOf(C("🔊", "מיאו", "cat", "מיאו מיאו", "מיו"), C("🔊", "הב הב", "", "הב")), 0, "מיאו! חתול באנגלית זה cat."),
        Q("animals", "a_cow", 1, "איך עושה פרה?", listOf(C("🔊", "מוו", "cow", "מו", "מוווו", "מוו מוו"), C("🔊", "גע גע", "", "גע")), 0, "מוווו! פרה באנגלית זה cow."),
        Q("animals", "a_duck", 2, "איך עושה ברווז?", listOf(C("🔊", "גע גע", "duck", "גע", "קוואק", "קווק"), C("🔊", "מוו", "", "מו")), 0, "גע גע! ברווז באנגלית זה duck."),
        Q("animals", "a_big", 1, "מי יותר גדול: פיל, או עכבר?", listOf(C("🐘", "פיל", "", "הפיל"), C("🐭", "עכבר", "", "העכבר")), 0, "הפיל גדול גדול, והעכבר קטן קטן!"),
        Q("animals", "a_fly", 1, "מי יודע לעוף: ציפור, או דג?", listOf(C("🐦", "ציפור", "", "הציפור", "ציפורים"), C("🐟", "דג", "", "הדג")), 0,
            "הציפור עפה בשמיים, והדג שוחה במים."),
        Q("animals", "a_owl", 2, "הינשוף חבר שלי. מתי הוא ער: ביום, או בלילה?", listOf(C("🌙", "בלילה", "", "לילה"), C("☀️", "ביום", "", "יום")), 0,
            "הינשוף ער בלילה, ואומר: הו הו!"),
        // 7. Friends, helping and good manners
        Q("kindness", "k_thanks", 1, "מה אומרים כשמקבלים משהו?", listOf(C("🙏", "תודה", "thank you", "תודה רבה", "תנקיו"), C("🤐", "כלום")), 0,
            "אומרים תודה! ובאנגלית: thank you."),
        Q("kindness", "k_please", 1, "ומה אומרים כשמבקשים משהו?", listOf(C("🙏", "בבקשה", "please", "בקשה"), C("🤐", "כלום")), 0, "אומרים בבקשה! ובאנגלית: please."),
        Q("kindness", "k_share", 1, "לחבר אין צעצוע. מה עושים?", listOf(C("🤝", "משתפים", "", "נותנים", "לתת", "לשתף", "חולקים"), C("🙅", "לא נותנים", "", "לא")), 0,
            "משתפים, וככה לשניים כיף!"),
        Q("kindness", "k_tidy", 1, "אמא מסדרת צעצועים. איך עוזרים לה?", listOf(C("🧸", "מסדרים איתה", "", "מסדרים", "לסדר", "עוזרים"), C("📺", "מסתכלים בטלוויזיה", "", "טלוויזיה")), 0,
            "עוזרים לסדר, ויחד זה הולך מהר!"),
        Q("kindness", "k_fall", 2, "חבר נפל. מה עושים?", listOf(C("🤲", "עוזרים לו לקום", "", "עוזרים", "לעזור", "מרימים"), C("😂", "צוחקים", "", "צוחק")), 0,
            "עוזרים לו לקום, ושואלים אם הוא בסדר."),
        Q("kindness", "k_sorry", 2, "כשבטעות דוחפים חבר, מה אומרים?", listOf(C("🙏", "סליחה", "sorry", "סליחה סליחה"), C("🤷", "כלום")), 0, "אומרים סליחה, וזה עושה לו טוב."),
        Q("kindness", "k_game", 1, "במה [אתה/את/אתם] הכי [אוהב/אוהבת/אוהבים] לשחק?", fact = "איזה כיף! אני הכי אוהב לשחק מחבואים עם החברים שלי ביער.", open = true),
        Q("kindness", "k_slide", 2, "חבר על המגלשה, ואנחנו רוצים לגלוש. מה עושים?", listOf(C("⏳", "מחכים בתור", "", "מחכים", "תור", "לחכות"), C("🙌", "דוחפים", "", "לדחוף")), 0,
            "מחכים בתור, ואז מגיע התור שלנו."),
        // 8. Forest adventures, counting and imagination
        Q("forest", "fo_birds", 1, "על הענף יושבות שתי ציפורים. כמה ציפורים יש?", listOf(TWO, ONE, THREE), 0, "שתי ציפורים! בוא נספור: אחת, שתיים!"),
        Q("forest", "fo_fingers", 2, "כמה אצבעות יש ביד אחת?", listOf(FIVE, TWO, THREE), 0, "חמש אצבעות! אחת, שתיים, שלוש, ארבע, חמש!"),
        Q("forest", "fo_eyes", 1, "כמה עיניים יש [לך/לך/לכם]?", listOf(TWO, THREE, ONE), 0, "שתי עיניים! אחת, שתיים!"),
        Q("forest", "fo_hide", 1, "אם היינו משחקים מחבואים ביער, איפה [היית מתחבא/היית מתחבאת/הייתם מתחבאים]?",
            fact = "רעיון מצוין! אני הייתי מתחבא מאחורי העץ הגדול. קוקו!", open = true),
        Q("forest", "fo_trees", 1, "מה גדל ביער: עצים, או מכוניות?", listOf(C("🌳", "עצים", "tree", "עץ"), C("🚗", "מכוניות", "", "מכונית")), 0,
            "ביער יש המון עצים! עץ באנגלית זה tree."),
        Q("forest", "fo_ears", 1, "לארנב שלי יש אוזניים ארוכות! [תעשה/תעשי/תעשו] לי אוזניים של ארנב עם הידיים!", fact = "איזה ארנב חמוד! ממש כמו החבר שלי!"),
        Q("forest", "fo_night", 2, "מה רואים בשמיים בלילה?", listOf(C("⭐", "כוכבים", "star", "כוכב", "ירח"), C("☀️", "שמש", "", "השמש")), 0,
            "בלילה יש ירח וכוכבים. כוכב באנגלית זה star."),
        Q("forest", "fo_hat", 2, "אם היה לי כובע קסמים, מה היינו מוציאים ממנו?", fact = "איזה רעיון מצחיק! ואולי גם ארנב קטן!", open = true),
    )

    /** The child's form of a text: the name, and boy / girl / everyone words. */
    fun form(kid: PitzkiKid, text: String, en: String = ""): String =
        Regex("\\[([^/\\]]*)/([^/\\]]*)/([^\\]]*)\\]").replace(text) { m -> kid.g(m.groupValues[1], m.groupValues[2], m.groupValues[3]) }
            .replace("{n}", if (kid.named) "${kid.name}, " else "")
            .replace("{en}", en)
            .replace("בוא נספור", kid.g("בוא נספור", "בואי נספור", "בואו נספור"))

    fun opening(kid: PitzkiKid) = (if (kid.named) "שלום ${kid.name}! " else "שלום! ") + "זה אני, פיצקי, עם החברים שלי מהיער. " +
        kid.g("בוא נדבר קצת!", "בואי נדבר קצת!", "בואו נדבר קצת!")
    fun closing(kid: PitzkiKid) = "היה לי כל כך כיף לדבר " + kid.g("איתך", "איתך", "איתכם") + (if (kid.named) ", ${kid.name}" else "") + "! להתראות, ביי ביי!"
    fun notHeard(kid: PitzkiKid) = "אופס, לא שמעתי טוב. " + kid.g("תנסה", "תנסי", "תנסו") + " שוב" + (if (kid.named) ", ${kid.name}" else "") +
        "? אפשר גם ללחוץ על תמונה."
    fun moveOn(kid: PitzkiKid) = "לא נורא! " + kid.g("בוא", "בואי", "בואו") + " ננסה משהו אחר."

    private val PRAISE = listOf("נכון!", "כל הכבוד!", "יופי!", "מצוין!", "וואו, נכון!")
    fun praise(i: Int) = PRAISE[Math.floorMod(i, PRAISE.size)]

    // ---------- understanding an answer (never guessing) ----------

    private val NIQQUD = Regex("[\\u0591-\\u05C7]")
    private fun norm(s: String) = s.replace(NIQQUD, "").replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim().lowercase()

    /** Which picture the child said (its index), or -1. A word must appear whole (not inside another word). */
    fun match(q: Q, heard: List<String>): Int {
        for (h in heard) {
            val t = " " + norm(h) + " "
            for ((i, c) in q.choices.withIndex()) {
                if (c.words.any { w -> val n = norm(w); n.isNotEmpty() && (t.contains(" $n ") || t.contains(" ה$n ") || t.contains(" ו$n ") || t.contains(" ב$n ")) }) return i
                if (c.en.isNotEmpty() && t.contains(" ${c.en.lowercase()} ")) return i
            }
        }
        return -1
    }

    /**
     * Pitzki's answer when the child's answer is known: right (praise + the thing to learn), not right (gently, the
     * right answer), or a favorite (said back, with its English word when there is one).
     */
    fun replyTo(kid: PitzkiKid, q: Q, picked: Int): String {
        val c = q.choices.getOrNull(picked)
        val fact = form(kid, q.fact)
        // The same words for the same answer every time: his voice for it is made once, ahead of time.
        val praise = praise(q.id.hashCode())
        return when {
            q.plain -> fact
            c == null -> "$praise $fact"
            q.right >= 0 && picked == q.right -> "$praise $fact"
            q.right >= 0 -> "כמעט! " + fact
            c.en.isNotEmpty() && q.id == "c_fav" -> "${c.label}! איזה צבע יפה! ${c.label} באנגלית זה ${c.en}."
            else -> "${c.label}! " + fact
        }
    }

    /** Said after "say it with me" (any answer is taking part). */
    const val WELL_DONE = "כל הכבוד!"

    /**
     * Every line Pitzki may say for [q] (the question, its answers, the "say with me"), so his voice for them is
     * made ahead of time and he answers at once.
     */
    fun lines(kid: PitzkiKid, q: Q): List<String> {
        val out = LinkedHashSet<String>()
        out.add(form(kid, q.ask))
        out.add(replyTo(kid, q, -1))
        if (q.right >= 0) { out.add(replyTo(kid, q, q.right)); q.choices.indices.firstOrNull { it != q.right }?.let { out.add(replyTo(kid, q, it)) } }
        else q.choices.indices.forEach { out.add(replyTo(kid, q, it)) }
        if (q.follow.isNotEmpty()) {
            if (q.follow.contains("{en}")) q.choices.filter { it.en.isNotEmpty() }.forEach { out.add(form(kid, q.follow, it.en)) }
            else out.add(form(kid, q.follow))
        }
        return out.toList()
    }

    /** Everything in the talk, for the voice made ahead (with the lessons' lines). */
    fun allLines(kid: PitzkiKid): List<String> =
        (listOf(opening(kid), closing(kid), notHeard(kid), moveOn(kid), WELL_DONE, momentReply(kid), momentQuiet(kid)) + PitzkiMoments.allLines(kid) + PitzkiFreeTalk.allLines(kid) + BANK.flatMap { lines(kid, it) }).distinct()

    /**
     * One talk's questions, chosen at the start (so his voice for them can be ready before he asks): three easy ones
     * from different subjects, then three more, each with an easy and a harder choice (the harder one after a few
     * right answers).
     */
    fun plan(recent: Set<String>, random: java.util.Random): List<Pair<Q, Q>> {
        val used = HashSet<String>()
        val out = ArrayList<Pair<Q, Q>>()
        repeat(3) { pick(used, recent, 1, random)?.let { used.add(it.topic); out.add(it to it) } }
        repeat(3) {
            val easy = pick(used, recent, 1, random) ?: return@repeat
            used.add(easy.topic)
            val hard = BANK.filter { it.topic == easy.topic && it.level == 2 && it.id !in recent }.shuffled(random).firstOrNull()
                ?: BANK.filter { it.topic == easy.topic && it.level == 2 }.shuffled(random).firstOrNull() ?: easy
            out.add(easy to hard)
        }
        return out
    }

    /**
     * The next question: from a subject not asked yet in this talk, at the child's level (harder ones after a few
     * right answers), not one asked in the last talks.
     */
    fun pick(usedTopics: Set<String>, recent: Set<String>, level: Int, random: java.util.Random): Q? {
        val pool = BANK.filter { it.topic !in usedTopics && it.level <= level }
        val fresh = pool.filter { it.id !in recent }.ifEmpty { pool }
        if (fresh.isEmpty()) return null
        // One question per subject: a subject is chosen first (so the talk goes around), then a question in it.
        val topic = fresh.map { it.topic }.distinct().shuffled(random).first()
        return fresh.filter { it.topic == topic }.shuffled(random).first()
    }

    // ---------- the AI, for open questions only ----------

    /** The rules for the AI's reactions (as Pitzki, to a small child). */
    fun system(kid: PitzkiKid): String {
        val who = when {
            !kid.named -> "a small child"
            kid.girl -> "a small girl named ${kid.name}"
            else -> "a small boy named ${kid.name}"
        }
        val forms = when {
            !kid.named -> "plural forms (אתם)"
            kid.girl -> "feminine singular forms (את)"
            else -> "masculine singular forms (אתה)"
        }
        return """
            You are Pitzki, a gentle, cheerful little forest elf in a children's learning app, talking out loud with $who, about 3 years old.
            The child hears your words in your voice. Reply in simple Hebrew only (at most one English word you are teaching, in English letters).
            Talk to the child in $forms.
            Your reply: one or two very short sentences, 5 to 20 words in all, only words a 3-year-old knows. Warm, playful and encouraging.
            React to what the child said about the topic, and you may add the given idea in your own words.
            Never ask a question: the app asks the next one.
            Never ask about or mention personal things: no address, place, kindergarten or school, phone, passwords, family names, the parents' details,
            money, the body, secrets, pictures, other apps, or meeting anyone. Never claim you heard or know anything about the child.
            Never suggest doing anything without a parent, and never say you replace a parent or a grown-up.
            Clean, gentle language only: no rude, scary, violent, sexual or grown-up words, even if the child says them; then just go back kindly to the topic.
            Never call the child bad or silly, and never shame a wrong answer.
            If the child's words seem unclear, unrelated or cut off, don't guess what they meant: say something kind and use the given idea.
            If the child says something worrying (hurt, scared, sad, someone hurt them), answer gently in one sentence that it's good
            to tell Abba or Ima or a grown-up they love, and ask nothing about it.
            You are Pitzki the elf, never a real person. Write only Pitzki's words: no quotes, no emoji, no stage directions, no names.
        """.trimIndent()
    }

    fun prompt(kid: PitzkiKid, q: Q, heard: String): String =
        "Pitzki asked: ${form(kid, q.ask)}\nAn idea you may use: ${form(kid, q.fact)}\nThe child answered (speech recognizer, may be wrong): ${heard.take(160)}"

    /** Words that never come out of Pitzki's mouth (a rare AI slip): his prepared answer is said instead. */
    private val NEVER = listOf(
        "מטומטם", "טיפש", "סתום", "שתוק", "חרא", "זונה", "למות", "להרוג", "רובה", "סכין", "סוד", "כתובת", "טלפון", "סיסמה", "כסף",
        "fuck", "shit", "stupid", "kill",
    )

    /** The AI's reaction made fit to say, or null (then the prepared answer is said). */
    fun clean(reply: String): String? {
        var t = reply.trim().trim('"', '\'', '“', '”', '״')
        t = t.replace(Regex("[*_#>`]"), "").replace(Regex("\\([^)]*\\)"), "")
            .replace(Regex("[\\p{So}\\p{Cs}]"), "").replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty() || t.length > 170) return null
        if (!Regex("[\\u05D0-\\u05EA]").containsMatchIn(t)) return null
        val low = t.lowercase()
        if (NEVER.any { low.contains(it) }) return null
        // Pitzki doesn't ask here (the app asks the next question): a question is cut off.
        if (t.contains('?')) {
            val q = t.substringBeforeLast('?')
            t = q.substring(0, maxOf(q.lastIndexOf('.'), q.lastIndexOf('!')) + 1).trim()
            if (t.isEmpty()) return null
        }
        if (t.split(' ').size > 28) return null
        return t
    }

    /** After the child told about the real moment of the week. */
    fun momentReply(kid: PitzkiKid) = kid.g("איזה כיף! תודה שסיפרת לי!", "איזה כיף! תודה שסיפרת לי!", "איזה כיף! תודה שסיפרתם לי!")
    /** Nothing said: it's fine, on we go. */
    fun momentQuiet(kid: PitzkiKid) = kid.g("נשמע כיף! עכשיו בוא נשחק.", "נשמע כיף! עכשיו בואי נשחק.", "נשמע כיף! עכשיו בואו נשחק.")
}

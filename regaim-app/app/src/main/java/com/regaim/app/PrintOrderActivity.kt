package com.regaim.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Ordering a printed copy of a book: the address, the price from Lulu (book + shipping), then the order. Lulu prints
 * it and ships it; this screen also shows the order's state afterwards (in production, shipped, tracking link).
 */
class PrintOrderActivity : AppCompatActivity() {
    companion object {
        private const val EXTRA_ID = "book"
        fun open(ctx: Context, bookId: String) = ctx.startActivity(Intent(ctx, PrintOrderActivity::class.java).putExtra(EXTRA_ID, bookId))
    }

    private val ui by lazy { Ui(this) }
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var content: LinearLayout
    private var book: Book? = null
    private var busy: String? = null
    private var quote: JSONObject? = null
    private var shipping = 0
    private val levels = listOf("MAIL", "PRIORITY_MAIL", "EXPRESS")
    private val fields = LinkedHashMap<String, EditText>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        book = BookStore.load(this, intent.getStringExtra(EXTRA_ID).orEmpty()) ?: run { finish(); return }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(40)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(ui.color(R.color.bg)); addView(content) })
        render()
        // An order already placed: its latest state.
        if (PrintBook.order(this, book!!.id) != null) refreshStatus()
    }

    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }

    private fun render() {
        val b = book ?: return
        content.removeAllViews()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(ui.heading(L.t("ספר מודפס", "Printed book"), 26f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(ui.action(L.t("סגור", "Close")) { finish() })
        content.addView(head)
        content.addView(ui.text(b.title, 17f, bold = true, top = 6))
        content.addView(ui.hint(L.t(
            "כריכה קשה, 21.6×27.9 ס\"מ, צבע מלא, ${PrintBook.pageCount(b)} עמודים: המילים מול כל ציור. מודפס ונשלח על ידי Lulu.",
            "Hardcover, 8.5×11 in, full color, ${PrintBook.pageCount(b)} pages: the words facing each picture. Printed and shipped by Lulu.",
        ), top = 4))

        PrintBook.order(this, b.id)?.let { o -> orderCard(o); return }

        busy?.let { msg ->
            content.addView(ui.text(msg, 16f, bold = true, top = 24))
            content.addView(ProgressBar(this).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { topMargin = ui.dp(12) } })
            content.addView(ui.hint(L.t("זה לוקח כמה דקות. השאירו את המסך פתוח.", "This takes a few minutes. Keep the screen open."), top = 8))
            return
        }

        content.addView(ui.section(L.t("לאן לשלוח", "Where to send it")))
        val saved = PrintBook.lastAddress(this)
        fun field(key: String, hint: String, type: Int = InputType.TYPE_CLASS_TEXT, def: String = "") {
            val f = fields[key] ?: ui.field(hint, saved.optString(key, def)).apply { inputType = type; id = View.generateViewId() }
            fields[key] = f
            (f.parent as? LinearLayout)?.removeView(f)
            content.addView(f)
        }
        field("name", L.t("שם מלא", "Full name"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME)
        field("street1", L.t("רחוב ומספר", "Street and number"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS)
        field("street2", L.t("דירה, כניסה (לא חובה)", "Apartment (optional)"))
        field("city", L.t("עיר", "City"))
        field("postcode", L.t("מיקוד", "Postal code"), InputType.TYPE_CLASS_NUMBER)
        field("phone_number", L.t("טלפון (לשליח)", "Phone (for the courier)"), InputType.TYPE_CLASS_PHONE)
        field("email", L.t("מייל לעדכונים", "Email for updates"), InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or InputType.TYPE_CLASS_TEXT, Account.email(this))
        field("country_code", L.t("מדינה (קוד, למשל IL)", "Country (code, e.g. IL)"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, "IL")

        content.addView(ui.section(L.t("משלוח", "Shipping")))
        content.addView(ui.chips(listOf(L.t("רגיל", "Standard"), L.t("מהיר", "Priority"), L.t("אקספרס", "Express")), shipping) { i ->
            shipping = i; quote = null; render()
        })

        val q = quote
        if (q == null) {
            content.addView(ui.button(L.t("כמה זה עולה?", "How much is it?"), primary = true, top = 18) { getQuote() })
        } else {
            val cur = q.optString("currency", "USD")
            val card = ui.card(top = 16)
            fun money(v: Any?) = (v as? Number)?.toDouble()?.let { "%.2f %s".format(it, cur) } ?: (v?.toString()?.takeIf { it.isNotBlank() && it != "null" }?.let { "$it $cur" } ?: "-")
            card.addView(ui.row(L.t("הדפסת הספר", "Printing"), money(q.opt("book")), top = 0))
            card.addView(ui.row(L.t("משלוח", "Shipping"), money(q.opt("shipping"))))
            if (q.opt("fees") != null && q.opt("fees") != JSONObject.NULL) card.addView(ui.row(L.t("עמלת טיפול", "Handling"), money(q.opt("fees"))))
            card.addView(ui.row(L.t("סה\"כ (כולל מס)", "Total (incl. tax)"), money(q.opt("total"))))
            content.addView(card)
            content.addView(ui.hint(L.t("התשלום יורד מהכרטיס שבחשבון ה-Lulu של Moments.", "Charged to the card on Moments' Lulu account."), top = 6))
            content.addView(ui.button(L.t("הזמינו עכשיו", "Order now"), primary = true, top = 14) { confirmOrder() })
        }
    }

    private fun address(): JSONObject? {
        val a = JSONObject()
        for (k in listOf("name", "street1", "street2", "city", "postcode", "phone_number", "country_code")) a.put(k, fields[k]?.text?.toString()?.trim().orEmpty())
        val missing = listOf("name", "street1", "city", "postcode", "phone_number").filter { a.optString(it).isEmpty() }
        if (missing.isNotEmpty() || (fields["email"]?.text?.toString()?.contains("@") != true)) {
            android.widget.Toast.makeText(this, L.t("חסרים פרטים בכתובת או במייל", "Some address or email details are missing"), android.widget.Toast.LENGTH_LONG).show()
            return null
        }
        if (a.optString("country_code").isEmpty()) a.put("country_code", "IL")
        PrintBook.rememberAddress(this, JSONObject(a.toString()).put("email", fields["email"]?.text?.toString()?.trim()))
        return a
    }

    private fun getQuote() {
        val b = book ?: return
        val a = address() ?: return
        busy = L.t("בודקים מחיר אצל Lulu...", "Asking Lulu for the price...")
        render()
        worker.execute {
            val r = try { PrintBook.quote(applicationContext, b, a, levels[shipping]) } catch (e: Exception) { JSONObject().put("_error", e.message ?: "") }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                busy = null
                if (r.has("_error")) android.widget.Toast.makeText(this, r.optString("_error"), android.widget.Toast.LENGTH_LONG).show() else quote = r
                render()
            }
        }
    }

    private fun confirmOrder() {
        val b = book ?: return
        val a = address() ?: return
        val email = fields["email"]?.text?.toString()?.trim().orEmpty()
        AlertDialog.Builder(this)
            .setTitle(L.t("להזמין עותק מודפס?", "Order a printed copy?"))
            .setMessage(L.t(
                "Lulu תדפיס ותשלח עותק אחד אל ${a.optString("name")}, ${a.optString("city")}. אחרי ההזמנה אי אפשר לשנות את הספר.",
                "Lulu will print and ship one copy to ${a.optString("name")}, ${a.optString("city")}. The book can't be changed after ordering.",
            ))
            .setPositiveButton(L.t("כן, להזמין", "Yes, order")) { _, _ -> place(b, a, email) }
            .setNegativeButton(L.t("ביטול", "Cancel"), null)
            .show()
    }

    private fun place(b: Book, a: JSONObject, email: String) {
        busy = L.t("מכינים את הספר להדפסה...", "Preparing the book for print...")
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        render()
        worker.execute {
            val err = try {
                PrintBook.place(applicationContext, b.id, a, email, levels[shipping]) { msg -> runOnUiThread { busy = msg; render() } }
                null
            } catch (e: Throwable) { if (e is OutOfMemoryError) L.t("לא היה מספיק זיכרון בטלפון. סגרו אפליקציות אחרות ונסו שוב.", "Not enough memory on the phone. Close other apps and try again.") else e.message ?: "" }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                busy = null
                if (err != null) android.widget.Toast.makeText(this, L.t("ההזמנה לא נשלחה: ", "The order wasn't sent: ") + err, android.widget.Toast.LENGTH_LONG).show()
                render()
            }
        }
    }

    /** An order that was placed: its Lulu number, state and tracking. */
    private fun orderCard(o: JSONObject) {
        val card = ui.card(top = 18)
        card.addView(ui.text(L.t("ההזמנה נשלחה ל-Lulu ✓", "Order sent to Lulu ✓"), 17f, bold = true))
        card.addView(ui.row(L.t("מספר הזמנה", "Order number"), o.optString("id"), top = 8))
        card.addView(ui.row(L.t("מצב", "Status"), statusName(o.optString("status"))))
        card.addView(ui.row(L.t("הוזמן", "Ordered"), Fmt.day(o.optLong("created"))))
        o.optString("message").takeIf { it.isNotBlank() && it != "null" }?.let { card.addView(ui.hint(it, top = 4)) }
        val tracking = when (val t = o.opt("tracking")) { is JSONArray -> (0 until t.length()).map { t.optString(it) }; is String -> listOf(t); else -> emptyList() }.filter { it.startsWith("http") }
        tracking.forEach { url -> card.addView(ui.action(L.t("מעקב משלוח ›", "Track the shipment ›")) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) }
        content.addView(card)
        if (busy == null) content.addView(ui.button(L.t("רענון מצב ההזמנה", "Refresh the order status"), top = 12) { refreshStatus() })
        else content.addView(ui.hint(busy!!, top = 12))
        if (o.optString("status") == "UNPAID") content.addView(ui.hint(L.t(
            "Lulu מחכה לתשלום: צריך להיכנס לחשבון ב-Lulu ולשלם על ההזמנה (או להוסיף כרטיס אשראי לחשבון).",
            "Lulu is waiting for payment: open the Lulu account and pay for the order (or add a card to the account).",
        ), top = 10))
    }

    private fun statusName(s: String): String = when (s) {
        "CREATED" -> L.t("נוצרה", "Created")
        "UNPAID" -> L.t("ממתינה לתשלום ב-Lulu", "Waiting for payment at Lulu")
        "PAYMENT_IN_PROGRESS" -> L.t("בתשלום", "Payment in progress")
        "PRODUCTION_DELAYED", "PRODUCTION_READY" -> L.t("ממתינה להדפסה", "Waiting to print")
        "IN_PRODUCTION" -> L.t("בהדפסה", "Printing")
        "SHIPPED" -> L.t("נשלחה 📦", "Shipped 📦")
        "REJECTED" -> L.t("נדחתה (בעיה בקבצים)", "Rejected (a file problem)")
        "CANCELED" -> L.t("בוטלה", "Canceled")
        "" -> "-"
        else -> s
    }

    private fun refreshStatus() {
        val b = book ?: return
        busy = L.t("בודקים מצב אצל Lulu...", "Checking with Lulu...")
        render()
        worker.execute {
            val err = try { PrintBook.status(applicationContext, b.id); null } catch (e: Exception) { e.message ?: "" }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                busy = null
                if (err != null) android.widget.Toast.makeText(this, err, android.widget.Toast.LENGTH_LONG).show()
                render()
            }
        }
    }
}

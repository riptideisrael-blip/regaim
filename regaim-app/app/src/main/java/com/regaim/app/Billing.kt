package com.regaim.app

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/**
 * Paying for a book, through Google Play (the rule for digital content in an app from Google Play).
 *
 * Off until the server says payments are on (film_settings "payments" = "on"): until then books use credits only,
 * as before. When on, a parent without enough credits buys one book; our server checks the purchase with Google and
 * adds the credits, and only then is the purchase "used" on the phone. A purchase whose answer was lost (the app
 * closed, no signal) is found and finished the next time ([recover]). Nothing about cards is ever seen by the app.
 *
 * Products (one-time, consumable), set up in Play Console with the same ids:
 *  - book_digital: a digital book (1 credit)
 *  - book_animated: an animated book (2 credits: the book and its animation)
 *  - book_animation: animating a book already made (1 credit)
 */
object Billing {
    const val DIGITAL = "book_digital"
    const val ANIMATED = "book_animated"
    const val ANIMATION = "book_animation"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("billing", Context.MODE_PRIVATE)

    /** Payments are on (the server's switch, remembered from its last answer). */
    fun on(ctx: Context) = prefs(ctx).getBoolean("on", false)
    fun setOn(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("on", v).apply()

    /** Google's price for the product in the parent's currency, once known; else our display price. */
    fun price(ctx: Context, product: String): String = prefs(ctx).getString("price_$product", null) ?: when (product) {
        ANIMATED -> BookMaker.PRICE_ANIMATED
        ANIMATION -> BookMaker.ANIMATION_EXTRA
        else -> BookMaker.PRICE_DIGITAL
    }

    private val main = Handler(Looper.getMainLooper())
    private var client: BillingClient? = null
    /** The purchase waiting for an answer: (product, callback). */
    private var waiting: Pair<String, (Boolean, String) -> Unit>? = null

    private val listener = PurchasesUpdatedListener { result, purchases ->
        val cb = waiting
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                val list = purchases.orEmpty()
                if (list.isEmpty()) { waiting = null; cb?.second?.invoke(false, L.t("התשלום לא הושלם", "The payment wasn't completed")) }
                list.forEach { finish(appCtx ?: return@forEach, it) }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> { waiting = null; cb?.second?.invoke(false, "") }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                // A purchase that was paid but not finished: finish it now.
                appCtx?.let { recover(it) { ok -> waiting = null; cb?.second?.invoke(ok, if (ok) "" else L.t("נסו שוב בעוד רגע", "Try again in a moment")) } }
            }
            else -> {
                android.util.Log.w("Billing", "purchase: ${result.responseCode} ${result.debugMessage}")
                waiting = null; cb?.second?.invoke(false, L.t("התשלום לא הצליח. לא חויבתם. נסו שוב בעוד רגע.", "The payment didn't go through. You weren't charged. Try again in a moment."))
            }
        }
    }
    private var appCtx: Context? = null

    /** Connects to Google Play, then runs [then] (on the main thread), or reports why it can't. */
    private fun connect(ctx: Context, then: (BillingClient?) -> Unit) {
        appCtx = ctx.applicationContext
        val c = client ?: BillingClient.newBuilder(ctx.applicationContext)
            .setListener(listener)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build().also { client = it }
        if (c.isReady) { then(c); return }
        c.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                main.post { then(if (result.responseCode == BillingClient.BillingResponseCode.OK) c else null) }
            }
            override fun onBillingServiceDisconnected() { client = null }
        })
    }

    /**
     * Buys [product]. [done] (main thread): (paid and the credits were added, message). An empty message with false
     * means the parent cancelled.
     */
    fun buy(activity: Activity, product: String, done: (Boolean, String) -> Unit) {
        connect(activity) { c ->
            if (c == null) { done(false, L.t("Google Play לא זמין כרגע במכשיר הזה", "Google Play isn't available on this device right now")); return@connect }
            val q = QueryProductDetailsParams.newBuilder().setProductList(listOf(
                QueryProductDetailsParams.Product.newBuilder().setProductId(product).setProductType(BillingClient.ProductType.INAPP).build(),
            )).build()
            c.queryProductDetailsAsync(q) { result, details ->
                main.post {
                    val pd: ProductDetails? = details.productDetailsList.firstOrNull()
                    if (result.responseCode != BillingClient.BillingResponseCode.OK || pd == null) {
                        android.util.Log.w("Billing", "product: ${result.responseCode} ${result.debugMessage}")
                        done(false, L.t("הרכישה לא זמינה כרגע. נסו שוב מאוחר יותר.", "This purchase isn't available right now. Try again later.")); return@post
                    }
                    pd.oneTimePurchaseOfferDetails?.formattedPrice?.let { prefs(activity).edit().putString("price_$product", it).apply() }
                    val params = BillingFlowParams.newBuilder()
                        .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(pd).build()))
                        // Lets our server check that the purchase belongs to this account.
                        .setObfuscatedAccountId(Account.id(activity))
                        .build()
                    waiting = product to done
                    val r = c.launchBillingFlow(activity, params)
                    if (r.responseCode != BillingClient.BillingResponseCode.OK) {
                        waiting = null
                        android.util.Log.w("Billing", "launch: ${r.responseCode} ${r.debugMessage}")
                        done(false, L.t("לא הצלחנו לפתוח את התשלום. נסו שוב בעוד רגע.", "Couldn't open the payment. Try again in a moment."))
                    }
                }
            }
        }
    }

    /** A paid purchase: our server checks it with Google and adds the credits; then it's used up on the phone. */
    private fun finish(ctx: Context, p: Purchase, after: ((Boolean) -> Unit)? = null) {
        if (p.purchaseState == Purchase.PurchaseState.PENDING) {
            val cb = waiting; waiting = null
            cb?.second?.invoke(false, L.t("התשלום ממתין לאישור. הספר יתווסף כשהתשלום יאושר.", "The payment is waiting for approval. The book is added once it's approved."))
            after?.invoke(false); return
        }
        if (p.purchaseState != Purchase.PurchaseState.PURCHASED) { after?.invoke(false); return }
        Thread {
            val product = p.products.firstOrNull().orEmpty()
            val ok = try {
                val token = Account.token(ctx) ?: throw IllegalStateException(L.t("צריך להתחבר", "Please sign in"))
                val r = CartoonFilm.callEdge(Circle.url(ctx) + "/functions/v1/cartoon-film", token,
                    mapOf("action" to "play_purchase", "token" to p.purchaseToken, "product" to product))
                r.optBoolean("ok", false)
            } catch (_: Exception) { false }
            main.post {
                if (ok) client?.consumeAsync(ConsumeParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) { _, _ -> }
                val cb = waiting; waiting = null
                cb?.second?.invoke(ok, if (ok) "" else L.t("התשלום התקבל אבל עוד לא אושר אצלנו. נסו שוב בעוד רגע (לא תחויבו פעמיים).", "The payment went through but we haven't confirmed it yet. Try again in a moment (you won't be charged twice)."))
                after?.invoke(ok)
            }
        }.start()
    }

    /**
     * Before something that takes credits: when payments are on and the account has fewer than [need], the parent buys
     * what's missing ([whole] for a whole book; the animation alone when only it is missing); then [then] runs. When
     * payments are off, or the balance can't be checked right now, [then] runs and the server decides as before.
     */
    fun ensureCredits(activity: Activity, need: Int, whole: String, then: () -> Unit) {
        val app = activity.applicationContext
        Thread {
            val credits = CartoonFilm.credits(app) // also refreshes whether payments are on
            main.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                if (credits == null || !on(app) || credits >= need) { then(); return@post }
                val product = if (need == 2 && credits == 1) ANIMATION else whole
                buy(activity, product) { ok, msg ->
                    if (ok) then()
                    else if (msg.isNotEmpty()) android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** Finishes purchases that were paid but never confirmed (call when a book screen opens). [done]: any finished. */
    fun recover(ctx: Context, done: ((Boolean) -> Unit)? = null) {
        if (!on(ctx)) { done?.invoke(false); return }
        connect(ctx) { c ->
            if (c == null) { done?.invoke(false); return@connect }
            c.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { _, list ->
                main.post {
                    val paid = list.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                    if (paid.isEmpty()) { done?.invoke(false); return@post }
                    var left = paid.size
                    var any = false
                    paid.forEach { p -> finish(ctx.applicationContext, p) { ok -> any = any || ok; if (--left == 0) done?.invoke(any) } }
                }
            }
        }
    }
}

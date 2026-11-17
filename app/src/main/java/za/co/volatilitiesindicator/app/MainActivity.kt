package za.co.volatilitiesindicator.app

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.Locale

class MainActivity : Activity(), DerivApiClient.Callback {

    private val ink = Color.rgb(28, 39, 51)
    private val muted = Color.rgb(112, 125, 140)
    private val green = Color.rgb(20, 150, 92)
    private val red = Color.rgb(205, 70, 70)
    private val amber = Color.rgb(207, 145, 35)
    private val pageColor = Color.rgb(245, 247, 250)
    private val cardColor = Color.WHITE
    private val lineColor = Color.rgb(226, 231, 237)

    private lateinit var content: LinearLayout
    private var selectedTab = "Home"

    private val indexes = listOf("R_10", "R_25", "R_50", "R_75", "R_100")
    private val latestPrices = mutableMapOf<String, Double>()
    private val priceViews = mutableMapOf<String, TextView>()
    private val indexStatusViews = mutableMapOf<String, TextView>()
    private val marketStatusViews = mutableMapOf<String, TextView>()

    private var marketFeedConnected = false
    private var accountConnected = false
    private var accountLabel = "Not connected"
    private var accountBalanceLabel = "Balance unavailable"
    private var feedPillView: TextView? = null

    private var derivService: DerivConnectionService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? DerivConnectionService.LocalBinder)?.getService()
                ?: return
            derivService = service
            service.attachActivity(this@MainActivity)
            // Public market feed: does not require signing in to a Deriv account.
            service.client.requestHistoricalCandles()
            runOnUiThread { updateConnectionLabels("Connecting to live market feed…") }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            derivService?.detachActivity(this@MainActivity)
            derivService = null
            serviceBound = false
            marketFeedConnected = false
            runOnUiThread { updateConnectionLabels("Market feed disconnected") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = pageColor
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        render()
        startMarketFeedService()
    }

    private fun startMarketFeedService() {
        if (serviceBound) return
        val intent = Intent(this, DerivConnectionService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            serviceBound = bindService(intent, serviceConnection, BIND_AUTO_CREATE)
            if (!serviceBound) updateConnectionLabels("Unable to bind market feed service")
        } catch (e: Exception) {
            updateConnectionLabels("Market feed unavailable: ${e.message ?: "service error"}")
        }
    }

    override fun onDestroy() {
        derivService?.detachActivity(this)
        if (serviceBound) {
            try { unbindService(serviceConnection) } catch (_: Exception) {}
            serviceBound = false
        }
        super.onDestroy()
    }

    override fun onConnecting(message: String) {
        runOnUiThread { updateConnectionLabels(message) }
    }

    override fun onConnected(accountId: String, accountType: String) {
        accountConnected = true
        accountLabel = "$accountId ($accountType)"
        runOnUiThread { updateConnectionLabels("Deriv account connected") }
    }

    override fun onBalance(balance: Double, currency: String) {
        accountBalanceLabel = String.format(Locale.US, "%.2f %s", balance, currency)
        runOnUiThread { renderIfHome() }
    }

    override fun onTick(symbol: String, quote: Double, epoch: Long) {
        if (symbol !in indexes || !quote.isFinite()) return
        latestPrices[symbol] = quote
        marketFeedConnected = true
        runOnUiThread {
            feedPillView?.text = "LIVE"
            feedPillView?.setTextColor(green)
            priceViews[symbol]?.text = formatPrice(quote)
            indexStatusViews[symbol]?.apply {
                text = "LIVE"
                setTextColor(green)
            }
        }
    }

    override fun onCandles(symbol: String, candles: List<DerivApiClient.Candle>) {
        if (symbol !in indexes) return
        if (candles.isNotEmpty()) {
            marketFeedConnected = true
            val lastClose = candles.last().close
            if (!latestPrices.containsKey(symbol)) latestPrices[symbol] = lastClose
            runOnUiThread {
                feedPillView?.text = "LIVE"
                feedPillView?.setTextColor(green)
                priceViews[symbol]?.text = formatPrice(latestPrices[symbol] ?: lastClose)
                indexStatusViews[symbol]?.apply {
                    text = "LIVE"
                    setTextColor(green)
                }
            }
        }
    }

    override fun onMarketDataError(symbol: String, message: String) {
        runOnUiThread {
            indexStatusViews[symbol]?.apply {
                text = "FEED ERROR"
                setTextColor(red)
            }
            marketStatusViews[symbol]?.text = message
            updateConnectionLabels("Market data error: $symbol")
        }
    }

    override fun onError(message: String) {
        runOnUiThread { updateConnectionLabels(message) }
    }

    override fun onTradeData(json: JSONObject) {
        // Trade/account events are not used by the market-only screen yet.
    }

    override fun onDisconnected() {
        accountConnected = false
        runOnUiThread { updateConnectionLabels("Deriv account disconnected") }
    }

    private fun render() {
        priceViews.clear()
        indexStatusViews.clear()
        marketStatusViews.clear()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pageColor)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(22))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(bottomNavigation())
        setContentView(root)

        when (selectedTab) {
            "Home" -> showHome()
            "Trade" -> showTrade()
            "Accounts" -> showAccounts()
            "Settings" -> showSettings()
        }
    }

    private fun showHome() {
        val brand = horizontal()
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.volatilities_indicator_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Volatilities Indicator logo"
        }
        brand.addView(logo, LinearLayout.LayoutParams(dp(58), dp(58)))
        val brandText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        brandText.addView(text("VOLATILITIES", 19f, ink, true))
        brandText.addView(text("INDICATOR", 19f, ink, true))
        brand.addView(brandText)
        content.addView(brand)
        content.addView(space(3))
        content.addView(text("Market monitor", 13f, muted))
        content.addView(space(18))

        val status = horizontal()
        status.addView(text("DERIV MARKET FEED", 12f, muted, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        val feedPill = pill(if (marketFeedConnected) "LIVE" else "CONNECTING", if (marketFeedConnected) green else amber)
        feedPillView = feedPill
        status.addView(feedPill)
        content.addView(status)
        content.addView(space(12))

        val account = card()
        account.addView(text("TRADING ACCOUNT", 12f, muted, true))
        account.addView(space(8))
        account.addView(text(if (accountConnected) accountLabel else "Not connected", 17f, ink, true))
        account.addView(text(accountBalanceLabel, 13f, muted))
        account.addView(space(12))
        account.addView(actionButton("ACCOUNT OPTIONS") { openTab("Accounts") })
        content.addView(account)
        content.addView(space(18))

        content.addView(text("INDEX MONITOR", 16f, ink, true))
        content.addView(text("Live Deriv prices for all five synthetic indices.", 12f, muted))
        content.addView(space(10))

        indexes.forEach { symbol ->
            val indexCard = card()
            val row = horizontal()
            row.addView(text(symbol, 15f, ink, true),
                LinearLayout.LayoutParams(0, -2, 1f))
            val statusView = pill(
                if (latestPrices.containsKey(symbol)) "LIVE" else "CONNECTING",
                if (latestPrices.containsKey(symbol)) green else amber
            )
            indexStatusViews[symbol] = statusView
            row.addView(statusView)
            indexCard.addView(row)
            indexCard.addView(space(10))

            val priceRow = horizontal()
            priceRow.addView(text("LIVE PRICE", 11f, muted),
                LinearLayout.LayoutParams(0, -2, 1f))
            val priceView = text(latestPrices[symbol]?.let { formatPrice(it) } ?: "--", 17f, ink, true)
            priceViews[symbol] = priceView
            priceRow.addView(priceView)
            indexCard.addView(priceRow)
            indexCard.addView(space(8))
            indexCard.addView(divider())
            indexCard.addView(space(8))
            indexCard.addView(text("MASTER DECISION", 11f, muted, true))
            indexCard.addView(text("WAITING", 12f, amber, true))
            val feedDetail = text("Waiting for market data…", 11f, muted)
            marketStatusViews[symbol] = feedDetail
            indexCard.addView(feedDetail)
            content.addView(indexCard)
            content.addView(space(9))
        }
    }

    private fun showTrade() {
        pageHeading("RUNNING TRADES", "Open positions and their current status.")
        val c = card()
        c.addView(text("NO TRADE DATA", 15f, ink, true))
        c.addView(space(6))
        c.addView(text("Trade display will be connected after the live account and execution workflow is added.", 13f, muted))
        content.addView(c)
    }

    private fun showAccounts() {
        pageHeading("ACCOUNTS", "Account access and connection settings.")
        val deriv = card()
        deriv.addView(text("DERIV ACCOUNT", 15f, ink, true))
        deriv.addView(space(6))
        deriv.addView(text(if (accountConnected) accountLabel else "Connection: Disconnected", 13f, muted))
        deriv.addView(space(12))
        deriv.addView(actionButton("CONNECT DERIV") {
            toast("Deriv OAuth sign-in is not yet connected to this fresh screen.")
        })
        content.addView(deriv)
        content.addView(space(12))

        val users = card()
        users.addView(text("USER ACCOUNT", 15f, ink, true))
        users.addView(text("Sign-in and registration interface will be added here.", 13f, muted))
        users.addView(space(10))
        users.addView(actionButton("USER ACCOUNT", false) {
            toast("User account screen is not connected yet.")
        })
        content.addView(users)
        content.addView(space(12))

        val admin = card()
        admin.addView(text("ADMIN ACCOUNT", 15f, ink, true))
        admin.addView(text("Administrator access and user management.", 13f, muted))
        admin.addView(space(10))
        admin.addView(actionButton("ADMIN ACCOUNT", false) {
            toast("Admin account screen is not connected yet.")
        })
        content.addView(admin)
    }

    private fun showSettings() {
        pageHeading("SETTINGS", "Application preferences.")
        val c = card()
        c.addView(text("APPLICATION", 15f, ink, true))
        c.addView(space(8))
        c.addView(settingRow("Interface", "Light"))
        c.addView(divider())
        c.addView(settingRow("Market indexes", "R_10, R_25, R_50, R_75, R_100"))
        c.addView(divider())
        c.addView(settingRow("Market feed", if (marketFeedConnected) "Connected" else "Connecting"))
        c.addView(divider())
        c.addView(settingRow("Trading account", if (accountConnected) "Connected" else "Not connected"))
        content.addView(c)
    }

    private fun updateConnectionLabels(message: String) {
        if (selectedTab == "Home") {
            feedPillView?.text = if (marketFeedConnected) "LIVE" else "CONNECTING"
            feedPillView?.setTextColor(if (marketFeedConnected) green else amber)
        }
    }

    private fun renderIfHome() {
        if (selectedTab == "Home") render()
    }

    private fun pageHeading(title: String, subtitle: String) {
        content.addView(text(title, 21f, ink, true))
        content.addView(text(subtitle, 13f, muted))
        content.addView(space(18))
    }

    private fun settingRow(label: String, value: String): View {
        val row = horizontal()
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(12), 0, dp(12))
        row.addView(text(label, 14f, ink, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(text(value, 13f, muted))
        return row
    }

    private fun bottomNavigation(): View {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.WHITE)
            setPadding(dp(6), dp(8), dp(6), dp(8))
            elevation = dp(8).toFloat()
        }
        listOf("Home", "Trade", "Accounts", "Settings").forEach { tab ->
            val item = TextView(this).apply {
                text = tab
                textSize = 12f
                gravity = Gravity.CENTER
                setTypeface(null, if (selectedTab == tab) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (selectedTab == tab) green else muted)
                setPadding(dp(4), dp(8), dp(4), dp(8))
                setOnClickListener { openTab(tab) }
            }
            nav.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
        }
        return nav
    }

    private fun openTab(tab: String) {
        selectedTab = tab
        render()
    }

    private fun card(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(cardColor, lineColor, 14)
            elevation = dp(1).toFloat()
        }
    }

    private fun actionButton(label: String, primary: Boolean = true, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            setTextColor(if (primary) Color.WHITE else green)
            background = rounded(if (primary) green else Color.rgb(235, 247, 241),
                if (primary) green else Color.rgb(210, 235, 222), 10)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { action() }
        }
    }

    private fun pill(label: String, color: Int): TextView {
        return TextView(this).apply {
            text = label
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setTextColor(color)
            setPadding(dp(9), dp(5), dp(9), dp(5))
            background = rounded(Color.WHITE, color, 20)
        }
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
        }
    }

    private fun horizontal() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(lineColor)
        layoutParams = LinearLayout.LayoutParams(-1, dp(1))
    }

    private fun space(height: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun rounded(fill: Int, stroke: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fill)
            setCornerRadius(dp(radius).toFloat())
            setStroke(dp(1), stroke)
        }
    }

    private fun formatPrice(value: Double): String =
        String.format(Locale.US, "%,.2f", value)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }
}

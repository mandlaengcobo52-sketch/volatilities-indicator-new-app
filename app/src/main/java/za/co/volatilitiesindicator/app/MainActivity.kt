package za.co.volatilitiesindicator.app

import android.app.Activity
import android.os.Bundle
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Switch
import android.widget.Toast
import android.content.Intent
import android.net.Uri
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import org.json.JSONObject

class MainActivity : Activity() {

    private val pageBackground = Color.rgb(246, 248, 252)
    private val surface = Color.WHITE
    private val primary = Color.rgb(31, 48, 75)
    private val secondary = Color.rgb(103, 119, 140)
    private val accent = Color.rgb(0, 145, 150)
    private val indexBackgrounds = listOf(
        Color.rgb(238, 246, 255),
        Color.rgb(237, 249, 246),
        Color.rgb(245, 242, 255),
        Color.rgb(255, 247, 237),
        Color.rgb(255, 241, 243)
    )
    private val indexAccents = listOf(
        Color.rgb(48, 112, 190),
        Color.rgb(0, 145, 120),
        Color.rgb(116, 86, 190),
        Color.rgb(205, 125, 35),
        Color.rgb(190, 72, 95)
    )

    private lateinit var pageHeading: TextView
    private lateinit var workspace: LinearLayout
    private val navigationItems = mutableListOf<TextView>()
    private val priceViews = mutableMapOf<String, TextView>()
    private lateinit var feedStatus: TextView
    private var derivApiClient: DerivApiClient? = null
    private var selectedPage = "HOME"
    private var executionMode = "DEMO"
    private var accountStatusText = "NOT CONNECTED"
    private var connectedAccountId = ""
    private var accountBalanceText = "—"
    private val accountPrefs by lazy { getSharedPreferences("deriv_oauth", MODE_PRIVATE) }
    private val oauthHttp by lazy { OkHttpClient() }
    private var oauthVerifier = ""
    private var oauthState = ""
    private var tradeTab = "RUNNING"
    private lateinit var connectionStatusView: TextView
    private lateinit var accountDetailsView: TextView

    private val indexSymbols = listOf("R_10", "R_25", "R_50", "R_75", "R_100")

    private val indexes = listOf(
    "VOLATILITY INDEX 10",
    "VOLATILITY INDEX 25",
    "VOLATILITY INDEX 50",
    "VOLATILITY INDEX 75",
    "VOLATILITY INDEX 100"
)
        private data class RollingRange(
        val high: Double,
        val low: Double,
        val highEpoch: Long,
        val lowEpoch: Long
    )

    private val rollingRanges =
        mutableMapOf<String, RollingRange>()

    private val candleHistory =
        mutableMapOf<String, List<DerivApiClient.Candle>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = pageBackground
        window.navigationBarColor = surface
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pageBackground)
            fitsSystemWindows = true
        }

        // HEADER WITH BRAND MARK
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(22.dp(), 18.dp(), 22.dp(), 18.dp())
            setBackgroundColor(surface)
        }

        header.addView(BrandMark(this), LinearLayout.LayoutParams(54.dp(), 54.dp()))

        val brandText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp(), 0, 0, 0)
        }
        brandText.addView(label("VOLATILITIES", 21f, primary, true))
        brandText.addView(label("INDICATOR", 15f, accent, true).apply {
            letterSpacing = 0.08f
            setPadding(0, 2.dp(), 0, 0)
        })
        header.addView(brandText, LinearLayout.LayoutParams(0, -2, 1f))

        val notificationBell = label("♧", 25f, primary, true).apply {
            text = "🔔"
            gravity = Gravity.CENTER
            setPadding(10.dp(), 8.dp(), 8.dp(), 8.dp())
            isClickable = true
            contentDescription = "Notifications"
            setOnClickListener { showPage("NOTIFICATIONS") }
        }
        header.addView(notificationBell, LinearLayout.LayoutParams(48.dp(), 48.dp()))
        root.addView(header)

        // MAIN WORKSPACE
        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 24.dp(), 20.dp(), 20.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        pageHeading = label("HOME", 12f, secondary, true).apply {
            letterSpacing = 0.12f
            setPadding(2.dp(), 0, 0, 14.dp())
        }
        main.addView(pageHeading)

        workspace = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(surface, 20)
            setPadding(16.dp(), 18.dp(), 16.dp(), 18.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        main.addView(workspace)
        root.addView(main)

        // CLICKABLE BOTTOM NAVIGATION
        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(surface)
            setPadding(8.dp(), 12.dp(), 8.dp(), 12.dp())
        }

        listOf("HOME", "CONNECTION", "TRADE", "SETTINGS", "PROFILE").forEach { page ->
            val itemView = TextView(this).apply {
                text = page
                textSize = 8.5f
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.04f
                isClickable = true
                isFocusable = true
                setPadding(3.dp(), 12.dp(), 3.dp(), 12.dp())
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                setOnClickListener { showPage(page) }
            }
            navigationItems.add(itemView)
            navigation.addView(itemView)
        }

        root.addView(navigation)
        showPage(selectedPage)
        setContentView(root)

        // Public market prices start immediately; no Deriv account login is required.
        startLiveIndexFeed()
        handleOAuthCallback(intent)
    }

    private fun showPage(page: String) {
        selectedPage = page
        pageHeading.text = if (page == "NOTIFICATIONS") "NOTIFICATIONS" else page
        navigationItems.forEach { item ->
            val active = item.text.toString() == page
            item.setTextColor(if (active) accent else secondary)
            item.alpha = if (active) 1f else 0.82f
        }

        workspace.removeAllViews()
        when (page) {
            "HOME" -> showIndexes()
            "CONNECTION" -> showConnectionScreen()
            "TRADE" -> showTradeScreen()
            "NOTIFICATIONS" -> showNotificationsScreen()
            "SETTINGS" -> showSettingsScreen()
            "PROFILE" -> showProfileScreen()
        }
    }


    private fun addSectionTitle(title: String, subtitle: String? = null) {
        workspace.addView(label(title, 13f, primary, true).apply {
            setPadding(0, 2.dp(), 0, 4.dp())
        })
        if (!subtitle.isNullOrBlank()) {
            workspace.addView(label(subtitle, 10f, secondary, false).apply {
                setPadding(0, 0, 0, 12.dp())
            })
        }
    }

    private fun addInfoCard(title: String, detail: String, tint: Int = indexBackgrounds[0]) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(tint, 12)
            setPadding(14.dp(), 13.dp(), 14.dp(), 13.dp())
        }
        card.addView(label(title, 11f, primary, true))
        card.addView(label(detail, 10f, secondary, false).apply {
            setPadding(0, 5.dp(), 0, 0)
        })
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, -2
        ).apply { bottomMargin = 10.dp() }
        workspace.addView(card, params)
    }

    private fun showTradeScreen() {
        addSectionTitle("TRADE ACTIVITY", "Monitor open positions and review completed trades.")
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(pageBackground, 10)
            setPadding(4.dp(), 4.dp(), 4.dp(), 4.dp())
        }
        listOf("RUNNING", "HISTORY").forEach { tab ->
            val selected = tradeTab == tab
            val tabView = label(tab, 10f, if (selected) Color.WHITE else secondary, true).apply {
                gravity = Gravity.CENTER
                background = rounded(if (selected) accent else pageBackground, 8)
                setPadding(8.dp(), 11.dp(), 8.dp(), 11.dp())
                isClickable = true
                setOnClickListener {
                    tradeTab = tab
                    showPage("TRADE")
                }
            }
            tabs.addView(tabView, LinearLayout.LayoutParams(0, -2, 1f))
        }
        workspace.addView(tabs)
        workspace.addView(View(this), LinearLayout.LayoutParams(1, 10.dp()))
        if (tradeTab == "RUNNING") {
            addInfoCard("RUNNING TRADES", "No running trades to display yet.", indexBackgrounds[1])
            workspace.addView(label("When a trade is opened, its index, direction, stake, entry price and current status will appear here.", 10f, secondary, false))
        } else {
            addInfoCard("TRADE HISTORY", "No completed trades recorded yet.", indexBackgrounds[2])
            workspace.addView(label("Completed trade results and profit or loss will appear here.", 10f, secondary, false))
        }
    }

    private fun showNotificationsScreen() {
        addSectionTitle("NOTIFICATIONS", "Important account and trading updates.")
        addInfoCard("YOU'RE ALL CAUGHT UP", "New alerts and trade notifications will appear here.", indexBackgrounds[0])
    }

    private fun showSettingsScreen() {
        addSectionTitle("TRADING SETTINGS", "Configure your trading preferences.")
        val trailingEnabled = accountPrefs.getBoolean("trailing_stop_enabled", false)
        val trailingSwitch = Switch(this).apply {
            text = "STANDALONE TRAILING STOP"
            textSize = 11f
            setTextColor(primary)
            isChecked = trailingEnabled
            setOnCheckedChangeListener { _, checked ->
                accountPrefs.edit().putBoolean("trailing_stop_enabled", checked).apply()
            }
        }
        val trailingCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(indexBackgrounds[0], 12)
            setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
            addView(trailingSwitch)
            addView(label("Enable or disable the trailing-stop preference. Configuration and trade integration will be added separately.", 9.5f, secondary, false).apply {
                setPadding(0, 5.dp(), 0, 0)
            })
        }
        workspace.addView(trailingCard, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = 12.dp()
        })

        val autoTradeSwitch = Switch(this).apply {
            text = "AUTO TRADE"
            textSize = 11f
            setTextColor(primary)
            isChecked = accountPrefs.getBoolean("auto_trade_enabled", false)
            setOnCheckedChangeListener { _, checked ->
                accountPrefs.edit().putBoolean("auto_trade_enabled", checked).apply()
            }
        }
        val autoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(indexBackgrounds[1], 12)
            setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
            addView(autoTradeSwitch)
            addView(label("Save your Auto Trade preference. This switch does not activate execution logic yet.", 9.5f, secondary, false).apply {
                setPadding(0, 5.dp(), 0, 0)
            })
        }
        workspace.addView(autoCard)
    }

    private fun showProfileScreen() {
        addSectionTitle("USER PROFILE", "Your Volatilities Indicator account.")
        addInfoCard("PROFILE", "User profile details can be added when registration fields are connected.", indexBackgrounds[0])
        addInfoCard("DERIV ACCOUNT", "Type: $executionMode\nStatus: $accountStatusText\nAccount ID: ${if (connectedAccountId.isBlank()) "—" else connectedAccountId}\nBalance: $accountBalanceText", indexBackgrounds[1])
    }

    private fun showConnectionScreen() {
        workspace.addView(label("DERIV ACCOUNT CONNECTION", 12f, primary, true))
        workspace.addView(label("Choose the account type you want to connect.", 10f, secondary, false))
        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12.dp(), 0, 12.dp())
        }
        listOf("DEMO", "REAL").forEach { mode ->
            val selected = mode == executionMode
            val b = label(mode, 12f, if (selected) Color.WHITE else primary, true).apply {
                gravity = Gravity.CENTER
                background = rounded(if (selected) accent else indexBackgrounds[0], 10)
                setPadding(18.dp(), 12.dp(), 18.dp(), 12.dp())
                isClickable = true
                setOnClickListener {
                    executionMode = mode
                    accountPrefs.edit().putString("account_mode", mode).apply()
                    showPage("CONNECTION")
                }
            }
            modeRow.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (mode == "DEMO") rightMargin = 8.dp() else leftMargin = 8.dp()
            })
        }
        workspace.addView(modeRow)

        connectionStatusView = label("STATUS: $accountStatusText", 11f, secondary, true)
        connectionStatusView.setPadding(0, 8.dp(), 0, 8.dp())
        workspace.addView(connectionStatusView)

        accountDetailsView = label(
            if (connectedAccountId.isBlank()) "Account: —\nBalance: —" else
                "Account: $connectedAccountId\nBalance: $accountBalanceText",
            11f, primary, false
        )
        accountDetailsView.setPadding(0, 4.dp(), 0, 12.dp())
        workspace.addView(accountDetailsView)

        val connectButton = label("CONNECT $executionMode ACCOUNT", 11f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = rounded(accent, 10)
            setPadding(14.dp(), 14.dp(), 14.dp(), 14.dp())
            isClickable = true
            setOnClickListener { beginDerivOAuth() }
        }
        workspace.addView(connectButton)

        val disconnectButton = label("DISCONNECT", 10f, secondary, true).apply {
            gravity = Gravity.CENTER
            setPadding(14.dp(), 14.dp(), 14.dp(), 14.dp())
            isClickable = true
            setOnClickListener {
                derivApiClient?.disconnect()
                accountStatusText = "NOT CONNECTED"
                connectedAccountId = ""
                accountBalanceText = "—"
                showPage("CONNECTION")
            }
        }
        workspace.addView(disconnectButton)
    }

    private fun beginDerivOAuth() {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        oauthVerifier = Base64.encodeToString(bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(oauthVerifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        oauthState = "android-" + UUID.randomUUID().toString()
accountPrefs.edit()
    .putString("oauth_state", oauthState)
    .putString("oauth_verifier", oauthVerifier)
    .putString("oauth_mode", executionMode)
    .apply()

        val authUrl = Uri.parse(DerivApiClient.OAUTH_AUTH_URL).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", DerivApiClient.clientId(executionMode))
            .appendQueryParameter("redirect_uri", DerivApiClient.OAUTH_REDIRECT_URI)
            .appendQueryParameter("scope", DerivApiClient.OAUTH_SCOPE)
            .appendQueryParameter("state", oauthState)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
        try {
            startActivity(Intent(Intent.ACTION_VIEW, authUrl))
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to open Deriv login: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleOAuthCallback(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "volatilitiesindicator" || data.host != "oauth-callback") return
        val error = data.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            Toast.makeText(this, "Deriv OAuth error: $error", Toast.LENGTH_LONG).show()
            return
        }
        val code = data.getQueryParameter("code")
        val returnedState = data.getQueryParameter("state")
        val savedState = accountPrefs.getString("oauth_state", "").orEmpty()
        oauthVerifier = accountPrefs.getString("oauth_verifier", "").orEmpty()
        executionMode = accountPrefs.getString("oauth_mode", "DEMO") ?: "DEMO"
        if (code.isNullOrBlank() || savedState.isBlank() || returnedState != savedState || oauthVerifier.isBlank()) {
            Toast.makeText(this, "OAuth security check failed or authorization code is missing.", Toast.LENGTH_LONG).show()
            return
        }
        showPage("CONNECTION")
exchangeOAuthCode(code)
    }

    private fun exchangeOAuthCode(code: String) {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("client_id", DerivApiClient.clientId(executionMode))
            .add("redirect_uri", DerivApiClient.OAUTH_REDIRECT_URI)
            .add("code_verifier", oauthVerifier)
            .build()
        val request = Request.Builder().url(DerivApiClient.OAUTH_TOKEN_URL).post(body).build()
        oauthHttp.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    accountStatusText = "OAUTH FAILED"
                    Toast.makeText(this@MainActivity, "Deriv login failed: ${e.message}", Toast.LENGTH_LONG).show()
                    if (selectedPage == "CONNECTION") showPage("CONNECTION")
                }
            }
            override fun onResponse(call: Call, response: Response) {
                val responseText = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    runOnUiThread {
                        accountStatusText = "OAUTH FAILED"
                        Toast.makeText(this@MainActivity, "Deriv OAuth HTTP ${response.code}: ${responseText.take(180)}", Toast.LENGTH_LONG).show()
                        if (selectedPage == "CONNECTION") showPage("CONNECTION")
                    }
                    return
                }
                val token = try { JSONObject(responseText).optString("access_token", "") } catch (_: Exception) { "" }
                if (token.isBlank()) {
                    runOnUiThread { Toast.makeText(this@MainActivity, "Deriv did not return an access token.", Toast.LENGTH_LONG).show() }
                    return
                }
   accountPrefs.edit().putString(if (executionMode == "REAL") "real_token" else "demo_token", token)
                    .remove("oauth_state").remove("oauth_verifier").apply()
                runOnUiThread {
                    accountStatusText = "CONNECTING"
                    if (selectedPage == "CONNECTION") showPage("CONNECTION")
                    ensureDerivClient().connectWithAccessToken(token, executionMode)
                }
            }
        })
    }

    private fun ensureDerivClient(): DerivApiClient {
        derivApiClient?.let { return it }
        val client = DerivApiClient(object : DerivApiClient.Callback {
            override fun onConnecting(message: String) {
                runOnUiThread {
                    accountStatusText = "CONNECTING"
                    if (selectedPage == "CONNECTION") showPage("CONNECTION")
                }
            }
            override fun onConnected(accountId: String, accountType: String) {
                runOnUiThread {
                    connectedAccountId = accountId
                    accountStatusText = "CONNECTED • $accountType"
                    if (selectedPage == "CONNECTION") showPage("CONNECTION")
                }
            }
            override fun onBalance(balance: Double, currency: String) {
                runOnUiThread {
                    accountBalanceText = "${java.text.DecimalFormat("#,##0.00").format(balance)} $currency"
                    if (selectedPage == "CONNECTION") showPage("CONNECTION")
                }
            }
                        override fun onTick(
                symbol: String,
                quote: Double,
                epoch: Long
            ) {
                if (!quote.isFinite() || epoch <= 0L) return

                updateRollingRangeFromTick(symbol, quote, epoch)

                runOnUiThread {
                    priceViews[symbol]?.text = formatIndexPrice(quote)

                    if (::feedStatus.isInitialized) {
                        feedStatus.text = "LIVE • DERIV MARKET DATA"
                        feedStatus.setTextColor(accent)
                    }
                }
                        }
                        override fun onCandles(
                symbol: String,
                candles: List<DerivApiClient.Candle>
            ) {
                val now = System.currentTimeMillis() / 1000L
                val cutoff = now - (24L * 60L * 60L)

                val recentCandles = candles.filter {
                    it.epoch >= cutoff && it.epoch <= now
                }

                if (recentCandles.isEmpty()) {
                    android.util.Log.e(
                        "RollingRange",
                        "$symbol: no valid candles within the last 24 hours"
                    )
                    return
                }

                val highest = recentCandles.maxByOrNull { it.high } ?: return
                val lowest = recentCandles.minByOrNull { it.low } ?: return

                synchronized(candleHistory) {
                    candleHistory[symbol] = recentCandles

                    rollingRanges[symbol] = RollingRange(
                        high = highest.high,
                        low = lowest.low,
                        highEpoch = highest.epoch,
                        lowEpoch = lowest.epoch
                    )
                }

                android.util.Log.i(
                    "RollingRange",
                    "$symbol | 24H HIGH: ${highest.high} | 24H LOW: ${lowest.low}"
                )
                        }

override fun onMarketDataStatus(message: String) {
    runOnUiThread {
        if (::feedStatus.isInitialized) {
            feedStatus.text = message
            feedStatus.setTextColor(secondary)
        }
    }
}

override fun onMarketDataError(symbol: String, message: String) {
    android.util.Log.e(
        "MarketData",
        "$symbol: $message"
    )

    runOnUiThread {
        if (::feedStatus.isInitialized) {
            feedStatus.text = "DATA ERROR • $symbol: $message"
            feedStatus.setTextColor(Color.RED)
        }
    }
}
            override fun onError(message: String) {
                runOnUiThread {
                    accountStatusText = "CONNECTION ERROR"
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                    if (selectedPage == "CONNECTION") showPage("CONNECTION")
                }
            }
            override fun onTradeData(json: JSONObject) = Unit
            override fun onDisconnected() {
                runOnUiThread {
                    accountStatusText = "DISCONNECTED"
                    if (selectedPage == "CONNECTION") showPage("CONNECTION")
                }
            }
        })
        derivApiClient = client
        client.requestHistoricalCandles()
        return client
    }
        private fun updateRollingRangeFromTick(
        symbol: String,
        quote: Double,
        epoch: Long
    ) {
        if (symbol !in indexSymbols) return

        synchronized(candleHistory) {
            val history = candleHistory[symbol]?.toMutableList() ?: return
            val minuteEpoch = (epoch / 60L) * 60L

            val existingIndex = history.indexOfLast {
                it.epoch == minuteEpoch
            }

            if (existingIndex >= 0) {
                val previous = history[existingIndex]

                history[existingIndex] = DerivApiClient.Candle(
                    epoch = minuteEpoch,
                    open = previous.open,
                    high = maxOf(previous.high, quote),
                    low = minOf(previous.low, quote),
                    close = quote
                )
            } else {
                history.add(
                    DerivApiClient.Candle(
                        epoch = minuteEpoch,
                        open = quote,
                        high = quote,
                        low = quote,
                        close = quote
                    )
                )
            }

            val cutoff = epoch - 86400L
            val recent = history
                .filter { it.epoch >= cutoff && it.epoch <= epoch }
                .sortedBy { it.epoch }

            if (recent.isEmpty()) return

            candleHistory[symbol] = recent

            val highest = recent.maxByOrNull { it.high } ?: return
            val lowest = recent.minByOrNull { it.low } ?: return

            rollingRanges[symbol] = RollingRange(
                high = highest.high,
                low = lowest.low,
                highEpoch = highest.epoch,
                lowEpoch = lowest.epoch
            )
        }
        }

    private fun showIndexes() {
        priceViews.clear()

        workspace.addView(label("INDEX MONITOR", 11f, primary, true).apply {
            letterSpacing = 0.08f
            setPadding(2.dp(), 0, 0, 8.dp())
        })

        feedStatus = label("CONNECTING TO DERIV...", 9f, secondary, true).apply {
            letterSpacing = 0.04f
            setPadding(2.dp(), 0, 0, 10.dp())
            includeFontPadding = false
        }
        workspace.addView(feedStatus)

        indexes.forEachIndexed { position, index ->
            val symbol = indexSymbols[position]
            val indexCard = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(indexBackgrounds[position], 11)
                setPadding(10.dp(), 8.dp(), 11.dp(), 8.dp())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                ).apply {
                    bottomMargin = 7.dp()
                }
            }

            val accentBar = View(this).apply {
                background = rounded(indexAccents[position], 3)
            }
            indexCard.addView(
                accentBar,
                LinearLayout.LayoutParams(4.dp(), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                    rightMargin = 9.dp()
                }
            )

            val name = label(index, 10.5f, indexAccents[position], true).apply {
                includeFontPadding = false
                maxLines = 1
                isSingleLine = true
                setTextColor(indexAccents[position])
            }
            indexCard.addView(name, LinearLayout.LayoutParams(0, -2, 1f))

            val priceView = label("--", 12f, primary, true).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                includeFontPadding = false
                maxLines = 1
            }
            priceViews[symbol] = priceView
            indexCard.addView(priceView)

            workspace.addView(indexCard)
        }
    }

    private fun startLiveIndexFeed() {
        ensureDerivClient().requestHistoricalCandles()
    }

    private fun formatIndexPrice(value: Double): String {
        return java.text.DecimalFormat("#,##0.00##").format(value)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOAuthCallback(intent)
    }

    override fun onDestroy() {
        derivApiClient?.destroy()
        derivApiClient = null
        super.onDestroy()
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.dp().toFloat()
        }
    }

    private fun Int.dp(): Int =
            (this * resources.displayMetrics.density).toInt()

    // Simple vector-style logo: rising bars with a curved underline.
    private inner class BrandMark(context: android.content.Context) : View(context) {
        private val green = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(30, 166, 75)
            style = Paint.Style.FILL
        }
        private val navy = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = primary
            style = Paint.Style.STROKE
            strokeWidth = 5.dp().toFloat()
            strokeCap = Paint.Cap.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val base = h * 0.76f
            val barWidth = w * 0.17f
            val gap = w * 0.07f
            val left = w * 0.12f
            val heights = floatArrayOf(h * 0.30f, h * 0.48f, h * 0.67f)

            heights.forEachIndexed { index, barHeight ->
                val x = left + index * (barWidth + gap)
                canvas.drawRoundRect(
                    x, base - barHeight, x + barWidth, base,
                    3.dp().toFloat(), 3.dp().toFloat(), green
                )
            }

            val curve = Path().apply {
                moveTo(w * 0.10f, h * 0.78f)
                cubicTo(w * 0.32f, h * 0.98f, w * 0.82f, h * 0.92f, w * 0.91f, h * 0.48f)
            }
            canvas.drawPath(curve, navy)
        }
    }
}

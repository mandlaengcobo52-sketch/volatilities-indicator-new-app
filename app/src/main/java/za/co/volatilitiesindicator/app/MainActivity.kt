package za.co.volatilitiesindicator.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/** Volatilities Indicator Android app. */
class MainActivity : Activity(), DerivApiClient.Callback {

    private val bg = Color.rgb(3, 8, 6)
    private val panel = Color.rgb(8, 20, 18)
    private val panel2 = Color.rgb(16, 34, 39)
    private val green = Color.rgb(0, 255, 92)
    private val greenDark = Color.rgb(0, 120, 48)
    private val white = Color.rgb(242, 248, 245)
    private val muted = Color.rgb(145, 164, 155)
    private val yellow = Color.rgb(255, 220, 45)
    private val red = Color.rgb(255, 65, 75)
    private val cyan = Color.rgb(0, 225, 235)

    private var platform = "USER"
    private var executionMode = "DEMO"
    private var selectedTab = "Home"
    private var drawerOpen = false

    private lateinit var page: LinearLayout
    private lateinit var accountStatus: TextView
    private lateinit var accountIdView: TextView
    private lateinit var balanceView: TextView
    private var connected = false
    private var accountId = ""
    private var accountType = ""
    private var accountBalance = 0.0
    private var accountCurrency = ""
    private val derivApiClient by lazy { DerivApiClient(this) }
    private val httpClient = OkHttpClient()
    private val prefs by lazy { getSharedPreferences("deriv_oauth", MODE_PRIVATE) }

    private data class MarketIndexState(
        var price: Double? = null,
        var candles: List<DerivApiClient.Candle> = emptyList(),
        var lastCandleEpoch: Long = 0L,
        var status: String = "WAITING",
        var message: String = "Waiting for Deriv market data..."
    )

    private val marketSymbols = listOf("R_10", "R_25", "R_50", "R_75", "R_100")
    private val marketData = marketSymbols.associateWith { MarketIndexState() }.toMutableMap()
    private val marketDataViews = mutableMapOf<String, Map<String, TextView>>()
    private var marketDataStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0

        render()
        handleOAuthCallback(intent)
        if (prefs.getString(if (executionMode == "REAL") "real_token" else "demo_token", "").orEmpty().isNotBlank()) {
            accountStatus.text = "●  TOKEN SAVED — TAP CONNECT"
            accountStatus.setTextColor(yellow)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            handleOAuthCallback(intent)
        }
    }

    private fun render() {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(18))
            setBackgroundColor(bg)
        }

        scroll.addView(page)

        outer.addView(
            scroll,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        setContentView(outer)

        addHeader()

        if (drawerOpen) {
            addDrawer()
        }

        if (selectedTab == "Analysis") {
            if (platform == "ADMIN") {
                addAdminMarketData()
            } else {
                addAdminOnlyNotice()
            }
        } else {
            addPlatformSelector()
            addExecutionSelector()
            addAccountCard()
            addIndexMonitor()

            if (platform == "ADMIN") {
                addAdminPreview()
            }
        }

        addBottomNavigation(outer)
    }

    // =========================================================
    // HEADER: EXISTING LOGO AND NOTIFICATION BELL
    // =========================================================

    private fun addHeader() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(12))
        }

        val menu = label("☰", 28f, white, true).apply {
            gravity = Gravity.CENTER

            setOnClickListener {
                drawerOpen = !drawerOpen
                render()
            }
        }

        row.addView(
            menu,
            LinearLayout.LayoutParams(dp(40), dp(54))
        )

        // Use the logo PNG already in res/drawable.
        val logo = ImageView(this).apply {
            setImageResource(
                R.drawable.volatilities_indicator_logo
            )

            scaleType = ImageView.ScaleType.CENTER_CROP

            contentDescription = "Volatilities Indicator logo"

            background = shape(
                Color.rgb(0, 22, 12),
                green,
                100,
                2
            )

            clipToOutline = true
        }

        row.addView(
            logo,
            LinearLayout.LayoutParams(dp(52), dp(52)).apply {
                rightMargin = dp(8)
            }
        )

        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }

        titles.addView(
            label("VOLATILITIES", 18f, white, true)
        )

        titles.addView(
            label("— INDICATOR —", 15f, green, true)
        )

        titles.addView(
            label("TRADE SMARTER TODAY", 8f, muted, false)
        )

        row.addView(
            titles,
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        // Notification bell. Notification functionality is not
        // connected yet.
        val bell = TextView(this).apply {
            text = "♧"
            textSize = 29f
            setTextColor(white)
            gravity = Gravity.CENTER
            contentDescription = "Notifications"

            setOnClickListener {
                toast("Notifications will be connected later")
            }
        }

        // Draw a clear bell symbol rather than using the club glyph.
        bell.text = "🔔"
        bell.textSize = 25f

        row.addView(
            bell,
            LinearLayout.LayoutParams(dp(42), dp(52))
        )

        page.addView(row)
    }

    // =========================================================
    // NAVIGATION DRAWER
    // =========================================================

    private fun addDrawer() {
        val card = card()

        card.orientation = LinearLayout.VERTICAL

        card.addView(
            label("NAVIGATION", 13f, green, true)
        )

        val items = if (platform == "ADMIN") {
            listOf(
                "Dashboard",
                "Index Settings",
                "Risk Categories",
                "User Accounts",
                "Notifications",
                "Trading Logs",
                "System Settings",
                "Logout"
            )
        } else {
            listOf(
                "Home",
                "Trade",
                "Analysis",
                "News",
                "Settings",
                "Account",
                "Logout"
            )
        }

        items.forEach { item ->
            val b = button("  $item", false)

            b.gravity = Gravity.START or Gravity.CENTER_VERTICAL

            b.setOnClickListener {
                drawerOpen = false
                selectedTab = item
                render()
            }

            card.addView(
                b,
                matchWrap().apply {
                    topMargin = dp(4)
                }
            )
        }

        page.addView(
            card,
            matchWrap().apply {
                bottomMargin = dp(10)
            }
        )
    }

    // =========================================================
    // PLATFORM SELECTOR
    // =========================================================

    private fun addPlatformSelector() {
        page.addView(sectionTitle("PLATFORM"))

        val row = horizontalRow()

        row.addView(
            button("♟  USER", platform == "USER") {
                platform = "USER"
                drawerOpen = false
                render()
            },
            weighted()
        )

        row.addView(space(dp(8)))

        row.addView(
            button("♟♟  ADMIN", platform == "ADMIN") {
                platform = "ADMIN"
                drawerOpen = false
                render()
            },
            weighted()
        )

        page.addView(row)
    }

    // =========================================================
    // EXECUTION MODE SELECTOR
    // =========================================================

    private fun addExecutionSelector() {
        page.addView(sectionTitle("EXECUTION MODE"))

        val row = horizontalRow()

        row.addView(
            button("⌁  DEMO", executionMode == "DEMO") {
                executionMode = "DEMO"
                render()
            },
            weighted()
        )

        row.addView(space(dp(8)))

        row.addView(
            button("▥  REAL", executionMode == "REAL") {
                executionMode = "REAL"
                render()
            },
            weighted()
        )

        page.addView(row)
    }

    // =========================================================
    // DERIV ACCOUNT CARD — VISUAL PLACEHOLDER ONLY
    // =========================================================

    private fun addAccountCard() {
        val card = card()

        card.orientation = LinearLayout.VERTICAL

        card.setPadding(
            dp(12),
            dp(12),
            dp(12),
            dp(12)
        )

        card.addView(
            label(
                "⚡  DERIV EXECUTION ACCOUNT",
                15f,
                yellow,
                true
            )
        )

        accountStatus = label(
    if (connected) {
        "●  CONNECTED — $executionMode"
    } else {
        "●  NOT CONNECTED"
    },
    14f,
    if (connected) green else red,
    true
)

        val statusLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        statusLine.addView(
            accountStatus,
            LinearLayout.LayoutParams(-1, -2)
        )

        card.addView(
            statusLine,
            matchWrap().apply {
                topMargin = dp(4)
            }
        )

        accountIdView = label("Account:  ${if (accountId.isBlank()) "—" else accountId}", 14f, white, false)
        card.addView(accountIdView, matchWrap().apply { topMargin = dp(7) })

        balanceView = label("Balance:  ${if (connected) "$accountCurrency ${String.format(java.util.Locale.US, "%.2f", accountBalance)}" else "—"}", 14f, white, false)
        card.addView(balanceView, matchWrap().apply { topMargin = dp(4) })

        card.addView(
            label("Open P/L:  —", 14f, white, false),
            matchWrap().apply {
                topMargin = dp(4)
            }
        )

        val buttons = horizontalRow()

        buttons.addView(
            button(if (connected) "↗  DISCONNECT" else "↗  CONNECT", true) {
                if (connected) disconnectDeriv() else beginDerivConnection()
            },
            weighted()
        )

                buttons.addView(
            button("⟳  REFRESH", false) {
                if (connected) {
                    accountStatus.text = "●  CONNECTED — $executionMode"
                    accountStatus.setTextColor(green)
                    toast("Deriv connection is active.")
                } else {
                    toast("Connect to Deriv first.")
                }
            },
            weighted()
        )

        card.addView(
            buttons,
            matchWrap().apply {
                topMargin = dp(8)
            }
        )

        page.addView(
            card,
            matchWrap().apply {
                topMargin = dp(14)
                bottomMargin = dp(12)
            }
        )
    }

    // =========================================================
    // INDEX MONITOR — VISUAL PLACEHOLDER ONLY
    // =========================================================

    private fun addIndexMonitor() {
        val titleRow = horizontalRow().apply {
            gravity = Gravity.CENTER_VERTICAL

            addView(
                sectionTitle("INDEX MONITOR"),
                LinearLayout.LayoutParams(0, -2, 1f)
            )

            addView(
                label("⌃", 24f, white, true)
            )
        }

        page.addView(titleRow)

        val indexes = listOf(
            Triple("R_10", cyan, "⌁ 〰⌁"),
            Triple("R_25", green, "⌁〰⌁"),
            Triple("R_50", yellow, "〰⌁〰"),
            Triple("R_75", red, "⌁〰⌁"),
            Triple(
                "R_100",
                Color.rgb(190, 75, 255),
                "⌁〰⌁"
            )
        )

        indexes.forEach { (name, tint, spark) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                setPadding(
                    dp(12),
                    dp(8),
                    dp(10),
                    dp(8)
                )

                background = shape(panel, tint, 12, 1)

                setOnClickListener {
                    toast("$name details will be added later.")
                }
            }

            row.addView(
                label(name, 20f, tint, true),
                LinearLayout.LayoutParams(dp(76), -2)
            )

            row.addView(
                label(spark, 17f, tint, false),
                LinearLayout.LayoutParams(0, -2, 1f)
            )

            val indexState = marketData[name]

val statusText = when {
    indexState == null -> "WAITING"
    indexState.status == "ERROR" -> "ERROR"
    indexState.candles.isNotEmpty() -> "READY"
    indexState.price != null -> "LOADING"
    else -> "WAITING"
}

val statusColor = when (statusText) {
    "READY" -> green
    "ERROR" -> red
    else -> yellow
}

row.addView(
    label(statusText, 12f, statusColor, true),
    LinearLayout.LayoutParams(dp(76), -2)
)

            row.addView(
                label("›", 26f, white, false)
            )

            page.addView(
                row,
                matchWrap().apply {
                    bottomMargin = dp(5)
                }
            )
        }
    }

    // =========================================================
    // ADMIN-ONLY ANALYSIS — LIVE MARKET DATA
    // =========================================================

    private fun addAdminOnlyNotice() {
        page.addView(sectionTitle("ANALYSIS"))
        val notice = card()
        notice.addView(label("ADMIN ACCESS ONLY", 16f, yellow, true))
        notice.addView(label("Market data and historical candles are available only in the Admin platform.", 13f, muted, false))
        page.addView(notice, matchWrap())
    }

    private fun addAdminMarketData() {

    val marketSectionContent = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }
        val intro = card()
        intro.addView(label("NEW STRUCTURE ENGINE", 17f, green, true))
        intro.addView(label("Live Deriv prices and completed H1 historical candles for all five synthetic indexes.", 13f, muted, false))
        intro.addView(label("H1 timeframe • 1,000 candles per index", 12f, white, true))
        intro.addView(button("⟳  REFRESH MARKET DATA", true) {
            marketSymbols.forEach { symbol ->
                marketData[symbol]?.apply {
                    status = "LOADING"
                    message = "Refreshing live price and H1 candles..."
                }
                updateMarketDataCard(symbol)
            }
            marketDataStarted = true
            derivApiClient.refreshHistoricalCandles()
            toast("Market data refresh requested.")
        }, matchWrap().apply { topMargin = dp(10) })
        marketSectionContent.addView(
    intro,
    matchWrap().apply { bottomMargin = dp(10) }
)

        marketDataViews.clear()
        marketSymbols.forEach { symbol ->
            val state = marketData[symbol] ?: MarketIndexState()
            val indexCard = card()
            val top = horizontalRow()
            top.addView(label("Index ${symbol.removePrefix("R_")}", 16f, green, true), LinearLayout.LayoutParams(0, -2, 1f))
            val statusView = label(state.status, 11f, if (state.status == "READY") green else yellow, true)
            top.addView(statusView)
            indexCard.addView(top)

            val stats = horizontalRow().apply { gravity = Gravity.TOP }
            val priceView = label("CURRENT PRICE\n${formatMarketPrice(state.price)}", 12f, white, true)
            val candlesView = label("HISTORICAL CANDLES\n${state.candles.size}", 12f, white, true)
            val timeView = label("LAST CANDLE\n${formatMarketTime(state.lastCandleEpoch)}", 12f, white, true)
            listOf(priceView, candlesView, timeView).forEachIndexed { index, view ->
                view.setPadding(dp(7), dp(9), dp(5), dp(9))
                view.background = shape(Color.rgb(5, 16, 10), Color.rgb(0, 65, 32), 9, 1)
                stats.addView(view, LinearLayout.LayoutParams(0, dp(82), 1f).apply {
                    if (index > 0) leftMargin = dp(5)
                })
            }
            indexCard.addView(stats, matchWrap().apply { topMargin = dp(8) })
            val messageView = label(state.message, 11f, muted, false)
            indexCard.addView(messageView, matchWrap().apply { topMargin = dp(7) })
            marketDataViews[symbol] = mapOf("status" to statusView, "price" to priceView, "candles" to candlesView, "time" to timeView, "message" to messageView)
            marketSectionContent.addView(
    indexCard,
    matchWrap().apply { bottomMargin = dp(8) }
)
        }
        if (!marketDataStarted) {
            marketDataStarted = true
            derivApiClient.requestHistoricalCandles()
        }
        marketSymbols.forEach { updateMarketDataCard(it) }
        marketSectionContent.addView(
    label(
        "Source: Deriv public market-data WebSocket • H1 candles • 1,000 candles per index",
        10f,
        muted,
        false
    )
)

page.addView(
    addCollapsibleSection(
        "ANALYSIS — MARKET DATA",
        marketSectionContent,
        false
    ),
    matchWrap()
)
        addAdminMarketStructure()   
    }
    private fun updateMarketDataCard(symbol: String) {
        val state = marketData[symbol] ?: return
        val views = marketDataViews[symbol] ?: return
        views["status"]?.apply {
            text = state.status
            setTextColor(if (state.status == "READY") green else if (state.status == "ERROR") red else yellow)
        }
        views["price"]?.text = "CURRENT PRICE\n${formatMarketPrice(state.price)}"
        views["candles"]?.text = "HISTORICAL CANDLES\n${state.candles.size}"
        views["time"]?.text = "LAST CANDLE\n${formatMarketTime(state.lastCandleEpoch)}"
        views["message"]?.text = state.message
    }
    // =========================================================
    // MARKET STRUCTURE ANALYSIS — BOS / CHOCH
    // =========================================================
        // =========================================================
    // CONFIRMED SWING PIVOT DETECTOR
    // =========================================================

    private data class StructurePivot(
        val index: Int,
        val price: Double,
        val isHigh: Boolean
    )

    private fun detectStructurePivots(
        candles: List<DerivApiClient.Candle>,
        leftBars: Int = 2,
        rightBars: Int = 2
    ): List<StructurePivot> {

        if (candles.size < leftBars + rightBars + 1) {
            return emptyList()
        }

        val pivots = mutableListOf<StructurePivot>()

        for (i in leftBars until candles.size - rightBars) {

            val candidate = candles[i]

            val leftCandles = candles.subList(
                i - leftBars,
                i
            )

            val rightCandles = candles.subList(
                i + 1,
                i + rightBars + 1
            )

            val isSwingHigh =
                leftCandles.all { candidate.high > it.high } &&
                rightCandles.all { candidate.high > it.high }

            val isSwingLow =
                leftCandles.all { candidate.low < it.low } &&
                rightCandles.all { candidate.low < it.low }

            if (isSwingHigh) {
                pivots.add(
                    StructurePivot(
                        index = i,
                        price = candidate.high,
                        isHigh = true
                    )
                )
            }

            if (isSwingLow) {
                pivots.add(
                    StructurePivot(
                        index = i,
                        price = candidate.low,
                        isHigh = false
                    )
                )
            }
        }

        return pivots.sortedBy { it.index }
    }

    private fun addAdminMarketStructure() {
        val structureContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        structureContent.addView(
            label(
                "H1 MARKET STRUCTURE",
                16f,
                green,
                true
            )
        )

        structureContent.addView(
            label(
                "Swing structure, Break of Structure (BOS), " +
                    "Change of Character (CHOCH), and trend direction.",
                12f,
                muted,
                false
            )
        )

        marketSymbols.forEach { symbol ->
            val state = marketData[symbol]
            val candles = state?.candles.orEmpty()

            val indexCard = card()

            indexCard.addView(
                label(
                    "INDEX ${symbol.removePrefix("R_")}",
                    15f,
                    green,
                    true
                )
            )

            if (candles.size < 10) {
                indexCard.addView(
                    label(
                        "WAITING — Insufficient historical candles.",
                        12f,
                        yellow,
                        false
                    )
                )
            } else {
                val recent = candles.takeLast(30)
val latest = recent.last()
val previous = recent[recent.lastIndex - 1]

val pivots = detectStructurePivots(recent.dropLast(1))

val swingHigh = pivots
    .lastOrNull { it.isHigh }
    ?.price ?: recent.dropLast(1).maxOf { it.high }

val swingLow = pivots
    .lastOrNull { !it.isHigh }
    ?.price ?: recent.dropLast(1).minOf { it.low }
                val highPivots = pivots.filter { it.isHigh }
                val lowPivots = pivots.filter { !it.isHigh }

                val lastTwoHighs = highPivots.takeLast(2)
                val lastTwoLows = lowPivots.takeLast(2)

                val highStructure = if (lastTwoHighs.size == 2) {
                    if (lastTwoHighs[1].price > lastTwoHighs[0].price) {
                        "HH — Higher High"
                    } else if (lastTwoHighs[1].price < lastTwoHighs[0].price) {
                        "LH — Lower High"
                    } else {
                        "EQH — Equal High"
                    }
                } else {
                    "WAITING — Need 2 swing highs"
                }

                val lowStructure = if (lastTwoLows.size == 2) {
                    if (lastTwoLows[1].price > lastTwoLows[0].price) {
                        "HL — Higher Low"
                    } else if (lastTwoLows[1].price < lastTwoLows[0].price) {
                        "LL — Lower Low"
                    } else {
                        "EQL — Equal Low"
                    }
                } else {
                    "WAITING — Need 2 swing lows"
                }
                val trend = when {
                    latest.close > previous.high -> "BULLISH"
                    latest.close < previous.low -> "BEARISH"
                    latest.close > latest.open -> "BULLISH CANDLE"
                    latest.close < latest.open -> "BEARISH CANDLE"
                    else -> "NEUTRAL"
                }

                                val previousTrend = when {
                    lastTwoHighs.size >= 2 &&
                        lastTwoLows.size >= 2 &&
                        lastTwoHighs[1].price > lastTwoHighs[0].price &&
                        lastTwoLows[1].price > lastTwoLows[0].price -> "BULLISH"

                    lastTwoHighs.size >= 2 &&
                        lastTwoLows.size >= 2 &&
                        lastTwoHighs[1].price < lastTwoHighs[0].price &&
                        lastTwoLows[1].price < lastTwoLows[0].price -> "BEARISH"

                    else -> "NEUTRAL"
                }

                val bos = when {
                    latest.close > swingHigh -> "BULLISH BOS"
                    latest.close < swingLow -> "BEARISH BOS"
                    else -> "NO CONFIRMED BOS"
                }

                val choch = when {
                    previousTrend == "BEARISH" &&
                        latest.close > swingHigh ->
                        "BULLISH CHOCH / MSS"

                    previousTrend == "BULLISH" &&
                        latest.close < swingLow ->
                        "BEARISH CHOCH / MSS"

                    else -> "NO CONFIRMED CHOCH / MSS"
                }

                indexCard.addView(
                    label(
                        "TREND: $trend",
                        13f,
                        if (trend.startsWith("BULLISH")) green
                        else if (trend.startsWith("BEARISH")) red
                        else yellow,
                        true
                    )
                )

                indexCard.addView(
                    label(
                        "STRUCTURE: $bos",
                        12f,
                        white,
                        true
                    )
                )

                indexCard.addView(
                    label(
                        "CHARACTER: $choch",
                        12f,
                        white,
                        false
                    )
                )
                                indexCard.addView(
                    label(
                        "SWING HIGH STRUCTURE: $highStructure",
                        12f,
                        if (highStructure.startsWith("HH")) green
                        else if (highStructure.startsWith("LH")) red
                        else yellow,
                        true
                    )
                )

                indexCard.addView(
                    label(
                        "SWING LOW STRUCTURE: $lowStructure",
                        12f,
                        if (lowStructure.startsWith("HL")) green
                        else if (lowStructure.startsWith("LL")) red
                        else yellow,
                        true
                    )
                )

                indexCard.addView(
                    label(
                        "SWING HIGH: ${formatMarketPrice(swingHigh)}",
                        12f,
                        green,
                        false
                    )
                )

                indexCard.addView(
                    label(
                        "SWING LOW: ${formatMarketPrice(swingLow)}",
                        12f,
                        red,
                        false
                    )
                )

                indexCard.addView(
                    label(
                        "LAST CLOSE: ${formatMarketPrice(latest.close)}",
                        12f,
                        white,
                        false
                    )
                )
            }

            structureContent.addView(
                indexCard,
                matchWrap().apply {
                    bottomMargin = dp(8)
                    topMargin = dp(5)
                }
            )
        }

        structureContent.addView(
            label(
                "Note: This is preliminary rule-based analysis. " +
                    "BOS and CHOCH require validated swing-point detection " +
                    "before being used as trading signals.",
                11f,
                yellow,
                false
            )
        )

        page.addView(
            addCollapsibleSection(
                "ANALYSIS — MARKET STRUCTURE",
                structureContent,
                false
            ),
            matchWrap()
        )
    }
    
    private fun formatMarketPrice(price: Double?): String =
        price?.let { String.format(java.util.Locale.US, "%.3f", it) } ?: "—"

    private fun formatMarketTime(epoch: Long): String {
        if (epoch <= 0L) return "—"
        return java.text.SimpleDateFormat("yyyy/MM/dd, HH:mm:ss", java.util.Locale("en", "ZA"))
            .format(java.util.Date(epoch * 1000L))
    }

    // =========================================================
    // ADMIN PREVIEW
    // =========================================================

    private fun addAdminPreview() {
        page.addView(sectionTitle("ADMINISTRATION"))

        val card = card()

        card.orientation = LinearLayout.VERTICAL

        listOf(
            "Dashboard",
            "Index Settings",
            "Risk Categories",
            "User Accounts",
            "Notifications",
            "Trading Logs",
            "System Settings"
        ).forEach { item ->
            card.addView(
                button("⚙  $item", false) {
                    toast("$item screen will be built separately.")
                },
                matchWrap().apply {
                    bottomMargin = dp(5)
                }
            )
        }

        page.addView(
            card,
            matchWrap().apply {
                topMargin = dp(4)
            }
        )
    }

    // =========================================================
    // BOTTOM NAVIGATION
    // =========================================================

    private fun addBottomNavigation(outer: LinearLayout) {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER

            setPadding(
                dp(3),
                dp(7),
                dp(3),
                dp(5)
            )

            background = shape(
                Color.rgb(4, 12, 10),
                greenDark,
                12,
                1
            )
        }

        val tabs = listOf(
            "⌂" to "Home",
            "▥" to "Trade",
            "⌁" to "Analysis",
            "▤" to "News",
            "⚙" to "Settings"
        )

        tabs.forEach { (icon, name) ->
            val selected = selectedTab == name

            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER

                setPadding(
                    dp(2),
                    dp(2),
                    dp(2),
                    dp(2)
                )

                if (selected) {
                    background = shape(
                        Color.rgb(0, 35, 17),
                        greenDark,
                        10,
                        1
                    )
                }

                setOnClickListener {
                    selectedTab = name
                    drawerOpen = false
                    render()
                }
            }

            item.addView(
                label(
                    icon,
                    24f,
                    if (selected) green else white,
                    true
                )
            )

            item.addView(
                label(
                    name,
                    11f,
                    if (selected) green else white,
                    selected
                )
            )

            nav.addView(
                item,
                LinearLayout.LayoutParams(0, dp(55), 1f)
            )
        }

        outer.addView(
            nav,
            LinearLayout.LayoutParams(-1, dp(65))
        )
    }

    // =========================================================
    // UI HELPERS
    // =========================================================

    private fun sectionTitle(text: String): TextView {
        return label(
            text,
            14f,
            white,
            true
        ).apply {
            setPadding(
                dp(2),
                dp(10),
                0,
                dp(6)
            )
        }
    }

    private fun label(
        text: String,
        size: Float,
        color: Int,
        bold: Boolean
    ): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)

            if (bold) {
                typeface = Typeface.create(
                    "sans-serif",
                    Typeface.BOLD
                )
            }

            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = true
        }
    }

    private fun button(
        text: String,
        active: Boolean,
        action: (() -> Unit)? = null
    ): Button {
        val b = Button(this).apply {
            this.text = text
            textSize = 13f
            isAllCaps = false

            typeface = Typeface.create(
                "sans-serif",
                Typeface.BOLD
            )

            setTextColor(
                if (active) {
                    Color.rgb(0, 25, 12)
                } else {
                    white
                }
            )

            background = shape(
                if (active) green else panel2,
                if (active) green else Color.rgb(40, 68, 72),
                12,
                1
            )

            minHeight = dp(48)
            minimumHeight = dp(48)
            stateListAnimator = null

            setPadding(
                dp(7),
                dp(4),
                dp(7),
                dp(4)
            )
        }

        if (action != null) {
            b.setOnClickListener {
                action()
            }
        }

        return b
    }

    private fun card(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            setPadding(
                dp(10),
                dp(10),
                dp(10),
                dp(10)
            )

            background = shape(
                panel,
                Color.rgb(0, 100, 48),
                14,
                1
            )
        }
    }

    private fun horizontalRow(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
                    }
    }

    private fun shape(
        color: Int,
        stroke: Int,
        radius: Int,
        strokeDp: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(strokeDp), stroke)
        }
    }

    private fun matchWrap(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(-1, -2)
    }

    private fun weighted(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(0, dp(52), 1f)
    }

    private fun space(width: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(width, 1)
        }
    }

    private fun dp(value: Int): Int {
        return (
            value * resources.displayMetrics.density
        ).toInt()
    }

    private fun toast(message: String) {
        Toast.makeText(
            this,
            message,
            Toast.LENGTH_SHORT
        ).show()
    }

    // =========================================================
    // DERIV OAUTH CONNECTION (existing OAuth + PKCE flow)
    // =========================================================
    private fun beginDerivConnection() {
        val key = if (executionMode == "REAL") "real_token" else "demo_token"
        val token = prefs.getString(key, "").orEmpty()
        if (token.isNotBlank()) connectWithToken(token) else startDerivOAuth()
    }

    private fun createCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    private fun createCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(
            digest,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    private fun startDerivOAuth() {
        val state = "android-" + UUID.randomUUID().toString()
        val verifier = createCodeVerifier()
        val challenge = createCodeChallenge(verifier)
        prefs.edit().putString("oauth_state", state).putString("code_verifier", verifier)
            .putString("oauth_mode", executionMode).apply()
        val url = Uri.parse(DerivApiClient.OAUTH_AUTH_URL).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", DerivApiClient.clientId(executionMode))
            .appendQueryParameter("redirect_uri", DerivApiClient.OAUTH_REDIRECT_URI)
            .appendQueryParameter("scope", DerivApiClient.OAUTH_SCOPE)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256").build()
        try { startActivity(Intent(Intent.ACTION_VIEW, url)) }
        catch (e: Exception) { toast("Unable to open Deriv login: ${e.message}") }
    }

    private fun handleOAuthCallback(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "volatilitiesindicator" || data.host != "oauth-callback") return
        val error = data.getQueryParameter("error")
        if (!error.isNullOrBlank()) { toast("Deriv OAuth error: $error"); return }
        val code = data.getQueryParameter("code")
        val returnedState = data.getQueryParameter("state")
        val savedState = prefs.getString("oauth_state", "").orEmpty()
        if (code.isNullOrBlank()) { toast("No Deriv authorization code received."); return }
        if (savedState.isBlank() || returnedState != savedState) { toast("OAuth security check failed."); return }
        executionMode = if (prefs.getString("oauth_mode", "DEMO").equals("REAL", true)) "REAL" else "DEMO"
        render()
        exchangeOAuthCode(code)
    }

    private fun exchangeOAuthCode(code: String) {
        val verifier = prefs.getString("code_verifier", "").orEmpty()
        if (verifier.isBlank()) { toast("OAuth verifier is missing."); return }
        val body = FormBody.Builder().add("grant_type", "authorization_code").add("code", code)
            .add("client_id", DerivApiClient.clientId(executionMode))
            .add("redirect_uri", DerivApiClient.OAUTH_REDIRECT_URI)
            .add("code_verifier", verifier).build()
        val request = Request.Builder().url(DerivApiClient.OAUTH_TOKEN_URL).post(body).build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { toast("OAuth connection failed: ${e.message ?: "network error"}") }
            }
            override fun onResponse(call: Call, response: Response) {
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    runOnUiThread { toast("OAuth failed (HTTP ${response.code}): ${oauthError(text)}") }
                    return
                }
                try {
                    val token = JSONObject(text).optString("access_token", "")
                    if (token.isBlank()) { runOnUiThread { toast("Deriv did not return an access token.") }; return }
                    val key = if (executionMode == "REAL") "real_token" else "demo_token"
                    prefs.edit().putString(key, token).remove("oauth_state").remove("code_verifier").apply()
                    runOnUiThread { connectWithToken(token) }
                } catch (e: Exception) {
                    runOnUiThread { toast("Invalid Deriv OAuth response: ${e.message}") }
                }
            }
        })
    }

    private fun oauthError(body: String): String = try {
        val j = JSONObject(body); val err = j.optJSONObject("error")
        err?.optString("message")?.takeIf { !it.isNullOrBlank() } ?: j.optString("message", body.take(180))
    } catch (_: Exception) { body.take(180) }

    private fun connectWithToken(token: String) {
        if (token.isBlank()) { toast("Deriv access token is empty."); return }
        try { derivApiClient.disconnect() } catch (_: Exception) {}
        connected = false; accountId = ""; accountType = ""; accountBalance = 0.0; accountCurrency = ""
        render()
        derivApiClient.connectWithAccessToken(token, executionMode)
    }

    private fun disconnectDeriv() {
        try { derivApiClient.disconnect() } catch (_: Exception) {}
        connected = false; accountId = ""; accountType = ""; accountBalance = 0.0; accountCurrency = ""
        accountStatus.text = "●  NOT CONNECTED"; accountStatus.setTextColor(red)
        accountIdView.text = "Account:  —"; balanceView.text = "Balance:  —"
        toast("Deriv disconnected.")
    }

    override fun onConnecting(message: String) {
        runOnUiThread { accountStatus.text = "●  CONNECTING TO DERIV"; accountStatus.setTextColor(yellow) }
    }
    override fun onConnected(accountId: String, accountType: String) {
        runOnUiThread {
            this.accountId = accountId; this.accountType = accountType; connected = true
            accountStatus.text = "●  CONNECTED — $executionMode"; accountStatus.setTextColor(green)
            accountIdView.text = "Account:  $accountId"

            toast("Deriv $executionMode connected.")
        }
    }
    override fun onBalance(balance: Double, currency: String) {
        runOnUiThread {
            accountBalance = balance; accountCurrency = currency; connected = true
            balanceView.text = "Balance:  $currency ${String.format(java.util.Locale.US, "%.2f", balance)}"
            accountStatus.text = "●  CONNECTED — $executionMode"; accountStatus.setTextColor(green)
        }
    }
    override fun onTick(symbol: String, quote: Double, epoch: Long) {
        if (symbol !in marketSymbols || !quote.isFinite()) return
        runOnUiThread {
            val state = marketData[symbol] ?: return@runOnUiThread
            state.price = quote
            if (state.candles.isNotEmpty()) {
                state.status = "READY"
                state.message = "LIVE — price updating from Deriv."
            } else {
                state.status = "LOADING"
                state.message = "Live price received; loading H1 historical candles..."
            }
            updateMarketDataCard(symbol)
        }
    }

    override fun onCandles(symbol: String, candles: List<DerivApiClient.Candle>) {
        if (symbol !in marketSymbols) return
        runOnUiThread {
            val state = marketData[symbol] ?: return@runOnUiThread
            state.candles = candles.sortedBy { it.epoch }
            state.lastCandleEpoch = state.candles.lastOrNull()?.epoch ?: 0L
            state.status = if (state.candles.isNotEmpty()) "READY" else "WAITING"
            state.message = if (state.candles.isNotEmpty()) {
                "LIVE — price updating from Deriv."
            } else {
                "Waiting for historical candles from Deriv..."
            }
            updateMarketDataCard(symbol)
        }
    }

    override fun onMarketDataError(symbol: String, message: String) {
        if (symbol !in marketSymbols) return
        runOnUiThread {
            val state = marketData[symbol] ?: return@runOnUiThread
            state.status = "ERROR"
            state.message = message
            updateMarketDataCard(symbol)
        }
    }

    override fun onError(message: String) {
        runOnUiThread {
            accountStatus.text = "●  CONNECTION ERROR"
            accountStatus.setTextColor(red)
            marketSymbols.forEach { symbol ->
                val state = marketData[symbol] ?: return@forEach
                if (state.candles.isEmpty() && state.price == null) {
                    state.status = "ERROR"
                    state.message = message
                    updateMarketDataCard(symbol)
                }
            }
            toast("Deriv: $message")
        }
    }
    override fun onDisconnected() {
        runOnUiThread { connected = false; accountStatus.text = "●  NOT CONNECTED"; accountStatus.setTextColor(red) }
    }

    override fun onDestroy() {
        try { derivApiClient.disconnect() } catch (_: Exception) {}
        super.onDestroy()
    }
    // =========================================================
// COLLAPSIBLE SECTION HELPER — ADDITIVE FEATURE
// =========================================================

private fun addCollapsibleSection(
    title: String,
    content: View,
    initiallyExpanded: Boolean = false
): LinearLayout {

    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(panel, greenDark, 14, 1)
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }

    val header = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(10), dp(8), dp(10))
        isClickable = true
        isFocusable = true
    }

    val titleView = label(title, 16f, green, true)

    header.addView(
        titleView,
        LinearLayout.LayoutParams(0, -2, 1f)
    )

    val arrow = label(
        if (initiallyExpanded) "▼" else "▶",
        16f,
        green,
        true
    )

    header.addView(arrow)

    val contentContainer = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = if (initiallyExpanded) View.VISIBLE else View.GONE
    }

    contentContainer.addView(content)

    header.setOnClickListener {
        val expand = contentContainer.visibility != View.VISIBLE

        contentContainer.visibility =
            if (expand) View.VISIBLE else View.GONE

        arrow.text = if (expand) "▼" else "▶"
    }

    container.addView(header)

    container.addView(
        contentContainer,
        matchWrap().apply {
            topMargin = dp(8)
        }
    )

    return container
}
                            }

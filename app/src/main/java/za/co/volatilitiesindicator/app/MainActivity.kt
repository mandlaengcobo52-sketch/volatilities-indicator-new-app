package za.co.volatilitiesindicator.app

import android.app.Activity
import android.os.Build
import android.content.Intent
import android.net.Uri
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Bundle
import android.os.Handler
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.Toast
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan

/** Volatilities Indicator - CLEAN v2: Design, Connectivity, Execution, Indexes ONLY. */
class MainActivity : Activity(), DerivApiClient.Callback {

    // 1. DESIGN - Original palette preserved
    private val bg = Color.rgb(3, 8, 6)
    private val panel = Color.rgb(8, 20, 18)
    private val panel2 = Color.rgb(16, 34, 39)
    private val green = Color.rgb(0, 255, 92)
    private val greenDark = Color.rgb(0, 120, 48)
    private val white = Color.rgb(255, 220, 45)
    private val muted = Color.rgb(242, 248, 245)
    private val yellow = Color.rgb(255, 220, 45)
    private val red = Color.rgb(255, 65, 75)
    private val cyan = Color.rgb(0, 225, 235)
    private val blue = Color.rgb(70, 155, 255)

    private val appPrefs by lazy {
        getSharedPreferences("volatilities_app_settings", MODE_PRIVATE)
    }

    private var executionMode = "DEMO"
        set(value) {
            field = value
            appPrefs.edit().putString("executionMode", value).apply()
        }

    private lateinit var page: LinearLayout
    private var mainScrollView: ScrollView? = null
    private lateinit var accountStatus: TextView
    private lateinit var accountIdView: TextView
    private lateinit var balanceView: TextView
    private lateinit var openPlView: TextView
    private val liveContractProfits = mutableMapOf<Long, Double>()
    private val liveContractProfitModes = mutableMapOf<Long, String>()
    private var runningTotalPlView: TextView? = null
}

    // 2. CONNECTIVITY
    private var connected = false
    private val reconnectHandler by lazy { Handler(mainLooper) }
    private var reconnectAttempt = 0
    private var reconnectScheduled = false
    private var reconnectRunnable: Runnable? = null
    private fun scheduleDerivReconnect() {
        if (appPrefs.getBoolean("deriv_manually_disconnected", false)) return
        if (connected || reconnectScheduled) return
        val tokenKey = if (executionMode == "REAL") "real_token" else "demo_token"
        val savedToken = prefs.getString(tokenKey, "").orEmpty()
        if (savedToken.isBlank()) return
        reconnectScheduled = true
        val delayMs = minOf(30_000L, 2_000L * (1L shl minOf(reconnectAttempt, 4)))
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(4)
        reconnectRunnable = Runnable {
            reconnectScheduled = false
            if (!connected && !appPrefs.getBoolean("deriv_manually_disconnected", false)) connectWithToken(savedToken)
        }
        reconnectHandler.postDelayed(reconnectRunnable!!, delayMs)
    }
    private fun cancelDerivReconnect() {
        reconnectRunnable?.let { reconnectHandler.removeCallbacks(it) }
        reconnectRunnable = null
        reconnectScheduled = false
        reconnectAttempt = 0
    }
    private var hasResumedOnce = false
    private var accountId = ""
    private var accountType = ""
    private var accountBalance = 0.0
    private var accountCurrency = ""
    private lateinit var derivApiClient: DerivApiClient
    private var derivService: DerivConnectionService? = null
    private var derivServiceBound = false
    private var pendingDerivToken: String? = null
    private var pendingResumeCheck = false

    private val derivServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(
            name: ComponentName?,
            binder: IBinder?
        ) {
            val service =
                (binder as? DerivConnectionService.LocalBinder)?.getService()
                    ?: return
            derivService = service
            derivApiClient = service.client
            service.attachActivity(this@MainActivity)
            derivServiceBound = true
            val token = pendingDerivToken
            pendingDerivToken = null
            if (!token.isNullOrBlank()) {
                derivApiClient.ensureConnectedOnResume(token, executionMode)
            } else if (
                pendingResumeCheck &&
                !appPrefs.getBoolean("deriv_manually_disconnected", false)
            ) {
                val key =
                    if (executionMode == "REAL") "real_token"
                    else "demo_token"
                val saved = prefs.getString(key, "").orEmpty()
                if (saved.isNotBlank()) {
                    derivApiClient.ensureConnectedOnResume(saved, executionMode)
                }
            }
            pendingResumeCheck = false
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            derivService?.detachActivity(this@MainActivity)
            derivService = null
            derivServiceBound = false
        }
    }

    private fun startAndBindDerivService() {
        if (derivServiceBound) return
        val serviceIntent =
            Intent(this, DerivConnectionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        derivServiceBound = bindService(
            serviceIntent,
            derivServiceConnection,
            BIND_AUTO_CREATE
        )
    }
    private val httpClient = OkHttpClient()
    private val prefs by lazy { getSharedPreferences("deriv_oauth", MODE_PRIVATE) }
    private var derivToken: String = ""
    private var openTradesResponse: JSONObject? = null
    private var portfolioLoadedForAccount: Boolean = false
    private var lastOpenTradesRequestAt: Long = 0L
    private var openContractResponse: JSONObject? = null
    private var tradeHistoryResponse: JSONObject? = null

    // 3. EXECUTION - Running-trade state (NO trailing stop, NO notification)
    private data class RunningContract(
        val contractId: Long,
        val symbol: String,
        val direction: String,
        val stake: Double,
        val target: Double,
        val targetProbability: Double,
        val mode: String,
        var entryBarrier: Double = 0.0,
        var openContractConfirmed: Boolean = false,
        var closeRequested: Boolean = false,
        var closeReason: String = "",
        var pairId: String = "",
        var pairSide: String = "",
        var potentialPayout: Double = 0.0
    )

    private data class ContractLiveViews(
        val stake: Double,
        val symbol: String,
        val target: Double?,
        val probability: Double,
        val pnl: TextView,
        val spot: TextView,
        val exitBarrier: TextView,
        val expiry: TextView,
        val ptpe: TextView
    )

    private val contractLiveViews = mutableMapOf<Long, ContractLiveViews>()
    private val runningContracts = mutableMapOf<Long, RunningContract>()
    private data class PendingTouchOrder(
        val symbol: String,
        val direction: String,
        val stake: Double,
        val target: Double,
        val mode: String,
        var proposalRequestId: Int,
        var proposalId: String = "",
        var buyRequestId: Int = 0,
        var barrierOffset: Double = 0.0,
        var proposalAttempts: Int = 0,
        var lowerBarrierOffset: Double = 0.0,
        var upperBarrierOffset: Double = 0.0,
        var potentialPayout: Double = 0.0,
        var sweepTime: Long = 0L
    )

    private val pendingTouchOrders = mutableMapOf<Int, PendingTouchOrder>()
    private val pendingTouchBySymbol = mutableMapOf<String, Int>()
    private val touchExecutionLocks = mutableSetOf<String>()
    private val consumedTouchQualifications = mutableMapOf<String, Long>()
    private var nextTradeRequestIdValue = 710000
    private fun nextTradeRequestId(): Int = ++nextTradeRequestIdValue
    private val dailyCapitalTimeZone = java.util.TimeZone.getTimeZone("Africa/Johannesburg")
    private fun saDayKey(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = dailyCapitalTimeZone }.format(java.util.Date())
    private fun dailyCycleCapital(): Double {
        val key = "daily_capital_${accountId}_$executionMode"
        val timeKey = "${key}_start_time"
        val now = System.currentTimeMillis()
        val savedCapital = appPrefs.getFloat(key, 0f).toDouble()
        val cycleStart = appPrefs.getLong(timeKey, 0L)
        if (
            savedCapital <= 0.0 ||
            cycleStart <= 0L ||
            now - cycleStart >= 86400000L
        ) {
            if (accountBalance <= 0.0) return 0.0
            appPrefs.edit()
                .putFloat(key, accountBalance.toFloat())
                .putLong(timeKey, now)
                .apply()
            return accountBalance
        }
        return savedCapital
    }
        private fun dailyTradeStake(): Double {
        return dailyCycleCapital() * 0.33
    }

    private var autoTradeEnabled: Boolean
        get() = appPrefs.getBoolean("autoTradeEnabled", false)
        set(value) { appPrefs.edit().putBoolean("autoTradeEnabled", value).apply() }

    private val touchAvailabilityPending = mutableSetOf<String>()
    private val touchAvailabilityRequests = mutableMapOf<Int, String>()
    private val touchContractsBySymbol =
        mutableMapOf<String, org.json.JSONArray>()
    private val tradeGateLastLogAt = mutableMapOf<String, Long>()

    // 3. EXECUTION - 24H ONETOUCH, 30% profit target, ATR barrier
    fun maxBarrierForSymbol(symbol: String): Double {
        return when (symbol) {
            "R_10" -> 180.0
            "R_25" -> 675.0
            "R_50" -> 1200.0
            "R_75" -> 1800.0
            "R_100" -> 3300.0
            else -> 1800.0
        }
    }
    fun startBarrierForSymbol(symbol: String): Double {
        return when (symbol) {
            "R_10" -> 120.0
            "R_25" -> 450.0
            "R_50" -> 800.0
            "R_75" -> 1200.0
            "R_100" -> 2200.0
            else -> 1200.0
        }
    }
    private fun submitTouchProposal(
        symbol: String,
        direction: String,
        barrier: Double,
        sweepTime: Long = 0L,
        manual: Boolean = false
    ): Boolean {
        if (!connected || (!manual && !autoTradeEnabled)) return false
        if (direction != "BUY" && direction != "SELL") return false
        if (!touchExecutionLocks.add(symbol)) return false
        val stake = kotlin.math.round(dailyTradeStake() * 100.0) / 100.0
        if (stake <= 0.0 || accountCurrency.isBlank()) {
            touchExecutionLocks.remove(symbol)
            return false
        }
        val requestId = nextTradeRequestId()
        val order = PendingTouchOrder(
            symbol = symbol,
            direction = direction,
            stake = stake,
            target = stake * 0.30,
            mode = executionMode,
            proposalRequestId = requestId,
            barrierOffset = kotlin.math.abs(barrier),
            sweepTime = sweepTime
        )
        pendingTouchOrders[requestId] = order
        pendingTouchBySymbol[symbol] = requestId
        val barrierValue = String.format(
            java.util.Locale.US,
            "%.2f",
            kotlin.math.abs(barrier)
        )
        val signedBarrier = if (direction == "BUY") {
            "+$barrierValue"
        } else {
            "-$barrierValue"
        }
        val sent = derivApiClient.send(
            JSONObject()
                .put("proposal", 1)
                .put("amount", stake)
                .put("basis", "stake")
                .put("contract_type", "ONETOUCH")
                .put("currency", accountCurrency)
                .put("duration", 24)
                .put("duration_unit", "h")
                .put("underlying_symbol", symbol)
                .put("subscribe", 1)
                .put("barrier", signedBarrier)
                .put("req_id", requestId)
        )
        if (!sent) {
            pendingTouchOrders.remove(requestId)
            pendingTouchBySymbol.remove(symbol)
            touchExecutionLocks.remove(symbol)
        }
        return sent
    }
    private fun requestTouchAvailability(symbol: String) {
        if (!connected) return
        if (!touchAvailabilityPending.add(symbol)) return
        val requestId = nextTradeRequestId()
        touchAvailabilityRequests[requestId] = symbol
        val sent = derivApiClient.send(
            JSONObject()
                .put("contracts_for", symbol)
                .put("req_id", requestId)
        )
        if (!sent) {
            touchAvailabilityPending.remove(symbol)
            touchAvailabilityRequests.remove(requestId)
            android.util.Log.e(
                "TouchAvailability",
                "$symbol: contracts_for request could not be sent."
            )
        }
    }

    private fun normalizedSymbol(item: JSONObject): String = item.optString(
        "underlying", item.optString("underlying_symbol", item.optString("symbol", ""))
    ).uppercase()
    private fun isOneDayTouchAvailable(symbol: String): Boolean {
        val contracts = touchContractsBySymbol[symbol] ?: return false
        for (i in 0 until contracts.length()) {
            val contract = contracts.optJSONObject(i) ?: continue
            if (contract.optString("contract_type") != "ONETOUCH") continue
            val minDuration = contract.optString("min_contract_duration")
            val maxDuration = contract.optString("max_contract_duration")
            if (minDuration.isBlank() || maxDuration.isBlank()) continue
            val minSeconds = durationToSeconds(minDuration)
            val maxSeconds = durationToSeconds(maxDuration)
            if (minSeconds > 0L && minSeconds <= 86400L &&
                maxSeconds >= 86400L
            ) {
                return true
            }
        }
        return false
    }

    private fun durationToSeconds(value: String): Long {
        val number = value.dropLast(1).toLongOrNull() ?: return -1L
        return when (value.last().lowercaseChar()) {
            's' -> number
            'm' -> number * 60L
            'h' -> number * 3600L
            'd' -> number * 86400L
            'y' -> number * 31536000L
            else -> -1L
        }
    }

    private fun isTouchExecution
        // 1. DESIGN - render() - ONLY Home / Indexes screen. No Profile, Settings, Trade, Notification screens.
    private fun render() {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            isSmoothScrollingEnabled = true
        }
        mainScrollView = scroll
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
        addExecutionSelector()
        addAccountCard()
        addDashboardIndexMonitor()
        if (!connected) {
            page.addView(
                button("CREATE DERIV ACCOUNT", true) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://deriv.com/signup/")))
                    } catch (e: Exception) {
                        toast("Unable to open Deriv registration. Please try again.")
                    }
                },
                matchWrap()
            )
        }
    }

    // 1. DESIGN - Helpers
    private fun sectionTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(white)
            setPadding(dp(4), dp(14), dp(4), dp(6))
        }
    }
    private fun label(
        text: String,
        size: Float = 14f,
        color: Int = muted,
        bold: Boolean = false
    ): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding(dp(4), dp(3), dp(4), dp(3))
        }
    }
    private fun button(
        text: String,
        primary: Boolean = true,
        onClick: () -> Unit
    ): Button {
        return Button(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(if (primary) bg else white)
            setBackgroundColor(if (primary) green else panel2)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { onClick() }
        }
    }
    private fun card(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(panel)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
    }
    private fun horizontalRow(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
    }
    private fun matchWrap(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }
    private fun weighted(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    private fun space(width: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(width), 1)
        }
    }
    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    private fun formatMoney(value: Double): String = String.format(java.util.Locale.US, "%.2f", value)
    private fun profitColor(value: Double): Int = when { value > 0.000001 -> green; value < -0.000001 -> red; else -> white }

    // 1. DESIGN - Header
    private fun addHeader() {
        val header = horizontalRow().apply {
            setPadding(dp(4), dp(6), dp(4), dp(10))
        }
        header.addView(label("VOLATILITIES INDICATOR", 17f, white, true), weighted())
        val statusText = if (connected) "● CONNECTED" else "○ DISCONNECTED"
        val statusColor = if (connected) green else red
        header.addView(label(statusText, 12f, statusColor, true))
        page.addView(header)
        val sub = label("Design • Connectivity • Execution • Indexes", 11f, muted, false)
        page.addView(sub)
    }

    // 3. EXECUTION - DEMO / REAL selector + Auto Trade toggle
    private fun addExecutionSelector() {
        val cardView = card()
        cardView.addView(label
                             // 4. INDEXES - Liquidity Levels: last swing highs / lows from candles
    private data class LiquidityLevels(
        val top: Double,
        val bottom: Double,
        val topTime: Long,
        val bottomTime: Long,
        val topSwept: Boolean,
        val bottomSwept: Boolean
    )
    private fun calculateLiquidityLevels(
        candles: List<DerivApiClient.Candle>,
        currentPrice: Double
    ): LiquidityLevels {
        if (candles.size < 10) return LiquidityLevels(currentPrice, currentPrice, 0L, 0L, false, false)
        val lookback = candles.takeLast(60)
        var top = Double.MIN_VALUE
        var bottom = Double.MAX_VALUE
        var topTime = 0L
        var bottomTime = 0L
        for (c in lookback) {
            if (c.high > top) { top = c.high; topTime = c.epoch }
            if (c.low < bottom) { bottom = c.low; bottomTime = c.epoch }
        }
        // Sweep = price went above top then back below, or below bottom then back above
        var topSwept = false
        var bottomSwept = false
        for (c in lookback) {
            if (c.high > top && c.close < top) topSwept = true
            if (c.low < bottom && c.close > bottom) bottomSwept = true
        }
        // Live price sweep check
        if (currentPrice > top) topSwept = true
        if (currentPrice < bottom) bottomSwept = true
        return LiquidityLevels(top, bottom, topTime, bottomTime, topSwept, bottomSwept)
    }

    // 4. INDEXES - Market Structure: HH / HL / LH / LL + BOS / CHOCH
    private data class MarketStructure(
        val trend: String, // BULLISH, BEARISH, RANGING
        val lastHigh: Double,
        val lastLow: Double,
        val bosBullish: Boolean,
        val bosBearish: Boolean,
        val chochBullish: Boolean,
        val chochBearish: Boolean
    )
    private fun calculateMarketStructure(
        candles: List<DerivApiClient.Candle>
    ): MarketStructure {
        if (candles.size < 20) return MarketStructure("RANGING", 0.0, 0.0, false, false, false, false)
        val last = candles.takeLast(30)
        var lastHigh = Double.MIN_VALUE
        var lastLow = Double.MAX_VALUE
        var prevHigh = Double.MIN_VALUE
        var prevLow = Double.MAX_VALUE
            // 2. CONNECTIVITY - Deriv connection
    private fun beginDerivConnection() {
        startAndBindDerivService()
    }
    private fun connectWithToken(token: String) {
        if (token.isBlank()) return
        derivToken = token
        appPrefs.edit().putBoolean("deriv_manually_disconnected", false).apply()
        prefs.edit().putString(if (executionMode == "REAL") "real_token" else "demo_token", token).apply()
        if (!derivServiceBound) {
            pendingDerivToken = token
            startAndBindDerivService()
            return
        }
        if (::derivApiClient.isInitialized) {
            derivApiClient.ensureConnectedOnResume(token, executionMode)
        } else {
            pendingDerivToken = token
        }
    }
    private fun disconnectDeriv(clearSavedTokens: Boolean = false) {
        appPrefs.edit().putBoolean("deriv_manually_disconnected", true).apply()
        cancelDerivReconnect()
        connected = false
        if (clearSavedTokens) {
            prefs.edit().remove("demo_token").remove("real_token").apply()
            derivToken = ""
        }
        if (::derivApiClient.isInitialized) {
            try { derivApiClient.disconnect() } catch (e: Exception) {}
        }
        runOnUiThread { render() }
    }

    // 2. CONNECTIVITY - DerivApiClient.Callback
    override fun onConnecting(message: String) {
        runOnUiThread {
            try { accountStatus.text = message } catch (e: Exception) {}
        }
    }
    override fun onConnected(accountId: String, accountType: String) {
        this.accountId = accountId
        this.accountType = accountType
        connected = true
        reconnectAttempt = 0
        cancelDerivReconnect()
        runOnUiThread { render() }
        // Request market data + availability for all indexes
        for (symbol in marketDataIndexes) {
            try { derivApiClient.subscribeTicks(symbol) } catch (e: Exception) {}
            try { derivApiClient.requestCandles(symbol) } catch (e: Exception) {}
            requestTouchAvailability(symbol)
        }
        requestOpenTradesIfNeeded()
    }
    override fun onBalance(balance: Double, currency: String) {
        accountBalance = balance
        accountCurrency = currency
        runOnUiThread {
            try {
                balanceView.text = "Balance: ${formatMoney(balance)} $currency"
                accountIdView.text = "Account: $accountId ($accountType)"
                accountStatus.text = if (connected) "Connected to Deriv" else "Not connected"
                accountStatus.setTextColor(if (connected) green else red)
            } catch (e: Exception) {}
        }
    }
    override fun onTick(symbol: String, quote: Double, epoch: Long) {
        val sym = symbol.uppercase()
        if (sym !in marketDataIndexes) return
        marketDataPrices[sym] = quote
        runOnUiThread {
            try {
                marketDataPriceViews[sym]?.text = formatMarketPrice(quote)
                dashboardPriceViews[sym]?.text = formatMarketPrice(quote)
                currentPriceViews[sym]?.forEach { it.text = formatMarketPrice(quote) }
            } catch (e: Exception) {}
        }
        // Re-evaluate Master Engine on every tick - Indexes are live
        evaluateMasterEngine(sym)
    }
    override fun onTradeData(json: JSONObject) {
        // Handle: proposal, buy, proposal_open_contract, portfolio, contracts_for
        try {
            val msgType = json.optString("msg_type", "")
            // contracts_for - availability
            if (msgType == "contracts_for") {
                val reqId = json.optInt("req_id", 0)
                val sym = touchAvailabilityRequests[reqId] ?: return
                touchAvailabilityPending.remove(sym)
                touchAvailabilityRequests.remove(reqId)
                val contracts = json.optJSONObject("contracts_for")?.optJSONArray("available") ?: json.optJSONArray("available")
                if (contracts != null) {
                    touchContractsBySymbol[sym] = contracts
                }
                return
            }
            // proposal - check payout % for 30% rule
            if (msgType == "proposal") {
                val proposal = json.optJSONObject("proposal") ?: return
                val reqId = json.optInt("req_id", 0)
                val order = pendingTouchOrders[reqId] ?: return
                val payout = proposal.optDouble("payout", 0.0)
                val askPrice = proposal.optDouble("ask_price", 0.0)
                val proposalId = proposal.optString("id", "")
                val profit = payout - order.stake
                val profitPct = if (order.stake > 0) profit / order.stake else 0.0
                // 30% rule: if payout < 30% profit, DECLINE - do NOT buy
                if (profitPct < 0.30) {
                    android.util.Log.w("TRADE_GATE", "${order.symbol} DECLINED: payout ${String.format("%.1f", profitPct*100)}% < 30% (payout $payout stake ${order.stake})")
                    pendingTouchOrders.remove(reqId)
                    pendingTouchBySymbol.remove(order.symbol)
                    touchExecutionLocks.remove(order.symbol)
                    runOnUiThread { toast("${order.symbol} DECLINED: ${String.format("%.1f", profitPct*100)}% < 30%") }
                    return
                }
                // Payout OK - buy
                order.proposalId = proposalId
                order.potentialPayout = payout
                val buyReqId = nextTradeRequestId()
                order.buyRequestId = buyReqId
                pendingTouchOrders[buyReqId] = order
                derivApiClient.send(
                    JSONObject()
                        .put("buy", proposalId)
                        .put("price", askPrice)
                        .put("req_id", buyReqId)
                )
                return
            }
            // buy response
            if (msgType == "buy") {
                val buy = json.optJSONObject("buy") ?: return
                val contractId = buy.optLong("contract_id", 0L)
                val reqId = json.optInt("req_id", 0)
                val order = pendingTouchOrders[reqId] ?: return
                val contract = RunningContract(
                    contractId = contractId,
                    symbol = order.symbol,
                    direction = order.direction,
                    stake = order.stake,
                    target = order.target,
                    targetProbability = 0.30,
                    mode = order.mode,
                    entryBarrier = order.barrierOffset,
                    potentialPayout = order.potentialPayout
                )
                runningContracts[contractId] = contract
                // Mark sweep as used - one trade per sweep
                appPrefs.edit().putLong("used_sweep_time_${accountId}_${executionMode}_${order.symbol}", order.sweepTime).apply()
                consumedTouchQualifications[order.symbol] = marketDataCandles[order.symbol]?.lastOrNull()?.epoch ?: 0L
                pendingTouchOrders.remove(reqId)
                touchExecutionLocks.remove(order.symbol)
                runOnUiThread {
                    toast("${order.symbol} BOUGHT: ${order.direction} Stake ${formatMoney(order.stake)}")
                    render()
                }
                return
            }
            // proposal_open_contract - live P/L
            if (msgType == "proposal_open_contract") {
                val poc = json.optJSONObject("proposal_open_contract") ?: return
                val contractId = poc.optLong("contract_id", 0L)
                val profit = poc.optDouble("profit", 0.0)
                liveContractProfits[contractId] = profit
                val total = liveContractProfits.values.sum()
                runOnUiThread {
                    try {
                        openPlView.text = "Open P/L: ${formatMoney(total)}"
                        openPlView.setTextColor(profitColor(total))
                        contractLiveViews[contractId]?.pnl?.let {
                            it.text = formatMoney(profit)
                            it.setTextColor(profitColor(profit))
                        }
                    } catch (e: Exception) {}
                }
                // Target hit: 30% profit reached - you can add auto-close here if you want
                val contract = runningContracts[contractId]
                if (contract != null && profit >= contract.target && !contract.closeRequested) {
                    contract.closeRequested = true
                    contract.closeReason = "TARGET 30% HIT"
                    // Close via sell - Deriv sell uses contract_id
                    try {
                        derivApiClient.send(JSONObject().put("sell", contractId).put("price", 0))
                    } catch (e: Exception) {}
                }
                return
            }
            // portfolio - mark loaded
            if (msgType == "portfolio") {
                portfolioLoadedForAccount = true
                openTradesResponse = json
                return
            }
        } catch (e: Exception) {
            android.util.Log.e("onTradeData", "Error: ${e.message}")
        }
    }
    override fun onCandles(
        symbol: String,
        candles: List<DerivApiClient.Candle>,
        error: String?
    ) {
        val sym = symbol.uppercase()
        if (sym !in marketDataIndexes) return
        if (error != null) {
            marketDataErrors[sym] = error
            return
        }
        marketDataCandles[sym] = candles
        marketDataErrors.remove(sym)
        evaluateMasterEngine(sym)
    }
    override fun onMarketDataError(symbol: String, message: String) {
        val sym = symbol.uppercase()
        marketDataErrors[sym] = message
        runOnUiThread {
            try { dashboardStatusViews[sym]?.let { it.text = "FEED ERROR"; it.setTextColor(red) } } catch (e: Exception) {}
        }
    }
    override fun onError(message: String) {
        android.util.Log.e("MainActivity", "Deriv error: $message")
        runOnUiThread { toast(message) }
    }
    override fun onDisconnected() {
        connected = false
        runOnUiThread {
            try {
                accountStatus.text = "Disconnected"
                accountStatus.setTextColor(red)
            } catch (e: Exception) {}
        }
        scheduleDerivReconnect()
    }
    override fun onDestroy() {
        try { derivService?.detachActivity(this) } catch (e: Exception) {}
        super.onDestroy()
    }
    }

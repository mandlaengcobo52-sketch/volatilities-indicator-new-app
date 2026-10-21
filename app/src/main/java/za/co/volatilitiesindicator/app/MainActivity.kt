package za.co.volatilitiesindicator.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.net.Uri
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.util.UUID
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Spinner
import android.widget.ArrayAdapter
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
    private val white = Color.rgb(255, 220, 45)
    private val muted = Color.rgb(242, 248, 245)
    private val yellow = Color.rgb(255, 220, 45)
    private val red = Color.rgb(255, 65, 75)
    private val cyan = Color.rgb(0, 225, 235)
    private val blue = Color.rgb(70, 155, 255)

    private val appPrefs by lazy {
    getSharedPreferences("volatilities_app_settings", MODE_PRIVATE)
}

private var platform = "USER"
    set(value) {
        field = value
        appPrefs.edit().putString("platform", value).apply()
    }

private var executionMode = "DEMO"
    set(value) {
        field = value
        appPrefs.edit().putString("executionMode", value).apply()
    }

private var selectedTab = "Home"
    set(value) {
        field = value
        appPrefs.edit().putString("selectedTab", value).apply()
    }

private var drawerOpen = false

    private lateinit var page: LinearLayout
    private var mainScrollView: ScrollView? = null
    private val analysisRenderHandler by lazy { Handler(mainLooper) }
    private var pendingAnalysisRender: Runnable? = null
    private var lastRenderedTab: String? = null
    private lateinit var accountStatus: TextView
    private lateinit var accountIdView: TextView
    private lateinit var balanceView: TextView
    private lateinit var openPlView: TextView
    private val liveContractProfits = mutableMapOf<Long, Double>()
    private val liveContractProfitModes = mutableMapOf<Long, String>()
    private var runningTotalPlView: TextView? = null
    private var historyAccountKey: String = ""
    private var connected = false
    private var accountId = ""
    private var accountType = ""
    private var accountBalance = 0.0
    private var accountCurrency = ""
    private val derivApiClient by lazy { DerivApiClient(this) }
    private val httpClient = OkHttpClient()
    private val prefs by lazy { getSharedPreferences("deriv_oauth", MODE_PRIVATE) }
    private val authPrefs by lazy { getSharedPreferences("vi_platform_auth", MODE_PRIVATE) }
    private val authEndpoint = "https://volatilitiesindicator.org.za/mobile-auth.php"
    private var authToken: String = ""
    private var currentProfile: JSONObject? = null
    private var profileLoading: Boolean = false
    // Every login/logout changes this generation. Async responses from older
    // sessions are ignored so one user's profile cannot overwrite another's.
    private var authSessionGeneration: Long = 0L
    private var adminUsers: JSONArray? = null
    private var adminUsersLoading: Boolean = false
    private var openTradesResponse: JSONObject? = null
    private var portfolioLoadedForAccount: Boolean = false
    // Throttle retries while waiting for Deriv's portfolio response.
    private var lastOpenTradesRequestAt: Long = 0L
    private var openContractResponse: JSONObject? = null
    private var tradeHistoryResponse: JSONObject? = null

    // MASTER DECISION / PTPE / DERIV EXECUTION STATE
    private var autoTradeEnabled: Boolean
        get() = appPrefs.getBoolean("autoTradeEnabled", false)
        set(value) { appPrefs.edit().putBoolean("autoTradeEnabled", value).apply() }

    private data class MasterDecision(
        val symbol: String,
        val direction: String,
        val entry: Double,
        val target: Double,
        val targetDistancePercent: Double,
        val targetProbability: Double,
        val sampleCount: Int,
        val reason: String
    )

    private data class PendingOrder(
        val symbol: String,
        val direction: String,
        val entry: Double,
        val target: Double,
        val targetProbability: Double,
        val stake: Double,
        val mode: String,
        val stage: String
    )

    private data class RunningContract(
        val contractId: Long,
        val symbol: String,
        val direction: String,
        val stake: Double,
        val target: Double,
        val targetProbability: Double,
        val mode: String,
        var takeProfitRequested: Boolean = false,
        var openContractConfirmed: Boolean = false,
        var targetNotified: Boolean = false,
        var trailingActivated: Boolean = false,
        var peakProfit: Double = 0.0,
        var closeRequested: Boolean = false,
        var closeReason: String = ""
    )
    private data class ContractLiveViews(
        val stake: Double,
        val symbol: String,
        val target: Double?,
        val probability: Double,
        val pnl: TextView,
        val spot: TextView,
        val barrierExpiry: TextView,
        val ptpe: TextView
    )
    private val contractLiveViews = mutableMapOf<Long, ContractLiveViews>()

    private val pendingOrdersByRequest = mutableMapOf<Int, PendingOrder>()
    private val pendingIndexSymbols = mutableSetOf<String>()
    private val runningContracts = mutableMapOf<Long, RunningContract>()
    private var nextTradeRequestId = 710000
    private var manualTradeDirection = "BUY"
    private val lastDecisionByIndex = mutableMapOf<String, MasterDecision>()
    private data class PtpeDisplay(
        val direction: String = "WAIT",
        val target: Double? = null,
        val distancePercent: Double? = null,
        val probability: Double = 0.0,
        val samples: Int = 0,
        val status: String = "WAITING FOR MARKET DATA"
    )
    private val lastPtpeByIndex = mutableMapOf<String, PtpeDisplay>()
    private val lastPtpeEvaluationAt = mutableMapOf<String, Long>()
    private val ptpeStatusViews = mutableMapOf<String, TextView>()
    private val notificationItems = mutableListOf<String>()
    private var notificationId = 4200
    private val notificationsEnabled: Boolean
        get() = appPrefs.getBoolean("notificationsEnabled", false)
    private var trailingStopEnabled: Boolean
        get() = appPrefs.getBoolean("trailingStopEnabled", false)
        set(value) { appPrefs.edit().putBoolean("trailingStopEnabled", value).apply() }
    private val dailyCapitalTimeZone = java.util.TimeZone.getTimeZone("Africa/Johannesburg")

    private fun nextTradeRequestId(): Int = ++nextTradeRequestId

    private fun saDayKey(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        .apply { timeZone = dailyCapitalTimeZone }.format(java.util.Date())

    /** Freeze the balance first observed for this account+mode on the SA calendar day. */
    private fun dailyStartingCapital(): Double {
        if (accountBalance <= 0.0) return 0.0
        val accountKey = accountId.ifBlank { "unlinked" }.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val key = "daily_start_${accountKey}_${executionMode}_${saDayKey()}"
        val existing = appPrefs.getString(key, null)?.toDoubleOrNull()
        if (existing != null && existing > 0.0) return existing
        appPrefs.edit().putString(key, accountBalance.toString()).apply()
        return accountBalance
    }

    /** The stake is fixed for the whole South African calendar day: 20% of day-start capital. */
    private fun fixedDailyStake(): Double {
        val capital = dailyStartingCapital()
        return if (capital > 0.0) kotlin.math.round(capital * 0.20 * 100.0) / 100.0 else 0.0
    }

    private fun normalizedSymbol(item: JSONObject): String = item.optString(
        "underlying", item.optString("underlying_symbol", item.optString("symbol", ""))
    ).uppercase()

    private fun activeContractForIndex(symbol: String): Boolean {
        // A pending request is a duplicate-execution lock, not proof of an open contract.
        if (pendingOrdersByRequest.values.any {
                it.symbol == symbol && it.mode == executionMode
            }) return true
        if (runningContracts.values.any {
                it.symbol == symbol && it.mode == executionMode
            }) return true

        val root = openTradesResponse ?: return false
        val portfolio = root.optJSONObject("portfolio")
        val arr = portfolio?.optJSONArray("contracts") ?: root.optJSONArray("contracts")
        if (arr != null) for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val contractSymbol = normalizedSymbol(item)
            val sold = item.optInt("is_sold", 0) == 1 ||
                item.optString("is_sold", "").equals("true", true)
            val status = item.optString("status", "open").lowercase()
            if (contractSymbol == symbol.uppercase() && !sold &&
                status !in setOf("sold", "won", "lost", "expired")) return true
        }
        return false
    }

    private fun requestOpenTradesIfNeeded() {
        if (!connected || accountId.isBlank() || portfolioLoadedForAccount) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastOpenTradesRequestAt < 5000L) return
        lastOpenTradesRequestAt = now
        derivApiClient.requestOpenTrades()
    }

    /**
     * A confirmed contract may carry a stake from a previous day's capital cycle.
     * That contract must never block today's trade solely because its stake differs.
     * Only a still-pending purchase in the same selected mode can block a conflicting stake.
     */
    private fun hasOpenStakeMismatch(expectedStake: Double): Boolean {
        if (expectedStake <= 0.0) return true
        return pendingOrdersByRequest.values.any {
            it.mode == executionMode && kotlin.math.abs(it.stake - expectedStake) > 0.01
        }
    }

    private fun riskPlanName(): String = currentProfile?.optString("risk_profile", "")?.trim()?.lowercase().orEmpty()

    private fun maxTradesPerDay(): Int {
        return when (riskPlanName()) {
            "minimum" -> 1
            "medium" -> 2
            "maximum" -> 3
            "super maximum", "super_maximum", "super-maximum", "privilege" -> Int.MAX_VALUE
            else -> 0
        }
    }

    private fun riskPlanIsActive(): Boolean {
        val user = currentProfile ?: return false
        val plan = riskPlanName()
        if (plan.isBlank() || plan == "null") return false
        val start = user.optString("start_date", "").trim()
        val expiry = user.optString("expiry_date", "").trim()
        if (start.isBlank() || start == "null" || expiry.isBlank() || expiry == "null") return false
        return try {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { isLenient = false; timeZone = dailyCapitalTimeZone }
            val startDate = fmt.parse(start) ?: return false
            val expiryDate = fmt.parse(expiry) ?: return false
            val today = fmt.parse(saDayKey()) ?: return false
            !today.before(startDate) && !today.after(expiryDate)
        } catch (_: Exception) { false }
    }

    private fun dailyTradeCount(): Int {
        val accountKey = accountId.ifBlank { "unlinked" }.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return appPrefs.getInt("daily_trades_${accountKey}_${executionMode}_${saDayKey()}", 0)
    }

    private fun canPlaceTrade(showToast: Boolean = true): Boolean {
        if (!riskPlanIsActive()) {
            if (showToast) toast("Trading is locked: an active risk plan must be assigned by the administrator.")
            return false
        }
        val limit = maxTradesPerDay()
        if (limit <= 0) {
            if (showToast) toast("Trading is locked: your risk plan is not recognised.")
            return false
        }
        if (dailyTradeCount() >= limit) {
            if (showToast) toast("Daily risk-plan limit reached (${if (limit == Int.MAX_VALUE) "unlimited" else limit} trades).")
            val eventKey = "risk_limit_notice_${accountId}_${executionMode}_${saDayKey()}"
            if (!appPrefs.getBoolean(eventKey, false)) {
                appPrefs.edit().putBoolean(eventKey, true).apply()
                notifyEvent("Daily limit reached", "No additional trades can be opened until the South African day resets.",
                    "daily_limit_${accountId}_${executionMode}_${saDayKey()}")
            }
            return false
        }
        return true
    }

    private fun recordSuccessfulTrade(symbol: String, stake: Double, contractId: Long) {
        val accountKey = accountId.ifBlank { "unlinked" }.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val key = "daily_trades_${accountKey}_${executionMode}_${saDayKey()}"
        appPrefs.edit().putInt(key, dailyTradeCount() + 1).apply()
        notifyEvent("Touch trade opened", "$symbol · Contract $contractId · Stake ${String.format(java.util.Locale.US, "%.2f", stake)} ${accountCurrency.ifBlank { "USD" }}")
    }

    /**
     * PTPE estimates whether the required price distance from the CURRENT live entry price
     * is historically reachable in the next 24 completed hourly candles. The caller supplies
     * the percentage distance from the current spot to the proposed absolute barrier; this
     * function applies that same percentage to each prior same-direction MSS event's close.
     * It does not calculate continuation from the original signal price or treat any tiny
     * favourable movement as a successful historical continuation.
     */
    private fun targetProbabilityPercent(
        candles: List<DerivApiClient.Candle>, direction: String, targetDistancePercent: Double
    ): Pair<Double, Int> {
        if (candles.size < 100 || !targetDistancePercent.isFinite() || targetDistancePercent <= 0.0 ||
            (direction != "BULLISH" && direction != "BEARISH")) return 0.0 to 0
        val outcomes = analysisHistoricalOutcomes(candles).filter { it.direction == direction }
        var samples = 0
        var hits = 0
        val evaluationCandles = 24
        for (outcome in outcomes) {
            val start = candles.indexOfFirst { it.epoch == outcome.confirmation.epoch }
            // Require 24 subsequent candles, and never count a partial forward window.
            if (start < 0 || start + evaluationCandles >= candles.size) continue
            val historicalEntry = outcome.confirmation.close
            if (!historicalEntry.isFinite() || historicalEntry <= 0.0) continue
            val historicalTarget = if (direction == "BULLISH")
                historicalEntry * (1.0 + targetDistancePercent / 100.0)
            else historicalEntry * (1.0 - targetDistancePercent / 100.0)
            if (!historicalTarget.isFinite() || historicalTarget <= 0.0) continue
            samples++
            var touched = false
            for (i in (start + 1)..(start + evaluationCandles)) {
                val candle = candles[i]
                if ((direction == "BULLISH" && candle.high >= historicalTarget) ||
                    (direction == "BEARISH" && candle.low <= historicalTarget)) {
                    touched = true
                    break
                }
            }
            if (touched) hits++
        }
        if (samples == 0) return 0.0 to 0
        return (hits.toDouble() / samples.toDouble() * 100.0) to samples
    }
    /** Sole authority for trade approval. PTPE is always recorded for display, even when it fails. */
    private fun evaluateMasterDecision(symbol: String): MasterDecision? {
        val candles = analysisCompletedCandles(symbol)
        if (candles.size < 100) {
            lastPtpeByIndex[symbol] = PtpeDisplay(status = "WAITING FOR 100+ CANDLES")
            lastDecisionByIndex.remove(symbol)
            return null
        }
        val current = marketDataPrices[symbol] ?: candles.lastOrNull()?.close
        if (current == null || !current.isFinite() || current <= 0.0) {
            lastPtpeByIndex[symbol] = PtpeDisplay(status = "WAITING FOR LIVE PRICE")
            lastDecisionByIndex.remove(symbol)
            return null
        }
        val event = analysisLatestMss(candles)
        if (event == null) {
            lastPtpeByIndex[symbol] = PtpeDisplay(status = "WAITING FOR CONFIRMED MSS/CHOCH")
            lastDecisionByIndex.remove(symbol)
            return null
        }
        val direction = event.direction
        val targetDirection = if (direction == "BULLISH") "UP" else "DOWN"
        val targetPivot = analysisStructuralTarget(candles, current, targetDirection, event.confirmation.epoch)
        if (targetPivot == null) {
            lastPtpeByIndex[symbol] = PtpeDisplay(direction = if (direction == "BULLISH") "BUY" else "SELL", status = "WAITING FOR STRUCTURAL TARGET")
            lastDecisionByIndex.remove(symbol)
            return null
            }
        val target = targetPivot.price
        if (!target.isFinite() || (direction == "BULLISH" && target <= current) || (direction == "BEARISH" && target >= current)) {
            lastPtpeByIndex[symbol] = PtpeDisplay(direction = if (direction == "BULLISH") "BUY" else "SELL", target = target, status = "INVALID TARGET DIRECTION")
            lastDecisionByIndex.remove(symbol)
            return null
        }
        val distance = kotlin.math.abs(target - current) / current * 100.0
        val (probability, samples) = targetProbabilityPercent(candles, direction, distance)
        val decisionDirection = if (direction == "BULLISH") "BUY" else "SELL"
        val priorOutcomeExists = analysisHistoricalOutcomes(candles).any { it.direction == direction && it.confirmation.epoch != event.confirmation.epoch }
        val status = when {
            distance < 1.5 -> "WAIT — TARGET DISTANCE BELOW 1.5%"
            samples < 5 -> "WAIT — INSUFFICIENT PTPE SAMPLES"
            probability < 55.0 -> "WAIT — PTPE BELOW 55%"
            !priorOutcomeExists -> "WAIT — NO PRIOR SAME-DIRECTION OUTCOME"
            else -> "QUALIFIED"
        }
        lastPtpeByIndex[symbol] = PtpeDisplay(decisionDirection, target, distance, probability, samples, status)
        if (status != "QUALIFIED") {
            lastDecisionByIndex.remove(symbol)
            return null
        }
        val decision = MasterDecision(symbol, decisionDirection, current, target, distance, probability, samples,
            "Confirmed MSS/CHOCH + structural target + empirical 24-hour PTPE; 3-day contract timeframe")
        lastDecisionByIndex[symbol] = decision
        notifyEvent(
            "Trade qualified",
            "$symbol · ${decision.direction} · Target ${formatMarketPrice(decision.target)} · PTPE ${String.format(java.util.Locale.US, "%.1f", decision.targetProbability)}%",
            "qualified_${symbol}_${decision.direction}_${event.confirmation.epoch}_${executionMode}_${accountId}"
        )
        return decision
    }
    private fun attemptMasterDecisionExecution(symbol: String, decision: MasterDecision) {
        // This decision was evaluated from the latest tick immediately before this call.
        // The proposal-response path still performs its own fresh validation before purchase.
        val signalKey = "${accountId}_${executionMode}_${symbol}_${decision.direction}_${String.format(java.util.Locale.US, "%.3f", decision.target)}"
        if (!autoTradeEnabled) {
            recordExecutionStage("Execution blocked", "$symbol · Auto Trade is OFF.", "blocked_auto_$signalKey")
            return
        }
        if (!connected || accountId.isBlank()) {
            recordExecutionStage("Execution blocked", "$symbol · Deriv is disconnected or no account is selected.", "blocked_connection_$signalKey")
            return
        }
        // Never execute against a stale/unknown portfolio: wait until Deriv confirms current open contracts.
        if (!portfolioLoadedForAccount) {
            requestOpenTradesIfNeeded()
            recordExecutionStage(
                "Execution waiting",
                "$symbol · Waiting for Deriv's current open-contract portfolio. No purchase has been submitted.",
                "waiting_portfolio_${accountId}_${executionMode}"
            )
            return
        }
        if (symbol !in marketDataIndexes) {
            recordExecutionStage("Execution blocked", "$symbol · Unsupported index.", "blocked_index_$signalKey")
            return
        }
        if (pendingOrdersByRequest.values.any { it.symbol == symbol && it.mode == executionMode }) {
            val stage = pendingOrdersByRequest.values.firstOrNull {
                it.symbol == symbol && it.mode == executionMode
            }?.stage ?: "EXECUTION"
            recordExecutionStage(
                "Execution in progress",
                "$symbol · A $stage request is already in progress for this index. Duplicate purchase suppressed.",
                "execution_in_progress_${accountId}_${executionMode}_${symbol}"
            )
            return
        }
        if (activeContractForIndex(symbol)) {
            recordExecutionStage(
                "Execution blocked",
                "$symbol · Deriv's confirmed portfolio or local confirmed-contract state shows an open contract for this index.",
                "blocked_open_contract_${accountId}_${executionMode}_${symbol}"
            )
            return
        }
        if (!canPlaceTrade(showToast = false)) {
            val reason = if (!riskPlanIsActive()) "Risk plan is inactive or expired."
                else "Daily risk-plan trade limit has been reached or the risk plan is unrecognised."
            recordExecutionStage("Execution blocked", "$symbol · $reason", "blocked_risk_$signalKey")
            return
        }
        if (accountBalance <= 0.0) {
            notifyEvent("Execution rejected", "$symbol · The selected Deriv account has no available balance.",
                "insufficient_funds_${accountId}_${executionMode}_${saDayKey()}_$symbol")
            return
        }
        val stake = fixedDailyStake()
        if (hasOpenStakeMismatch(stake)) {
            notifyEvent("Execution rejected", "$symbol · An existing open or pending contract uses a different stake. No new trade can open until the stake is consistent.",
                "stake_mismatch_${accountId}_${executionMode}_${saDayKey()}_$symbol")
            return
        }
        if (stake <= 0.0 || stake > accountBalance) {
            notifyEvent("Execution rejected", "$symbol · The fixed daily stake is greater than the available balance.",
                "insufficient_funds_${accountId}_${executionMode}_${saDayKey()}_$symbol")
            return
        }
        // Deriv accepts an unsigned barrier string as an absolute target price.
        // Do not convert the target into a signed offset from the current spot.
        val absoluteBarrier = decision.target
        if (!absoluteBarrier.isFinite() || absoluteBarrier <= 0.0) {
            recordExecutionStage("Execution rejected", "$symbol · The calculated absolute target/barrier is invalid.", "blocked_barrier_$signalKey")
            return
        }
        val reqId = nextTradeRequestId()
        pendingIndexSymbols.add(symbol)
        pendingOrdersByRequest[reqId] = PendingOrder(symbol, decision.direction, decision.entry,
            decision.target, decision.targetProbability, stake, executionMode, "PROPOSAL")
        val proposal = JSONObject()
            .put("proposal", 1)
            .put("amount", stake)
            .put("basis", "stake")
            .put("contract_type", "ONETOUCH")
            .put("currency", accountCurrency.ifBlank { "USD" })
            .put("duration", 3)
            .put("duration_unit", "d")
            .put("underlying_symbol", symbol)
            .put("barrier", String.format(java.util.Locale.US, "%.2f", absoluteBarrier))
            .put("req_id", reqId)
        if (!derivApiClient.send(proposal)) {
            pendingOrdersByRequest.remove(reqId)
            pendingIndexSymbols.remove(symbol)
            notifyEvent("Execution rejected", "$symbol · Unable to send the proposal request; Deriv connection/client did not accept the request.",
                "trade_open_failed_${accountId}_${executionMode}_$reqId")
        } else {
            notifyEvent("Execution requested", "$symbol · ${decision.direction} · Proposal request sent to Deriv. Stake ${formatMoney(stake)}; target ${formatMarketPrice(absoluteBarrier)}.",
                "execution_requested_${accountId}_${executionMode}_$reqId")
            // A proposal timeout is safe to release because no purchase request has been sent.
            // Do not leave the index locked indefinitely if Deriv never answers the proposal.
            android.os.Handler(mainLooper).postDelayed({
                val stillWaiting = pendingOrdersByRequest[reqId]
                if (stillWaiting != null && stillWaiting.stage == "PROPOSAL") {
                    pendingOrdersByRequest.remove(reqId)
                    if (pendingOrdersByRequest.values.none { it.symbol == stillWaiting.symbol && it.mode == stillWaiting.mode } &&
                        runningContracts.values.none { it.symbol == stillWaiting.symbol && it.mode == stillWaiting.mode }) {
                        pendingIndexSymbols.remove(stillWaiting.symbol)
                    }
                    notifyEvent(
                        "Execution rejected",
                        "${stillWaiting.symbol} · No proposal response was received from Deriv within 15 seconds. No purchase was submitted; the index lock was released safely.",
                        "proposal_timeout_${accountId}_${executionMode}_$reqId"
                    )
                }
            }, 15000L)
        }
    }

    private fun handleMasterDecisionTradeResponse(json: JSONObject) {
        val msgType = json.optString("msg_type", "")
        val reqId = json.optInt("req_id", -1)
        if (json.has("error")) {
            val failed = pendingOrdersByRequest.remove(reqId)
            if (failed != null) {
                pendingIndexSymbols.remove(failed.symbol)
                val errorMessage = json.optJSONObject("error")?.optString("message", "Deriv rejected the request.")
                    ?: "Deriv rejected the request."
                val insufficientFunds = errorMessage.contains("balance", true) ||
                    errorMessage.contains("fund", true) || errorMessage.contains("insufficient", true)
                notifyEvent(
                    if (insufficientFunds) "Execution rejected" else "Execution rejected",
                    "${failed.symbol} · Deriv rejected ${failed.stage.lowercase()} request: $errorMessage",
                    "trade_open_failed_${accountId}_${executionMode}_$reqId"
                )
            }
            return
        }
        val pending = pendingOrdersByRequest[reqId]
        if (pending != null && msgType == "proposal") {
            val proposal = json.optJSONObject("proposal")
            val proposalId = proposal?.optString("id", "").orEmpty()
            // Deriv's proposal payout is the total return (stake + profit) for a winning
            // ONETOUCH contract. Therefore payout must be at least 120% of stake to offer
            // the required 10% profit on stake.
            val payout = proposal?.optDouble("payout", 0.0) ?: 0.0
            if (proposalId.isBlank() || payout <= 0.0) {
                pendingOrdersByRequest.remove(reqId)
                pendingIndexSymbols.remove(pending.symbol)
                notifyEvent("Execution rejected", "${pending.symbol} · Deriv returned an invalid proposal (missing proposal ID or non-positive payout).",
                    "trade_open_failed_${accountId}_${executionMode}_$reqId")
                return
            }

            // Fresh-entry gate: the original signal may have become stale while the proposal
            // was in flight. Recalculate the full Master Decision using the latest live price,
            // then ensure the target still offers the required minimum distance and is still
            // the same structural target/direction that generated this proposal.
            val freshDecision = evaluateMasterDecision(pending.symbol)
            // Execution must use a live quote, not the candle-close fallback used for analysis.
            // PTPE is recalculated by evaluateMasterDecision() from this latest quote to the
            // absolute barrier. Never submit a proposal if the quote/target/probability changed.
            val livePrice = marketDataPrices[pending.symbol]
            val liveSpot = livePrice?.takeIf { it.isFinite() && it > 0.0 }
            val livePriceValid = liveSpot != null
            val freshDistance = if (liveSpot != null)
                kotlin.math.abs(pending.target - liveSpot) / liveSpot * 100.0 else 0.0
            val targetStillAhead = liveSpot != null && when (pending.direction) {
                "BUY" -> pending.target > liveSpot
                "SELL" -> pending.target < liveSpot
                else -> false
            }
            val sameSignal = freshDecision != null && freshDecision.direction == pending.direction &&
                kotlin.math.abs(freshDecision.target - pending.target) <= kotlin.math.max(0.0000001, kotlin.math.abs(pending.target) * 0.0000001)
            val freshPtpeQualifies = freshDecision != null && freshDecision.targetProbability >= 55.0 &&
                freshDecision.sampleCount >= 5 && freshDecision.targetDistancePercent >= 1.5
            if (freshDecision == null || !livePriceValid || !sameSignal || !targetStillAhead ||
                freshDistance < 1.5 || !freshPtpeQualifies) {
                pendingOrdersByRequest.remove(reqId)
                pendingIndexSymbols.remove(pending.symbol)
                val reason = when {
                    freshDecision == null -> "Master Decision no longer qualifies at the latest price."
                    !livePriceValid -> "No valid live price is available for final PTPE validation."
                    !sameSignal -> "The qualifying direction or absolute structural barrier changed while requesting the proposal."
                    !targetStillAhead -> "The target is no longer ahead of the current entry price."
                    !freshPtpeQualifies -> "Fresh PTPE no longer meets the 55% probability, 5-sample, and 1.50% minimum-distance requirements."
                    else -> "Remaining target distance is ${String.format(java.util.Locale.US, "%.2f", freshDistance)}%; minimum required is 1.50%."
                }
                notifyEvent("Execution rejected", "${pending.symbol} · Fresh-entry validation failed. $reason No purchase submitted.",
                    "fresh_entry_rejected_${accountId}_${executionMode}_$reqId")
                return
            }

            val requiredPayout = pending.stake * 1.20
            if (payout + 1e-8 < requiredPayout) {
                pendingOrdersByRequest.remove(reqId)
                pendingIndexSymbols.remove(pending.symbol)
                val potentialProfit = payout - pending.stake
                notifyEvent("Execution rejected", "${pending.symbol} · Insufficient remaining target profit. Deriv proposal payout ${formatMoney(payout)}; stake ${formatMoney(pending.stake)}; potential profit ${formatMoney(potentialProfit)}; required profit ${formatMoney(pending.stake * 0.20)} (20% of stake). No purchase submitted.",
                    "profit_target_rejected_${accountId}_${executionMode}_$reqId")
                return
            }
            notifyEvent("Profit target eligibility passed", "${pending.symbol} · Current proposal supports at least 20% potential profit on stake; fresh target distance ${String.format(java.util.Locale.US, "%.2f", freshDistance)}%.",
                "profit_target_passed_${accountId}_${executionMode}_$reqId")
            val buyReqId = nextTradeRequestId()
            pendingOrdersByRequest.remove(reqId)
            pendingOrdersByRequest[buyReqId] = pending.copy(stage = "BUY")
            if (!derivApiClient.send(JSONObject().put("buy", proposalId).put("price", pending.stake).put("req_id", buyReqId))) {
                pendingOrdersByRequest.remove(buyReqId)
                pendingIndexSymbols.remove(pending.symbol)
                notifyEvent("Execution rejected", "${pending.symbol} · Proposal received, but the purchase request could not be sent.",
                    "trade_open_failed_${accountId}_${executionMode}_$buyReqId")
            } else {
                notifyEvent("Execution requested", "${pending.symbol} · Deriv returned a valid proposal; purchase request sent.",
                    "buy_requested_${accountId}_${executionMode}_$buyReqId")
            }
            return
        }
        if (pending != null && msgType == "buy") {
            val buy = json.optJSONObject("buy")
            val contractId = buy?.optLong("contract_id", 0L) ?: 0L
            if (contractId <= 0L) {
                pendingOrdersByRequest.remove(reqId)
                pendingIndexSymbols.remove(pending.symbol)
                notifyEvent("Execution rejected", "${pending.symbol} · Deriv buy response did not include a valid contract ID.",
                    "trade_open_failed_${accountId}_${executionMode}_$reqId")
                return
            }
            runningContracts[contractId] = RunningContract(contractId, pending.symbol, pending.direction,
                pending.stake, pending.target, pending.targetProbability, pending.mode)
            notifyEvent("Execution accepted", "${pending.symbol} · Deriv accepted the buy request and returned contract $contractId.",
                "execution_accepted_${accountId}_${executionMode}_$contractId")
            recordSuccessfulTrade(pending.symbol, pending.stake, contractId)
            pendingOrdersByRequest.remove(reqId)
            derivApiClient.send(JSONObject().put("proposal_open_contract", 1).put("contract_id", contractId).put("subscribe", 1))
            derivApiClient.requestOpenTrades()
            derivApiClient.requestTradeHistory()
            if (selectedTab == "Trade") render()
            return
        }
        if (msgType == "proposal_open_contract") {
            val contract = json.optJSONObject("proposal_open_contract") ?: return
            val contractId = contract.optLong("contract_id", 0L)
            val running = runningContracts[contractId] ?: return
            val status = contract.optString("status", "")
            if (!running.openContractConfirmed && status !in setOf("sold", "won", "lost", "expired") && contractId > 0L) {
                running.openContractConfirmed = true
                notifyEvent("Open contract confirmed", "${running.symbol} · Contract $contractId · Deriv confirms status ${status.ifBlank { "open" }.uppercase()}.",
                    "open_contract_confirmed_${accountId}_${executionMode}_$contractId")
            }
            val profitOpen = contract.optDouble("profit", contract.optDouble("profit_open", 0.0))
            val trailStateKey = "trail_state_${accountId}_${executionMode}_$contractId"
            if (!running.trailingActivated) {
                running.trailingActivated = appPrefs.getBoolean("${trailStateKey}_activated", false)
                running.peakProfit = appPrefs.getString("${trailStateKey}_peak", "0")?.toDoubleOrNull() ?: 0.0
                running.targetNotified = appPrefs.getBoolean("${trailStateKey}_target_notified", false)
            }
            val isClosed = status in setOf("sold", "won", "lost", "expired") || contract.optInt("is_sold", 0) == 1
            if (!isClosed) {
                liveContractProfits[contractId] = profitOpen
                liveContractProfitModes[contractId] = "${accountId}_$executionMode"
                val tenPercentTarget = running.stake * 0.10
                val twentyPercentTarget = running.stake * 0.20
                // The monetary profit target is 20%; trailing activation remains a separate 10% rule.
                if (!running.targetNotified && profitOpen >= twentyPercentTarget) {
                    running.targetNotified = true
                    appPrefs.edit().putBoolean("${trailStateKey}_target_notified", true).apply()
                    notifyEvent("20% Profit Target Reached", "${running.symbol} · Contract $contractId · Stake ${formatMoney(running.stake)} · Target ${formatMoney(twentyPercentTarget)} · Current P/L ${formatMoney(profitOpen)}. Trailing stop ${if (trailingStopEnabled) "ACTIVE" else "READY — currently OFF"}.",
                        "profit_20pct_reached_${accountId}_${executionMode}_$contractId")
                }
                if (!running.trailingActivated && profitOpen >= tenPercentTarget) {
                    running.trailingActivated = true
                    running.peakProfit = profitOpen
                    appPrefs.edit().putBoolean("${trailStateKey}_activated", true).putString("${trailStateKey}_peak", running.peakProfit.toString()).apply()
                }
                if (running.trailingActivated) {
                    running.peakProfit = maxOf(running.peakProfit, profitOpen)
                    appPrefs.edit().putBoolean("${trailStateKey}_activated", true).putString("${trailStateKey}_peak", running.peakProfit.toString()).apply()
                }

                // The structural price target is distinct from the 10% monetary profit target.
                // Close only when a known target from the Master Decision is crossed.
                val currentSpot = contract.optDouble("current_spot", contract.optDouble("current_spot_display_value", 0.0))
                val structureReached = running.target > 0.0 && currentSpot > 0.0 &&
                    ((running.direction.equals("BUY", true) && currentSpot >= running.target) ||
                     (running.direction.equals("SELL", true) && currentSpot <= running.target))
                if (structureReached && !running.closeRequested) {
                    running.closeRequested = true
                    running.closeReason = "Structure Target Reached"
                    val sent = derivApiClient.send(JSONObject().put("sell", contractId).put("price", 0).put("req_id", nextTradeRequestId()))
                    if (!sent) {
                        running.closeRequested = false
                        running.closeReason = ""
                        notifyEvent("Structure Target Close Failed", "${running.symbol} · Contract $contractId · Close request could not be sent; monitoring continues.",
                            "structure_close_failed_${accountId}_${executionMode}_$contractId")
                    } else {
                        notifyEvent("Structure Target Reached", "${running.symbol} · Contract $contractId · Spot ${formatMarketPrice(currentSpot)} reached structure target ${formatMarketPrice(running.target)}. Waiting for Deriv confirmation."

                                    val trailingGap = tenPercentTarget
                val protectedProfit = maxOf(0.0, running.peakProfit - trailingGap)
                if (trailingStopEnabled && running.trailingActivated && !running.closeRequested &&
                    profitOpen <= protectedProfit && running.peakProfit > tenPercentTarget) {
                    running.closeRequested = true
                    running.closeReason = "Trailing Stop Triggered"
                    val sent = derivApiClient.send(JSONObject().put("sell", contractId).put("price", 0).put("req_id", nextTradeRequestId()))
                    if (!sent) {
                        running.closeRequested = false
                        running.closeReason = ""
                        notifyEvent("Trailing Stop Close Failed", "${running.symbol} · Contract $contractId · Deriv close request could not be sent; monitoring continues.",
                            "trailing_close_failed_${accountId}_${executionMode}_$contractId")
                    } else {
                        notifyEvent("Trailing Stop Triggered", "${running.symbol} · Contract $contractId · Peak P/L ${formatMoney(running.peakProfit)} · Protected level ${formatMoney(protectedProfit)}. Waiting for Deriv confirmation.",
                            "trailing_stop_triggered_${accountId}_${executionMode}_$contractId")
                    }
                }
            } else {
                val finalProfit = contract.optDouble("profit", contract.optDouble("profit_open", liveContractProfits[contractId] ?: 0.0))
                val reason = running.closeReason.ifBlank { if (status == "expired") "Contract Expired" else if (running.takeProfitRequested) "Target Close" else "Contract Closed" }
                runningContracts.remove(contractId)
                pendingIndexSymbols.remove(running.symbol)
                liveContractProfits.remove(contractId)
                liveContractProfitModes.remove(contractId)
                appPrefs.edit().remove("${trailStateKey}_activated").remove("${trailStateKey}_peak").remove("${trailStateKey}_target_notified").apply()
                notifyEvent("Trade Closed", "${running.symbol} · Contract $contractId · Final P/L ${formatMoney(finalProfit)} · Reason: $reason · Deriv status: ${status.uppercase()}.",
                    "trade_closed_${accountId}_${executionMode}_$contractId")
            }
        }
    }
    // ANALYSIS — MARKET DATA STATE
private val marketDataIndexes = listOf(
    "R_10",
    "R_25",
    "R_50",
    "R_75",
    "R_100"
)

private val marketDataCandles =
    mutableMapOf<String, List<DerivApiClient.Candle>>()

private val marketDataPrices =
    mutableMapOf<String, Double>()
    private val marketDataPriceViews = mutableMapOf<String, TextView>()
private val dashboardPriceViews = mutableMapOf<String, TextView>()
private val currentPriceViews = mutableMapOf<String, MutableList<TextView>>()
private val dashboardStatusViews = mutableMapOf<String, TextView>()
private val marketDataErrors =
    mutableMapOf<String, String>()

private var marketDataRequested = false
private var marketStructureExpanded = true
private var mssChochExpanded = true
private var marketDataExpanded = true
private var mssChochBehaviourExpanded = true
private var directionStructuralTargetExpanded = true
private var qualificationExpanded = true
private var ptpeAnalysisExpanded = true
private var targetFilterExpanded = true
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = bg
window.navigationBarColor = bg
window.decorView.systemUiVisibility = 0

platform = appPrefs.getString("platform", "USER") ?: "USER"
executionMode = appPrefs.getString("executionMode", "DEMO") ?: "DEMO"
selectedTab = appPrefs.getString("selectedTab", "Home") ?: "Home"
        if (selectedTab == "Account") selectedTab = "My profile"
authToken = authPrefs.getString("mobile_auth_token", "").orEmpty()

render()
handleOAuthCallback(intent)
if (authToken.isNotBlank()) refreshProfile(showMessage = false)

val savedToken = prefs.getString(
    if (executionMode == "REAL") "real_token" else "demo_token",
    ""
).orEmpty()

if (authToken.isNotBlank() && savedToken.isNotBlank() &&
    !appPrefs.getBoolean("deriv_manually_disconnected", false) &&
    intent?.data?.host != "oauth-callback") {
    connectWithToken(savedToken)
}
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            handleOAuthCallback(intent)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7802) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                notifyEvent("Notifications enabled", "Android permission granted. Trade and risk alerts can now appear.")
            } else {
                toast("Android notification permission was not granted. In-app notification history will still work.")
            }
            if (selectedTab == "Notifications") render()
        }
    }

    private fun render() {
        val previousScrollY =
    if (lastRenderedTab == selectedTab) {
        mainScrollView?.scrollY ?: 0
    } else {
        0
    }
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

        if (drawerOpen) {
            addDrawer()
        }
if (selectedTab == "Analysis") {
    currentPriceViews.clear()
    marketDataPriceViews.clear()
}
when (selectedTab) {
                "Analysis" -> {
    addMarketDataAnalysis()
    addMarketStructureAnalysis()
    addMssChochAnalysis()
    addMssChochBehaviourAnalysis()
    addDirectionStructuralTargetAnalysis()
    addQualificationAnalysis()
    addPtpeAnalysis()
    addTargetFilterAnalysis()
                }

            "Trade" -> {
                if (authToken.isBlank()) {
                    page.addView(sectionTitle("SIGN IN REQUIRED"))
                    page.addView(label("Sign in to your Volatilities Indicator account before accessing trading.", 14f, yellow, false))
                    page.addView(button("SIGN IN", true) { selectedTab = "Login"; render() }, matchWrap())
                } else addTradeScreen()
            }
"Register" -> {
    addRegistrationScreen()
    page.addView(
        button("BACK TO HOME", false) {
            selectedTab = "Home"
            render()
        },
        matchWrap()
    )
}
"My profile", "Account" -> {
    addUserProfileScreen(page)
}
"User Accounts" -> addAdminUsersScreen()
"Risk Categories" -> {
    if (platform == "ADMIN") addRiskCategoriesScreen()
    else { page.addView(label("Only an administrator can view risk categories.", 14f, red, false)) }
}
"Notifications" -> addNotificationsScreen()
"Settings", "System Settings" -> addSettingsScreen()
"Trading Logs" -> addTradingLogsScreen()
"Dashboard" -> {
    if (authToken.isNotBlank()) {
        addPlatformSelector()
        addExecutionSelector()
        addAccountCard()
        addDashboardIndexMonitor()
    } else {
        page.addView(label("Sign in to access your dashboard.", 14f, yellow, false))
        page.addView(button("SIGN IN", true) { selectedTab = "Login"; render() }, matchWrap())
    }
}

"Logout" -> {
    performPlatformLogout()
}
            "Login" -> {
    addLoginScreen()
            }
                    else -> {
                if (authToken.isNotBlank()) {
                    addPlatformSelector()
                    addExecutionSelector()
                    addAccountCard()
                    addDashboardIndexMonitor()
                    if (platform == "USER" && !connected) {
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
                } else {
                    page.addView(label("Sign in to your Volatilities Indicator account to access Deriv trading.", 15f, white, false), matchWrap())
                    page.addView(button("SIGN IN", true) {
                        selectedTab = "Login"
                        render()
                    }, matchWrap())
                }
            }
}

        addBottomNavigation(outer)

        // Restore the previous position after layout settles. Live account callbacks can
        // trigger repeated renders; a delayed restore prevents the REAL account screen jumping.
        scroll.post {
            scroll.postDelayed({
                if (lastRenderedTab == selectedTab && mainScrollView === scroll) {
                    val maxScroll = (page.height - scroll.height).coerceAtLeast(0)
                    scroll.scrollTo(0, previousScrollY.coerceIn(0, maxScroll))
                }
                lastRenderedTab = selectedTab
            }, 120L)
        }
    }
    // =========================================================
// HEADER: EXISTING LOGO AND NOTIFICATION BELL
// =========================================================
// ==========================================
// CREATE ACCOUNT — VOLATILITIES INDICATOR
// ==========================================

private fun addRegistrationScreen() {
    page.addView(sectionTitle("CREATE ACCOUNT"))

    val registrationCard = card().apply { orientation = LinearLayout.VERTICAL }
    registrationCard.addView(label("Register your Volatilities Indicator account", 17f, white, true))
    registrationCard.addView(label("Your application will remain pending until an administrator approves it.", 14f, yellow, false))

    fun textInput(hintText: String, inputType: Int = android.text.InputType.TYPE_CLASS_TEXT): android.widget.EditText {
        return android.widget.EditText(this).apply {
            hint = hintText
            this.inputType = inputType
            setSingleLine(true)
            setTextColor(white)
            setHintTextColor(muted)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(green))
        }
    }
    val nameInput = textInput("Name")
    val surnameInput = textInput("Surname")
    val emailInput = textInput("Email address", android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
    val phoneInput = textInput("Contact number", android.text.InputType.TYPE_CLASS_PHONE)
    registrationCard.addView(nameInput)
    registrationCard.addView(surnameInput)
    registrationCard.addView(emailInput)
    registrationCard.addView(phoneInput)

    fun addPasswordField(title: String): Pair<LinearLayout, android.widget.EditText> {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val input = textInput(title, android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val eyeButton = button("SHOW", false)
        var visible = false
        eyeButton.setOnClickListener {
            val cursorPosition = input.selectionStart
            visible = !visible
            input.inputType = if (visible) android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            eyeButton.text = if (visible) "HIDE" else "SHOW"
            if (cursorPosition >= 0) input.setSelection(cursorPosition.coerceAtMost(input.text.length))
        }
        row.addView(input)
        row.addView(eyeButton, LinearLayout.LayoutParams(dp(76), dp(48)))
        registrationCard.addView(row)
        return Pair(row, input)
    }
    val (_, passwordInput) = addPasswordField("Password")
    val (_, confirmPasswordInput) = addPasswordField("Confirm password")
    val submit = button("CREATE ACCOUNT", true)
    submit.setOnClickListener {
        val name = nameInput.text.toString().trim()
        val surname = surnameInput.text.toString().trim()
        val email = emailInput.text.toString().trim()
        val phone = phoneInput.text.toString().trim()
        val password = passwordInput.text.toString()
        val confirmPassword = confirmPasswordInput.text.toString()
        when {
            name.isBlank() -> toast("Enter your name.")
            surname.isBlank() -> toast("Enter your surname.")
            email.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches() -> toast("Enter a valid email address.")
            phone.trim().length < 7 -> toast("Enter a valid contact number.")
            password.length < 8 -> toast("Password must contain at least 8 characters.")
            password != confirmPassword -> toast("Passwords do not match.")
            else -> {
                submit.isEnabled = false
                submit.text = "SUBMITTING APPLICATION…"
                val registrationGeneration = authSessionGeneration
                authRequest("register", mapOf(
                    "first_name" to name,
                    "surname" to surname,
                    "email" to email,
                    "phone" to phone,
                    "password" to password,
                    "password_confirm" to confirmPassword
                ), token = "") { ok, result ->
                    if (registrationGeneration != authSessionGeneration) return@authRequest
                    submit.isEnabled = true
                    submit.text = "CREATE ACCOUNT"
                    if (ok) {
                        val message = result.optString("message", "Application submitted. Await administrator approval.")
                        val token = result.optString("token", "")
                        if (token.isNotBlank()) {
                            authSessionGeneration++
                            profileLoading = false
                            currentProfile = null
                            adminUsers = null
                            adminUsersLoading = false
                            authToken = token
                            authPrefs.edit().putString("mobile_auth_token", token).apply()
                            currentProfile = result.optJSONObject("user")
                            selectedTab = "My profile"
                        } else {
                            selectedTab = "Login"
                        }
                        drawerOpen = false
                        render()
                        toast(message)
                    } else {
                        toast(result.optString("message", "Registration failed. Please try again."))
                    }
                }
            }
        }
    }
    registrationCard.addView(submit, matchWrap().apply { topMargin = dp(12) })
    page.addView(registrationCard, matchWrap())
}
// SIGN IN — uses the same user records and approval status as WebTrader.
private fun addLoginScreen() {
    page.addView(sectionTitle("SIGN IN"))
    val loginCard = card().apply { orientation = LinearLayout.VERTICAL }
    loginCard.addView(label("Welcome back", 20f, green, true))
    loginCard.addView(label("Sign in with the email address and password used for your Volatilities Indicator account.", 14f, muted, false))

    val emailInput = android.widget.EditText(this).apply {
        hint = "Email address"
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        setSingleLine(true); setTextColor(white); setHintTextColor(muted)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setBackgroundTintList(android.content.res.ColorStateList.valueOf(green))
    }
    val passwordInput = android.widget.EditText(this).apply {
        hint = "Password"
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        setSingleLine(true); setTextColor(white); setHintTextColor(muted)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setBackgroundTintList(android.content.res.ColorStateList.valueOf(green))
    }
    loginCard.addView(emailInput)
    loginCard.addView(passwordInput)
    val signIn = button("SIGN IN", true)
    signIn.setOnClickListener {
        val email = emailInput.text.toString().trim()
        val password = passwordInput.text.toString()
        when {
            email.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches() -> toast("Enter a valid email address.")
            password.isBlank() -> toast("Enter your password.")
            else -> {
                signIn.isEnabled = false
                signIn.text = "SIGNING IN…"
                val loginGeneration = authSessionGeneration
                authRequest("login", mapOf("email" to email, "password" to password), token = "") { ok, result ->
                    if (loginGeneration != authSessionGeneration) return@authRequest
                    signIn.isEnabled = true
                    signIn.text = "SIGN IN"
                    if (ok) {
                        // Invalidate any profile/admin response from the previous user.
                        authSessionGeneration++
                        profileLoading = false
                        currentProfile = null
                        adminUsers = null
                        adminUsersLoading = false
                        authToken = result.optString("token", "")
                        authPrefs.edit().putString("mobile_auth_token", authToken).apply()
                        currentProfile = result.optJSONObject("user")
                        val role = currentProfile?.optString("role", "user") ?: "user"
platform = if (role.equals("admin", ignoreCase = true)) "ADMIN" else "USER"
                        selectedTab = "My profile"
                        drawerOpen = false
                        render()
                        refreshProfile(showMessage = false)
                        toast("Login successful.")
                    } else {
                        toast(result.optString("message", "Unable to sign in."))
                    }
                }
            }
        }
    }
    loginCard.addView(signIn, matchWrap().apply { topMargin = dp(12) })
    loginCard.addView(button("CREATE ACCOUNT", false) {
        selectedTab = "Register"; drawerOpen = false; render()
    }, matchWrap().apply { topMargin = dp(8) })
    loginCard.addView(button("BACK TO HOME", false) { selectedTab = "Home"; render() }, matchWrap().apply { topMargin = dp(6) })
    page.addView(loginCard, matchWrap())
}

private fun addUserProfileScreen(page: LinearLayout) {
    val profileCard = card().apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(20), dp(20), dp(20))
    }
    val profileLogo = ImageView(this).apply {
        setImageResource(R.drawable.volatilities_indicator_logo)
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = "Volatilities Indicator logo"
    }
    profileCard.addView(
        profileLogo,
        LinearLayout.LayoutParams(dp(96), dp(96)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(12)
        }
        )
    profileCard.addView(label("MY PROFILE", 22f, green, true).apply {
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, dp(16))
    })
    if (authToken.isBlank()) {
        profileCard.addView(label("Sign in to view your account profile.", 15f, muted, false))
        profileCard.addView(button("SIGN IN", true) { selectedTab = "Login"; render() }, matchWrap())
        profileCard.addView(button("CREATE ACCOUNT", false) { selectedTab = "Register"; render() }, matchWrap())
    } else if (currentProfile == null) {
        profileCard.addView(label(if (profileLoading) "Loading your profile…" else "Your profile has not loaded yet.", 15f, yellow, false))
        profileCard.addView(button("LOAD PROFILE", true) { refreshProfile(showMessage = true) }, matchWrap())
    } else {
        val u = currentProfile!!
        fun profileField(title: String, value: String) {
            profileCard.addView(label(title.uppercase(), 12f, green, true))
            profileCard.addView(label(value.ifBlank { "Not assigned" }, 16f, yellow, false))
        }
        val fullName = u.optString("full_name", "")
        val firstName = u.optString("first_name", fullName.substringBefore(" "))
        val surname = u.optString("surname", fullName.substringAfter(" ", ""))
        profileField("Name", firstName)
        profileField("Surname", surname)
        profileField("Email address", u.optString("email", ""))
        profileField("Contact number", u.optString("phone", ""))
        val risk = u.optString("risk_profile", "")
        val riskPercent = u.optInt("risk_percent", 0)
        val riskText = if (risk.isBlank() || risk == "null") "Not assigned" else "${risk.replaceFirstChar { it.uppercase() }} risk${if (riskPercent > 0) " — $riskPercent% stake" else ""}"
        profileField("Risk plan", riskText)
        val riskKey = risk.lowercase().replace("_", " ").replace("-", " ")
        val subscriptionText = when (riskKey) {
            "minimum" -> "25% of capital"
            "medium" -> "75% of capital"
            "maximum" -> "100% of capital"
            "super maximum", "privilege" -> "As assigned by administrator"
            "trial" -> "Free"
            else -> "As assigned by administrator"
        }
        val periodText = if (riskKey == "trial") "7 days" else if (riskKey.isBlank() || riskKey == "null") "Not assigned" else "30 days"
        profileField("Interest per period", when (riskKey) {
            "minimum" -> "50% per 30 days"
            "medium" -> "100% per 30 days"
            "maximum" -> "200% per 30 days"
            "trial" -> "50% per 7 days"
            else -> "As assigned by administrator"
        })
        profileField("Subscription amount", subscriptionText)
        profileField("Period", periodText)
        profileField("Trading capital range", "R1,000 to R5,000")
        profileField("Target profit", "20% of stake")
        val riskLimitText = when (risk.lowercase()) {
            "minimum" -> "1 trade per day"
            "medium" -> "2 trades per day"
            "maximum" -> "3 trades per day"
            "super maximum", "super_maximum", "super-maximum", "privilege" -> "Unlimited trades per day"
            "trial" -> "Trade limit not configured"
            else -> "Trading disabled until assigned"
        }
        profileField("Risk-plan trade limit", riskLimitText)
        profileField("Daily fixed stake", "20% of the account balance frozen at 00:00 South African time")
        profileField("Trades used today", "${dailyTradeCount()} / ${if (maxTradesPerDay() == Int.MAX_VALUE) "Unlimited" else maxTradesPerDay()}")
        profileField("Start of risk plan", u.optString("start_date", "").takeUnless { it == "null" }.orEmpty())
        profileField("Expiry of risk plan", u.optString("expiry_date", "").takeUnless { it == "null" }.orEmpty())
        val status = u.optString("status", "approved").uppercase()
        profileField("Account status", status)
        if (connected && accountId.isNotBlank()) {
            profileCard.addView(label("APPLY FOR A RISK PLAN", 14f, green, true).apply { setPadding(0, dp(12), 0, dp(4)) })
            val riskOptions = listOf("Minimum — 25% of capital / 30 days", "Medium — 75% of capital / 30 days", "Maximum — 100% of capital / 30 days", "Super Maximum — 30 days", "Trial — Free / 7 days")
            val riskValues = listOf("minimum", "medium", "maximum", "super_maximum", "trial")
            val riskSpinner = Spinner(this).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, riskOptions)
            }
            profileCard.addView(riskSpinner, matchWrap())
            profileCard.addView(label("Trading capital range: R1,000–R5,000 • Stake: 20% of daily starting capital • Target profit: 20% of stake. Applying does not activate a plan; the administrator must verify payment where required and assign the plan and dates.", 12f, muted, false).apply { setPadding(0, dp(5), 0, dp(5)) })
            profileCard.addView(button("SUBMIT RISK-PLAN APPLICATION", true) {
                val chosen = riskValues[riskSpinner.selectedItemPosition]
                val fee = when (chosen) { "minimum" -> "25% of capital"; "medium" -> "75% of capital"; "maximum" -> "100% of capital"; "super_maximum" -> "0"; else -> "0" }
                val days = if (chosen == "trial") "7" else "30"
                val applicationFields = mapOf(
                    "risk_profile" to chosen,
                    "plan_id" to "${chosen}_${days}_days",
                    "period_days" to days,
                    "subscription_amount" to fee,
                    "deriv_account_id" to accountId,
                    "payment_status" to if (fee == "0") "not_required_or_pending_admin_review" else "awaiting_payment"
                )
                authRequest("risk_application", applicationFields) { ok, result ->
                    if (ok) {
                        notifyEvent("Risk-plan application submitted", "Application submitted for ${riskOptions[riskSpinner.selectedItemPosition]}. The administrator must verify payment where required and assign the plan.")
                        toast(result.optString("message", "Application submitted. Await administrator assignment."))
                    } else {
                        toast(result.optString("message", "Risk-plan applications are not yet enabled by the server. No plan has been activated."))
                    }
                }
            }, matchWrap().apply { topMargin = dp(6) })
        } else {
            profileCard.addView(label("Connect your Deriv account to choose a risk plan.", 12f, yellow, false).apply { setPadding(0, dp(10), 0, dp(4)) })
        }
        profileCard.addView(button("REFRESH PROFILE", false) { refreshProfile(showMessage = true) }, matchWrap().apply { topMargin = dp(8) })
        profileCard.addView(button("LOG OUT", true) { performPlatformLogout() }, matchWrap().apply { topMargin = dp(8) })
    }
    page.addView(profileCard, matchWrap())
}

private fun authRequest(
    action: String,
    fields: Map<String, String> = emptyMap(),
    token: String = authToken,
    callback: (Boolean, JSONObject) -> Unit
) {
    val form = FormBody.Builder().add("action", action).apply {
        fields.forEach { (key, value) -> add(key, value) }
    }.build()
    val requestBuilder = Request.Builder()
        .url(authEndpoint)
        .post(form)
        .header("Accept", "application/json")
        .header("X-VI-Mobile-App", "1")
    if (token.isNotBlank()) requestBuilder.header("Authorization", "Bearer $token")
    httpClient.newCall(requestBuilder.build()).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            runOnUiThread { callback(false, JSONObject().put("message", "Cannot reach the registration service. Check your internet connection and try again.")) }
        }
        override fun onResponse(call: Call, response: Response) {
            val raw = response.body?.string().orEmpty()
            val result = try { JSONObject(raw) } catch (_: Exception) { JSONObject().put("message", "The server returned an invalid response. Please contact the administrator.") }
            val ok = response.isSuccessful && result.optBoolean("ok", false)
            response.close()
            runOnUiThread { callback(ok, result) }
        }
    })
}
private fun refreshProfile(showMessage: Boolean) {
    if (authToken.isBlank()) {
        currentProfile = null
        selectedTab = "Login"
        render()
        return
    }
    if (profileLoading) return
    val requestedToken = authToken
    val requestedGeneration = authSessionGeneration
    profileLoading = true
    authRequest("profile", token = requestedToken) { ok, result ->
        if (requestedGeneration != authSessionGeneration || requestedToken != authToken) return@authRequest
        profileLoading = false
        if (ok) {
            currentProfile = result.optJSONObject("user")
            val role = currentProfile?.optString("role", "user") ?: "user"
            platform = if (role.equals("admin", true)) "ADMIN" else "USER"
            if (selectedTab == "My profile" || selectedTab == "Account" || selectedTab == "Notifications" || selectedTab == "Settings") render()
            if (showMessage) toast("Profile updated.")
        } else {
            authSessionGeneration++
            authToken = ""
            currentProfile = null
            profileLoading = false
            adminUsers = null
            adminUsersLoading = false
            authPrefs.edit().remove("mobile_auth_token").apply()
            platform = "USER"
            selectedTab = "Login"
            render()
            toast(result.optString("message", "Please sign in again."))
        }
    }
}

private fun performPlatformLogout() {
    val token = authToken
    // Invalidate every in-flight request before clearing the old user's identity.
    authSessionGeneration++
    profileLoading = false
    currentProfile = null
    adminUsers = null
    adminUsersLoading = false
    // Clear Deriv session and both saved OAuth tokens so another user on this device
    // cannot reconnect to the previous user's Deriv account.
    disconnectDeriv(clearSavedTokens = true)
    authToken = ""
    authPrefs.edit().remove("mobile_auth_token").apply()
    platform = "USER"
    selectedTab = "Login"
    drawerOpen = false
    render()
    if (token.isNotBlank()) authRequest("logout", token = token) { _, _ -> }
    toast("You have been signed out.")
}
private fun addHeader() {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(2), dp(4), dp(2), dp(4))
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

        val bell = TextView(this).apply {
            text = if (notificationsEnabled) "🔔" else "♧"
            textSize = 25f
            setTextColor(if (notificationsEnabled) green else white)
            gravity = Gravity.CENTER
            contentDescription = "Notifications"
            setOnClickListener { selectedTab = "Notifications"; drawerOpen = false; render() }
        }

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
                "News",
                "My profile",
                "Logout"
            )
        }

        items.forEach { item ->
            val b = button("  $item", false)

            b.gravity = Gravity.START or Gravity.CENTER_VERTICAL

            b.setOnClickListener {
                if (item == "Logout") {
                    performPlatformLogout()
                } else {
                    drawerOpen = false
                    selectedTab = item
                    render()
                }
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
    // Only an authenticated administrator may see this section.
        if (currentProfile?.optString("role", "user")
        .equals("admin", ignoreCase = true).not()) {
    platform = "USER"
    return
        }

    page.addView(sectionTitle("PLATFORM"))

    val row = horizontalRow()

    row.addView(
        button("♟ USER", platform == "USER") {
            platform = "USER"
            drawerOpen = false
            render()
        },
        weighted()
    )

    row.addView(space(dp(8)))

    row.addView(
        button("♟♟ ADMIN", platform == "ADMIN") {
            platform = "ADMIN"
            drawerOpen = false
            render()
        },
        weighted()
    )

    page.addView(row)
    }

    private fun resetAccountScopedTradeData() {
        tradeHistoryResponse = null
        openTradesResponse = null
        portfolioLoadedForAccount = false
        lastOpenTradesRequestAt = 0L
        openContractResponse = null
        liveContractProfits.clear()
        liveContractProfitModes.clear()
        historyAccountKey = ""
    }

    private fun formatMoney(value: Double): String = String.format(java.util.Locale.US, "%.2f", value)
    private fun profitColor(value: Double): Int = when { value > 0.000001 -> green; value < -0.000001 -> red; else -> white }

    // =========================================================
    // EXECUTION MODE SELECTOR
    // =========================================================

    private fun addExecutionSelector() {
        page.addView(sectionTitle("EXECUTION MODE"))

        val row = horizontalRow()
        row.addView(
    button("⌁  DEMO", executionMode == "DEMO") {
        if (executionMode != "DEMO") resetAccountScopedTradeData()
        executionMode = "DEMO"
        render()
        beginDerivConnection()
    },
    weighted()
)
        
        row.addView(space(dp(8)))

        row.addView(
    button("▥  REAL", executionMode == "REAL") {
    if (executionMode != "REAL") {
        resetAccountScopedTradeData()
        executionMode = "REAL"
        render()
        beginDerivConnection()
    }
},
    weighted()
)

        page.addView(row)
    }

    // Five-index live monitor shown on the dashboard.
    private fun addDashboardIndexMonitor() {
        page.addView(sectionTitle("INDEX MONITOR"))
        val monitorCard = card().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        marketDataIndexes.forEach { symbol ->
            val accent = when (symbol) {
                "R_10" -> cyan
                "R_25" -> green
                "R_50" -> yellow
                "R_75" -> red
                else -> Color.rgb(190, 75, 255)
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = shape(panel, accent, 10, 1)
            }
            row.addView(label(symbol, 17f, accent, true), LinearLayout.LayoutParams(dp(78), -2))
            val price = marketDataPrices[symbol]
            val priceView = label(price?.let { formatMarketPrice(it) } ?: "PRICE WAITING", 12f, muted, false)
            dashboardPriceViews[symbol] = priceView
            row.addView(priceView, LinearLayout.LayoutParams(0, -2, 1f))
            val initialStatus = dashboardDecisionStatus(symbol)
            val statusView = label(initialStatus.first, 10f, initialStatus.second, true)
            dashboardStatusViews[symbol] = statusView
            row.addView(statusView)
            row.setOnClickListener { selectedTab = "Analysis"; render() }
            monitorCard.addView(row, matchWrap().apply { bottomMargin = dp(5) })
        }
        monitorCard.addView(label("Select an index to open its detailed market analysis.", 11f, muted, false))
        page.addView(monitorCard, matchWrap().apply { bottomMargin = dp(12) })
        if (connected && !marketDataRequested) {
            marketDataRequested = true
            derivApiClient.requestHistoricalCandles()
        }
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

        val selectedModePl = liveContractProfits.filterKeys { liveContractProfitModes[it] == "${accountId}_$executionMode" }.values.sum()
        openPlView = label("Open P/L:  ${formatMoney(selectedModePl)}", 14f,
            profitColor(selectedModePl), true)
        card.addView(openPlView, matchWrap().apply { topMargin = dp(4) })

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
                    derivApiClient.requestBalance()
                    derivApiClient.requestOpenTrades()
                    derivApiClient.requestTradeHistory()
                    accountStatus.text = "●  REFRESHING — $executionMode"
                    accountStatus.setTextColor(yellow)
                    toast("Refreshing Deriv balance, open trades and history…")
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
                            private fun ptpeDisplayText(symbol: String): String {
    val ptpe = lastPtpeByIndex[symbol] ?: PtpeDisplay()
    val targetText = ptpe.target?.let { String.format(java.util.Locale.US, "%.3f", it) } ?: "—"
    val distanceText = ptpe.distancePercent?.let { String.format(java.util.Locale.US, "%.2f%%", it) } ?: "—"
    return "$symbol • ${ptpe.direction} • Target $targetText • Distance $distanceText • PTPE ${String.format(java.util.Locale.US, "%.1f", ptpe.probability)}% (${ptpe.samples} samples) • ${ptpe.status}"
                            }

                            private fun dashboardDecisionStatus(symbol: String): Pair<String, Int> {
    val decision = lastDecisionByIndex[symbol]
    if (decision != null) return "QUALIFIED ${decision.direction}" to green
    if (marketDataErrors.containsKey(symbol) && marketDataPrices[symbol] == null) return "FEED ERROR" to red
    val status = lastPtpeByIndex[symbol]?.status ?: "WAITING FOR MARKET DATA"
    val shortStatus = when {
        status.contains("100+ CANDLES") -> "HISTORY WAIT"
        status.contains("LIVE PRICE") -> "PRICE WAIT"
        status.contains("MSS/CHOCH") -> "MSS WAIT"
        status.contains("STRUCTURAL TARGET") -> "TARGET WAIT"
        status.contains("INVALID TARGET") -> "INVALID TARGET"
        status.contains("DISTANCE BELOW") -> "DISTANCE <1.5%"
        status.contains("INSUFFICIENT PTPE") -> "PTPE SAMPLES"
        status.contains("PTPE BELOW") -> "PTPE <55%"
        status.contains("NO PRIOR") -> "NO PRIOR EVENT"
        status.contains("ERROR") -> "DATA ERROR"
        else -> "DATA WAIT"
    }
    return shortStatus to yellow
}

private fun updatePtpeDisplay(symbol: String) {
    val qualified = lastDecisionByIndex.containsKey(symbol)
    ptpeStatusViews[symbol]?.apply {
        text = ptpeDisplayText(symbol)
        setTextColor(if (qualified) green else yellow)
    }
    // Do not return when the Trade-tab view is absent: dashboard status must update
    // while Home is open, otherwise all indexes remain stuck on WAITING.
    val (statusText, statusColor) = dashboardDecisionStatus(symbol)
    dashboardStatusViews[symbol]?.apply {
        text = statusText
        setTextColor(statusColor)
    }
}

// =========================================================
// TRADE SCREEN
// =========================================================
private fun addTradeScreen() {
    ptpeStatusViews.clear()
    contractLiveViews.clear()
    marketDataIndexes.forEach { evaluateMasterDecision(it) }
    page.addView(sectionTitle("TRADE"))

    val accountCard = card().apply { orientation = LinearLayout.VERTICAL }
    accountCard.addView(label("EXECUTION ACCOUNT", 14f, green, true))
    accountCard.addView(label("Mode: $executionMode", 13f, white, true))
    accountCard.addView(label("Connection: ${if (connected) "CONNECTED" else "DISCONNECTED"}", 12f, if (connected) green else red, true))
    accountCard.addView(label("Account ID: ${accountId.ifBlank { "—" }}", 12f, muted, false))
    if (platform == "ADMIN") {
        val latestTarget = lastDecisionByIndex.values.lastOrNull()?.target
        accountCard.addView(label("Target barrier: ${latestTarget?.let { formatMarketPrice(it) } ?: "Waiting for a qualifying Master Decision"}", 12f, yellow, true))
    }
    page.addView(accountCard, matchWrap().apply { bottomMargin = dp(10) })

    val decisionCard = card().apply { orientation = LinearLayout.VERTICAL }
    decisionCard.addView(label(if (autoTradeEnabled) "MASTER DECISION: ON" else "MASTER DECISION: OFF", 15f, if (autoTradeEnabled) green else red, true))
    decisionCard.addView(button(if (autoTradeEnabled) "TURN MASTER DECISION OFF" else "TURN MASTER DECISION ON", autoTradeEnabled) {
        autoTradeEnabled = !autoTradeEnabled
        if (autoTradeEnabled && !connected) toast("Master Decision is ON, but execution waits until Deriv is connected.")
        render()
    }, matchWrap().apply { topMargin = dp(8) })
    page.addView(decisionCard, matchWrap().apply { bottomMargin = dp(10) })

    val trailingCard = card().apply { orientation = LinearLayout.VERTICAL }
    trailingCard.addView(label("STANDALONE TRAILING STOP", 15f, green, true))
    trailingCard.addView(label("Activation: +10% of original stake · Trailing gap: 10% of original stake · Break-even floor before trailing gains", 12f, muted, false).apply { setPadding(0, dp(5), 0, dp(5)) })
    trailingCard.addView(label("Status: ${if (trailingStopEnabled) "ON — managing eligible open trades" else "OFF — monitoring only"}", 12f, if (trailingStopEnabled) green else yellow, true))
    trailingCard.addView(button(if (trailingStopEnabled) "TURN TRAILING STOP OFF" else "TURN TRAILING STOP ON", trailingStopEnabled) {
        trailingStopEnabled = !trailingStopEnabled
        if (trailingStopEnabled) notifyEvent("Trailing Stop Enabled", "Standalone trailing stop is ON. It will manage eligible open trades after the 10% activation threshold.", "trailing_stop_enabled_${accountId}_${executionMode}")
        else notifyEvent("Trailing Stop Disabled", "Standalone trailing stop is OFF. Open trades will remain open unless closed manually or by another applicable contract rule.", "trailing_stop_disabled_${accountId}_${executionMode}_${System.currentTimeMillis()}")
        render()
    }, matchWrap().apply { topMargin = dp(8) })
    page.addView(trailingCard, matchWrap().apply { bottomMargin = dp(10) })

    page.addView(sectionTitle("RUNNING TRADES"))
    val runningCard = card().apply { orientation = LinearLayout.VERTICAL }
    val portfolioArray = openTradesResponse?.optJSONObject("portfolio")?.optJSONArray("contracts")
        ?: openTradesResponse?.optJSONArray("contracts")
    var visibleRunning = 0
    val renderedContractIds = mutableSetOf<Long>()
    if (portfolioArray != null) for (i in 0 until portfolioArray.length()) {
        val item = portfolioArray.optJSONObject(i) ?: continue
        val contractId = item.optLong("contract_id", 0L)
        if (contractId <= 0L) continue
        renderedContractIds.add(contractId)
        val tracked = runningContracts[contractId]
        val symbol = item.optString("underlying", item.optString("underlying_symbol", item.optString("symbol", tracked?.symbol ?: "—")))
        val stake = item.optDouble("buy_price", tracked?.stake ?: 0.0)
        val currentValue = item.optDouble("current_spot", item.optDouble("current_spot_display_value", item.optDouble("bid_price", 0.0)))
        val expiryEpoch = item.optLong("date_expiry", item.optLong("expiry_time", 0L))
        val expiryText = if (expiryEpoch > 0L) {
            val remain = (expiryEpoch - System.currentTimeMillis() / 1000L).coerceAtLeast(0L)
            String.format(java.util.Locale.US, "%02d:%02d:%02d", remain / 3600, (remain % 3600) / 60, remain % 60)
        } else "—"
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = shape(panel, greenDark, 8, 1)
        }
        row.addView(label("Index: $symbol", 14f, green, true))
        row.addView(label("Contract number: $contractId", 12f, white, false).apply { setPadding(0, dp(4), 0, 0) })
        row.addView(label("Stake: ${formatMoney(stake)}", 12f, white, false).apply { setPadding(0, dp(4), 0, 0) })
        val initialPnl = liveContractProfits[contractId] ?: 0.0
        val pnlHidden = label("P/L: ${formatMoney(initialPnl)}", 12f, profitColor(initialPnl), true).apply { setPadding(0, dp(4), 0, 0) }
        val targetPrice = tracked?.target
        val targetView = label("Structure target: ${targetPrice?.takeIf { it.isFinite() && it > 0.0 }?.let { formatMarketPrice(it) } ?: "Not available"}", 12f, yellow, true).apply { setPadding(0, dp(4), 0, 0) }
        val profitTargetView = label("20% profit target: ${formatMoney(stake * 0.20)}", 12f, yellow, true).apply { setPadding(0, dp(4), 0, 0) }
        val trailView = label("Trailing stop: ${if (tracked?.trailingActivated == true) "ACTIVATED" else if (trailingStopEnabled) "ARMED" else "OFF"}", 12f, if (trailingStopEnabled) green else muted, true).apply { setPadding(0, dp(4), 0, 0) }
        val spotView = label("Current value/spot: ${if (currentValue > 0) formatMarketPrice(currentValue) else "—"}", 12f, white, false).apply { setPadding(0, dp(4), 0, 0) }
        val expiryView = label("Expiry: $expiryText", 12f, white, false).apply { setPadding(0, dp(4), 0, 0) }
        val ptpeHidden = label("", 1f, white, false)
        row.addView(pnlHidden)
        row.addView(profitTargetView)
        row.addView(targetView)
        row.addView(trailView)
        row.addView(spotView)
        row.addView(expiryView)
        contractLiveViews[contractId] = ContractLiveViews(stake, symbol, tracked?.target, tracked?.targetProbability ?: 0.0, pnlHidden, spotView, expiryView, ptpeHidden)
        row.addView(button("CLOSE TRADE", true) {
            if (!connected) {
                toast("Connect to the account holding this contract.")
            } else {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Confirm closing trade")
                    .setMessage("Close contract $contractId for $symbol? The final sale value and profit/loss may differ from the current value.")
                    .setNegativeButton("CANCEL", null)
                    .setPositiveButton("CLOSE TRADE") { _, _ ->
                        val sent = derivApiClient.send(JSONObject().put("sell", contractId).put("price", 0).put("req_id", nextTradeRequestId()))
                        if (!sent) toast("Unable to send close request.") else toast("Close request sent for contract $contractId.")
                    }
                    .show()
            }
        }, matchWrap().apply { topMargin = dp(8) })
        runningCard.addView(row, matchWrap().apply { topMargin = dp(7) })
        visibleRunning++
    }
    runningContracts.values.filter { it.mode == executionMode && it.contractId !in renderedContractIds }.forEach { running ->
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = shape(panel, greenDark, 8, 1)
        }
        row.addView(label("Index: ${running.symbol}", 14f, green, true))
        row.addView(label("Contract number: ${running.contractId}", 12f, white, false).apply { setPadding(0, dp(4), 0, 0) })
        row.addView(label("Stake: ${formatMoney(running.stake)}", 12f, white, false).apply { setPadding(0, dp(4), 0, 0) })
        val initialPnl = liveContractProfits[running.contractId] ?: 0.0
        val pnlHidden = label("P/L: ${formatMoney(initialPnl)}", 12f, profitColor(initialPnl), true).apply { setPadding(0, dp(4), 0, 0) }
        val targetView = label("Structure target: ${running.target.takeIf { it.isFinite() && it > 0.0 }?.let { formatMarketPrice(it) } ?: "Not available"}", 12f, yellow, true).apply { setPadding(0, dp(4), 0, 0) }
        val profitTargetView = label("20% profit target: ${formatMoney(running.stake * 0.20)}", 12f, yellow, true).apply { setPadding(0, dp(4), 0, 0) }
        val trailView = label("Trailing stop: ${if (running.trailingActivated) "ACTIVATED" else if (trailingStopEnabled) "ARMED" else "OFF"}", 12f, if (trailingStopEnabled) green else muted, true).apply { setPadding(0, dp(4), 0, 0) }
        val spotView = label("Current value/spot: —", 12f, white, false).apply { setPadding(0, dp(4), 0, 0) }
        val expiryView = label("Expiry: —", 12f, white, false).apply { setPadding(0, dp(4), 0, 0) }
        val ptpeHidden = label("", 1f, white, false)
        row.addView(pnlHidden); row.addView(profitTargetView); row.addView(targetView); row.addView(trailView); row.addView(spotView); row.addView(expiryView)
        contractLiveViews[running.contractId] = ContractLiveViews(running.stake, running.symbol, running.target, running.targetProbability, pnlHidden, spotView, expiryView, ptpeHidden)
        row.addView(button("CLOSE TRADE", true) {
            if (!connected) {
                toast("Connect to the account holding this contract.")
            } else {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Confirm closing trade")
                    .setMessage("Close contract ${running.contractId} for ${running.symbol}? The final sale value and profit/loss may differ from the current value.")
                    .setNegativeButton("CANCEL", null)
                    .setPositiveButton("CLOSE TRADE") { _, _ ->
                        val sent = derivApiClient.send(JSONObject().put("sell", running.contractId).put("price", 0).put("req_id", nextTradeRequestId()))
                        if (!sent) toast("Unable to send close request.") else toast("Close request sent for contract ${running.contractId}.")
                    }
                    .show()
            }
            }, matchWrap().apply { topMargin = dp(8) })
        runningCard.addView(row, matchWrap().apply { topMargin = dp(7) })
        visibleRunning++
    }
    if (visibleRunning == 0) runningCard.addView(label("No open contracts for the selected account.", 12f, yellow, false))
    val totalOpenPl = liveContractProfits.filterKeys { liveContractProfitModes[it] == "${accountId}_$executionMode" }.values.sum()
    val totalPlView = label("TOTAL OPEN P/L: ${formatMoney(totalOpenPl)}", 15f, profitColor(totalOpenPl), true).apply { setPadding(0, dp(10), 0, dp(4)) }
    runningTotalPlView = totalPlView
    runningCard.addView(totalPlView)
    runningCard.addView(button("REFRESH RUNNING TRADES AND HISTORY", false) {
        if (connected) { derivApiClient.requestOpenTrades(); derivApiClient.requestTradeHistory() } else toast("Connect to Deriv first.")
    }, matchWrap().apply { topMargin = dp(8) })
    page.addView(runningCard, matchWrap().apply { bottomMargin = dp(10) })

    page.addView(sectionTitle("TRADING HISTORY"))
    val historyCard = card().apply { orientation = LinearLayout.VERTICAL }
    historyCard.addView(label("Account mode: $executionMode", 12f, green, true))
    val historyArray = tradeHistoryResponse?.optJSONObject("profit_table")?.optJSONArray("transactions")
        ?: tradeHistoryResponse?.optJSONArray("transactions")
    var historyCount = 0
    if (historyArray != null) for (i in 0 until minOf(historyArray.length(), 20)) {
        val item = historyArray.optJSONObject(i) ?: continue
        val symbol = item.optString("underlying", item.optString("symbol", "—"))
        val contractId = item.optLong("contract_id", 0L)
        val buyPrice = item.optDouble("buy_price", 0.0)
        val sellPrice = item.optDouble("sell_price", 0.0)
        val profit = sellPrice - buyPrice
        val epoch = item.optLong("purchase_time", item.optLong("date_start", 0L))
        val timeText = if (epoch > 0) java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(epoch * 1000L)) else "Time unavailable"
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(9), dp(10), dp(9)); background = shape(panel, greenDark, 7, 1) }
        row.addView(label("$symbol • Contract $contractId", 12f, white, true))
        row.addView(label("$timeText • Stake ${formatMoney(buyPrice)} • P/L ${formatMoney(profit)}", 11f, profitColor(profit), false).apply { setPadding(0, dp(4), 0, 0) })
        historyCard.addView(row, matchWrap().apply { topMargin = dp(6) }); historyCount++
    }
    if (historyCount == 0) historyCard.addView(label("No completed trade-history records were returned by Deriv yet.", 12f, yellow, false))
    page.addView(historyCard, matchWrap().apply { bottomMargin = dp(10) })
    page.addView(button("NOTIFICATIONS", false) { selectedTab = "Notifications"; render() }, matchWrap().apply { bottomMargin = dp(6) })
    page.addView(button("MY PROFILE", false) { selectedTab = "My profile"; render() }, matchWrap())
}
// =========================================================
// WEBTRADER-PARITY ANALYSIS CORE
// =========================================================
private data class AnalysisPivot(
    val type: String,
    val price: Double,
    val epoch: Long,
    val index: Int,
    val confirmedEpoch: Long,
    var label: String = ""
)

private data class AnalysisMssEvent(
    val state: String,
    val direction: String,
    val broken: AnalysisPivot,
    val confirmation: DerivApiClient.Candle
)

private data class AnalysisHistoricalOutcome(
    val direction: String,
    val pivot: AnalysisPivot,
    val confirmation: DerivApiClient.Candle,
    val index: Int,
    val maxFav: Double,
    val maxFavPrice: Double,
    val maxFavEpoch: Long,
    val maxAdv: Double,
    val maxAdvPrice: Double,
    val maxAdvEpoch: Long,
    val endedByOpposite: Boolean
)

/* Matches WebTrader mssCompleted()/csCompleted(): sort valid H1 candles,
   remove the live candle using the same one-hour age test, and use the
   last-candle fallback only when fewer than two candles pass that test. */
private fun analysisCompletedCandles(symbol: String): List<DerivApiClient.Candle> {
    val all = marketDataCandles[symbol].orEmpty()
        .filter { candle ->
            listOf(candle.epoch.toDouble(), candle.open, candle.high, candle.low, candle.close).all { it.isFinite() }
        }
        .sortedBy { it.epoch }
    if (all.size < 2) return all
    val now = System.currentTimeMillis() / 1000L
    val completed = all.filter { now - it.epoch >= 3600L }
    return if (completed.size >= 2) completed else all.dropLast(1)
}

/* L=2/R=2 pivots and same-type collapse match WebTrader. Left comparisons
   are strict; right comparisons permit equality, exactly as mssDetect(). */
private fun analysisPivots(candles: List<DerivApiClient.Candle>): List<AnalysisPivot> {
    val raw = mutableListOf<AnalysisPivot>()
    fun swingHigh(i: Int): Boolean {
        for (k in 1..2) if (candles[i].high <= candles[i-k].high) return false
        for (k in 1..2) if (candles[i].high < candles[i+k].high) return false
        return true
    }
    fun swingLow(i: Int): Boolean {
        for (k in 1..2) if (candles[i].low >= candles[i-k].low) return false
        for (k in 1..2) if (candles[i].low > candles[i+k].low) return false
        return true
    }
    for (i in 2 until candles.size - 2) {
        if (swingHigh(i)) raw.add(AnalysisPivot("HIGH", candles[i].high, candles[i].epoch, i, candles[i+2].epoch))
        if (swingLow(i)) raw.add(AnalysisPivot("LOW", candles[i].low, candles[i].epoch, i, candles[i+2].epoch))
    }
    val collapsed = mutableListOf<AnalysisPivot>()
    raw.sortedBy { it.index }.forEach { pivot ->
        val last = collapsed.lastOrNull()
        if (last == null) collapsed.add(pivot)
        else if (last.type == pivot.type) {
            val moreExtreme = if (pivot.type == "HIGH") pivot.price > last.price else pivot.price < last.price
            if (moreExtreme) collapsed[collapsed.lastIndex] = pivot
        } else collapsed.add(pivot)
    }
    val highs = collapsed.filter { it.type == "HIGH" }
    val lows = collapsed.filter { it.type == "LOW" }
    highs.forEachIndexed { i, pivot -> pivot.label = if (i > 0 && pivot.price > highs[i-1].price) "HH" else "LH" }
    lows.forEachIndexed { i, pivot -> pivot.label = if (i > 0 && pivot.price > lows[i-1].price) "HL" else "LL" }
    return collapsed
}

private fun analysisMssEvents(candles: List<DerivApiClient.Candle>): List<AnalysisMssEvent> {
    if (candles.size < 20) return emptyList()
    val pivots = analysisPivots(candles)
    val highs = pivots.filter { it.type == "HIGH" }
    val lows = pivots.filter { it.type == "LOW" }
    val brokenHighs = mutableSetOf<Int>()
    val brokenLows = mutableSetOf<Int>()
    val events = mutableListOf<AnalysisMssEvent>()
    for (j in candles.indices) {
        val candle = candles[j]
        val priorLH = highs.lastOrNull { it.label == "LH" && it.index < j }
        val priorHL = lows.lastOrNull { it.label == "HL" && it.index < j }
        if (priorLH != null && candle.close > priorLH.price && brokenHighs.add(priorLH.index)) {
            events.add(AnalysisMssEvent("BULLISH MSS", "BULLISH", priorLH, candle))
        }
        if (priorHL != null && candle.close < priorHL.price && brokenLows.add(priorHL.index)) {
            events.add(AnalysisMssEvent("BEARISH MSS", "BEARISH", priorHL, candle))
            }
    }
    return events
}
private fun analysisLatestMss(candles: List<DerivApiClient.Candle>): AnalysisMssEvent? =
    analysisMssEvents(candles).lastOrNull()

/* Historical Step 5 events only become eligible once the pivot's two
   right-side confirmation candles have completed, as in WebTrader h5Events. */
private fun analysisHistoricalOutcomes(candles: List<DerivApiClient.Candle>): List<AnalysisHistoricalOutcome> {
    if (candles.size < 30) return emptyList()
    val pivots = analysisPivots(candles)
    val highs = pivots.filter { it.type == "HIGH" }
    val lows = pivots.filter { it.type == "LOW" }
    // WebTrader Step 3/5 labels the first pivot of each type SWING; only
    // subsequent pivots are HH/LH or HL/LL. Keep this separate from Step 4's
    // mssDetect classification, where the first high is LH and first low LL.
    highs.forEachIndexed { i, pivot -> pivot.label = if (i == 0) "SWING" else if (pivot.price > highs[i-1].price) "HH" else "LH" }
    lows.forEachIndexed { i, pivot -> pivot.label = if (i == 0) "SWING" else if (pivot.price > lows[i-1].price) "HL" else "LL" }
    val events = mutableListOf<AnalysisMssEvent>()
    val used = mutableSetOf<String>()
    for (i in candles.indices) {
        val eligibleHighs = highs.filter { it.index <= i - 2 }
        val eligibleLows = lows.filter { it.index <= i - 2 }
        val lh = eligibleHighs.lastOrNull { it.label == "LH" }
        val hl = eligibleLows.lastOrNull { it.label == "HL" }
        val c = candles[i]
        if (lh != null && c.epoch > lh.epoch && c.close > lh.price) {
            if (used.add("B:${lh.epoch}")) events.add(AnalysisMssEvent("BULLISH MSS", "BULLISH", lh, c))
        } else if (hl != null && c.epoch > hl.epoch && c.close < hl.price) {
            if (used.add("S:${hl.epoch}")) events.add(AnalysisMssEvent("BEARISH MSS", "BEARISH", hl, c))
        }
    }
    return events.mapIndexed { idx, event ->
        var end = candles.lastIndex
        for (next in idx + 1 until events.size) {
            if (events[next].direction != event.direction) { end = events[next].confirmation.let { c -> candles.indexOfFirst { it.epoch == c.epoch } }; break }
        }
        val start = (event.confirmation.let { c -> candles.indexOfFirst { it.epoch == c.epoch } } + 1).coerceAtLeast(0)
        var maxFav = 0.0; var maxAdv = 0.0
        var favPrice = event.confirmation.close; var advPrice = event.confirmation.close
        var favEpoch = event.confirmation.epoch; var advEpoch = event.confirmation.epoch
        if (start <= end && start < candles.size) for (k in start..end) {
            val c = candles[k]
            val fav = if (event.direction == "BULLISH") c.high - event.confirmation.close else event.confirmation.close - c.low
            val adv = if (event.direction == "BULLISH") event.confirmation.close - c.low else c.high - event.confirmation.close
            if (fav > maxFav) { maxFav = fav; favPrice = if (event.direction == "BULLISH") c.high else c.low; favEpoch = c.epoch }
            if (adv > maxAdv) { maxAdv = adv; advPrice = if (event.direction == "BULLISH") c.low else c.high; advEpoch = c.epoch }
        }
        AnalysisHistoricalOutcome(event.direction, event.broken, event.confirmation,
            candles.indexOfFirst { it.epoch == event.confirmation.epoch }, maxFav, favPrice, favEpoch,
            maxAdv, advPrice, advEpoch, end < candles.lastIndex)
    }
}

private fun analysisStructuralTarget(
    candles: List<DerivApiClient.Candle>, currentPrice: Double, direction: String, confirmationEpoch: Long
): AnalysisPivot? {
    val pivots = analysisPivots(candles)
    val eligible = pivots.filter { pivot ->
        if (pivot.confirmedEpoch > confirmationEpoch) return@filter false
        candles.none { candle ->
            candle.epoch > pivot.confirmedEpoch && candle.epoch <= confirmationEpoch &&
                ((pivot.type == "HIGH" && candle.close > pivot.price) ||
                 (pivot.type == "LOW" && candle.close < pivot.price))
        }
    }
    return when (direction) {
        "UP" -> eligible.filter { it.type == "HIGH" && it.price > currentPrice }
            .sortedWith(compareBy<AnalysisPivot> { it.price }.thenByDescending { it.epoch }).firstOrNull()
        "DOWN" -> eligible.filter { it.type == "LOW" && it.price < currentPrice }
            .sortedWith(compareByDescending<AnalysisPivot> { it.price }.thenByDescending { it.epoch }).firstOrNull()
        else -> null
    }
}

// =========================================================
// ANALYSIS — MARKET DATA
// =========================================================
private fun addMarketDataAnalysis() {
    if (connected && !marketDataRequested) {
        marketDataRequested = true
        derivApiClient.requestHistoricalCandles()
    }
    val header = card().apply { orientation = LinearLayout.VERTICAL }
    val row = horizontalRow()
    row.addView(label("Analysis - Market data", 17f, green, true), LinearLayout.LayoutParams(0, -2, 1f))
    row.addView(button(if (marketDataExpanded) "▼ OPEN" else "▶ CLOSED", marketDataExpanded) {
        marketDataExpanded = !marketDataExpanded; render()
    }, LinearLayout.LayoutParams(-2, dp(48)))
    header.addView(row)
    page.addView(header, matchWrap().apply { bottomMargin = dp(8) })
    if (!marketDataExpanded) return
    page.addView(label("Live market prices and historical H1 candles from the existing Deriv market-data connection.", 13f, muted, false))
    page.addView(label("TIMEFRAME: H1  |  HISTORICAL CANDLES: 1000 PER INDEX", 12f, green, true))
    marketDataIndexes.forEach { symbol ->
        val marketCard = card().apply { orientation = LinearLayout.VERTICAL }
        marketCard.addView(label("Index ${symbol.removePrefix("R_")}", 18f, green, true))
        val priceView = label("CURRENT PRICE: ${formatMarketPrice(marketDataPrices[symbol])}", 14f, blue, true)
        registerCurrentPriceView(symbol, "market-data", priceView)
        marketCard.addView(priceView)
        val candles = marketDataCandles[symbol].orEmpty().sortedBy { it.epoch }
        val completed = analysisCompletedCandles(symbol)
        marketCard.addView(label("HISTORICAL CANDLES: ${candles.size}", 13f, white, false))
        marketCard.addView(label("COMPLETED H1 CANDLES: ${completed.size}", 12f, muted, false))
        marketCard.addView(label("LAST CANDLE: ${candles.lastOrNull()?.let { formatMarketTime(it.epoch) } ?: "WAITING FOR DATA"}", 12f, muted, false))
        val error = marketDataErrors[symbol]
        val ready = candles.isNotEmpty() && error == null
        marketCard.addView(label(
            if (error != null) "STATUS: ERROR — $error" else if (ready) "STATUS: READY" else "STATUS: WAITING",
            12f, if (error != null) red else if (ready) green else yellow, true
        ))
        page.addView(marketCard, matchWrap().apply { topMargin = dp(8) })
    }
    page.addView(label("Data source: Deriv market data | H1 candles | 1000 candles per index", 11f, muted, false))
}
// =========================================================
// ANALYSIS — MARKET STRUCTURE
// =========================================================

private fun addMarketStructureAnalysis() {
    val header = card().apply { orientation = LinearLayout.VERTICAL }
    val row = horizontalRow()
    row.addView(label("Analysis - Market Structure", 17f, green, true), LinearLayout.LayoutParams(0, -2, 1f))
    row.addView(button(if (marketStructureExpanded) "▼ OPEN" else "▶ CLOSED", marketStructureExpanded) {
        marketStructureExpanded = !marketStructureExpanded; render()
    }, LinearLayout.LayoutParams(-2, dp(48)))
    header.addView(row)
    page.addView(header, matchWrap().apply { bottomMargin = dp(8) })
    if (!marketStructureExpanded) return
    page.addView(label("HH, HL, LH and LL are structural observations only. H1 pivots use two candles on each side; consecutive same-type pivots retain the more extreme price. MSS/CHOCH is confirmed in Step 4, not here.", 12f, muted, false).apply { setPadding(dp(12), dp(10), dp(12), dp(10)) })
    marketDataIndexes.forEach { symbol ->
        val candles = analysisCompletedCandles(symbol)
        val structureCard = card().apply { orientation = LinearLayout.VERTICAL }
        structureCard.addView(label("INDEX ${symbol.removePrefix("R_")}", 17f, green, true))
        val structurePriceView = label("CURRENT PRICE: ${formatMarketPrice(marketDataPrices[symbol])}", 14f, blue, true)
        registerCurrentPriceView(symbol, "market-structure", structurePriceView)
        structureCard.addView(structurePriceView)
        structureCard.addView(label("COMPLETED H1: ${candles.size}", 12f, muted, false))
        if (candles.size < 20) {
            structureCard.addView(label("STATUS: WAITING FOR SUFFICIENT COMPLETED H1 HISTORY", 12f, yellow, true))
        } else {
            // Match Webtrader: display only the latest 40 pivots in Market Structure.
            // This cap is local to this display; MSS/CHOCH, behaviour, and structural
            // target calculations continue using their full analysis pivot history.
            val pivots = analysisPivots(candles).takeLast(40)
            val highs = pivots.filter { it.type == "HIGH" }
            val lows = pivots.filter { it.type == "LOW" }
            val last = pivots.takeLast(2)
            val leg = if (last.size == 2 && last[0].type == "LOW" && last[1].type == "HIGH") "BULLISH LEG"
                else if (last.size == 2 && last[0].type == "HIGH" && last[1].type == "LOW") "BEARISH LEG" else "UNCLEAR"
            structureCard.addView(label("STRUCTURAL PIVOTS: ${pivots.size}", 12f, white, false))
            structureCard.addView(label("CURRENT STRUCTURAL LEG: $leg", 13f, if (leg == "BULLISH LEG") green else if (leg == "BEARISH LEG") red else yellow, true))
            if (last.size == 2) structureCard.addView(label("LEG: ${last[0].type} ${formatMarketPrice(last[0].price)} (${formatMarketTime(last[0].epoch)}) → ${last[1].type} ${formatMarketPrice(last[1].price)} (${formatMarketTime(last[1].epoch)})", 12f, white, false))
            structureCard.addView(label("LATEST STRUCTURAL PIVOTS", 13f, green, true))
            pivots.takeLast(10).forEach { pivot ->
                val sequence = if (pivot.type == "HIGH") highs else lows
                val pivotIndex = sequence.indexOfFirst { it.index == pivot.index }
                val structureLabel = if (pivotIndex <= 0) "SWING" else if (pivot.type == "HIGH") {
                    if (pivot.price > sequence[pivotIndex - 1].price) "HH" else "LH"
                } else {
                    if (pivot.price > sequence[pivotIndex - 1].price) "HL" else "LL"
                }
                structureCard.addView(label("$structureLabel  |  ${pivot.type} ${formatMarketPrice(pivot.price)}  |  ${formatMarketTime(pivot.epoch)}", 12f, white, false))
            }
            structureCard.addView(label("HIGH STRUCTURE: ${highs.takeLast(4).joinToString("  |  ") { "${it.label} ${formatMarketPrice(it.price)}" }.ifBlank { "WAITING" }}", 12f, muted, false))
            structureCard.addView(label("LOW STRUCTURE: ${lows.takeLast(4).joinToString("  |  ") { "${it.label} ${formatMarketPrice(it.price)}" }.ifBlank { "WAITING" }}", 12f, muted, false))
        }
        page.addView(structureCard, matchWrap().apply { topMargin = dp(6); bottomMargin = dp(6) })
    }
}
// =========================================================
// ANALYSIS — MSS / CHOCH CONFIRMATION
// =========================================================
private fun addMssChochAnalysis() {
    val header = card().apply { orientation = LinearLayout.VERTICAL }
    val row = horizontalRow()
    row.addView(label("Analysis - MSS/CHOCH", 17f, green, true), LinearLayout.LayoutParams(0, -2, 1f))
    row.addView(button(if (mssChochExpanded) "▼ OPEN" else "▶ CLOSED", mssChochExpanded) {
        mssChochExpanded = !mssChochExpanded; render()
    }, LinearLayout.LayoutParams(-2, dp(48)))
    header.addView(row)
    page.addView(header, matchWrap().apply { bottomMargin = dp(8) })
    if (!mssChochExpanded) return
    page.addView(label("A bullish MSS requires a completed H1 close above a prior LH; a bearish MSS requires a completed H1 close below a prior HL. A pivot can generate only one event. The most recent confirmed event remains active until replaced by a newer event; a live price touch is not confirmation.", 12f, muted, false).apply { setPadding(dp(12), dp(10), dp(12), dp(10)) })
    marketDataIndexes.forEach { symbol ->
        val candles = analysisCompletedCandles(symbol)
        val card = card().apply { orientation = LinearLayout.VERTICAL }
        card.addView(label("INDEX ${symbol.removePrefix("R_")}", 17f, green, true))
        val mssPriceView = label("CURRENT PRICE: ${formatMarketPrice(marketDataPrices[symbol])}", 14f, blue, true)
        registerCurrentPriceView(symbol, "mss-choch", mssPriceView)
        card.addView(mssPriceView)
        if (candles.size < 20) {
            card.addView(label("STATUS: WAITING FOR SUFFICIENT COMPLETED H1 HISTORY", 12f, yellow, true))
        } else {
            val pivots = analysisPivots(candles)
            val latestLH = pivots.lastOrNull { it.type == "HIGH" && it.label == "LH" }
            val latestHL = pivots.lastOrNull { it.type == "LOW" && it.label == "HL" }
            val event = analysisLatestMss(candles)
            card.addView(label("STRUCTURAL EVENT: ${event?.state ?: "WAITING FOR MSS / CHOCH"}", 13f, if (event?.direction == "BULLISH") green else if (event?.direction == "BEARISH") red else yellow, true))
            if (event != null) {
                card.addView(label("DIRECTION: ${event.direction}", 13f, if (event.direction == "BULLISH") green else red, true))
                card.addView(label("BROKEN PIVOT: ${event.broken.label} ${formatMarketPrice(event.broken.price)}", 12f, white, false))
                card.addView(label("PIVOT TIME: ${formatMarketTime(event.broken.epoch)}", 12f, muted, false))
                card.addView(label("CONFIRMATION CLOSE: ${formatMarketPrice(event.confirmation.close)}", 12f, white, false))
                card.addView(label("CONFIRMED AT: ${formatMarketTime(event.confirmation.epoch + 3600L)}", 12f, muted, false))
                card.addView(label("STATUS: CONFIRMED — PERSISTENT UNTIL A NEWER EVENT", 12f, green, true))
            } else {
                card.addView(label("LATEST LH: ${latestLH?.let { formatMarketPrice(it.price) + " | " + formatMarketTime(it.epoch) } ?: "NOT AVAILABLE"}", 12f, white, false))
                card.addView(label("LATEST HL: ${latestHL?.let { formatMarketPrice(it.price) + " | " + formatMarketTime(it.epoch) } ?: "NOT AVAILABLE"}", 12f, white, false))
                card.addView(label("STATUS: WAITING FOR A COMPLETED H1 CLOSE BEYOND THE RELEVANT PIVOT", 12f, yellow, true))
            }
        }
        page.addView(card, matchWrap().apply { topMargin = dp(6); bottomMargin = dp(6) })
    }
}
// =========================================================
// ANALYSIS — MSS / CHOCH BEHAVIOUR
// =========================================================
private fun addMssChochBehaviourAnalysis() {
    val header = card().apply { orientation = LinearLayout.VERTICAL }
    val row = horizontalRow()
    row.addView(label("Analysis - MSS/CHOCH behaviour", 17f, green, true), LinearLayout.LayoutParams(0, -2, 1f))
    row.addView(button(if (mssChochBehaviourExpanded) "▼ OPEN" else "▶ CLOSED", mssChochBehaviourExpanded) {
        mssChochBehaviourExpanded = !mssChochBehaviourExpanded; render()
    }, LinearLayout.LayoutParams(-2, dp(48)))
    header.addView(row)
    page.addView(header, matchWrap().apply { bottomMargin = dp(8) })
    if (!mssChochBehaviourExpanded) return
    page.addView(label("Uses the same completed-H1 MSS event rules. The latest current event is excluded from historical samples. The last 20 historical events are measured until an opposite MSS or the end of loaded history. Continuation is the percentage with favourable movement greater than zero; averages are directional favourable/adverse extremes.", 12f, muted, false).apply { setPadding(dp(12), dp(10), dp(12), dp(10)) })
    marketDataIndexes.forEach { symbol ->
        val candles = analysisCompletedCandles(symbol)
        val card = card().apply { orientation = LinearLayout.VERTICAL }
        card.addView(label("INDEX ${symbol.removePrefix("R_")}", 17f, green, true))
        if (candles.size < 30) {
            card.addView(label("STATUS: WAITING FOR AT LEAST 30 COMPLETED H1 CANDLES", 12f, yellow, true))
        } else {
            val allOutcomes = analysisHistoricalOutcomes(candles)
            val currentEvent = analysisLatestMss(candles)
            val history = allOutcomes.filterNot { currentEvent != null && it.confirmation.epoch == currentEvent.confirmation.epoch }.takeLast(20)
            val direction = currentEvent?.direction
            val sample = when (direction) {
                "BULLISH" -> history.filter { it.direction == "BULLISH" }
                "BEARISH" -> history.filter { it.direction == "BEARISH" }
                else -> history
            }
            val continuation = if (sample.isEmpty()) null else sample.count { it.maxFav > 0.0 }.toDouble() / sample.size * 100.0
            val avgFav = sample.takeIf { it.isNotEmpty() }?.map { it.maxFav }?.average()
            val avgAdv = sample.takeIf { it.isNotEmpty() }?.map { it.maxAdv }?.average()
            card.addView(label("CURRENT MSS: ${currentEvent?.state ?: "NO CONFIRMED EVENT"}", 13f, if (currentEvent?.direction == "BULLISH") green else if (currentEvent?.direction == "BEARISH") red else yellow, true))
            card.addView(label("HISTORICAL SAMPLE: ${sample.size}  |  BULLISH / BEARISH EVENTS: ${history.count { it.direction == "BULLISH" }} / ${history.count { it.direction == "BEARISH" }}", 12f, white, false))
            card.addView(label("EVENT SAMPLE: ${direction ?: "ALL HISTORICAL MSS"}", 12f, muted, false))
            card.addView(label("HISTORICAL CONTINUATION: ${continuation?.let { String.format(java.util.Locale.US, "%.1f%%", it) } ?: "WAITING"}", 13f, if (continuation != null && continuation >= 50.0) green else yellow, true))
            card.addView(label("AVERAGE FAVOURABLE MOVE: ${avgFav?.let { formatMarketPrice(it) } ?: "—"}", 12f, white, false))
            card.addView(label("AVERAGE ADVERSE MOVE: ${avgAdv?.let { formatMarketPrice(it) } ?: "—"}", 12f, white, false))
        }
        page.addView(card, matchWrap().apply { topMargin = dp(6); bottomMargin = dp(6) })
    }
}
// =========================================================
// ANALYSIS — DIRECTION AND STRUCTURAL TARGET
// =========================================================
private fun addDirectionStructuralTargetAnalysis() {
    val header = card().apply { orientation = LinearLayout.VERTICAL }
    val row = horizontalRow()
    row.addView(label("Analysis - Direction and Structural Target", 17f, green, true), LinearLayout.LayoutParams(0, -2, 1f))
    row.addView(button(if (directionStructuralTargetExpanded) "▼ OPEN" else "▶ CLOSED", directionStructuralTargetExpanded) {
        directionStructuralTargetExpanded = !directionStructuralTargetExpanded
        render()
    }, LinearLayout.LayoutParams(-2, dp(48)))
    header.addView(row)
    page.addView(header, matchWrap().apply { bottomMargin = dp(8) })
    if (!directionStructuralTargetExpanded) return
    page.addView(label("Direction is taken from the latest confirmed MSS/CHOCH. The structural target must lie beyond the current price in that direction.", 12f, muted, false))
    marketDataIndexes.forEach { symbol ->
        val candles = analysisCompletedCandles(symbol)
        val current = marketDataPrices[symbol] ?: candles.lastOrNull()?.close
        val event = if (candles.size >= 20) analysisLatestMss(candles) else null
        val direction = when (event?.direction) {
            "BULLISH" -> "UP"
            "BEARISH" -> "DOWN"
            else -> ""
        }
        val target = if (event != null && current != null && direction.isNotBlank()) {
            analysisStructuralTarget(candles, current, direction, event.confirmation.epoch)
        } else null
        val hasTarget = target != null &&
            current != null &&
            (
                (direction == "UP" && target.price > current) ||
                (direction == "DOWN" && target.price < current)
            )

            val distance = if (
            hasTarget && current != null && current != 0.0
        ) {
            kotlin.math.abs(target!!.price - current) /
                current * 100.0
        } else {
            null
        }

        val indexCard = card().apply {
            orientation = LinearLayout.VERTICAL
        }

        indexCard.addView(
            label("INDEX ${symbol.removePrefix("R_")}", 17f, green, true)
        )

        val priceView = label(
            "CURRENT PRICE: ${formatMarketPrice(current)}",
            14f, blue, true
        )

        registerCurrentPriceView(
            symbol,
            "direction-target",
            priceView
        )

        indexCard.addView(priceView)

        indexCard.addView(
            label(
                "MSS / CHOCH: ${event?.state ?: "WAITING"}",
                13f,
                when (direction) {
                    "UP" -> green
                    "DOWN" -> red
                    else -> yellow
                },
                true
            )
        )

        indexCard.addView(
            label(
                "DIRECTION: ${direction.ifBlank { "WAITING" }}",
                13f,
                when (direction) {
                    "UP" -> green
                    "DOWN" -> red
                    else -> yellow
                },
                true
            )
        )

        // Target prices are confidential and must be visible to administrators only.
        if (platform == "ADMIN") {
            indexCard.addView(
                label(
                    "STRUCTURAL TARGET: ${
                        if (hasTarget) formatMarketPrice(target?.price)
                        else "WAITING"
                    }",
                    13f,
                    if (hasTarget) green else yellow,
                    true
                )
            )
        }

        indexCard.addView(
            label(
                "TARGET DISTANCE: ${
                    distance?.let {
                        String.format(
                            java.util.Locale.US,
                            "%.3f%%",
                            it
                        )
                    } ?: "—"
                }",
                12f, blue, false
            )
        )
        page.addView(
            indexCard,
            matchWrap().apply {
                topMargin = dp(6)
                bottomMargin = dp(6)
            }
        )
    }
}

// =========================================================
// label("Analysis - Qualification", 17f, green, true)
// =========================================================

private fun addQualificationAnalysis() {
    val header = card().apply { orientation = LinearLayout.VERTICAL }
    val row = horizontalRow()
    row.addView(
        label("Analysis - Qualification", 17f, green, true),
        LinearLayout.LayoutParams(0, -2, 1f)
    )
    row.addView(
        button(if (qualificationExpanded) "▼ OPEN" else "▶ CLOSED", qualificationExpanded) {
            qualificationExpanded = !qualificationExpanded
            render()
        },
        LinearLayout.LayoutParams(-2, dp(48))
    )
    header.addView(row)
    page.addView(header, matchWrap().apply { bottomMargin = dp(8) })
    if (!qualificationExpanded) return
    page.addView(
        label(
            "Qualification checks the same index for a confirmed MSS/CHOCH, a valid structural target in the confirmed direction, and available historical MSS behaviour for that direction. It reports evidence only and does not execute a trade.",
            12f, muted, false
        ).apply { setPadding(dp(12), dp(10), dp(12), dp(10)) }
    )

    marketDataIndexes.forEach { symbol ->
        val candles = analysisCompletedCandles(symbol)
        val current = marketDataPrices[symbol] ?: candles.lastOrNull()?.close
        val event = if (candles.size >= 20) analysisLatestMss(candles) else null
        val direction = when (event?.direction) {
            "BULLISH" -> "UP"
            "BEARISH" -> "DOWN"
            else -> ""
        }
        val target = if (event != null && current != null && direction.isNotBlank()) {
            analysisStructuralTarget(candles, current, direction, event.confirmation.epoch)
        } else null
        val history = if (candles.size >= 30) {
            val outcomes = analysisHistoricalOutcomes(candles)
            outcomes.filter {
                it.direction == event?.direction &&
                    (event == null || it.confirmation.epoch != event.confirmation.epoch)
            }.takeLast(20)
        } else emptyList()
        val hasConfirmedEvent = event != null
        val hasTarget = target != null && current != null &&
            ((direction == "UP" && target.price > current) ||
             (direction == "DOWN" && target.price < current))
        val hasHistory = history.isNotEmpty()
        val masterDecision = evaluateMasterDecision(symbol)
        val qualified = masterDecision != null

        val qualificationCard = card().apply { orientation = LinearLayout.VERTICAL }
        qualificationCard.addView(label("INDEX ${symbol.removePrefix("R_")}", 17f, green, true))
        val priceView = label("CURRENT PRICE: ${formatMarketPrice(current)}", 14f, blue, true)
        registerCurrentPriceView(symbol, "qualification", priceView)
        qualificationCard.addView(priceView)
        qualificationCard.addView(
            label(
                "QUALIFICATION: ${if (qualified) "QUALIFIED" else "WAITING"}",
                14f, if (qualified) green else yellow, true
            )
        )
        qualificationCard.addView(
            label(
                "MSS / CHOCH CONFIRMED: ${if (hasConfirmedEvent) "PASS — ${event!!.state}" else "WAIT"}",
                12f, if (hasConfirmedEvent) green else yellow, true
            )
        )
           // Keep the target-price value on the administrator interface only.
        if (platform == "ADMIN") {
            qualificationCard.addView(
                label(
                    "STRUCTURAL TARGET: ${if (hasTarget) "PASS — ${formatMarketPrice(target?.price)}" else "WAIT — NO VALID TARGET"}",
                    12f, if (hasTarget) green else yellow, true
                )
            )
        }
        qualificationCard.addView(
            label(
                "HISTORICAL DIRECTION SAMPLE: ${if (hasHistory) "PASS — ${history.size} event(s)" else "WAIT — NO HISTORICAL SAMPLE"}",
                12f, if (hasHistory) green else yellow, true
            )
        )
        val currentPriceForDistance = current ?: 0.0
        val targetPriceForDistance = target?.price
        val targetDistance: Double? = if (targetPriceForDistance != null && currentPriceForDistance > 0.0) {
            kotlin.math.abs(targetPriceForDistance - currentPriceForDistance) / currentPriceForDistance * 100.0
        } else {
            null
        }
        val targetEligibilityText = if (targetDistance != null && targetDistance >= 1.5) {
            "PASS — " + String.format(java.util.Locale.US, "%.3f%%", targetDistance)
        } else {
            "WAIT — " + (targetDistance?.let { String.format(java.util.Locale.US, "%.3f%%", it) } ?: "NO TARGET")
        }
        qualificationCard.addView(label(
            "TARGET ELIGIBILITY ≥ 1.5%: $targetEligibilityText",
            12f, if (targetDistance != null && targetDistance >= 1.5) green else yellow, true
        ))
        val ptpeText = if (masterDecision != null) {
            String.format(java.util.Locale.US, "%.1f%%", masterDecision.targetProbability) +
                " (${masterDecision.sampleCount} comparable 3-day samples)"
        } else "WAIT — BELOW 55% OR INSUFFICIENT SAMPLES"
        qualificationCard.addView(label(
            "PTPE TARGET PROBABILITY: $ptpeText",
            12f, if (masterDecision != null) green else yellow, true
        ))
        val masterText = if (masterDecision != null) "APPROVED ${masterDecision.direction}" else "WAIT"
        qualificationCard.addView(label(
            "MASTER DECISION: $masterText",
            13f, if (masterDecision != null) green else yellow, true
        ))
        page.addView(qualificationCard, matchWrap().apply { topMargin = dp(6); bottomMargin = dp(6) })
    }
}

private fun addPtpeAnalysis() {
    val header = card().apply { orientation = LinearLayout.VERTICAL }
    val row = horizontalRow()
    row.addView(label("Analysis - PTPE Target Probability", 17f, green, true), LinearLayout.LayoutParams(0, -2, 1f))
    row.addView(button(if (ptpeAnalysisExpanded) "▼ OPEN" else "▶ CLOSED", ptpeAnalysisExpanded) {
        ptpeAnalysisExpanded = !ptpeAnalysisExpanded
        render()
    }, LinearLayout.LayoutParams(-2, dp(48)))
    header.addView(row)
    page.addView(header, matchWrap().apply { bottomMargin = dp(8) })
    if (!ptpeAnalysisExpanded) return

    page.addView(label(
        "PTPE estimates how often a comparable historical MSS/CHOCH in the same direction reached the current target distance within 24 hours. The contract timeframe remains three days. Qualification requires at least 55% probability and at least 5 comparable samples. This section reports the reason each index qualifies or waits.",
        12f, muted, false
        ).apply { setPadding(dp(12), dp(10), dp(12), dp(10)) })

    marketDataIndexes.forEach { symbol ->
        val ptpe = lastPtpeByIndex[symbol] ?: PtpeDisplay()
        val card = card().apply { orientation = LinearLayout.VERTICAL }
        card.addView(label("INDEX ${symbol.removePrefix("R_")}", 17f, green, true))
        card.addView(label("DIRECTION: ${ptpe.direction}", 13f, if (ptpe.direction == "BUY") green else if (ptpe.direction == "SELL") red else yellow, true))
        card.addView(label("STRUCTURAL TARGET: ${ptpe.target?.let { formatMarketPrice(it) } ?: "—"}", 13f, white, false))
        card.addView(label("TARGET DISTANCE: ${ptpe.distancePercent?.let { String.format(java.util.Locale.US, "%.3f%%", it) } ?: "—"}", 13f, white, false))
        card.addView(label("PTPE PROBABILITY: ${String.format(java.util.Locale.US, "%.1f%%", ptpe.probability)}", 14f, if (ptpe.probability >= 55.0 && ptpe.samples >= 5) green else yellow, true))
        card.addView(label("COMPARABLE HISTORICAL SAMPLES: ${ptpe.samples} (minimum 5)", 12f, white, false))
        card.addView(label("MINIMUM PROBABILITY: 55%", 12f, white, false))
        card.addView(label("STATUS: ${ptpe.status}", 13f, if (ptpe.status == "QUALIFIED") green else yellow, true))
        page.addView(card, matchWrap().apply { topMargin = dp(6); bottomMargin = dp(6) })
    }
}

private fun addTargetFilterAnalysis() {
    val header = card().apply { orientation = LinearLayout.VERTICAL }
    val row = horizontalRow()
    row.addView(label("Analysis - Target filter", 17f, green, true), LinearLayout.LayoutParams(0, -2, 1f))
    row.addView(button(if (targetFilterExpanded) "▼ OPEN" else "▶ CLOSED", targetFilterExpanded) {
        targetFilterExpanded = !targetFilterExpanded; render()
    }, LinearLayout.LayoutParams(-2, dp(48)))
    header.addView(row)
    page.addView(header, matchWrap().apply { bottomMargin = dp(8) })
    if (!targetFilterExpanded) return
    page.addView(label("Checks that a confirmed direction has a valid structural target beyond the live price in that direction. A target is rejected when no valid confirmed pivot is available.", 12f, yellow, false).apply {
        setPadding(dp(12), dp(10), dp(12), dp(10))
    })
    marketDataIndexes.forEach { symbol ->
        val candles = analysisCompletedCandles(symbol)
        val card = card().apply { orientation = LinearLayout.VERTICAL }
        card.addView(label("INDEX ${symbol.removePrefix("R_")}", 17f, green, true))
        val current = marketDataPrices[symbol] ?: candles.lastOrNull()?.close
        val priceView = label("CURRENT PRICE: ${formatMarketPrice(current)}", 13f, blue, true)
        registerCurrentPriceView(symbol, "target-filter", priceView)
        card.addView(priceView)
        val event = if (candles.size >= 20) analysisLatestMss(candles) else null
        val direction = when (event?.direction) { "BULLISH" -> "UP"; "BEARISH" -> "DOWN"; else -> "" }
        val target = if (event != null && current != null && direction.isNotBlank())
            analysisStructuralTarget(candles, current, direction, event.confirmation.epoch) else null
        val currentPriceForDistance = current ?: 0.0
        val targetPriceForDistance = target?.price
        val distance: Double? = if (targetPriceForDistance != null && currentPriceForDistance > 0.0) {
            kotlin.math.abs(targetPriceForDistance - currentPriceForDistance) / currentPriceForDistance * 100.0
        } else {
            null
        }
        val passes = targetPriceForDistance != null && distance != null && distance > 0.0 &&
            ((direction == "UP" && targetPriceForDistance > currentPriceForDistance) ||
             (direction == "DOWN" && targetPriceForDistance < currentPriceForDistance))
        card.addView(label("DIRECTION: ${direction.ifBlank { "WAITING" }}", 13f,
            if (direction == "UP") green else if (direction == "DOWN") red else yellow, true))
        // Never render the actual target price for a regular user.
        if (platform == "ADMIN") {
            card.addView(label("TARGET PRICE: ${target?.let { formatMarketPrice(it.price) } ?: "REJECTED / NOT AVAILABLE"}", 13f,
                if (target != null) blue else yellow, true))
        }
        card.addView(label("TARGET DISTANCE: ${distance?.let { String.format(java.util.Locale.US, "%.3f%%", it) } ?: "—"}", 12f, blue, false))
        card.addView(label("TARGET FILTER: ${if (passes) "PASS" else "WAIT — NO VALID TARGET"}", 13f,
            if (passes) green else yellow, true))
        page.addView(card, matchWrap().apply { topMargin = dp(6); bottomMargin = dp(6) })
    }
}

    private fun registerCurrentPriceView(symbol: String, section: String, view: TextView) {
        marketDataPriceViews["$section:$symbol"] = view
        val views = currentPriceViews.getOrPut(symbol) { mutableListOf() }
        if (!views.contains(view)) views.add(view)
    }
    private fun formatMarketPrice(price: Double?): String =
        price?.let { String.format(java.util.Locale.US, "%.3f", it) } ?: "—"

    private fun formatMarketTime(epoch: Long): String {
        if (epoch <= 0L) return "—"
        return java.text.SimpleDateFormat("yyyy/MM/dd, HH:mm:ss", java.util.Locale("en", "ZA"))
            .format(java.util.Date(epoch * 1000L))
    }

    // =========================================================
    // ADMIN USER APPROVAL — server verifies administrator role
    // =========================================================
    private fun addAdminUsersScreen() {
        page.addView(sectionTitle("USER APPROVALS"))
        val profile = currentProfile
        if (authToken.isBlank()) {
            page.addView(label("Sign in with the administrator account to manage registrations.", 14f, yellow, false))
            page.addView(button("SIGN IN", true) { selectedTab = "Login"; render() }, matchWrap())
            return
        }
        if (profile == null) {
            page.addView(label("Loading your account role…", 14f, yellow, false))
            if (!profileLoading) refreshProfile(showMessage = false)
            return
        }
        if (!profile.optString("role", "user").equals("admin", ignoreCase = true)) {
            page.addView(label("Access denied. Only an administrator can approve registrations.", 14f, red, false))
            page.addView(button("BACK TO HOME", false) { selectedTab = "Home"; render() }, matchWrap())
            return
        }

        page.addView(label("New registrations remain pending until you approve them here.", 13f, muted, false))
        page.addView(
            button(if (adminUsersLoading) "LOADING USERS…" else "REFRESH PENDING USERS", false) {
                loadAdminUsers()
            },
            matchWrap().apply { topMargin = dp(8); bottomMargin = dp(8) }
        )
        val users = adminUsers
        if (adminUsersLoading && users == null) {
            page.addView(label("Loading registrations…", 14f, yellow, false))
            return
            }
        if (users == null) {
            page.addView(label("Tap REFRESH PENDING USERS to load registrations.", 14f, muted, false))
            return
        }
        var pendingCount = 0
        for (i in 0 until users.length()) {
            val user = users.optJSONObject(i) ?: continue
            val userStatus = user.optString("status", "pending").uppercase()
            if (userStatus == "PENDING") pendingCount++

            val userCard = card().apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
            userCard.addView(label(user.optString("full_name", "Name not supplied"), 16f, white, true))
            userCard.addView(label(user.optString("email", ""), 13f, muted, false))
            userCard.addView(label("Contact: " + user.optString("phone", "Not supplied"), 13f, muted, false))
            val statusColor = when (userStatus) {
                "APPROVED" -> green
                "SUSPENDED" -> red
                else -> yellow
            }
            userCard.addView(label("Status: $userStatus", 14f, statusColor, true))
            userCard.addView(label("Risk plan: " + user.optString("risk_profile", "Not assigned"), 12f, muted, false))
            userCard.addView(label("Plan start: " + user.optString("start_date", "Not assigned"), 12f, muted, false))
            userCard.addView(label("Plan expiry: " + user.optString("expiry_date", "Not assigned"), 12f, muted, false))
            userCard.addView(button("USER VIEW", false) { showAdminUserViewDialog(user) }, matchWrap().apply { topMargin = dp(7) })
            val userIdForDates = user.optString("id", user.optString("user_id", ""))
            if (userIdForDates.isNotBlank() && !user.optString("role", "user").equals("admin", true)) {
                userCard.addView(button("ASSIGN START / EXPIRY DATES", true) { showAssignPlanDatesDialog(user) },
                    matchWrap().apply { topMargin = dp(6) })
            }

            // User-management actions are handled by the server against the shared users.json.
            val userId = user.optString("id", "")
            if (userId.isNotBlank()) {
                userCard.addView(
                    button("EDIT USER", false) { showEditUserDialog(user) },
                    matchWrap().apply { topMargin = dp(7) }
                )
                if (userStatus == "SUSPENDED") {
                    userCard.addView(
                        button("REACTIVATE USER", true) {
                            android.app.AlertDialog.Builder(this@MainActivity)
                                .setTitle("Reactivate user")
                                .setMessage("Allow this user to log in again?")
                                .setNegativeButton("CANCEL", null)
                                .setPositiveButton("REACTIVATE") { _, _ ->
                                    authRequest("admin_reactivate_user", mapOf("user_id" to userId)) { ok, result ->
                                        toast(result.optString("message", if (ok) "User reactivated." else "Unable to reactivate user."))
                                        if (ok) loadAdminUsers()
                                    }
                                }.show()
                        },
                        matchWrap().apply { topMargin = dp(6) }
                    )
                } else if (userStatus == "APPROVED") {
                    userCard.addView(
                        button("SUSPEND USER", false) {
                            android.app.AlertDialog.Builder(this@MainActivity)
                                .setTitle("Suspend user")
                                .setMessage("This user will no longer be able to log in. Continue?")
                                .setNegativeButton("CANCEL", null)
                                .setPositiveButton("SUSPEND") { _, _ ->
                                    authRequest("admin_suspend_user", mapOf("user_id" to userId)) { ok, result ->
                                        toast(result.optString("message", if (ok) "User suspended." else "Unable to suspend user."))
                                        if (ok) loadAdminUsers()
                                    }
                                }.show()
                        },
                        matchWrap().apply { topMargin = dp(6) }
                    )
                }
                if (!user.optString("role", "user").equals("admin", ignoreCase = true)) {
                    userCard.addView(
                        button("DELETE USER", false) {
                            android.app.AlertDialog.Builder(this@MainActivity)
                                .setTitle("Delete user")
                                .setMessage("Permanently delete ${user.optString("full_name", "this user")} from user management? This cannot be undone.")
                                .setNegativeButton("CANCEL", null)
                                .setPositiveButton("DELETE") { _, _ ->
                                    authRequest("admin_delete_user", mapOf("user_id" to userId)) { ok, result ->
                                        toast(result.optString("message", if (ok) "User deleted." else "Unable to delete user."))
                                        if (ok) loadAdminUsers()
                                    }
                                }.show()
                        },
                        matchWrap().apply { topMargin = dp(6) }
                    )
                }
            } else {
                userCard.addView(label("This record has no user ID; it cannot be edited, suspended, or deleted from the app.", 12f, yellow, false))
            }

            if (userStatus == "PENDING") {
                userCard.addView(
                    button("APPROVE USER", true) {
                        val id = user.optString("id", "")
                        if (id.isBlank()) {
                            toast("This user record has no ID. Please manage it in WebTrader.")
                        } else {
                           authRequest("admin_approve_user", mapOf("user_id" to id)) { ok, result ->
                                if (ok) {
                                    toast(result.optString("message", "User approved."))
                                    loadAdminUsers()
                                } else {
                                    toast(result.optString("message", "Unable to approve this user."))
                                }
                            }
                            }
                    },
                    matchWrap().apply { topMargin = dp(8) }
                )
            }
            page.addView(userCard, matchWrap().apply { topMargin = dp(8) })
        }
        if (pendingCount == 0) {
            page.addView(label("There are no pending registrations.", 14f, green, false))
        }
    }
    private fun showAdminUserViewDialog(user: JSONObject) {
        val fullName = user.optString("full_name", listOf(user.optString("first_name", ""), user.optString("surname", "")).filter { it.isNotBlank() }.joinToString(" "))
        val details = listOf(
            "Name: ${fullName.ifBlank { "Not supplied" }}",
            "Email: ${user.optString("email", "Not supplied")}",
            "Contact: ${user.optString("phone", "Not supplied")}",
            "Role: ${user.optString("role", "user")}",
            "Status: ${user.optString("status", "pending")}",
            "Risk plan: ${user.optString("risk_profile", "Not assigned")}",
            "Start date: ${user.optString("start_date", "Not assigned")}",
            "Expiry date: ${user.optString("expiry_date", "Not assigned")}"
        ).joinToString("\n\n")
        android.app.AlertDialog.Builder(this).setTitle("User view").setMessage(details).setPositiveButton("CLOSE", null).show()
    }
    private fun showAssignPlanDatesDialog(user: JSONObject) {
        val userId = user.optString("id", user.optString("user_id", ""))
        if (userId.isBlank()) { toast("This user record has no ID."); return }
        val startInput = android.widget.EditText(this).apply {
            hint = "Start date (yyyy-MM-dd)"; setSingleLine(true)
            setText(user.optString("start_date", "").takeUnless { it == "null" }.orEmpty())
        }
        val expiryInput = android.widget.EditText(this).apply {
            hint = "Expiry date (yyyy-MM-dd)"; setSingleLine(true)
            setText(user.optString("expiry_date", "").takeUnless { it == "null" }.orEmpty())
        }
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(4))
            addView(label("PLAN START DATE", 12f, green, true)); addView(startInput, matchWrap())
            addView(label("PLAN EXPIRY DATE", 12f, green, true).apply { setPadding(0, dp(8), 0, 0) }); addView(expiryInput, matchWrap())
            addView(label("Use yyyy-MM-dd. Expiry must be on or after start.", 12f, muted, false).apply { setPadding(0, dp(8), 0, 0) })
        }
        android.app.AlertDialog.Builder(this).setTitle("Assign plan dates").setView(fields)
            .setNegativeButton("CANCEL", null).setPositiveButton("ASSIGN", null).create().apply {
                setOnShowListener {
                    getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val startDate = startInput.text.toString().trim()
                        val expiryDate = expiryInput.text.toString().trim()
                        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { isLenient = false; timeZone = dailyCapitalTimeZone }
                        val start = try { fmt.parse(startDate) } catch (_: Exception) { null }
                        val expiry = try { fmt.parse(expiryDate) } catch (_: Exception) { null }
                        if (start == null || expiry == null || expiry.before(start)) {
                            toast("Enter valid dates in yyyy-MM-dd format. Expiry cannot be before start."); return@setOnClickListener
                        }
                        val risk = user.optString("risk_profile", "").ifBlank { "minimum" }.lowercase(java.util.Locale.US).replace(" ", "_").replace("-", "_")
                        val values = mapOf(
                            "user_id" to userId,
                            "risk_profile" to risk,
                                "risk_percent" to user.optString("risk_percent", "20").ifBlank { "20" },
                            "plan_id" to user.optString("plan_id", "${risk}_assigned"),
                            "start_date" to startDate,
                            "expiry_date" to expiryDate
                        )
                        authRequest("admin_allocate_risk", values) { ok, result ->
                            toast(result.optString("message", if (ok) "Plan dates assigned." else "Unable to assign plan dates."))
                            if (ok) loadAdminUsers()
                        }
                        dismiss()
                    }
                }
                show()
            }
    }

    private fun showEditUserDialog(user: JSONObject) {
        val firstName = android.widget.EditText(this).apply {
            hint = "First name"
            setSingleLine(true)
            setText(user.optString("first_name", user.optString("full_name", "").substringBefore(" ")))
        }
        val surname = android.widget.EditText(this).apply {
            hint = "Surname"
            setSingleLine(true)
            setText(user.optString("surname", user.optString("full_name", "").substringAfter(" ", "")))
            }
        val email = android.widget.EditText(this).apply {
            hint = "Email address"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setSingleLine(true)
            setText(user.optString("email", ""))
        }
        val phone = android.widget.EditText(this).apply {
            hint = "Contact number"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setSingleLine(true)
            setText(user.optString("phone", ""))
            }
        val password = android.widget.EditText(this).apply {
            hint = "New password (leave blank to keep current password)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val riskPlans = listOf("Minimum", "Medium", "Maximum", "Super Maximum", "Trial")
        val riskSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, riskPlans)
            val existingRisk = user.optString("risk_profile", "").replaceFirstChar { it.uppercase() }
            val normalizedRisk = existingRisk.replace("_", " ").replace("-", " ")
            val idx = riskPlans.indexOfFirst { it.equals(normalizedRisk, true) }
            if (idx >= 0) setSelection(idx)
        }
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
            addView(firstName)
            addView(surname)
            addView(email)
            addView(phone)
            addView(label("Risk plan (30 days)", 14f, yellow, true))
            addView(riskSpinner)
            addView(label("Minimum: 1/day · Medium: 2/day · Maximum: 3/day · Super Maximum: unlimited/day · Trial: 7 days", 12f, white, false))
            addView(password)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Edit user details and risk plan")
            .setView(fields)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("SAVE", null)
            .create().apply {
                setOnShowListener {
                    getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val id = user.optString("id", "")
                        val first = firstName.text.toString().trim()
                        val last = surname.text.toString().trim()
                    val mail = email.text.toString().trim()
                        val contact = phone.text.toString().trim()
                        val newPassword = password.text.toString()
                        val selectedRisk = riskPlans[riskSpinner.selectedItemPosition]
                        val tradesPerDay = when (selectedRisk) {
                            "Minimum" -> "1"
                            "Medium" -> "2"
                            "Maximum" -> "3"
                            "Super Maximum" -> "unlimited"
                            else -> "1"
                        }
                        val planDays = if (selectedRisk == "Trial") 7 else 30
                        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = dailyCapitalTimeZone }
                        val planStartDate = dateFormat.format(java.util.Date())
                        val planExpiryCalendar = java.util.Calendar.getInstance().apply {
                            timeZone = dailyCapitalTimeZone
                            add(java.util.Calendar.DAY_OF_YEAR, planDays - 1)
                        }
                        val planExpiryDate = dateFormat.format(planExpiryCalendar.time)
                        if (first.isBlank() || last.isBlank() || mail.isBlank() || contact.length < 7) {
                            toast("Enter name, surname, email and a valid contact number.")
                            return@setOnClickListener
                        }
                        val values = mutableMapOf(
                            "user_id" to id,
                            "first_name" to first,
                            "surname" to last,
                            "email" to mail,
                            "phone" to contact,
                            "risk_profile" to selectedRisk.lowercase(),
                            "risk_trades_per_day" to tradesPerDay,
                            "risk_period_days" to planDays.toString(),
                            "start_date" to planStartDate,
                            "expiry_date" to planExpiryDate
                        )
                        if (newPassword.isNotEmpty()) {
                            if (newPassword.length < 8) {
                                toast("A new password must contain at least 8 characters.")
                                return@setOnClickListener
                            }
                            values["password"] = newPassword
                        }
                        authRequest("admin_edit_user", values) { ok, result ->
                            if (!ok) {
                                toast(result.optString("message", "Unable to update user details."))
                                return@authRequest
                            }
                            val riskValues = mapOf(
                                "user_id" to id,
                                "risk_profile" to selectedRisk.lowercase(java.util.Locale.US),
                                "risk_percent" to "20",
                                "plan_id" to (selectedRisk.lowercase(java.util.Locale.US).replace(" ", "_") + "_${planDays}_days"),
                                "start_date" to planStartDate,
                                "expiry_date" to planExpiryDate
                            )
                            authRequest("admin_allocate_risk", riskValues) { riskOk, riskResult ->
                                toast(riskResult.optString("message", if (riskOk) "User details and risk plan saved." else "User details saved, but risk plan allocation failed."))
                                if (riskOk) loadAdminUsers() else loadAdminUsers()
                            }
                        }
                        dismiss()
                    }
                }
                show()
            }
    }

    private fun loadAdminUsers() {
        if (authToken.isBlank()) {
            toast("Sign in with the administrator account first.")
            return
        }
        if (adminUsersLoading) return
        adminUsersLoading = true
        adminUsers = null
        val requestedToken = authToken
        val requestedGeneration = authSessionGeneration
        render()
        authRequest("admin_users", token = requestedToken) { ok, result ->
            if (requestedGeneration != authSessionGeneration || requestedToken != authToken) return@authRequest
            adminUsersLoading = false
            if (ok) {
                adminUsers = result.optJSONArray("users") ?: JSONArray()
                if (selectedTab == "User Accounts") render()
            } else {
                adminUsers = null
                if (selectedTab == "User Accounts") render()
                toast(result.optString("message", "Unable to load users."))
            }
        }
    }
    private fun addRiskCategoriesScreen() {
        page.addView(sectionTitle("RISK CATEGORIES"))
        page.addView(label("Trading capital range: R1,000 to R5,000. Interest is administrator-only. Users see plan name, interest period label only if permitted, subscription amount, period, trades used today, stake, target, trade limit, and assigned dates in My Profile.", 13f, muted, false))
        val rules = listOf(
            listOf("MINIMUM RISK", "Interest per 30 days: 50%", "Subscription: 25% of capital", "Period: 30 days", "Stake: 20% of capital", "Target profit: 20% of stake", "Trades per day: 1"),
            listOf("MEDIUM RISK", "Interest per 30 days: 100%", "Subscription: 75% of capital", "Period: 30 days", "Stake: 20% of capital", "Target profit: 20% of stake", "Trades per day: 2"),
            listOf("MAXIMUM RISK", "Interest per 30 days: 200%", "Subscription: 100% of capital", "Period: 30 days", "Stake: 20% of capital", "Target profit: 20% of stake", "Trades per day: 3"),
            listOf("SUPER MAXIMUM RISK", "Period: 30 days", "Stake: 20% of capital", "Target profit: 20% per stake", "Trades per day: Unlimited"),
            listOf("TRIAL", "Interest per 7 days: 50%", "Subscription: Free", "Period: 7 days", "Stake: 20% of capital", "Target profit: 20% per stake", "Trade limit: Not specified")
        )
        rules.forEach { details ->
            val ruleCard = card().apply { orientation = LinearLayout.VERTICAL }
            ruleCard.addView(label(details.first(), 16f, green, true))
            details.drop(1).forEach { detail ->
                val adminOnlyInterest = detail.startsWith("Interest:") || detail.startsWith("Interest per ")
                if (platform == "ADMIN" || !adminOnlyInterest) ruleCard.addView(label(detail, 13f, if (adminOnlyInterest) yellow else white, adminOnlyInterest))
            }
            page.addView(ruleCard, matchWrap().apply { topMargin = dp(6); bottomMargin = dp(6) })
        }
        page.addView(button("ASSIGN / EDIT A USER'S RISK PLAN", true) { selectedTab = "User Accounts"; loadAdminUsers() }, matchWrap().apply { topMargin = dp(8) })
        page.addView(label("Start and expiry dates are assigned by the administrator. Backend support is required for shared risk applications/payment status; a local application cannot itself notify the server administrator.", 12f, yellow, false).apply { setPadding(0, dp(10), 0, 0) })
    }
    private fun addTradingLogsScreen() {
        page.addView(sectionTitle("TRADING LOGS"))
        val card = card().apply { orientation = LinearLayout.VERTICAL }
        card.addView(label("Recent local trading events", 16f, green, true))
        val logs = appPrefs.getString("notification_feed", "").orEmpty().lines().filter { it.isNotBlank() }
        if (logs.isEmpty()) card.addView(label("No local events recorded yet.", 13f, yellow, false))
        else logs.take(50).forEach { card.addView(label(it, 12f, white, false).apply { setPadding(0, dp(5), 0, dp(5)) }) }
        card.addView(button("REFRESH DERIV TRADES", false) {
            if (connected) { derivApiClient.requestOpenTrades(); derivApiClient.requestTradeHistory(); toast("Refreshing open trades and history.") }
            else toast("Connect to Deriv first.")
        }, matchWrap().apply { topMargin = dp(8) })
        page.addView(card, matchWrap())
    }

    private fun addNotificationsScreen() {
        page.addView(sectionTitle("NOTIFICATIONS"))
        val card = card().apply { orientation = LinearLayout.VERTICAL }
        card.addView(label("Trade and risk notifications", 18f, green, true))
        card.addView(label("Notifications are off until you activate them here. When enabled, the app records trade events in this feed and posts Android notifications when permission is granted.", 13f, muted, false))
        card.addView(button(if (notificationsEnabled) "TURN NOTIFICATIONS OFF" else "ACTIVATE NOTIFICATIONS", notificationsEnabled) {
            if (!notificationsEnabled) {
                appPrefs.edit().putBoolean("notificationsEnabled", true).apply()
                ensureNotificationPermission()
                notifyEvent("Notifications activated", "Trade and risk event notifications are now enabled.")
            } else {
                appPrefs.edit().putBoolean("notificationsEnabled", false).apply()
                addNotificationFeedItem("Notifications deactivated")
            }
            render()
        }, matchWrap().apply { topMargin = dp(10) })
        card.addView(label("Notification permission: ${if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) "GRANTED / NOT REQUIRED" else "NOT GRANTED — allow it in Android settings"}", 12f, yellow, false).apply { setPadding(0, dp(8), 0, dp(4)) })
        card.addView(button("CLEAR NOTIFICATION FEED", false) {
            appPrefs.edit().remove("notification_feed").apply()
            render()
        }, matchWrap().apply { topMargin = dp(6) })
        page.addView(card, matchWrap().apply { bottomMargin = dp(10) })
        page.addView(sectionTitle("RECENT NOTIFICATIONS"))
        val saved = appPrefs.getString("notification_feed", "").orEmpty().lines().filter { it.isNotBlank() }
        if (saved.isEmpty()) page.addView(label("No notifications yet.", 13f, yellow, false))
        else saved.take(50).forEach { item ->
            val row = card().apply { addView(label(item, 12f, white, false)) }
            page.addView(row, matchWrap().apply { bottomMargin = dp(5) })
        }
    }
    private fun addSettingsScreen() {
        page.addView(sectionTitle("SETTINGS"))
        val card = card().apply { orientation = LinearLayout.VERTICAL }
        card.addView(label("Account and notification settings", 16f, green, true))
        card.addView(button("OPEN NOTIFICATIONS", true) { selectedTab = "Notifications"; render() }, matchWrap())
        card.addView(button("MY PROFILE", false) { selectedTab = "My profile"; render() }, matchWrap().apply { topMargin = dp(6) })
        card.addView(button("SIGN OUT", false) { performPlatformLogout() }, matchWrap().apply { topMargin = dp(6) })
        page.addView(card, matchWrap())
    }

    private fun addNotificationFeedItem(message: String) {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).apply { timeZone = dailyCapitalTimeZone }.format(java.util.Date())
        val entry = "$stamp · $message"
        val current = appPrefs.getString("notification_feed", "").orEmpty().lines().filter { it.isNotBlank() }.toMutableList()
        current.add(0, entry)
        appPrefs.edit().putString("notification_feed", current.take(50).joinToString("\n")).apply()
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7802)
        }
    }
    private fun recordExecutionStage(title: String, message: String, eventKey: String) {
        notifyEvent(title, message, eventKey)
    }
    private fun notifyEvent(title: String, message: String, eventKey: String = "") {
        val stableKey = eventKey.ifBlank {
            "${saDayKey()}_${accountId}_${executionMode}_${title}_${message}".hashCode().toString()
        }
        val seen = appPrefs.getStringSet("notification_seen_keys", emptySet()).orEmpty().toMutableSet()
        if (!seen.add(stableKey)) return
        appPrefs.edit().putStringSet("notification_seen_keys", seen.toList().takeLast(500).toSet()).apply()
        addNotificationFeedItem("$title — $message")
        if (!notificationsEnabled) return
        try {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "vi_trade_events_v2"
            val vibrationPattern = longArrayOf(0L, 250L, 150L, 250L, 150L, 250L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(channelId, "Trading notifications", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    enableVibration(true)
                    setVibrationPattern(vibrationPattern)
                }
                manager.createNotificationChannel(channel)
            }
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pendingIntent = PendingIntent.getActivity(this, 7803, intent, PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0))
            val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, channelId).setSmallIcon(R.drawable.volatilities_indicator_logo)
                    .setContentTitle(title).setContentText(message)
                    .setStyle(Notification.BigTextStyle().bigText(message)).setContentIntent(pendingIntent)
                    .setAutoCancel(true).setVibrate(vibrationPattern).build()
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this).setSmallIcon(R.drawable.volatilities_indicator_logo)
                    .setContentTitle(title).setContentText(message).setContentIntent(pendingIntent)
                    .setAutoCancel(true).setVibrate(vibrationPattern).build()
            }
            val notificationId = 4200 + (stableKey.hashCode() and 0x7fffffff) % 100000
            manager.notify(notificationId, notification)
        } catch (_: Exception) {
            // The in-app notification feed remains available if Android notification delivery fails.
        }
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
            "Risk Categories",
            "User Accounts",
            "Notifications",
            "Trading Logs",
            "System Settings"
        ).forEach { item ->
            card.addView(
                button("⚙  $item", false) {
                    selectedTab = when (item) {
                        "Risk Categories" -> "Risk Categories"
                        "User Accounts" -> "User Accounts"
                        "Notifications" -> "Notifications"
                        "Trading Logs" -> "Trading Logs"
                        "System Settings" -> "Settings"
                        else -> "Dashboard"
                    }
                    if (selectedTab == "User Accounts") loadAdminUsers() else render()
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
        // The application bottom tabs are visible only after the platform account is signed in.
        if (authToken.isBlank()) return
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(3), dp(7), dp(3), dp(5))
            background = shape(
                Color.rgb(4, 12, 10),
                greenDark,
                12,
                1
            )
        }

        val tabs = if (authToken.isBlank()) {
            listOf("⌂" to "Home", "↪" to "Login", "+" to "Register")
        } else if (platform == "ADMIN") {
            listOf("⌂" to "Home", "▥" to "Trade", "⌁" to "Analysis", "🔔" to "Notifications", "⚙" to "Settings")
        } else {
            listOf("⌂" to "Home", "▥" to "Trade", "🔔" to "Notifications", "♙" to "My profile")
        }

        tabs.forEach { (icon, name) ->
            val selected = selectedTab == name
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(2), dp(3), dp(2), dp(2))
                background = shape(
                    Color.rgb(4, 12, 10),
                    if (selected) greenDark else Color.rgb(40, 68, 72),
                    12,
                    1
                )
                setOnClickListener {
                    selectedTab = name
                    drawerOpen = false
                    render()
                }
            }
            item.addView(
                label(icon, 23f, if (selected) green else yellow, true).apply {
                    gravity = Gravity.CENTER
                }
            )
            item.addView(
                label(name, 10f, if (selected) green else yellow, selected).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                    includeFontPadding = true
                }
            )
            nav.addView(item, LinearLayout.LayoutParams(0, dp(68), 1f))
        }

        outer.addView(nav, LinearLayout.LayoutParams(-1, dp(76)))
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
        appPrefs.edit().putBoolean("deriv_manually_disconnected", false).apply()
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
    if (token.isBlank()) {
        toast("Deriv access token is empty.")
        return
    }

    connected = false
    marketDataRequested = false
        accountId = ""
    accountType = ""
    accountBalance = 0.0
    accountCurrency = ""

    render()
    derivApiClient.connectWithAccessToken(token, executionMode)
    }

    private fun disconnectDeriv(clearSavedTokens: Boolean = false) {
        try { derivApiClient.disconnect() } catch (_: Exception) {}
        appPrefs.edit().putBoolean("deriv_manually_disconnected", true).apply()
        if (clearSavedTokens) {
            prefs.edit().remove("demo_token").remove("real_token").remove("oauth_state")
                .remove("code_verifier").remove("oauth_mode").apply()
        }
        connected = false
        marketDataRequested = false
        accountId = ""
        accountType = ""
        accountBalance = 0.0
        accountCurrency = ""
        // These views only exist on screens that render the account card.
        // Analysis and other tabs can be active while Deriv callbacks arrive.
        if (::accountStatus.isInitialized) {
            accountStatus.text = "●  NOT CONNECTED"
            accountStatus.setTextColor(red)
        }
        if (::accountIdView.isInitialized) accountIdView.text = "Account:  —"
        if (::balanceView.isInitialized) balanceView.text = "Balance:  —"
        toast("Deriv disconnected.")
        }

    override fun onConnecting(message: String) {
        runOnUiThread {
            if (::accountStatus.isInitialized) {
                accountStatus.text = "●  CONNECTING TO DERIV"
                accountStatus.setTextColor(yellow)
            }
        }
    }
    override fun onConnected(accountId: String, accountType: String) {
    runOnUiThread {
        if (this.accountId.isNotBlank() && this.accountId != accountId) resetAccountScopedTradeData()
        this.accountId = accountId
        this.accountType = accountType
        historyAccountKey = "$executionMode:$accountId"
        portfolioLoadedForAccount = false
        lastOpenTradesRequestAt = android.os.SystemClock.elapsedRealtime()
        derivApiClient.requestOpenTrades()
        derivApiClient.requestTradeHistory()
        marketDataRequested = true
        derivApiClient.requestHistoricalCandles()
        connected = true
        render()
        toast("Deriv $executionMode connected.")
    }
    }
    override fun onBalance(balance: Double, currency: String) {
        runOnUiThread {
            accountBalance = balance; accountCurrency = currency; connected = true
            if (::balanceView.isInitialized) {
                balanceView.text = "Balance:  $currency ${String.format(java.util.Locale.US, "%.2f", balance)}"
            }
            if (::accountStatus.isInitialized) {
                accountStatus.text = "●  CONNECTED — $executionMode"
                accountStatus.setTextColor(green)
            }
        }
    }
    override fun onTick(symbol: String, quote: Double, epoch: Long) {
    runOnUiThread {
        if (symbol in marketDataIndexes && quote.isFinite()) {
            marketDataPrices[symbol] = quote
            marketDataErrors.remove(symbol)
            dashboardPriceViews[symbol]?.text = formatMarketPrice(quote)
            currentPriceViews[symbol]?.forEach { priceView ->
                priceView.text = "CURRENT PRICE: ${formatMarketPrice(quote)}"
                priceView.setTextColor(blue)
            }
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - (lastPtpeEvaluationAt[symbol] ?: 0L) >= 3000L) {
                lastPtpeEvaluationAt[symbol] = now
                // Heavy historical analysis is limited to one pass per index per interval.
                // Reuse its result for this execution check instead of calculating it twice.
                val decision = evaluateMasterDecision(symbol)
                updatePtpeDisplay(symbol)
                if (decision != null) attemptMasterDecisionExecution(symbol, decision)
            }
        }
    }
}

    override fun onTradeData(json: JSONObject) {
    runOnUiThread {
        handleMasterDecisionTradeResponse(json)
        val messageType = json.optString("msg_type", "")

        when (messageType) {
            "portfolio" -> {
                openTradesResponse = json
                portfolioLoadedForAccount = true
                val contracts = json.optJSONObject("portfolio")?.optJSONArray("contracts") ?: json.optJSONArray("contracts")
                if (contracts != null) for (i in 0 until contracts.length()) {
                    val c = contracts.optJSONObject(i) ?: continue
                    val id = c.optLong("contract_id", 0L)
                    if (id > 0L) {
                        if (!runningContracts.containsKey(id)) {
                            val symbol = normalizedSymbol(c).ifBlank { "UNKNOWN" }
                            val stake = c.optDouble("buy_price", c.optDouble("purchase_price", 0.0))
                            // An execution barrier is not necessarily the technical structure target.
                            // Keep the structure target unknown unless Master Decision supplied it.
                            val barrier = 0.0
                            val contractType = c.optString("contract_type", "").uppercase()
                            val direction = if (contractType.contains("PUT") || contractType.contains("FALL")) "SELL" else "BUY"
                            if (stake > 0.0) runningContracts[id] = RunningContract(id, symbol, direction, stake, barrier, 0.0, executionMode)
                        }
                        derivApiClient.send(JSONObject().put("proposal_open_contract", 1).put("contract_id", id).put("subscribe", 1))
                    }
                }
                if (selectedTab == "Trade") render()
            }
            "proposal_open_contract" -> {
                openContractResponse = json
                val contract = json.optJSONObject("proposal_open_contract")
                if (contract != null) {
                    val id = contract.optLong("contract_id", 0L)
                    val status = contract.optString("status", "")
                    if (id > 0L) {
                        if (status == "sold" || status == "won" || status == "lost" || contract.optInt("is_sold", 0) == 1) {
                            liveContractProfits.remove(id)
                            liveContractProfitModes.remove(id)
                        } else {
                            liveContractProfits[id] = contract.optDouble("profit", contract.optDouble("profit_open", 0.0))
                            liveContractProfitModes[id] = "${accountId}_$executionMode"
                        }
                    }
                    if (id > 0L) {
                        val liveProfit = contract.optDouble("profit", contract.optDouble("profit_open", liveContractProfits[id] ?: 0.0))
                        val views = contractLiveViews[id]
                        if (views != null) {
                            views.pnl.text = "Stake: ${formatMoney(views.stake)}   P/L: ${formatMoney(liveProfit)}"
                            views.pnl.setTextColor(profitColor(liveProfit))
                            val spot = contract.optDouble("current_spot", contract.optDouble("current_spot_display_value", 0.0))
                            views.spot.text = "Current value/spot: ${if (spot > 0.0) formatMoney(spot) else "—"}"
                            val barrierValue = contract.optString("barrier", "").takeIf { it.isNotBlank() && it != "null" }
                                ?: views.target?.let { String.format(java.util.Locale.US, "%.3f", it) } ?: "—"
                            val expiryEpoch = contract.optLong("date_expiry", contract.optLong("expiry_time", 0L))
                            val expiryRemaining = if (expiryEpoch > 0L) (expiryEpoch - System.currentTimeMillis() / 1000L).coerceAtLeast(0L) else 0L
                            val expiryText = if (expiryEpoch > 0L) String.format(java.util.Locale.US, "%02d:%02d:%02d", expiryRemaining / 3600, (expiryRemaining % 3600) / 60, expiryRemaining % 60) else "—"
                            views.barrierExpiry.text = "Expiry: $expiryText"
                            views.ptpe.text = "PTPE: ${String.format(java.util.Locale.US, "%.1f", views.probability)}%"
                        }
                    }
                    val pl = liveContractProfits.filterKeys { liveContractProfitModes[it] == "${accountId}_$executionMode" }.values.sum()
                    if (::openPlView.isInitialized) {
                        openPlView.text = "Open P/L:  ${formatMoney(pl)}"
                        openPlView.setTextColor(profitColor(pl))
                    }
                    runningTotalPlView?.apply {
                        text = "TOTAL OPEN P/L: ${formatMoney(pl)}"
                        setTextColor(profitColor(pl))
                    }
                    if ((status == "sold" || status == "won" || status == "lost" || contract.optInt("is_sold", 0) == 1) && selectedTab == "Trade") render()
                }
            }
            "profit_table" -> {
                if (connected && accountId.isNotBlank()) tradeHistoryResponse = json
            }
        }

        // Do not rebuild the entire Trade view for every live quote/contract update.
        // Re-rendering here caused the ScrollView to reset/jump while users scrolled.
        // The explicit Refresh button requests fresh account/trade data when needed.
    }

    override fun onCandles(
    symbol: String,
    candles: List<DerivApiClient.Candle>
) {
    runOnUiThread {
        marketDataCandles[symbol] = candles
        marketDataErrors.remove(symbol)
        evaluateMasterDecision(symbol)
        updatePtpeDisplay(symbol)

        if (selectedTab == "Analysis") {
            pendingAnalysisRender?.let { analysisRenderHandler.removeCallbacks(it) }
            val refresh = Runnable {
                pendingAnalysisRender = null
                if (selectedTab == "Analysis") render()
            }
            pendingAnalysisRender = refresh
            analysisRenderHandler.postDelayed(refresh, 450L)
        }
    }
}

override fun onMarketDataError(
    symbol: String,
    message: String
) {
    runOnUiThread {
        marketDataErrors[symbol] = message
        dashboardStatusViews[symbol]?.apply {
            text = "FEED ERROR"
            setTextColor(red)
        }
        android.util.Log.e("DerivMarketData", "$symbol: $message")
    }
}

    override fun onError(message: String) {
    runOnUiThread {
        if (!connected && ::accountStatus.isInitialized) {
            accountStatus.text = "●  CONNECTION ERROR"
            accountStatus.setTextColor(red)
        }
        toast("Deriv: $message")
    }
    }
    override fun onDisconnected() {
        runOnUiThread {
            connected = false
            marketDataRequested = false
            if (::accountStatus.isInitialized) {
                accountStatus.text = "●  NOT CONNECTED"
                accountStatus.setTextColor(red)
            }
        }
    }

                                        }

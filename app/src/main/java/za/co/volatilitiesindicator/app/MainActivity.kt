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
import org.json.JSONArray
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
    private var lastRenderedTab: String? = null
    private lateinit var accountStatus: TextView
    private lateinit var accountIdView: TextView
    private lateinit var balanceView: TextView
    private lateinit var openPlView: TextView
    private val liveContractProfits = mutableMapOf<Long, Double>()
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
    private var adminUsers: JSONArray? = null
    private var adminUsersLoading: Boolean = false
    private var openTradesResponse: JSONObject? = null
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
        var takeProfitRequested: Boolean = false
    )

    private val pendingOrdersByRequest = mutableMapOf<Int, PendingOrder>()
    private val pendingIndexSymbols = mutableSetOf<String>()
    private val runningContracts = mutableMapOf<Long, RunningContract>()
    private var nextTradeRequestId = 710000
    private var manualTradeDirection = "BUY"
    private val lastDecisionByIndex = mutableMapOf<String, MasterDecision>()
    private val dailyCapitalTimeZone = java.util.TimeZone.getTimeZone("Africa/Johannesburg")

    private fun nextTradeRequestId(): Int = ++nextTradeRequestId

    private fun saDayKey(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        .apply { timeZone = dailyCapitalTimeZone }.format(java.util.Date())

    /** Freeze the balance first observed for this account mode on the SA calendar day. */
    private fun dailyStartingCapital(): Double {
        if (accountBalance <= 0.0) return 0.0
        val key = "daily_start_${executionMode}_${saDayKey()}"
        val existing = appPrefs.getString(key, null)?.toDoubleOrNull()
        if (existing != null && existing > 0.0) return existing
        appPrefs.edit().putString(key, accountBalance.toString()).apply()
        return accountBalance
    }

    private fun activeContractForIndex(symbol: String): Boolean {
        if (runningContracts.values.any { it.symbol == symbol && it.mode == executionMode }) return true
        val portfolio = openTradesResponse?.optJSONObject("portfolio")
            ?: openTradesResponse?.optJSONObject("echo_req")
        val arr = portfolio?.optJSONArray("contracts")
            ?: openTradesResponse?.optJSONArray("contracts")
        if (arr != null) for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val contractSymbol = item.optString("underlying", item.optString("symbol", ""))
            if (contractSymbol == symbol && item.optString("is_sold", "0") != "1") return true
        }
        return pendingIndexSymbols.contains(symbol)
    }

    /** Empirical PTPE: among prior same-direction MSS events, how often was an equivalent
 * percentage target touched within the following 3-day (72-hour) window? */
    private fun targetProbabilityPercent(
        candles: List<DerivApiClient.Candle>, direction: String, targetDistancePercent: Double
    ): Pair<Double, Int> {
        if (candles.size < 100 || targetDistancePercent <= 0.0) return 0.0 to 0
        val outcomes = analysisHistoricalOutcomes(candles).filter { it.direction == direction }
        var samples = 0
        var hits = 0
        for (outcome in outcomes) {
            val start = candles.indexOfFirst { it.epoch == outcome.confirmation.epoch }
            val evaluationCandles = 3 * 24
            if (start < 0 || start + evaluationCandles >= candles.size) continue // require a full 72-hour forward window
            val entry = outcome.confirmation.close
            if (entry <= 0.0 || !entry.isFinite()) continue
            val target = if (direction == "BULLISH") entry * (1.0 + targetDistancePercent / 100.0)
                         else entry * (1.0 - targetDistancePercent / 100.0)
            samples++
            var touched = false
            for (i in (start + 1)..(start + evaluationCandles)) {
                val candle = candles[i]
                if ((direction == "BULLISH" && candle.high >= target) ||
                    (direction == "BEARISH" && candle.low <= target)) { touched = true; break }
            }
            if (touched) hits++
        }
        if (samples == 0) return 0.0 to 0
        return (hits.toDouble() / samples.toDouble() * 100.0) to samples
    }

    /** Sole authority for trade approval. No technical score is relabelled as probability. */
    private fun evaluateMasterDecision(symbol: String): MasterDecision? {
        val candles = analysisCompletedCandles(symbol)
        if (candles.size < 100) return null
        val current = marketDataPrices[symbol] ?: candles.lastOrNull()?.close ?: return null
        if (!current.isFinite() || current <= 0.0) return null
        val event = analysisLatestMss(candles) ?: return null
        val direction = event.direction
        val targetDirection = if (direction == "BULLISH") "UP" else "DOWN"
        val targetPivot = analysisStructuralTarget(candles, current, targetDirection, event.confirmation.epoch) ?: return null
        val target = targetPivot.price
        if (!target.isFinite() || (direction == "BULLISH" && target <= current) ||
            (direction == "BEARISH" && target >= current)) return null
        val distance = kotlin.math.abs(target - current) / current * 100.0
        if (distance < 1.5) return null
        val (probability, samples) = targetProbabilityPercent(candles, direction, distance)
        if (samples < 5 || probability < 80.0) return null
        // Historical evidence must include at least one prior same-direction outcome.
        if (analysisHistoricalOutcomes(candles).none { it.direction == direction && it.confirmation.epoch != event.confirmation.epoch }) return null
        return MasterDecision(symbol, if (direction == "BULLISH") "BUY" else "SELL", current, target,
            distance, probability, samples, "Confirmed MSS/CHOCH + structural target + empirical 3-day PTPE")
    }

    private fun attemptMasterDecisionExecution(symbol: String) {
        if (!autoTradeEnabled || !connected || accountId.isBlank() || accountBalance <= 0.0) return
        if (symbol !in marketDataIndexes || activeContractForIndex(symbol)) return
        val decision = evaluateMasterDecision(symbol) ?: return
        lastDecisionByIndex[symbol] = decision
        val startCapital = dailyStartingCapital()
        val stake = kotlin.math.round(startCapital * 0.20 * 100.0) / 100.0
        if (stake <= 0.0 || stake > accountBalance) return
        val barrierOffset = decision.target - decision.entry
        if (!barrierOffset.isFinite() || kotlin.math.abs(barrierOffset) < 0.000001) return
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
            .put("barrier", (if (barrierOffset > 0) "+" else "") + String.format(java.util.Locale.US, "%.2f", barrierOffset))
            .put("req_id", reqId)
        if (!derivApiClient.send(proposal)) {
            pendingOrdersByRequest.remove(reqId); pendingIndexSymbols.remove(symbol)
        }
    }

    private fun handleMasterDecisionTradeResponse(json: JSONObject) {
        val msgType = json.optString("msg_type", "")
        val reqId = json.optInt("req_id", -1)
        if (json.has("error")) {
            val failed = pendingOrdersByRequest.remove(reqId)
            if (failed != null) pendingIndexSymbols.remove(failed.symbol)
            return
        }
        val pending = pendingOrdersByRequest[reqId]
        if (pending != null && msgType == "proposal") {
            val proposal = json.optJSONObject("proposal")
            val proposalId = proposal?.optString("id", "").orEmpty()
            val payout = proposal?.optDouble("payout", 0.0) ?: 0.0
            if (proposalId.isBlank() || payout <= 0.0) {
                pendingOrdersByRequest.remove(reqId); pendingIndexSymbols.remove(pending.symbol); return
            }
            val buyReqId = nextTradeRequestId()
            pendingOrdersByRequest.remove(reqId)
            pendingOrdersByRequest[buyReqId] = pending.copy(stage = "BUY")
            if (!derivApiClient.send(JSONObject().put("buy", proposalId).put("price", pending.stake).put("req_id", buyReqId))) {
                pendingOrdersByRequest.remove(buyReqId); pendingIndexSymbols.remove(pending.symbol)
            }
            return
        }
        if (pending != null && msgType == "buy") {
            val buy = json.optJSONObject("buy")
            val contractId = buy?.optLong("contract_id", 0L) ?: 0L
            if (contractId <= 0L) {
                pendingOrdersByRequest.remove(reqId); pendingIndexSymbols.remove(pending.symbol); return
            }
            runningContracts[contractId] = RunningContract(contractId, pending.symbol, pending.direction,
                pending.stake, pending.target, pending.targetProbability, pending.mode)
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
            val profitOpen = contract.optDouble("profit", contract.optDouble("profit_open", 0.0))
            if (!running.takeProfitRequested && status != "sold" && status != "won" && status != "lost" &&
                profitOpen >= running.stake * 0.10) {
                running.takeProfitRequested = true
                derivApiClient.send(JSONObject().put("sell", contractId).put("price", 0).put("req_id", nextTradeRequestId()))
            }
            if (status == "sold" || status == "won" || status == "lost" || contract.optInt("is_sold", 0) == 1) {
                runningContracts.remove(contractId)
                pendingIndexSymbols.remove(running.symbol)
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
private val currentPriceViews = mutableMapOf<String, MutableList<TextView>>()
private val marketDataErrors =
    mutableMapOf<String, String>()

private var marketDataRequested = false
private var marketStructureExpanded = true
private var mssChochExpanded = true
private var marketDataExpanded = true
private var mssChochBehaviourExpanded = true
private var directionStructuralTargetExpanded = true
private var qualificationExpanded = true
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

if (authToken.isNotBlank() && savedToken.isNotBlank() && intent?.data?.host != "oauth-callback") {
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
    addTargetFilterAnalysis()
                }

            "Trade" -> {
                addTradeScreen()
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
                authRequest("register", mapOf(
                    "first_name" to name,
                    "surname" to surname,
                    "email" to email,
                    "phone" to phone,
                    "password" to password,
                    "password_confirm" to confirmPassword
                ), token = "") { ok, result ->
                    submit.isEnabled = true
                    submit.text = "CREATE ACCOUNT"
                    if (ok) {
                        val message = result.optString("message", "Application submitted. Await administrator approval.")
                        val token = result.optString("token", "")
                        if (token.isNotBlank()) {
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
                authRequest("login", mapOf("email" to email, "password" to password), token = "") { ok, result ->
                    signIn.isEnabled = true
                    signIn.text = "SIGN IN"
                    if (ok) {
                        authToken = result.optString("token", "")
                        authPrefs.edit().putString("mobile_auth_token", authToken).apply()
                        currentProfile = result.optJSONObject("user")
                        val role = currentProfile?.optString("role", "user") ?: "user"
platform = if (role.equals("admin", ignoreCase = true)) "ADMIN" else "USER"
                        selectedTab = "My profile"
                        drawerOpen = false
                        render()
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
        profileField("Start of risk plan", u.optString("start_date", "").takeUnless { it == "null" }.orEmpty())
        profileField("Expiry of risk plan", u.optString("expiry_date", "").takeUnless { it == "null" }.orEmpty())
        val status = u.optString("status", "approved").uppercase()
        profileField("Account status", status)
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
    profileLoading = true
    authRequest("profile") { ok, result ->
        profileLoading = false
        if (ok) {
            currentProfile = result.optJSONObject("user")
            if (selectedTab == "My profile" || selectedTab == "Account") render()
            if (showMessage) toast("Profile updated.")
        } else {
            authToken = ""
            currentProfile = null
            authPrefs.edit().remove("mobile_auth_token").apply()
            selectedTab = "Login"
            render()
            toast(result.optString("message", "Please sign in again."))
        }
    }
}

private fun performPlatformLogout() {
    val token = authToken
    authToken = ""
    currentProfile = null
    authPrefs.edit().remove("mobile_auth_token").apply()
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
        openContractResponse = null
        liveContractProfits.clear()
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

        openPlView = label("Open P/L:  ${formatMoney(liveContractProfits.values.sum())}", 14f,
            profitColor(liveContractProfits.values.sum()), true)
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
// =========================================================
// TRADE SCREEN
// =========================================================
private fun addTradeScreen() {
    page.addView(sectionTitle("TRADE CONTROL"))

    // Account and connection status
    val accountCard = card()

    accountCard.addView(
        label("EXECUTION ACCOUNT", 14f, green, true)
    )

    accountCard.addView(
        label("Mode: $executionMode", 13f, white, true)
    )

    accountCard.addView(
        label(
            if (connected) {
                "Connection: CONNECTED"
            } else {
                "Connection: NOT CONNECTED"
            },
            12f,
            if (connected) green else red,
            true
        )
    )

    accountCard.addView(
        label(
            if (accountId.isNotBlank()) {
                "Account ID: $accountId"
            } else {
                "Account ID: —"
            },
            12f,
            muted,
            false
        )
    )

    page.addView(
        accountCard,
        matchWrap().apply {
            bottomMargin = dp(10)
        }
    )

    // Auto Trade: this toggle now enables the integrated Master Decision execution loop.
    page.addView(sectionTitle("MASTER DECISION / AUTO TRADE"))
    val autoTradeCard = card()
    autoTradeCard.addView(label(if (autoTradeEnabled) "MASTER DECISION: ACTIVE" else "MASTER DECISION: OFF", 15f,
        if (autoTradeEnabled) green else red, true))
    autoTradeCard.addView(label("PTPE target probability ≥ 80% • target eligibility ≥ 1.5% • 3-day ONETOUCH • 10% profit close target • stake 20% of South African day-start capital.", 12f, white, false))
    autoTradeCard.addView(label("Master Decision requires confirmed MSS/CHOCH, a valid structural target, and at least five historical comparable 3-day samples . One open contract per index.", 12f, muted, false))
    val gateCard = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(9), dp(10), dp(9))
        background = shape(bg, greenDark, 8, 1)
    }
    gateCard.addView(label("ANALYSIS GATE BY INDEX", 12f, green, true))
    marketDataIndexes.forEach { symbol ->
        val decision = lastDecisionByIndex[symbol]
        val text = if (decision != null) {
            "$symbol • ${decision.direction} • Target ${String.format(java.util.Locale.US, "%.2f", decision.target)} • PTPE ${String.format(java.util.Locale.US, "%.1f", decision.targetProbability)}% • ${decision.sampleCount} samples"
        } else {
            "$symbol • WAITING — all MSS/CHOCH, structural-target and PTPE gates not yet confirmed"
        }
        gateCard.addView(label(text, 11f, if (decision != null) green else yellow, decision != null).apply {
            setPadding(0, dp(5), 0, dp(3))
            })
    }
    autoTradeCard.addView(gateCard, matchWrap().apply { topMargin = dp(8) })
    autoTradeCard.addView(button(if (autoTradeEnabled) "TURN MASTER DECISION OFF" else "TURN MASTER DECISION ON", autoTradeEnabled) {
        autoTradeEnabled = !autoTradeEnabled
        if (autoTradeEnabled && !connected) toast("Master Decision is ON, but execution waits until Deriv is connected.")
        render()
    }, matchWrap().apply { topMargin = dp(8) })
    page.addView(autoTradeCard, matchWrap().apply { bottomMargin = dp(10) })

    // Manual Touch trade — intentionally excludes No Touch and all other contract types.
    page.addView(sectionTitle("MANUAL TOUCH TRADE"))
    val manualCard = card()
    manualCard.addView(label("Manual execution • Touch only", 14f, green, true))
    manualCard.addView(label("Choose an index, direction, duration, barrier offset and stake. This places a Deriv ONETOUCH contract only.", 12f, muted, false))

    val symbolSpinner = Spinner(this)
    val symbolAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
        listOf("R_10", "R_25", "R_50", "R_75", "R_100"))
    symbolAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    symbolSpinner.adapter = symbolAdapter
    manualCard.addView(label("INDEX", 11f, green, true))
    manualCard.addView(symbolSpinner, matchWrap())

    val directionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
    val buyDirection = button("BUY / TOUCHES", manualTradeDirection == "BUY") {
        manualTradeDirection = "BUY"
        render()
    }
    val sellDirection = button("SELL / TOUCHES", manualTradeDirection == "SELL") {
        manualTradeDirection = "SELL"
        render()
    }
    directionRow.addView(buyDirection, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(5) })
    directionRow.addView(sellDirection, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(5) })
    manualCard.addView(directionRow, matchWrap().apply { topMargin = dp(7) })

    fun manualInput(hint: String, value: String, numeric: Boolean = true): android.widget.EditText {
        return android.widget.EditText(this).apply {
            setSingleLine(true)
            setText(value)
            this.hint = hint
            setHintTextColor(muted)
            setTextColor(yellow)
            textSize = 16f
            inputType = if (numeric) android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED else android.text.InputType.TYPE_CLASS_TEXT
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = shape(panel2, greenDark, 8, 1)
        }
    }
    manualCard.addView(label("DURATION (HOURS)", 11f, green, true))
    val durationInput = manualInput("8", "8")
    manualCard.addView(durationInput, matchWrap())
    manualCard.addView(label("BARRIER OFFSET (PRICE UNITS)", 11f, green, true).apply { setPadding(0, dp(7), 0, 0) })
    val barrierInput = manualInput("1.10", "1.10")
    manualCard.addView(barrierInput, matchWrap())
    manualCard.addView(label("STAKE (${accountCurrency.ifBlank { "USD" }})", 11f, green, true).apply { setPadding(0, dp(7), 0, 0) })
    val stakeInput = manualInput("1.00", "1.00")
    manualCard.addView(stakeInput, matchWrap())
    manualCard.addView(button("PURCHASE TOUCH CONTRACT", true) {
        if (!connected || accountId.isBlank()) {
            toast("Connect the selected Deriv account before manual execution.")
            return@button
        }
        val symbol = symbolSpinner.selectedItem?.toString().orEmpty()
        val duration = durationInput.text.toString().toIntOrNull()
        val barrierMagnitude = barrierInput.text.toString().toDoubleOrNull()
        val stake = stakeInput.text.toString().toDoubleOrNull()
        if (duration == null || duration < 1 || barrierMagnitude == null || barrierMagnitude <= 0.0 || stake == null || stake <= 0.0) {
            toast("Enter a duration of at least 1 hour, a positive barrier offset and a positive stake.")
            return@button
        }
        if (stake > accountBalance) {
            toast("Stake exceeds the available account balance.")
            return@button
        }
        if (activeContractForIndex(symbol)) {
            toast("An open or pending contract already exists for $symbol.")
            return@button
        }
        val current = marketDataPrices[symbol] ?: 0.0
        val signedBarrier = if (manualTradeDirection == "BUY") barrierMagnitude else -barrierMagnitude
        val target = if (current > 0.0) current + signedBarrier else signedBarrier
        val reqId = nextTradeRequestId()
        pendingIndexSymbols.add(symbol)
        pendingOrdersByRequest[reqId] = PendingOrder(symbol, manualTradeDirection, current, target, 0.0, stake, executionMode, "PROPOSAL")
        val proposal = JSONObject()
            .put("proposal", 1)
            .put("amount", stake)
            .put("basis", "stake")
            .put("contract_type", "ONETOUCH")
            .put("currency", accountCurrency.ifBlank { "USD" })
            .put("duration", duration)
            .put("duration_unit", "h")
            .put("underlying_symbol", symbol)
            .put("barrier", (if (signedBarrier > 0) "+" else "") + String.format(java.util.Locale.US, "%.2f", signedBarrier))
            .put("req_id", reqId)
        if (!derivApiClient.send(proposal)) {
            pendingOrdersByRequest.remove(reqId)
            pendingIndexSymbols.remove(symbol)
            toast("Unable to submit Touch proposal.")
        } else {
            toast("Touch proposal requested for $symbol.")
        }
    }, matchWrap().apply { topMargin = dp(10) })
    page.addView(manualCard, matchWrap().apply { bottomMargin = dp(12) })

    // Running Trades
    page.addView(sectionTitle("RUNNING TRADES"))

    val runningCard = card()

    runningCard.addView(
        label(
            "OPEN CONTRACT DETAILS",
            13f,
            green,
            true
        )
    )

    val portfolioArray = openTradesResponse?.optJSONObject("portfolio")?.optJSONArray("contracts")
        ?: openTradesResponse?.optJSONArray("contracts")
    var visibleRunning = 0
    if (portfolioArray != null) for (i in 0 until portfolioArray.length()) {
        val item = portfolioArray.optJSONObject(i) ?: continue
        val contractId = item.optLong("contract_id", 0L)
        val symbol = item.optString("underlying", item.optString("symbol", "—"))
        val contractType = item.optString("contract_type", "")
        val direction = if (contractType.contains("DOWN") || contractType.contains("PUT")) "SELL" else "BUY"
        val stake = item.optDouble("buy_price", 0.0)
        val currentValue = item.optDouble("bid_price", item.optDouble("current_spot", 0.0))
        val profit = item.optDouble("profit", item.optDouble("profit_open", 0.0))
        val barrier = item.optString("barrier", "—")
        val expiryEpoch = item.optLong("date_expiry", 0L)
        val expiryText = if (expiryEpoch > 0L) {
            val remain = (expiryEpoch - System.currentTimeMillis() / 1000L).coerceAtLeast(0L)
            String.format(java.util.Locale.US, "%02d:%02d:%02d", remain / 3600, (remain % 3600) / 60, remain % 60)
        } else "—"
        val tradeRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = shape(panel, greenDark, 8, 1)
        }
        tradeRow.addView(label("$symbol • $direction ${if (contractType.contains("TOUCH")) "TOUCH" else contractType}", 14f, green, true))
        tradeRow.addView(label("Contract: $contractId", 12f, white, false).apply { setPadding(0, dp(4), 0, 0) })
        tradeRow.addView(label("Stake: ${String.format(java.util.Locale.US, "%.2f", stake)}   P/L: ${String.format(java.util.Locale.US, "%.2f", profit)}", 12f, if (profit >= 0) blue else red, true).apply { setPadding(0, dp(4), 0, 0) })
        tradeRow.addView(label("Current value/spot: ${if (currentValue > 0) String.format(java.util.Locale.US, "%.2f", currentValue) else "—"}", 12f, white, false).apply { setPadding(0, dp(4), 0, 0) })
        tradeRow.addView(label("Barrier: $barrier   Expiry: $expiryText", 12f, white, false).apply { setPadding(0, dp(4), 0, 0) })
        tradeRow.addView(button("CLOSE TRADE", true) {
            if (connected && contractId > 0L) {
                val sent = derivApiClient.send(JSONObject().put("sell", contractId).put("price", 0).put("req_id", nextTradeRequestId()))
                if (!sent) toast("Unable to send close request.")
                else toast("Close request sent for contract $contractId.")
            } else toast("Connect to the account holding this contract.")
        }, matchWrap().apply { topMargin = dp(8) })
        runningCard.addView(tradeRow, matchWrap().apply { topMargin = dp(7) })
        visibleRunning++
    }
    runningContracts.values.forEach { running ->
        val runningStakeText = String.format(java.util.Locale.US, "%.2f", running.stake)
        val runningProbabilityText = String.format(java.util.Locale.US, "%.1f", running.targetProbability)
        val tradeRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = shape(panel, greenDark, 8, 1)
        }
        tradeRow.addView(label("${running.symbol} • ${running.direction} TOUCH", 14f, green, true))
        tradeRow.addView(label("Contract: ${running.contractId}", 12f, white, false).apply { setPadding(0, dp(4), 0, 0) })
        tradeRow.addView(label("Stake: $runningStakeText   PTPE: $runningProbabilityText%", 12f, white, false).apply { setPadding(0, dp(4), 0, 0) })
        tradeRow.addView(label("Target: ${String.format(java.util.Locale.US, "%.3f", running.target)}", 12f, white, false).apply { setPadding(0, dp(4), 0, 0) })
        tradeRow.addView(button("CLOSE TRADE", true) {
            if (connected) derivApiClient.send(JSONObject().put("sell", running.contractId).put("price", 0).put("req_id", nextTradeRequestId()))
            else toast("Connect to the account holding this contract.")
        }, matchWrap().apply { topMargin = dp(8) })
        runningCard.addView(tradeRow, matchWrap().apply { topMargin = dp(7) })
        visibleRunning++
    }
    if (visibleRunning == 0) runningCard.addView(label("No open contracts returned by Deriv for this account.", 12f, yellow, false))
    runningCard.addView(button("REFRESH RUNNING TRADES & HISTORY", false) {
        if (connected) { derivApiClient.requestOpenTrades(); derivApiClient.requestTradeHistory() } else toast("Connect to Deriv first.")
    }, matchWrap().apply { topMargin = dp(8) })

    page.addView(
        runningCard,
        matchWrap().apply {
            bottomMargin = dp(10)
        }
    )

    // Trading History
    page.addView(sectionTitle("TRADING HISTORY"))

    val historyCard = card()

    historyCard.addView(
        label(
            "ACCOUNT MODE: $executionMode",
            13f,
            green,
            true
        )
    )

    historyCard.addView(
        label(
            "History is displayed for the selected account mode only.",
            12f,
            muted,
            false
        )
    )

    val historyArray = tradeHistoryResponse?.optJSONObject("profit_table")?.optJSONArray("transactions")
        ?: tradeHistoryResponse?.optJSONArray("transactions")
    var historyCount = 0
    if (historyArray != null) for (i in 0 until minOf(historyArray.length(), 20)) {
        val item = historyArray.optJSONObject(i) ?: continue
        val symbol = item.optString("underlying", item.optString("symbol", "—"))
        val contractId = item.optLong("contract_id", 0L)
        val buyPrice = item.optDouble("buy_price", 0.0)
        val sellPrice = item.optDouble("sell_price", 0.0)
        val profit = item.optDouble("sell_price", 0.0) - buyPrice
        val epoch = item.optLong("purchase_time", item.optLong("date_start", 0L))
        val timeText = if (epoch > 0) java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(epoch * 1000L)) else "Time unavailable"
        val buyPriceText = String.format(java.util.Locale.US, "%.2f", buyPrice)
        val profitText = String.format(java.util.Locale.US, "%.2f", profit)
        val statusText = if (sellPrice > 0) "CLOSED" else "OPEN/UNKNOWN"
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = GradientDrawable().apply {
                setColor(Color.rgb(10, 25, 22))
                setStroke(dp(1), if (profit > 0.0) greenDark else if (profit < 0.0) Color.rgb(130, 35, 42) else Color.rgb(45, 65, 58))
                cornerRadius = dp(7).toFloat()
            }
        }
        row.addView(label("$symbol   •   $statusText", 13f, white, true))
        row.addView(label("Contract $contractId   •   $timeText", 11f, muted, false).apply { setPadding(0, dp(4), 0, 0) })
        row.addView(label("STAKE  $buyPriceText     P/L  $profitText", 12f, profitColor(profit), true).apply { setPadding(0, dp(5), 0, 0) })
        historyCard.addView(row, matchWrap().apply { topMargin = dp(6) })
        historyCount++
    }
    if (historyCount == 0) historyCard.addView(label("No completed trade-history records were returned by Deriv yet.", 12f, yellow, false))

    page.addView(
        historyCard,
        matchWrap().apply {
            bottomMargin = dp(10)
        }
    )
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
    if (!marketDataRequested) {
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
            val pivots = analysisPivots(candles)
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
        qualificationCard.addView(
            label(
                "STRUCTURAL TARGET: ${if (hasTarget) "PASS — ${formatMarketPrice(target?.price)}" else "WAIT — NO VALID TARGET"}",
                12f, if (hasTarget) green else yellow, true
            )
        )
        qualificationCard.addView(
            label(
                "HISTORICAL DIRECTION SAMPLE: ${if (hasHistory) "PASS — ${history.size} event(s)" else "WAIT — NO HISTORICAL SAMPLE"}",
                12f, if (hasHistory) green else yellow, true
            )
        )
        val targetDistance = if (target != null && current != null && current > 0.0) {
            kotlin.math.abs(target.price - current) / current * 100.0
        } else null
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
        } else "WAIT — BELOW 80% OR INSUFFICIENT SAMPLES"
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
        val distance = if (target != null && current != null && current != 0.0)
            kotlin.math.abs(target.price - current) / current * 100.0 else null
        val passes = target != null && distance != null && distance > 0.0 &&
            ((direction == "UP" && target.price > current!!) || (direction == "DOWN" && target.price < current!!))
        card.addView(label("DIRECTION: ${direction.ifBlank { "WAITING" }}", 13f,
            if (direction == "UP") green else if (direction == "DOWN") red else yellow, true))
        card.addView(label("TARGET PRICE: ${target?.let { formatMarketPrice(it.price) } ?: "REJECTED / NOT AVAILABLE"}", 13f,
            if (target != null) blue else yellow, true))
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
        val riskPlans = listOf("Minimum", "Medium", "Maximum", "Super Maximum")
        val riskSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, riskPlans)
            val existingRisk = user.optString("risk_profile", "").replaceFirstChar { it.uppercase() }
            val idx = riskPlans.indexOf(existingRisk)
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
            addView(label("Minimum: 1 trade/day · Medium: 3 trades/day · Maximum: 5 trades/day · Super Maximum: unlimited trades/day", 12f, white, false))
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
                            "Medium" -> "3"
                            "Maximum" -> "5"
                            else -> "unlimited"
                        }
                        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                        val planStartDate = dateFormat.format(java.util.Date())
                        val planExpiryCalendar = java.util.Calendar.getInstance().apply {
                            add(java.util.Calendar.DAY_OF_YEAR, 30)
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
                            "risk_period_days" to "30",
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
                            toast(result.optString("message", if (ok) "User details updated." else "Unable to update user."))
                            if (ok) loadAdminUsers()
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
        render()
        authRequest("admin_users") { ok, result ->
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
            setPadding(dp(3), dp(7), dp(3), dp(5))
            background = shape(
                Color.rgb(4, 12, 10),
                greenDark,
                12,
                1
            )
        }

        val tabs = if (platform == "ADMIN") {
            listOf(
                "⌂" to "Home",
                "▥" to "Trade",
                "⌁" to "Analysis",
                "▤" to "News",
                "⚙" to "Settings"
            )
        } else {
            listOf(
                "⌂" to "Home",
                "▥" to "Trade",
                "▤" to "News",
                "♙" to "My profile"
            )
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
    accountId = ""
    accountType = ""
    accountBalance = 0.0
    accountCurrency = ""

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
        if (this.accountId.isNotBlank() && this.accountId != accountId) resetAccountScopedTradeData()
        this.accountId = accountId
        this.accountType = accountType
        historyAccountKey = "$executionMode:$accountId"
        derivApiClient.requestOpenTrades()
        derivApiClient.requestTradeHistory()
        derivApiClient.requestHistoricalCandles()
        connected = true
        render()
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
    runOnUiThread {
        if (symbol in marketDataIndexes && quote.isFinite()) {
            marketDataPrices[symbol] = quote
            marketDataErrors.remove(symbol)
            currentPriceViews[symbol]?.forEach { priceView ->
                priceView.text = "CURRENT PRICE: ${formatMarketPrice(quote)}"
                priceView.setTextColor(blue)
            }
            attemptMasterDecisionExecution(symbol)
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
                val contracts = json.optJSONObject("portfolio")?.optJSONArray("contracts") ?: json.optJSONArray("contracts")
                if (contracts != null) for (i in 0 until contracts.length()) {
                    val c = contracts.optJSONObject(i) ?: continue
                    val id = c.optLong("contract_id", 0L)
                    if (id > 0L) derivApiClient.send(JSONObject().put("proposal_open_contract", 1).put("contract_id", id).put("subscribe", 1))
                }
            }
            "proposal_open_contract" -> {
                openContractResponse = json
                val contract = json.optJSONObject("proposal_open_contract")
                if (contract != null) {
                    val id = contract.optLong("contract_id", 0L)
                    val status = contract.optString("status", "")
                    if (id > 0L) {
                        if (status == "sold" || status == "won" || status == "lost" || contract.optInt("is_sold", 0) == 1) liveContractProfits.remove(id)
                        else liveContractProfits[id] = contract.optDouble("profit", contract.optDouble("profit_open", 0.0))
                    }
                    if (::openPlView.isInitialized) {
                        val pl = liveContractProfits.values.sum()
                        openPlView.text = "Open P/L:  ${formatMoney(pl)}"
                        openPlView.setTextColor(profitColor(pl))
                    }
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
    }
override fun onCandles(
    symbol: String,
    candles: List<DerivApiClient.Candle>
) {
    runOnUiThread {
        marketDataCandles[symbol] = candles
        marketDataErrors.remove(symbol)

        if (selectedTab == "Analysis") {
            render()
        }
    }
}

override fun onMarketDataError(
    symbol: String,
    message: String
) {
    runOnUiThread {
        marketDataErrors[symbol] = message
    }
}

    override fun onError(message: String) {
    runOnUiThread {
        if (!connected) {
            accountStatus.text = "●  CONNECTION ERROR"
            accountStatus.setTextColor(red)
        }
        toast("Deriv: $message")
    }
    }
    override fun onDisconnected() {
        runOnUiThread { connected = false; accountStatus.text = "●  NOT CONNECTED"; accountStatus.setTextColor(red) }
    }

                }

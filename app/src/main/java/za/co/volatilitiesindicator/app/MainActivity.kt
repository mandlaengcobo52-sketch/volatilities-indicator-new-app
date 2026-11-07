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
import android.widget.EditText
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
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
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
private val notificationsEnabled: Boolean
    get() = appPrefs.getBoolean("notificationsEnabled", false)
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
    private val profileRefreshHandler by lazy { Handler(mainLooper) }
    private val profileRefreshRunnable = object : Runnable {
        override fun run() {
            if (authToken.isNotBlank() && platform != "ADMIN") refreshProfile(showMessage = false)
            profileRefreshHandler.postDelayed(this, 15_000L)
        }
    }
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
    // Automatic Deriv recovery while the user has not manually disconnected.
    private val reconnectHandler by lazy { Handler(mainLooper) }
    private var reconnectAttempt = 0
    private var reconnectScheduled = false
    private var reconnectRunnable: Runnable? = null
    private fun scheduleDerivReconnect() {
        if (authToken.isBlank() || appPrefs.getBoolean("deriv_manually_disconnected", false)) return
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
    // onCreate already initiates the first connection; only run the recovery check
    // on later returns to the foreground.
    private var hasResumedOnce = false
    private var accountId = ""
    private var accountType = ""
    private var accountBalance = 0.0
    private var accountCurrency = ""
    // DerivApiClient is owned by DerivConnectionService.
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
            authToken.isNotBlank() &&
            !appPrefs.getBoolean("deriv_manually_disconnected", false)
        ) {
            val key =
                if (executionMode == "REAL") "real_token"
                else "demo_token"

            val saved = prefs.getString(key, "").orEmpty()

            if (saved.isNotBlank()) {
                derivApiClient.ensureConnectedOnResume(
                    saved,
                    executionMode
                )
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

    // Running-trade state required by the trade screen and Deriv portfolio updates.
    private data class RunningContract(
        val contractId: Long,
        val symbol: String,
        val direction: String,
        val stake: Double,
        val target: Double,
        val targetProbability: Double,
        val mode: String,
        var entryBarrier: Double = 0.0,
        var takeProfitRequested: Boolean = false,
        var openContractConfirmed: Boolean = false,
        var targetNotified: Boolean = false,
        var trailingActivated: Boolean = false,
        var peakProfit: Double = 0.0,
        var closeRequested: Boolean = false,
        var closeReason: String = "",
        var pairId: String = "",
        var pairSide: String = "",
        val isRecovery: Boolean = false,
        var recoveryTriggered: Boolean = false,
        var potentialPayout: Double = 0.0
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
    private val runningContracts = mutableMapOf<Long, RunningContract>()
    private var nextTradeRequestIdValue = 710000
    private fun nextTradeRequestId(): Int = ++nextTradeRequestIdValue

    
    private val dailyCapitalTimeZone = java.util.TimeZone.getTimeZone("Africa/Johannesburg")
    private fun saDayKey(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = dailyCapitalTimeZone }.format(java.util.Date())

    private var autoTradeEnabled: Boolean
        get() = appPrefs.getBoolean("autoTradeEnabled", false)
        set(value) { appPrefs.edit().putBoolean("autoTradeEnabled", value).apply() }



    private fun normalizedSymbol(item: JSONObject): String = item.optString(
        "underlying", item.optString("underlying_symbol", item.optString("symbol", ""))
    ).uppercase()
    private fun requestOpenTradesIfNeeded() {
        if (!connected || accountId.isBlank() || portfolioLoadedForAccount) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastOpenTradesRequestAt < 5000L) return
        lastOpenTradesRequestAt = now
        derivApiClient.requestOpenTrades()
    }

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
private val dashboardAnalysisViews =
    mutableMapOf<String, TextView>()
private val marketDataErrors =
    mutableMapOf<String, String>()
private fun dashboardDecisionStatus(symbol: String): Pair<String, Int> {
    if (marketDataErrors.containsKey(symbol) && marketDataPrices[symbol] == null) {
        return "FEED ERROR" to red
    }

    if (!connected) return "DISCONNECTED" to red

    if (marketDataPrices[symbol] == null) {
        return "WAITING FOR PRICE" to yellow
    }

    val decision = masterEngineDecisions[symbol]

    if (decision?.qualified == true) {
        return "QUALIFIED ${decision.direction}" to green
    }

    return "MONITORING" to yellow
}
private var marketDataRequested = false
private var marketStructureExpanded = true
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

    override fun onResume() {
        super.onResume()

        // onCreate handles the initial launch. Avoid starting a second connection
        // during the first resume while OAuth/account lookup may still be running.
        profileRefreshHandler.removeCallbacks(profileRefreshRunnable)
        if (authToken.isNotBlank()) {
            refreshProfile(showMessage = false)
            profileRefreshHandler.postDelayed(profileRefreshRunnable, 15_000L)
        }
        if (!hasResumedOnce) {
            hasResumedOnce = true
            return
        }

        if (authToken.isBlank() || appPrefs.getBoolean("deriv_manually_disconnected", false)) {
            return
        }

        val tokenKey = if (executionMode == "REAL") "real_token" else "demo_token"
val savedToken = prefs.getString(tokenKey, "").orEmpty()

if (savedToken.isNotBlank()) {
    if (derivServiceBound && ::derivApiClient.isInitialized) {
        derivApiClient.ensureConnectedOnResume(savedToken, executionMode)
    } else {
        pendingResumeCheck = true
        startAndBindDerivService()
    }
}
    }

    override fun onPause() {
        profileRefreshHandler.removeCallbacks(profileRefreshRunnable)
        super.onPause()
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
                notifyEvent("Notifications enabled", "Android permission granted. Trade notifications can now appear.")
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
        when (selectedTab) {
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
    "ANALYSIS - SUPPORT AND RESISTANCE",
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
            // Use two display rows so long live prices cannot be squeezed into
            // a narrow column or wrap one digit per line on smaller screens.
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(9), dp(10), dp(9))
                background = shape(panel, accent, 10, 1)
            }
            val dashboardIndexName = when (symbol) {
                "R_10" -> "VOLATILITY INDEX 10"
                "R_25" -> "VOLATILITY INDEX 25"
                "R_50" -> "VOLATILITY INDEX 50"
                "R_75" -> "VOLATILITY INDEX 75"
                "R_100" -> "VOLATILITY INDEX 100"
                else -> symbol
            }
            val headingRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            headingRow.addView(
                label(dashboardIndexName, 13f, accent, true),
                LinearLayout.LayoutParams(0, -2, 1f)
            )
            val initialStatus = dashboardDecisionStatus(symbol)
            val statusView = label(initialStatus.first, 10f, initialStatus.second, true).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                maxLines = 2
            }
            dashboardStatusViews[symbol] = statusView
            headingRow.addView(statusView, LinearLayout.LayoutParams(-2, -2))
            row.addView(headingRow, matchWrap())

            val price = marketDataPrices[symbol]
            val priceView = label(
                price?.let { formatMarketPrice(it) } ?: "PRICE WAITING",
                14f,
                muted,
                false
            ).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                includeFontPadding = false
            }
            dashboardPriceViews[symbol] = priceView
            row.addView(
                priceView,
                LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(7)
                }
            )
            val analysisView = label(
    "SUPPORT / RESISTANCE: WAITING\n" +
    "LIQUIDITY: WAITING\n" +
    "MARKET STRUCTURE: WAITING\n" +
    "CHOCH: WAITING\n" +
    "ORDER BLOCK: WAITING",
    11f,
    yellow,
    false
).apply {
    setPadding(dp(4), dp(8), dp(4), dp(4))
}

dashboardAnalysisViews[symbol] = analysisView
row.addView(analysisView, matchWrap())
// =========================================================
// ADMIN ONLY — SUPPORT AND RESISTANCE
// =========================================================
if (
    platform == "ADMIN" &&
    currentProfile?.optString("role", "user")
        .equals("admin", ignoreCase = true)
) {

    val candles = marketDataCandles[symbol].orEmpty()

    val supports = calculateSupportResistance(candles, true)
    val resistances = calculateSupportResistance(candles, false)

    row.addView(label("SUPPORT LEVELS", 12f, green, true))

    supports.take(2).forEachIndexed { index, level ->
        row.addView(
            label(
                "S${index + 1}: ${formatMarketPrice(level.price)} | ${formatMarketTime(level.createdAt)}",
                11f, green, false
            )
        )
    }

    row.addView(label("RESISTANCE LEVELS", 12f, red, true))

    resistances.take(2).forEachIndexed { index, level ->
        row.addView(
            label(
                "R${index + 1}: ${formatMarketPrice(level.price)} | ${formatMarketTime(level.createdAt)}",
                11f, red, false
            )
        )
    }
}
// =========================================================
// ADMIN ONLY — LIQUIDITY AND LIQUIDITY SWEEP
// =========================================================

val liquidityCandles = marketDataCandles[symbol].orEmpty()

val bullishLiquidity = calculateLiquidityLevels(liquidityCandles, true)
val bearishLiquidity = calculateLiquidityLevels(liquidityCandles, false)

row.addView(label("BULLISH LIQUIDITY (SSL)", 12f, green, true))

if (bullishLiquidity.isEmpty()) {
    row.addView(label("No confirmed bullish liquidity.", 11f, muted, false))
} else {
    bullishLiquidity.take(2).forEachIndexed { index, level ->
        row.addView(
            label(
                "SSL ${index + 1}: ${formatMarketPrice(level.price)} | CREATED: ${formatMarketTime(level.createdAt)} | SWEPT: ${if (level.sweepAt > 0L) formatMarketTime(level.sweepAt) else "NOT SWEPT"} | OCCURRENCES: ${level.occurrences}",
                11f, green, false
            )
        )
    }
}

row.addView(label("BEARISH LIQUIDITY (BSL)", 12f, red, true))

if (bearishLiquidity.isEmpty()) {
    row.addView(label("No confirmed bearish liquidity.", 11f, muted, false))
} else {
    bearishLiquidity.take(2).forEachIndexed { index, level ->
        row.addView(
            label(
                "BSL ${index + 1}: ${formatMarketPrice(level.price)} | CREATED: ${formatMarketTime(level.createdAt)} | SWEPT: ${if (level.sweepAt > 0L) formatMarketTime(level.sweepAt) else "NOT SWEPT"} | OCCURRENCES: ${level.occurrences}",
                11f, red, false
            )
        )
    }
}
// =========================================================
// ADMIN ONLY — MARKET STRUCTURE AND CHOCH DISPLAY
// =========================================================

row.addView(label("ANALYSIS - MARKET STRUCTURE AND CHOCH", 13f, green, true))
val structureCandles = marketDataCandles[symbol].orEmpty()
val structureAnalysis = calculateMarketStructure(structureCandles)

row.addView(label("MARKET STRUCTURE", 12f, green, true))

if (structureAnalysis.structures.isEmpty()) {
    row.addView(label("No confirmed market structure available.", 11f, muted, false))
} else {
    structureAnalysis.structures.forEach { event ->
        row.addView(
            label(
                "${event.number}. ${event.direction} | PRICE: ${formatMarketPrice(event.price)} | CREATED: ${formatMarketTime(event.startedAt)}",
                11f, white, false
            )
        )
    }
}

row.addView(label("CHANGE OF CHARACTER (CHOCH)", 12f, yellow, true))

if (structureAnalysis.chochs.isEmpty()) {
    row.addView(label("No confirmed CHOCH available.", 11f, muted, false))
} else {
    structureAnalysis.chochs.forEach { event ->
        row.addView(
            label(
                "${event.number}. ${event.direction} | PRICE: ${formatMarketPrice(event.price)} | CONFIRMED: ${formatMarketTime(event.changedAt)}",
                11f, yellow, false
            )
        )
    }
}
// =========================================================
// ADMIN ONLY — BULLISH AND BEARISH ORDER BLOCKS
// =========================================================

if (
    platform == "ADMIN" &&
    currentProfile?.optString("role", "user")
        .equals("admin", ignoreCase = true)
) {

    val orderBlockCandles = marketDataCandles[symbol].orEmpty()
    val orderBlocks = calculateOrderBlocks(orderBlockCandles)

    row.addView(
        label("BULLISH ORDER BLOCKS", 12f, green, true)
    )

    if (orderBlocks.bullish.isEmpty()) {
        row.addView(
            label("No bullish order blocks found.", 11f, muted, false)
        )
    } else {
        orderBlocks.bullish.forEach { block ->
            row.addView(
                label(
                    "BULLISH OB ${block.number} | ZONE: ${formatMarketPrice(block.low)} - ${formatMarketPrice(block.high)} | CREATED: ${formatMarketTime(block.createdAt)} | STATUS: ${if (block.valid) "VALID" else "INVALID"} | INVALIDATED: ${if (block.invalidatedAt > 0L) formatMarketTime(block.invalidatedAt) else "—"}",
                    11f, green, false
                )
            )
        }
    }

    row.addView(
        label("BEARISH ORDER BLOCKS", 12f, red, true)
    )

    if (orderBlocks.bearish.isEmpty()) {
        row.addView(
            label("No bearish order blocks found.", 11f, muted, false)
        )
    } else {
        orderBlocks.bearish.forEach { block ->
            row.addView(
                label(
                    "BEARISH OB ${block.number} | ZONE: ${formatMarketPrice(block.low)} - ${formatMarketPrice(block.high)} | CREATED: ${formatMarketTime(block.createdAt)} | STATUS: ${if (block.valid) "VALID" else "INVALID"} | INVALIDATED: ${if (block.invalidatedAt > 0L) formatMarketTime(block.invalidatedAt) else "—"}",
                    11f, red, false
                )
            )
        }
    }
}
            // Index monitor rows are display-only.
            row.isClickable = false
            row.isFocusable = false
            monitorCard.addView(row, matchWrap().apply { bottomMargin = dp(6) })
        }
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

 // =========================================================
// TRADE SCREEN
// =========================================================
private fun addTradeScreen() {
    contractLiveViews.clear()
    page.addView(sectionTitle("TRADE"))
    val masterDecisionCard = card().apply {
    orientation = LinearLayout.VERTICAL
    }
    masterDecisionCard.addView(
    label(
        if (autoTradeEnabled) "MASTER DECISION: ON"
        else "MASTER DECISION: OFF",
        15f,
        if (autoTradeEnabled) green else red,
        true
    )
)
masterDecisionCard.addView(
    button(
        if (autoTradeEnabled) "TURN MASTER DECISION OFF"
        else "TURN MASTER DECISION ON",
        autoTradeEnabled
    ) {
        autoTradeEnabled = !autoTradeEnabled
        toast(if (autoTradeEnabled) "Master Decision display is ON. Trade execution is disabled." else "Master Decision display is OFF.")
        render()
    },
    matchWrap().apply { topMargin = dp(8) }
)
page.addView(
    masterDecisionCard,
    matchWrap().apply { bottomMargin = dp(10) }
)

    val accountCard = card().apply { orientation = LinearLayout.VERTICAL }
    accountCard.addView(label("EXECUTION ACCOUNT", 14f, green, true))
    accountCard.addView(label("Mode: $executionMode", 13f, white, true))
    accountCard.addView(label("Connection: ${if (connected) "CONNECTED" else "DISCONNECTED"}", 12f, if (connected) green else red, true))
    accountCard.addView(label("Account ID: ${accountId.ifBlank { "—" }}", 12f, muted, false))
    page.addView(accountCard, matchWrap().apply { bottomMargin = dp(10) })
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
        val tradeDirection = tracked?.direction ?: if (
    item.optString("contract_type", "").uppercase().let {
        it.contains("PUT") || it.contains("FALL")
    }
) "SELL" else "BUY"
row.addView(
    label(
        "DIRECTION: $tradeDirection",
        14f,
        if (tradeDirection == "BUY") green else red,
        true
        ).apply { setPadding(0, dp(4), 0, 0) }
)
val originalTarget = tracked?.target?.takeIf { it.isFinite() && it > 0.0 }
        val proposalPayout = tracked?.potentialPayout?.takeIf { it > 0.0 }
            ?: item.optDouble("payout", 0.0).takeIf { it > 0.0 }
            ?: 0.0
        val potentialProfit = (proposalPayout - stake).coerceAtLeast(0.0)
        val potentialProfitView = label(
            "Potential profit: ${if (proposalPayout > 0.0) formatMoney(potentialProfit) else "Awaiting Deriv payout"}",
            12f, green, true
        ).apply { setPadding(0, dp(4), 0, 0) }
val actualBarrier = tracked?.entryBarrier?.takeIf { it > 0.0 }
    ?: item.optDouble("entry_tick", item.optDouble("entry_spot", 0.0))
val entryBarrierView = label(
    "Actual Deriv entry barrier: ${if (actualBarrier > 0.0) formatMarketPrice(actualBarrier) else "Waiting for Deriv"}",
    12f, white, true
).apply { setPadding(0, dp(4), 0, 0) }
        val spotView = label("Current value/spot: ${if (currentValue > 0) formatMarketPrice(currentValue) else "—"}", 12f, white, false).apply { setPadding(0, dp(4), 0, 0) }
        val expiryView = label("Expiry: $expiryText", 12f, white, false).apply { setPadding(0, dp(4), 0, 0) }
        row.addView(pnlHidden)
        row.addView(entryBarrierView)
        row.addView(potentialProfitView)
        row.addView(spotView)
        row.addView(expiryView)
        contractLiveViews[contractId] = ContractLiveViews(stake, symbol, tracked?.target, tracked?.targetProbability ?: 0.0, pnlHidden, spotView, expiryView, TextView(this))
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
row.addView(
    label(
        "DIRECTION: ${if (running.pairSide.contains("TOUCH")) running.pairSide else running.direction}",
        14f,
        if (running.direction == "BUY") green else red,
        true
    ).apply { setPadding(0, dp(4), 0, 0) }
)
        row.addView(label("Contract number: ${running.contractId}", 12f, white, false).apply { setPadding(0, dp(4), 0, 0) })
        row.addView(label("Stake: ${formatMoney(running.stake)}", 12f, white, false).apply { setPadding(0, dp(4), 0, 0) })
                val initialPnl = liveContractProfits[running.contractId] ?: 0.0
        val pnlHidden = label("P/L: ${formatMoney(initialPnl)}", 12f, profitColor(initialPnl), true)
            .apply { setPadding(0, dp(4), 0, 0) }
        row.addView(pnlHidden)
        val entryBarrierView = label(
            "Actual Deriv entry barrier: ${if (running.entryBarrier > 0.0) formatMarketPrice(running.entryBarrier) else "Waiting for Deriv"}",
            12f, white, true
        ).apply { setPadding(0, dp(4), 0, 0) }

        val potentialProfit = (running.potentialPayout - running.stake).coerceAtLeast(0.0)
        val potentialProfitView = label(
            "Potential profit: ${if (running.potentialPayout > 0.0) formatMoney(potentialProfit) else "Awaiting Deriv payout"}",
            12f, green, true
        ).apply { setPadding(0, dp(4), 0, 0) }


        val spotView = label("Current value/spot: —", 12f, white, false)
            .apply { setPadding(0, dp(4), 0, 0) }

        val expiryView = label("Expiry: —", 12f, white, false)
            .apply { setPadding(0, dp(4), 0, 0) }


        row.addView(pnlHidden)
        row.addView(entryBarrierView)
        row.addView(potentialProfitView)
        row.addView(spotView)
        row.addView(expiryView)

        contractLiveViews[running.contractId] = ContractLiveViews(
            running.stake,
            running.symbol,
            running.target,
            running.targetProbability,
            pnlHidden,
            spotView,
            expiryView,
            TextView(this)
        )
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
        val contractId = item.optLong("contract_id", item.optLong("transaction_id", 0L))
        val rawSymbol = listOf("underlying_symbol", "underlying", "symbol")
            .asSequence()
            .map { item.optString(it, "").trim() }
            .firstOrNull { it.isNotBlank() && it != "null" && it != "—" }
            .orEmpty()
        val historyDescription = listOf("longcode", "shortcode", "description")
            .asSequence()
            .map { item.optString(it, "").trim() }
            .firstOrNull { it.isNotBlank() && it != "null" }
            .orEmpty()
            val symbol = rawSymbol.ifBlank {
            runningContracts[contractId]?.symbol
                ?: when {
                    historyDescription.contains("Volatility 10 Index", true) -> "R_10"
                    historyDescription.contains("Volatility 25 Index", true) -> "R_25"
                    historyDescription.contains("Volatility 50 Index", true) -> "R_50"
                    historyDescription.contains("Volatility 75 Index", true) -> "R_75"
                    historyDescription.contains("Volatility 100 Index", true) -> "R_100"
                    else -> "INDEX UNAVAILABLE"
                }
            }
            val buyPrice = item.optDouble("buy_price", 0.0)
        val sellPrice = item.optDouble("sell_price", 0.0)
        val profit = sellPrice - buyPrice
        val epoch = item.optLong("purchase_time", item.optLong("date_start", 0L))
        val timeText = if (epoch > 0) java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(epoch * 1000L)) else "Time unavailable"
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(9), dp(10), dp(9)); background = shape(panel, greenDark, 7, 1) }
        row.addView(label("Index: $symbol • Contract $contractId", 12f, white, true))
        row.addView(label("$timeText • Stake ${formatMoney(buyPrice)} • P/L ${formatMoney(profit)}", 11f, profitColor(profit), false).apply { setPadding(0, dp(4), 0, 0) })
        historyCard.addView(row, matchWrap().apply { topMargin = dp(6) }); historyCount++
    }
    if (historyCount == 0) historyCard.addView(label("No completed trade-history records were returned by Deriv yet.", 12f, yellow, false))
    page.addView(historyCard, matchWrap().apply { bottomMargin = dp(10) })
    page.addView(button("NOTIFICATIONS", false) { selectedTab = "Notifications"; render() }, matchWrap().apply { bottomMargin = dp(6) })
    page.addView(button("MY PROFILE", false) { selectedTab = "My profile"; render() }, matchWrap())
}
// =========================================================
// LIVE MARKET DISPLAY HELPERS
// =========================================================
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
    // =============================================================
// ANALYSIS - LIQUIDITY AND LIQUIDITY SWEEP
// =============================================================

private data class LiquidityLevel(
    val price: Double,
    val createdAt: Long,
    val sweepAt: Long,
    val occurrences: Int
)

private fun calculateLiquidityLevels(
    candles: List<DerivApiClient.Candle>,
    bullish: Boolean
): List<LiquidityLevel> {

    if (candles.size < 10) return emptyList()

    val sorted = candles.sortedBy { it.epoch }
    val pivots = mutableListOf<Pair<Int, Double>>()

    for (i in 2 until sorted.size - 2) {
        val price = if (bullish) sorted[i].low else sorted[i].high

        val confirmed = if (bullish) {
            price < sorted[i - 1].low &&
            price < sorted[i - 2].low &&
            price < sorted[i + 1].low &&
            price < sorted[i + 2].low
        } else {
            price > sorted[i - 1].high &&
            price > sorted[i - 2].high &&
            price > sorted[i + 1].high &&
            price > sorted[i + 2].high
        }

        if (confirmed) pivots.add(i to price)
    }

    val tolerance = 0.0015
    val used = mutableSetOf<Int>()
    val results = mutableListOf<LiquidityLevel>()

    pivots.forEachIndexed { index, pivot ->
        if (index in used) return@forEachIndexed

        val basePrice = pivot.second

        val group = pivots.filterIndexed { otherIndex, other ->
            otherIndex !in used &&
            kotlin.math.abs(other.second - basePrice) /
                basePrice.coerceAtLeast(0.000001) <= tolerance
        }

        group.forEachIndexed { _, member ->
            val memberIndex = pivots.indexOf(member)
            if (memberIndex >= 0) used.add(memberIndex)
        }

        if (group.size < 2) return@forEachIndexed

        val levelPrice = group.map { it.second }.average()
        val firstPivotIndex = group.minOf { it.first }
        val lastPivotIndex = group.maxOf { it.first }

        var sweepAt = 0L

        for (i in lastPivotIndex + 1 until sorted.size) {
            val candle = sorted[i]

            val swept = if (bullish) {
                candle.low < levelPrice && candle.close > levelPrice
            } else {
                candle.high > levelPrice && candle.close < levelPrice
            }

            if (swept) {
                sweepAt = candle.epoch
                break
            }
        }

        results.add(
            LiquidityLevel(
                price = levelPrice,
                createdAt = sorted[firstPivotIndex].epoch,
                sweepAt = sweepAt,
                occurrences = group.size
            )
        )
    }

    return results
        .sortedWith(
            compareByDescending<LiquidityLevel> { it.occurrences }
                .thenByDescending { it.createdAt }
        )
        .take(2)
}
// =============================================================
// ANALYSIS - BULLISH AND BEARISH ORDER BLOCKS
// =============================================================

private data class OrderBlockEvent(
    val number: Int,
    val direction: String,
    val low: Double,
    val high: Double,
    val createdAt: Long,
    val valid: Boolean,
    val invalidatedAt: Long
)

private data class OrderBlockAnalysis(
    val bullish: List<OrderBlockEvent>,
    val bearish: List<OrderBlockEvent>
)

private fun calculateOrderBlocks(
    candles: List<DerivApiClient.Candle>
): OrderBlockAnalysis {

    val sorted = candles.sortedBy { it.epoch }

    if (sorted.size < 10) {
        return OrderBlockAnalysis(emptyList(), emptyList())
    }

    val bullishBlocks = mutableListOf<OrderBlockEvent>()
    val bearishBlocks = mutableListOf<OrderBlockEvent>()

    val usedBullish = mutableSetOf<Long>()
    val usedBearish = mutableSetOf<Long>()
    var bullishNumber = 0
    var bearishNumber = 0

    for (i in 2 until sorted.size - 2) {

        val swingHigh =
            sorted[i].high > sorted[i - 1].high &&
            sorted[i].high > sorted[i - 2].high &&
            sorted[i].high > sorted[i + 1].high &&
            sorted[i].high > sorted[i + 2].high

        val swingLow =
            sorted[i].low < sorted[i - 1].low &&
            sorted[i].low < sorted[i - 2].low &&
            sorted[i].low < sorted[i + 1].low &&
            sorted[i].low < sorted[i + 2].low

        // BULLISH ORDER BLOCK
        if (swingHigh) {

            val breakIndex = (i + 3 until sorted.size)
                .firstOrNull { sorted[it].close > sorted[i].high }

            if (breakIndex != null) {

                val obIndex = (breakIndex - 1 downTo i)
                    .firstOrNull {
                        sorted[it].close < sorted[it].open
                    }

                if (obIndex != null) {

                    val ob = sorted[obIndex]

                    if (usedBullish.add(ob.epoch)) {

                        val invalidation = (obIndex + 1 until sorted.size)
                            .firstOrNull {
                                sorted[it].close < ob.low
                            }

                        bullishNumber++

                        bullishBlocks.add(
                            OrderBlockEvent(
                                bullishNumber,
                                "BULLISH",
                                ob.low,
                                ob.high,
                                ob.epoch,
                                invalidation == null,
                                invalidation?.let { sorted[it].epoch } ?: 0L
                            )
                        )
                    }
                }
            }
        }

        // BEARISH ORDER BLOCK
        if (swingLow) {

            val breakIndex = (i + 3 until sorted.size)
                .firstOrNull { sorted[it].close < sorted[i].low }

            if (breakIndex != null) {

                val obIndex = (breakIndex - 1 downTo i)
                    .firstOrNull {
                        sorted[it].close > sorted[it].open
                    }

                if (obIndex != null) {

                    val ob = sorted[obIndex]

                    if (usedBearish.add(ob.epoch)) {

                        val invalidation = (obIndex + 1 until sorted.size)
                            .firstOrNull {
                                sorted[it].close > ob.high
                            }

                        bearishNumber++

                        bearishBlocks.add(
                            OrderBlockEvent(
                                bearishNumber,
                                "BEARISH",
                                ob.low,
                                ob.high,
                                ob.epoch,
                                invalidation == null,
                                invalidation?.let { sorted[it].epoch } ?: 0L
                            )
                        )
                    }
                }
            }
        }
    }

    return OrderBlockAnalysis(
        bullish = bullishBlocks.takeLast(10),
        bearish = bearishBlocks.takeLast(10)
    )
}
// =============================================================
// ANALYSIS - MARKET STRUCTURE AND CHANGE OF CHARACTER
// =============================================================

private data class MarketStructureEvent(
    val number: Int,
    val direction: String,
    val price: Double,
    val startedAt: Long
)

private data class ChochEvent(
    val number: Int,
    val direction: String,
    val price: Double,
    val changedAt: Long
)

private data class MarketStructureAnalysis(
    val structures: List<MarketStructureEvent>,
    val chochs: List<ChochEvent>
)

private fun calculateMarketStructure(
    candles: List<DerivApiClient.Candle>
): MarketStructureAnalysis {

    val sorted = candles.sortedBy { it.epoch }

    if (sorted.size < 10) {
        return MarketStructureAnalysis(emptyList(), emptyList())
    }

    val highs = mutableListOf<Pair<Int, Double>>()
    val lows = mutableListOf<Pair<Int, Double>>()

    for (i in 2 until sorted.size - 2) {

        if (
            sorted[i].high > sorted[i - 1].high &&
            sorted[i].high > sorted[i - 2].high &&
            sorted[i].high > sorted[i + 1].high &&
            sorted[i].high > sorted[i + 2].high
        ) {
            highs.add(i to sorted[i].high)
        }

        if (
            sorted[i].low < sorted[i - 1].low &&
            sorted[i].low < sorted[i - 2].low &&
            sorted[i].low < sorted[i + 1].low &&
            sorted[i].low < sorted[i + 2].low
        ) {
            lows.add(i to sorted[i].low)
        }
    }

    val structures = mutableListOf<MarketStructureEvent>()
    val chochs = mutableListOf<ChochEvent>()

    var structureNumber = 0
    var chochNumber = 0
    var trend = ""

    var previousHigh: Pair<Int, Double>? = null
    var previousLow: Pair<Int, Double>? = null

    var bullishStart = 0L
    var bearishStart = 0L

    val pivotHighs = highs.associate { it.first to it.second }
    val pivotLows = lows.associate { it.first to it.second }

    for (i in sorted.indices) {

        val currentHigh = pivotHighs[i]
        val currentLow = pivotLows[i]

        if (currentHigh != null) {
            val previous = previousHigh

            if (previous != null) {
                if (currentHigh > previous.second) {
                    if (trend != "BULLISH") bullishStart = sorted[previous.first].epoch
                    trend = "BULLISH"
                    structureNumber++

                    structures.add(
                        MarketStructureEvent(
                            structureNumber,
                            "BULLISH",
                            currentHigh,
                            bullishStart
                        )
                    )
                } else if (currentHigh < previous.second) {
                    if (trend != "BEARISH") bearishStart = sorted[previous.first].epoch
                    trend = "BEARISH"
                    structureNumber++

                    structures.add(
                        MarketStructureEvent(
                            structureNumber,
                            "BEARISH",
                            currentHigh,
                            bearishStart
                        )
                    )
                }
            }

            previousHigh = i to currentHigh
        }

        if (currentLow != null) {
            val previous = previousLow

            if (previous != null) {
                if (currentLow > previous.second) {
                    if (trend != "BULLISH") bullishStart = sorted[previous.first].epoch
                    trend = "BULLISH"
                    structureNumber++

                    structures.add(
                        MarketStructureEvent(
                            structureNumber,
                            "BULLISH",
                            currentLow,
                            bullishStart
                        )
                    )
                } else if (currentLow < previous.second) {
                    if (trend != "BEARISH") bearishStart = sorted[previous.first].epoch
                    trend = "BEARISH"
                    structureNumber++

                    structures.add(
                        MarketStructureEvent(
                            structureNumber,
                            "BEARISH",
                            currentLow,
                            bearishStart
                        )
                    )
                }
            }

            previousLow = i to currentLow
        }

        val candle = sorted[i]

        if (trend == "BEARISH") {
            val breakLevel = previousHigh
            if (
                breakLevel != null &&
                i > breakLevel.first &&
                candle.close > breakLevel.second
            ) {
                chochNumber++
                chochs.add(
                    ChochEvent(
                        chochNumber,
                        "BULLISH",
                        breakLevel.second,
                        candle.epoch
                    )
                )
                trend = "BULLISH"
            }
        } else if (trend == "BULLISH") {
            val breakLevel = previousLow
            if (
                breakLevel != null &&
                i > breakLevel.first &&
                candle.close < breakLevel.second
            ) {
                chochNumber++
                chochs.add(
                    ChochEvent(
                        chochNumber,
                        "BEARISH",
                        breakLevel.second,
                        candle.epoch
                    )
                )
                trend = "BEARISH"
            }
        }
    }

    return MarketStructureAnalysis(
        structures = structures.takeLast(10),
        chochs = chochs.takeLast(10)
    )
}
private data class MasterEngineDecision(
    val symbol: String,
    val direction: String,
    val supportResistanceQualified: Boolean,
    val liquidityQualified: Boolean,
    val marketStructureQualified: Boolean,
    val chochQualified: Boolean,
    val orderBlockQualified: Boolean,
    val qualified: Boolean,
    val updatedAt: Long
)

private val masterEngineDecisions =
    mutableMapOf<String, MasterEngineDecision>()

private val masterEngineNotificationKeys =
    mutableSetOf<String>()
// =============================================================
// ANALYSIS - SUPPORT AND RESISTANCE
// =============================================================
private data class SupportResistanceLevel(
    val price: Double,
    val createdAt: Long,
    val touches: Int,
    val reversalPercent: Double,
    val lastTouch: Long,
    val score: Double
)

private fun calculateSupportResistance(
    candles: List<DerivApiClient.Candle>,
    support: Boolean,
    currentPrice: Double? = null
): List<SupportResistanceLevel> {

    if (candles.size < 5) return emptyList()

    val pivots = mutableListOf<Pair<Double, Long>>()

    for (i in 2 until candles.size - 2) {
        val candle = candles[i]

        val confirmed = if (support) {
            candle.low < candles[i - 1].low &&
            candle.low < candles[i - 2].low &&
            candle.low < candles[i + 1].low &&
            candle.low < candles[i + 2].low
        } else {
            candle.high > candles[i - 1].high &&
            candle.high > candles[i - 2].high &&
            candle.high > candles[i + 1].high &&
            candle.high > candles[i + 2].high
        }

        if (confirmed) {
            pivots.add(
                (if (support) candle.low else candle.high) to candle.epoch
            )
        }
    }

    val results = mutableListOf<SupportResistanceLevel>()
    val used = mutableSetOf<Int>()

    for (i in pivots.indices) {
        if (i in used) continue

        val basePrice = pivots[i].first
        val tolerance = basePrice * 0.0015

        val group = pivots.indices.filter {
            kotlin.math.abs(pivots[it].first - basePrice) <= tolerance
        }

        used.addAll(group)

        val levelPrice = if (support) {
            group.map { pivots[it].first }.average()
        } else {
            group.map { pivots[it].first }.average()
        }

        val createdAt = group.minOf { pivots[it].second }

        var touches = 0
        var lastTouch = createdAt
        var strongestReversal = 0.0

        for (index in group) {
            val pivotEpoch = pivots[index].second
            val pivotPosition = candles.indexOfFirst {
                it.epoch == pivotEpoch
            }

            if (pivotPosition < 0) continue

            val endPosition = minOf(
                candles.lastIndex,
                pivotPosition + 6
            )

            val nextCandles = candles.subList(
                pivotPosition + 1,
                endPosition + 1
            )

            if (nextCandles.isNotEmpty()) {
                val reversal = if (support) {
                    (nextCandles.maxOf { it.high } - levelPrice) /
                        levelPrice * 100.0
                } else {
                    (levelPrice - nextCandles.minOf { it.low }) /
                        levelPrice * 100.0
                }

                strongestReversal = maxOf(
                    strongestReversal,
                    reversal.coerceAtLeast(0.0)
                )
            }
        }

        for (candle in candles) {
            if (candle.epoch <= createdAt) continue

            val touched = if (support) {
                kotlin.math.abs(candle.low - levelPrice) <= tolerance
            } else {
                kotlin.math.abs(candle.high - levelPrice) <= tolerance
            }

            if (touched) {
                touches++
                lastTouch = maxOf(lastTouch, candle.epoch)
            }
        }

        val recencyDays = (
            (System.currentTimeMillis() / 1000L - lastTouch) / 86400.0
        ).coerceAtLeast(0.0)

        val score =
            (touches * 100.0) +
            (strongestReversal * 10.0) +
            (30.0 - recencyDays).coerceAtLeast(0.0)

        results.add(
            SupportResistanceLevel(
                price = levelPrice,
                createdAt = createdAt,
                touches = touches,
                reversalPercent = strongestReversal,
                lastTouch = lastTouch,
                score = score
            )
        )
    }

    val sevenDaysAgo =
    System.currentTimeMillis() / 1000L - (7L * 24L * 60L * 60L)

val recentLevels = results.filter {
    it.createdAt >= sevenDaysAgo
}

return if (currentPrice != null && currentPrice > 0.0) {
    recentLevels
        .filter {
            if (support) it.price < currentPrice
            else it.price > currentPrice
        }
        .sortedBy { kotlin.math.abs(it.price - currentPrice) }
        .take(2)
} else {
    recentLevels.sortedByDescending { it.score }.take(2)
}
}
private fun evaluateMasterEngine(symbol: String) {
    val candles = marketDataCandles[symbol].orEmpty()
    val price = marketDataPrices[symbol] ?: return
    if (candles.size < 20) return

    val latest = candles.last()

    val supports = calculateSupportResistance(candles, true)
    val resistances = calculateSupportResistance(candles, false)

    val bullishSR = supports.any {
        latest.low <= it.price * 1.0015 &&
        latest.close > it.price
    }

    val bearishSR = resistances.any {
        latest.high >= it.price * 0.9985 &&
        latest.close < it.price
    }

    val bullishLiquidity = calculateLiquidityLevels(candles, true)
    val bearishLiquidity = calculateLiquidityLevels(candles, false)

    val bullishSweep = bullishLiquidity.any {
        it.sweepAt > 0L && latest.epoch > it.sweepAt &&
        latest.close > latest.open
    }

    val bearishSweep = bearishLiquidity.any {
        it.sweepAt > 0L && latest.epoch > it.sweepAt &&
        latest.close < latest.open
    }

    val structure = calculateMarketStructure(candles)
    val marketDirection =
        structure.structures.lastOrNull()?.direction ?: ""

    val chochDirection =
        structure.chochs.lastOrNull()?.direction ?: ""

    val orderBlocks = calculateOrderBlocks(candles)

    val bullishOB = orderBlocks.bullish.any {
        it.valid && marketDirection == "BULLISH" &&
        chochDirection == "BULLISH"
    }

    val bearishOB = orderBlocks.bearish.any {
        it.valid && marketDirection == "BEARISH" &&
        chochDirection == "BEARISH"
    }

    val bullishQualified =
        bullishSR && bullishSweep &&
        marketDirection == "BULLISH" &&
        chochDirection == "BULLISH" &&
        bullishOB

    val bearishQualified =
        bearishSR && bearishSweep &&
        marketDirection == "BEARISH" &&
        chochDirection == "BEARISH" &&
        bearishOB

    val direction = when {
        bullishQualified -> "BUY"
        bearishQualified -> "SELL"
        else -> "WAIT"
    }

    val decision = MasterEngineDecision(
        symbol = symbol,
        direction = direction,
        supportResistanceQualified =
    bullishSR || bearishSR,

liquidityQualified =
    bullishSweep || bearishSweep,

marketStructureQualified =
    marketDirection == "BULLISH" ||
    marketDirection == "BEARISH",

chochQualified =
    chochDirection == "BULLISH" ||
    chochDirection == "BEARISH",

orderBlockQualified =
    bullishOB || bearishOB,
        qualified = direction != "WAIT",
        updatedAt = System.currentTimeMillis()
    )

    masterEngineDecisions[symbol] = decision
val analysisText =
    "SUPPORT / RESISTANCE: " +
        (if (decision.supportResistanceQualified) "QUALIFIED" else "WAITING") +
    "\nLIQUIDITY: " +
        (if (decision.liquidityQualified) "QUALIFIED" else "WAITING") +
    "\nMARKET STRUCTURE: " +
        (if (decision.marketStructureQualified) "QUALIFIED" else "WAITING") +
    "\nCHOCH: " +
        (if (decision.chochQualified) "QUALIFIED" else "WAITING") +
    "\nORDER BLOCK: " +
        (if (decision.orderBlockQualified) "QUALIFIED" else "WAITING")

dashboardAnalysisViews[symbol]?.apply {
    text = analysisText
    setTextColor(
        if (decision.qualified)
            android.graphics.Color.GREEN
        else
            android.graphics.Color.YELLOW
    )
}
    if (decision.qualified) {
        val eventKey =
            "${saDayKey()}_${accountId}_${executionMode}_${symbol}_${direction}_${latest.epoch}"

        if (masterEngineNotificationKeys.add(eventKey)) {
            notifyEvent(
                "Master Engine Qualified",
                "$symbol · $direction · All five conditions qualified.",
                "master_engine_$eventKey"
            )
        }
    }
}
    // =========================================================
    // ADMIN USER APPROVAL — server verifies administrator role
    // =========================================================
   private fun addAdminSupportResistanceScreen() {
    page.addView(sectionTitle("ANALYSIS - SUPPORT AND RESISTANCE"))

    val profile = currentProfile

    if (authToken.isBlank()) {
        page.addView(label("Sign in with the administrator account.", 14f, yellow, false))
        return
    }

    if (profile == null) {
        page.addView(label("Loading administrator account...", 14f, yellow, false))
        if (!profileLoading) refreshProfile(showMessage = false)
        return
    }

    if (!profile.optString("role", "user").equals("admin", ignoreCase = true)) {
        page.addView(label("ACCESS DENIED. ADMINISTRATORS ONLY.", 14f, red, false))
        return
    }

    page.addView(label("HISTORICAL WINDOW: ROLLING 30 DAYS", 13f, muted, false))

    marketDataIndexes.forEach { symbol ->
        val candles = marketDataCandles[symbol].orEmpty()

        page.addView(sectionTitle(symbol))

        if (candles.isEmpty()) {
            page.addView(label("Waiting for historical candles...", 13f, yellow, false))
            return@forEach
        }

        val supports = calculateSupportResistance(candles, true)
        val resistances = calculateSupportResistance(candles, false)

        page.addView(label("STRONGEST SUPPORT LEVELS", 13f, green, true))

        if (supports.isEmpty()) {
            page.addView(label("No confirmed support levels found.", 13f, muted, false))
        } else {
            supports.take(2).forEachIndexed { index, level ->
                page.addView(
                    label(
                        "SUPPORT ${index + 1}: ${formatMarketPrice(level.price)} | CREATED: ${formatMarketTime(level.createdAt)} | TOUCHES: ${level.touches} | REVERSAL: ${String.format(java.util.Locale.US, "%.2f", level.reversalPercent)}%",
                        13f, green, false
                    )
                )
            }
        }

        page.addView(label("STRONGEST RESISTANCE LEVELS", 13f, red, true))

        if (resistances.isEmpty()) {
            page.addView(label("No confirmed resistance levels found.", 13f, muted, false))
        } else {
            resistances.take(2).forEachIndexed { index, level ->
                page.addView(
                    label(
                        "RESISTANCE ${index + 1}: ${formatMarketPrice(level.price)} | CREATED: ${formatMarketTime(level.createdAt)} | TOUCHES: ${level.touches} | REVERSAL: ${String.format(java.util.Locale.US, "%.2f", level.reversalPercent)}%",
                        13f, red, false
                    )
                )
            }
        }
    }
   }
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
            userCard.addView(button("USER VIEW", false) { showAdminUserViewDialog(user) }, matchWrap().apply { topMargin = dp(7) })
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
        ).joinToString("\n\n")
        android.app.AlertDialog.Builder(this).setTitle("User view").setMessage(details).setPositiveButton("CLOSE", null).show()
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
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
            addView(firstName)
            addView(surname)
            addView(email)
            addView(phone)
            addView(password)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Edit user details")
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
    private fun clearTradeNotificationBadges() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val manager =
                getSystemService(NOTIFICATION_SERVICE) as NotificationManager

            manager.activeNotifications
                .filter {
                    it.notification.channelId == "vi_trade_events_v2"
                }
                .forEach {
                    manager.cancel(it.tag, it.id)
                }
        }
    }
    private fun addNotificationsScreen() {
                clearTradeNotificationBadges()
        page.addView(sectionTitle("NOTIFICATIONS"))
        val card = card().apply { orientation = LinearLayout.VERTICAL }
        card.addView(label("Trade notifications", 18f, green, true))
        card.addView(label("Notifications are off until you activate them here. When enabled, the app records trade events in this feed and posts Android notifications when permission is granted.", 13f, muted, false))
        card.addView(button(if (notificationsEnabled) "TURN NOTIFICATIONS OFF" else "ACTIVATE NOTIFICATIONS", notificationsEnabled) {
            if (!notificationsEnabled) {
                appPrefs.edit().putBoolean("notificationsEnabled", true).apply()
                ensureNotificationPermission()
                notifyEvent("Notifications activated", "Trade event notifications are now enabled.")
            } else {
                appPrefs.edit().putBoolean("notificationsEnabled", false).apply()
                addNotificationFeedItem("Notifications deactivated")
            }
            render()
            }, matchWrap().apply { topMargin = dp(10) })
        card.addView(label("────────────────────────", 10f, muted, false))
                card.addView(button("CLEAR NOTIFICATION FEED", false) {
            clearTradeNotificationBadges()
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
        // Trading controls are deliberately housed in Settings; their state remains persisted in appPrefs.
        val decisionCard = card().apply { orientation = LinearLayout.VERTICAL }
        decisionCard.addView(label(if (autoTradeEnabled) "MASTER DECISION: ON" else "MASTER DECISION: OFF", 15f, if (autoTradeEnabled) green else red, true))
        decisionCard.addView(button(if (autoTradeEnabled) "TURN MASTER DECISION OFF" else "TURN MASTER DECISION ON", autoTradeEnabled) {
            autoTradeEnabled = !autoTradeEnabled
            toast(if (autoTradeEnabled) "Master Decision display is ON. Trade execution is disabled." else "Master Decision display is OFF.")
            render()
        }, matchWrap().apply { topMargin = dp(8) })
        page.addView(decisionCard, matchWrap().apply { bottomMargin = dp(10) })

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
        val noReturnError = title == "Execution rejected" &&
    message.contains("This contract offers no return", ignoreCase = true)


        val notificationScope =
            "${accountId}_${executionMode}_${title}_${message.substringBefore(" · ")}".hashCode().toString()
                val statusSignature =
            "$title|${message.substringBefore(" · ")}|${message.substringAfter(" · ", message).substringBefore(".")}"
        val lastMessageKey = "notification_last_message_$notificationScope"
        val lastTimeKey = "notification_last_time_$notificationScope"
        val lastMessage = appPrefs.getString(lastMessageKey, null)
        val lastTime = appPrefs.getLong(lastTimeKey, 0L)
        val now = System.currentTimeMillis()

        if (lastMessage == statusSignature && now - lastTime < 60 * 60 * 1000L) return

        appPrefs.edit()
            .putString(lastMessageKey, statusSignature)
            .putLong(lastTimeKey, now)
            .apply()

        val stableKey = if (noReturnError) {
            "deriv_no_return_${saDayKey()}_${accountId}_${executionMode}"
        } else {
            eventKey.ifBlank {
                "${saDayKey()}_${accountId}_${executionMode}_${title}_${message}".hashCode().toString()
            }
        }
        val seen = appPrefs.getStringSet("notification_seen_keys", emptySet())
            .orEmpty().toMutableSet()
            
        appPrefs.edit().putStringSet("notification_seen_keys", seen.toList().takeLast(500).toSet()).apply()
        addNotificationFeedItem("$title — $message")
        if (!notificationsEnabled) return
        try {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "vi_trade_events_v2"
            val vibrationPattern = longArrayOf(0L, 250L, 150L, 250L, 150L, 250L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(channelId, "Trading notifications", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setShowBadge(true)
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
            // Ignore notification delivery failures.
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
            "User Accounts",
            "Notifications",
            "Trading Logs",
            "System Settings"
        ).forEach { item ->
            card.addView(
                button("⚙  $item", false) {
                    selectedTab = when (item) {
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
            listOf("⌂" to "Home", "▥" to "Trade", "🔔" to "Notifications", "⚙" to "Settings")
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
    cancelDerivReconnect()
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

if (!derivServiceBound || !::derivApiClient.isInitialized) {
    pendingDerivToken = token
    startAndBindDerivService()
    return
}

derivApiClient.connectWithAccessToken(token, executionMode)
    }

    private fun disconnectDeriv(clearSavedTokens: Boolean = false) {
        cancelDerivReconnect()
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
        // Other tabs can be active while Deriv callbacks arrive.
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
        cancelDerivReconnect()
        if (this.accountId.isNotBlank() && this.accountId != accountId) resetAccountScopedTradeData()
        this.accountId = accountId
        this.accountType = accountType
        historyAccountKey = "$executionMode:$accountId"
        portfolioLoadedForAccount = false
        lastOpenTradesRequestAt = android.os.SystemClock.elapsedRealtime()
        derivApiClient.requestOpenTrades()
        derivApiClient.requestTradeHistory()
        marketDataRequested = true
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
            evaluateMasterEngine(symbol)

val engineQualified =
    masterEngineDecisions[symbol]?.qualified == true

val (statusText, statusColor) = dashboardDecisionStatus(symbol)

dashboardStatusViews[symbol]?.apply {
    text = statusText
    setTextColor(statusColor)
}

dashboardPriceViews[symbol]?.text = formatMarketPrice(quote)
            currentPriceViews[symbol]?.forEach { priceView ->
                priceView.text = "CURRENT PRICE: ${formatMarketPrice(quote)}"
                priceView.setTextColor(blue)
            }
        }
        }
}

    override fun onTradeData(json: JSONObject) {
    runOnUiThread {
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

val savedDirection = appPrefs.getString(
    "contract_direction_${accountId}_${executionMode}_$id",
    null
)

val direction = savedDirection
    ?.takeIf { it == "BUY" || it == "SELL" }
    ?: if (contractType.contains("PUT") || contractType.contains("FALL")) "SELL" else "BUY"

val savedPairId = appPrefs.getString(
    "contract_pair_${accountId}_${executionMode}_$id",
    ""
).orEmpty()

val savedPairSide = appPrefs.getString(
    "contract_pair_side_${accountId}_${executionMode}_$id",
    ""
).orEmpty()

val savedIsRecovery = appPrefs.getBoolean(
    "contract_is_recovery_${accountId}_${executionMode}_$id",
    false
)
if (stake > 0.0) {
    val restored = RunningContract(
        id, symbol, direction, stake, barrier, 0.0, executionMode,
        pairId = savedPairId,
        pairSide = savedPairSide,
        isRecovery = savedIsRecovery
    )
    runningContracts[id] = restored

    // Reconstruct an older original pair if its pairing data was not saved.
    if (restored.pairId.isBlank() && !restored.isRecovery) {
        val partner = runningContracts.values.firstOrNull {
            it.contractId != id &&
            it.symbol == symbol &&
            it.mode == executionMode &&
            it.direction != direction &&
            it.pairId.isBlank() &&
            !it.isRecovery
        }

        if (partner != null) {
            val restoredPairId =
                "restored_${accountId}_${executionMode}_$symbol"

            restored.pairId = restoredPairId
            restored.pairSide = "RESTORED"

            partner.pairId = restoredPairId
            partner.pairSide = "RESTORED"

            appPrefs.edit()
                .putString(
                    "contract_pair_${accountId}_${executionMode}_$id",
                    restoredPairId
                )
                .putString(
                    "contract_pair_${accountId}_${executionMode}_${partner.contractId}",
                    restoredPairId
                )
                .apply()
        }
    }
}
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
                            val actualEntry = contract.optDouble(
    "entry_tick",
                                contract.optDouble(
        "entry_spot",
        runningContracts[id]?.entryBarrier ?: 0.0
    )
)
views.barrierExpiry.text =
    "Actual Deriv entry barrier: ${if (actualEntry > 0.0) formatMarketPrice(actualEntry) else "Waiting for Deriv"}   |   Expiry: $expiryText"
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
    }

    override fun onCandles(
    symbol: String,
    candles: List<DerivApiClient.Candle>
) {
    runOnUiThread {
        val cutoffEpoch = System.currentTimeMillis() / 1000L - (30L * 24L * 60L * 60L)

marketDataCandles[symbol] = candles
    .filter { it.epoch >= cutoffEpoch }
    .sortedBy { it.epoch }
    evaluateMasterEngine(symbol)

android.util.Log.i(
    "SupportResistance",
    "$symbol: ${marketDataCandles[symbol]?.size ?: 0} candles stored for the rolling 30-day analysis."
)
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
                accountStatus.text = "●  RECONNECTING"
                accountStatus.setTextColor(yellow)
            }
        }
    }
                                        
 // DerivApiClient manages automatic reconnection.
// Do not start a second Activity-level reconnect.
override fun onDestroy() {
// Remove Activity-level callbacks.
    profileRefreshHandler.removeCallbacks(profileRefreshRunnable)

    reconnectRunnable?.let {
        reconnectHandler.removeCallbacks(it)
    }

    reconnectRunnable = null
    reconnectScheduled = false

    // Detach this Activity from the service.
    derivService?.detachActivity(this)

    // Release only the Activity binding.
    // Do NOT stop the foreground Deriv service.
    if (derivServiceBound) {
        try {
            unbindService(derivServiceConnection)
        } catch (e: IllegalArgumentException) {
            android.util.Log.w(
                "DerivService",
                "Service was already unbound",
                e
            )
        }
    }

    derivService = null
    derivServiceBound = false

    super.onDestroy()
}
                            }

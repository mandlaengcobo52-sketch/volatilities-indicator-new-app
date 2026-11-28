package za.co.volatilitiesindicator.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.app.AlertDialog
import android.widget.EditText
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.math.BigDecimal
import java.math.RoundingMode
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import org.json.JSONObject
import java.util.Locale
import java.util.Calendar
import java.util.TimeZone
import org.json.JSONArray

class MainActivity : Activity(), DerivApiClient.Callback {

    private var darkInterface = true
    private val ink: Int get() = if (darkInterface) Color.rgb(242, 246, 244) else Color.rgb(28, 39, 51)
    private val muted: Int get() = if (darkInterface) Color.WHITE else Color.rgb(28, 39, 51)
    private val whiteText: Int get() = if (darkInterface) Color.WHITE else ink
    private val green = Color.rgb(0, 200, 83)
    private val red = Color.rgb(244, 67, 54)
    private val amber = Color.rgb(255, 214, 0)
    private val gold = Color.rgb(255, 214, 0)
    private val blue = Color.rgb(33, 150, 243)
    private val pageColor: Int get() = if (darkInterface) Color.rgb(0, 7, 5) else Color.rgb(245, 247, 250)
    private val cardColor: Int get() = if (darkInterface) Color.rgb(5, 25, 18) else Color.WHITE
    private val lineColor: Int get() = green

    private lateinit var content: LinearLayout
    private var selectedTab = "Home"
    private var renderedTab = ""
    private var pageScrollView: ScrollView? = null
    private val scrollPositions = mutableMapOf<String, Int>()
    private val settingsPrefs by lazy { getSharedPreferences("vi_settings", MODE_PRIVATE) }
    // Auto trading defaults ON to preserve the existing automatic execution behaviour.
    // OFF pauses new original entries but does not affect open contracts.
    private var autoTradingEnabled = true

    private val indexes = listOf("R_10", "R_25", "R_50", "R_75", "R_100")
    private val latestPrices = mutableMapOf<String, Double>()
    // Prevent an older tick from a parallel WebSocket subscription overwriting
    // a newer quote for the same index.
    private val latestTickEpochs = mutableMapOf<String, Long>()
    private val priceViews = mutableMapOf<String, TextView>()
    private val indexStatusViews = mutableMapOf<String, TextView>()
    private val marketStatusViews = mutableMapOf<String, TextView>()
    private val masterDecisionViews = mutableMapOf<String, TextView>()
    private val levelStatusViews = mutableMapOf<String, TextView>()
    private val highLevelViews = mutableMapOf<String, TextView>()
    private val lowLevelViews = mutableMapOf<String, TextView>()
    private val recoveryStatusViews = mutableMapOf<String, TextView>()
    private val tradeRecoveryStatusViews = mutableMapOf<String, TextView>()
    private val mssStatusViews = mutableMapOf<String, TextView>()
    private val chochStatusViews = mutableMapOf<String, TextView>()
    // Structural break prices captured from the completed H1 candle's confirmed swing levels.
    private val recoveryMssPrice = mutableMapOf<String, Double>()
    private val recoveryChochPrice = mutableMapOf<String, Double>()
    private val recoverySignalClosePrice = mutableMapOf<String, Double>()

    private data class AppNotification(
        val id: Long,
        val page: String,
        val title: String,
        val message: String,
        val timestamp: Long,
        var read: Boolean = false
    )

    private val appNotifications = mutableListOf<AppNotification>()
    private val notificationPrefs by lazy {
        getSharedPreferences("vi_notifications", MODE_PRIVATE)
    }
    private val notificationChannelId = "vi_app_alerts"
    private val notificationPermissionRequest = 9001
    private val paymentProofPickerRequest = 9107

    private var marketFeedConnected = false
    private var accountConnected = false
    private var accountLabel = "Not connected"
    private var accountBalanceLabel = "Balance unavailable"
    private var availableBalance = 0.0
    private var executionMode = "DEMO"
    private var accountStatusView: TextView? = null
    private var accountOpenPlView: TextView? = null
    private var accountClosedPlView: TextView? = null
    private var accountTotalPlView: TextView? = null
    private var accountBalanceView: TextView? = null
    private var accountIdView: TextView? = null
    private val prefs by lazy { getSharedPreferences("deriv_oauth", MODE_PRIVATE) }
    private val httpClient = OkHttpClient()
    private val platformPrefs by lazy { getSharedPreferences("vi_platform_auth", MODE_PRIVATE) }
    private val platformAuthUrl = "https://www.volatilitiesindicator.org.za/mobile-auth.php"
    private val investmentApiUrl = "https://www.volatilitiesindicator.org.za/investment-api.php"
    private var platformAuthToken = ""
    private var platformAuthenticated = false
    private var platformAuthBusy = false
    private var platformUserName = ""
    private var platformUserRole = "user"
    private var platformUser = JSONObject()
    private var oauthState = ""
    private var codeVerifier = ""
    private var feedPillView: TextView? = null
    private var notificationBellView: TextView? = null

    private var derivService: DerivConnectionService? = null
    private var serviceBound = false
    private val candleRefreshHandler = Handler(Looper.getMainLooper())
    private val candleRefreshTask = object : Runnable {
        override fun run() {
            derivService?.client?.refreshHistoricalCandles()
            candleRefreshHandler.postDelayed(this, 60_000L)
        }
    }

    private data class Position(
        val localId: String,
        val symbol: String,
        var direction: String,
        var role: String,
        var stake: Double,
        var originalId: String = "",
        var engineType: String = "",
        var contractId: Long = 0L,
        var profit: Double = 0.0,
        var status: String = "PROPOSAL",
        var canSell: Boolean = false,
        var closedAt: Long = 0L,
        var expiryEpoch: Long = 0L,
        var openedAtEpoch: Long = 0L,
        var originalSignalDirection: String = "",
        // Immutable entry audit: M15 candle epochs are stored as candle-close times.
        var entryMssDirection: String = "",
        var entryMssCloseEpoch: Long = 0L,
        var entryChochDirection: String = "",
        var entryChochCloseEpoch: Long = 0L,
        var entryMssPrice: Double = 0.0,
        var entryChochPrice: Double = 0.0,
        var entryLastCandleClosePrice: Double = 0.0,
        var entryLastCandleCloseEpoch: Long = 0L,
        var executionEpoch: Long = 0L,
        // Trailing state is isolated to this individual Deriv contract.
        var trailingActivated: Boolean = false,
        var trailingPeakPercent: Double = 0.0,
        var trailingStopPercent: Double = 0.0,
        var trailingClosePending: Boolean = false,
        // Latch a rejected protected sale. Do not resubmit on every contract tick;
        // re-arm only after this contract's P/L recovers above its trailing threshold.
        var trailingCloseRejected: Boolean = false,
        // Minimum Deriv sale price requested to preserve the protected P/L.
        var trailingCloseFloorPrice: Double = 0.0
    )

    private val positions = linkedMapOf<String, Position>()
    private val pendingRequests = mutableMapOf<Int, String>()
    private val dailyLevels = mutableMapOf<String, Pair<Double, Double>>() // previous completed-day high, low (H1 source)
    private val m15CandlesByIndex = mutableMapOf<String, List<DerivApiClient.Candle>>()
    private val originalSweepStatus = mutableMapOf<String, String>()
    // Independent PDH/PDL qualification state for the two entry engines.
    private val reversalTouched = mutableMapOf<String, String>() // PDH or PDL
    private val reversalQualifiedDirection = mutableMapOf<String, String>()
    private val reversalEventEpoch = mutableMapOf<String, Long>()
    private val reversalEventPrice = mutableMapOf<String, Double>()
    private val reversalReferenceLevel = mutableMapOf<String, Double>()
    private val continuationDirection = mutableMapOf<String, String>()
    private val continuationLevel = mutableMapOf<String, Double>()
    private val continuationBreakEpoch = mutableMapOf<String, Long>()
    private val continuationRetestEpoch = mutableMapOf<String, Long>()
    private val candlesByIndex = mutableMapOf<String, List<DerivApiClient.Candle>>()
    private val originalTriggered = mutableSetOf<String>()
    // Signal identity is independent for each index and engine and survives rollover/restart.
    private val consumedEngineSignals = mutableSetOf<String>()
    private val recoveryMss = mutableMapOf<String, String>()
    private val recoveryChoch = mutableMapOf<String, String>()
    // Candle epochs are retained so a pre-existing qualification cannot trigger
    // recovery before a fresh new direction change is confirmed.
    private val recoveryMssEpoch = mutableMapOf<String, Long>()
    private val recoveryChochEpoch = mutableMapOf<String, Long>()
    private var requestCounter = 7000
    private var dailyCapital = 0.0
    private var accountCurrency = "USD"
    private var tradingDayKey = ""
    private var tradeStatusView: TextView? = null
    private val tradeStatusViews = mutableMapOf<String, TextView>()
    private val tradeProfitViews = mutableMapOf<String, TextView>()
    private val tradeTrailingViews = mutableMapOf<String, TextView>()
    private val tradeCountdownViews = mutableMapOf<String, TextView>()
    private val countdownHandler = Handler(Looper.getMainLooper())
    private val countdownTask = object : Runnable {
        override fun run() {
            updateRunningTradeCountdowns()
            countdownHandler.postDelayed(this, 1_000L)
        }
    }
    private val insufficientFundsNotified = mutableSetOf<String>()

    // Each Deriv environment has an independent ledger and capital store.
    private val tradePrefs get() = getSharedPreferences(
        "vi_trade_engine_${executionMode.lowercase(Locale.US)}", MODE_PRIVATE
    )

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? DerivConnectionService.LocalBinder)?.getService()
                ?: return
            derivService = service
            service.attachActivity(this@MainActivity)
            // Public market feed: does not require signing in to a Deriv account.
            service.client.requestHistoricalCandles()
            candleRefreshHandler.removeCallbacks(candleRefreshTask)
            candleRefreshHandler.postDelayed(candleRefreshTask, 60_000L)
            executionMode = if (prefs.getString("oauth_mode", executionMode).equals("REAL", true)) "REAL" else "DEMO"
            val tokenKey = if (executionMode == "REAL") "real_token" else "demo_token"
            val savedToken = prefs.getString(tokenKey, "").orEmpty()

            // The foreground service owns the Deriv client, so an Activity recreation
            // (including device rotation) must restore the UI from the existing socket
            // instead of treating the account as disconnected.
            if (service.client.isConnected()) {
                val activeAccountId = service.client.getAccountId()
                val activeMode = service.client.getAccountType()
                if (!activeAccountId.isNullOrBlank() &&
                    activeMode.equals(executionMode, true)
                ) {
                    accountConnected = true
                    accountLabel = "$activeAccountId ($executionMode)"
                    service.client.requestBalance()
                    service.client.requestOpenTrades()
                    runOnUiThread {
                        accountStatusView?.text = "CONNECTED"
                        accountStatusView?.setTextColor(green)
                        accountIdView?.text = accountLabel
                        render()
                    }
                } else {
                    // The service still owns a socket for the other environment.
                    // Disconnect that authenticated session before restoring the
                    // token selected in oauth_mode.
                    accountConnected = false
                    try { service.client.disconnectAccount() } catch (_: Exception) {}
                    if (savedToken.isNotBlank()) {
                        service.client.ensureConnectedOnResume(savedToken, executionMode)
                    }
                }
            } else if (savedToken.isNotBlank()) {
                service.client.ensureConnectedOnResume(savedToken, executionMode)
            }
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
        darkInterface = settingsPrefs.getBoolean("dark_interface", true)
        autoTradingEnabled = settingsPrefs.getBoolean("auto_trading_enabled", true)
        executionMode = if (prefs.getString("oauth_mode", "DEMO").equals("REAL", true)) "REAL" else "DEMO"
        window.statusBarColor = pageColor
        window.navigationBarColor = if (darkInterface) Color.rgb(19, 25, 33) else Color.WHITE
        window.decorView.systemUiVisibility = if (darkInterface) 0 else
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        tradingDayKey = ""
        activateAccountMode(executionMode)
        loadNotifications()
        createNotificationChannel()
        requestNotificationPermissionIfNeeded()
        handleNotificationIntent(intent, false)
        platformAuthToken = platformPrefs.getString("token", "").orEmpty()
        if (platformAuthToken.isNotBlank()) {
            verifyPlatformSession()
        } else {
            showPlatformAuthScreen(false)
        }
        handleOAuthCallback(intent)
    }

    private fun verifyPlatformSession() {
        platformAuthBusy = true
        val request = Request.Builder()
            .url(platformAuthUrl)
            .header("Authorization", "Bearer $platformAuthToken")
            .post(FormBody.Builder().add("action", "profile").build())
            .build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    platformAuthBusy = false
                    showPlatformAuthScreen(false, "Unable to verify your account. Check your internet connection and try again.")
                }
            }
            override fun onResponse(call: Call, response: Response) {
                val raw = response.body?.string().orEmpty()
                val json = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
                runOnUiThread {
                    platformAuthBusy = false
                    if (response.isSuccessful && json.optBoolean("ok")) {
                        platformAuthenticated = true
                        platformUser = json.optJSONObject("user") ?: JSONObject()
                        platformUserName = platformUser.optString("full_name")
                        platformUserRole = platformUser.optString("role", "user").lowercase(Locale.US)
                        // Activity recreation (including screen rotation) runs
                        // platform-session verification again. If a Deriv OAuth
                        // token is already saved, rebind to the foreground service
                        // that owns the socket instead of showing the first-connect
                        // screen and waiting for the user to connect again.
                        val savedMode = if (prefs.getString("oauth_mode", "DEMO").equals("REAL", true)) "REAL" else "DEMO"
                        val savedTokenKey = if (savedMode == "REAL") "real_token" else "demo_token"
                        val hasSavedDerivToken = prefs.getString(savedTokenKey, "").orEmpty().isNotBlank()
                        if (hasSavedDerivToken) {
                            executionMode = savedMode
                            startMarketFeedService()
                        }

                        when (platformUserRole) {
                            "admin" -> {
                                if (hasSavedDerivToken) showDerivRestoringScreen()
                                else showDerivRequiredScreen()
                            }
                            "investor" -> {
                                if (hasSavedDerivToken) showDerivRestoringScreen()
                                else showInvestorDerivScreen()
                            }
                            else -> openUserProfile()
                        }
                    } else {
                        platformPrefs.edit().remove("token").apply()
                        platformAuthToken = ""
                        platformAuthenticated = false
                        showPlatformAuthScreen(false, json.optString("message", "Please sign in again."))
                    }
                    response.close()
                }
            }
        })
    }

    private fun submitPlatformAuth(isRegister: Boolean, fields: List<EditText>, status: TextView, submit: Button, selectedRole: String = "user") {
        if (platformAuthBusy) return
        val values = fields.map { it.text.toString().trim() }
        val body = FormBody.Builder().add("action", if (isRegister) "register" else "login")
        if (isRegister) {
            if (values.size != 6 || values.any { it.isBlank() }) { status.text = "Please complete every field."; return }
            body.add("first_name", values[0]).add("surname", values[1]).add("email", values[2])
                .add("phone", values[3]).add("password", values[4]).add("password_confirm", values[5])
                .add("role", selectedRole)
        } else {
            if (values.size != 2 || values.any { it.isBlank() }) { status.text = "Enter your email and password."; return }
            body.add("email", values[0]).add("password", values[1])
        }
        platformAuthBusy = true
        submit.isEnabled = false
        status.text = if (isRegister) "Submitting registration…" else "Signing in…"
        val request = Request.Builder().url(platformAuthUrl).post(body.build()).build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { platformAuthBusy = false; submit.isEnabled = true; status.text = "Connection failed. Please try again." }
            }
            override fun onResponse(call: Call, response: Response) {
                val raw = response.body?.string().orEmpty()
                val json = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
                runOnUiThread {
                    platformAuthBusy = false
                    submit.isEnabled = true
                    val message = json.optString("message", "The request could not be completed.")
                    if (response.isSuccessful && json.optBoolean("ok")) {
                        if (isRegister) {
                            status.text = message
                            fields.forEach { it.text.clear() }
                        } else {
                            platformAuthToken = json.optString("token")
                            platformPrefs.edit().putString("token", platformAuthToken).apply()
                            platformAuthenticated = platformAuthToken.isNotBlank()
                            platformUser = json.optJSONObject("user") ?: JSONObject()
                            platformUserName = platformUser.optString("full_name")
                            platformUserRole = platformUser.optString("role", "user").lowercase(Locale.US)
                            if (platformAuthenticated) {
                                when (platformUserRole) {
                                    "admin" -> showDerivRequiredScreen()
                                    "investor" -> showInvestorDerivScreen()
                                    else -> openUserProfile()
                                }
                            } else status.text = "Login did not return a valid session."
                        }
                    } else status.text = message
                    response.close()
                }
            }
        })
    }

    private fun showPlatformAuthScreen(register: Boolean, message: String = "") {
        platformAuthenticated = false

        // Keep authentication screens consistent with the Volatilities Indicator website.
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(18), dp(20), dp(20))
            setBackgroundColor(Color.rgb(0, 7, 5))
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }

        // Required branded logo resource: app/src/main/res/drawable/volatilities_logo.png
        page.addView(ImageView(this).apply {
            setImageResource(R.drawable.volatilities_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            contentDescription = "Volatilities Indicator logo"
        }, LinearLayout.LayoutParams(dp(154), dp(154)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(8)
        })

        page.addView(TextView(this).apply {
            text = "VOLATILITIES INDICATOR"
            textSize = 25f
            setTextColor(green)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(22))
        })

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(22))
            background = GradientDrawable().apply {
                setColor(Color.rgb(3, 22, 14))
                setCornerRadius(dp(22).toFloat())
                setStroke(dp(2), green)
            }
        }
        panel.addView(TextView(this).apply {
            text = if (register) "CREATE YOUR PROFILE" else "ACCOUNT LOGIN"
            textSize = 21f
            setTextColor(green)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        })
        val status = TextView(this).apply {
            text = message
            textSize = 13f
            setTextColor(amber)
            setPadding(0, dp(4), 0, dp(10))
        }
        panel.addView(status)

        fun field(hint: String, password: Boolean = false): EditText {
            val edit = EditText(this).apply {
                this.hint = hint
                textSize = 16f
                setTextColor(Color.WHITE)
                setHintTextColor(Color.rgb(170, 190, 180))
                setSingleLine(true)
                if (password) {
                    inputType = android.text.InputType.TYPE_CLASS_TEXT
                    transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
                }
                backgroundTintList = android.content.res.ColorStateList.valueOf(green)
            }
            val fieldParams = LinearLayout.LayoutParams(-1, dp(58)).apply {
                bottomMargin = dp(7)
            }
            if (password) {
                val passwordRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                passwordRow.addView(edit, LinearLayout.LayoutParams(0, dp(58), 1f))
                var passwordVisible = false
                val eye = TextView(this).apply {
                    text = "SHOW"
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setTextColor(green)
                    setPadding(dp(10), 0, dp(4), 0)
                    contentDescription = "Show password"
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        val selection = edit.selectionStart.coerceAtLeast(0)
                        passwordVisible = !passwordVisible
                        edit.transformationMethod = if (passwordVisible) {
                            android.text.method.HideReturnsTransformationMethod.getInstance()
                        } else {
                            android.text.method.PasswordTransformationMethod.getInstance()
                        }
                        edit.setSelection(selection.coerceAtMost(edit.text.length))
                        text = if (passwordVisible) "HIDE" else "SHOW"
                        contentDescription = if (passwordVisible) "Hide password" else "Show password"
                    }
                }
                passwordRow.addView(eye, LinearLayout.LayoutParams(dp(58), dp(58)))
                panel.addView(passwordRow, fieldParams)
            } else {
                panel.addView(edit, fieldParams)
            }
            return edit
        }

        var selectedRegistrationRole = "user"
        if (register) {
            panel.addView(TextView(this).apply {
                text = "SELECT ACCOUNT TYPE"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setPadding(0, dp(8), 0, dp(6))
            })
            val roleChoices = RadioGroup(this).apply {
                orientation = RadioGroup.VERTICAL
                setPadding(dp(4), 0, 0, dp(10))
            }
            val userChoice = RadioButton(this).apply {
                id = View.generateViewId()
                text = "USER — Standard platform account"
                textSize = 14f
                setTextColor(Color.WHITE)
                isChecked = true
                buttonTintList = android.content.res.ColorStateList.valueOf(green)
            }
            val investorChoice = RadioButton(this).apply {
                id = View.generateViewId()
                text = "INVESTOR — Deriv trading account"
                textSize = 14f
                setTextColor(Color.WHITE)
                buttonTintList = android.content.res.ColorStateList.valueOf(green)
            }
            roleChoices.addView(userChoice)
            roleChoices.addView(investorChoice)
            roleChoices.setOnCheckedChangeListener { _, checkedId ->
                selectedRegistrationRole = if (checkedId == investorChoice.id) "investor" else "user"
            }
            panel.addView(roleChoices)
        }

        val fields = if (register) listOf(
            field("First name"),
            field("Surname"),
            field("Email address").apply { inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS },
            field("Contact number").apply { inputType = android.text.InputType.TYPE_CLASS_PHONE },
            field("Password", true),
            field("Confirm password", true)
        ) else listOf(
            field("Email address").apply { inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS },
            field("Password", true)
        )

        val submit = Button(this).apply {
            text = if (register) "CREATE ACCOUNT" else "LOGIN"
            textSize = 16f
            isAllCaps = false
            setTextColor(Color.BLACK)
            background = rounded(green, green, 10)
            setOnClickListener { submitPlatformAuth(register, fields, status, this, selectedRegistrationRole) }
        }
        panel.addView(submit, LinearLayout.LayoutParams(-1, dp(54)).apply {
            topMargin = dp(10)
        })

        panel.addView(TextView(this).apply {
            text = if (register) "Already registered? Login" else "New user? Register here"
            gravity = Gravity.CENTER
            setTextColor(green)
            setPadding(0, dp(20), 0, dp(8))
            textSize = 16f
            setOnClickListener { showPlatformAuthScreen(!register) }
        })

        page.addView(panel, LinearLayout.LayoutParams(-1, -2))
        scroll.addView(page)
        root.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    private fun showDerivRestoringScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(pageColor)
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            background = GradientDrawable().apply {
                setColor(cardColor)
                setCornerRadius(dp(16).toFloat())
                setStroke(dp(1), lineColor)
            }
        }
        panel.addView(TextView(this).apply {
            text = "RESTORING DERIV CONNECTION"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(green)
        })
        panel.addView(TextView(this).apply {
            text = "Your saved Deriv session is being restored. Please wait; you do not need to connect again."
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(ink)
            setPadding(0, dp(16), 0, dp(8))
        })
        root.addView(panel, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
    }

    private fun showDerivRequiredScreen() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(24), dp(24), dp(24), dp(24)); setBackgroundColor(pageColor) }
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(24), dp(20), dp(24)); background = GradientDrawable().apply { setColor(cardColor); setCornerRadius(dp(16).toFloat()); setStroke(dp(1), lineColor) } }
        panel.addView(TextView(this).apply { text = "PLATFORM ACCOUNT APPROVED"; textSize = 19f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(gold) })
        panel.addView(TextView(this).apply { text = "Welcome${if (platformUserName.isNotBlank()) ", $platformUserName" else ""}. Connect your Deriv account to unlock the application."; textSize = 14f; gravity = Gravity.CENTER; setTextColor(ink); setPadding(0, dp(16), 0, dp(16)) })
        panel.addView(Button(this).apply { text = "CONNECT DERIV ACCOUNT"; setTextColor(Color.BLACK); backgroundTintList = android.content.res.ColorStateList.valueOf(green); setOnClickListener { startMarketFeedService(); showAccountModeDialog() } }, LinearLayout.LayoutParams(-1, dp(52)))
        panel.addView(TextView(this).apply { text = "Sign out"; gravity = Gravity.CENTER; setTextColor(red); setPadding(0, dp(18), 0, dp(8)); setOnClickListener { signOutPlatform() } })
        root.addView(panel, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
    }

    private fun showInvestorDerivScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(pageColor)
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            background = GradientDrawable().apply {
                setColor(cardColor)
                setCornerRadius(dp(16).toFloat())
                setStroke(dp(1), lineColor)
            }
        }
        panel.addView(TextView(this).apply {
            text = "INVESTOR ACCOUNT APPROVED"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(green)
        })
        panel.addView(TextView(this).apply {
            text = "Welcome${if (platformUserName.isNotBlank()) ", $platformUserName" else ""}. Continue to Deriv to register a free account or log in to your existing account. After successful Deriv login, you will enter the Investor trading area."
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(ink)
            setPadding(0, dp(16), 0, dp(16))
        })
        panel.addView(Button(this).apply {
            text = "REGISTER / LOGIN WITH DERIV"
            setOnClickListener { showAccountModeDialog() }
        }, LinearLayout.LayoutParams(-1, dp(52)))
        panel.addView(TextView(this).apply {
            text = "Sign out"
            gravity = Gravity.CENTER
            setTextColor(red)
            setPadding(0, dp(18), 0, dp(8))
            setOnClickListener { signOutPlatform() }
        })
        root.addView(panel, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
    }

    private fun signOutPlatform() {
        val request = Request.Builder().url(platformAuthUrl).header("Authorization", "Bearer $platformAuthToken")
            .post(FormBody.Builder().add("action", "logout").build()).build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { }
            override fun onResponse(call: Call, response: Response) { response.close() }
        })
        platformPrefs.edit().clear().apply()
        platformAuthToken = ""; platformAuthenticated = false
        platformUser = JSONObject(); platformUserName = ""; platformUserRole = "user"
        derivService?.client?.disconnectAccount()
        accountConnected = false
        prefs.edit().remove("demo_token").remove("real_token").apply()
        showPlatformAuthScreen(false, "You have signed out.")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
        handleOAuthCallback(intent)
    }

    override fun onPause() {
        pageScrollView?.let { scrollPositions[selectedTab] = it.scrollY }
        // Flush the complete trade ledger whenever the activity leaves the foreground.
        savePositions()
        saveNotifications()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (!platformAuthenticated) return
        val selectedMode = if (prefs.getString("oauth_mode", executionMode).equals("REAL", true)) "REAL" else "DEMO"
        if (selectedMode != executionMode) {
            activateAccountMode(selectedMode)
            accountConnected = false
            if (::content.isInitialized) render()
        }
        val tokenKey = if (executionMode == "REAL") "real_token" else "demo_token"
        val savedToken = prefs.getString(tokenKey, "").orEmpty()
        if (savedToken.isNotBlank()) {
            if (derivService == null) {
                // Rebind after Activity recreation; the foreground service keeps
                // the Deriv client/socket alive while this Activity is destroyed.
                startMarketFeedService()
            } else if (derivService?.client?.isConnected() != true &&
                derivService?.client?.isConnecting() != true
            ) {
                derivService?.client?.ensureConnectedOnResume(savedToken, executionMode)
            }
        }
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
        candleRefreshHandler.removeCallbacks(candleRefreshTask)
        countdownHandler.removeCallbacks(countdownTask)
        derivService?.detachActivity(this)
        if (serviceBound) {
            try { unbindService(serviceConnection) } catch (_: Exception) {}
            serviceBound = false
        }
        super.onDestroy()
    }

    override fun onConnecting(message: String) {
        runOnUiThread {
            accountConnected = false
            accountStatusView?.text = message
            accountStatusView?.setTextColor(amber)
            if (selectedTab == "Accounts") render()
        }
    }

    override fun onConnected(accountId: String, accountType: String) {
        if (!platformAuthenticated) {
            derivService?.client?.disconnect()
            accountConnected = false
            runOnUiThread { showPlatformAuthScreen(false, "Please sign in to your Volatilities Indicator account first.") }
            return
        }
        val connectedMode = if (accountType.equals("REAL", true)) "REAL" else "DEMO"
        if (connectedMode != executionMode) activateAccountMode(connectedMode)
        accountConnected = true
        accountLabel = "$accountId ($accountType)"
        addNotification("Accounts", "Deriv account connected", "$accountLabel is connected.")
        derivService?.client?.requestOpenTrades()
        // Re-check already-qualified MSS/CHOCH against any waiting closed original
        // immediately after the execution account reconnects.
        m15CandlesByIndex.forEach { (symbol, candles) -> evaluateRecoverySignals(symbol, candles) }
        runOnUiThread {
            if (platformUserRole == "investor") selectedTab = "Trade"
            accountIdView?.text = accountLabel
            accountStatusView?.text = "CONNECTED"
            accountStatusView?.setTextColor(green)
            toast("Deriv $executionMode account connected.")
            render()
        }
    }

    override fun onBalance(balance: Double, currency: String) {
        availableBalance = balance
        accountBalanceLabel = String.format(Locale.US, "%.2f %s", balance, currency)
        accountCurrency = currency
        val today = utcDayKey(System.currentTimeMillis() / 1000L)
        if (tradingDayKey != today) {
            tradingDayKey = today
            dailyCapital = balance
            originalTriggered.clear()
            tradePrefs.edit().putString("day_key", today).putFloat("daily_capital", balance.toFloat()).apply()
        } else if (dailyCapital <= 0.0) {
            dailyCapital = tradePrefs.getFloat("daily_capital", balance.toFloat()).toDouble()
        }
        runOnUiThread {
            accountBalanceView?.text = accountBalanceLabel
            renderIfHome()
        }
    }

    override fun onTick(symbol: String, quote: Double, epoch: Long) {
        if (symbol !in indexes || !quote.isFinite()) return

        // The authenticated and public market-data sockets can both deliver
        // ticks. Ignore an older/duplicate timestamp so a delayed callback
        // cannot replace a newer quote already received for this index.
        synchronized(latestTickEpochs) {
            val previousEpoch = latestTickEpochs[symbol] ?: Long.MIN_VALUE
            if (epoch > 0L && epoch <= previousEpoch) return
            if (epoch > 0L) latestTickEpochs[symbol] = epoch
            latestPrices[symbol] = quote
        }

        marketFeedConnected = true
        if (accountConnected) evaluateOriginalEntries(symbol, quote)
        runOnUiThread {
            // This was missing: the TextView was only populated during render(),
            // so the stored live quote did not refresh on incoming ticks.
            priceViews[symbol]?.text = formatPrice(quote)

            feedPillView?.apply {
                text = "LIVE"
                setTextColor(Color.BLACK)
                background = rounded(green, green, 20)
            }

            indexStatusViews[symbol]?.apply {
                text = "LIVE"
                setTextColor(Color.BLACK)
                background = rounded(green, green, 20)
            }
            updateMasterDecisionViews(symbol)
        }
    }

    override fun onCandles(symbol: String, candles: List<DerivApiClient.Candle>) {
        if (symbol !in indexes) return
        if (candles.isNotEmpty()) {
            marketFeedConnected = true
            candlesByIndex[symbol] = candles.sortedBy { it.epoch }
            calculatePreviousDailyLevels(symbol, candles)
            reconcileRestoredOriginalPairs(symbol)
            val lastClose = candles.last().close
            if (!latestPrices.containsKey(symbol)) latestPrices[symbol] = lastClose
            runOnUiThread {
                feedPillView?.apply {
    text = "LIVE"
    setTextColor(Color.BLACK)
    background = rounded(green, green, 20)
}

indexStatusViews[symbol]?.apply {
    text = "LIVE"
    setTextColor(Color.BLACK)
    background = rounded(green, green, 20)
}
                updateMasterDecisionViews(symbol)
                updateRecoveryStatusView(symbol)
            }
            if (selectedTab == "Home") runOnUiThread { updateMasterDecisionViews(symbol); updateRecoveryStatusView(symbol) }
        }
    }

    override fun onM15Candles(symbol: String, candles: List<DerivApiClient.Candle>) {
        if (symbol !in indexes || candles.isEmpty()) return
        val ordered = candles.sortedBy { it.epoch }
        m15CandlesByIndex[symbol] = ordered

        // Evaluate completed M15 candles before checking either entry engine.
        updatePreviousDayLevelBreakQualification(symbol, ordered)
        // MSS and CHOCH are now calculated exclusively from M15 candles.
        evaluateRecoverySignals(symbol, ordered)
        latestPrices[symbol]?.let { evaluateOriginalEntries(symbol, it) }

        runOnUiThread {
            updateRecoveryStatusView(symbol)
            updateMasterDecisionViews(symbol)
        }
    }

    override fun onMarketDataError(symbol: String, message: String) {
        runOnUiThread {
            indexStatusViews[symbol]?.apply {
                text = "FEED ERROR"
                setTextColor(red)
            }
            marketStatusViews[symbol]?.text = message
            // Market-data errors remain visible on the index card; do not send notifications.
            updateConnectionLabels("Market data error: $symbol")
        }
    }

    override fun onError(message: String) {
        runOnUiThread {
            // A stale/minimum-price rejection from a protected trailing sale is an
            // order-level event, not a connection failure. onTradeData records the
            // single contract-specific rejection; do not also toast and notify it here.
            val lowerMessage = message.lowercase(Locale.US)
            val protectedSalePriceRejection =
                lowerMessage.contains("underlying market has moved too much since you priced the contract") ||
                lowerMessage.contains("contract sell price has changed")
            if (protectedSalePriceRejection) return@runOnUiThread

            addNotification("Accounts", "Deriv connection issue", message)
            if (accountConnected) {
                toast(message)
            } else {
                accountLabel = "Connection failed"
                accountBalanceLabel = message
                accountStatusView?.text = "CONNECTION FAILED"
                accountStatusView?.setTextColor(red)
                accountIdView?.text = accountLabel
                accountBalanceView?.text = message
                if (selectedTab == "Accounts") render()
            }
        }
    }

    override fun onTradeData(json: JSONObject) {
        val reqId = json.optInt("req_id", -1)
        val localId = pendingRequests[reqId]
        val error = json.optJSONObject("error")
        if (error != null) {
            if (localId != null) {
                pendingRequests.remove(reqId)
                val failedPosition = positions[localId]
                if (failedPosition != null) {
                    val errorMessage = error.optString("message", "Deriv request failed")
                    val wasClosing = failedPosition.status == "CLOSING"
                    val wasTrailingClose = failedPosition.trailingClosePending
                    failedPosition.status = if (failedPosition.contractId > 0L) "OPEN" else "ERROR: $errorMessage"
                    failedPosition.trailingClosePending = false
                    if (wasTrailingClose) {
                        // Keep the rejection latched. Repeated open-contract updates
                        // must not submit the same stale protected-sale price again.
                        failedPosition.trailingCloseRejected = true
                        addNotification(
                            "Trade",
                            "${displayIndexName(failedPosition.symbol)} protected sale not filled",
                            "Deriv did not accept the trailing-stop sale at the required minimum price of ${formatMoney(failedPosition.trailingCloseFloorPrice)}. The contract remains OPEN; no below-floor market sale will be sent. Deriv response: $errorMessage"
                        )
                    } else {
                        addNotification("Trade", "${displayIndexName(failedPosition.symbol)} trade request failed", errorMessage + if (wasClosing) " Automatic close remains pending; the contract is not marked closed." else "")
                    }
                }
                savePositions()
                runOnUiThread { if (selectedTab == "Trade") render() }
            }
            return
        }
        when (json.optString("msg_type")) {
            "proposal" -> {
                if (localId == null) return
                pendingRequests.remove(reqId)
                val proposal = json.optJSONObject("proposal") ?: return
                val proposalId = proposal.optString("id", "")
                val position = positions[localId] ?: return
                if (proposalId.isBlank()) { position.status = "PROPOSAL FAILED"; return }
                position.status = "BUYING"
                val buyReq = nextRequestId()
                pendingRequests[buyReq] = localId
                val buy = JSONObject().put("buy", proposalId).put("price", roundStake(position.stake)).put("req_id", buyReq)
                derivService?.client?.send(buy)
            }
            "buy" -> {
                if (localId == null) return
                pendingRequests.remove(reqId)
                val buy = json.optJSONObject("buy") ?: return
                val position = positions[localId] ?: return
                position.contractId = buy.optLong("contract_id", 0L)
                if (position.contractId <= 0L) { position.status = "BUY FAILED"; return }
                val confirmedBuyPrice = buy.optDouble("buy_price", position.stake)
                if (confirmedBuyPrice.isFinite() && confirmedBuyPrice > 0.0) {
                    position.stake = confirmedBuyPrice
                }
                position.status = "OPEN"
                position.openedAtEpoch = buy.optLong("purchase_time", System.currentTimeMillis() / 1000L)
                position.executionEpoch = position.openedAtEpoch
                savePositions()
                addNotification("Trade", "${displayIndexName(position.symbol)} ${position.role.lowercase(Locale.US)} opened", "${position.direction} contract opened. Stake: ${formatMoney(position.stake)}.")
                subscribePosition(position.contractId)
                runOnUiThread {
                    updateMasterDecisionViews(position.symbol)
                    if (selectedTab == "Trade") render()
                }
            }
            "sell" -> {
                if (localId == null) return
                pendingRequests.remove(reqId)
                val closingPosition = positions[localId] ?: return
                val receipt = json.optJSONObject("sell") ?: return
                val soldContractId = receipt.optLong("contract_id", 0L)
                if (soldContractId != closingPosition.contractId || soldContractId <= 0L) {
                    closingPosition.status = "OPEN"
                    addNotification("Trade", "${displayIndexName(closingPosition.symbol)} close response mismatch", "Deriv returned a sale receipt for contract $soldContractId, but this position is contract ${closingPosition.contractId}. The position remains open pending confirmation.")
                } else if (closingPosition.status != "CLOSED") {
                    // A successful Deriv sell receipt is the sale confirmation.
                    // Do not leave the position waiting indefinitely for a later
                    // proposal_open_contract packet that may be delayed or missed.
                    val soldFor = receipt.optDouble("sold_for", Double.NaN)
                    val wasTrailingClose = closingPosition.trailingClosePending
                    val requestedFloor = closingPosition.trailingCloseFloorPrice
                    if (soldFor.isFinite()) {
                        closingPosition.profit = soldFor - closingPosition.stake
                    }
                    if (wasTrailingClose && requestedFloor > 0.0 && soldFor.isFinite() &&
                        soldFor + 0.01 < requestedFloor) {
                        addNotification(
                            "Trade",
                            "${displayIndexName(closingPosition.symbol)} trailing protection breach",
                            "Deriv confirmed sale below the requested minimum sale price. Requested minimum: ${formatMoney(requestedFloor)}; actual sale: ${formatMoney(soldFor)}; actual P/L: ${formatMoney(closingPosition.profit)}. This is recorded as a protection breach, not a successful protected-profit exit."
                        )
                    }
                    closingPosition.status = "CLOSED"
                    closingPosition.canSell = false
                    closingPosition.trailingClosePending = false
                    closingPosition.trailingCloseRejected = false
                    closingPosition.closedAt = System.currentTimeMillis() / 1000L

                    addNotification("Trade", "${displayIndexName(closingPosition.symbol)} trade closed", "${closingPosition.role} ${closingPosition.direction} final P/L: ${formatMoney(closingPosition.profit)}.")
                }
                savePositions()
                runOnUiThread {
                    updateMasterDecisionViews(closingPosition.symbol)
                    updateRecoveryStatusView(closingPosition.symbol)
                    if (selectedTab == "Trade") render()
                    updateAccountProfitLossViews()
                }
            }
            "proposal_open_contract" -> handleOpenContract(json.optJSONObject("proposal_open_contract") ?: return)
            "portfolio" -> {
                // Ignore stale portfolio packets from the previously connected
                // account. Only the authenticated socket matching the selected
                // DEMO/REAL environment may populate this environment's ledger.
                if (!accountConnected ||
                    derivService?.client?.getAccountType()?.equals(executionMode, true) != true
                ) return

                val list = json.optJSONObject("portfolio")
                    ?.optJSONArray("contracts") ?: return

                for (i in 0 until list.length()) {
                    val c = list.optJSONObject(i) ?: continue

                    val id = c.optLong("contract_id", 0L)
                    if (id <= 0L) continue

                    val symbol = c.optString(
                        "underlying_symbol",
                        c.optString("symbol", "")
                    )

                    if (symbol !in indexes) continue

                    val contractType = c.optString("contract_type", "")
                        .uppercase(Locale.US)

                    val direction = when (contractType) {
                        "CALL" -> "RISE"
                        "PUT" -> "FALL"
                        else -> continue
                    }

                    val stake = c.optDouble(
                        "buy_price",
                        c.optDouble("purchase_price", 0.0)
                    )

                    val existing = positions.values.firstOrNull {
                        it.contractId == id
                    }

                    val portfolioExpiry = c.optLong("date_expiry", 0L)
                    // Only use Deriv's actual purchase_time. Do not manufacture an
                    // opening timestamp by subtracting an assumed duration from expiry.
                    val portfolioPurchase = c.optLong("purchase_time", 0L)
                    if (existing != null) {
                        existing.direction = direction
                        existing.expiryEpoch = portfolioExpiry.takeIf { it > 0L } ?: existing.expiryEpoch
                        if (portfolioPurchase > 0L) {
                            existing.openedAtEpoch = portfolioPurchase
                            if (existing.executionEpoch <= 0L) existing.executionEpoch = portfolioPurchase
                        }
                        if (existing.status != "CLOSING") {
                            existing.status = "OPEN"
                        }
                    } else {
                        val restored = Position(
                            localId = "DERIV-$id",
                            symbol = symbol,
                            direction = direction,
                            role = "EXISTING",
                            stake = stake
                        )

                        restored.contractId = id
                        restored.expiryEpoch = portfolioExpiry
                        restored.openedAtEpoch = portfolioPurchase
                        restored.executionEpoch = portfolioPurchase
                        restored.status = "OPEN"
                        positions[restored.localId] = restored
                    }

                    // The contract must exist locally before its updates arrive.
                    subscribePosition(id)
                }

                reconcileRestoredOriginalPairs(symbolHint = null)
                savePositions()

                runOnUiThread {
                    updateAccountProfitLossViews()

                    indexes.forEach { symbol ->
                        updateMasterDecisionViews(symbol)
                        updateRecoveryStatusView(symbol)
                    }

                    if (selectedTab == "Trade") {
                        render()
                    }
                }
            }
        }
    }

    override fun onDisconnected() {
        val wasConnected = accountConnected
        accountConnected = false
        if (wasConnected) addNotification("Accounts", "Deriv account disconnected", "The authenticated Deriv account connection was lost.")
        accountLabel = "Not connected"
        accountBalanceLabel = "Balance unavailable"
        runOnUiThread {
            accountStatusView?.text = "ACCOUNT NOT CONNECTED"
            accountStatusView?.setTextColor(red)
            renderIfHome()
        }
    }


    private fun handleNotificationIntent(intent: Intent?, shouldRender: Boolean = true) {
        if (intent == null) return
        val page = intent.getStringExtra("notification_page")
        val notificationId = intent.getLongExtra("notification_id", -1L)
        if (!page.isNullOrBlank() && page in listOf("Home", "Trade", "Accounts", "Settings")) {
            selectedTab = if (platformUserRole == "investor" && page !in listOf("Trade", "Accounts")) "Trade" else page
        }
        if (notificationId > 0L) {
            appNotifications.firstOrNull { it.id == notificationId }?.let { item ->
                item.read = true
                cancelAndroidNotification(item)
            }
            saveNotifications()
            updateNotificationBadge()
        }
        intent.removeExtra("notification_page")
        intent.removeExtra("notification_id")
        if (shouldRender && !page.isNullOrBlank()) render()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                notificationPermissionRequest
            )
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                notificationChannelId,
                "Volatilities Indicator Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Market, trading, recovery and account notifications"
            }
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun addNotification(page: String, title: String, message: String) {
        runOnUiThread {
            val duplicate = appNotifications.any {
                it.page == page && it.title == title && it.message == message && !it.read
            }
            if (duplicate) return@runOnUiThread

            val now = System.currentTimeMillis()
            val item = AppNotification(now, page, title, message, now)
            appNotifications.add(0, item)
            while (appNotifications.size > 250) appNotifications.removeAt(appNotifications.lastIndex)
            saveNotifications()
            showAndroidNotification(item)
            updateNotificationBadge()
        }
    }

    private fun showAndroidNotification(item: AppNotification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return

        val tapIntent = Intent(this, MainActivity::class.java).apply {
            putExtra("notification_page", item.page)
            putExtra("notification_id", item.id)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            item.id.hashCode(),
            tapIntent,
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, notificationChannelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setPriority(Notification.PRIORITY_DEFAULT)
        }
        builder.setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(item.title)
            .setContentText(item.message)
            .setStyle(Notification.BigTextStyle().bigText(item.message))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(item.id.hashCode(), builder.build())
    }

    private fun saveNotifications() {
        val array = JSONArray()
        appNotifications.forEach { item ->
            array.put(JSONObject()
                .put("id", item.id)
                .put("page", item.page)
                .put("title", item.title)
                .put("message", item.message)
                .put("timestamp", item.timestamp)
                .put("read", item.read))
        }
        notificationPrefs.edit().putString("items", array.toString()).commit()
    }

    private fun loadNotifications() {
        appNotifications.clear()
        val raw = notificationPrefs.getString("items", "") ?: return
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                appNotifications.add(AppNotification(
                    obj.optLong("id"),
                    obj.optString("page", "Home"),
                    obj.optString("title"),
                    obj.optString("message"),
                    obj.optLong("timestamp"),
                    obj.optBoolean("read", false)
                ))
            }
        } catch (_: Exception) {
            appNotifications.clear()
        }
    }

    private fun notificationBar(): View {
        val row = horizontal()
        row.addView(text(selectedTab.uppercase(Locale.US), 13f, gold, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        val unread = appNotifications.count { !it.read }
        val bell = TextView(this).apply {
            text = if (unread > 0) "🔔 $unread" else "🔔"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(gold)
            setPadding(dp(13), dp(8), dp(13), dp(8))
            background = rounded(Color.BLACK, lineColor, 22)
            contentDescription = "$unread unread notifications"
            setOnClickListener { showNotificationsDialog() }
        }
        notificationBellView = bell
        row.addView(bell)
        return row
    }

    private fun updateNotificationBadge() {
        val unread = appNotifications.count { !it.read }
        notificationBellView?.text = if (unread > 0) "🔔 $unread" else "🔔"
        notificationBellView?.setTextColor(gold)
        notificationBellView?.contentDescription = "$unread unread notifications"
    }

    private fun showNotificationsDialog() {
        if (appNotifications.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("ALL NOTIFICATIONS")
                .setMessage("There are no notifications.")
                .setPositiveButton("CLOSE", null)
                .show()
            return
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(Color.BLACK)
        }
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        appNotifications.forEach { item ->
            val entry = TextView(this).apply {
                val state = if (item.read) "READ" else "UNREAD"
                val time = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(item.timestamp))
                text = "$state · ${item.page.uppercase(Locale.US)} · ${item.title}\n${item.message}\n$time"
                textSize = 14f
                setTextColor(gold)
                setPadding(dp(8), dp(12), dp(8), dp(12))
                setBackgroundColor(Color.BLACK)
                setOnClickListener {
                    item.read = true
                    cancelAndroidNotification(item)
                    saveNotifications()
                    updateNotificationBadge()
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(item.title)
                        .setMessage("${item.page}\n\n${item.message}")
                        .setPositiveButton("CLOSE", null)
                        .show()
                }
            }
            list.addView(entry)
            val separator = View(this).apply { setBackgroundColor(lineColor) }
            list.addView(separator, LinearLayout.LayoutParams(-1, dp(1)))
        }
        scroll.addView(list)
        panel.addView(scroll, LinearLayout.LayoutParams(-1, dp(360)))
        val unread = appNotifications.count { !it.read }
        AlertDialog.Builder(this)
            .setTitle("ALL NOTIFICATIONS ($unread UNREAD)")
            .setView(panel)
            .setNeutralButton("MARK ALL READ") { _, _ ->
    appNotifications.forEach {
        it.read = true
        cancelAndroidNotification(it)
    }
    saveNotifications()
    updateNotificationBadge()
}
.setPositiveButton("CLEAR ALL") { _, _ ->
    AlertDialog.Builder(this)
        .setTitle("CLEAR ALL NOTIFICATIONS")
        .setMessage("Are you sure you want to delete all saved notifications?")
        .setNegativeButton("CANCEL", null)
        .setPositiveButton("CLEAR") { _, _ ->
            appNotifications.toList().forEach {
                cancelAndroidNotification(it)
            }

            appNotifications.clear()
            saveNotifications()
            updateNotificationBadge()
        }
        .show()
}
.setNegativeButton("CLOSE", null)
.show()
    }

    private fun cancelAndroidNotification(item: AppNotification) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(item.id.hashCode())
    }

    private fun referenceLevelsText(symbol: String): String {
        val levels = dailyLevels[symbol] ?: return "REFERENCE HIGH: --\nREFERENCE LOW: --"
        return "PREVIOUS DAY HIGH: ${formatPrice(levels.first)}\nPREVIOUS DAY LOW: ${formatPrice(levels.second)}"
    }

    private fun runningDirectionText(symbol: String): String? {
        val running = positions.values.filter {
            it.symbol == symbol && it.contractId > 0L &&
                it.status !in setOf("CLOSED") && !it.status.startsWith("ERROR") &&
                it.status !in setOf("BUY FAILED", "PROPOSAL FAILED")
        }
        if (running.isEmpty()) return null
        val directions = running.map { it.direction }.distinct()
        return "TRADE RUNNING · " + directions.joinToString(" + ")
    }

    private fun masterDecisionText(symbol: String): String {
        runningDirectionText(symbol)?.let { return "$it. Contract is active on Deriv." }
        if (!marketFeedConnected || latestPrices[symbol] == null) {
            return "Waiting for live market price data."
        }
        if (!accountConnected) return "M15 engines are monitored. Waiting for Deriv account connection."

        val mss = recoveryMss[symbol]
        val choch = recoveryChoch[symbol]
        val mssEpoch = recoveryMssEpoch[symbol] ?: 0L
        val chochEpoch = recoveryChochEpoch[symbol] ?: 0L
        val touched = reversalTouched[symbol]
        val reversal = reversalQualifiedDirection[symbol]
        val reversalEpoch = reversalEventEpoch[symbol] ?: 0L
        val continuation = continuationDirection[symbol]
        val retestEpoch = continuationRetestEpoch[symbol] ?: 0L
        val eventEpoch = when {
            reversal != null -> reversalEpoch
            continuation != null && retestEpoch > 0L -> retestEpoch
            else -> 0L
        }

        // Signal epochs are candle OPEN times, while engine event epochs are
        // candle CLOSE times. Require the MSS/CHOCH candle to CLOSE strictly
        // after the reversal/retest candle has closed. The event candle itself
        // must never double as post-event structure confirmation.
        val mssAfterEvent = eventEpoch > 0L && mssEpoch + 900L > eventEpoch
        val chochAfterEvent = eventEpoch > 0L && chochEpoch + 900L > eventEpoch
        val mssReady = mssAfterEvent && mss == (reversal ?: continuation)
        val chochReady = chochAfterEvent && choch == (reversal ?: continuation)

        if (touched == "PDH") {
            return "REVERSAL BEARISH · PDH touched. Waiting for a completed M15 close below PDH."
        }
        if (touched == "PDL") {
            return "REVERSAL BULLISH · PDL touched. Waiting for a completed M15 close above PDL."
        }
        if (reversal != null) {
            return when {
                !mssReady && !chochReady -> "REVERSAL ${reversal} · Reversal candle confirmed. Waiting for M15 MSS and CHOCH."
                !mssReady -> "REVERSAL ${reversal} · Waiting for a fresh matching M15 MSS."
                !chochReady -> "REVERSAL ${reversal} · MSS confirmed. Waiting for a fresh matching M15 CHOCH."
                else -> "REVERSAL ${reversal} · M15 MSS + CHOCH confirmed. Ready for execution."
            }
        }
        if (continuation != null && retestEpoch == 0L) {
            val level = continuationLevel[symbol] ?: 0.0
            return "CONTINUATION ${continuation} · Breakout confirmed. WAITING FOR RETEST of ${formatPrice(level)}."
        }
        if (continuation != null && retestEpoch > 0L) {
            return when {
                !mssReady && !chochReady -> "CONTINUATION ${continuation} · Retest confirmed. Waiting for M15 MSS and CHOCH."
                !mssReady -> "CONTINUATION ${continuation} · Retest confirmed. Waiting for a fresh matching M15 MSS."
                !chochReady -> "CONTINUATION ${continuation} · MSS confirmed after retest. Waiting for matching M15 CHOCH."
                else -> "CONTINUATION ${continuation} · Retest and M15 MSS + CHOCH confirmed. Ready for execution."
            }
        }

        val levels = dailyLevels[symbol]
        val price = latestPrices[symbol] ?: 0.0
        if (levels != null && price > levels.first) {
            return "PRICE ABOVE PDH · Waiting for a completed M15 close above PDH for bullish continuation, or a close back below PDH for bearish reversal."
        }
        if (levels != null && price < levels.second) {
            return "PRICE BELOW PDL · Waiting for a completed M15 close below PDL for bearish continuation, or a close back above PDL for bullish reversal."
        }
        return "WAITING FOR PDH/PDL EVENT · No confirmed breakout or reversal yet."
    }

    private fun reconcileRestoredOriginalPairs(symbolHint: String? = null) {
        val now = System.currentTimeMillis() / 1000L
        // If a restored pair was identified before historical candles arrived,
        // retry reconstructing its entry direction now that candle history exists.
        positions.values.filter { p ->
            (symbolHint == null || p.symbol == symbolHint) &&
                p.role == "ORIGINAL" && p.originalId.startsWith("RESTORED|") &&
                p.originalSignalDirection.isBlank() && p.openedAtEpoch > 0L
        }.forEach { leg ->
            originalSignalAt(leg.symbol, leg.openedAtEpoch)?.let { direction ->
                positions.values.filter { it.role == "ORIGINAL" && it.originalId == leg.originalId }
                    .forEach { it.originalSignalDirection = direction }
            }
        }
        val candidates = positions.values.filter { p ->
            (symbolHint == null || p.symbol == symbolHint) &&
                p.role == "EXISTING" && p.status == "OPEN" &&
                p.contractId > 0L && p.expiryEpoch > now
        }
        candidates.groupBy { it.symbol }.forEach { (symbol, indexPositions) ->
            val unmatched = indexPositions.toMutableList()
            while (unmatched.isNotEmpty()) {
                val first = unmatched.removeAt(0)
                val partnerIndex = unmatched.indexOfFirst { other ->
                    other.direction != first.direction &&
                        other.expiryEpoch == first.expiryEpoch &&
                        kotlin.math.abs(other.openedAtEpoch - first.openedAtEpoch) <= 120L
                }
                if (partnerIndex < 0) continue
                val partner = unmatched.removeAt(partnerIndex)
                val pairOpened = listOf(first.openedAtEpoch, partner.openedAtEpoch)
                    .filter { it > 0L }.minOrNull()
                    ?: (first.expiryEpoch - 5L * 24L * 60L * 60L).coerceAtLeast(0L)
                val pairId = "RESTORED|$symbol|${first.expiryEpoch}|$pairOpened"
                val historicalDirection = originalSignalAt(symbol, pairOpened)
                listOf(first, partner).forEach { leg ->
                    leg.role = "ORIGINAL"
                    leg.originalId = pairId
                    leg.openedAtEpoch = pairOpened
                    leg.originalSignalDirection = historicalDirection.orEmpty()
                }
                addNotification(
                    "Trade",
                    "${displayIndexName(symbol)} original pair restored",
                    if (historicalDirection != null)
                        "Active RISE/FALL contracts were matched as one original pair. Original signal: $historicalDirection. Pair lifecycle monitoring is active."
                    else
                        "Active RISE/FALL contracts were matched as one original pair. Reconstructing the original signal direction from historical candles."
                )
            }
        }
    }

    /** Reconstruct the original matching MSS/CHOCH direction at pair entry. */
    private fun originalSignalAt(symbol: String, openedAt: Long): String? {
        val history = candlesByIndex[symbol].orEmpty()
            .filter { it.epoch < openedAt }
            .sortedBy { it.epoch }
        if (history.size < 6) return null
        val last = history.last()
        val prior = history.dropLast(1)
        val highs = mutableListOf<Double>()
        val lows = mutableListOf<Double>()
        for (i in 1 until prior.lastIndex) {
            if (prior[i].high > prior[i - 1].high && prior[i].high > prior[i + 1].high) highs.add(prior[i].high)
            if (prior[i].low < prior[i - 1].low && prior[i].low < prior[i + 1].low) lows.add(prior[i].low)
        }
        val high = highs.lastOrNull() ?: return null
        val low = lows.lastOrNull() ?: return null
        val mss = when { last.close > high -> "RISE"; last.close < low -> "FALL"; else -> return null }
        val choch = when {
            last.close > high && last.close > last.open -> "RISE"
            last.close < low && last.close < last.open -> "FALL"
            else -> return null
        }
        return mss.takeIf { it == choch }
    }

    private fun recoveryStatusText(symbol: String): String {
        val running = positions.values.filter { it.symbol == symbol && it.role == "ORIGINAL" &&
            it.status !in setOf("CLOSED", "ERROR", "BUY FAILED", "PROPOSAL FAILED") }
        val openPair = running.groupBy { it.originalId }.values.firstOrNull { pair ->
            pair.size == 2 && pair.any { it.status == "OPEN" || it.status == "CLOSING" }
        }
        if (openPair != null) return "ORIGINAL RISE + FALL PAIR RUNNING · Waiting for the original contracts to close."
        if (positions.values.any { it.symbol == symbol && it.status == "OPEN" && it.role == "EXISTING" }) {
            return "Existing Deriv contract detected. Its original contract terms remain unchanged."
        }
        val mss = recoveryMss[symbol]
        val choch = recoveryChoch[symbol]
        val mssEpoch = recoveryMssEpoch[symbol] ?: 0L
        val chochEpoch = recoveryChochEpoch[symbol] ?: 0L
        val rev = reversalQualifiedDirection[symbol]
        val cont = continuationDirection[symbol]
        val retest = continuationRetestEpoch[symbol] ?: 0L
        val event = when { rev != null -> reversalEventEpoch[symbol] ?: 0L; cont != null && retest > 0L -> retest; else -> 0L }
        val signalsReady = event > 0L && mss == choch && mss != null &&
            mssEpoch + 900L > event && chochEpoch + 900L > event
        return when {
            rev != null && signalsReady -> "QUALIFIED — REVERSAL · $rev"
            rev != null && mss == rev -> "REVERSAL $rev · WAITING FOR CHOCH"
            rev != null -> "REVERSAL $rev · WAITING FOR MSS"
            cont != null && retest == 0L -> "CONTINUATION $cont · WAITING FOR RETEST"
            cont != null && signalsReady -> "QUALIFIED — CONTINUATION · $cont"
            cont != null && mss == cont -> "CONTINUATION $cont · WAITING FOR CHOCH"
            cont != null -> "CONTINUATION $cont · WAITING FOR MSS"
            reversalTouched[symbol] == "PDH" -> "REVERSAL · PDH TOUCHED · WAITING FOR CLOSE BELOW PDH"
            reversalTouched[symbol] == "PDL" -> "REVERSAL · PDL TOUCHED · WAITING FOR CLOSE ABOVE PDL"
            else -> "WAITING FOR BREAKOUT OR REVERSAL AT PDH/PDL"
        }
    }

    private fun updateMasterDecisionViews(symbol: String) {
        val levels = dailyLevels[symbol]
        highLevelViews[symbol]?.text = "PREVIOUS DAY HIGH: ${levels?.first?.let { formatPrice(it) } ?: "--"}"
        lowLevelViews[symbol]?.text = "PREVIOUS DAY LOW: ${levels?.second?.let { formatPrice(it) } ?: "--"}"
        val running = runningDirectionText(symbol)
        masterDecisionViews[symbol]?.apply {
            text = running ?: "WAITING"
            setTextColor(if (running != null) green else amber)
        }
        marketStatusViews[symbol]?.text = masterDecisionText(symbol)
    }

    private fun signalQualificationText(signal: String?): String {
        return if (signal.isNullOrBlank()) "NOT QUALIFIED" else "QUALIFIED · $signal"
    }

    private fun updateRecoveryStatusView(symbol: String) {
        val status = recoveryStatusText(symbol)
        recoveryStatusViews[symbol]?.text = status
        tradeRecoveryStatusViews[symbol]?.text = status
        mssStatusViews[symbol]?.apply {
            text = signalQualificationText(recoveryMss[symbol])
            setTextColor(if (recoveryMss[symbol].isNullOrBlank()) amber else green)
        }
        chochStatusViews[symbol]?.apply {
            text = signalQualificationText(recoveryChoch[symbol])
            setTextColor(if (recoveryChoch[symbol].isNullOrBlank()) amber else green)
        }
    }

    private fun formatAuditDateTime(epochSeconds: Long): String {
        if (epochSeconds <= 0L) return "NOT RECORDED"
        val formatter = java.text.SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.US)
        formatter.timeZone = TimeZone.getDefault()
        return formatter.format(java.util.Date(epochSeconds * 1000L))
    }

    private fun entrySignalAuditText(position: Position): String {
        val hasSnapshot = position.entryMssCloseEpoch > 0L && position.entryChochCloseEpoch > 0L
        if (!hasSnapshot) {
            return "ENTRY SIGNAL AUDIT\nMSS: DATE NOT RECORDED\nCHOCH: DATE NOT RECORDED\nLAST CANDLE CLOSE: DATE NOT RECORDED\nEXECUTION: ${formatAuditDateTime(position.executionEpoch)}"
        }
        return "ENTRY SIGNAL AUDIT\n" +
            "CHOCH (${position.entryChochDirection}): ${formatAuditDateTime(position.entryChochCloseEpoch)} · LEVEL ${formatPrice(position.entryChochPrice)}\n" +
            "MSS (${position.entryMssDirection}): ${formatAuditDateTime(position.entryMssCloseEpoch)} · LEVEL ${formatPrice(position.entryMssPrice)}\n" +
            "LAST CANDLE CLOSE: ${formatAuditDateTime(position.entryLastCandleCloseEpoch)} · PRICE ${formatPrice(position.entryLastCandleClosePrice)}\n" +
            "EXECUTION: ${formatAuditDateTime(position.executionEpoch)}"
    }

    private fun formatMoney(value: Double): String =
        String.format(Locale.US, "%.2f %s", value, accountCurrency)

    private fun nextRequestId(): Int = ++requestCounter

    private fun utcDayKey(epochSeconds: Long): String {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US)
        c.timeInMillis = epochSeconds * 1000L
        return String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    private fun calculatePreviousDailyLevels(symbol: String, candles: List<DerivApiClient.Candle>) {
        val grouped = candles.groupBy { it.epoch / 86400L }.toSortedMap()
        if (grouped.size < 2) return
        val currentDay = System.currentTimeMillis() / 1000L / 86400L
        val completed = grouped.filterKeys { it < currentDay }.entries.lastOrNull() ?: return
        val high = completed.value.maxOf { it.high }
        val low = completed.value.minOf { it.low }
        if (high > low && high.isFinite() && low.isFinite()) {
            val previous = dailyLevels[symbol]
            dailyLevels[symbol] = high to low
            if (previous == null || previous.first != high || previous.second != low) {
                // Keep an in-progress setup across the 00:00 South African rollover.
                // Its captured reference price remains in the setup maps; only a
                // genuinely new level event can replace it.
                addNotification(
                    "Home",
                    "${displayIndexName(symbol)} reference levels updated",
                    "Previous completed-day high: ${formatPrice(high)}; low: ${formatPrice(low)}."
                )
            }
        }
    }

    /** Evaluate PDH/PDL reversal and continuation independently, using completed M15 candles only. */
    private fun updatePreviousDayLevelBreakQualification(
        symbol: String,
        candles: List<DerivApiClient.Candle>
    ) {
        val levels = dailyLevels[symbol] ?: return
        val completed = candles.sortedBy { it.epoch }.dropLast(1).lastOrNull() ?: return
        val high = levels.first
        val low = levels.second
        if (!high.isFinite() || !low.isFinite() || high <= low) return
        val closeEpoch = completed.epoch + 900L

        // REVERSAL: a touch/penetration is remembered; only a later completed
        // M15 close back across that same level qualifies the reversal event.
        val touched = reversalTouched[symbol]
        if (touched == null) {
            when {
                completed.high >= high && completed.close < high -> {
                    reversalQualifiedDirection[symbol] = "FALL"
                    reversalEventEpoch[symbol] = closeEpoch
                    reversalEventPrice[symbol] = completed.close
                    reversalReferenceLevel[symbol] = low
                    reversalReferenceLevel[symbol] = high
                }
                completed.low <= low && completed.close > low -> {
                    reversalQualifiedDirection[symbol] = "RISE"
                    reversalEventEpoch[symbol] = closeEpoch
                    reversalEventPrice[symbol] = completed.close
                }
                completed.high >= high -> reversalTouched[symbol] = "PDH"
                completed.low <= low -> reversalTouched[symbol] = "PDL"
            }
        } else if (touched == "PDH" && completed.close < high) {
            reversalQualifiedDirection[symbol] = "FALL"
            reversalEventEpoch[symbol] = closeEpoch
            reversalEventPrice[symbol] = completed.close
            reversalReferenceLevel[symbol] = high
            reversalTouched.remove(symbol)
        } else if (touched == "PDL" && completed.close > low) {
            reversalQualifiedDirection[symbol] = "RISE"
            reversalEventEpoch[symbol] = closeEpoch
            reversalEventPrice[symbol] = completed.close
            reversalReferenceLevel[symbol] = low
            reversalTouched.remove(symbol)
        }

        // CONTINUATION: close beyond PDH/PDL, then a later candle must
        // interact with the broken level. No extension-distance filter applies.
        val activeDirection = continuationDirection[symbol]
        if (activeDirection == null) {
            when {
                completed.close > high -> {
                    continuationDirection[symbol] = "RISE"
                    continuationLevel[symbol] = high
                    continuationBreakEpoch[symbol] = closeEpoch
                    continuationRetestEpoch.remove(symbol)
                }
                completed.close < low -> {
                    continuationDirection[symbol] = "FALL"
                    continuationLevel[symbol] = low
                    continuationBreakEpoch[symbol] = closeEpoch
                    continuationRetestEpoch.remove(symbol)
                }
            }
        } else if (continuationRetestEpoch[symbol] == null && closeEpoch > (continuationBreakEpoch[symbol] ?: Long.MAX_VALUE)) {
            val level = continuationLevel[symbol] ?: Double.NaN
            val retested = level.isFinite() && completed.low <= level && completed.high >= level
            val held = if (activeDirection == "RISE") completed.close >= level else completed.close <= level
            val invalidated = if (activeDirection == "RISE") completed.close < level else completed.close > level
            if (retested && held) {
                continuationRetestEpoch[symbol] = closeEpoch
            } else if (invalidated) {
                continuationDirection.remove(symbol)
                continuationLevel.remove(symbol)
                continuationBreakEpoch.remove(symbol)
                continuationRetestEpoch.remove(symbol)
            }
        }

        // Expire stale setups on a new daily reference-level set naturally via
        // the level refresh path; keep both engines independent per index.
    }

    // Original paired contracts retain their shared lifecycle identifier.
    private fun tradeGroupId(position: Position): String =
        position.originalId.ifBlank { position.localId }

    private fun isActiveTradePosition(position: Position): Boolean {
        return position.status !in setOf("CLOSED", "ERROR", "BUY FAILED", "PROPOSAL FAILED") &&
            !position.status.startsWith("ERROR")
    }

    private fun activeTradeGroupIds(): Set<String> {
        return positions.values
            .filter { isActiveTradePosition(it) }
            .map { tradeGroupId(it) }
            .toSet()
    }

    // H1 ATR(14), calculated from the completed H1 candles supplied by the
    // historical-candle stream. Used only to validate a setup carried over rollover.
    private fun completedH1Atr(symbol: String): Double? {
        val candles = candlesByIndex[symbol]?.sortedBy { it.epoch }?.dropLast(1) ?: return null
        if (candles.size < 15) return null
        val recent = candles.takeLast(14)
        val ranges = recent.mapIndexed { i, candle ->
            val previousClose = candles[candles.size - 14 + i - 1].close
            maxOf(candle.high - candle.low, kotlin.math.abs(candle.high - previousClose), kotlin.math.abs(candle.low - previousClose))
        }
        val atr = ranges.average()
        return atr.takeIf { it.isFinite() && it > 0.0 }
    }
    
private fun evaluateOriginalEntries(symbol: String, quote: Double) {
    if (!autoTradingEnabled || !accountConnected ||
        dailyCapital <= 0.0 || !quote.isFinite()
    ) return

    val today = utcDayKey(System.currentTimeMillis() / 1000L)
    val tradeKey = "$today|$symbol|ORIGINAL"
    if (tradeKey in originalTriggered) return

    // Only qualification rule: MSS and CHOCH must agree.
    val mss = recoveryMss[symbol]
    val choch = recoveryChoch[symbol]
    if (mss.isNullOrBlank() || mss != choch) return

    // Preserve one original running trade per index.
    val indexAlreadyActive = positions.values.any {
        it.symbol == symbol && it.status != "CLOSED" &&
            !it.status.startsWith("ERROR") &&
            it.status !in setOf("BUY FAILED", "PROPOSAL FAILED")
    }
    if (indexAlreadyActive) return

    val stake = roundStake(dailyCapital * 0.20)
    if (stake <= 0.0 || availableBalance + 0.000001 < stake) {
        if (insufficientFundsNotified.add(tradeKey)) {
            addNotification(
                "Trade",
                "${displayIndexName(symbol)} trade not executed",
                "Available balance ${formatMoney(availableBalance)} is below the single-trade stake ${formatMoney(stake)} (20% of daily starting capital)."
            )
        }
        return
    }

    val originalId = "ORIGINAL-${today}-${symbol}-${UUID.randomUUID()}"
    val mssEpoch = recoveryMssEpoch[symbol] ?: 0L
    val chochEpoch = recoveryChochEpoch[symbol] ?: 0L

    val mssCloseEpoch = if (mssEpoch > 0L) mssEpoch + 900L else 0L
    val chochCloseEpoch = if (chochEpoch > 0L) chochEpoch + 900L else 0L

    val submitted = openPosition(
        symbol, mss, "ORIGINAL", stake, originalId,
        entryMssDirection = mss,
        entryMssCloseEpoch = mssCloseEpoch,
        entryChochDirection = choch,
        entryChochCloseEpoch = chochCloseEpoch,
        entryMssPrice = recoveryMssPrice[symbol] ?: 0.0,
        entryChochPrice = recoveryChochPrice[symbol] ?: 0.0,
        entryLastCandleClosePrice = recoverySignalClosePrice[symbol] ?: quote,
        entryLastCandleCloseEpoch = maxOf(mssCloseEpoch, chochCloseEpoch),
        engineType = "MSS_CHOCH"
    )

    if (!submitted) {
        addNotification(
            "Trade",
            "${displayIndexName(symbol)} trade not submitted",
            "The ${mss} proposal request could not be sent. The signal remains available for retry."
        )
        return
    }

    originalTriggered.add(tradeKey)

    tradePrefs.edit()
        .putStringSet("triggered_$today", originalTriggered.toSet())
        .commit()

    addNotification(
        "Trade",
        "${displayIndexName(symbol)} ${mss} trade submitted",
        "MSS and CHOCH agree in the ${mss} direction. One contract proposal was sent to Deriv. Stake: ${formatMoney(stake)} (20% of fixed daily starting capital). Waiting for Deriv acceptance."
    )
}


    private fun isSetupCarriedBeyondAtr(symbol: String, eventEpoch: Long, reference: Double, quote: Double): Boolean {
        val eventDate = java.text.SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Africa/Johannesburg")
        }.format(java.util.Date(eventEpoch * 1000L))
        val todayDate = java.text.SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Africa/Johannesburg")
        }.format(java.util.Date())
        if (eventDate == todayDate) return false
        val atr = completedH1Atr(symbol) ?: return true
        return kotlin.math.abs(quote - reference) > atr
    }

    
    private fun roundStake(value: Double): Double =
        BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toDouble()

    private fun openPosition(
        symbol: String, direction: String, role: String, stake: Double,
        originalId: String = "",
        entryMssDirection: String = "", entryMssCloseEpoch: Long = 0L,
        entryChochDirection: String = "", entryChochCloseEpoch: Long = 0L,
        entryMssPrice: Double = 0.0, entryChochPrice: Double = 0.0,
        entryLastCandleClosePrice: Double = 0.0,
        entryLastCandleCloseEpoch: Long = 0L,
        engineType: String = ""
    ): Boolean {
                if (platformAuthenticated && platformUserRole == "investor") {
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())
            val subscriptionActive = platformUser.optString("subscription_status") == "approved" &&
                platformUser.optString("subscription_expiry_date") >= today
            val selectedPlan = platformUser.optString("investor_plan")
            val planActive = platformUser.optString("investor_plan_status") == "approved" &&
                (selectedPlan == "U" || platformUser.optString("investor_plan_expiry_date") >= today)
            if (!subscriptionActive || !planActive) {
                addNotification("Account", "Investor execution blocked", "An active approved subscription and an approved Investor plan are required before trading.")
                runOnUiThread { toast("Trading requires an approved active subscription and Investor plan.") }
                return false
            }
                }
        val roundedStake = roundStake(stake)
        if (!accountConnected || !roundedStake.isFinite() || roundedStake <= 0.0) return false
        val localId = UUID.randomUUID().toString()
        val p = Position(localId, symbol, direction, role, roundedStake, originalId, engineType)
        p.entryMssDirection = entryMssDirection
        p.entryMssCloseEpoch = entryMssCloseEpoch
        p.entryChochDirection = entryChochDirection
        p.entryChochCloseEpoch = entryChochCloseEpoch
        p.entryMssPrice = entryMssPrice
        p.entryChochPrice = entryChochPrice
        p.entryLastCandleClosePrice = entryLastCandleClosePrice
        p.entryLastCandleCloseEpoch = entryLastCandleCloseEpoch
        // Atomic per-index execution gate. The reservation is inserted before
        // any proposal is sent, so concurrent signal callbacks cannot both pass.
        // Every role and direction shares this same index slot.
        val indexReserved = synchronized(positions) {
            val alreadyOccupied = positions.values.any {
                it.symbol == symbol &&
                    it.status != "CLOSED" &&
                    !it.status.startsWith("ERROR") &&
                    it.status !in setOf("BUY FAILED", "PROPOSAL FAILED")
            }
            if (alreadyOccupied) {
                false
            } else {
                p.status = "REQUESTING PROPOSAL"
                positions[localId] = p
                true
            }
        }
        if (!indexReserved) {
            addNotification(
                "Trade",
                "${displayIndexName(symbol)} execution blocked",
                "This index already has a pending or running contract. Only one trade per index is permitted; no second proposal was sent."
            )
            return false
        }

        val req = nextRequestId()
        pendingRequests[req] = localId
        val contractType = if (direction == "RISE") "CALL" else "PUT"
        val proposal = JSONObject()
            .put("proposal", 1)
            .put("amount", roundStake(roundedStake))
            .put("basis", "stake")
            .put("contract_type", contractType)
            .put("currency", accountCurrency)
            .put("duration", 5)
            .put("duration_unit", "d")
            .put("underlying_symbol", symbol)
            .put("req_id", req)
        val client = derivService?.client
        if (client == null) {
            pendingRequests.remove(req)
            positions.remove(localId)
            addNotification("Trade", "${displayIndexName(symbol)} execution failed", "Deriv trading connection is unavailable. The original pair signal has not been consumed.")
            return false
        }
        client.send(proposal)
        savePositions()
        runOnUiThread { if (selectedTab == "Trade") render() }
        return true
    }
    private fun subscribePosition(contractId: Long) {
        val req = JSONObject().put("proposal_open_contract", 1).put("contract_id", contractId).put("subscribe", 1)
        derivService?.client?.send(req)
    }

    private fun handleOpenContract(contract: JSONObject) {
        val contractId = contract.optLong("contract_id", 0L)
        if (contractId <= 0L) return

        // Deriv can stream an open contract before the portfolio response has
        // populated the local list. Restore that contract instead of discarding it.
        var foundPosition = positions.values.firstOrNull { it.contractId == contractId }
        var wasRestored = false

        if (foundPosition == null) {
            val symbol = contract.optString(
                "underlying_symbol",
                contract.optString("symbol", "")
            )
            val direction = when (
                contract.optString("contract_type", "").uppercase(Locale.US)
            ) {
                "CALL" -> "RISE"
                "PUT" -> "FALL"
                else -> return
            }

            if (symbol !in indexes) return

            val stake = contract.optDouble("buy_price", 0.0)
            val restored = Position(
                localId = "DERIV-$contractId",
                symbol = symbol,
                direction = direction,
                role = "EXISTING",
                stake = stake
            )
            restored.contractId = contractId
            restored.expiryEpoch = contract.optLong("date_expiry", 0L)
            // Deriv open-contract updates may contain the original purchase time.
            // Store it in both fields used by the trade audit and persistence ledger.
            val actualPurchaseTime = contract.optLong("purchase_time", 0L)
                .takeIf { it > 0L }
                ?: contract.optLong("date_start", 0L).takeIf { it > 0L }
                ?: 0L
            restored.openedAtEpoch = actualPurchaseTime
            restored.executionEpoch = actualPurchaseTime
            restored.status = "OPEN"
            positions[restored.localId] = restored
            foundPosition = restored
            wasRestored = true
        }

        val position = foundPosition ?: return
        val wasAlreadyClosed = position.status == "CLOSED"
        // Ignore delayed open-contract snapshots after a confirmed sell receipt.
        // They must never move a locally confirmed CLOSED trade back to OPEN.
        if (wasAlreadyClosed) return
        position.expiryEpoch = contract.optLong("date_expiry", position.expiryEpoch)
        // Backfill missing opening/execution time from Deriv's real contract fields,
        // while preserving any previously saved local timestamp and signal audit.
        val actualPurchaseTime = contract.optLong("purchase_time", 0L)
            .takeIf { it > 0L }
            ?: contract.optLong("date_start", 0L).takeIf { it > 0L }
            ?: 0L
        if (actualPurchaseTime > 0L) {
            if (position.openedAtEpoch <= 0L) position.openedAtEpoch = actualPurchaseTime
            if (position.executionEpoch <= 0L) position.executionEpoch = actualPurchaseTime
        }
        // Use this exact Deriv contract's confirmed purchase price as its P/L denominator.
        val confirmedContractStake = contract.optDouble("buy_price", position.stake)
        if (confirmedContractStake.isFinite() && confirmedContractStake > 0.0) {
            position.stake = confirmedContractStake
        }

        when (contract.optString("contract_type").uppercase(Locale.US)) {
            "CALL" -> position.direction = "RISE"
            "PUT" -> position.direction = "FALL"
            "EXPIRYRANGE" -> position.direction = "BETWEEN"
        }

        position.profit = contract.optDouble("profit", position.profit)

        val validToSell = contract.opt("is_valid_to_sell")
        position.canSell = when (validToSell) {
            is Boolean -> validToSell
            is Number -> validToSell.toInt() == 1
            is String -> validToSell.equals("true", true) || validToSell == "1"
            else -> false
        }

        val soldValue = contract.opt("is_sold")
        val expiredValue = contract.opt("is_expired")
        val isSold = listOf(soldValue, expiredValue).any { value ->
            when (value) {
                is Boolean -> value
                is Number -> value.toInt() == 1
                is String -> value.equals("true", true) || value == "1"
                else -> false
            }
        }

        if (isSold) {
            position.status = "CLOSED"
            position.canSell = false
            position.trailingClosePending = false
            position.trailingCloseRejected = false
            position.closedAt = contract.optLong("sell_time", 0L).takeIf { it > 0L }
                ?: contract.optLong("date_expiry", 0L).takeIf { it > 0L }
                ?: System.currentTimeMillis() / 1000L
            if (!wasAlreadyClosed) {
                addNotification("Trade", "${displayIndexName(position.symbol)} trade closed", "${position.role} ${position.direction} final P/L: ${formatMoney(position.profit)}.")
            }
        } else if (position.status != "CLOSING") {
            position.status = "OPEN"
        }

        // Evaluate only this contract, after its latest sellability and sold state
        // have been read from the same Deriv update.
        if (position.status == "OPEN" && !isSold) {
            evaluateContractTrailingStop(position)
        }

        savePositions()
        runOnUiThread {
            updateMasterDecisionViews(position.symbol)
            updateRecoveryStatusView(position.symbol)
            val relatedPositions = positions.values.filter {
                it.symbol == position.symbol && it.status != "CLOSED" &&
                    (if (position.originalId.isNotBlank()) it.originalId == position.originalId
                     else position.role == "EXISTING" && it.role == "EXISTING")
            }
            relatedPositions.forEach { updateTradePositionViews(it) }
            if (selectedTab == "Trade" && (wasRestored || (!wasAlreadyClosed && position.status == "CLOSED"))) {
                render()
            }
            updateAccountProfitLossViews()
        }

    }

    /**
     * Contract-specific stepped protection:
     * +10% peak protects 0%; +20% protects +10%; +30% protects +20%, etc.
     * Every percentage is measured against this contract's own stake.
     */
    private fun trailingProtectionPercent(peakPercent: Double): Double {
        if (!peakPercent.isFinite() || peakPercent < 10.0) return 0.0
        val completedSteps = kotlin.math.floor((peakPercent - 10.0) / 10.0)
            .toInt().coerceAtLeast(0)
        return completedSteps * 10.0
    }

    /** Display values are calculated from this individual contract's own stake. */
    private fun trailingStopDisplayText(position: Position): String {
        val currentPercent = if (position.stake > 0.0 && position.stake.isFinite())
            (position.profit / position.stake) * 100.0 else 0.0
        val currentText = String.format(Locale.US, "%+.2f%%", currentPercent)
        if (!position.trailingActivated) {
            return "TRAILING STOP: WAITING · ACTIVATES AT +10.00% · CURRENT P/L: $currentText"
        }
        val peakText = String.format(Locale.US, "%+.2f%%", position.trailingPeakPercent)
        val protectedPercent = trailingProtectionPercent(position.trailingPeakPercent)
        val protectedText = String.format(Locale.US, "+%.2f%%", protectedPercent)
        return "TRAILING STOP: ${if (position.trailingClosePending) "CLOSING" else "ACTIVE"} · CURRENT: $currentText · PEAK: $peakText · PROTECTED: $protectedText"
    }

    /**
     * Contract-specific trailing stop. This function is called only with the
     * Position matched by the incoming Deriv contract_id, so another index or
     * another contract cannot change this contract's trailing state.
     * P/L percentage = live Deriv P/L / this contract's confirmed buy_price.
     * Activate at +10%, initially protect 0%, then advance protection by
     * +10 percentage points at each further +10 peak milestone.
     */
    private fun evaluateContractTrailingStop(position: Position) {
        if (position.status != "OPEN" || position.contractId <= 0L ||
            position.stake <= 0.0 || !position.stake.isFinite() ||
            position.trailingClosePending) return

        // Always calculate against this exact contract's current confirmed stake.
        val currentPercent = (position.profit / position.stake) * 100.0
        if (!currentPercent.isFinite()) return

        if (!position.trailingActivated) {
            // Negative and sub-threshold values are monitored but never trigger a sale.
            if (currentPercent < 10.0) return
            position.trailingActivated = true
            position.trailingPeakPercent = currentPercent
            position.trailingStopPercent = trailingProtectionPercent(currentPercent)
            addNotification(
                "Trade",
                "${displayIndexName(position.symbol)} trailing stop activated",
                "${position.role} ${position.direction} contract reached +10.00% of its own current stake. Initial protected-profit level: 0.00% (breakeven at the original stake)."
            )
        }

        // Preserve the highest P/L reached by this contract only.
        if (currentPercent > position.trailingPeakPercent) {
            position.trailingPeakPercent = currentPercent
        }

        // Stepped protection agreed for this strategy:
        // +10 peak protects 0; +20 protects +10; +30 protects +20, etc.
        position.trailingStopPercent = trailingProtectionPercent(position.trailingPeakPercent)

        // A rejected sale remains blocked while P/L is at/below the protected
        // threshold. Re-arm only after a genuine recovery above that threshold;
        // the next downward crossing may then make one fresh sale attempt.
        if (position.trailingCloseRejected) {
            if (currentPercent <= position.trailingStopPercent) {
                savePositions()
                return
            }
            position.trailingCloseRejected = false
        }
        savePositions()

        // A reversal to the protected threshold triggers a close of this contract_id only.
        if (currentPercent <= position.trailingStopPercent &&
            currentPercent < position.trailingPeakPercent) {
            if (!position.canSell) return

            position.trailingClosePending = true
            position.status = "CLOSING"
            savePositions()
            val req = nextRequestId()
            pendingRequests[req] = position.localId
            // Deriv "sell.price" is the MINIMUM acceptable sale price.
            // Never send 0 here: 0 explicitly requests an unrestricted market sale
            // and can realize a loss even after the trailing threshold was reached.
            val minimumSalePrice = position.stake *
                (1.0 + position.trailingStopPercent / 100.0)
            if (!minimumSalePrice.isFinite() || minimumSalePrice < position.stake) {
                pendingRequests.remove(req)
                position.trailingClosePending = false
                position.status = "OPEN"
                savePositions()
                return
            }
            position.trailingCloseFloorPrice = minimumSalePrice
            savePositions()
            val request = JSONObject()
                .put("sell", position.contractId)
                .put("price", minimumSalePrice)
                .put("req_id", req)
            val sent = derivService?.client?.send(request) == true
            if (!sent) {
                pendingRequests.remove(req)
                position.trailingClosePending = false
                position.status = "OPEN"
                savePositions()
                return
            }
            addNotification(
                "Trade",
                "${displayIndexName(position.symbol)} trailing stop triggered",
                "${position.role} ${position.direction} contract reversed to its protected-profit threshold of +${String.format(Locale.US, "%.2f", position.trailingStopPercent)}% of its own current stake (${formatMoney(position.stake * position.trailingStopPercent / 100.0)}). A minimum Deriv sale price of ${formatMoney(minimumSalePrice)} has been requested for this contract only. If the market cannot meet that minimum, Deriv must reject the sale rather than sell below the protection floor; waiting for the response."
            )
            runOnUiThread { if (selectedTab == "Trade") render() }
        }
    }

    private fun closeRunningPosition(position: Position) {
        if (!accountConnected) {
            toast("Connect the Deriv trading account before closing this contract.")
            return
        }
        if (position.contractId <= 0L || !position.canSell || position.status != "OPEN") {
            toast("Deriv has not made this contract available for sale yet. Please wait for the next contract update.")
            return
        }
        position.status = "CLOSING"
        savePositions()
        val req = nextRequestId()
        pendingRequests[req] = position.localId
        val request = JSONObject().put("sell", position.contractId).put("price", 0).put("req_id", req)
        val sent = derivService?.client?.send(request) == true
        if (!sent) {
            pendingRequests.remove(req)
            position.status = "OPEN"
            savePositions()
            toast("Could not send the close request. The contract remains open.")
        } else {
            toast("Close request sent to Deriv.")
        }
        if (selectedTab == "Trade") render()
    }

    private fun accountProfitLoss(): Triple<Double, Double, Double> {
        val open = positions.values.filter { it.status != "CLOSED" && it.contractId > 0L }.sumOf { it.profit }
        val closed = positions.values.filter { it.status == "CLOSED" }.sumOf { it.profit }
        return Triple(open, closed, open + closed)
    }


    private fun updateAccountProfitLossViews() {
        val pl = accountProfitLoss()
        accountOpenPlView?.apply {
            text = "OPEN TRADE P/L: ${formatMoney(pl.first)}"
            setTextColor(if (pl.first >= 0.0) green else red)
        }
        accountClosedPlView?.apply {
            text = "CLOSED TRADE P/L: ${formatMoney(pl.second)}"
            setTextColor(if (pl.second >= 0.0) green else red)
        }
        accountTotalPlView?.apply {
            text = "TOTAL TRADE P/L: ${formatMoney(pl.third)}"
            setTextColor(if (pl.third >= 0.0) green else red)
        }
    }

    private fun evaluateRecoverySignals(symbol: String, candles: List<DerivApiClient.Candle>) {
        if (candles.size < 7) return
        val closed = candles.sortedBy { it.epoch }.dropLast(1)
        if (closed.size < 6) return
        val last = closed.last()
        val lastProcessed = tradePrefs.getLong("last_structural_candle_$symbol", 0L)
        if (last.epoch <= lastProcessed) return

        // Confirm pivots only when a candle on both sides exists. The two
        // structural events are deliberately distinct: CHOCH is the first
        // break against the established swing sequence; MSS is a later break
        // in the newly established direction.
        val prior = closed.dropLast(1)
        val pivotsHigh = mutableListOf<Pair<Int, Double>>()
        val pivotsLow = mutableListOf<Pair<Int, Double>>()
        for (i in 1 until prior.lastIndex) {
            if (prior[i].high > prior[i - 1].high && prior[i].high > prior[i + 1].high) pivotsHigh.add(i to prior[i].high)
            if (prior[i].low < prior[i - 1].low && prior[i].low < prior[i + 1].low) pivotsLow.add(i to prior[i].low)
        }
        val swingHigh = pivotsHigh.lastOrNull()?.second
        val swingLow = pivotsLow.lastOrNull()?.second
        if (swingHigh == null || swingLow == null) {
            tradePrefs.edit().putLong("last_structural_candle_$symbol", last.epoch).apply()
            return
        }
        val risingStructure = pivotsHigh.size >= 2 && pivotsLow.size >= 2 &&
            pivotsHigh.last().second > pivotsHigh[pivotsHigh.lastIndex - 1].second &&
            pivotsLow.last().second > pivotsLow[pivotsLow.lastIndex - 1].second
        val fallingStructure = pivotsHigh.size >= 2 && pivotsLow.size >= 2 &&
            pivotsHigh.last().second < pivotsHigh[pivotsHigh.lastIndex - 1].second &&
            pivotsLow.last().second < pivotsLow[pivotsLow.lastIndex - 1].second

        val breakUp = last.close > swingHigh
        val breakDown = last.close < swingLow
        val chochDirection = when {
            breakUp && fallingStructure -> "RISE"
            breakDown && risingStructure -> "FALL"
            else -> ""
        }
        val mssDirection = when {
            breakUp && !fallingStructure -> "RISE"
            breakDown && !risingStructure -> "FALL"
            else -> ""
        }

        if (chochDirection.isNotBlank()) {
            recoveryChoch[symbol] = chochDirection
            recoveryChochPrice[symbol] = if (chochDirection == "RISE") swingHigh else swingLow
            recoveryChochEpoch[symbol] = last.epoch
            tradePrefs.edit().putString("signal_choch_m15_$symbol", chochDirection)
                .putLong("signal_choch_m15_epoch_$symbol", last.epoch).apply()
            addNotification("Home", "${displayIndexName(symbol)} CHOCH qualified", "A completed M15 candle changed the prior swing direction to ${chochDirection.lowercase(Locale.US)}.")
        } else if (mssDirection.isNotBlank()) {
            recoveryMss[symbol] = mssDirection
            recoveryMssPrice[symbol] = if (mssDirection == "RISE") swingHigh else swingLow
            recoveryMssEpoch[symbol] = last.epoch
            tradePrefs.edit().putString("signal_mss_m15_$symbol", mssDirection)
                .putLong("signal_mss_m15_epoch_$symbol", last.epoch).apply()
            addNotification("Home", "${displayIndexName(symbol)} MSS qualified", "A completed M15 candle confirmed a market structure break in the ${mssDirection.lowercase(Locale.US)} direction.")
        }
        tradePrefs.edit().putLong("last_structural_candle_$symbol", last.epoch).apply()
        runOnUiThread {
            updateRecoveryStatusView(symbol)
            mssStatusViews[symbol]?.apply { text = signalQualificationText(recoveryMss[symbol]); setTextColor(if (recoveryMss[symbol].isNullOrBlank()) amber else green) }
            chochStatusViews[symbol]?.apply { text = signalQualificationText(recoveryChoch[symbol]); setTextColor(if (recoveryChoch[symbol].isNullOrBlank()) amber else green) }
        }
    }

    /**
     * Switch the in-memory trading ledger to the selected Deriv environment.
     * DEMO and REAL use different SharedPreferences files; no legacy shared
     * ledger is copied into either environment because its account ownership
     * cannot be established safely.
     */
    private fun activateAccountMode(mode: String) {
        val normalized = if (mode.equals("REAL", true)) "REAL" else "DEMO"
        if (executionMode == normalized && tradingDayKey.isNotBlank()) return

        // Persist the currently selected environment before changing the key.
        if (tradingDayKey.isNotBlank()) savePositions()
        executionMode = normalized

        positions.clear()
        reversalTouched.clear()
        reversalQualifiedDirection.clear()
        reversalEventEpoch.clear()
        reversalEventPrice.clear()
        reversalReferenceLevel.clear()
        continuationDirection.clear()
        continuationLevel.clear()
        continuationBreakEpoch.clear()
        continuationRetestEpoch.clear()
        recoveryMss.clear()
        recoveryChoch.clear()
        recoveryMssEpoch.clear()
        recoveryChochEpoch.clear()
        originalTriggered.clear()
        consumedEngineSignals.clear()
        consumedEngineSignals.addAll(tradePrefs.getStringSet("consumed_engine_signals", emptySet()) ?: emptySet())

        tradingDayKey = tradePrefs.getString("day_key", "").orEmpty()
        dailyCapital = tradePrefs.getFloat("daily_capital", 0f).toDouble()
        indexes.forEach { symbol ->
            tradePrefs.getString("signal_mss_m15_$symbol", null)?.let { recoveryMss[symbol] = it }
            tradePrefs.getString("signal_choch_m15_$symbol", null)?.let { recoveryChoch[symbol] = it }
            val mssEpoch = tradePrefs.getLong("signal_mss_m15_epoch_$symbol", 0L)
            val chochEpoch = tradePrefs.getLong("signal_choch_m15_epoch_$symbol", 0L)
            if (mssEpoch > 0L) recoveryMssEpoch[symbol] = mssEpoch
            if (chochEpoch > 0L) recoveryChochEpoch[symbol] = chochEpoch
        }
        loadPositions()
        if (tradingDayKey.isNotBlank()) {
            originalTriggered.addAll(
                tradePrefs.getStringSet("triggered_$tradingDayKey", emptySet()) ?: emptySet()
            )
        }
    }

    private fun savePositions() {
        val array = JSONArray()
        positions.values.forEach { p ->
            array.put(JSONObject().put("id", p.localId).put("symbol", p.symbol).put("direction", p.direction)
                .put("role", p.role).put("stake", p.stake).put("original", p.originalId)
                .put("engineType", p.engineType)
                .put("contract", p.contractId).put("profit", p.profit).put("status", p.status)
                .put("closedAt", p.closedAt)
                .put("expiryEpoch", p.expiryEpoch)
                .put("openedAtEpoch", p.openedAtEpoch)
                .put("originalSignalDirection", p.originalSignalDirection)
                .put("entryMssDirection", p.entryMssDirection)
                .put("entryMssCloseEpoch", p.entryMssCloseEpoch)
                .put("entryChochDirection", p.entryChochDirection)
                .put("entryChochCloseEpoch", p.entryChochCloseEpoch)
                .put("entryMssPrice", p.entryMssPrice)
                .put("entryChochPrice", p.entryChochPrice)
                .put("entryLastCandleClosePrice", p.entryLastCandleClosePrice)
                .put("entryLastCandleCloseEpoch", p.entryLastCandleCloseEpoch)
                .put("executionEpoch", p.executionEpoch)
                .put("trailingActivated", p.trailingActivated)
                .put("trailingPeakPercent", p.trailingPeakPercent)
                .put("trailingStopPercent", p.trailingStopPercent)
                .put("trailingClosePending", p.trailingClosePending)
                .put("trailingCloseRejected", p.trailingCloseRejected)
                .put("trailingCloseFloorPrice", p.trailingCloseFloorPrice))
        }
        // commit() makes completed trade records durable before activity teardown/update.
        tradePrefs.edit().putString("positions", array.toString())
            .putStringSet("consumed_engine_signals", consumedEngineSignals.toSet()).commit()
    }

    private fun loadPositions() {
        consumedEngineSignals.clear()
        consumedEngineSignals.addAll(tradePrefs.getStringSet("consumed_engine_signals", emptySet()) ?: emptySet())
        val raw = tradePrefs.getString("positions", "") ?: return
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val p = Position(o.getString("id"), o.getString("symbol"), o.getString("direction"), o.getString("role"), o.getDouble("stake"), o.optString("original"), o.optString("engineType", ""))
                p.contractId = o.optLong("contract")
                p.profit = o.optDouble("profit")
                p.status = o.optString("status", "OPEN")
                p.closedAt = o.optLong("closedAt", 0L)
                p.expiryEpoch = o.optLong("expiryEpoch", 0L)
                p.openedAtEpoch = o.optLong("openedAtEpoch", 0L)
                p.originalSignalDirection = o.optString("originalSignalDirection", "")
                p.entryMssDirection = o.optString("entryMssDirection", "")
                p.entryMssCloseEpoch = o.optLong("entryMssCloseEpoch", 0L)
                p.entryChochDirection = o.optString("entryChochDirection", "")
                p.entryChochCloseEpoch = o.optLong("entryChochCloseEpoch", 0L)
                p.entryMssPrice = o.optDouble("entryMssPrice", 0.0)
                p.entryChochPrice = o.optDouble("entryChochPrice", 0.0)
                p.entryLastCandleClosePrice = o.optDouble("entryLastCandleClosePrice", 0.0)
                p.entryLastCandleCloseEpoch = o.optLong("entryLastCandleCloseEpoch", 0L)
                p.executionEpoch = o.optLong("executionEpoch", p.openedAtEpoch)
                p.trailingActivated = o.optBoolean("trailingActivated", false)
                p.trailingPeakPercent = o.optDouble("trailingPeakPercent", 0.0)
                p.trailingStopPercent = o.optDouble("trailingStopPercent", 0.0)
                p.trailingClosePending = o.optBoolean("trailingClosePending", false)
                p.trailingCloseRejected = o.optBoolean("trailingCloseRejected", false)
                p.trailingCloseFloorPrice = o.optDouble("trailingCloseFloorPrice", 0.0)
                positions[p.localId] = p
                // Never subscribe a restored contract against the other account's socket.
                if (p.contractId > 0 && p.status != "CLOSED" && accountConnected &&
                    derivService?.client?.getAccountType()?.equals(executionMode, true) == true
                ) subscribePosition(p.contractId)
            }
            savePositions()
        } catch (_: Exception) { }
    }

    private fun showAccountModeDialog() {
        val modes = arrayOf("DEMO ACCOUNT", "REAL ACCOUNT")
        val modeAdapter = object : android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, modes) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val item = super.getView(position, convertView, parent) as TextView
                item.setTextColor(if (position == 0) gold else blue)
                item.textSize = 16f
                item.setPadding(dp(24), dp(14), dp(24), dp(14))
                return item
            }
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Connect Deriv account")
            .setAdapter(modeAdapter) { _, which ->
                val selectedMode = if (which == 1) "REAL" else "DEMO"
                if (selectedMode != executionMode) {
                    // Stop the previously authenticated socket before changing
                    // environments. Otherwise its portfolio stream can repopulate
                    // the newly selected environment with the previous account's
                    // contracts.
                    accountConnected = false
                    try { derivService?.client?.disconnectAccount() } catch (_: Exception) {}
                    activateAccountMode(selectedMode)
                }
                prefs.edit().putString("oauth_mode", executionMode).apply()
                accountConnected = false
                accountLabel = "Connecting…"
                accountBalanceLabel = "Balance loading…"
                render()
                beginDerivConnection()
            }
            .setNegativeButton("CANCEL", null)
            .create()
        dialog.setOnShowListener {
            dialog.listView.setBackgroundColor(cardColor)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(green)
        }
        dialog.show()
    }

    private fun beginDerivConnection() {
        val tokenKey = if (executionMode == "REAL") "real_token" else "demo_token"
        val savedToken = prefs.getString(tokenKey, "").orEmpty()
        if (savedToken.isNotBlank()) {
            connectWithToken(savedToken)
        } else {
            startDerivOAuth()
        }
    }

    private fun startDerivOAuth() {
        oauthState = "android-" + UUID.randomUUID().toString()
        codeVerifier = generateCodeVerifier()
        prefs.edit()
            .putString("oauth_state", oauthState)
            .putString("code_verifier", codeVerifier)
            .putString("oauth_mode", executionMode)
            .apply()

        val challenge = generateCodeChallenge(codeVerifier)
        val authorizationUrl = Uri.parse(DerivApiClient.OAUTH_AUTH_URL).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", DerivApiClient.clientId(executionMode))
            .appendQueryParameter("redirect_uri", DerivApiClient.OAUTH_REDIRECT_URI)
            .appendQueryParameter("scope", DerivApiClient.OAUTH_SCOPE)
            .appendQueryParameter("state", oauthState)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()

        try {
            startActivity(Intent(Intent.ACTION_VIEW, authorizationUrl))
        } catch (e: Exception) {
            toast("Unable to open Deriv login: ${e.message ?: "browser unavailable"}")
        }
    }

    private fun handleOAuthCallback(callbackIntent: Intent?) {
        val data = callbackIntent?.data ?: return
        if (data.scheme != "volatilitiesindicator" || data.host != "oauth-callback") return

        val error = data.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            toast("Deriv authorization failed: $error")
            return
        }

        val code = data.getQueryParameter("code")
        val returnedState = data.getQueryParameter("state")
        val expectedState = prefs.getString("oauth_state", "").orEmpty()
        if (code.isNullOrBlank() || expectedState.isBlank() || returnedState != expectedState) {
            toast("Deriv sign-in failed: authorization code or security state is invalid.")
            return
        }

        executionMode = if (prefs.getString("oauth_mode", "DEMO").equals("REAL", true)) "REAL" else "DEMO"
        exchangeOAuthCode(code)
    }

    private fun exchangeOAuthCode(code: String) {
        val verifier = prefs.getString("code_verifier", "").orEmpty()
        if (verifier.isBlank()) {
            toast("Deriv sign-in failed: PKCE verifier is missing.")
            return
        }

        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("client_id", DerivApiClient.clientId(executionMode))
            .add("redirect_uri", DerivApiClient.OAUTH_REDIRECT_URI)
            .add("code_verifier", verifier)
            .build()

        val request = Request.Builder()
            .url(DerivApiClient.OAUTH_TOKEN_URL)
            .post(body)
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { toast("Deriv sign-in failed: ${e.message ?: "network error"}") }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseText = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    runOnUiThread { toast("Deriv token exchange failed (HTTP ${response.code}).") }
                    return
                }
                val token = try {
                    JSONObject(responseText).optString("access_token", "")
                } catch (_: Exception) { "" }

                if (token.isBlank()) {
                    runOnUiThread { toast("Deriv did not return an access token.") }
                    return
                }

                val tokenKey = if (executionMode == "REAL") "real_token" else "demo_token"
                prefs.edit()
                    .putString(tokenKey, token)
                    .remove("oauth_state")
                    .remove("code_verifier")
                    .apply()

                runOnUiThread { connectWithToken(token) }
            }
        })
    }

    private fun connectWithToken(token: String) {
        if (token.isBlank()) {
            toast("Deriv access token is empty.")
            return
        }
        val service = derivService
        if (service == null) {
            toast("Deriv service is starting. Please try Connect again.")
            startMarketFeedService()
            return
        }

        accountConnected = false
        accountLabel = "Connecting…"
        accountBalanceLabel = "Balance loading…"
        render()
        service.client.connectWithAccessToken(token, executionMode)
    }

    private fun disconnectDerivAccount() {
        val tokenKey = if (executionMode == "REAL") "real_token" else "demo_token"
        prefs.edit()
            .remove(tokenKey)
            .apply()
        try { derivService?.client?.disconnectAccount() } catch (_: Exception) {}
        accountConnected = false
        accountLabel = "Not connected"
        accountBalanceLabel = "Balance unavailable"
        addNotification("Accounts", "Deriv account disconnected", "The Deriv account was disconnected from the application.")
        render()
        toast("Deriv account disconnected.")
    }
    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(
            bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(
            hash, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    private fun render() {
        if (platformUserRole == "investor" && selectedTab !in listOf("Trade", "Accounts", "Profile")) selectedTab = "Trade"
        pageScrollView?.let { oldScroll ->
            if (renderedTab.isNotBlank()) scrollPositions[renderedTab] = oldScroll.scrollY
        }
        priceViews.clear()
        indexStatusViews.clear()
        marketStatusViews.clear()
        masterDecisionViews.clear()
        levelStatusViews.clear()
        highLevelViews.clear()
        lowLevelViews.clear()
        recoveryStatusViews.clear()
        mssStatusViews.clear()
        chochStatusViews.clear()
        tradeStatusViews.clear()
        tradeProfitViews.clear()
        tradeTrailingViews.clear()
        tradeCountdownViews.clear()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pageColor)
        }
        val restoreScrollY = scrollPositions[selectedTab] ?: 0
        // Keep the tab heading and notification bell outside the scrolling content.
        root.addView(notificationBar())
        root.addView(space(8))
        val tabForThisScrollView = selectedTab
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                scrollPositions[tabForThisScrollView] = scrollY
            }
        }
        pageScrollView = scroll
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(22))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (platformUserRole == "admin" || platformUserRole == "investor") root.addView(bottomNavigation())
        setContentView(root)
        renderedTab = selectedTab

        content.addView(space(8))

        when (selectedTab) {
            "Home" -> showHome()
            "Trade" -> showTrade()
            "Accounts" -> showAccounts()
            "Profile" -> showInvestorProfile()
            "Settings" -> showSettings()
        }
        val restoreListener = object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (scroll.viewTreeObserver.isAlive) {
                    scroll.viewTreeObserver.removeOnGlobalLayoutListener(this)
                }
                val maxScroll = (content.height - scroll.height).coerceAtLeast(0)
                scroll.scrollTo(0, restoreScrollY.coerceAtMost(maxScroll))
            }
        }
        scroll.viewTreeObserver.addOnGlobalLayoutListener(restoreListener)
    }

    private fun showHome() {
        val brand = horizontal()
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.volatilities_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Volatilities Indicator logo"
        }
        brand.addView(logo, LinearLayout.LayoutParams(dp(58), dp(58)))
        val brandText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        brandText.addView(text("VOLATILITIES", 19f, green, true))
        brandText.addView(text("INDICATOR", 19f, green, true))
        brand.addView(brandText)
        content.addView(brand)
        content.addView(space(3))
        content.addView(text("Market monitor", 13f, green))
        content.addView(space(18))
        val status = horizontal()
        status.addView(text("DERIV MARKET FEED", 12f, green, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        val feedPill = pill(if (marketFeedConnected) "LIVE" else "CONNECTING", if (marketFeedConnected) green else amber)
        feedPillView = feedPill
        status.addView(feedPill)
        content.addView(status)
        content.addView(space(10))
        content.addView(autoTradingButton())
        content.addView(space(12))
        val account = card()
        account.addView(text("TRADING ACCOUNT", 12f, green, true))
        account.addView(space(8))
        val accountName = text(if (accountConnected) accountLabel else "Not connected", 17f, whiteText, true)
        accountIdView = accountName
        account.addView(accountName)
        account.addView(text("Account mode: $executionMode", 12f, if (executionMode == "REAL") blue else red, true))
        val balanceText = text(accountBalanceLabel, 13f, blue)
        accountBalanceView = balanceText
        account.addView(balanceText)
        val homePl = accountProfitLoss()
        accountOpenPlView = text("OPEN TRADE P/L: ${formatMoney(homePl.first)}", 12f, if (homePl.first >= 0) green else red, true)
        accountClosedPlView = text("CLOSED TRADE P/L: ${formatMoney(homePl.second)}", 12f, if (homePl.second >= 0) green else red, true)
        accountTotalPlView = text("TOTAL TRADE P/L: ${formatMoney(homePl.third)}", 12f, if (homePl.third >= 0) green else red, true)
        account.addView(accountOpenPlView)
        account.addView(accountClosedPlView)
        account.addView(accountTotalPlView)
        val statusText = text(if (accountConnected) "CONNECTED" else "ACCOUNT NOT CONNECTED", 12f,
            if (accountConnected) green else red, true)
        accountStatusView = statusText
        account.addView(statusText)
        account.addView(space(12))
        account.addView(actionButton(if (accountConnected) "ACCOUNT OPTIONS" else "CONNECT DERIV ACCOUNT") {
    if (accountConnected) openTab("Accounts") else showAccountModeDialog()
}.apply {
    setTextColor(Color.BLACK)
})
        content.addView(account)
        content.addView(space(18))

        content.addView(text("INDEX MONITOR", 16f, green, true))
        content.addView(text("Live Deriv prices for all five synthetic indices.", 12f, whiteText))
        content.addView(space(10))

        indexes.forEach { symbol ->
            val indexCard = card(indexNameColor(symbol))
            val row = horizontal()
            row.addView(text(displayIndexName(symbol), 15f, indexNameColor(symbol), true),
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
            priceRow.addView(text("LIVE PRICE", 11f, whiteText),
                LinearLayout.LayoutParams(0, -2, 1f))
            val priceView = text(latestPrices[symbol]?.let { formatPrice(it) } ?: "--", 17f, blue, true)
            priceViews[symbol] = priceView
            priceRow.addView(priceView)
            indexCard.addView(priceRow)
            indexCard.addView(space(8))
            indexCard.addView(divider())
            indexCard.addView(space(8))
            indexCard.addView(text("MASTER DECISION", 11f, Color.WHITE, true))
            val decisionView = text(runningDirectionText(symbol) ?: "WAITING", 12f,
                if (runningDirectionText(symbol) != null) green else amber, true)
            masterDecisionViews[symbol] = decisionView
            indexCard.addView(decisionView)
            val currentLevels = dailyLevels[symbol]
            val highView = text("PREVIOUS DAY HIGH: ${currentLevels?.first?.let { formatPrice(it) } ?: "--"}", 12f, green, true)
            val lowView = text("PREVIOUS DAY LOW: ${currentLevels?.second?.let { formatPrice(it) } ?: "--"}", 12f, red, true)
            highLevelViews[symbol] = highView
            lowLevelViews[symbol] = lowView
            indexCard.addView(highView)
            indexCard.addView(lowView)
            indexCard.addView(space(6))
            val feedDetail = text(masterDecisionText(symbol), 11f, Color.WHITE)
            marketStatusViews[symbol] = feedDetail
            indexCard.addView(text("WAITING FOR", 10f, Color.WHITE, true))
            indexCard.addView(feedDetail)
            indexCard.addView(space(8))
            indexCard.addView(divider())
            indexCard.addView(space(8))
            indexCard.addView(text("MSS QUALIFICATION", 10f, whiteText, true))
            val mssView = text(signalQualificationText(recoveryMss[symbol]), 11f,
                if (recoveryMss[symbol].isNullOrBlank()) amber else green, true)
            mssStatusViews[symbol] = mssView
            indexCard.addView(mssView)
            indexCard.addView(space(5))
            indexCard.addView(text("CHOCH QUALIFICATION", 10f, whiteText, true))
            val chochView = text(signalQualificationText(recoveryChoch[symbol]), 11f,
                if (recoveryChoch[symbol].isNullOrBlank()) amber else green, true)
            chochStatusViews[symbol] = chochView
            indexCard.addView(chochView)
            indexCard.addView(space(8))
            indexCard.addView(divider())
            indexCard.addView(space(8))
            indexCard.addView(text("TRADE LIFECYCLE", 11f, whiteText, true))
            val recoveryView = text(recoveryStatusText(symbol), 11f, muted)
            recoveryStatusViews[symbol] = recoveryView
            indexCard.addView(recoveryView)
            content.addView(indexCard)
            content.addView(space(9))
        }
    }

    private fun showTrade() {
        countdownHandler.removeCallbacks(countdownTask)
        countdownHandler.post(countdownTask)
        pageHeading("RUNNING TRADES", "Reversal and Continuation executions.")
        content.addView(autoTradingButton())
        content.addView(space(10))
        val summary = card()
        summary.addView(text("DAILY STARTING CAPITAL", 12f, gold, true))
        summary.addView(text(String.format(Locale.US, "%.2f %s", dailyCapital, accountCurrency), 18f, blue, true))
        summary.addView(text("ACCOUNT MODE: $executionMode", 12f, if (executionMode == "REAL") blue else red, true))
        summary.addView(text("ORIGINAL TRADE STAKE: 20% OF FIXED DAILY STARTING CAPITAL", 12f, gold))
        content.addView(summary)
        content.addView(space(16))

        val activePositions = positions.values.filter { it.status != "CLOSED" }
        val closedPositions = positions.values.filter { it.status == "CLOSED" }.sortedByDescending { it.closedAt }

        content.addView(text("RUNNING TRADES", 17f, gold, true))
        content.addView(space(8))
        if (activePositions.isEmpty()) {
            val empty = card()
            empty.addView(text("NO RUNNING TRADES", 15f, ink, true))
            empty.addView(text("No trade is currently open.", 13f, muted))
            content.addView(empty)
        } else {
            // One independently collapsible card per index. The collapsed view
            // intentionally contains only countdown, profit/loss and trailing stop.
            activePositions.groupBy { it.symbol }.forEach { (symbol, indexPositions) ->
                val c = card(indexNameColor(symbol))
                val header = text("${displayIndexName(symbol)} · RUNNING POSITIONS   +", 14f,
                    indexNameColor(symbol), true)
                c.addView(header)
                c.addView(space(6))

                val collapsedSummary = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                val detailsContainer = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    visibility = View.GONE
                }

                indexPositions.forEachIndexed { index, p ->
                    if (index > 0) {
                        collapsedSummary.addView(space(6))
                        collapsedSummary.addView(divider())
                        collapsedSummary.addView(space(6))
                        detailsContainer.addView(space(8))
                        detailsContainer.addView(divider())
                        detailsContainer.addView(space(8))
                    }

                    if (p.status == "OPEN") {
                        val countdownView = text("TIME REMAINING: ${formatContractCountdown(p.expiryEpoch)}", 13f, blue, true)
                        tradeCountdownViews[p.localId] = countdownView
                        collapsedSummary.addView(countdownView)
                    }
                    val engineLabel = p.engineType.ifBlank { if (p.role == "EXISTING") "EXISTING CONTRACT" else "ENGINE NOT RECORDED" }
                    collapsedSummary.addView(text(engineLabel, 11f, gold, true))
                    val profitView = text("Profit / Loss: ${formatMoney(p.profit)}", 14f,
                        if (p.profit >= 0.0) green else red, true)
                    tradeProfitViews[p.localId] = profitView
                    collapsedSummary.addView(profitView)
                    val trailingView = text(trailingStopDisplayText(p), 12f,
                        if (p.trailingActivated) green else blue, true)
                    tradeTrailingViews[p.localId] = trailingView
                    collapsedSummary.addView(trailingView)

                    detailsContainer.addView(text("${p.engineType.ifBlank { p.role }} · ${p.direction}", 14f,
                        if (p.direction == "RISE") green else red, true))
                    detailsContainer.addView(text("Contract: ${if (p.contractId > 0L) p.contractId.toString() else "Pending"}", 12f, Color.WHITE))
                    detailsContainer.addView(text("Stake: ${formatMoney(p.stake)}", 13f, gold))
                    detailsContainer.addView(space(5))
                    detailsContainer.addView(text(entrySignalAuditText(p), 11.5f, Color.WHITE, true))
                    val statusView = text("Status: ${p.status}", 13f,
                        if (p.status == "OPEN") green else amber, true)
                    tradeStatusViews[p.localId] = statusView
                    detailsContainer.addView(statusView)

                    if (p.contractId > 0L && p.status == "OPEN") {
                        detailsContainer.addView(space(6))
                        detailsContainer.addView(actionButton("CLOSE THIS ${p.direction} TRADE", primary = false) {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("CONFIRM TRADE CLOSURE")
                                .setMessage("Are you sure you want to close this ${p.direction} contract on ${displayIndexName(p.symbol)}?")
                                .setNegativeButton("CANCEL", null)
                                .setPositiveButton("CONFIRM CLOSE") { _, _ -> closeRunningPosition(p) }
                                .show()
                        })
                    }
                }

                val recoveryTradeView = text(recoveryStatusText(symbol), 12f, blue, true)
                tradeRecoveryStatusViews[symbol] = recoveryTradeView
                detailsContainer.addView(space(8))
                detailsContainer.addView(text("TRADE LIFECYCLE", 12f, gold, true))
                detailsContainer.addView(recoveryTradeView)
                c.addView(collapsedSummary)
                c.addView(detailsContainer)
                header.setOnClickListener {
                    val expanding = detailsContainer.visibility != View.VISIBLE
                    detailsContainer.visibility = if (expanding) View.VISIBLE else View.GONE
                    header.text = "${displayIndexName(symbol)} · RUNNING POSITIONS   ${if (expanding) "−" else "+"}"
                }
                content.addView(c)
                content.addView(space(8))
            }
        }

        content.addView(space(10))
        content.addView(divider())
        content.addView(space(14))
        content.addView(text("TRADE HISTORY", 17f, Color.WHITE, true))
content.addView(text("Completed original contracts.", 12f, whiteText))
content.addView(space(8))

content.addView(actionButton("CLEAR TRADING HISTORY", primary = false) {
    AlertDialog.Builder(this)
        .setTitle("CLEAR TRADING HISTORY")
        .setMessage("Are you sure you want to delete all completed trades? Running trades will not be affected.")
        .setNegativeButton("CANCEL", null)
        .setPositiveButton("CLEAR") { _, _ ->
            val completedIds = positions.values
                .filter { it.status == "CLOSED" }
                .map { it.localId }

            completedIds.forEach { positions.remove(it) }

            savePositions()
            render()
        }
        .show()
})
content.addView(space(8))
        if (closedPositions.isEmpty()) {
            content.addView(text("No completed trades yet.", 12f, muted))
        } else {
            closedPositions.forEach { p ->
                val c = card(indexNameColor(p.symbol))
                c.addView(text("${displayIndexName(p.symbol)} · ${p.role}", 14f, indexNameColor(p.symbol), true))
                c.addView(text("DIRECTION: ${p.direction}", 14f, if (p.direction == "RISE") green else red, true))
                c.addView(text("Stake: ${formatMoney(p.stake)}", 13f, Color.WHITE))
                c.addView(space(5))
                c.addView(text(entrySignalAuditText(p), 11.5f, Color.WHITE, true))
                c.addView(text("FINAL P/L: ${formatMoney(p.profit)}", 14f, if (p.profit >= 0.0) green else red, true))
                val closedText = if (p.closedAt > 0L) java.text.DateFormat.getDateTimeInstance().format(java.util.Date(p.closedAt * 1000L)) else "Time unavailable"
                c.addView(text("Closed: $closedText", 11f, Color.WHITE))
                content.addView(c)
                content.addView(space(8))
            }
        }
        tradeStatusView = text(if (accountConnected) "EXECUTION CONNECTED" else "CONNECT A DERIV ACCOUNT TO ENABLE EXECUTION", 12f, if (accountConnected) green else amber, true)
        content.addView(tradeStatusView)
    }

    private fun formatContractCountdown(expiryEpoch: Long): String {
        if (expiryEpoch <= 0L) return "Waiting for Deriv expiry time"

        val nowEpochSeconds: Long = System.currentTimeMillis() / 1000L
        val remainingSeconds: Long = (expiryEpoch - nowEpochSeconds).coerceAtLeast(0L)

        // Use subtraction-based remainder calculations to keep every value Long
        // and avoid overloaded rem()/compareTo() compiler resolution problems.
        val totalHours: Long = remainingSeconds / 3600L
        val remainingDays: Long = totalHours / 24L
        val hoursWithinDay: Long = totalHours - (remainingDays * 24L)
        val totalMinutes: Long = remainingSeconds / 60L
        val minutesWithinHour: Long = totalMinutes - (totalHours * 60L)
        val secondsWithinMinute: Long = remainingSeconds - (totalMinutes * 60L)

        return if (remainingDays > 0L) {
            String.format(
                Locale.US,
                "%dd %02d:%02d:%02d",
                remainingDays,
                hoursWithinDay,
                minutesWithinHour,
                secondsWithinMinute
            )
        } else {
            String.format(
                Locale.US,
                "%02d:%02d:%02d",
                totalHours,
                minutesWithinHour,
                secondsWithinMinute
            )
        }
    }

    private fun updateRunningTradeCountdowns() {
        if (selectedTab != "Trade") return
        tradeCountdownViews.forEach { (localId, view) ->
            val position = positions[localId]
            if (position == null || position.status != "OPEN") {
                view.text = "TIME REMAINING: --"
            } else {
                view.text = "TIME REMAINING: ${formatContractCountdown(position.expiryEpoch)}"
            }
        }
    }

    private fun updateTradePositionViews(position: Position) {
        tradeStatusViews[position.localId]?.apply {
            text = "Status: ${position.status}"
            setTextColor(if (position.status == "OPEN") green else amber)
        }
        tradeProfitViews[position.localId]?.apply {
            text = "Profit / Loss: ${formatMoney(position.profit)}"
            setTextColor(if (position.profit >= 0.0) green else red)
        }
        tradeTrailingViews[position.localId]?.apply {
            text = trailingStopDisplayText(position)
            setTextColor(if (position.trailingActivated) green else blue)
        }
    }

    private fun showAccounts() {
        pageHeading("ACCOUNTS", "Account access and connection settings.")
        val deriv = card()
        deriv.addView(text("DERIV ACCOUNT", 15f, gold, true))
        deriv.addView(space(6))
        deriv.addView(text("Account mode: $executionMode", 13f, if (executionMode == "REAL") blue else red, true))
        deriv.addView(text(if (accountConnected) accountLabel else "Connection: Disconnected", 13f, Color.WHITE))
        deriv.addView(text(accountBalanceLabel, 13f, blue))
        val pl = accountProfitLoss()
        accountOpenPlView = text("OPEN TRADE P/L: ${formatMoney(pl.first)}", 13f, if (pl.first >= 0) green else red, true)
        accountClosedPlView = text("CLOSED TRADE P/L: ${formatMoney(pl.second)}", 13f, if (pl.second >= 0) green else red, true)
        accountTotalPlView = text("TOTAL TRADE P/L: ${formatMoney(pl.third)}", 13f, if (pl.third >= 0) green else red, true)
        deriv.addView(accountOpenPlView)
        deriv.addView(accountClosedPlView)
        deriv.addView(accountTotalPlView)
        deriv.addView(space(12))
        deriv.addView(actionButton(if (accountConnected) "SWITCH / CONNECT ACCOUNT" else "CONNECT DERIV") {
    showAccountModeDialog()
}.apply {
    setTextColor(Color.BLACK)
})
        deriv.addView(space(8))
        deriv.addView(actionButton("DISCONNECT DERIV", false) {
            disconnectDerivAccount()
        })
        content.addView(deriv)
        content.addView(space(12))

        if (platformUserRole != "investor") {
        val users = card()
        users.addView(text("USER ACCOUNT", 15f, green, true))
        users.addView(text(if (platformAuthenticated) platformUserName.ifBlank { "Signed-in account" } else "Sign in to view your profile", 13f, muted))
        users.addView(space(10))
        users.addView(actionButton("USER ACCOUNT", false) { openUserProfile() })
        content.addView(users)
        content.addView(space(12))

        val admin = card()
        admin.addView(text("ADMIN ACCOUNT", 15f, green, true))
        admin.addView(text(if (platformUserRole == "admin") "Administrator dashboard and registered-user management." else "Administrator access only.", 13f, muted))
        admin.addView(space(10))
        admin.addView(actionButton("ADMIN ACCOUNT", false) { openAdminDashboard() })
        content.addView(admin)
        }
    }

    // All account-management requests use the existing authenticated mobile-auth.php session.
    private fun platformRequest(action: String, values: Map<String, String> = emptyMap(), onResult: (JSONObject) -> Unit) {
        if (!platformAuthenticated || platformAuthToken.isBlank()) {
            toast("Please sign in to your Volatilities Indicator account first.")
            showPlatformAuthScreen(false, "Please sign in to continue.")
            return
        }
        val form = FormBody.Builder().add("action", action)
        values.forEach { (key, value) -> form.add(key, value) }
        val request = Request.Builder().url(platformAuthUrl)
            .header("Authorization", "Bearer $platformAuthToken")
            .post(form.build()).build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { toast("Unable to contact the account server. Check your connection.") }
            }
            override fun onResponse(call: Call, response: Response) {
                val raw = response.body?.string().orEmpty()
                val result = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
                response.close()
                runOnUiThread {
                    if (!result.optBoolean("ok")) {
                        val message = result.optString("message", "The account request could not be completed.")
                        toast(message)
                        if (response.code == 401) signOutPlatform()
                    } else onResult(result)
                }
            }
        })
    }

    private fun accountSubPage(title: String, subtitle: String): LinearLayout {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(pageColor) }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(12)) }
        heading.addView(text(title, 21f, gold, true))
        heading.addView(text(subtitle, 13f, Color.WHITE))
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(20)) }
        scroll.addView(body)
        root.addView(heading)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(actionButton("BACK TO ACCOUNTS", false) { selectedTab = "Accounts"; render() },
            LinearLayout.LayoutParams(-1, dp(52)).apply { setMargins(dp(18), dp(8), dp(18), dp(12)) })
        setContentView(root)
        return body
    }

    private fun addProfileField(target: LinearLayout, label: String, value: String) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(9), 0, dp(9)) }
        row.addView(text(label.uppercase(Locale.US), 11f, green, true))
        row.addView(text(value.ifBlank { "Not provided" }, 15f, ink))
        target.addView(row)
        target.addView(divider())
    }

    private fun openUserProfile() {
        if (!platformAuthenticated) {
            showPlatformAuthScreen(false, "Sign in to view your user profile.")
            return
        }
        platformRequest("profile") { result ->
            platformUser = result.optJSONObject("user") ?: platformUser
            platformUserName = platformUser.optString("full_name", platformUserName)
            platformUserRole = platformUser.optString("role", platformUserRole).lowercase(Locale.US)
            if (platformUserRole == "investor") showInvestorDerivScreen() else showUserProfile(platformUser)
        }
    }

    private fun showUserProfile(user: JSONObject) {
        val body = accountSubPage("USER PROFILE", "Your personal Volatilities Indicator account.")
        val panel = card()
        panel.addView(text(user.optString("full_name", platformUserName), 18f, green, true))
        panel.addView(space(8))
        addProfileField(panel, "Account role", user.optString("role", "user").uppercase(Locale.US))
        addProfileField(panel, "Account status", user.optString("status", "unknown").uppercase(Locale.US))
        addProfileField(panel, "Email address", user.optString("email"))
        addProfileField(panel, "Contact number", user.optString("phone"))
        addProfileField(panel, "Registration date", user.optString("created_at"))
        addProfileField(panel, "Subscription", "R500 · ${if (user.optString("subscription_status") == "approved" && user.optString("subscription_expiry_date") < java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())) "EXPIRED" else user.optString("subscription_status", "pending").uppercase(Locale.US)}")
        addProfileField(panel, "Subscription inception", user.optString("subscription_inception_date", "Not approved"))
        addProfileField(panel, "Subscription expiry", user.optString("subscription_expiry_date", "Not approved"))
        addProfileField(panel, "Proof of payment", if (user.optString("payment_proof_name").isNotBlank()) "Uploaded: ${user.optString("payment_proof_name")}" else "Not uploaded")
        body.addView(panel)
        body.addView(space(12))
        body.addView(actionButton("UPLOAD SUBSCRIPTION PROOF OF PAYMENT", true) { openPaymentProofPicker("subscription") })
        body.addView(space(8))
        body.addView(actionButton("UPLOAD INVESTMENT PROOF OF PAYMENT", false) { openPaymentProofPicker("investment") })
        body.addView(space(8))
        body.addView(actionButton("VIEW INVESTMENT PLANS", true) { loadInvestmentPlans(false) })
        body.addView(space(8))
        body.addView(actionButton("MY INVESTMENT PROFILE", false) { loadMyInvestments() })
        body.addView(space(8))
        body.addView(actionButton("SIGN OUT", false) { signOutPlatform() })
    }

    private fun showInvestorProfile() {
        if (!platformAuthenticated) {
            showPlatformAuthScreen(false, "Sign in to view your investor profile.")
            return
        }
        platformRequest("profile") { result ->
            val user = result.optJSONObject("user") ?: platformUser
            platformUser = user
            platformUserName = user.optString("full_name", platformUserName)
            platformUserRole = user.optString("role", platformUserRole).lowercase(Locale.US)
            if (platformUserRole != "investor") {
                selectedTab = "Trade"
                render()
                return@platformRequest
            }
            val body = accountSubPage("INVESTOR PROFILE", "Your Volatilities Indicator investor account.")
            val panel = card()
            panel.addView(text(user.optString("full_name", platformUserName), 18f, green, true))
            panel.addView(space(8))
            addProfileField(panel, "Account type", "INVESTOR")
            addProfileField(panel, "Account status", user.optString("status", "unknown").uppercase(Locale.US))
            addProfileField(panel, "Email address", user.optString("email"))
            addProfileField(panel, "Contact number", user.optString("phone"))
            addProfileField(panel, "Registration date", user.optString("created_at"))
            addProfileField(panel, "Subscription proof", if (user.optString("subscription_proof_name", user.optString("payment_proof_name")).isNotBlank()) "Uploaded" else "Not uploaded")
            addProfileField(panel, "Investment proof", if (user.optString("investment_proof_name").isNotBlank()) "Uploaded" else "Not uploaded")
            addProfileField(panel, "Subscription", "R500 · ${user.optString("subscription_status", "pending").uppercase(Locale.US)}")
            addProfileField(panel, "Subscription inception", user.optString("subscription_inception_date", "Not approved"))
            addProfileField(panel, "Subscription expiry", user.optString("subscription_expiry_date", "Not approved"))
                        addProfileField(panel, "Selected trading plan", when(user.optString("investor_plan")) {
                "A" -> "Plan A · 50% target · 15 days"
                "B" -> "Plan B · 100% target · 30 days"
                "U" -> "UNLIMITED INVESTOR · No plan expiry or capital-growth ceiling"
                else -> "Not selected"
            })
            addProfileField(panel, "Plan approval", user.optString("investor_plan_status", "not_selected").replace('_',' ').uppercase(Locale.US))
            val planStartDate = user.optString("investor_plan_start_date").takeUnless { it.isBlank() || it == "null" } ?: "Not approved"
                        val planExpiryDate = if (user.optString("investor_plan") == "U" && user.optString("investor_plan_status") == "approved") "NO EXPIRY" else user.optString("investor_plan_expiry_date").takeUnless { it.isBlank() || it == "null" } ?: "Not approved"
            addProfileField(panel, "Investor plan start date", planStartDate)
            addProfileField(panel, "Investor plan expiry date", planExpiryDate)
            body.addView(panel)
            body.addView(space(12))
            body.addView(actionButton("UPLOAD SUBSCRIPTION PROOF OF PAYMENT", true) { openPaymentProofPicker("subscription") })
            body.addView(space(8))
            body.addView(actionButton("UPLOAD INVESTMENT PROOF OF PAYMENT", false) { openPaymentProofPicker("investment") })
            body.addView(space(8))
            body.addView(actionButton("SELECT INVESTOR TRADING PLAN", true) { showInvestorPlanSelection() })
            body.addView(space(8))
            body.addView(actionButton("SIGN OUT", false) { signOutPlatform() })
        }
    }

        private fun showInvestorPlanSelection() {
        val current = platformUser.optString("investor_plan")
        val options = arrayOf(
            "Plan A — 50% in 15 days",
            "Plan B — 100% in 30 days",
            "UNLIMITED INVESTOR — No plan expiry or capital-growth ceiling"
        )
        val selected = when (current) {
            "B" -> 1
            "U" -> 2
            else -> 0
        }
        AlertDialog.Builder(this).setTitle("Choose Investor Trading Plan")
            .setSingleChoiceItems(options, selected, null)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("SUBMIT SELECTION") { dialog, _ ->
                val index = (dialog as AlertDialog).listView.checkedItemPosition
                val plan = when (index) {
                    1 -> "B"
                    2 -> "U"
                    else -> "A"
                }
                platformRequest("investor_select_plan", mapOf("plan" to plan)) {
                    toast(it.optString("message", "Investor plan selection submitted."))
                    showInvestorProfile()
                }
            }.show()
        }

    private var selectedProofType: String = "subscription"

    private fun openPaymentProofPicker(proofType: String) {
        selectedProofType = if (proofType == "investment") "investment" else "subscription"
        if (!platformAuthenticated || platformAuthToken.isBlank()) {
            toast("Please sign in before uploading proof of payment.")
            return
        }
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "image/jpeg", "image/png"))
        }
        startActivityForResult(picker, paymentProofPickerRequest)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != paymentProofPickerRequest || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val mimeType = contentResolver.getType(uri)?.lowercase(Locale.US).orEmpty()
        if (mimeType !in listOf("application/pdf", "image/jpeg", "image/png")) {
            toast("Please select a PDF, JPG or PNG proof of payment.")
            return
        }
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null || bytes.isEmpty()) {
                toast("The selected file could not be read.")
                return
            }
            if (bytes.size > 5 * 1024 * 1024) {
                toast("The proof of payment must not exceed 5 MB.")
                return
            }
            val extension = when (mimeType) {
                "application/pdf" -> "pdf"
                "image/jpeg" -> "jpg"
                else -> "png"
            }
            uploadPaymentProof(bytes, mimeType, "proof_of_payment.$extension", selectedProofType)
        } catch (_: Exception) {
            toast("Unable to read the selected proof of payment.")
        }
    }

    private fun uploadPaymentProof(bytes: ByteArray, mimeType: String, fileName: String, proofType: String) {
        if (!platformAuthenticated || platformAuthToken.isBlank()) {
            toast("Please sign in before uploading proof of payment.")
            return
        }
        val body = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("action", "upload_payment_proof")
            .addFormDataPart("proof_type", proofType)
            .addFormDataPart("proof", fileName,
                bytes.toRequestBody(mimeType.toMediaType()))
            .build()
        val request = Request.Builder().url(platformAuthUrl)
            .header("Authorization", "Bearer $platformAuthToken")
            .post(body).build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { toast("Proof of payment could not be uploaded. Check your connection.") }
            }
            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                val raw = response.body?.string().orEmpty()
                response.close()
                val result = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
                runOnUiThread {
                    if (result.optBoolean("ok")) {
                        toast(result.optString("message", "Proof of payment uploaded successfully."))
                        if (platformUserRole == "investor") showInvestorProfile() else openUserProfile()
                    } else {
                        val message = result.optString("message").ifBlank { "Upload failed (HTTP $code). Check server upload limits and mobile-auth.php response." }
                        toast(message)
                        if (code == 401) signOutPlatform()
                    }
                }
            }
        })
    }

    private fun openAdminDashboard() {
        if (!platformAuthenticated) {
            showPlatformAuthScreen(false, "Sign in with an administrator account to continue.")
            return
        }
        if (platformUserRole != "admin") {
            toast("Administrator access is required.")
            return
        }
        platformRequest("admin_users") { result -> showAdminDashboard(result.optJSONArray("users") ?: JSONArray()) }
    }

    private fun showAdminDashboard(users: JSONArray) {
        val body = accountSubPage("ADMIN DASHBOARD", "Administrator profile and registered-user management.")
        val adminCard = card()
        adminCard.addView(text("ADMINISTRATOR PROFILE", 15f, green, true))
        adminCard.addView(space(6))
        addProfileField(adminCard, "Name", platformUser.optString("full_name", platformUserName))
        addProfileField(adminCard, "Email", platformUser.optString("email"))
        addProfileField(adminCard, "Role", "ADMIN")
        body.addView(adminCard)
        body.addView(space(14))
        body.addView(text("PENDING APPROVALS", 17f, gold, true))
        body.addView(space(8))
        var pendingCount = 0
        for (i in 0 until users.length()) {
            val pendingUser = users.optJSONObject(i) ?: continue
            val uid = pendingUser.optString("id")
            val name = pendingUser.optString("full_name", "User")
            val subProof = pendingUser.optString("subscription_proof_name", pendingUser.optString("payment_proof_name"))
            val invProof = pendingUser.optString("investment_proof_name")
            if (subProof.isNotBlank() && pendingUser.optString("subscription_status", "pending") != "approved") {
                pendingCount++
                body.addView(text("$name · SUBSCRIPTION PAYMENT", 14f, ink, true))
                body.addView(actionButton("VIEW SUBSCRIPTION PROOF", false) { adminViewPaymentProof(uid, "subscription") })
                body.addView(actionButton("APPROVE SUBSCRIPTION", true) { adminDecision("admin_subscription_decision", uid, "approve") })
                body.addView(actionButton("REJECT SUBSCRIPTION", false) { adminDecision("admin_subscription_decision", uid, "reject") })
                body.addView(space(8))
            }
            if (invProof.isNotBlank() && pendingUser.optString("investment_payment_status", "pending") == "pending") {
                pendingCount++
                body.addView(text("$name · INVESTMENT PAYMENT", 14f, ink, true))
                body.addView(actionButton("VIEW INVESTMENT PROOF", false) { adminViewPaymentProof(uid, "investment") })
                body.addView(actionButton("APPROVE INVESTMENT PAYMENT", true) { adminDecision("admin_investment_payment_decision", uid, "approve") })
                body.addView(actionButton("REJECT INVESTMENT PAYMENT", false) { adminDecision("admin_investment_payment_decision", uid, "reject") })
                body.addView(space(8))
            }
            if (pendingUser.optString("role").equals("investor", true) && pendingUser.optString("investor_plan_status") == "pending" && pendingUser.optString("investor_plan").isNotBlank()) {
                pendingCount++
                body.addView(text("$name · INVESTOR PLAN ${pendingUser.optString("investor_plan")}", 14f, ink, true))
                body.addView(actionButton("APPROVE INVESTOR PLAN", true) { adminDecision("admin_investor_plan_decision", uid, "approve") })
                body.addView(actionButton("REJECT INVESTOR PLAN", false) { adminDecision("admin_investor_plan_decision", uid, "reject") })
                body.addView(space(8))
            }
        }
        if (pendingCount == 0) body.addView(text("No pending subscription, investment payment or investor plan approvals.", 13f, muted))
        body.addView(space(14))
        body.addView(text("INVESTMENT ADMINISTRATION", 17f, gold, true))
        body.addView(space(8))
        body.addView(actionButton("MANAGE SUBSCRIPTIONS", true) { loadAdminSubscriptions() })
        body.addView(space(8))
        body.addView(actionButton("MANAGE INVESTMENT PLANS", true) { loadInvestmentPlans(true) })
        body.addView(space(8))
        body.addView(actionButton("VIEW INVESTMENT REQUESTS", false) { loadAdminInvestments() })
        body.addView(space(18))
        val heading = horizontal()
        val registeredUserCount = (0 until users.length()).count { index ->
            users.optJSONObject(index)?.optString("role", "user")?.equals("admin", true) != true
        }
        heading.addView(text("REGISTERED USERS", 17f, gold, true), LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(text("$registeredUserCount total", 12f, ink))
        body.addView(heading)
        body.addView(space(8))
        if (registeredUserCount == 0) {
            body.addView(text("No registered users were returned by the server.", 14f, muted))
        }
        for (i in 0 until users.length()) {
            val user = users.optJSONObject(i) ?: continue
            if (user.optString("role", "user").equals("admin", true)) continue
            val item = card()
            item.addView(text(user.optString("full_name", "Unnamed user"), 15f, green, true))
            item.addView(text(user.optString("email"), 13f, ink))
            item.addView(space(4))
            val statusRow = horizontal()
            statusRow.addView(text("Status: ${user.optString("status", "unknown").uppercase(Locale.US)}", 12f,
                if (user.optString("status") == "approved") green else amber, true), LinearLayout.LayoutParams(0, -2, 1f))
            statusRow.addView(text("ACCOUNT TYPE: ${user.optString("role", "user").uppercase(Locale.US)}", 11f, muted))
            item.addView(statusRow)
            item.addView(space(8))
            item.addView(actionButton("OPEN USER PROFILE", false) { showAdminUserProfile(user) })
            body.addView(item)
            body.addView(space(9))
        }
        body.addView(actionButton("REFRESH REGISTERED USERS", true) { openAdminDashboard() })
    }

    // Investment-plan screens are additive: the existing trading engine and account dashboard remain unchanged.
    private fun investmentRequest(action: String, values: Map<String, String> = emptyMap(), onResult: (JSONObject) -> Unit) {
        if (!platformAuthenticated || platformAuthToken.isBlank()) {
            toast("Please sign in to your Volatilities Indicator account first.")
            showPlatformAuthScreen(false, "Please sign in to continue.")
            return
        }
        val form = FormBody.Builder().add("action", action)
        values.forEach { (key, value) -> form.add(key, value) }
        val request = Request.Builder().url(investmentApiUrl)
            .header("Authorization", "Bearer $platformAuthToken")
            .post(form.build()).build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { toast("Unable to contact the investment server. Check your connection.") }
            }
            override fun onResponse(call: Call, response: Response) {
                val statusCode = response.code
                val raw = response.body?.string().orEmpty()
                val result = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
                response.close()
                runOnUiThread {
                    if (!result.optBoolean("ok")) {
                        toast(result.optString("message", "Investment request could not be completed."))
                        if (statusCode == 401) signOutPlatform()
                    } else onResult(result)
                }
            }
        })
    }

    private fun loadInvestmentPlans(admin: Boolean) {
        investmentRequest(if (admin) "admin_plans" else "plans") { result ->
            showInvestmentPlans(result.optJSONArray("plans") ?: JSONArray(), admin)
        }
    }

    private fun showInvestmentPlans(plans: JSONArray, admin: Boolean) {
        val body = accountSubPage(if (admin) "MANAGE INVESTMENT PLANS" else "INVESTMENT PLANS",
            if (admin) "Administrator controls for plan terms and availability." else "Choose an available plan and enter an amount between R1,000 and R5,000.")
        if (plans.length() == 0) body.addView(text("No plans are currently available.", 14f, muted))
        for (i in 0 until plans.length()) {
            val plan = plans.optJSONObject(i) ?: continue
            val item = card()
            item.addView(text(plan.optString("name"), 17f, green, true))
            item.addView(space(6))
            addProfileField(item, "Plan duration", "${plan.optInt("duration_days")} days")
            addProfileField(item, "Total projected growth", "${plan.optDouble("total_growth_percent").toInt()}% simple growth")
            addProfileField(item, "Investment range", "R${moneyText(plan.optDouble("min_amount"))} – R${moneyText(plan.optDouble("max_amount"))}")
            if (admin) {
                addProfileField(item, "Availability", if (plan.optBoolean("enabled", true)) "ENABLED" else "DISABLED")
                item.addView(actionButton("EDIT PLAN", true) { showEditInvestmentPlanDialog(plan) })
            } else {
                item.addView(actionButton("SELECT PLAN", true) { showSelectInvestmentPlanDialog(plan) })
            }
            body.addView(item)
            body.addView(space(10))
        }
        if (admin) body.addView(actionButton("ADD NEW PLAN", false) { showEditInvestmentPlanDialog(JSONObject()) })
    }

    private fun moneyText(value: Double): String = String.format(Locale.US, "%,.2f", value)

    private fun showSelectInvestmentPlanDialog(plan: JSONObject) {
        val amountInput = EditText(this).apply {
            hint = "Investment amount (R1,000 – R5,000)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(ink)
        }
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(8), dp(22), 0) }
        wrap.addView(text("${plan.optString("name")} · ${plan.optInt("duration_days")} days", 14f, green, true))
        wrap.addView(space(8)); wrap.addView(amountInput)
        AlertDialog.Builder(this).setTitle("Select investment plan").setView(wrap)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("CONTINUE") { _, _ ->
                val amount = amountInput.text.toString().trim().toDoubleOrNull()
                if (amount == null) { toast("Enter a valid investment amount."); return@setPositiveButton }
                investmentRequest("select_plan", mapOf("plan_id" to plan.optString("plan_id"), "amount" to amount.toString())) {
                    toast(it.optString("message", "Plan selected.")); loadMyInvestments()
                }
            }.show()
    }

    private fun loadMyInvestments() {
        investmentRequest("profile") { result -> showMyInvestments(result.optJSONArray("investments") ?: JSONArray()) }
    }

    private fun showMyInvestments(investments: JSONArray) {
        val body = accountSubPage("MY INVESTMENTS", "Your selected plans and projected progress.")
        if (investments.length() == 0) body.addView(text("You have not selected an investment plan yet.", 14f, muted))
        for (i in 0 until investments.length()) {
            val inv = investments.optJSONObject(i) ?: continue
            val item = card()
            item.addView(text(inv.optString("plan_name"), 17f, green, true))
            addProfileField(item, "Status", friendlyInvestmentStatus(inv.optString("status")))
            addProfileField(item, "Invested amount", "R${moneyText(inv.optDouble("invested_amount"))}")
            addProfileField(item, "Plan duration", "${inv.optInt("duration_days")} days")
            addProfileField(item, "Investment date", inv.optString("investment_date", "Pending approval"))
            addProfileField(item, "Expiry date", inv.optString("expiry_date", "Pending approval"))
            addProfileField(item, "Daily projected growth", "R${moneyText(inv.optDouble("daily_growth_amount"))}")
            addProfileField(item, "Projected growth to date", "R${moneyText(inv.optDouble("accrued_projected_growth"))}")
            addProfileField(item, "Potential projected return", "R${moneyText(inv.optDouble("potential_return"))}")
            if (inv.optBoolean("withdrawal_available") && inv.optString("withdrawal_status").isBlank()) {
                item.addView(actionButton("REQUEST WITHDRAWAL REVIEW", false) { showWithdrawalRequestDialog(inv) })
            }
            body.addView(item); body.addView(space(10))
        }
    }

    private fun friendlyInvestmentStatus(status: String): String = when (status.uppercase(Locale.US)) {
        "PENDING_DEMO_DEPOSIT" -> "PENDING DEPOSIT REVIEW"
        "DEMO_APPROVED" -> "APPROVED · GROWTH ACTIVE"
        "PENDING_DEMO_REVIEW" -> "PENDING WITHDRAWAL REVIEW"
        "DEMO_APPROVED_NOT_PAID" -> "WITHDRAWAL REVIEW APPROVED"
        "DEMO_REJECTED" -> "WITHDRAWAL REVIEW REJECTED"
        else -> status.replace('_', ' ').uppercase(Locale.US)
    }

    private fun showWithdrawalRequestDialog(inv: JSONObject) {
        val amount = EditText(this).apply { hint = "Requested amount"; inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL; setTextColor(ink) }
        AlertDialog.Builder(this).setTitle("Request withdrawal review").setMessage("This submits a request for administrator review; it does not transfer money.")
            .setView(amount).setNegativeButton("CANCEL", null).setPositiveButton("SUBMIT") { _, _ ->
                val value = amount.text.toString().trim().toDoubleOrNull()
                if (value == null || value <= 0) { toast("Enter a valid amount."); return@setPositiveButton }
                investmentRequest("request_demo_withdrawal", mapOf("investment_id" to inv.optString("investment_id"), "amount" to value.toString())) {
                    toast(it.optString("message", "Request submitted.")); loadMyInvestments()
                }
            }.show()
    }

    private fun showEditInvestmentPlanDialog(plan: JSONObject) {
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        fun field(hint: String, value: String): EditText = EditText(this).apply { this.hint = hint; setText(value); setTextColor(ink); setSingleLine(true) }.also { fields.addView(it) }
        val id = field("Plan ID", plan.optString("plan_id"))
        val name = field("Plan name", plan.optString("name"))
        val days = field("Duration in days", plan.optString("duration_days"))
        val growth = field("Total projected growth (%)", plan.optString("total_growth_percent"))
        val min = field("Minimum amount", plan.optString("min_amount", "1000"))
        val max = field("Maximum amount", plan.optString("max_amount", "5000"))
        val enabled = android.widget.CheckBox(this).apply { text = "Plan enabled"; setTextColor(ink); isChecked = plan.optBoolean("enabled", true) }
        fields.addView(enabled)
        AlertDialog.Builder(this).setTitle(if (plan.optString("plan_id").isBlank()) "Add investment plan" else "Edit investment plan")
            .setView(fields).setNegativeButton("CANCEL", null).setPositiveButton("SAVE") { _, _ ->
                val values = mapOf("plan_id" to id.text.toString().trim(), "name" to name.text.toString().trim(),
                    "duration_days" to days.text.toString().trim(), "total_growth_percent" to growth.text.toString().trim(),
                    "min_amount" to min.text.toString().trim(), "max_amount" to max.text.toString().trim(), "enabled" to if (enabled.isChecked) "1" else "0")
                investmentRequest("admin_save_plan", values) { toast(it.optString("message", "Plan saved.")); loadInvestmentPlans(true) }
            }.show()
    }

    private fun loadAdminSubscriptions() {
        platformRequest("admin_users") { result ->
            val users = result.optJSONArray("users") ?: JSONArray()
            val body = accountSubPage("MANAGE SUBSCRIPTIONS", "Review subscription payments and subscription status.")
            var count = 0
            for (i in 0 until users.length()) {
                val user = users.optJSONObject(i) ?: continue
                val uid = user.optString("id")
                val proofName = user.optString("subscription_proof_name", user.optString("payment_proof_name"))
                val status = user.optString("subscription_status", "pending")
                val item = card()
                item.addView(text(user.optString("full_name", "User"), 16f, green, true))
                addProfileField(item, "Email", user.optString("email"))
                addProfileField(item, "Subscription", "R500")
                addProfileField(item, "Status", status.replace('_', ' ').uppercase(Locale.US))
                addProfileField(item, "Proof of payment", if (proofName.isBlank()) "Not uploaded" else proofName)
                if (proofName.isNotBlank()) {
                    item.addView(actionButton("VIEW SUBSCRIPTION PROOF", false) { adminViewPaymentProof(uid, "subscription") })
                    if (status != "approved") {
                        item.addView(actionButton("APPROVE SUBSCRIPTION", true) {
                            adminDecision("admin_subscription_decision", uid, "approve")
                        })
                        item.addView(actionButton("REJECT SUBSCRIPTION", false) {
                            adminDecision("admin_subscription_decision", uid, "reject")
                        })
                    }
                }
                body.addView(item)
                body.addView(space(8))
                count++
            }
            if (count == 0) body.addView(text("No registered accounts found.", 14f, muted))
        }
    }

    private fun loadAdminInvestments() {
        investmentRequest("admin_investments") { result -> showAdminInvestments(result.optJSONArray("investments") ?: JSONArray()) }
    }

    private fun showAdminInvestments(investments: JSONArray) {
        val body = accountSubPage("INVESTMENT REQUESTS", "Administrator review of selected plans and requests.")
        if (investments.length() == 0) body.addView(text("No investment requests found.", 14f, muted))
        for (i in 0 until investments.length()) {
            val inv = investments.optJSONObject(i) ?: continue
            val item = card()
            item.addView(text(inv.optString("plan_name"), 17f, green, true))
            addProfileField(item, "User", inv.optString("user_email"))
            addProfileField(item, "Amount", "R${moneyText(inv.optDouble("invested_amount"))}")
            addProfileField(item, "Status", friendlyInvestmentStatus(inv.optString("status")))
            if (inv.optString("status") == "PENDING_DEMO_DEPOSIT") {
                item.addView(actionButton("APPROVE DEPOSIT", true) {
                    investmentRequest("admin_demo_approve_deposit", mapOf("investment_id" to inv.optString("investment_id"))) {
                        toast(it.optString("message", "Deposit review updated.")); loadAdminInvestments()
                    }
                })
            }
            if (inv.optString("withdrawal_status") == "PENDING_DEMO_REVIEW") {
                item.addView(actionButton("APPROVE WITHDRAWAL REVIEW", true) { decideInvestmentWithdrawal(inv, "approve") })
                item.addView(actionButton("REJECT WITHDRAWAL REVIEW", false) { decideInvestmentWithdrawal(inv, "reject") })
            }
            body.addView(item); body.addView(space(10))
        }
    }

    private fun decideInvestmentWithdrawal(inv: JSONObject, decision: String) {
        investmentRequest("admin_demo_withdrawal_decision", mapOf("investment_id" to inv.optString("investment_id"), "decision" to decision)) {
            toast(it.optString("message", "Withdrawal review updated.")); loadAdminInvestments()
        }
    }

    private fun showAdminUserProfile(user: JSONObject) {
        val userId = user.optString("id")
        val body = accountSubPage("USER PROFILE", "Administrator controls for this registered user.")
        val panel = card()
        panel.addView(text(user.optString("full_name"), 17f, green, true))
        panel.addView(space(8))
        addProfileField(panel, "Email", user.optString("email"))
        addProfileField(panel, "Contact number", user.optString("phone"))
        addProfileField(panel, "Role", user.optString("role", "user").uppercase(Locale.US))
        addProfileField(panel, "Status", user.optString("status", "unknown").uppercase(Locale.US))
        addProfileField(panel, "Registered", user.optString("created_at"))
        addProfileField(panel, "Subscription proof", if (user.optString("subscription_proof_name", user.optString("payment_proof_name")).isNotBlank()) "Uploaded" else "Not uploaded")
        addProfileField(panel, "Investment proof", if (user.optString("investment_proof_name").isNotBlank()) "Uploaded" else "Not uploaded")
        addProfileField(panel, "Investment payment", user.optString("investment_payment_status", "pending").uppercase(Locale.US))
        addProfileField(panel, "Subscription", user.optString("subscription_status", "pending").uppercase(Locale.US))
        addProfileField(panel, "Subscription expiry", user.optString("subscription_expiry_date", "Not approved"))
        addProfileField(panel, "Investor plan", when(user.optString("investor_plan")) { "A" -> "Plan A · 15 days"; "B" -> "Plan B · 30 days"; else -> "Not selected" })
        addProfileField(panel, "Investor plan status", user.optString("investor_plan_status", "not_selected").replace('_',' ').uppercase(Locale.US))
        val planStartDate = user.optString("investor_plan_start_date").takeUnless { it.isBlank() || it == "null" } ?: "Not approved"
        val planExpiryDate = user.optString("investor_plan_expiry_date").takeUnless { it.isBlank() || it == "null" } ?: "Not approved"
        addProfileField(panel, "Investor plan start date", planStartDate)
        addProfileField(panel, "Investor plan expiry date", planExpiryDate)
        body.addView(panel)
        if (user.optString("subscription_proof_name", user.optString("payment_proof_name")).isNotBlank()) {
            body.addView(actionButton("VIEW SUBSCRIPTION PROOF", true) { adminViewPaymentProof(userId, "subscription") })
            body.addView(space(8))
            body.addView(actionButton("APPROVE SUBSCRIPTION", true) { adminDecision("admin_subscription_decision", userId, "approve") })
            body.addView(space(8))
            body.addView(actionButton("REJECT SUBSCRIPTION", false) { adminDecision("admin_subscription_decision", userId, "reject") })
        }
        if (user.optString("investment_proof_name").isNotBlank()) {
            body.addView(space(8))
            body.addView(actionButton("VIEW INVESTMENT PROOF", true) { adminViewPaymentProof(userId, "investment") })
            body.addView(space(8))
            body.addView(actionButton("APPROVE INVESTMENT PAYMENT", true) { adminDecision("admin_investment_payment_decision", userId, "approve") })
            body.addView(space(8))
            body.addView(actionButton("REJECT INVESTMENT PAYMENT", false) { adminDecision("admin_investment_payment_decision", userId, "reject") })
        }
        if (user.optString("role").equals("investor", true) && user.optString("investor_plan_status") == "pending") {
            body.addView(space(8))
            body.addView(actionButton("APPROVE INVESTOR PLAN", true) { adminDecision("admin_investor_plan_decision", userId, "approve") })
            body.addView(space(8))
            body.addView(actionButton("REJECT INVESTOR PLAN", false) { adminDecision("admin_investor_plan_decision", userId, "reject") })
        }
        body.addView(space(12))
        body.addView(actionButton("EDIT USER PROFILE", true) { showEditUserDialog(user) })
        body.addView(space(8))
        body.addView(actionButton("CHANGE ACCOUNT TYPE", false) {
            val currentRole = user.optString("role", "user").lowercase(Locale.US)
            val nextRole = if (currentRole == "investor") "user" else "investor"
            AlertDialog.Builder(this)
                .setTitle("Change account type?")
                .setMessage("Change ${user.optString("full_name")} from ${currentRole.uppercase(Locale.US)} to ${nextRole.uppercase(Locale.US)}? The existing login and account history will be retained.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("CHANGE") { _, _ ->
                    platformRequest("admin_change_role", mapOf("user_id" to userId, "role" to nextRole)) {
                        toast(it.optString("message", "Account type updated."))
                        openAdminDashboard()
                    }
                }.show()
        })
        body.addView(space(8))

        body.addView(space(8))
        when (user.optString("status", "pending").lowercase(Locale.US)) {
            "pending" -> body.addView(actionButton("APPROVE USER", true) { adminUserAction("admin_approve_user", userId) })
            "suspended" -> body.addView(actionButton("REACTIVATE USER", true) { adminUserAction("admin_reactivate_user", userId) })
            else -> body.addView(actionButton("SUSPEND USER", false) {
                AlertDialog.Builder(this).setTitle("Suspend user?")
                    .setMessage("This user will no longer be able to sign in.")
                    .setNegativeButton("CANCEL", null)
                    .setPositiveButton("SUSPEND") { _, _ -> adminUserAction("admin_suspend_user", userId) }.show()
            })
        }
        body.addView(space(8))
        body.addView(actionButton("DELETE USER", false) {
            AlertDialog.Builder(this).setTitle("Delete user?")
                .setMessage("This permanently deletes this user account. This action cannot be undone.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("DELETE") { _, _ -> adminUserAction("admin_delete_user", userId) }.show()
        })
    }

    private fun adminDecision(action: String, userId: String, decision: String) {
        platformRequest(action, mapOf("user_id" to userId, "decision" to decision)) {
            toast(it.optString("message", "Decision saved."))
            openAdminDashboard()
        }
    }

    private fun adminViewPaymentProof(userId: String, proofType: String) {
        platformRequest("admin_get_payment_proof", mapOf("user_id" to userId, "proof_type" to proofType)) { result ->
            try {
                val bytes = android.util.Base64.decode(result.optString("content"), android.util.Base64.DEFAULT)
                val mime = result.optString("mime", "application/octet-stream")
                val extension = when (mime) { "application/pdf" -> "pdf"; "image/jpeg" -> "jpg"; "image/png" -> "png"; else -> "bin" }
                val file = java.io.File(cacheDir, "payment-proof-$userId.$extension")
                file.writeBytes(bytes)
                val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(view, "View proof of payment"))
            } catch (_: Exception) { toast("No application is available to open this proof of payment.") }
        }
    }

    private fun adminUserAction(action: String, userId: String) {
        platformRequest(action, mapOf("user_id" to userId)) {
            toast(it.optString("message", "User account updated."))
            openAdminDashboard()
        }
    }

    private fun showEditUserDialog(user: JSONObject) {
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        fun edit(hint: String, value: String): EditText = EditText(this).apply {
            this.hint = hint; setText(value); setTextColor(ink); setSingleLine(true)
        }.also { fields.addView(it) }
        val first = edit("First name", user.optString("first_name"))
        val surname = edit("Surname", user.optString("surname"))
        val email = edit("Email address", user.optString("email"))
        val phone = edit("Contact number", user.optString("phone"))
        val password = edit("New password (leave blank to keep current)", "")
        password.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        AlertDialog.Builder(this).setTitle("Edit user profile").setView(fields)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("SAVE") { _, _ ->
                val values = mapOf(
                    "user_id" to user.optString("id"), "first_name" to first.text.toString().trim(),
                    "surname" to surname.text.toString().trim(), "email" to email.text.toString().trim(),
                    "phone" to phone.text.toString().trim(), "password" to password.text.toString()
                )
                platformRequest("admin_edit_user", values) {
                    toast(it.optString("message", "User details updated."))
                    openAdminDashboard()
                }
            }.show()
    }

    private fun autoTradingButton(): View {
        val button = actionButton(
            if (autoTradingEnabled) "AUTO TRADING: ON" else "AUTO TRADING: OFF",
            primary = autoTradingEnabled
        ) { toggleAutoTrading() }
        button.setTextColor(Color.BLACK)
        button.background = rounded(
            if (autoTradingEnabled) green else red,
            if (autoTradingEnabled) green else red,
            10
        )
        return button
    }

    private fun toggleAutoTrading() {
        autoTradingEnabled = !autoTradingEnabled
        settingsPrefs.edit().putBoolean("auto_trading_enabled", autoTradingEnabled).apply()
        addNotification(
            "Settings",
            "Auto trading ${if (autoTradingEnabled) "enabled" else "disabled"}",
            if (autoTradingEnabled) "Automatic original entries are enabled. Existing contracts remain managed by Deriv."
            else "New automatic entries are paused. Existing contracts remain open until manually closed or they expire."
        )
        if (autoTradingEnabled && accountConnected) {
            indexes.forEach { symbol ->
                m15CandlesByIndex[symbol]?.let { evaluateRecoverySignals(symbol, it) }
                latestPrices[symbol]?.let { evaluateOriginalEntries(symbol, it) }
            }
        }
        render()
    }

    private fun showSettings() {
        pageHeading("SETTINGS", "Application preferences.")
        val c = card()
        c.addView(text("APPLICATION", 15f, gold, true))
        c.addView(space(8))
        c.addView(settingRow("Interface", if (darkInterface) "Dark" else "Light") {
            showInterfaceDialog()
        })
        c.addView(divider())
        c.addView(settingRow("Auto trading", if (autoTradingEnabled) "ON" else "OFF") {
            toggleAutoTrading()
        })
        c.addView(divider())
        c.addView(marketIndexSettingRow())
        c.addView(divider())
        c.addView(settingRow("Market feed", if (marketFeedConnected) "Connected" else "Connecting"))
        c.addView(divider())
        c.addView(settingRow("Trading account", "$executionMode · " + if (accountConnected) "Connected" else "Not connected"))
        content.addView(c)
    }

    private fun marketIndexSettingRow(): View {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        wrapper.addView(text("Market indexes", 14f, green, true))
        val indexRow = horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
        indexes.forEach { symbol ->
            val item = text(displayIndexName(symbol), 10f, indexNameColor(symbol), true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(2), dp(7), dp(2), dp(7))
            }
            indexRow.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
        }
        wrapper.addView(indexRow)
        return wrapper
    }

    private fun updateConnectionLabels(message: String) {
    if (selectedTab == "Home") {
        val isLive = marketFeedConnected
        feedPillView?.apply {
            text = if (isLive) "LIVE" else "CONNECTING"
            setTextColor(if (isLive) Color.BLACK else amber)
            background = rounded(
                if (isLive) green else Color.WHITE,
                if (isLive) green else amber,
                20
            )
        }
    }
    }

    private fun renderIfHome() {
        if (selectedTab == "Home") render()
    }

    private fun pageHeading(title: String, subtitle: String) {
        content.addView(text(title, 21f, gold, true))
        content.addView(text(subtitle, 13f, Color.WHITE))
        content.addView(space(18))
    }

    private fun settingRow(label: String, value: String, action: (() -> Unit)? = null): View {
        val row = horizontal()
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(12), 0, dp(12))
        row.addView(text(label, 14f, green, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        val valueColor = if (label == "Trading account") {
            if (executionMode == "REAL") blue else red
        } else Color.WHITE
        row.addView(text(value, 13f, valueColor))
        if (action != null) {
            row.isClickable = true
            row.setOnClickListener { action() }
            val selectable = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, selectable, true)
            if (selectable.resourceId != 0) {
                row.setBackgroundResource(selectable.resourceId)
            }
        }
        return row
    }

    private fun showInterfaceDialog() {
        val options = arrayOf("Light", "Dark")
        AlertDialog.Builder(this)
            .setTitle("Choose interface")
            .setSingleChoiceItems(options, if (darkInterface) 1 else 0) { dialog, which ->
                darkInterface = which == 1
                settingsPrefs.edit().putBoolean("dark_interface", darkInterface).apply()
                dialog.dismiss()
                window.statusBarColor = pageColor
                window.navigationBarColor = if (darkInterface) pageColor else Color.WHITE
                window.decorView.systemUiVisibility = if (darkInterface) 0 else
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                render()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun bottomNavigation(): View {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(if (darkInterface) Color.rgb(0, 7, 5) else Color.WHITE)
            setPadding(dp(6), dp(8), dp(6), dp(8))
            elevation = dp(8).toFloat()
        }
        val tabs = if (platformUserRole == "investor") listOf("Trade", "Accounts", "Profile") else listOf("Home", "Trade", "Accounts", "Settings")
        tabs.forEach { tab ->
            val item = TextView(this).apply {
                text = tab
                textSize = 12f
                gravity = Gravity.CENTER
                setTypeface(null, if (selectedTab == tab) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (selectedTab == tab) green else gold)
                setPadding(dp(4), dp(8), dp(4), dp(8))
                setOnClickListener { openTab(tab) }
            }
            nav.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
        }
        return nav
    }

    private fun openTab(tab: String) {
        if (platformUserRole == "investor" && tab !in listOf("Trade", "Accounts", "Profile")) {
            toast("Investor access is limited to Trade and Accounts.")
            return
        }
        selectedTab = tab
        render()
    }

    private fun card(accent: Int? = null): LinearLayout {
        val fill = if (darkInterface && accent != null) {
            val r = Color.red(accent) / 10
            val g = Color.green(accent) / 10
            val b = Color.blue(accent) / 10
            Color.rgb(5 + r, 12 + g, 12 + b)
        } else cardColor
        val stroke = accent ?: lineColor
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(fill, stroke, 14)
            elevation = dp(1).toFloat()
        }
    }

    private fun actionButton(label: String, primary: Boolean = true, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            setTextColor(if (primary) Color.WHITE else green)
            background = rounded(if (primary) green else if (darkInterface) Color.rgb(8, 42, 28) else Color.rgb(235, 247, 241),
                if (primary) green else if (darkInterface) lineColor else Color.rgb(210, 235, 222), 10)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { action() }
        }
    }

    private fun pill(label: String, color: Int): TextView {
    val isLive = color == green
    return TextView(this).apply {
        text = label
        textSize = 10f
        setTypeface(null, Typeface.BOLD)
        setTextColor(if (isLive) Color.BLACK else color)
        setPadding(dp(9), dp(5), dp(9), dp(5))
        background = rounded(
            if (isLive) green else Color.WHITE,
            if (isLive) green else color,
            20
        )
    }
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(
    color
)
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


    private fun indexNameColor(symbol: String): Int = when (symbol) {
        "R_10" -> blue
        "R_25" -> green
        "R_50" -> gold
        "R_75" -> red
        "R_100" -> Color.rgb(156, 39, 176)
        else -> ink
    }

    private fun displayIndexName(symbol: String): String {
        return when (symbol) {
            "R_10" -> "VIX 10"
            "R_25" -> "VIX 25"
            "R_50" -> "VIX 50"
            "R_75" -> "VIX 75"
            "R_100" -> "VIX 100"
            else -> symbol
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

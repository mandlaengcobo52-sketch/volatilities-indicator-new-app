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
    private val lineColor: Int get() = if (darkInterface) Color.rgb(25, 91, 62) else Color.rgb(190, 225, 205)

    private lateinit var content: LinearLayout
    private var selectedTab = "Home"
    private var renderedTab = ""
    private var pageScrollView: ScrollView? = null
    private val scrollPositions = mutableMapOf<String, Int>()
    private val settingsPrefs by lazy { getSharedPreferences("vi_settings", MODE_PRIVATE) }
    // Auto trading defaults ON to preserve the existing automatic execution behaviour.
    // OFF pauses new original/recovery entries but does not affect open contracts.
    private var autoTradingEnabled = true

    private val indexes = listOf("R_10", "R_25", "R_50", "R_75", "R_100")
    private val latestPrices = mutableMapOf<String, Double>()
    private val priceViews = mutableMapOf<String, TextView>()
    private val indexStatusViews = mutableMapOf<String, TextView>()
    private val marketStatusViews = mutableMapOf<String, TextView>()
    private val masterDecisionViews = mutableMapOf<String, TextView>()
    private val levelStatusViews = mutableMapOf<String, TextView>()
    private val highLevelViews = mutableMapOf<String, TextView>()
    private val lowLevelViews = mutableMapOf<String, TextView>()
    private val recoveryStatusViews = mutableMapOf<String, TextView>()
    private val mssStatusViews = mutableMapOf<String, TextView>()
    private val chochStatusViews = mutableMapOf<String, TextView>()

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
        val role: String,
        val stake: Double,
        val originalId: String = "",
        var contractId: Long = 0L,
        var profit: Double = 0.0,
        var status: String = "PROPOSAL",
        var canSell: Boolean = false,
        var recoveryEligible: Boolean = false,
        var recoveryUsed: Boolean = false,
        var closedAt: Long = 0L
    )

    private val positions = linkedMapOf<String, Position>()
    private val pendingRequests = mutableMapOf<Int, String>()
    private val dailyLevels = mutableMapOf<String, Pair<Double, Double>>() // high, low
    private val candlesByIndex = mutableMapOf<String, List<DerivApiClient.Candle>>()
    private val originalTriggered = mutableSetOf<String>()
    private val recoveryWaiting = mutableSetOf<String>()
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
    private val insufficientFundsNotified = mutableSetOf<String>()

    private val tradePrefs by lazy { getSharedPreferences("vi_trade_engine", MODE_PRIVATE) }

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
                if (!activeAccountId.isNullOrBlank()) {
                    accountConnected = true
                    executionMode = service.client.getAccountType()
                    accountLabel = "$activeAccountId ($executionMode)"
                    service.client.requestBalance()
                    service.client.requestOpenTrades()
                    runOnUiThread {
                        accountStatusView?.text = "CONNECTED"
                        accountStatusView?.setTextColor(green)
                        accountIdView?.text = accountLabel
                        render()
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
        window.statusBarColor = pageColor
        window.navigationBarColor = if (darkInterface) Color.rgb(19, 25, 33) else Color.WHITE
        window.decorView.systemUiVisibility = if (darkInterface) 0 else
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        tradingDayKey = tradePrefs.getString("day_key", "").orEmpty()
        dailyCapital = tradePrefs.getFloat("daily_capital", 0f).toDouble()
        loadPositions()
        indexes.forEach { symbol ->
            tradePrefs.getString("signal_mss_$symbol", null)?.let { recoveryMss[symbol] = it }
            tradePrefs.getString("signal_choch_$symbol", null)?.let { recoveryChoch[symbol] = it }
            val mssEpoch = tradePrefs.getLong("signal_mss_epoch_$symbol", 0L)
            val chochEpoch = tradePrefs.getLong("signal_choch_epoch_$symbol", 0L)
            if (mssEpoch > 0L) recoveryMssEpoch[symbol] = mssEpoch
            if (chochEpoch > 0L) recoveryChochEpoch[symbol] = chochEpoch
        }
        loadNotifications()
        originalTriggered.addAll(tradePrefs.getStringSet("triggered_$tradingDayKey", emptySet()) ?: emptySet())
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
                        showDerivRequiredScreen()
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

    private fun submitPlatformAuth(isRegister: Boolean, fields: List<EditText>, status: TextView, submit: Button) {
        if (platformAuthBusy) return
        val values = fields.map { it.text.toString().trim() }
        val body = FormBody.Builder().add("action", if (isRegister) "register" else "login")
        if (isRegister) {
            if (values.size != 6 || values.any { it.isBlank() }) { status.text = "Please complete every field."; return }
            body.add("first_name", values[0]).add("surname", values[1]).add("email", values[2])
                .add("phone", values[3]).add("password", values[4]).add("password_confirm", values[5])
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
                            if (platformAuthenticated) showDerivRequiredScreen() else status.text = "Login did not return a valid session."
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
            setTextColor(Color.rgb(0, 230, 118))
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
                setStroke(dp(2), Color.rgb(0, 230, 118))
            }
        }
        panel.addView(TextView(this).apply {
            text = if (register) "CREATE ACCOUNT" else "ACCOUNT LOGIN"
            textSize = 21f
            setTextColor(Color.rgb(0, 230, 118))
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
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(0, 230, 118))
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
                    setTextColor(Color.rgb(0, 230, 118))
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
            background = rounded(Color.rgb(0, 230, 118), Color.rgb(0, 230, 118), 10)
            setOnClickListener { submitPlatformAuth(register, fields, status, this) }
        }
        panel.addView(submit, LinearLayout.LayoutParams(-1, dp(54)).apply {
            topMargin = dp(10)
        })

        panel.addView(TextView(this).apply {
            text = if (register) "Already registered? Login" else "New user? Register here"
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(0, 230, 118))
            setPadding(0, dp(20), 0, dp(8))
            textSize = 16f
            setOnClickListener { showPlatformAuthScreen(!register) }
        })

        page.addView(panel, LinearLayout.LayoutParams(-1, -2))
        scroll.addView(page)
        root.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    private fun showDerivRequiredScreen() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(24), dp(24), dp(24), dp(24)); setBackgroundColor(pageColor) }
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(24), dp(20), dp(24)); background = GradientDrawable().apply { setColor(cardColor); setCornerRadius(dp(16).toFloat()); setStroke(dp(1), lineColor) } }
        panel.addView(TextView(this).apply { text = "PLATFORM ACCOUNT APPROVED"; textSize = 19f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(green) })
        panel.addView(TextView(this).apply { text = "Welcome${if (platformUserName.isNotBlank()) ", $platformUserName" else ""}. Connect your Deriv account to unlock the application."; textSize = 14f; gravity = Gravity.CENTER; setTextColor(ink); setPadding(0, dp(16), 0, dp(16)) })
        panel.addView(Button(this).apply { text = "CONNECT DERIV ACCOUNT"; setOnClickListener { startMarketFeedService(); showAccountModeDialog() } }, LinearLayout.LayoutParams(-1, dp(52)))
        panel.addView(TextView(this).apply { text = "Sign out"; gravity = Gravity.CENTER; setTextColor(red); setPadding(0, dp(18), 0, dp(8)); setOnClickListener { signOutPlatform() } })
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
        val previousMode = executionMode
        executionMode = if (prefs.getString("oauth_mode", executionMode).equals("REAL", true)) "REAL" else "DEMO"
        if (previousMode != executionMode && ::content.isInitialized && accountConnected) render()
        val tokenKey = if (executionMode == "REAL") "real_token" else "demo_token"
        val savedToken = prefs.getString(tokenKey, "").orEmpty()
        if (savedToken.isNotBlank() && derivService != null &&
            derivService?.client?.isConnected() != true &&
            derivService?.client?.isConnecting() != true
        ) {
            derivService?.client?.ensureConnectedOnResume(savedToken, executionMode)
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
        accountConnected = true
        executionMode = if (accountType.equals("REAL", true)) "REAL" else "DEMO"
        accountLabel = "$accountId ($accountType)"
        addNotification("Accounts", "Deriv account connected", "$accountLabel is connected.")
        derivService?.client?.requestOpenTrades()
        // Re-check already-qualified MSS/CHOCH against any waiting closed original
        // immediately after the execution account reconnects.
        candlesByIndex.forEach { (symbol, candles) -> evaluateRecoverySignals(symbol, candles) }
        runOnUiThread {
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
        latestPrices[symbol] = quote
        marketFeedConnected = true
        if (accountConnected) evaluateOriginalEntries(symbol, quote)
        runOnUiThread {
            feedPillView?.text = "LIVE"
            feedPillView?.setTextColor(green)
            priceViews[symbol]?.text = formatPrice(quote)
            indexStatusViews[symbol]?.apply {
                text = "LIVE"
                setTextColor(green)
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
            evaluateRecoverySignals(symbol, candles)
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
                updateMasterDecisionViews(symbol)
                updateRecoveryStatusView(symbol)
            }
            if (selectedTab == "Home") runOnUiThread { updateMasterDecisionViews(symbol); updateRecoveryStatusView(symbol) }
        }
    }

    override fun onMarketDataError(symbol: String, message: String) {
        runOnUiThread {
            indexStatusViews[symbol]?.apply {
                text = "FEED ERROR"
                setTextColor(red)
            }
            marketStatusViews[symbol]?.text = message
            addNotification("Home", "Market data error · ${displayIndexName(symbol)}", message)
            updateConnectionLabels("Market data error: $symbol")
        }
    }

    override fun onError(message: String) {
        runOnUiThread {
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
                    failedPosition.status = if (failedPosition.contractId > 0L) "OPEN" else "ERROR: ${error.optString("message", "Deriv request failed")}"
                    addNotification("Trade", "${displayIndexName(failedPosition.symbol)} trade request failed", error.optString("message", "Deriv request failed"))
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
                position.status = "OPEN"
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
                closingPosition.status = "CLOSING"
                savePositions()
                runOnUiThread { if (selectedTab == "Trade") render() }
            }
            "proposal_open_contract" -> handleOpenContract(json.optJSONObject("proposal_open_contract") ?: return)
            "portfolio" -> {
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

                    if (existing != null) {
                        existing.direction = direction
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
                        restored.status = "OPEN"
                        positions[restored.localId] = restored
                    }

                    // The contract must exist locally before its updates arrive.
                    subscribePosition(id)
                }

                savePositions()

                runOnUiThread {
                    updateAccountProfitLossViews()

                    indexes.forEach { symbol ->
                        updateMasterDecisionViews(symbol)
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
            selectedTab = page
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
        val levels = dailyLevels[symbol]
            ?: return "Waiting for completed daily candles to establish the previous-day reference high and low."
        if (!accountConnected) {
            return "Reference levels are available. Waiting for Deriv account connection before entry conditions can be evaluated."
        }
        val price = latestPrices[symbol] ?: return "Waiting for live market price data."
        return when {
            price >= levels.first -> "Price is at or above the previous-day high. The high-side entry condition is being evaluated."
            price <= levels.second -> "Price is at or below the previous-day low. The low-side entry condition is being evaluated."
            else -> "Waiting for price to reach the previous-day high (${formatPrice(levels.first)}) or low (${formatPrice(levels.second)})."
        }
    }

    private fun recoveryStatusText(symbol: String): String {
        val eligibleWinner = positions.values.firstOrNull {
            it.symbol == symbol && it.role == "ORIGINAL" && it.status == "CLOSED" &&
                it.recoveryEligible && !it.recoveryUsed
        }
        if (eligibleWinner == null) {
            val originalRunning = positions.values.any {
                it.symbol == symbol && it.role == "ORIGINAL" &&
                    it.status !in setOf("CLOSED", "ERROR", "BUY FAILED", "PROPOSAL FAILED") &&
                    !it.status.startsWith("ERROR")
            }
            return if (originalRunning) {
                "MSS and CHOCH qualifications are monitored independently. Recovery waits for one original contract to close with positive realised profit; the opposing leg remains open."
            } else {
                "No qualifying winning original closure. Recovery is not eligible."
            }
        }
        val pairOriginals = positions.values.filter {
            it.symbol == symbol && it.role == "ORIGINAL" && it.originalId == eligibleWinner.originalId
        }
        val oppositeOriginal = pairOriginals.firstOrNull { it.localId != eligibleWinner.localId }
        val oppositeActive = oppositeOriginal != null && oppositeOriginal.status !in
            setOf("CLOSED", "ERROR", "BUY FAILED", "PROPOSAL FAILED") &&
            !oppositeOriginal.status.startsWith("ERROR")
        if (!oppositeActive) {
            return "Winning leg qualified, but the opposing original leg is not running. Recovery is held; the app will not close or replace that leg."
        }
        val mss = recoveryMss[symbol]
        val choch = recoveryChoch[symbol]
        if (!autoTradingEnabled && !mss.isNullOrBlank() && mss == choch) {
            return "MSS and CHOCH qualify ${mss.lowercase(Locale.US)}. Recovery is waiting because Auto Trading is OFF."
        }
        return when {
            mss.isNullOrBlank() && choch.isNullOrBlank() -> "A profitable original contract closed. Waiting for MSS and CHOCH."
            !mss.isNullOrBlank() && choch.isNullOrBlank() -> "Profitable original closed. MSS confirmed ${mss.lowercase(Locale.US)}. Waiting for CHOCH."
            mss.isNullOrBlank() && !choch.isNullOrBlank() -> "Profitable original closed. CHOCH confirmed ${choch.lowercase(Locale.US)}. Waiting for MSS."
            mss == choch -> "Profitable original closed. MSS and CHOCH agree (${mss.orEmpty().lowercase(Locale.US)}). Recovery is eligible."
            else -> "Profitable original closed, but MSS and CHOCH disagree. Waiting for matching confirmation."
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
        recoveryStatusViews[symbol]?.text = recoveryStatusText(symbol)
        mssStatusViews[symbol]?.apply {
            text = signalQualificationText(recoveryMss[symbol])
            setTextColor(if (recoveryMss[symbol].isNullOrBlank()) amber else green)
        }
        chochStatusViews[symbol]?.apply {
            text = signalQualificationText(recoveryChoch[symbol])
            setTextColor(if (recoveryChoch[symbol].isNullOrBlank()) amber else green)
        }
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
                addNotification(
                    "Home",
                    "${displayIndexName(symbol)} reference levels updated",
                    "Previous completed-day high: ${formatPrice(high)}; low: ${formatPrice(low)}."
                )
            }
        }
    }

    // A RISE/FALL pair is one global execution slot. A recovery remains in
    // the same slot as the original pair that qualified it.
    private fun tradeGroupId(position: Position): String {
        if (position.role == "RECOVERY") {
            val parent = positions[position.originalId]
            return parent?.originalId?.takeIf { it.isNotBlank() } ?: position.originalId
        }
        return position.originalId.ifBlank { position.localId }
    }

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

    // A profitable closed original leg reserves its stake plus realised profit
    // while its paired original leg remains active and recovery is still unused.
    private fun reservedRecoveryCapital(): Double {
        return positions.values
            .filter {
                it.role == "ORIGINAL" && it.status == "CLOSED" &&
                    it.recoveryEligible && !it.recoveryUsed && it.profit > 0.0
            }
            .filter { winner ->
                val pair = positions.values.filter {
                    it.role == "ORIGINAL" && it.originalId == winner.originalId
                }
                val opposite = pair.firstOrNull { it.localId != winner.localId }
                opposite != null && isActiveTradePosition(opposite)
            }
            .filterNot { winner ->
                positions.values.any {
                    it.role == "RECOVERY" && it.originalId == winner.localId
                }
            }
            .sumOf { roundStake(it.stake + it.profit) }
    }

    private fun evaluateOriginalEntries(symbol: String, quote: Double) {
        if (!autoTradingEnabled || !accountConnected || dailyCapital <= 0.0 || !quote.isFinite()) return
        val levels = dailyLevels[symbol] ?: return
        val today = utcDayKey(System.currentTimeMillis() / 1000L)
        val pairKey = "$today|$symbol|PAIR"
        val legacyRiseKey = "$today|$symbol|RISE"
        val legacyFallKey = "$today|$symbol|FALL"
        if (pairKey in originalTriggered || legacyRiseKey in originalTriggered || legacyFallKey in originalTriggered) return
        // Never start another original pair on an index while any leg or recovery on
        // that same index is still active. The opposite original leg may remain open
        // while its paired recovery runs; both must finish before a fresh pair can start.
        val indexAlreadyActive = positions.values.any {
            it.symbol == symbol && it.status != "CLOSED" &&
                !it.status.startsWith("ERROR") && it.status !in setOf("BUY FAILED", "PROPOSAL FAILED")
        }
        if (indexAlreadyActive) return
        // Global limit: at most two active trade groups across every index.
        if (activeTradeGroupIds().size >= 2) return

        // A single qualifying boundary starts the agreed simultaneous RISE/FALL pair.
        val boundaryReached = quote <= levels.second || quote >= levels.first
        if (!boundaryReached) return

        val stake = roundStake(dailyCapital * 0.25)
        val requiredForPair = roundStake(stake * 2.0)
        val unreservedBalance = (availableBalance - reservedRecoveryCapital()).coerceAtLeast(0.0)
        if (unreservedBalance + 0.000001 < requiredForPair) {
            if (insufficientFundsNotified.add(pairKey)) {
                addNotification("Trade", "${displayIndexName(symbol)} pair not executed", "Available balance after recovery reservations ${formatMoney(unreservedBalance)} is below the combined RISE and FALL stake ${formatMoney(requiredForPair)}. Neither contract was submitted.")
            }
            return
        }
        val pairId = "PAIR-${today}-${symbol}-${UUID.randomUUID()}"
        originalTriggered.add(pairKey)
        originalTriggered.add(legacyRiseKey)
        originalTriggered.add(legacyFallKey)
        tradePrefs.edit().putStringSet("triggered_$today", originalTriggered.toSet()).apply()
        openPosition(symbol, "RISE", "ORIGINAL", stake, pairId)
        openPosition(symbol, "FALL", "ORIGINAL", stake, pairId)
        addNotification("Trade", "${displayIndexName(symbol)} original pair opened", "RISE and FALL opened together with the same stake of ${formatMoney(stake)} each.")
    }

    private fun roundStake(value: Double): Double =
        BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toDouble()

    private fun openPosition(symbol: String, direction: String, role: String, stake: Double, originalId: String = "") {
        val roundedStake = roundStake(stake)
        if (!accountConnected || !roundedStake.isFinite() || roundedStake <= 0.0) return
        val localId = UUID.randomUUID().toString()
        val requestedGroupId = if (role == "RECOVERY") {
            positions[originalId]?.originalId?.takeIf { it.isNotBlank() } ?: originalId
        } else {
            originalId.ifBlank { localId }
        }
        // Existing pair legs and their recovery share a slot; a new pair cannot
        // enter when two other groups already occupy the global capacity.
        val activeGroups = activeTradeGroupIds()
        if (requestedGroupId !in activeGroups && activeGroups.size >= 2) {
            addNotification("Trade", "${displayIndexName(symbol)} execution held", "The global limit of two running trade pairs has been reached. This execution must wait.")
            return
        }
        val p = Position(localId, symbol, direction, role, roundedStake, originalId)
        positions[localId] = p
        val req = nextRequestId()
        pendingRequests[req] = localId
        // Deriv contract types: CALL = Rise, PUT = Fall.
        val contractType = if (direction == "RISE") "CALL" else "PUT"
        val proposal = JSONObject()
            .put("proposal", 1)
            .put("amount", roundStake(roundedStake))
            .put("basis", "stake")
            .put("contract_type", contractType)
            .put("currency", accountCurrency)
            .put("duration", 3)
            .put("duration_unit", "d")
            .put("underlying_symbol", symbol)
            .put("req_id", req)
        p.status = "REQUESTING PROPOSAL"
        if (role == "RECOVERY") {
            addNotification("Trade", "${displayIndexName(symbol)} recovery setup", "Recovery ${direction.lowercase(Locale.US)} proposal requested after the existing recovery conditions aligned.")
        }
        derivService?.client?.send(proposal)
        savePositions()
        runOnUiThread { if (selectedTab == "Trade") render() }
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
            restored.status = "OPEN"
            positions[restored.localId] = restored
            foundPosition = restored
            wasRestored = true
        }

        val position = foundPosition ?: return
        val wasAlreadyClosed = position.status == "CLOSED"

        when (contract.optString("contract_type").uppercase(Locale.US)) {
            "CALL" -> position.direction = "RISE"
            "PUT" -> position.direction = "FALL"
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
            position.closedAt = contract.optLong("sell_time", 0L).takeIf { it > 0L }
                ?: contract.optLong("date_expiry", 0L).takeIf { it > 0L }
                ?: System.currentTimeMillis() / 1000L
            position.recoveryEligible = position.role == "ORIGINAL" && position.profit > 0.0
            if (position.recoveryEligible && !position.recoveryUsed) recoveryWaiting.add(position.localId)
            if (!wasAlreadyClosed) {
                addNotification("Trade", "${displayIndexName(position.symbol)} trade closed", "${position.role} ${position.direction} final P/L: ${formatMoney(position.profit)}. ${if (position.recoveryEligible) "Recovery may proceed after matching MSS and CHOCH." else "No recovery qualification from this closure."}")
            }
        } else if (position.status != "CLOSING") {
            position.status = "OPEN"
        }
                // AUTOMATIC PROFIT CLOSING: 30% OF ORIGINAL STAKE
        if (
            !isSold &&
            position.status == "OPEN" &&
            position.contractId > 0L &&
            position.canSell &&
            position.profit >= roundStake(position.stake * 0.30)
        ) {
            runOnUiThread {
                val currentPosition = positions[position.localId]

                if (
                    currentPosition != null &&
                    currentPosition.status == "OPEN" &&
                    currentPosition.canSell &&
                    currentPosition.profit >= roundStake(currentPosition.stake * 0.30)
                ) {
                    closeRunningPosition(currentPosition)
                }
            }
        }

        savePositions()
        runOnUiThread {
            updateMasterDecisionViews(position.symbol)
            updateRecoveryStatusView(position.symbol)
            updateTradePositionViews(position)
            if (selectedTab == "Trade" && (wasRestored || (!wasAlreadyClosed && position.status == "CLOSED"))) {
                render()
            }
            updateAccountProfitLossViews()
        }

        if (position.recoveryEligible && !position.recoveryUsed) {
            candlesByIndex[position.symbol]?.let { evaluateRecoverySignals(position.symbol, it) }
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
        if (candles.size < 5) return
        val closed = candles.sortedBy { it.epoch }.dropLast(1)
        if (closed.size < 4) return
        val last = closed.last()
        val prior = closed.dropLast(1)
        val swingHighs = mutableListOf<Double>()
        val swingLows = mutableListOf<Double>()
        for (i in 1 until prior.lastIndex) {
            if (prior[i].high > prior[i - 1].high && prior[i].high > prior[i + 1].high) swingHighs.add(prior[i].high)
            if (prior[i].low < prior[i - 1].low && prior[i].low < prior[i + 1].low) swingLows.add(prior[i].low)
        }
        val lastHigh = swingHighs.lastOrNull()
        val lastLow = swingLows.lastOrNull()
        if (lastHigh == null || lastLow == null) return

        val mssDirection = when {
            last.close > lastHigh -> "RISE"
            last.close < lastLow -> "FALL"
            else -> ""
        }
        if (mssDirection.isNotBlank()) {
            recoveryMssEpoch[symbol] = last.epoch
            tradePrefs.edit().putLong("signal_mss_epoch_$symbol", last.epoch).apply()
            if (recoveryMss[symbol] != mssDirection) {
                recoveryMss[symbol] = mssDirection
                tradePrefs.edit().putString("signal_mss_$symbol", mssDirection).apply()
                addNotification("Home", "${displayIndexName(symbol)} MSS qualified", "MSS qualified in the ${mssDirection.lowercase(Locale.US)} direction on a completed candle.")
            }
        }

        // Preserve the existing CHOCH qualification rule; its display is independent of recovery.
        val chochDirection = when {
            last.close > lastHigh && last.close > last.open -> "RISE"
            last.close < lastLow && last.close < last.open -> "FALL"
            else -> ""
        }
        if (chochDirection.isNotBlank()) {
            recoveryChochEpoch[symbol] = last.epoch
            tradePrefs.edit().putLong("signal_choch_epoch_$symbol", last.epoch).apply()
            if (recoveryChoch[symbol] != chochDirection) {
                recoveryChoch[symbol] = chochDirection
                tradePrefs.edit().putString("signal_choch_$symbol", chochDirection).apply()
                addNotification("Home", "${displayIndexName(symbol)} CHOCH qualified", "CHOCH qualified in the ${chochDirection.lowercase(Locale.US)} direction on a completed candle.")
            }
        }

        runOnUiThread {
            updateRecoveryStatusView(symbol)
            mssStatusViews[symbol]?.apply {
                text = signalQualificationText(recoveryMss[symbol])
                setTextColor(if (recoveryMss[symbol].isNullOrBlank()) amber else green)
            }
            chochStatusViews[symbol]?.apply {
                text = signalQualificationText(recoveryChoch[symbol])
                setTextColor(if (recoveryChoch[symbol].isNullOrBlank()) amber else green)
            }
        }

        val mss = recoveryMss[symbol]
        val choch = recoveryChoch[symbol]
        // Qualification is always calculated and displayed, even while the original
        // pair is running. Execution is gated separately by Auto Trading and by a
        // a profitable original contract closure.
        if (!autoTradingEnabled || !accountConnected || mss.isNullOrBlank() || mss != choch) return

        val winningOriginal = positions.values.firstOrNull { candidate ->
            candidate.symbol == symbol && candidate.role == "ORIGINAL" && candidate.status == "CLOSED" &&
                candidate.recoveryEligible && !candidate.recoveryUsed && candidate.profit > 0.0 &&
                // MSS + CHOCH must confirm the reversal away from the stopped winning leg.
                candidate.direction != mss
        } ?: return

        val pairOriginals = positions.values.filter {
            it.symbol == symbol && it.role == "ORIGINAL" && it.originalId == winningOriginal.originalId
        }
        if (pairOriginals.size != 2) return
        val oppositeOriginal = pairOriginals.firstOrNull { it.localId != winningOriginal.localId } ?: return
        val oppositeStillRunning = oppositeOriginal.status !in setOf(
            "CLOSED", "ERROR", "BUY FAILED", "PROPOSAL FAILED"
        ) && !oppositeOriginal.status.startsWith("ERROR")
        // The losing/opposing original leg must remain open. Never close it here and
        // never start recovery if that leg is already closed or failed.
        if (!oppositeStillRunning) return

        val pairAlreadyRecovered = pairOriginals.any { it.recoveryUsed } || positions.values.any { recovery ->
            recovery.role == "RECOVERY" && recovery.originalId in pairOriginals.map { it.localId }
        }
        if (pairAlreadyRecovered) return

        val recoveryStake = roundStake(winningOriginal.stake + winningOriginal.profit)
        if (recoveryStake <= 0.0) return
        // Recovery can only be submitted when its reserved funds are actually
        // available in the Deriv account. Do not consume the qualification early.
        if (availableBalance + 0.000001 < recoveryStake) return
        pairOriginals.forEach { it.recoveryUsed = true }
        recoveryWaiting.remove(winningOriginal.localId)
        savePositions()
        addNotification("Trade", "${displayIndexName(symbol)} recovery confirmed", "MSS and CHOCH qualify the new ${mss.lowercase(Locale.US)} direction. Recovery stake: ${formatMoney(recoveryStake)}. The opposing original leg remains open.")
        openPosition(symbol, mss, "RECOVERY", recoveryStake, winningOriginal.localId)
        runOnUiThread { updateRecoveryStatusView(symbol) }
    }

    private fun savePositions() {
        val array = JSONArray()
        positions.values.forEach { p ->
            array.put(JSONObject().put("id", p.localId).put("symbol", p.symbol).put("direction", p.direction)
                .put("role", p.role).put("stake", p.stake).put("original", p.originalId)
                .put("contract", p.contractId).put("profit", p.profit).put("status", p.status)
                .put("recoveryEligible", p.recoveryEligible)
                .put("recoveryUsed", p.recoveryUsed)
                .put("closedAt", p.closedAt))
        }
        // commit() makes completed trade records durable before activity teardown/update.
        tradePrefs.edit().putString("positions", array.toString()).commit()
    }

    private fun loadPositions() {
        val raw = tradePrefs.getString("positions", "") ?: return
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val p = Position(o.getString("id"), o.getString("symbol"), o.getString("direction"), o.getString("role"), o.getDouble("stake"), o.optString("original"))
                p.contractId = o.optLong("contract")
                p.profit = o.optDouble("profit")
                p.status = o.optString("status", "OPEN")
                p.recoveryEligible = o.optBoolean("recoveryEligible", false)
                p.recoveryUsed = o.optBoolean("recoveryUsed", false)
                p.closedAt = o.optLong("closedAt", 0L)
                if (p.recoveryEligible && !p.recoveryUsed) recoveryWaiting.add(p.localId)
                positions[p.localId] = p
                if (p.contractId > 0 && p.status != "CLOSED") subscribePosition(p.contractId)
            }
            savePositions()
        } catch (_: Exception) { }
    }

    private fun showAccountModeDialog() {
        val modes = arrayOf("DEMO ACCOUNT", "REAL ACCOUNT")
        AlertDialog.Builder(this)
            .setTitle("Connect Deriv account")
            .setItems(modes) { _, which ->
                executionMode = if (which == 1) "REAL" else "DEMO"
                prefs.edit().putString("oauth_mode", executionMode).apply()
                render()
                beginDerivConnection()
            }
            .setNegativeButton("CANCEL", null)
            .show()
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
        root.addView(bottomNavigation())
        setContentView(root)
        renderedTab = selectedTab

        content.addView(space(8))

        when (selectedTab) {
            "Home" -> showHome()
            "Trade" -> showTrade()
            "Accounts" -> showAccounts()
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
            indexCard.addView(text("RECOVERY STATUS", 11f, whiteText, true))
            val recoveryView = text(recoveryStatusText(symbol), 11f, muted)
            recoveryStatusViews[symbol] = recoveryView
            indexCard.addView(recoveryView)
            content.addView(indexCard)
            content.addView(space(9))
        }
    }

    private fun showTrade() {
        pageHeading("RUNNING TRADES", "Original and recovery contracts.")
        content.addView(autoTradingButton())
        content.addView(space(10))
        val summary = card()
        summary.addView(text("DAILY STARTING CAPITAL", 12f, gold, true))
        summary.addView(text(String.format(Locale.US, "%.2f %s", dailyCapital, accountCurrency), 18f, blue, true))
        summary.addView(text("ACCOUNT MODE: $executionMode", 12f, if (executionMode == "REAL") blue else red, true))
        summary.addView(text("Stake per original contract: 25%", 12f, gold))
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
            // Keep one card per index, with each running contract displayed
            // independently inside that card.
            activePositions.groupBy { it.symbol }.forEach { (symbol, indexPositions) ->
                val c = card(indexNameColor(symbol))
                c.addView(text("${displayIndexName(symbol)} · RUNNING POSITIONS", 14f,
                    indexNameColor(symbol), true))
                c.addView(space(8))

                indexPositions.forEachIndexed { index, p ->
                    if (index > 0) {
                        c.addView(space(8))
                        c.addView(divider())
                        c.addView(space(8))
                    }

                    c.addView(text("${p.role} · ${p.direction}", 14f,
                        if (p.direction == "RISE") green else red, true))
                    c.addView(text("Contract: ${if (p.contractId > 0L) p.contractId.toString() else "Pending"}",
                        12f, Color.WHITE))
                    c.addView(text("Stake: ${formatMoney(p.stake)}", 13f, gold))

                    val statusView = text("Status: ${p.status}", 13f,
                        if (p.status == "OPEN") green else amber, true)
                    tradeStatusViews[p.localId] = statusView
                    c.addView(statusView)

                    val profitView = text("Profit / Loss: ${formatMoney(p.profit)}", 14f,
                        if (p.profit >= 0.0) green else red, true)
                    tradeProfitViews[p.localId] = profitView
                    c.addView(profitView)

                    if (p.contractId > 0L && p.status == "OPEN") {
                        c.addView(space(6))
                        c.addView(actionButton("CLOSE THIS ${p.direction} TRADE", primary = false) {
                            AlertDialog.Builder(this)
                                .setTitle("CONFIRM TRADE CLOSURE")
                                .setMessage("Are you sure you want to close this ${p.direction} contract on ${displayIndexName(p.symbol)}?")
                                .setNegativeButton("CANCEL", null)
                                .setPositiveButton("CONFIRM CLOSE") { _, _ -> closeRunningPosition(p) }
                                .show()
                        })
                    }
                }

                val combinedProfit = indexPositions.sumOf { it.profit }
                c.addView(space(8))
                c.addView(divider())
                c.addView(space(8))
                c.addView(text("INDEX COMBINED P/L: ${formatMoney(combinedProfit)}", 13f,
                    if (combinedProfit >= 0.0) green else red, true))

                content.addView(c)
                content.addView(space(8))
            }
        }

        content.addView(space(10))
        content.addView(divider())
        content.addView(space(14))
        content.addView(text("TRADE HISTORY", 17f, Color.WHITE, true))
        content.addView(text("Completed original and recovery contracts.", 12f, whiteText))
        content.addView(space(8))
        if (closedPositions.isEmpty()) {
            content.addView(text("No completed trades yet.", 12f, muted))
        } else {
            closedPositions.forEach { p ->
                val c = card(indexNameColor(p.symbol))
                c.addView(text("${displayIndexName(p.symbol)} · ${p.role}", 14f, indexNameColor(p.symbol), true))
                c.addView(text("DIRECTION: ${p.direction}", 14f, if (p.direction == "RISE") green else red, true))
                c.addView(text("Stake: ${formatMoney(p.stake)}", 13f, Color.WHITE))
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

    private fun updateTradePositionViews(position: Position) {
        tradeStatusViews[position.localId]?.apply {
            text = "Status: ${position.status}"
            setTextColor(if (position.status == "OPEN") green else amber)
        }
        tradeProfitViews[position.localId]?.apply {
            text = "Profit / Loss: ${formatMoney(position.profit)}"
            setTextColor(if (position.profit >= 0.0) green else red)
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
        })
        deriv.addView(space(8))
        deriv.addView(actionButton("DISCONNECT DERIV", false) {
            disconnectDerivAccount()
        })
        content.addView(deriv)
        content.addView(space(12))

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
            showUserProfile(platformUser)
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
        body.addView(panel)
        body.addView(space(12))
        body.addView(actionButton("SIGN OUT", false) { signOutPlatform() })
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
            statusRow.addView(text("${user.optString("role", "user").uppercase(Locale.US)}", 11f, muted))
            item.addView(statusRow)
            item.addView(space(8))
            item.addView(actionButton("OPEN USER PROFILE", false) { showAdminUserProfile(user) })
            body.addView(item)
            body.addView(space(9))
        }
        body.addView(actionButton("REFRESH REGISTERED USERS", true) { openAdminDashboard() })
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
        body.addView(panel)
        body.addView(space(12))
        body.addView(actionButton("EDIT USER PROFILE", true) { showEditUserDialog(user) })
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
        button.setTextColor(Color.WHITE)
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
            if (autoTradingEnabled) "Automatic original and eligible recovery entries are enabled. Existing contracts remain managed by Deriv."
            else "New automatic entries are paused. Existing contracts remain open until manually closed or they expire."
        )
        if (autoTradingEnabled && accountConnected) {
            indexes.forEach { symbol ->
                candlesByIndex[symbol]?.let { evaluateRecoverySignals(symbol, it) }
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
            feedPillView?.text = if (marketFeedConnected) "LIVE" else "CONNECTING"
            feedPillView?.setTextColor(if (marketFeedConnected) green else amber)
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
        listOf("Home", "Trade", "Accounts", "Settings").forEach { tab ->
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

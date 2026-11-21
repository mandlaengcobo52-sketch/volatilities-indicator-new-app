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
    private val accountGatePending = mutableSetOf<String>()
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
        var closedAt: Long = 0L,
        var peakProfit: Double = 0.0,
        var trailingActivated: Boolean = false,
        var lockedProfitPercent: Int = 0,
        var pairExitTriggered: Boolean = false,
        var recoveryAfterEpoch: Long = 0L,
        var pairedLoss: Double = 0.0,
        var entryBarrier: Double = 0.0,
        var expiryAt: Long = 0L
    )

    private val positions = linkedMapOf<String, Position>()
    private val pendingRequests = mutableMapOf<Int, String>()
    private val originalSweepStatus = mutableMapOf<String, String>()
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
    private val tradeTrailingViews = mutableMapOf<String, TextView>()
    private val tradeEntryBarrierViews = mutableMapOf<String, TextView>()
    private val tradeExpiryViews = mutableMapOf<String, TextView>()
    private val insufficientFundsNotified = mutableSetOf<String>()
    private val pendingReversalDirection = mutableMapOf<String, String>()
    private val lastExecutedSignalEpoch = mutableMapOf<String, Long>()

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
                        when (platformUserRole) {
                            "admin" -> showDerivRequiredScreen()
                            "investor" -> showInvestorDerivScreen()
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
            text = if (register) "CREATE YOUR PROFILE" else "ACCOUNT LOGIN"
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
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.rgb(0, 230, 118))
            }
            val investorChoice = RadioButton(this).apply {
                id = View.generateViewId()
                text = "INVESTOR — Deriv trading account"
                textSize = 14f
                setTextColor(Color.WHITE)
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.rgb(0, 230, 118))
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
            background = rounded(Color.rgb(0, 230, 118), Color.rgb(0, 230, 118), 10)
            setOnClickListener { submitPlatformAuth(register, fields, status, this, selectedRegistrationRole) }
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
        candlesByIndex.forEach { (symbol, candles) -> evaluateNewTradingSignal(symbol, candles) }
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
            updateMasterDecisionViews(symbol)
        }
    }

    override fun onCandles(symbol: String, candles: List<DerivApiClient.Candle>) {
        if (symbol !in indexes) return
        if (candles.isNotEmpty()) {
            marketFeedConnected = true
            candlesByIndex[symbol] = candles.sortedBy { it.epoch }
            evaluateNewTradingSignal(symbol, candles)
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
                val title = obj.optString("title")
                val message = obj.optString("message")
                val obsoleteLevelNotice = listOf(title, message).any {
                    it.contains("previous day", true) ||
                        it.contains("reference level", true) ||
                        it.contains("previous completed-day", true)
                }
                if (!obsoleteLevelNotice) {
                    appNotifications.add(AppNotification(
                        obj.optLong("id"),
                        obj.optString("page", "Home"),
                        title,
                        message,
                        obj.optLong("timestamp"),
                        obj.optBoolean("read", false)
                    ))
                }
            }
            saveNotifications()
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
        if (!accountConnected) return "Waiting for Deriv account connection."
        val mss = recoveryMss[symbol]
        val choch = recoveryChoch[symbol]
        return when {
            !mss.isNullOrBlank() && mss == choch -> "MSS + CHOCH confirmed ${mss}. Waiting for execution."
            !mss.isNullOrBlank() -> "MSS confirmed ${mss}. Waiting for matching CHOCH."
            !choch.isNullOrBlank() -> "CHOCH confirmed ${choch}. Waiting for matching MSS."
            else -> "Waiting for MSS + CHOCH confirmation on a closed H1 candle."
        }
    }

    private fun recoveryStatusText(symbol: String): String {
        val mss = recoveryMss[symbol]
        val choch = recoveryChoch[symbol]
        return when {
            !mss.isNullOrBlank() && mss == choch -> "MSS + CHOCH confirmed ${mss}."
            !mss.isNullOrBlank() -> "MSS confirmed ${mss}. Waiting for matching CHOCH."
            !choch.isNullOrBlank() -> "CHOCH confirmed ${choch}. Waiting for matching MSS."
            else -> "Waiting for MSS + CHOCH confirmation on a closed H1 candle."
        }
    }

    private fun updateMasterDecisionViews(symbol: String) {
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

    // Recovery capital reservation is retired in the replacement trading plan.
    private fun reservedRecoveryCapital(): Double = 0.0

    // Legacy tick/boundary entry is disabled. New entries are candle-close driven
    // exclusively by evaluateNewTradingSignal().
    private fun evaluateOriginalEntries(symbol: String, quote: Double) {
        // Intentionally no-op: previous-day high/low paired entries are retired.
    }

    private fun roundStake(value: Double): Double =
        BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toDouble()

    private fun openPosition(symbol: String, direction: String, role: String, stake: Double, originalId: String = "", accountGatePassed: Boolean = false): Boolean {
        if (platformAuthenticated && platformUserRole in setOf("user", "investor") && !accountGatePassed) {
            if (!accountGatePending.add(symbol)) return false
            platformRequest("account_trade_gate", mapOf("daily_capital" to dailyCapital.toString()), onResult = {
                accountGatePending.remove(symbol)
                openPosition(symbol, direction, role, stake, originalId, true)
            }, onError = { accountGatePending.remove(symbol) })
            return true
        }
        if (platformAuthenticated && platformUserRole == "investor") {
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())
            val subscriptionActive = platformUser.optString("subscription_status") == "approved" &&
                platformUser.optString("subscription_expiry_date") >= today
            val planActive = platformUser.optString("investor_plan_status") == "approved" &&
                platformUser.optString("investor_plan_expiry_date") >= today
            if (!subscriptionActive || !planActive) {
                addNotification("Account", "Investor execution blocked", "An active approved subscription and an approved, unexpired Investor plan are required before trading.")
                runOnUiThread { toast("Trading requires an approved active subscription and Investor plan.") }
                return false
            }
        }
        val roundedStake = roundStake(stake)
        if (!accountConnected || !roundedStake.isFinite() || roundedStake <= 0.0) return false
        val localId = UUID.randomUUID().toString()
        // Enforce one live or pending contract per index.
        val indexBusy = positions.values.any {
            it.symbol == symbol && it.status !in setOf("CLOSED", "BUY FAILED", "PROPOSAL FAILED") &&
                !it.status.startsWith("ERROR")
        }
        if (indexBusy) return false
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
            .put("duration", 12)
            .put("duration_unit", "h")
            .put("underlying_symbol", symbol)
            .put("req_id", req)
        p.status = "REQUESTING PROPOSAL"
        val client = derivService?.client
        if (client == null) {
            pendingRequests.remove(req)
            positions.remove(localId)
            addNotification("Trade", "${displayIndexName(symbol)} execution failed", "Deriv trading connection is unavailable. The qualifying MSS + CHOCH signal remains available for retry.")
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
        position.entryBarrier = contract.optDouble(
            "entry_tick",
            contract.optDouble("entry_spot", contract.optDouble("barrier", position.entryBarrier))
        )
        position.expiryAt = contract.optLong("date_expiry", position.expiryAt)

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
            if (!wasAlreadyClosed) {
                addNotification("Trade", "${displayIndexName(position.symbol)} trade closed", "${position.role} ${position.direction} final P/L: ${formatMoney(position.profit)}.")
                if (platformAuthenticated && platformUserRole in setOf("user", "investor") && position.contractId > 0L) {
                    platformRequest("account_trade_result", mapOf(
                        "trade_id" to position.contractId.toString(),
                        "profit" to position.profit.toString()
                    )) { }
                }
            }
            val reversal = pendingReversalDirection.remove(position.symbol)
            if (!reversal.isNullOrBlank() && autoTradingEnabled && accountConnected) {
                val anotherActive = positions.values.any { it.symbol == position.symbol && it.localId != position.localId &&
                    it.status !in setOf("CLOSED", "BUY FAILED", "PROPOSAL FAILED") && !it.status.startsWith("ERROR") }
                val reversalStake = roundStake(dailyCapital * 0.10)
                if (!anotherActive && availableBalance >= reversalStake && openPosition(position.symbol, reversal, "MASTER", reversalStake)) {
                    addNotification("Trade", "${displayIndexName(position.symbol)} reversal submitted", "The previous contract is confirmed closed. Opening the opposite ${reversal} contract.")
                }
            }
        } else if (position.status != "CLOSING") {
            position.status = "OPEN"
        }
        // Trailing activates at +10% of this contract's stake and initially
        // protects +10%. Protection advances in 10-point increments.
        if (!isSold && position.status == "OPEN" && position.stake > 0.0) {
            if (position.profit >= roundStake(position.stake * 0.10)) {
                position.trailingActivated = true
            }
            if (position.trailingActivated && position.profit > position.peakProfit) {
                position.peakProfit = position.profit
            }
            if (position.trailingActivated) {
                val peakProfitPercent = (position.peakProfit / position.stake * 100.0).toInt()
                val newLock = (peakProfitPercent / 10) * 10
                if (newLock >= 10 && newLock > position.lockedProfitPercent) {
                    position.lockedProfitPercent = newLock
                }
            }
            val lockedNetProfit = roundStake(
                position.stake * position.lockedProfitPercent / 100.0
            )
            val reversedToLock = position.trailingActivated &&
                position.lockedProfitPercent >= 10 &&
                position.profit < position.peakProfit &&
                position.profit <= lockedNetProfit
            if (reversedToLock && position.canSell && position.contractId > 0L) {
                closeRunningPosition(position)
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

    private fun evaluateNewTradingSignal(symbol: String, candles: List<DerivApiClient.Candle>) {
        if (candles.size < 8) return
        // The final candle is still forming; only closed H1 candles qualify.
        val closed = candles.sortedBy { it.epoch }.dropLast(1)
        if (closed.size < 7) return
        val last = closed.last()
        val prior = closed.dropLast(1)
        // Only a fresh H1 qualification may execute: maximum age is one hour
        // measured from the candle close (not the candle opening timestamp).
        val qualificationClosedAt = last.epoch + 3600L
        val qualificationAgeSeconds = (System.currentTimeMillis() / 1000L) - qualificationClosedAt
        if (qualificationAgeSeconds < 0L || qualificationAgeSeconds > 3600L) return

        // Use confirmed local swing points from completed candles only.
        val swingHighs = mutableListOf<Double>()
        val swingLows = mutableListOf<Double>()
        for (i in 1 until prior.lastIndex) {
            if (prior[i].high > prior[i - 1].high && prior[i].high > prior[i + 1].high) swingHighs.add(prior[i].high)
            if (prior[i].low < prior[i - 1].low && prior[i].low < prior[i + 1].low) swingLows.add(prior[i].low)
        }
        val lastHigh = swingHighs.lastOrNull() ?: return
        val lastLow = swingLows.lastOrNull() ?: return

        // MSS = close beyond the latest confirmed swing. CHOCH = the same
        // structural break confirmed by candle direction. Both must agree on
        // this exact closed candle; no live-tick or previous-day trigger is used.
        val mssDirection = when {
            last.close > lastHigh -> "RISE"
            last.close < lastLow -> "FALL"
            else -> ""
        }
        val chochDirection = when {
            last.close > lastHigh && last.close > last.open -> "RISE"
            last.close < lastLow && last.close < last.open -> "FALL"
            else -> ""
        }
        recoveryMss[symbol] = mssDirection
        recoveryChoch[symbol] = chochDirection
        recoveryMssEpoch[symbol] = if (mssDirection.isNotBlank()) last.epoch else 0L
        recoveryChochEpoch[symbol] = if (chochDirection.isNotBlank()) last.epoch else 0L
        tradePrefs.edit()
            .putString("signal_mss_$symbol", mssDirection)
            .putString("signal_choch_$symbol", chochDirection)
            .putLong("signal_mss_epoch_$symbol", recoveryMssEpoch[symbol] ?: 0L)
            .putLong("signal_choch_epoch_$symbol", recoveryChochEpoch[symbol] ?: 0L)
            .apply()

        runOnUiThread {
            updateMasterDecisionViews(symbol)
            updateRecoveryStatusView(symbol)
        }
        if (!autoTradingEnabled || !accountConnected || dailyCapital <= 0.0) return
        if (mssDirection.isBlank() || mssDirection != chochDirection) return
        if (lastExecutedSignalEpoch[symbol] == last.epoch) return

        val active = positions.values.firstOrNull {
            it.symbol == symbol && it.status !in setOf("CLOSED", "BUY FAILED", "PROPOSAL FAILED") &&
                !it.status.startsWith("ERROR")
        }
        if (active != null) {
            // Same-direction confirmation never closes or duplicates a trade.
            if (active.direction == mssDirection) return
            // An opposite MSS + CHOCH must close the old contract first.
            if (active.status == "OPEN" && active.canSell && active.contractId > 0L) {
                pendingReversalDirection[symbol] = mssDirection
                closeRunningPosition(active)
            }
            return
        }

        val stake = roundStake(dailyCapital * 0.10)
        val available = (availableBalance - reservedRecoveryCapital()).coerceAtLeast(0.0)
        if (stake <= 0.0 || available + 0.000001 < stake) return
        if (openPosition(symbol, mssDirection, "MASTER", stake)) {
            lastExecutedSignalEpoch[symbol] = last.epoch
            addNotification("Trade", "${displayIndexName(symbol)} ${mssDirection} signal submitted", "Closed-candle MSS and CHOCH agree. Stake: ${formatMoney(stake)}. Contract expiry: 12 hours.")
        }
    }

    private fun savePositions() {
        val array = JSONArray()
        positions.values.forEach { p ->
            array.put(JSONObject().put("id", p.localId).put("symbol", p.symbol).put("direction", p.direction)
                .put("role", p.role).put("stake", p.stake).put("original", p.originalId)
                .put("contract", p.contractId).put("profit", p.profit).put("status", p.status)
                .put("recoveryEligible", p.recoveryEligible)
                .put("recoveryUsed", p.recoveryUsed)
                .put("closedAt", p.closedAt)
                .put("peakProfit", p.peakProfit)
                .put("trailingActivated", p.trailingActivated)
                .put("lockedProfitPercent", p.lockedProfitPercent)
                .put("pairExitTriggered", p.pairExitTriggered)
                .put("recoveryAfterEpoch", p.recoveryAfterEpoch)
                .put("pairedLoss", p.pairedLoss)
                .put("entryBarrier", p.entryBarrier)
                .put("expiryAt", p.expiryAt))
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
                p.peakProfit = o.optDouble("peakProfit", p.profit.coerceAtLeast(0.0))
                p.trailingActivated = o.optBoolean(
                    "trailingActivated",
                    p.stake > 0.0 && p.peakProfit >= p.stake * 0.10
                )
                val savedLockPercent = o.optInt("lockedProfitPercent", 0)
                // New trailing protection is expressed as net profit percentage of stake.
                p.lockedProfitPercent = savedLockPercent.coerceAtLeast(0)
                p.pairExitTriggered = o.optBoolean("pairExitTriggered", false)
                p.recoveryAfterEpoch = o.optLong("recoveryAfterEpoch", p.closedAt)
                p.pairedLoss = o.optDouble("pairedLoss", 0.0)
                p.entryBarrier = o.optDouble("entryBarrier", 0.0)
                p.expiryAt = o.optLong("expiryAt", 0L)
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
        if (platformUserRole == "investor" && selectedTab !in listOf("Trade", "Accounts", "Profile")) selectedTab = "Trade"
        pageScrollView?.let { oldScroll ->
            if (renderedTab.isNotBlank()) scrollPositions[renderedTab] = oldScroll.scrollY
        }
        priceViews.clear()
        indexStatusViews.clear()
        marketStatusViews.clear()
        masterDecisionViews.clear()
        levelStatusViews.clear()
        recoveryStatusViews.clear()
        mssStatusViews.clear()
        chochStatusViews.clear()
        tradeStatusViews.clear()
        tradeProfitViews.clear()
        tradeTrailingViews.clear()
        tradeEntryBarrierViews.clear()
        tradeExpiryViews.clear()

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
            content.addView(indexCard)
            content.addView(space(9))
        }
    }

    private fun showTrade() {
        pageHeading("RUNNING TRADES", "Contracts opened by confirmed MSS + CHOCH.")
        content.addView(autoTradingButton())
        content.addView(space(10))
        val summary = card()
        summary.addView(text("DAILY STARTING CAPITAL", 12f, gold, true))
        summary.addView(text(String.format(Locale.US, "%.2f %s", dailyCapital, accountCurrency), 18f, blue, true))
        summary.addView(text("ACCOUNT MODE: $executionMode", 12f, if (executionMode == "REAL") blue else red, true))
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
                    val entryBarrierView = text(
                        "ENTRY BARRIER: ${if (p.entryBarrier > 0.0) formatPrice(p.entryBarrier) else "Awaiting Deriv entry price"}",
                        12f, blue, true
                    )
                    tradeEntryBarrierViews[p.localId] = entryBarrierView
                    c.addView(entryBarrierView)
                    val expiryText = if (p.expiryAt > 0L) {
                        java.text.DateFormat.getDateTimeInstance().format(java.util.Date(p.expiryAt * 1000L))
                    } else {
                        "Awaiting Deriv expiry"
                    }
                    val expiryView = text("EXPIRY: $expiryText", 12f, blue, true)
                    tradeExpiryViews[p.localId] = expiryView
                    c.addView(expiryView)
                    c.addView(text("Stake: ${formatMoney(p.stake)}", 13f, gold))

                    val statusView = text("Status: ${p.status}", 13f,
                        if (p.status == "OPEN") green else amber, true)
                    tradeStatusViews[p.localId] = statusView
                    c.addView(statusView)

                    val profitView = text("Profit / Loss: ${formatMoney(p.profit)}", 14f,
                        if (p.profit >= 0.0) green else red, true)
                    tradeProfitViews[p.localId] = profitView
                    c.addView(profitView)

                    val trailingText = if (p.trailingActivated) {
                        "TRAILING STOP: ON · PROTECTING ${p.lockedProfitPercent}% (${formatMoney(p.stake * p.lockedProfitPercent / 100.0)})"
                    } else {
                        "TRAILING STOP: ON · WAITING FOR +10% (${formatMoney(p.stake * 0.10)} profit)"
                    }
                    val trailingView = text(trailingText, 12f, if (p.trailingActivated) green else amber, true)
                    tradeTrailingViews[p.localId] = trailingView
                    c.addView(trailingView)

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

                content.addView(c)
                content.addView(space(8))
            }
        }

        content.addView(space(10))
        content.addView(divider())
        content.addView(space(14))
        content.addView(text("TRADE HISTORY", 17f, Color.WHITE, true))
        content.addView(text("Completed contracts.", 12f, whiteText))
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
        tradeEntryBarrierViews[position.localId]?.text =
            "ENTRY BARRIER: ${if (position.entryBarrier > 0.0) formatPrice(position.entryBarrier) else "Awaiting Deriv entry price"}"
        tradeExpiryViews[position.localId]?.text = "EXPIRY: " + if (position.expiryAt > 0L) {
            java.text.DateFormat.getDateTimeInstance().format(java.util.Date(position.expiryAt * 1000L))
        } else {
            "Awaiting Deriv expiry"
        }
        tradeStatusViews[position.localId]?.apply {
            text = "Status: ${position.status}"
            setTextColor(if (position.status == "OPEN") green else amber)
        }
        tradeProfitViews[position.localId]?.apply {
            text = "Profit / Loss: ${formatMoney(position.profit)}"
            setTextColor(if (position.profit >= 0.0) green else red)
        }
        tradeTrailingViews[position.localId]?.apply {
            text = if (position.trailingActivated) {
                "TRAILING STOP: ON · PROTECTING ${position.lockedProfitPercent}% (${formatMoney(position.stake * position.lockedProfitPercent / 100.0)})"
            } else {
                "TRAILING STOP: ON · WAITING FOR +10% (${formatMoney(position.stake * 0.10)} profit)"
            }
            setTextColor(if (position.trailingActivated) green else amber)
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
    private fun platformRequest(action: String, values: Map<String, String> = emptyMap(), onResult: (JSONObject) -> Unit, onError: () -> Unit = {}) {
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
                runOnUiThread { toast("Unable to contact the account server. Check your connection."); onError() }
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
                        onError()
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
            addProfileField(panel, "Selected trading plan", when(user.optString("investor_plan")) { "A" -> "Plan A · 50% target · 15 days"; "B" -> "Plan B · 100% target · 30 days"; else -> "Not selected" })
            addProfileField(panel, "Plan approval", user.optString("investor_plan_status", "not_selected").replace('_',' ').uppercase(Locale.US))
            val planStartDate = user.optString("investor_plan_start_date").takeUnless { it.isBlank() || it == "null" } ?: "Not approved"
            val planExpiryDate = user.optString("investor_plan_expiry_date").takeUnless { it.isBlank() || it == "null" } ?: "Not approved"
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
        val options = arrayOf("Plan A — 50% in 15 days", "Plan B — 100% in 30 days")
        val selected = if (current == "B") 1 else 0
        AlertDialog.Builder(this).setTitle("Choose Investor Trading Plan")
            .setSingleChoiceItems(options, selected, null)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("SUBMIT SELECTION") { dialog, _ ->
                val index = (dialog as AlertDialog).listView.checkedItemPosition
                val plan = if (index == 1) "B" else "A"
                platformRequest("investor_select_plan", mapOf("plan" to plan)) {
                    toast(it.optString("message", "Plan selection submitted."))
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
                candlesByIndex[symbol]?.let { evaluateNewTradingSignal(symbol, it) }
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

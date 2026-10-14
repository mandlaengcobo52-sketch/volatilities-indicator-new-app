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
    private val authPrefs by lazy { getSharedPreferences("vi_platform_auth", MODE_PRIVATE) }
    private val authEndpoint = "https://volatilitiesindicator.org.za/mobile-auth.php"
    private var authToken: String = ""
    private var currentProfile: JSONObject? = null
    private var profileLoading: Boolean = false
    private var adminUsers: JSONArray? = null
    private var adminUsersLoading: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = bg
window.navigationBarColor = bg
window.decorView.systemUiVisibility = 0

platform = appPrefs.getString("platform", "USER") ?: "USER"
executionMode = appPrefs.getString("executionMode", "DEMO") ?: "DEMO"
selectedTab = appPrefs.getString("selectedTab", "Home") ?: "Home"
authToken = authPrefs.getString("mobile_auth_token", "").orEmpty()

render()
handleOAuthCallback(intent)
if (authToken.isNotBlank()) refreshProfile(showMessage = false)

val savedToken = prefs.getString(
    if (executionMode == "REAL") "real_token" else "demo_token",
    ""
).orEmpty()

if (savedToken.isNotBlank() && intent?.data?.host != "oauth-callback") {
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
when (selectedTab) {
                "Analysis" -> {
    page.addView(
        sectionTitle("ANALYSIS — NEW MODULE PENDING"),
        matchWrap()
    )
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
"Account" -> {
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
                addPlatformSelector()
                addExecutionSelector()
                addAccountCard()
                                                if (platform == "USER") {

                    // Show sign-in and registration only when logged out.
                    if (authToken.isBlank()) {
                        page.addView(
                            button("SIGN IN", false) {
                                selectedTab = "Login"
                                render()
                            },
                            matchWrap()
                        )

                        page.addView(
                            button("CREATE ACCOUNT", true) {
                                selectedTab = "Register"
                                render()
                            },
                            matchWrap()
                        )
                    }

                    // Show Deriv registration only when disconnected.
                    if (!connected) {
                        page.addView(
                            button("CREATE DERIV ACCOUNT", true) {
                                try {
                                    val derivSignupUrl =
                                        Uri.parse("https://deriv.com/signup/")

                                    startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            derivSignupUrl
                                        )
                                    )
                                } catch (e: Exception) {
                                    toast(
                                        "Unable to open Deriv registration. " +
                                            "Please try again."
                                    )
                                }
                            },
                            matchWrap()
                        )
                    }
                }
            }
}

        addBottomNavigation(outer)
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
                            selectedTab = "Account"
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
                        selectedTab = "Account"
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
    val profileCard = card().apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(20)) }
    profileCard.addView(label("MY PROFILE", 22f, green, true))
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
        profileField("Assigned risk plan", riskText)
        profileField("Start date", u.optString("start_date", "").takeUnless { it == "null" }.orEmpty())
        profileField("Expiry date", u.optString("expiry_date", "").takeUnless { it == "null" }.orEmpty())
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
            if (selectedTab == "Account") render()
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
        if (platform != "ADMIN") {
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
        beginDerivConnection()
    },
    weighted()
)
        
        row.addView(space(dp(8)))

        row.addView(
    button("▥  REAL", executionMode == "REAL") {
        executionMode = "REAL"
        render()
        beginDerivConnection()
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
// TRADE SCREEN
// =========================================================

// Always starts OFF. This UI switch does not execute trades.
private var autoTradeEnabled = false

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

    // Auto Trade control
    page.addView(sectionTitle("AUTO TRADE"))

    val autoTradeCard = card()

    autoTradeCard.addView(
        label(
            if (autoTradeEnabled) {
                "AUTO TRADE: ARMED — EXECUTION NOT CONNECTED"
            } else {
                "AUTO TRADE: OFF"
            },
            15f,
            if (autoTradeEnabled) yellow else red,
            true
        )
    )

    autoTradeCard.addView(
        label(
            "Automatic execution is not connected yet. " +
                "Turning this switch ON will not open trades.",
            12f,
            muted,
            false
            )
    )

    autoTradeCard.addView(
        button(
            if (autoTradeEnabled) {
                "DISARM AUTO TRADE"
            } else {
                "ARM AUTO TRADE"
                },
            autoTradeEnabled
        ) {
            autoTradeEnabled = !autoTradeEnabled
            render()
        },
        matchWrap().apply {
            topMargin = dp(8)
        }
    )

    page.addView(
        autoTradeCard,
        matchWrap().apply {
            bottomMargin = dp(10)
        }
    )

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

    listOf(
        "Index",
        "Direction",
        "Execution time",
        "Time remaining",
        "Profit / Loss",
        "Barrier",
        "Potential payment",
        "Stake",
        "Close trade"
    ).forEach { field ->
        runningCard.addView(
            label(
                "$field: —",
                12f,
                white,
                false
            )
        )
    }

    runningCard.addView(
        label(
            "No confirmed running-contract records are available in this screen yet.",
            12f,
            yellow,
            false
        ).apply {
            setPadding(0, dp(8), 0, 0)
        }
    )

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

    listOf(
        "Index",
        "Direction",
        "Execution time",
        "Closing time",
        "Stake",
        "Barrier",
        "Final payment",
        "Final Profit / Loss",
        "Status"
    ).forEach { field ->
        historyCard.addView(
            label(
                "$field: —",
                12f,
                white,
                false
            )
        )
    }

    historyCard.addView(
        label(
            "No confirmed completed-contract records are available in this screen yet.",
            12f,
            yellow,
            false
        ).apply {
            setPadding(0, dp(8), 0, 0)
        }
    )

    page.addView(
        historyCard,
        matchWrap().apply {
            bottomMargin = dp(10)
        }
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
                            "phone" to contact
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
        this.accountId = accountId
        this.accountType = accountType
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
}

override fun onCandles(
    symbol: String,
    candles: List<DerivApiClient.Candle>
) {
}

override fun onMarketDataError(symbol: String, message: String) {
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

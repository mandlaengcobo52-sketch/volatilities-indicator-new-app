package za.co.volatilitiesindicator.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

class MainActivity : Activity() {

    // =============================================================
    // COLOURS
    // =============================================================

    private val bg = Color.rgb(15, 18, 22)
    private val panel = Color.rgb(25, 29, 35)
    private val panel2 = Color.rgb(32, 37, 44)

    private val green = Color.rgb(0, 220, 120)
    private val red = Color.rgb(255, 70, 70)
    private val yellow = Color.rgb(255, 205, 0)
    private val cyan = Color.rgb(0, 210, 255)
    private val blue = Color.rgb(70, 150, 255)
    private val white = Color.WHITE
    private val muted = Color.rgb(150, 160, 170)

    // =============================================================
    // ROOT
    // =============================================================

    private lateinit var rootScroll: ScrollView
    private lateinit var rootLayout: LinearLayout

    private var currentPlatform = "USER"
    private var executionMode = "DEMO"

    private var autoTradeEnabled = false
    private var connected = false

    private var accountId = ""
    private var accountType = ""
    private var accountBalance = 0.0
    private var accountCurrency = ""

    private val client = OkHttpClient()

    // =============================================================
    // OAUTH / PKCE
    // =============================================================

    private val prefs by lazy {
        getSharedPreferences("deriv_oauth", MODE_PRIVATE)
    }

    private var oauthState = ""
    private var codeVerifier = ""

    // =============================================================
    // INDEXES
    // =============================================================

    private val indexes = listOf(
        "R_10",
        "R_25",
        "R_50",
        "R_75",
        "R_100"
    )

    // =============================================================
    // ACTIVITY
    // =============================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = bg
        window.navigationBarColor = bg

        handleOAuthCallback(intent)

        buildMainScreen()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)

        if (intent != null) {
            setIntent(intent)
            handleOAuthCallback(intent)
        }
    }

    // =============================================================
    // MAIN SCREEN
    // =============================================================

    private fun buildMainScreen() {

        rootScroll = ScrollView(this).apply {
    setBackgroundColor(bg)
    isFillViewport = true
}

rootLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(12), dp(12), dp(12), dp(24))
    setBackgroundColor(bg)
}

rootScroll.addView(rootLayout)

setContentView(rootScroll)

addHeader()
addPlatformSelector()
addExecutionSelector()
addAccountCard()
addIndexMonitor()
addAdminSection()
addBottomNavigation()

    // =============================================================
    // HEADER
    // =============================================================

    private fun addHeader() {

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(12))
        }

        val logo = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_dialog_info)
            layoutParams = LinearLayout.LayoutParams(
                dp(42),
                dp(42)
            ).apply {
                rightMargin = dp(10)
            }
        }

        header.addView(logo)

        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val title = TextView(this).apply {
            text = "VOLATILITIES INDICATOR"
            textSize = 19f
            setTextColor(white)
            setTypeface(null, Typeface.BOLD)
        }

        val subtitle = TextView(this).apply {
            text = "Trading Control Platform"
            textSize = 12f
            setTextColor(muted)
        }

        titleBox.addView(title)
        titleBox.addView(subtitle)

        header.addView(titleBox)

        val status = TextView(this).apply {
            text = if (connected) "CONNECTED" else "OFFLINE"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (connected) green else red)
            gravity = Gravity.CENTER
        }

        header.addView(status)

        rootLayout.addView(header)
    }

    // =============================================================
    // PLATFORM SELECTOR
    // =============================================================

    private fun addPlatformSelector() {

        val section = createSection("PLATFORM")

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val userButton = createActionButton(
            "USER",
            if (currentPlatform == "USER") green else panel2
        )

        val adminButton = createActionButton(
            "ADMIN",
            if (currentPlatform == "ADMIN") cyan else panel2
        )

        userButton.setOnClickListener {
            currentPlatform = "USER"
            rebuild()
        }

        adminButton.setOnClickListener {
            currentPlatform = "ADMIN"
            rebuild()
        }

        row.addView(
            userButton,
            LinearLayout.LayoutParams(
                0,
                dp(46),
                1f
            ).apply {
                rightMargin = dp(6)
            }
        )

        row.addView(
            adminButton,
            LinearLayout.LayoutParams(
                0,
                dp(46),
                1f
            ).apply {
                leftMargin = dp(6)
            }
        )

        section.addView(row)

        rootLayout.addView(section)
    }

    // =============================================================
    // EXECUTION MODE
    // =============================================================

    private fun addExecutionSelector() {

        val section = createSection("EXECUTION MODE")

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val demoButton = createActionButton(
            "DEMO",
            if (executionMode == "DEMO") green else panel2
        )

        val realButton = createActionButton(
            "REAL",
            if (executionMode == "REAL") red else panel2
        )

        demoButton.setOnClickListener {
            executionMode = "DEMO"
            rebuild()
        }

        realButton.setOnClickListener {
            executionMode = "REAL"
            rebuild()
        }

        row.addView(
            demoButton,
            LinearLayout.LayoutParams(
                0,
                dp(46),
                1f
            ).apply {
                rightMargin = dp(6)
            }
        )

        row.addView(
            realButton,
            LinearLayout.LayoutParams(
                0,
                dp(46),
                1f
            ).apply {
                leftMargin = dp(6)
            }
        )

        section.addView(row)

        rootLayout.addView(section)
    }
        // =============================================================
    // ACCOUNT CARD
    // =============================================================

    private fun addAccountCard() {

        val section = createSection("ACCOUNT")

        val accountBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(panel2)
        }

        val accountTitle = TextView(this).apply {
            text = "DERIV ACCOUNT"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(white)
        }

        accountBox.addView(accountTitle)

        val accountText = TextView(this).apply {
            text = if (connected) {
                if (accountId.isNotEmpty()) {
                    "$accountId\n${accountType.uppercase()}"
                } else {
                    "OAUTH CONNECTED"
                }
            } else {
                "NOT CONNECTED"
            }

            textSize = 13f
            setTextColor(if (connected) green else muted)
            setPadding(0, dp(6), 0, dp(6))
        }

        accountBox.addView(accountText)

        val balanceText = TextView(this).apply {
            text = if (accountCurrency.isNotEmpty()) {
                "BALANCE: %.2f %s".format(
                    accountBalance,
                    accountCurrency
                )
            } else {
                "BALANCE: --"
            }

            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(white)
        }

        accountBox.addView(balanceText)

        val connectButton = createActionButton(
            if (connected) "OAUTH CONNECTED" else "CONNECT DERIV",
            if (connected) green else cyan
        )

        connectButton.setOnClickListener {

            if (connected) {
                Toast.makeText(
                    this,
                    "Deriv account already connected",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                startDerivOAuth()
            }
        }

        accountBox.addView(
            connectButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46)
            ).apply {
                topMargin = dp(10)
            }
        )

        section.addView(accountBox)

        rootLayout.addView(section)
    }

    // =============================================================
    // INDEX MONITOR
    // =============================================================

    private fun addIndexMonitor() {

        val section = createSection("INDEX MONITOR")

        for (index in indexes) {
            section.addView(indexRow(index))
        }

        rootLayout.addView(section)
    }

    // =============================================================
    // INDEX ROW
    // =============================================================

    private fun indexRow(index: String): LinearLayout {

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackgroundColor(panel2)

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val name = TextView(this).apply {
            text = index
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(white)

            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val phase = TextView(this).apply {
            text = "WAITING"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(yellow)
            gravity = Gravity.CENTER
        }

        header.addView(name)
        header.addView(phase)

        row.addView(header)

        val priceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
        }

        val currentPrice = TextView(this).apply {
            text = "CURRENT PRICE: --"
            textSize = 13f
            setTextColor(cyan)

            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val probability = TextView(this).apply {
            text = "0%"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(yellow)
        }

        priceRow.addView(currentPrice)
        priceRow.addView(probability)

        row.addView(priceRow)

        // ---------------------------------------------------------
        // TOUCH
        // ---------------------------------------------------------

        val touchTitle = TextView(this).apply {
            text = "TOUCH"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(white)
            setPadding(0, dp(4), 0, dp(4))
        }

        row.addView(touchTitle)

        val touchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val buyTouch = createActionButton(
            "BUY TOUCH",
            panel
        )

        val sellTouch = createActionButton(
            "SELL TOUCH",
            panel
        )

        buyTouch.setOnClickListener {
            Toast.makeText(
                this,
                "$index BUY TOUCH selected",
                Toast.LENGTH_SHORT
            ).show()
        }

        sellTouch.setOnClickListener {
            Toast.makeText(
                this,
                "$index SELL TOUCH selected",
                Toast.LENGTH_SHORT
            ).show()
        }

        touchRow.addView(
            buyTouch,
            LinearLayout.LayoutParams(
                0,
                dp(44),
                1f
            ).apply {
                rightMargin = dp(4)
            }
        )

        touchRow.addView(
            sellTouch,
            LinearLayout.LayoutParams(
                0,
                dp(44),
                1f
            ).apply {
                leftMargin = dp(4)
            }
        )

        row.addView(touchRow)

        // ---------------------------------------------------------
        // RISE / FALL
        // ---------------------------------------------------------

        val directionTitle = TextView(this).apply {
            text = "DIRECTION"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(white)
            setPadding(0, dp(10), 0, dp(4))
        }

        row.addView(directionTitle)

        val directionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val riseButton = createActionButton(
            "RISE",
            panel
        )

        val fallButton = createActionButton(
            "FALL",
            panel
        )

        riseButton.setOnClickListener {
            Toast.makeText(
                this,
                "$index RISE",
                Toast.LENGTH_SHORT
            ).show()
        }

        fallButton.setOnClickListener {
            Toast.makeText(
                this,
                "$index FALL",
                Toast.LENGTH_SHORT
            ).show()
        }

        directionRow.addView(
            riseButton,
            LinearLayout.LayoutParams(
                0,
                dp(44),
                1f
            ).apply {
                rightMargin = dp(4)
            }
        )

        directionRow.addView(
            fallButton,
            LinearLayout.LayoutParams(
                0,
                dp(44),
                1f
            ).apply {
                leftMargin = dp(4)
            }
        )

        row.addView(directionRow)

        // ---------------------------------------------------------
        // TARGET
        // ---------------------------------------------------------

        val target = TextView(this).apply {
            text = "TARGET PRICE: --"
            textSize = 13f
            setTextColor(white)
            setPadding(0, dp(10), 0, dp(4))
        }

        row.addView(target)

        // ---------------------------------------------------------
        // SUPPORT / RESISTANCE
        // ---------------------------------------------------------

        val support = TextView(this).apply {
            text = "SUPPORT: --"
            textSize = 12f
            setTextColor(green)
        }

        val resistance = TextView(this).apply {
            text = "RESISTANCE: --"
            textSize = 12f
            setTextColor(red)
        }

        row.addView(support)
        row.addView(resistance)

        // ---------------------------------------------------------
        // PROFIT / LOSS
        // ---------------------------------------------------------

        val pnl = TextView(this).apply {
            text = "P/L: 0.00"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(white)
            setPadding(0, dp(8), 0, 0)
        }

        row.addView(pnl)

        return row
    }

    // =============================================================
    // ADMIN SECTION
    // =============================================================

    private fun addAdminSection() {

        if (currentPlatform != "ADMIN") {
            return
        }

        val section = createSection("ADMIN")

        val menuButton = createActionButton(
            "ADMIN MENU",
            cyan
        )

        menuButton.setOnClickListener {
            adminMenu()
        }

        section.addView(
            menuButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46)
            )
        )

        val summaryButton = createActionButton(
            "ACCOUNT SUMMARY",
            panel2
        )

        summaryButton.setOnClickListener {
            summaryRow()
        }

        section.addView(
            summaryButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46)
            ).apply {
                topMargin = dp(8)
            }
        )

        rootLayout.addView(section)
    }

    // =============================================================
    // ADMIN MENU
    // =============================================================

    private fun adminMenu() {

        Toast.makeText(
            this,
            "Admin menu opened",
            Toast.LENGTH_SHORT
        ).show()
    }

    // =============================================================
    // SUMMARY
    // =============================================================

    private fun summaryRow() {

        Toast.makeText(
            this,
            "Account summary",
            Toast.LENGTH_SHORT
        ).show()
    }

    // =============================================================
    // BOTTOM NAVIGATION
    // =============================================================

    private fun addBottomNavigation() {

        val section = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }

        val refresh = createActionButton(
            "REFRESH",
            panel2
        )

        val scrollTop = createActionButton(
            "TOP",
            panel2
        )

        val autoTrade = createActionButton(
            if (autoTradeEnabled) "AUTO ON" else "AUTO OFF",
            if (autoTradeEnabled) green else panel2
        )

        refresh.setOnClickListener {
            rebuild()
        }

        scrollTop.setOnClickListener {
            rootScroll.smoothScrollTo(0, 0)
        }

        autoTrade.setOnClickListener {
            autoTradeEnabled = !autoTradeEnabled
            rebuild()
        }

        section.addView(
            refresh,
            LinearLayout.LayoutParams(
                0,
                dp(44),
                1f
            ).apply {
                rightMargin = dp(4)
            }
        )

        section.addView(
            scrollTop,
            LinearLayout.LayoutParams(
                0,
                dp(44),
                1f
            ).apply {
                leftMargin = dp(4)
                rightMargin = dp(4)
            }
        )

        section.addView(
            autoTrade,
            LinearLayout.LayoutParams(
                0,
                dp(44),
                1f
            ).apply {
                leftMargin = dp(4)
            }
        )

        rootLayout.addView(section)
    }
        // =============================================================
    // SECTION BUILDER
    // =============================================================

    private fun createSection(title: String): LinearLayout {

        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(4),
                dp(4),
                dp(4),
                dp(12)
            )
        }

        val titleView = TextView(this).apply {
            text = title
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(cyan)
            setPadding(
                dp(2),
                dp(4),
                dp(2),
                dp(8)
            )
        }

        section.addView(titleView)

        return section
    }

    // =============================================================
    // ACTION BUTTON
    // =============================================================

    private fun createActionButton(
        textValue: String,
        backgroundColor: Int
    ): Button {

        return Button(this).apply {

            text = textValue
            textSize = 12f

            setTextColor(
                if (
                    backgroundColor == green ||
                    backgroundColor == yellow
                ) {
                    Color.BLACK
                } else {
                    white
                }
            )

            setBackgroundColor(backgroundColor)

            setTypeface(null, Typeface.BOLD)

            isAllCaps = false

            minHeight = 0
            minimumHeight = 0

            setPadding(
                dp(8),
                dp(4),
                dp(8),
                dp(4)
            )
        }
    }

    // =============================================================
    // OAUTH
    // =============================================================

    private fun startDerivOAuth() {

        try {

            val state = UUID.randomUUID().toString()

            val verifierBytes = ByteArray(32)

            SecureRandom().nextBytes(verifierBytes)

            val verifier = Base64.encodeToString(
                verifierBytes,
                Base64.URL_SAFE or
                    Base64.NO_WRAP or
                    Base64.NO_PADDING
            )

            val challenge = createCodeChallenge(verifier)

            oauthState = state
            codeVerifier = verifier

            prefs.edit()
                .putString("oauth_state", state)
                .putString("code_verifier", verifier)
                .apply()

            val clientId =
                DerivApiClient.clientId(executionMode)

            val redirectUri =
                DerivApiClient.OAUTH_REDIRECT_URI

            val scope =
                DerivApiClient.OAUTH_SCOPE

            val authorizationUrl =
                Uri.Builder()
                    .scheme("https")
                    .authority("auth.deriv.com")
                    .appendPath("oauth2")
                    .appendPath("authorize")
                    .appendQueryParameter(
                        "app_id",
                        clientId
                    )
                    .appendQueryParameter(
                        "client_id",
                        clientId
                    )
                    .appendQueryParameter(
                        "redirect_uri",
                        redirectUri
                    )
                    .appendQueryParameter(
                        "response_type",
                        "code"
                    )
                    .appendQueryParameter(
                        "scope",
                        scope
                    )
                    .appendQueryParameter(
                        "state",
                        state
                    )
                    .appendQueryParameter(
                        "code_challenge",
                        challenge
                    )
                    .appendQueryParameter(
                        "code_challenge_method",
                        "S256"
                    )
                    .build()

            val intent = Intent(
                Intent.ACTION_VIEW,
                authorizationUrl
            )

            startActivity(intent)

        } catch (e: Exception) {

            Toast.makeText(
                this,
                "OAuth error: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // =============================================================
    // PKCE CODE CHALLENGE
    // =============================================================

    private fun createCodeChallenge(
        verifier: String
    ): String {

        val digest =
            MessageDigest.getInstance("SHA-256")

        val hash =
            digest.digest(
                verifier.toByteArray(Charsets.US_ASCII)
            )

        return Base64.encodeToString(
            hash,
            Base64.URL_SAFE or
                Base64.NO_WRAP or
                Base64.NO_PADDING
        )
    }

    // =============================================================
    // OAUTH CALLBACK
    // =============================================================

    private fun handleOAuthCallback(
        callbackIntent: Intent?
    ) {

        if (callbackIntent == null) {
            return
        }

        val uri =
            callbackIntent.data ?: return

        if (
            uri.scheme != "volatilitiesindicator" ||
            uri.host != "oauth-callback"
        ) {
            return
        }

        val error =
            uri.getQueryParameter("error")

        if (!error.isNullOrEmpty()) {

            Toast.makeText(
                this,
                "OAuth error: $error",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        val returnedState =
            uri.getQueryParameter("state")

        val code =
            uri.getQueryParameter("code")

        val savedState =
            prefs.getString(
                "oauth_state",
                ""
            ) ?: ""

        val verifier =
            prefs.getString(
                "code_verifier",
                ""
            ) ?: ""

        if (
            returnedState.isNullOrEmpty() ||
            returnedState != savedState
        ) {

            Toast.makeText(
                this,
                "OAuth security check failed",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        if (
            code.isNullOrEmpty() ||
            verifier.isEmpty()
        ) {

            Toast.makeText(
                this,
                "OAuth authorization code missing",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        exchangeOAuthCode(
            code,
            verifier
        )
    }

    // =============================================================
    // TOKEN EXCHANGE
    // =============================================================

    private fun exchangeOAuthCode(
        code: String,
        verifier: String
    ) {

        val clientId =
            DerivApiClient.clientId(executionMode)

        val redirectUri =
            DerivApiClient.OAUTH_REDIRECT_URI

        val requestBody =
            FormBody.Builder()
                .add(
                    "grant_type",
                    "authorization_code"
                )
                .add(
                    "code",
                    code
                )
                .add(
                    "client_id",
                    clientId
                )
                .add(
                    "redirect_uri",
                    redirectUri
                )
                .add(
                    "code_verifier",
                    verifier
                )
                .build()

        val request =
            Request.Builder()
                .url(
                    "https://auth.deriv.com/oauth2/token"
                )
                .post(requestBody)
                .build()

        runOnUiThread {

            Toast.makeText(
                this,
                "Connecting to Deriv...",
                Toast.LENGTH_SHORT
            ).show()
        }

        client.newCall(request)
            .enqueue(
                object : Callback {

                    override fun onFailure(
                        call: Call,
                        e: IOException
                    ) {

                        runOnUiThread {

                            Toast.makeText(
                                this@MainActivity,
                                "Connection failed: ${e.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response
                    ) {

                        val responseBody =
                            response.body?.string()
                                ?: ""

                        if (!response.isSuccessful) {

                            runOnUiThread {

                                Toast.makeText(
                                    this@MainActivity,
                                    "OAuth token error: ${response.code}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }

                            return
                        }

                        try {

                            val json =
                                JSONObject(responseBody)

                            val accessToken =
                                json.optString(
                                    "access_token",
                                    ""
                                )

                            if (
                                accessToken.isEmpty()
                            ) {

                                runOnUiThread {

                                    Toast.makeText(
                                        this@MainActivity,
                                        "No access token returned",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }

                                return
                            }

                            prefs.edit()
                                .putString(
                                    "access_token",
                                    accessToken
                                )
                                .apply()

                            connected = true

                            accountType =
                                executionMode

                            runOnUiThread {

                                Toast.makeText(
                                    this@MainActivity,
                                    "Deriv OAuth connected",
                                    Toast.LENGTH_SHORT
                                ).show()

                                rebuild()
                            }

                        } catch (
                            e: Exception
                        ) {

                            runOnUiThread {

                                Toast.makeText(
                                    this@MainActivity,
                                    "Invalid OAuth response",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
            )
    }

    // =============================================================
    // REBUILD
    // =============================================================

    private fun rebuild() {

        rootLayout.removeAllViews()

        addHeader()
        addPlatformSelector()
        addExecutionSelector()
        addAccountCard()
        addIndexMonitor()
        addAdminSection()
        addBottomNavigation()
    }

    // =============================================================
    // DIMENSION HELPER
    // =============================================================

    private fun dp(value: Int): Int {

        return (
            value *
                resources.displayMetrics.density
            ).toInt()
    }

    // =============================================================
    // ACTIVITY DESTROY
    // =============================================================

    override fun onDestroy() {

        super.onDestroy()

        // OkHttp requests are allowed to finish
        // without keeping an Activity reference.
    }
}

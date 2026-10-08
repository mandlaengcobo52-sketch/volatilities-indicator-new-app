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

    private val bg = Color.rgb(2, 10, 14)
    private val panel = Color.rgb(5, 25, 32)
    private val panel2 = Color.rgb(7, 32, 40)

    private val green = Color.rgb(0, 255, 90)
    private val cyan = Color.rgb(0, 210, 255)
    private val yellow = Color.rgb(255, 215, 0)
    private val red = Color.rgb(255, 55, 70)
    private val purple = Color.rgb(170, 60, 255)

    private val white = Color.WHITE
    private val muted = Color.rgb(165, 180, 185)

    // =============================================================
    // ROOT
    // =============================================================

    private lateinit var rootScroll: ScrollView
    private lateinit var rootLayout: LinearLayout

    // =============================================================
    // PLATFORM STATE
    // =============================================================

    private var currentPlatform = "USER"
    private var executionMode = "DEMO"

    private var autoTradeEnabled = false
    private var connected = false

    // =============================================================
    // ACCOUNT STATE
    // =============================================================

    private var accountId = ""
    private var accountType = ""
    private var accountBalance = 0.0
    private var accountCurrency = ""

    // =============================================================
    // DERIV CLIENT
    // =============================================================

    private val client = OkHttpClient()

    // =============================================================
    // OAUTH / PKCE
    // =============================================================

    private val prefs by lazy {
        getSharedPreferences(
            "deriv_oauth",
            MODE_PRIVATE
        )
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
    // INDEX COLOURS
    // =============================================================

    private fun indexColor(index: String): Int {
        return when (index) {
            "R_10" -> cyan
            "R_25" -> green
            "R_50" -> yellow
            "R_75" -> red
            "R_100" -> purple
            else -> white
        }
    }

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
            setPadding(
                dp(12),
                dp(10),
                dp(12),
                dp(18)
            )
            setBackgroundColor(bg)
        }

        rootScroll.addView(rootLayout)

        setContentView(rootScroll)

        addHeader()
        addPlatformSelector()
        addExecutionSelector()
        addAutoTrade()
        addAccountCard()
        addIndexMonitor()
        addAccountSummary()
        addBottomNavigation()
    }

    // =============================================================
    // HEADER
    // =============================================================

    private fun addHeader() {

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                dp(4),
                dp(4),
                dp(4),
                dp(12)
            )
        }

        // ---------------------------------------------------------
        // LOGO
        // ---------------------------------------------------------

        val logo = ImageView(this).apply {

            val resourceId = resources.getIdentifier(
                "volatilities_logo",
                "drawable",
                packageName
            )

            if (resourceId != 0) {
                setImageResource(resourceId)
            } else {
                setImageResource(
                    android.R.drawable.ic_dialog_info
                )
            }

            scaleType = ImageView.ScaleType.CENTER_INSIDE

            layoutParams = LinearLayout.LayoutParams(
                dp(82),
                dp(82)
            ).apply {
                rightMargin = dp(10)
            }
        }

        header.addView(logo)

        // ---------------------------------------------------------
        // TITLE
        // ---------------------------------------------------------

        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val title1 = TextView(this).apply {
            text = "VOLATILITIES"
            textSize = 25f
            setTextColor(white)
            setTypeface(
                null,
                Typeface.BOLD
            )
        }

        val title2 = TextView(this).apply {
            text = "INDICATOR"
            textSize = 25f
            setTextColor(green)
            setTypeface(
                null,
                Typeface.BOLD
            )
        }

        val subtitle = TextView(this).apply {
            text = "TRADING PLATFORM"
            textSize = 12f
            setTextColor(muted)
            setTypeface(
                null,
                Typeface.BOLD
            )
            setPadding(
                0,
                dp(3),
                0,
                0
            )
        }

        titleBox.addView(title1)
        titleBox.addView(title2)
        titleBox.addView(subtitle)

        header.addView(titleBox)

        // ---------------------------------------------------------
        // BELL
        // ---------------------------------------------------------

        val bell = TextView(this).apply {
            text = "♧"
            textSize = 30f
            setTextColor(white)
            gravity = Gravity.CENTER
            setPadding(
                dp(6),
                0,
                dp(2),
                0
            )

            setOnClickListener {
                Toast.makeText(
                    this@MainActivity,
                    "Notifications",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        header.addView(bell)

        rootLayout.addView(header)
    }

    // =============================================================
    // PLATFORM
    // =============================================================

    private fun addPlatformSelector() {

        val section = createSection("PLATFORM")

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val userButton = createActionButton(
            "👤  USER",
            if (currentPlatform == "USER") {
                green
            } else {
                panel2
            }
        )

        val adminButton = createActionButton(
            "👥  ADMIN",
            if (currentPlatform == "ADMIN") {
                green
            } else {
                panel2
            }
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
                dp(52),
                1f
            ).apply {
                rightMargin = dp(5)
            }
        )

        row.addView(
            adminButton,
            LinearLayout.LayoutParams(
                0,
                dp(52),
                1f
            ).apply {
                leftMargin = dp(5)
            }
        )

        section.addView(row)

        rootLayout.addView(section)
    }

    // =============================================================
    // EXECUTION MODE
    // =============================================================

    private fun addExecutionSelector() {

        val section = createSection(
            "EXECUTION MODE"
        )

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val demoButton = createActionButton(
            "📈  DEMO",
            if (executionMode == "DEMO") {
                green
            } else {
                panel2
            }
        )

        val realButton = createActionButton(
            "▥  REAL",
            if (executionMode == "REAL") {
                green
            } else {
                panel2
            }
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
                dp(52),
                1f
            ).apply {
                rightMargin = dp(5)
            }
        )

        row.addView(
            realButton,
            LinearLayout.LayoutParams(
                0,
                dp(52),
                1f
            ).apply {
                leftMargin = dp(5)
            }
        )

        section.addView(row)

        rootLayout.addView(section)
    }
        // =============================================================
    // AUTO TRADE
    // =============================================================

    private fun addAutoTrade() {

        val button = createActionButton(
            "⚡  AUTO TRADE: " +
                    if (autoTradeEnabled) "ON" else "OFF",
            if (autoTradeEnabled) green else panel2
        )

        button.setOnClickListener {
            autoTradeEnabled = !autoTradeEnabled
            rebuild()
        }

        rootLayout.addView(
            button,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            ).apply {
                topMargin = dp(10)
                bottomMargin = dp(10)
            }
        )
    }

    // =============================================================
    // DERIV EXECUTION ACCOUNT
    // =============================================================

    private fun addAccountCard() {

        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(14),
                dp(14),
                dp(14),
                dp(14)
            )
            setBackgroundColor(panel)
        }

        val title = TextView(this).apply {
            text = "⚡  DERIV EXECUTION ACCOUNT"
            textSize = 18f
            setTextColor(yellow)
            setTypeface(
                null,
                Typeface.BOLD
            )
        }

        section.addView(title)

        val connection = TextView(this).apply {
            text = if (connected) {
                "🟢  CONNECTION READY"
            } else {
                "🔴  NOT CONNECTED"
            }

            textSize = 16f
            setTypeface(
                null,
                Typeface.BOLD
            )

            setTextColor(
                if (connected) green else red
            )

            setPadding(
                0,
                dp(7),
                0,
                dp(4)
            )
        }

        section.addView(connection)

        val account = TextView(this).apply {
            text = "Account: " +
                    if (accountId.isEmpty()) {
                        "—"
                    } else {
                        accountId
                    }

            textSize = 16f
            setTextColor(white)
            setPadding(
                0,
                dp(2),
                0,
                dp(2)
            )
        }

        section.addView(account)

        val balance = TextView(this).apply {
            text = "Balance: " +
                    if (accountCurrency.isEmpty()) {
                        "—"
                    } else {
                        String.format(
                            "%s %.2f",
                            accountCurrency,
                            accountBalance
                        )
                    }

            textSize = 16f
            setTextColor(white)
            setPadding(
                0,
                dp(2),
                0,
                dp(2)
            )
        }

        section.addView(balance)

        val openPL = TextView(this).apply {
            text = "Open P/L: —"
            textSize = 16f
            setTextColor(white)
            setPadding(
                0,
                dp(2),
                0,
                dp(10)
            )
        }

        section.addView(openPL)

        // ---------------------------------------------------------
        // CONNECT / REFRESH
        // ---------------------------------------------------------

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val connectButton = createActionButton(
            "🔗  CONNECT",
            if (connected) panel2 else green
        )

        connectButton.setOnClickListener {
            startDerivOAuth()
        }

        val refreshButton = createActionButton(
            "⟳  REFRESH",
            panel2
        )

        refreshButton.setOnClickListener {
            rebuild()
        }

        actionRow.addView(
            connectButton,
            LinearLayout.LayoutParams(
                0,
                dp(52),
                1f
            ).apply {
                rightMargin = dp(5)
            }
        )

        actionRow.addView(
            refreshButton,
            LinearLayout.LayoutParams(
                0,
                dp(52),
                1f
            ).apply {
                leftMargin = dp(5)
            }
        )

        section.addView(actionRow)

        rootLayout.addView(
            section,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
                bottomMargin = dp(12)
            }
        )
    }

    // =============================================================
    // INDEX MONITOR
    // =============================================================

    private fun addIndexMonitor() {

        val section = createSection(
            "INDEX MONITOR"
        )

        indexes.forEach { index ->

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    dp(12),
                    dp(7),
                    dp(8),
                    dp(7)
                )
                setBackgroundColor(panel)

                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(72)
                    ).apply {
                        bottomMargin = dp(6)
                    }
            }

            // -----------------------------------------------------
            // INDEX NAME
            // -----------------------------------------------------

            val indexName = TextView(this).apply {
                text = index
                textSize = 22f
                setTextColor(
                    indexColor(index)
                )
                setTypeface(
                    null,
                    Typeface.BOLD
                )

                gravity = Gravity.CENTER_VERTICAL

                layoutParams =
                    LinearLayout.LayoutParams(
                        dp(88),
                        LinearLayout.LayoutParams.MATCH_PARENT
                    )
            }

            row.addView(indexName)

            // -----------------------------------------------------
            // SIMPLE MARKET LINE
            // -----------------------------------------------------

            val chart = TextView(this).apply {
                text = "⌁⌁⌁⌁⌁"
                textSize = 28f
                setTextColor(
                    indexColor(index)
                )
                gravity = Gravity.CENTER

                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        1f
                    )
            }

            row.addView(chart)

            // -----------------------------------------------------
            // WAITING
            // -----------------------------------------------------

            val waiting = TextView(this).apply {
                text = "WAITING"
                textSize = 13f
                setTextColor(yellow)
                setTypeface(
                    null,
                    Typeface.BOLD
                )
                gravity = Gravity.CENTER

                setBackgroundColor(
                    panel2
                )

                layoutParams =
                    LinearLayout.LayoutParams(
                        dp(100),
                        dp(48)
                    ).apply {
                        rightMargin = dp(5)
                    }
            }

            row.addView(waiting)

            // -----------------------------------------------------
            // ARROW
            // -----------------------------------------------------

            val arrow = TextView(this).apply {
                text = "›"
                textSize = 34f
                setTextColor(white)
                gravity = Gravity.CENTER

                layoutParams =
                    LinearLayout.LayoutParams(
                        dp(28),
                        LinearLayout.LayoutParams.MATCH_PARENT
                    )
            }

            row.addView(arrow)

            row.setOnClickListener {
                Toast.makeText(
                    this,
                    "$index selected",
                    Toast.LENGTH_SHORT
                ).show()
            }

            section.addView(row)
        }

        rootLayout.addView(section)
    }

    // =============================================================
    // ACCOUNT SUMMARY
    // =============================================================

    private fun addAccountSummary() {

        val section = createSection(
            "ACCOUNT SUMMARY"
        )

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        row.addView(
            summaryCard(
                "OPEN",
                "0",
                cyan
            ),
            LinearLayout.LayoutParams(
                0,
                dp(82),
                1f
            ).apply {
                rightMargin = dp(3)
            }
        )

        row.addView(
            summaryCard(
                "TOTAL P/L",
                "0.00",
                cyan
            ),
            LinearLayout.LayoutParams(
                0,
                dp(82),
                1f
            ).apply {
                leftMargin = dp(3)
                rightMargin = dp(3)
            }
        )

        row.addView(
            summaryCard(
                "WINS",
                "0",
                green
            ),
            LinearLayout.LayoutParams(
                0,
                dp(82),
                1f
            ).apply {
                leftMargin = dp(3)
                rightMargin = dp(3)
            }
        )

        row.addView(
            summaryCard(
                "LOSSES",
                "0",
                red
            ),
            LinearLayout.LayoutParams(
                0,
                dp(82),
                1f
            ).apply {
                leftMargin = dp(3)
            }
        )

        section.addView(row)

        rootLayout.addView(section)
    }

    // =============================================================
    // SUMMARY CARD
    // =============================================================

    private fun summaryCard(
        title: String,
        value: String,
        color: Int
    ): LinearLayout {

        return LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(panel)

            val titleView = TextView(
                this@MainActivity
            ).apply {
                text = title
                textSize = 12f
                setTextColor(color)
                setTypeface(
                    null,
                    Typeface.BOLD
                )
                gravity = Gravity.CENTER
            }

            val valueView = TextView(
                this@MainActivity
            ).apply {
                text = value
                textSize = 17f
                setTextColor(color)
                setTypeface(
                    null,
                    Typeface.BOLD
                )
                gravity = Gravity.CENTER
            }

            addView(titleView)
            addView(valueView)
        }
    }
        // =============================================================
    // BOTTOM NAVIGATION
    // =============================================================

    private fun addBottomNavigation() {

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(
                dp(6),
                dp(6),
                dp(6),
                dp(6)
            )
            setBackgroundColor(panel)
        }

        val home = createNavigationItem(
            "⌂",
            "Home",
            true
        )

        val trade = createNavigationItem(
            "▥",
            "Trade",
            false
        )

        val analysis = createNavigationItem(
            "⌁",
            "Analysis",
            false
        )

        val news = createNavigationItem(
            "▤",
            "News",
            false
        )

        val settings = createNavigationItem(
            "⚙",
            "Settings",
            false
        )

        nav.addView(
            home,
            LinearLayout.LayoutParams(
                0,
                dp(78),
                1f
            )
        )

        nav.addView(
            trade,
            LinearLayout.LayoutParams(
                0,
                dp(78),
                1f
            )
        )

        nav.addView(
            analysis,
            LinearLayout.LayoutParams(
                0,
                dp(78),
                1f
            )
        )

        nav.addView(
            news,
            LinearLayout.LayoutParams(
                0,
                dp(78),
                1f
            )
        )

        nav.addView(
            settings,
            LinearLayout.LayoutParams(
                0,
                dp(78),
                1f
            )
        )

        rootLayout.addView(
            nav,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(90)
            ).apply {
                topMargin = dp(12)
            }
        )
    }

    // =============================================================
    // NAVIGATION ITEM
    // =============================================================

    private fun createNavigationItem(
        icon: String,
        label: String,
        selected: Boolean
    ): LinearLayout {

        return LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER

            if (selected) {
                setBackgroundColor(
                    Color.rgb(0, 70, 45)
                )
            }

            val iconView = TextView(
                this@MainActivity
            ).apply {
                text = icon
                textSize = 22f
                setTextColor(
                    if (selected) green else white
                )
                gravity = Gravity.CENTER
            }

            val labelView = TextView(
                this@MainActivity
            ).apply {
                text = label
                textSize = 12f
                setTextColor(
                    if (selected) green else white
                )
                setTypeface(
                    null,
                    Typeface.BOLD
                )
                gravity = Gravity.CENTER
            }

            addView(iconView)
            addView(labelView)

            setOnClickListener {

                Toast.makeText(
                    this@MainActivity,
                    label,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // =============================================================
    // SECTION
    // =============================================================

    private fun createSection(
        title: String
    ): LinearLayout {

        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val titleView = TextView(this).apply {
            text = title
            textSize = 18f
            setTextColor(white)
            setTypeface(
                null,
                Typeface.BOLD
            )
            setPadding(
                0,
                dp(8),
                0,
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
        background: Int
    ): Button {

        return Button(this).apply {

            text = textValue
            textSize = 15f

            setTextColor(
                if (background == green) {
                    Color.BLACK
                } else {
                    white
                }
            )

            setTypeface(
                null,
                Typeface.BOLD
            )

            setBackgroundColor(background)

            isAllCaps = false

            gravity = Gravity.CENTER

            minHeight = 0
            minimumHeight = 0

            stateListAnimator = null
        }
    }

    // =============================================================
    // START DERIV OAUTH
    // =============================================================

    private fun startDerivOAuth() {

        try {

            val state =
                "android-" +
                        UUID.randomUUID()
                            .toString()

            val verifier =
                generateCodeVerifier()

            val challenge =
                generateCodeChallenge(
                    verifier
                )

            oauthState = state
            codeVerifier = verifier

            prefs.edit()
                .putString(
                    "oauth_state",
                    state
                )
                .putString(
                    "code_verifier",
                    verifier
                )
                .apply()

            val clientId =
                DerivApiClient.clientId(
                    executionMode
                )

            val redirectUri =
                DerivApiClient.OAUTH_REDIRECT_URI

            val scope =
                DerivApiClient.OAUTH_SCOPE

            // IMPORTANT:
            // This is the corrected Deriv OAuth
            // authorization endpoint.
            //
            // /oauth2/authorize  = WRONG
            // /oauth2/auth       = CORRECT

            val authorizationUrl =
                Uri.parse(
                    "https://auth.deriv.com/oauth2/auth"
                )
                    .buildUpon()
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

            val browserIntent =
                Intent(
                    Intent.ACTION_VIEW,
                    authorizationUrl
                )

            startActivity(browserIntent)

        } catch (e: Exception) {

            Toast.makeText(
                this,
                "Unable to start Deriv connection: " +
                        e.message,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // =============================================================
    // PKCE - CODE VERIFIER
    // =============================================================

    private fun generateCodeVerifier(): String {

        val bytes = ByteArray(32)

        SecureRandom().nextBytes(bytes)

        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or
                    Base64.NO_WRAP or
                    Base64.NO_PADDING
        )
    }

    // =============================================================
    // PKCE - CODE CHALLENGE
    // =============================================================

    private fun generateCodeChallenge(
        verifier: String
    ): String {

        val digest =
            MessageDigest.getInstance(
                "SHA-256"
            )

        val hashed =
            digest.digest(
                verifier.toByteArray(
                    Charsets.US_ASCII
                )
            )

        return Base64.encodeToString(
            hashed,
            Base64.URL_SAFE or
                    Base64.NO_WRAP or
                    Base64.NO_PADDING
        )
    }

    // =============================================================
    // OAUTH CALLBACK
    // =============================================================

    private fun handleOAuthCallback(
        intent: Intent?
    ) {

        val uri =
            intent?.data
                ?: return

        if (
            uri.scheme !=
                "volatilitiesindicator"
        ) {
            return
        }

        if (
            uri.host !=
                "oauth-callback"
        ) {
            return
        }

        val returnedState =
            uri.getQueryParameter(
                "state"
            )

        val savedState =
            prefs.getString(
                "oauth_state",
                null
            )

        if (
            returnedState.isNullOrEmpty() ||
            savedState.isNullOrEmpty() ||
            returnedState != savedState
        ) {

            Toast.makeText(
                this,
                "OAuth state verification failed",
                Toast.LENGTH_LONG
            ).show()

            return
        }
                val code =
            uri.getQueryParameter(
                "code"
            )

        val error =
            uri.getQueryParameter(
                "error"
            )

        if (!error.isNullOrEmpty()) {

            Toast.makeText(
                this,
                "Deriv connection cancelled: $error",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        if (code.isNullOrEmpty()) {

            Toast.makeText(
                this,
                "No OAuth authorization code received",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        exchangeCodeForToken(code)
    }

    // =============================================================
    // EXCHANGE AUTHORIZATION CODE FOR TOKEN
    // =============================================================

    private fun exchangeCodeForToken(
        code: String
    ) {

        val verifier =
            prefs.getString(
                "code_verifier",
                null
            )

        if (verifier.isNullOrEmpty()) {

            Toast.makeText(
                this,
                "OAuth verifier is missing",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        val clientId =
            DerivApiClient.clientId(
                executionMode
            )

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

        client.newCall(
            request
        ).enqueue(
            object : Callback {

                override fun onFailure(
                    call: Call,
                    e: IOException
                ) {

                    runOnUiThread {

                        Toast.makeText(
                            this@MainActivity,
                            "Deriv connection failed: " +
                                    e.message,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                override fun onResponse(
                    call: Call,
                    response: Response
                ) {

                    val responseText =
                        response.body
                            ?.string()
                            ?: ""

                    if (!response.isSuccessful) {

                        runOnUiThread {

                            Toast.makeText(
                                this@MainActivity,
                                "Deriv OAuth error: " +
                                        responseText,
                                Toast.LENGTH_LONG
                            ).show()
                        }

                        return
                    }

                    try {

                        val json =
                            JSONObject(
                                responseText
                            )

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
                                    "No access token received",
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
                            .remove(
                                "oauth_state"
                            )
                            .remove(
                                "code_verifier"
                            )
                            .apply()

                        connected = true

                        runOnUiThread {

                            Toast.makeText(
                                this@MainActivity,
                                "Deriv connected",
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
                                "Invalid Deriv response: " +
                                        e.message,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        )
    }

    // =============================================================
    // REBUILD SCREEN
    // =============================================================

    private fun rebuild() {

        rootLayout.removeAllViews()

        addHeader()
        addPlatformSelector()
        addExecutionSelector()
        addAutoTrade()
        addAccountCard()
        addIndexMonitor()
        addAccountSummary()
        addBottomNavigation()

        rootScroll.post {
            rootScroll.scrollTo(
                0,
                0
            )
        }
    }

    // =============================================================
    // DP HELPER
    // =============================================================

    private fun dp(
        value: Int
    ): Int {

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
    }
        // =============================================================
    // END OF MAIN ACTIVITY
    // =============================================================

}

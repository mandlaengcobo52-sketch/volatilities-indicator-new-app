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

class MainActivity : Activity(), DerivApiClient.Callback {

    // =============================================================
    // COLOURS
    // =============================================================

    private val BG = Color.rgb(2, 12, 16)
    private val CARD = Color.rgb(5, 25, 32)
    private val CARD2 = Color.rgb(7, 32, 40)
    private val BORDER = Color.rgb(0, 95, 75)

    private val GREEN = Color.rgb(0, 255, 90)
    private val CYAN = Color.rgb(0, 220, 255)
    private val YELLOW = Color.rgb(255, 220, 0)
    private val RED = Color.rgb(255, 45, 60)
    private val PURPLE = Color.rgb(170, 50, 255)

    private val WHITE = Color.WHITE
    private val MUTED = Color.rgb(170, 185, 190)

    // =============================================================
    // ROOT
    // =============================================================

    private lateinit var rootScroll: ScrollView
    private lateinit var rootLayout: LinearLayout

    // =============================================================
    // PLATFORM
    // =============================================================

    private var currentPlatform = "USER"

    private var executionMode = "DEMO"

    // =============================================================
    // AUTO TRADE
    // =============================================================

    private var autoTradeEnabled = false

    // =============================================================
    // DERIV CONNECTION
    // =============================================================

    private var connected = false

    private var accountId = ""

    private var accountType = ""

    private var accountBalance = 0.0

    private var accountCurrency = ""

    private val derivApiClient by lazy {
        DerivApiClient(this)
    }

    private val httpClient = OkHttpClient()

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

    private fun indexColor(
        index: String
    ): Int {

        return when (index) {

            "R_10" -> CYAN

            "R_25" -> GREEN

            "R_50" -> YELLOW

            "R_75" -> RED

            "R_100" -> PURPLE

            else -> WHITE
        }
    }

    // =============================================================
    // ACTIVITY
    // =============================================================

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        window.statusBarColor = BG

        window.navigationBarColor = BG

        handleOAuthCallback(
            intent
        )

        buildMainScreen()
    }

    override fun onNewIntent(
        intent: Intent?
    ) {

        super.onNewIntent(
            intent
        )

        if (
            intent != null
        ) {

            setIntent(
                intent
            )

            handleOAuthCallback(
                intent
            )
        }
    }

    // =============================================================
    // MAIN SCREEN
    // =============================================================

    private fun buildMainScreen() {

        rootScroll =
            ScrollView(this).apply {

                setBackgroundColor(
                    BG
                )

                isFillViewport =
                    true

                overScrollMode =
                    View.OVER_SCROLL_NEVER
            }

        rootLayout =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    dp(14),
                    dp(8),
                    dp(14),
                    dp(24)
                )

                setBackgroundColor(
                    BG
                )
            }

        rootScroll.addView(
            rootLayout
        )

        setContentView(
            rootScroll
        )

        addHeader()

        if (
            currentPlatform == "ADMIN"
        ) {

            addPlatformSelector()
        }

        addExecutionSelector()

        addAccountCard()

        addIndexMonitor()

        if (
            currentPlatform == "ADMIN"
        ) {

            addAdminFeatures()
        }

        addBottomNavigation()
    }

    // =============================================================
    // HEADER
    // =============================================================

    private fun addHeader() {

        val header =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL

                setPadding(
                    dp(2),
                    dp(4),
                    dp(2),
                    dp(10)
                )
            }

        val menu =
            TextView(this).apply {

                text = "☰"

                textSize = 28f

                setTextColor(
                    WHITE
                )

                gravity =
                    Gravity.CENTER

                layoutParams =
                    LinearLayout.LayoutParams(
                        dp(42),
                        dp(52)
                    )
            }

        menu.setOnClickListener {

            if (
                currentPlatform == "ADMIN"
            ) {

                showAdminMenu()

            } else {

                showUserMenu()
            }
        }

        header.addView(
            menu
        )

        val titleBox =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL

                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
            }

        val logo =
            ImageView(this).apply {

                setImageResource(
                    R.drawable.volatilities_indicator_logo
                )

                scaleType =
                    ImageView.ScaleType.CENTER_INSIDE

                contentDescription =
                    "Volatilities Indicator"

                layoutParams =
                    LinearLayout.LayoutParams(
                        dp(48),
                        dp(48)
                    )
            }

        titleBox.addView(
            logo
        )

        val titleColumn =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
            }

        val title =
            TextView(this).apply {

                text =
                    "VOLATILITIES"

                textSize = 16f

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                setTextColor(
                    WHITE
                )
            }

        titleColumn.addView(
            title
        )

        val indicator =
            TextView(this).apply {

                text =
                    "INDICATOR"

                textSize = 16f

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                setTextColor(
                    GREEN
                )
            }

        titleColumn.addView(
            indicator
        )

        val subtitle =
            TextView(this).apply {

                text =
                    "TRADE SMARTER TODAY"

                textSize = 8f

                setTextColor(
                    MUTED
                )
            }

        titleColumn.addView(
            subtitle
        )

        titleBox.addView(
            titleColumn
        )

        header.addView(
            titleBox
        )

        val bell =
            TextView(this).apply {

                text = "🔔"

                textSize = 28f

                setTextColor(
                    WHITE
                )

                gravity =
                    Gravity.CENTER

                layoutParams =
                    LinearLayout.LayoutParams(
                        dp(44),
                        dp(52)
                    )
            }

        bell.setOnClickListener {

            Toast.makeText(
                this,
                "Notifications",
                Toast.LENGTH_SHORT
            ).show()
        }

        header.addView(
            bell
        )

        rootLayout.addView(
            header
        )
    }

    // =============================================================
    // PLATFORM SELECTOR
    // =============================================================

    private fun addPlatformSelector() {

        addSectionTitle(
            "PLATFORM"
        )

        val row =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL
            }

        val user =
            createMainButton(
                "●   USER",
                currentPlatform == "USER"
            )

        val admin =
            createMainButton(
                "●   ADMIN",
                currentPlatform == "ADMIN"
            )

        user.setOnClickListener {

            currentPlatform =
                "USER"

            rebuild()
        }

        admin.setOnClickListener {

            currentPlatform =
                "ADMIN"

            rebuild()
        }

        row.addView(
            user,
            LinearLayout.LayoutParams(
                0,
                dp(54),
                1f
            ).apply {

                rightMargin =
                    dp(5)
            }
        )

        row.addView(
            admin,
            LinearLayout.LayoutParams(
                0,
                dp(54),
                1f
            ).apply {

                leftMargin =
                    dp(5)
            }
        )

        rootLayout.addView(
            row
        )
    }

    // =============================================================
    // EXECUTION MODE
    // =============================================================

    private fun addExecutionSelector() {

        addSectionTitle(
            "EXECUTION MODE"
        )

        val row =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL
            }

        val demo =
            createMainButton(
                "▥   DEMO",
                executionMode == "DEMO"
            )

        val real =
            createMainButton(
                "▥   REAL",
                executionMode == "REAL"
            )

        demo.setOnClickListener {

            if (
                executionMode != "DEMO"
            ) {

                try {

                    derivApiClient.disconnect()

                } catch (_: Exception) {
                }

                connected =
                    false

                accountId =
                    ""

                accountType =
                    ""

                accountBalance =
                    0.0

                accountCurrency =
                    ""
            }

            executionMode =
                "DEMO"

            rebuild()
        }

        real.setOnClickListener {

            if (
                executionMode != "REAL"
            ) {

                try {

                    derivApiClient.disconnect()

                } catch (_: Exception) {
                }

                connected =
                    false

                accountId =
                    ""

                accountType =
                    ""

                accountBalance =
                    0.0

                accountCurrency =
                    ""
            }

            executionMode =
                "REAL"

            rebuild()
        }

        row.addView(
            demo,
            LinearLayout.LayoutParams(
                0,
                dp(54),
                1f
            ).apply {

                rightMargin =
                    dp(5)
            }
        )

        row.addView(
            real,
            LinearLayout.LayoutParams(
                0,
                dp(54),
                1f
            ).apply {

                leftMargin =
                    dp(5)
            }
        )

        rootLayout.addView(
            row
        )
    }

        // =============================================================
    // AUTO TRADE
    // =============================================================

    private fun addAutoTradeControl() {

        val card =
            createCard()

        val row =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL

                setPadding(
                    dp(10),
                    dp(7),
                    dp(10),
                    dp(7)
                )
            }

        val title =
            TextView(this).apply {

                text =
                    "AUTO TRADE"

                textSize =
                    14f

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                setTextColor(
                    WHITE
                )

                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
            }

        row.addView(
            title
        )

        val status =
            TextView(this).apply {

                text =
                    if (
                        autoTradeEnabled
                    ) {
                        "● ON"
                    } else {
                        "● OFF"
                    }

                textSize =
                    13f

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                setTextColor(
                    if (
                        autoTradeEnabled
                    ) {
                        GREEN
                    } else {
                        RED
                    }
                )

                gravity =
                    Gravity.CENTER

                setPadding(
                    dp(12),
                    dp(7),
                    dp(12),
                    dp(7)
                )
            }

        row.addView(
            status
        )

        row.setOnClickListener {

            autoTradeEnabled =
                !autoTradeEnabled

            status.text =
                if (
                    autoTradeEnabled
                ) {
                    "● ON"
                } else {
                    "● OFF"
                }

            status.setTextColor(
                if (
                    autoTradeEnabled
                ) {
                    GREEN
                } else {
                    RED
                }
            )

            Toast.makeText(
                this,
                if (
                    autoTradeEnabled
                ) {
                    "AUTO TRADE ON"
                } else {
                    "AUTO TRADE OFF"
                },
                Toast.LENGTH_SHORT
            ).show()
        }

        card.addView(
            row
        )

        rootLayout.addView(
            card
        )
    }

    // =============================================================
    // ACCOUNT CARD
    // =============================================================

    private fun addAccountCard() {

        addAutoTradeControl()

        val card =
            createCard()

        val titleRow =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL
            }

        val accountTitle =
            TextView(this).apply {

                text =
                    "ACCOUNT"

                textSize =
                    14f

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                setTextColor(
                    WHITE
                )

                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
            }

        titleRow.addView(
            accountTitle
        )

        val connectionStatus =
            TextView(this).apply {

                text =
                    if (
                        connected
                    ) {
                        "● CONNECTED"
                    } else {
                        "● DISCONNECTED"
                    }

                textSize =
                    11f

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                setTextColor(
                    if (
                        connected
                    ) {
                        GREEN
                    } else {
                        RED
                    }
                )
            }

        titleRow.addView(
            connectionStatus
        )

        card.addView(
            titleRow
        )

        val information =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    0,
                    dp(8),
                    0,
                    dp(5)
                )
            }

        val accountText =
            TextView(this).apply {

                text =
                    if (
                        accountId.isNotEmpty()
                    ) {

                        "ACCOUNT ID   $accountId"

                    } else {

                        "ACCOUNT ID   —"
                    }

                textSize =
                    11f

                setTextColor(
                    MUTED
                )
            }

        information.addView(
            accountText
        )

        val typeText =
            TextView(this).apply {

                text =
                    if (
                        accountType.isNotEmpty()
                    ) {

                        "TYPE          $accountType"

                    } else {

                        "TYPE          $executionMode"
                    }

                textSize =
                    11f

                setTextColor(
                    MUTED
                )
            }

        information.addView(
            typeText
        )

        val balanceText =
            TextView(this).apply {

                text =
                    "BALANCE       ${
                        if (
                            accountCurrency.isNotEmpty()
                        ) {
                            accountCurrency
                        } else {
                            ""
                        }
                    } ${
                        String.format(
                            "%.2f",
                            accountBalance
                        )
                    }"

                textSize =
                    12f

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                setTextColor(
                    CYAN
                )
            }

        information.addView(
            balanceText
        )

        card.addView(
            information
        )

        val connectButton =
            Button(this).apply {

                text =
                    if (
                        connected
                    ) {

                        "DISCONNECT"

                    } else {

                        if (
                            executionMode == "REAL"
                        ) {
                            "CONNECT REAL"
                        } else {
                            "CONNECT DEMO"
                        }
                    }

                textSize =
                    11f

                setTextColor(
                    WHITE
                )

                isAllCaps =
                    false

                setPadding(
                    dp(5),
                    0,
                    dp(5),
                    0
                )

                setBackgroundColor(
                    if (
                        connected
                    ) {
                        RED
                    } else {
                        GREEN
                    }
                )
            }

        connectButton.setOnClickListener {

            if (
                connected
            ) {

                disconnectDeriv()

            } else {

                beginDerivConnection()
            }
        }

        card.addView(
            connectButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44)
            ).apply {

                topMargin =
                    dp(5)
            }
        )

        rootLayout.addView(
            card
        )
    }

    // =============================================================
    // INDEX MONITOR
    // =============================================================

    private fun addIndexMonitor() {

        addSectionTitle(
            "INDEX MONITOR"
        )

        for (
            index in indexes
        ) {

            addIndexCard(
                index
            )
        }
    }

    // =============================================================
    // INDEX CARD
    // =============================================================

    private fun addIndexCard(
        index: String
    ) {

        val card =
            createCard()

        val header =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL
            }

        val indexTitle =
            TextView(this).apply {

                text =
                    index

                textSize =
                    16f

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                setTextColor(
                    indexColor(
                        index
                    )
                )

                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
            }

        header.addView(
            indexTitle
        )

        val live =
            TextView(this).apply {

                text =
                    "● LIVE"

                textSize =
                    9f

                setTextColor(
                    GREEN
                )
            }

        header.addView(
            live
        )

        card.addView(
            header
        )

        val price =
            TextView(this).apply {

                text =
                    "CURRENT PRICE   WAITING"

                textSize =
                    12f

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                setTextColor(
                    WHITE
                )

                setPadding(
                    0,
                    dp(7),
                    0,
                    dp(4)
                )
            }

        card.addView(
            price
        )

        val phaseRow =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL
            }

        val phases =
            listOf(
                "BUILDING UP",
                "SETUP",
                "NOW",
                "ACTIVE",
                "EXPIRED",
                "EXIT TRADE"
            )

        for (
            phase in phases
        ) {

            val phaseView =
                TextView(this).apply {

                    text =
                        phase

                    textSize =
                        7f

                    gravity =
                        Gravity.CENTER

                    setTextColor(
                        MUTED
                    )

                    setPadding(
                        dp(4),
                        dp(6),
                        dp(4),
                        dp(6)
                    )

                    layoutParams =
                        LinearLayout.LayoutParams(
                            0,
                            dp(32),
                            1f
                        )
                }

            phaseRow.addView(
                phaseView
            )
        }

        card.addView(
            phaseRow
        )

        val support =
            TextView(this).apply {

                text =
                    "SUPPORT       AUTO"

                textSize =
                    10f

                setTextColor(
                    GREEN
                )

                setPadding(
                    0,
                    dp(7),
                    0,
                    dp(2)
                )
            }

        card.addView(
            support
        )

        val resistance =
            TextView(this).apply {

                text =
                    "RESISTANCE    AUTO"

                textSize =
                    10f

                setTextColor(
                    RED
                )
            }

        card.addView(
            resistance
        )

        val direction =
            TextView(this).apply {

                text =
                    "DIRECTION     WAITING"

                textSize =
                    11f

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                setTextColor(
                    YELLOW
                )

                setPadding(
                    0,
                    dp(7),
                    0,
                    dp(3)
                )
            }

        card.addView(
            direction
        )

        val qualifying =
            TextView(this).apply {

                text =
                    "QUALIFYING    —"

                textSize =
                    10f

                setTextColor(
                    CYAN
                )
            }

        card.addView(
            qualifying
        )

        val target =
            TextView(this).apply {

                text =
                    "TARGET PRICE  —"

                textSize =
                    10f

                setTextColor(
                    WHITE
                )

                setPadding(
                    0,
                    dp(3),
                    0,
                    dp(3)
                )
            }

        card.addView(
            target
        )

        rootLayout.addView(
            card
        )
    }

    // =============================================================
    // ADMIN FEATURES
    // =============================================================

    private fun addAdminFeatures() {

        addSectionTitle(
            "ADMIN CONTROL"
        )

        val card =
            createCard()

        val title =
            TextView(this).apply {

                text =
                    "TRADING CONTROL"

                textSize =
                    14f

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                setTextColor(
                    WHITE
                )
            }

        card.addView(
            title
        )

        val description =
            TextView(this).apply {

                text =
                    "Manage platform execution and monitoring."

                textSize =
                    10f

                setTextColor(
                    MUTED
                )

                setPadding(
                    0,
                    dp(5),
                    0,
                    dp(8)
                )
            }

        card.addView(
            description
        )

        val users =
            Button(this).apply {

                text =
                    "USER ACCOUNTS"

                textSize =
                    11f

                isAllCaps =
                    false

                setTextColor(
                    WHITE
                )

                setBackgroundColor(
                    CARD2
                )
            }

        users.setOnClickListener {

            showUserAccounts()
        }

        card.addView(
            users,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44)
            )
        )

        val refresh =
            Button(this).apply {

                text =
                    "REFRESH DATA"

                textSize =
                    11f

                isAllCaps =
                    false

                setTextColor(
                    WHITE
                )

                setBackgroundColor(
                    CARD2
                )
            }

        refresh.setOnClickListener {

            rebuild()
        }

        card.addView(
            refresh,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44)
            ).apply {

                topMargin =
                    dp(7)
            }
        )

        rootLayout.addView(
            card
        )
    }
        // =============================================================
    // BOTTOM NAVIGATION
    // =============================================================

    private fun addBottomNavigation() {

        val spacer =
            View(this).apply {

                layoutParams =
                    LinearLayout.LayoutParams(
                        1,
                        dp(12)
                    )
            }

        rootLayout.addView(
            spacer
        )

        val nav =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER

                setPadding(
                    dp(4),
                    dp(4),
                    dp(4),
                    dp(4)
                )

                setBackgroundColor(
                    CARD
                )
            }

        val dashboard =
            createNavButton(
                "⌂",
                "DASHBOARD"
            )

        dashboard.setOnClickListener {

            rebuild()
        }

        val refresh =
            createNavButton(
                "↻",
                "REFRESH"
            )

        refresh.setOnClickListener {

            if (
                connected
            ) {

                derivApiClient.requestBalance()

            } else {

                rebuild()
            }
        }

        val disconnect =
            createNavButton(
                "⏻",
                "DISCONNECT"
            )

        disconnect.setOnClickListener {

            if (
                connected
            ) {

                disconnectDeriv()

            } else {

                Toast.makeText(
                    this,
                    "Deriv is already disconnected.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        nav.addView(
            dashboard,
            LinearLayout.LayoutParams(
                0,
                dp(55),
                1f
            )
        )

        nav.addView(
            refresh,
            LinearLayout.LayoutParams(
                0,
                dp(55),
                1f
            )
        )

        nav.addView(
            disconnect,
            LinearLayout.LayoutParams(
                0,
                dp(55),
                1f
            )
        )

        rootLayout.addView(
            nav
        )
    }

    // =============================================================
    // DERIV CONNECTION
    // =============================================================

    private fun beginDerivConnection() {

        /*
         * If a saved token exists for the selected mode,
         * use it directly.
         *
         * Otherwise start OAuth.
         */

        val tokenKey =
            if (
                executionMode.equals(
                    "REAL",
                    ignoreCase = true
                )
            ) {

                "real_token"

            } else {

                "demo_token"
            }

        val savedToken =
            prefs.getString(
                tokenKey,
                ""
            ) ?: ""

        if (
            savedToken.isNotBlank()
        ) {

            connectWithToken(
                savedToken
            )

        } else {

            startDerivOAuth()
        }
    }

    // =============================================================
    // DERIV OAUTH
    // =============================================================

    private fun startDerivOAuth() {

        oauthState =
            UUID.randomUUID().toString()

        codeVerifier =
            generateCodeVerifier()

        /*
         * Save the mode together with the PKCE values.
         * This is important because after returning from
         * the browser we must know whether the user selected
         * DEMO or REAL.
         */

        prefs.edit()
            .putString(
                "oauth_state",
                oauthState
            )
            .putString(
                "code_verifier",
                codeVerifier
            )
            .putString(
                "oauth_mode",
                executionMode
            )
            .apply()

        val challenge =
            generateCodeChallenge(
                codeVerifier
            )

        val clientId =
            DerivApiClient.clientId(
                executionMode
            )

        val redirectUri =
            DerivApiClient.OAUTH_REDIRECT_URI

        val authorizationUrl =
            Uri.parse(
                DerivApiClient.OAUTH_AUTH_URL
            )
                .buildUpon()
                .appendQueryParameter(
                    "response_type",
                    "code"
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
                    "scope",
                    DerivApiClient.OAUTH_SCOPE
                )
                .appendQueryParameter(
                    "state",
                    oauthState
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

        try {

            val intent =
                Intent(
                    Intent.ACTION_VIEW,
                    authorizationUrl
                )

            startActivity(
                intent
            )

        } catch (
            e: Exception
        ) {

            Toast.makeText(
                this,
                "Unable to open Deriv login: ${
                    e.message
                }",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // =============================================================
    // OAUTH CALLBACK
    // =============================================================

    private fun handleOAuthCallback(
        intent: Intent?
    ) {

        val data =
            intent?.data
                ?: return

        /*
         * The website callback must redirect into:
         *
         * volatilitiesindicator://oauth-callback
         */

        if (
            data.scheme !=
            "volatilitiesindicator"
        ) {

            return
        }

        if (
            data.host !=
            "oauth-callback"
        ) {

            return
        }

        val error =
            data.getQueryParameter(
                "error"
            )

        if (
            !error.isNullOrBlank()
        ) {

            Toast.makeText(
                this,
                "Deriv OAuth error: $error",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        val code =
            data.getQueryParameter(
                "code"
            )

        val returnedState =
            data.getQueryParameter(
                "state"
            )

        if (
            code.isNullOrBlank()
        ) {

            Toast.makeText(
                this,
                "No Deriv authorization code received.",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        val savedState =
            prefs.getString(
                "oauth_state",
                ""
            ) ?: ""

        if (
            savedState.isBlank() ||
            returnedState.isNullOrBlank() ||
            returnedState != savedState
        ) {

            Toast.makeText(
                this,
                "OAuth security check failed.",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        val mode =
            prefs.getString(
                "oauth_mode",
                executionMode
            ) ?: executionMode

        executionMode =
            if (
                mode.equals(
                    "REAL",
                    ignoreCase = true
                )
            ) {

                "REAL"

            } else {

                "DEMO"
            }

        exchangeOAuthCode(
            code
        )
    }

    // =============================================================
    // OAUTH TOKEN EXCHANGE
    // =============================================================

    private fun exchangeOAuthCode(
        code: String
    ) {

        val verifier =
            prefs.getString(
                "code_verifier",
                ""
            ) ?: ""

        if (
            verifier.isBlank()
        ) {

            Toast.makeText(
                this,
                "OAuth verifier is missing.",
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
                    DerivApiClient.OAUTH_TOKEN_URL
                )
                .post(
                    requestBody
                )
                .build()

        Toast.makeText(
            this,
            "Connecting to Deriv $executionMode...",
            Toast.LENGTH_SHORT
        ).show()

        httpClient.newCall(
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
                            "OAuth connection failed: ${
                                e.message
                                    ?: "network error"
                            }",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                override fun onResponse(
                    call: Call,
                    response: Response
                ) {

                    val responseBody =
                        response.body
                            ?.string()
                            .orEmpty()

                    if (
                        !response.isSuccessful
                    ) {

                        runOnUiThread {

                            Toast.makeText(
                                this@MainActivity,
                                "OAuth failed: ${
                                    formatHttpError(
                                        response.code,
                                        responseBody
                                    )
                                }",
                                Toast.LENGTH_LONG
                            ).show()
                        }

                        return
                    }

                    try {

                        val json =
                            JSONObject(
                                responseBody
                            )

                        val accessToken =
                            json.optString(
                                "access_token",
                                ""
                            )

                        if (
                            accessToken.isBlank()
                        ) {

                            runOnUiThread {

                                Toast.makeText(
                                    this@MainActivity,
                                    "Deriv did not return an access token.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }

                            return
                        }

                        /*
                         * Save a separate token for the selected
                         * execution mode.
                         */

                        val tokenKey =
                            if (
                                executionMode.equals(
                                    "REAL",
                                    ignoreCase = true
                                )
                            ) {

                                "real_token"

                            } else {

                                "demo_token"
                            }

                        prefs.edit()
                            .putString(
                                tokenKey,
                                accessToken
                            )
                            .putString(
                                "oauth_mode",
                                executionMode
                            )
                            .remove(
                                "oauth_state"
                            )
                            .remove(
                                "code_verifier"
                            )
                            .apply()

                        runOnUiThread {

                            connectWithToken(
                                accessToken
                            )
                        }

                    } catch (
                        e: Exception
                    ) {

                        runOnUiThread {

                            Toast.makeText(
                                this@MainActivity,
                                "Invalid Deriv OAuth response: ${
                                    e.message
                                }",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        )
    }
        // =============================================================
    // CONNECT USING ACCESS TOKEN
    // =============================================================

    private fun connectWithToken(
        token: String
    ) {

        if (
            token.isBlank()
        ) {

            Toast.makeText(
                this,
                "Deriv access token is empty.",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        try {

            derivApiClient.disconnect()

        } catch (_: Exception) {
        }

        connected = false

        accountId = ""

        accountType = ""

        accountBalance = 0.0

        accountCurrency = ""

        rebuild()

        /*
         * IMPORTANT:
         * Do NOT call derivApiClient.connect(token).
         *
         * That compatibility method defaults to DEMO.
         *
         * The selected DEMO/REAL mode is passed explicitly.
         */

        derivApiClient.connectWithAccessToken(
            token,
            executionMode
        )
    }

    // =============================================================
    // DERIV CONNECTING
    // =============================================================

    override fun onConnecting(
        message: String
    ) {

        runOnUiThread {

            connected = false

            rebuild()
        }
    }

    // =============================================================
    // DERIV CONNECTED
    // =============================================================

    override fun onConnected(
        accountId: String,
        accountType: String
    ) {

        runOnUiThread {

            this.accountId =
                accountId

            this.accountType =
                accountType

            connected = true

            rebuild()

            Toast.makeText(
                this@MainActivity,
                "Deriv ${executionMode} connected.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // =============================================================
    // DERIV BALANCE
    // =============================================================

    override fun onBalance(
        balance: Double,
        currency: String
    ) {

        runOnUiThread {

            accountBalance =
                balance

            accountCurrency =
                currency

            connected = true

            rebuild()
        }
    }

    // =============================================================
    // DERIV DISCONNECTED
    // =============================================================

    override fun onDisconnected() {

        runOnUiThread {

            connected = false

            accountId = ""

            accountType = ""

            accountBalance = 0.0

            accountCurrency = ""

            rebuild()
        }
    }

    // =============================================================
    // DERIV ERROR
    // =============================================================

    override fun onError(
        message: String
    ) {

        runOnUiThread {

            Toast.makeText(
                this@MainActivity,
                message,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // =============================================================
    // MANUAL DISCONNECT
    // =============================================================

    private fun disconnectDeriv() {

        try {

            derivApiClient.disconnect()

        } catch (_: Exception) {
        }

        connected = false

        accountId = ""

        accountType = ""

        accountBalance = 0.0

        accountCurrency = ""

        rebuild()

        Toast.makeText(
            this,
            "Deriv disconnected.",
            Toast.LENGTH_SHORT
        ).show()
    }

    // =============================================================
    // USER ACCOUNTS
    // =============================================================

    private fun showUserAccounts() {

        Toast.makeText(
            this,
            "User account management",
            Toast.LENGTH_SHORT
        ).show()
    }

    // =============================================================
    // ADMIN MENU
    // =============================================================

    private fun showAdminMenu() {

        Toast.makeText(
            this,
            "Admin menu",
            Toast.LENGTH_SHORT
        ).show()
    }

    // =============================================================
    // USER MENU
    // =============================================================

    private fun showUserMenu() {

        Toast.makeText(
            this,
            "User menu",
            Toast.LENGTH_SHORT
        ).show()
    }

    // =============================================================
    // REBUILD SCREEN
    // =============================================================

    private fun rebuild() {

        if (
            !::rootLayout.isInitialized
        ) {
            return
        }

        rootLayout.removeAllViews()

        addHeader()

        /*
         * PLATFORM selector appears only on ADMIN.
         *
         * USER therefore does NOT get the PLATFORM selector.
         */

        if (
            currentPlatform == "ADMIN"
        ) {

            addPlatformSelector()
        }

        addExecutionSelector()

        addAccountCard()

        addIndexMonitor()

        if (
            currentPlatform == "ADMIN"
        ) {

            addAdminFeatures()
        }

        addBottomNavigation()
    }

    // =============================================================
    // SECTION TITLE
    // =============================================================

    private fun addSectionTitle(
        textValue: String
    ) {

        val title =
            TextView(this).apply {

                text =
                    textValue

                textSize =
                    11f

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                setTextColor(
                    CYAN
                )

                setPadding(
                    dp(2),
                    dp(8),
                    dp(2),
                    dp(5)
                )
            }

        rootLayout.addView(
            title
        )
    }

    // =============================================================
    // CARD
    // =============================================================

    private fun createCard():
        LinearLayout {

        return LinearLayout(this).apply {

            orientation =
                LinearLayout.VERTICAL

            setPadding(
                dp(10),
                dp(9),
                dp(10),
                dp(9)
            )

            setBackgroundColor(
                CARD
            )

            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {

                    bottomMargin =
                        dp(8)
                }
        }
    }

    // =============================================================
    // MAIN BUTTON
    // =============================================================

    private fun createMainButton(
        textValue: String,
        selected: Boolean
    ): Button {

        return Button(this).apply {

            text =
                textValue

            textSize =
                11f

            isAllCaps =
                false

            setTextColor(
                if (
                    selected
                ) {

                    BG

                } else {

                    WHITE
                }
            )

            setTypeface(
                null,
                Typeface.BOLD
            )

            setBackgroundColor(
                if (
                    selected
                ) {

                    GREEN

                } else {

                    CARD2
                }
            )

            setPadding(
                dp(3),
                0,
                dp(3),
                0
            )
        }
    }

    // =============================================================
    // ACTION BUTTON
    // =============================================================

    private fun createActionButton(
        textValue: String
    ): Button {

        return Button(this).apply {

            text =
                textValue

            textSize =
                11f

            isAllCaps =
                false

            setTextColor(
                WHITE
            )

            setTypeface(
                null,
                Typeface.BOLD
            )

            setBackgroundColor(
                CARD2
            )

            setPadding(
                dp(3),
                0,
                dp(3),
                0
            )
        }
    }

    // =============================================================
    // NAV BUTTON
    // =============================================================

    private fun createNavButton(
        symbol: String,
        label: String
    ): LinearLayout {

        val box =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                gravity =
                    Gravity.CENTER

                setPadding(
                    dp(2),
                    dp(2),
                    dp(2),
                    dp(2)
                )
            }

        val icon =
            TextView(this).apply {

                text =
                    symbol

                textSize =
                    21f

                gravity =
                    Gravity.CENTER

                setTextColor(
                    CYAN
                )
            }

        box.addView(
            icon
        )

        val text =
            TextView(this).apply {

                text =
                    label

                textSize =
                    7f

                gravity =
                    Gravity.CENTER

                setTextColor(
                    MUTED
                )
            }

        box.addView(
            text
        )

        return box
    }

    // =============================================================
    // PKCE - CODE VERIFIER
    // =============================================================

    private fun generateCodeVerifier():
        String {

        val bytes =
            ByteArray(32)

        SecureRandom().nextBytes(
            bytes
        )

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

        val hash =
            digest.digest(
                verifier.toByteArray(
                    Charsets.US_ASCII
                )
            )

        return Base64.encodeToString(
            hash,
            Base64.URL_SAFE or
                Base64.NO_WRAP or
                Base64.NO_PADDING
        )
    }
        // =============================================================
    // HTTP ERROR FORMATTER
    // =============================================================

    private fun formatHttpError(
        code: Int,
        body: String
    ): String {

        if (
            body.isBlank()
        ) {

            return "HTTP $code"
        }

        return try {

            val json =
                JSONObject(
                    body
                )

            val error =
                json.optJSONObject(
                    "error"
                )

            if (
                error != null
            ) {

                val errorCode =
                    error.optString(
                        "code",
                        ""
                    )

                val message =
                    error.optString(
                        "message",
                        ""
                    )

                when {

                    errorCode.isNotBlank() &&
                        message.isNotBlank() -> {

                        "$errorCode: $message"
                    }

                    message.isNotBlank() -> {

                        message
                    }

                    else -> {

                        "HTTP $code"
                    }
                }

            } else {

                val message =
                    json.optString(
                        "message",
                        ""
                    )

                if (
                    message.isNotBlank()
                ) {

                    message

                } else {

                    "HTTP $code: $body"
                }
            }

        } catch (_: Exception) {

            "HTTP $code: $body"
        }
    }

    // =============================================================
    // CLEAR TEMPORARY OAUTH DATA
    // =============================================================

    private fun clearOAuthData() {

        prefs.edit()
            .remove(
                "oauth_state"
            )
            .remove(
                "code_verifier"
            )
            .remove(
                "oauth_mode"
            )
            .apply()
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
    // ACTIVITY CLEANUP
    // =============================================================

    override fun onDestroy() {

        super.onDestroy()

        /*
         * Do not deliberately disconnect simply because the
         * screen is rebuilt. Only clean up when the Activity
         * itself is actually destroyed.
         */

        try {

            derivApiClient.disconnect()

        } catch (_: Exception) {
        }
    }
}

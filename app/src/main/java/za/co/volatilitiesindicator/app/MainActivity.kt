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

        if (intent != null) {

            setIntent(intent)

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

        if (currentPlatform == "ADMIN") {
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

        // ---------------------------------------------------------
        // MENU
        // ---------------------------------------------------------

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

        // ---------------------------------------------------------
        // LOGO / TITLE AREA
        // ---------------------------------------------------------

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

        // ---------------------------------------------------------
        // APPROVED VOLATILITIES INDICATOR LOGO
        // ---------------------------------------------------------

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

        // ---------------------------------------------------------
        // TITLE
        // ---------------------------------------------------------

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

        // ---------------------------------------------------------
        // TOP RIGHT SYMBOL
        // ---------------------------------------------------------

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
                executionMode != "DEMO" ||
                connected
            ) {

                try {

                    derivApiClient.disconnect()

                } catch (_: Exception) {
                }

                clearOAuthData()
            }

            executionMode =
                "DEMO"

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

            rebuild()
        }

        real.setOnClickListener {

            if (
                executionMode != "REAL" ||
                connected
            ) {

                try {

                    derivApiClient.disconnect()

                } catch (_: Exception) {
                }

                clearOAuthData()
            }

            executionMode =
                "REAL"

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

        addAutoTradeControl()
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
                    if (connected) {

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
                    if (connected) {

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

        // ---------------------------------------------------------
        // ACCOUNT INFORMATION
        // ---------------------------------------------------------

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

        // ---------------------------------------------------------
        // CONNECT / DISCONNECT
        // ---------------------------------------------------------

        val connectButton =
            Button(this).apply {

                text =
                    if (connected) {

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
                    if (connected) {

                        RED

                    } else {

                        GREEN
                    }
                )
            }

        connectButton.setOnClickListener {

            if (connected) {

                disconnectFromDeriv()

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
                    indexColor(index)
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

        // ---------------------------------------------------------
        // CURRENT PRICE
        // ---------------------------------------------------------

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

        // ---------------------------------------------------------
        // PHASE
        // ---------------------------------------------------------

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

                    background =
                        null

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

        // ---------------------------------------------------------
        // SUPPORT / RESISTANCE
        // ---------------------------------------------------------

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

        // ---------------------------------------------------------
        // DIRECTION
        // ---------------------------------------------------------

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

        // ---------------------------------------------------------
        // QUALIFYING
        // ---------------------------------------------------------

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

        // ---------------------------------------------------------
        // TARGET
        // ---------------------------------------------------------

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

        // ---------------------------------------------------------
        // ADMIN AUTO TRADE
        // ---------------------------------------------------------

        val autoTrade =
            Button(this).apply {

                text =
                    if (
                        autoTradeEnabled
                    ) {

                        "AUTO TRADE: ON"

                    } else {

                        "AUTO TRADE: OFF"
                    }

                textSize =
                    11f

                isAllCaps =
                    false

                setTextColor(
                    WHITE
                )

                setBackgroundColor(
                    if (
                        autoTradeEnabled
                    ) {

                        GREEN

                    } else {

                        RED
                    }
                )
            }

        autoTrade.setOnClickListener {

            autoTradeEnabled =
                !autoTradeEnabled

            rebuild()
        }

        card.addView(
            autoTrade,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44)
            )
        )

        // ---------------------------------------------------------
        // ADMIN USER MANAGEMENT
        // ---------------------------------------------------------

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
            ).apply {

                topMargin =
                    dp(7)
            }
        )

        // ---------------------------------------------------------
        // ADMIN REFRESH
        // ---------------------------------------------------------

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

            rebuild()
        }

        val disconnect =
            createNavButton(
                "⏻",
                "DISCONNECT"
            )

        disconnect.setOnClickListener {

            disconnectFromDeriv()
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

        if (
            executionMode == "REAL"
        ) {

            startOAuth(
                "REAL"
            )

            return
        }

        val demoToken =
            prefs.getString(
                "demo_token",
                ""
            ) ?: ""

        if (
            demoToken.isNotEmpty()
        ) {

            connectWithToken(
                demoToken,
                "DEMO"
            )

        } else {

            startOAuth(
                "DEMO"
            )
        }
    }

    // =============================================================
    // START OAUTH
    // =============================================================

    private fun startOAuth(
        mode: String
    ) {

        executionMode =
            mode

        oauthState =
            UUID.randomUUID().toString()

        codeVerifier =
            generateCodeVerifier()

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
                mode
            )
            .apply()

        val challenge =
            generateCodeChallenge(
                codeVerifier
            )

        val clientId =
            if (
                mode == "REAL"
            ) {

                "34fz9hcgpdWwSzNCVeUgMR"

            } else {

                "34fz6VSRiNSQnELXjApVH"
            }

        val redirectUri =
            "https://volatilitiesindicator.org.za/accounts/account-callback.php"

        val authorizationUrl =
            Uri.Builder()
                .scheme("https")
                .authority(
                    "auth.deriv.com"
                )
                .appendPath(
                    "oauth2"
                )
                .appendPath(
                    "auth"
                )
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
                    "trade"
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

            val browser =
                Intent(
                    Intent.ACTION_VIEW,
                    authorizationUrl
                )

            startActivity(
                browser
            )

        } catch (
            e: Exception
        ) {

            Toast.makeText(
                this,
                "Unable to open Deriv login: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // =============================================================
    // OAUTH CALLBACK
    // =============================================================

    private fun handleOAuthCallback(
        intent: Intent
    ) {

        val data =
            intent.data
                ?: return

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

        val returnedState =
            data.getQueryParameter(
                "state"
            )

        val code =
            data.getQueryParameter(
                "code"
            )

        val error =
            data.getQueryParameter(
                "error"
            )

        if (
            !error.isNullOrEmpty()
        ) {

            Toast.makeText(
                this,
                "Deriv OAuth error: $error",
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
            returnedState.isNullOrEmpty() ||
            savedState.isEmpty() ||
            returnedState != savedState
        ) {

            Toast.makeText(
                this,
                "OAuth security check failed.",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        if (
            code.isNullOrEmpty()
        ) {

            Toast.makeText(
                this,
                "No Deriv authorization code received.",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        val mode =
            prefs.getString(
                "oauth_mode",
                executionMode
            ) ?: executionMode

        exchangeOAuthCode(
            code,
            mode
        )
    }

    // =============================================================
    // OAUTH TOKEN EXCHANGE
    // =============================================================

    private fun exchangeOAuthCode(
        code: String,
        mode: String
    ) {

        val verifier =
            prefs.getString(
                "code_verifier",
                ""
            ) ?: ""

        if (
            verifier.isEmpty()
        ) {

            Toast.makeText(
                this,
                "OAuth verifier is missing.",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        val clientId =
            if (
                mode == "REAL"
            ) {

                "34fz9hcgpdWwSzNCVeUgMR"

            } else {

                "34fz6VSRiNSQnELXjApVH"
            }

        val redirectUri =
            "https://volatilitiesindicator.org.za/accounts/account-callback.php"

        val body =
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
                .post(
                    body
                )
                .build()

        Toast.makeText(
            this,
            "Connecting to Deriv $mode...",
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
                            "OAuth connection failed: ${e.message}",
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
                            accessToken.isEmpty()
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

                        prefs.edit()
                            .putString(
                                if (
                                    mode == "REAL"
                                ) {
                                    "real_token"
                                } else {
                                    "demo_token"
                                },
                                accessToken
                            )
                            .putString(
                                "oauth_mode",
                                mode
                            )
                            .apply()

                        runOnUiThread {

                            connectWithToken(
                                accessToken,
                                mode
                            )
                        }

                    } catch (
                        e: Exception
                    ) {

                        runOnUiThread {

                            Toast.makeText(
                                this@MainActivity,
                                "Invalid Deriv response: ${e.message}",
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

        if (token.isBlank()) {

            Toast.makeText(
                this,
                "Deriv access token is empty",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        connected = false

        buildMainScreen()

        /*
         * IMPORTANT:
         * Use the selected executionMode.
         *
         * This fixes the previous problem where
         * connect(token) always forced DEMO.
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

            this.accountId = accountId

            this.accountType = accountType

            connected = true

            rebuild()

            Toast.makeText(
                this@MainActivity,
                "Deriv account connected",
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

            accountBalance = balance

            accountCurrency = currency

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

        derivApiClient.disconnect()

        connected = false

        accountId = ""

        accountType = ""

        accountBalance = 0.0

        accountCurrency = ""

        buildMainScreen()
    }

    // =============================================================
    // DERIV CALLBACK - CONNECTED
    // =============================================================

    override fun onConnected() {

        runOnUiThread {

            connected =
                true

            rebuild()

            Toast.makeText(
                this,
                "Deriv ${executionMode} connected.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // =============================================================
    // DERIV CALLBACK - DISCONNECTED
    // =============================================================

    override fun onDisconnected() {

        runOnUiThread {

            connected =
                false

            rebuild()
        }
    }

    // =============================================================
    // DERIV CALLBACK - ERROR
    // =============================================================

    override fun onError(
        message: String
    ) {

        runOnUiThread {

            Toast.makeText(
                this,
                message,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // =============================================================
    // DERIV CALLBACK - ACCOUNT
    // =============================================================

    override fun onAccountInfo(
        id: String,
        type: String,
        balance: Double,
        currency: String
    ) {

        runOnUiThread {

            accountId =
                id

            accountType =
                type

            accountBalance =
                balance

            accountCurrency =
                currency

            connected =
                true

            rebuild()
        }
    }

    // =============================================================
    // DERIV CALLBACK - BALANCE
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

            rebuild()
        }
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
    // USER ACCOUNTS
    // =============================================================

    private fun showUserAccounts() {

        val card =
            createCard()

        val title =
            TextView(this).apply {

                text =
                    "USER ACCOUNTS"

                textSize =
                    15f

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

        val information =
            TextView(this).apply {

                text =
                    "User account management is available from the admin control panel."

                textSize =
                    11f

                setTextColor(
                    MUTED
                )

                setPadding(
                    0,
                    dp(8),
                    0,
                    dp(8)
                )
            }

        card.addView(
            information
        )

        rootLayout.addView(
            card
        )

        rootScroll.post {

            rootScroll.fullScroll(
                View.FOCUS_DOWN
            )
        }
    }

    // =============================================================
    // REBUILD
    // =============================================================

    private fun rebuild() {

        if (
            !::rootLayout.isInitialized
        ) {
            return
        }

        rootLayout.removeAllViews()

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
                if (selected) {

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
                if (selected) {

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
    // CLEAR OAUTH DATA
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
    // HTTP ERROR
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
                JSONObject(body)

            val error =
                json.optJSONObject(
                    "error"
                )

            if (
                error != null
            ) {

                val message =
                    error.optString(
                        "message",
                        ""
                    )

                val codeText =
                    error.optString(
                        "code",
                        ""
                    )

                if (
                    codeText.isNotEmpty()
                ) {

                    "$codeText: $message"

                } else {

                    message.ifEmpty {
                        "HTTP $code"
                    }
                }

            } else {

                val message =
                    json.optString(
                        "message",
                        ""
                    )

                message.ifEmpty {
                    "HTTP $code: $body"
                }
            }

        } catch (_: Exception) {

            "HTTP $code: $body"
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
    // ACTIVITY CLEANUP
    // =============================================================

    override fun onDestroy() {

        super.onDestroy()

        try {

            derivApiClient.disconnect()

        } catch (_: Exception) {
        }
    }
}

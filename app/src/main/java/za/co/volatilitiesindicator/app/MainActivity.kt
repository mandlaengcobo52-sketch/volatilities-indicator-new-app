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
    // USER = MINOR FEATURES
    // ADMIN = ALL ADMIN FEATURES
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

    private fun indexColor(index: String): Int {

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

        super.onCreate(savedInstanceState)

        window.statusBarColor = BG
        window.navigationBarColor = BG

        handleOAuthCallback(intent)

        buildMainScreen()
    }

    override fun onNewIntent(
        intent: Intent?
    ) {

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

            setBackgroundColor(BG)

            isFillViewport = true

            overScrollMode =
                View.OVER_SCROLL_NEVER
        }

        rootLayout = LinearLayout(this).apply {

            orientation =
                LinearLayout.VERTICAL

            setPadding(
                dp(14),
                dp(8),
                dp(14),
                dp(24)
            )

            setBackgroundColor(BG)
        }

        rootScroll.addView(rootLayout)

        setContentView(rootScroll)

        addHeader()

        addPlatformSelector()

        addExecutionSelector()

        addAccountCard()

        addIndexMonitor()

        /*
         * USER:
         *   Only user/minor dashboard features.
         *
         * ADMIN:
         *   Additional administration features are
         *   displayed below the normal dashboard.
         */
        if (currentPlatform == "ADMIN") {

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

                setTextColor(WHITE)

                gravity = Gravity.CENTER

                layoutParams =
                    LinearLayout.LayoutParams(
                        dp(42),
                        dp(52)
                    )
            }

        menu.setOnClickListener {

            if (currentPlatform == "ADMIN") {

                showAdminMenu()

            } else {

                showUserMenu()
            }
        }

        header.addView(menu)

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

        /*
         * USE THE ACTUAL VOLATILITIES INDICATOR LOGO.
         *
         * This replaces the old text-only "V" logo.
         */
        val logo =
            ImageView(this).apply {

                setImageResource(
                    R.drawable.volatilities_indicator_logo
                )

                scaleType =
                    ImageView.ScaleType.CENTER_INSIDE

                contentDescription =
                    "Volatilities Indicator logo"

                layoutParams =
                    LinearLayout.LayoutParams(
                        dp(48),
                        dp(48)
                    )
            }

        titleBox.addView(logo)

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

                text = "VOLATILITIES"

                textSize = 20f

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                setTextColor(WHITE)
            }

        titleColumn.addView(title)

        val indicator =
            TextView(this).apply {

                text = "INDICATOR"

                textSize = 18f

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                setTextColor(GREEN)
            }

        titleColumn.addView(indicator)

        val subtitle =
            TextView(this).apply {

                text =
                    "TRADE SMARTER TODAY"

                textSize = 8f

                setTextColor(MUTED)
            }

        titleColumn.addView(subtitle)

        titleBox.addView(titleColumn)

        header.addView(titleBox)

        // ---------------------------------------------------------
        // TOP-RIGHT FLOWER / NOTIFICATION SYMBOL
        // ---------------------------------------------------------

        val bell =
            TextView(this).apply {

                text = "♧"

                textSize = 28f

                setTextColor(WHITE)

                gravity = Gravity.CENTER

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

        header.addView(bell)

        rootLayout.addView(header)
    }

    // =============================================================
    // PLATFORM SELECTOR
    // =============================================================

    private fun addPlatformSelector() {

        addSectionTitle("PLATFORM")

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

            currentPlatform = "USER"

            rebuild()
        }

        admin.setOnClickListener {

            currentPlatform = "ADMIN"

            rebuild()
        }

        row.addView(
            user,
            LinearLayout.LayoutParams(
                0,
                dp(54),
                1f
            ).apply {
                rightMargin = dp(5)
            }
        )

        row.addView(
            admin,
            LinearLayout.LayoutParams(
                0,
                dp(54),
                1f
            ).apply {
                leftMargin = dp(5)
            }
        )

        rootLayout.addView(row)
    }

    // =============================================================
    // EXECUTION MODE
    // =============================================================

    private fun addExecutionSelector() {

        addSectionTitle("EXECUTION MODE")

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

            executionMode = "DEMO"

            connected = false

            accountId = ""

            accountBalance = 0.0

            accountCurrency = ""

            rebuild()
        }

        real.setOnClickListener {

            executionMode = "REAL"

            connected = false

            accountId = ""

            accountBalance = 0.0

            accountCurrency = ""

            rebuild()
        }

        row.addView(
            demo,
            LinearLayout.LayoutParams(
                0,
                dp(54),
                1f
            ).apply {
                rightMargin = dp(5)
            }
        )

        row.addView(
            real,
            LinearLayout.LayoutParams(
                0,
                dp(54),
                1f
            ).apply {
                leftMargin = dp(5)
            }
        )

        rootLayout.addView(row)
    }
        // =============================================================
    // DERIV EXECUTION ACCOUNT CARD
    // =============================================================

    private fun addAccountCard() {

        addSectionTitle("DERIV EXECUTION ACCOUNT")

        val card =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    dp(14),
                    dp(14),
                    dp(14),
                    dp(14)
                )

                setBackgroundColor(CARD)
            }

        // ---------------------------------------------------------
        // ACCOUNT STATUS
        // ---------------------------------------------------------

        val status =
            TextView(this).apply {

                text =
                    if (connected)
                        "🟢  CONNECTED"
                    else
                        "🔴  NOT CONNECTED"

                textSize = 18f

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                setTextColor(
                    if (connected)
                        GREEN
                    else
                        RED
                )

                setPadding(
                    0,
                    0,
                    0,
                    dp(10)
                )
            }

        card.addView(status)

        // ---------------------------------------------------------
        // ACCOUNT NUMBER
        // ---------------------------------------------------------

        val account =
            TextView(this).apply {

                text =
                    if (accountId.isNotEmpty())
                        "Account: $accountId"
                    else
                        "Account: —"

                textSize = 16f

                setTextColor(WHITE)

                setPadding(
                    0,
                    dp(2),
                    0,
                    dp(8)
                )
            }

        card.addView(account)

        // ---------------------------------------------------------
        // BALANCE
        // ---------------------------------------------------------

        val balance =
            TextView(this).apply {

                text =
                    if (connected)
                        "Balance: ${
                            String.format(
                                "%.2f",
                                accountBalance
                            )
                        } $accountCurrency"
                    else
                        "Balance: —"

                textSize = 16f

                setTextColor(WHITE)

                setPadding(
                    0,
                    dp(2),
                    0,
                    dp(8)
                )
            }

        card.addView(balance)

        // ---------------------------------------------------------
        // OPEN P/L
        // ---------------------------------------------------------

        val pnl =
            TextView(this).apply {

                text = "Open P/L: —"

                textSize = 16f

                setTextColor(WHITE)

                setPadding(
                    0,
                    dp(2),
                    0,
                    dp(12)
                )
            }

        card.addView(pnl)

        // ---------------------------------------------------------
        // CONNECT / REFRESH
        // ---------------------------------------------------------

        val buttonRow =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL
            }

        val connect =
            createActionButton(
                "🔗  CONNECT"
            )

        val refresh =
            createActionButton(
                "⟳  REFRESH"
            )

        connect.setOnClickListener {

            startDerivOAuth()
        }

        refresh.setOnClickListener {

            if (connected) {

                derivApiClient.requestBalance()

            } else {

                Toast.makeText(
                    this,
                    "Deriv account is not connected",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        buttonRow.addView(
            connect,
            LinearLayout.LayoutParams(
                0,
                dp(54),
                1f
            ).apply {
                rightMargin = dp(5)
            }
        )

        buttonRow.addView(
            refresh,
            LinearLayout.LayoutParams(
                0,
                dp(54),
                1f
            ).apply {
                leftMargin = dp(5)
            }
        )

        card.addView(buttonRow)

        rootLayout.addView(
            card,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {

                bottomMargin = dp(12)
            }
        )
    }

    // =============================================================
    // INDEX MONITOR
    // =============================================================

    private fun addIndexMonitor() {

        addSectionTitle("INDEX MONITOR")

        for (index in indexes) {

            addIndexRow(index)
        }
    }

    // =============================================================
    // INDEX ROW
    // =============================================================

    private fun addIndexRow(
        index: String
    ) {

        val row =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL

                setPadding(
                    dp(12),
                    dp(8),
                    dp(8),
                    dp(8)
                )

                setBackgroundColor(CARD2)

                isClickable = true
            }

        // ---------------------------------------------------------
        // INDEX NAME
        // ---------------------------------------------------------

        val name =
            TextView(this).apply {

                text = index

                textSize = 20f

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                setTextColor(
                    indexColor(index)
                )

                gravity =
                    Gravity.CENTER_VERTICAL

                layoutParams =
                    LinearLayout.LayoutParams(
                        dp(105),
                        dp(58)
                    )
            }

        row.addView(name)

        // ---------------------------------------------------------
        // MARKET GRAPHIC
        // ---------------------------------------------------------

        val graph =
            TextView(this).apply {

                text = "⌁⌃⌁⌄⌁⌃⌁"

                textSize = 25f

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                setTextColor(
                    indexColor(index)
                )

                gravity =
                    Gravity.CENTER

                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        dp(58),
                        1f
                    )
            }

        row.addView(graph)

        // ---------------------------------------------------------
        // STATUS
        // ---------------------------------------------------------

        val status =
            TextView(this).apply {

                text = "WAITING"

                textSize = 13f

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                setTextColor(YELLOW)

                gravity =
                    Gravity.CENTER

                layoutParams =
                    LinearLayout.LayoutParams(
                        dp(105),
                        dp(58)
                    )
            }

        row.addView(status)

        // ---------------------------------------------------------
        // ARROW
        // ---------------------------------------------------------

        val arrow =
            TextView(this).apply {

                text = "›"

                textSize = 34f

                setTextColor(WHITE)

                gravity =
                    Gravity.CENTER

                layoutParams =
                    LinearLayout.LayoutParams(
                        dp(32),
                        dp(58)
                    )
            }

        row.addView(arrow)

        row.setOnClickListener {

            openIndex(index)
        }

        rootLayout.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {

                bottomMargin = dp(6)
            }
        )
    }

    // =============================================================
    // INDEX DETAIL
    // =============================================================

    private fun openIndex(
        index: String
    ) {

        Toast.makeText(
            this,
            "$index selected",
            Toast.LENGTH_SHORT
        ).show()
    }

    // =============================================================
    // ADMIN FEATURES
    // =============================================================

    private fun addAdminFeatures() {

        addSectionTitle("ADMIN CONTROL")

        val card =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    dp(14),
                    dp(14),
                    dp(14),
                    dp(14)
                )

                setBackgroundColor(CARD)
            }

        val title =
            TextView(this).apply {

                text =
                    "ADMINISTRATION"

                textSize = 18f

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                setTextColor(GREEN)

                setPadding(
                    0,
                    0,
                    0,
                    dp(12)
                )
            }

        card.addView(title)

        // ---------------------------------------------------------
        // USER ACCOUNTS
        // ---------------------------------------------------------

        val users =
            createActionButton(
                "USER ACCOUNTS"
            )

        users.setOnClickListener {

            Toast.makeText(
                this,
                "User Accounts",
                Toast.LENGTH_SHORT
            ).show()
        }

        card.addView(
            users,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            ).apply {

                bottomMargin = dp(8)
            }
        )

        // ---------------------------------------------------------
        // TRADE CONTROL
        // ---------------------------------------------------------

        val trade =
            createActionButton(
                "TRADE CONTROL"
            )

        trade.setOnClickListener {

            Toast.makeText(
                this,
                "Trade Control",
                Toast.LENGTH_SHORT
            ).show()
        }

        card.addView(
            trade,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            ).apply {

                bottomMargin = dp(8)
            }
        )

        // ---------------------------------------------------------
        // AUTO TRADE
        // ---------------------------------------------------------

        val auto =
            createActionButton(
                if (autoTradeEnabled)
                    "AUTO TRADE: ON"
                else
                    "AUTO TRADE: OFF"
            )

        auto.setOnClickListener {

            autoTradeEnabled =
                !autoTradeEnabled

            auto.text =
                if (autoTradeEnabled)
                    "AUTO TRADE: ON"
                else
                    "AUTO TRADE: OFF"
        }

        card.addView(
            auto,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            )
        )

        rootLayout.addView(
            card,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {

                bottomMargin = dp(12)
            }
        )
    }

    // =============================================================
    // BOTTOM NAVIGATION
    // =============================================================

    private fun addBottomNavigation() {

        val navigation =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER

                setPadding(
                    0,
                    dp(18),
                    0,
                    dp(4)
                )
            }

        addNavigationItem(
            navigation,
            "⌂",
            "Home",
            true
        )

        addNavigationItem(
            navigation,
            "▥",
            "Trade",
            false
        )

        addNavigationItem(
            navigation,
            "ϟ",
            "Analysis",
            false
        )

        addNavigationItem(
            navigation,
            "▤",
            "News",
            false
        )

        addNavigationItem(
            navigation,
            "⚙",
            "Settings",
            false
        )

        rootLayout.addView(navigation)
    }

    // =============================================================
    // NAVIGATION ITEM
    // =============================================================

    private fun addNavigationItem(
        parent: LinearLayout,
        icon: String,
        label: String,
        selected: Boolean
    ) {

        val item =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                gravity =
                    Gravity.CENTER

                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        dp(70),
                        1f
                    )
            }

        val iconView =
            TextView(this).apply {

                text = icon

                textSize = 25f

                setTextColor(
                    if (selected)
                        GREEN
                    else
                        MUTED
                )

                gravity =
                    Gravity.CENTER
            }

        item.addView(iconView)

        val labelView =
            TextView(this).apply {

                text = label

                textSize = 11f

                setTextColor(
                    if (selected)
                        GREEN
                    else
                        MUTED
                )

                gravity =
                    Gravity.CENTER

                setPadding(
                    0,
                    dp(4),
                    0,
                    0
                )
            }

        item.addView(labelView)

        item.setOnClickListener {

            when (label) {

                "Home" -> {

                    scrollToTop()
                }

                "Trade" -> {

                    Toast.makeText(
                        this,
                        "Trade",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                "Analysis" -> {

                    Toast.makeText(
                        this,
                        "Analysis",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                "News" -> {

                    Toast.makeText(
                        this,
                        "News",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                "Settings" -> {

                    Toast.makeText(
                        this,
                        "Settings",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        parent.addView(item)
    }

    // =============================================================
    // SECTION TITLE
    // =============================================================

    private fun addSectionTitle(
        text: String
    ) {

        val title =
            TextView(this).apply {

                this.text = text

                textSize = 18f

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                setTextColor(WHITE)

                setPadding(
                    0,
                    dp(8),
                    0,
                    dp(10)
                )
            }

        rootLayout.addView(title)
    }

    // =============================================================
    // MAIN BUTTON
    // =============================================================

    private fun createMainButton(
        text: String,
        selected: Boolean
    ): Button {

        return Button(this).apply {

            this.text = text

            textSize = 14f

            setTypeface(
                null,
                Typeface.BOLD
            )

            setTextColor(
                if (selected)
                    Color.BLACK
                else
                    WHITE
            )

            setBackgroundColor(
                if (selected)
                    GREEN
                else
                    CARD2
            )

            isAllCaps = false

            stateListAnimator = null

            minHeight = 0

            minimumHeight = 0

            setPadding(
                dp(6),
                0,
                dp(6),
                0
            )
        }
    }

    // =============================================================
    // ACTION BUTTON
    // =============================================================

    private fun createActionButton(
        text: String
    ): Button {

        return Button(this).apply {

            this.text = text

            textSize = 14f

            setTypeface(
                null,
                Typeface.BOLD
            )

            setTextColor(WHITE)

            setBackgroundColor(CARD2)

            isAllCaps = false

            stateListAnimator = null

            minHeight = 0

            minimumHeight = 0
        }
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
    // REBUILD SCREEN
    // =============================================================

    private fun rebuild() {

        buildMainScreen()
    }

    // =============================================================
    // SCROLL TO TOP
    // =============================================================

    private fun scrollToTop() {

        rootScroll.post {

            rootScroll.smoothScrollTo(
                0,
                0
            )
        }
    }

    // =============================================================
    // DERIV OAUTH
    // =============================================================

    private fun startDerivOAuth() {

        /*
         * IMPORTANT:
         *
         * The previous project used:
         *
         *     /oauth2/authorize
         *
         * That route caused the 404 shown in the browser.
         *
         * Deriv OAuth 2.0 uses:
         *
         *     /oauth2/auth
         */

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
            .apply()

        val challenge =
            generateCodeChallenge(
                codeVerifier
            )

        val redirectUri =
            "https://volatilitiesindicator.org.za/accounts/account-callback.php"

        val authorizationUrl =
            Uri.parse(
                "https://auth.deriv.com/oauth2/auth"
            )
                .buildUpon()
                .appendQueryParameter(
                    "response_type",
                    "code"
                )
                .appendQueryParameter(
                    "client_id",
                    DERIV_CLIENT_ID
                )
                .appendQueryParameter(
                    "redirect_uri",
                    redirectUri
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

            val browserIntent =
                Intent(
                    Intent.ACTION_VIEW,
                    authorizationUrl
                )

            startActivity(
                browserIntent
            )

        } catch (e: Exception) {

            Toast.makeText(
                this,
                "Unable to open Deriv login",
                Toast.LENGTH_LONG
            ).show()
        }
    }
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

                    val responseText =
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
                                    "Deriv did not return an access token",
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
    // CONNECT TO DERIV
    // =============================================================

    private fun connectWithToken(
        token: String
    ) {

        if (
            token.isEmpty()
        ) {

            Toast.makeText(
                this,
                "No Deriv token",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        connected = false

        rebuild()

        derivApiClient.connect(
            token,
            executionMode == "REAL"
        )
    }

    // =============================================================
    // DERIV CALLBACK
    // =============================================================

    override fun onConnected() {

        runOnUiThread {

            connected = true

            rebuild()
        }
    }

    override fun onAccountReceived(
        id: String,
        type: String,
        balance: Double,
        currency: String
    ) {

        runOnUiThread {

            accountId = id

            accountType = type

            accountBalance = balance

            accountCurrency = currency

            connected = true

            rebuild()
        }
    }

    override fun onBalanceReceived(
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

    override fun onDisconnected() {

        runOnUiThread {

            connected = false

            rebuild()
        }
    }

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
    // DERIV CLIENT ID
    // =============================================================
    //
    // The client ID is selected according to DEMO / REAL mode.
    // These IDs already exist in the supplied project.
    //
    // =============================================================

    private val DERIV_CLIENT_ID: String
        get() = DerivApiClient.clientId(
            executionMode
        )

    // =============================================================
    // PKCE CODE VERIFIER
    // =============================================================

    private fun generateCodeVerifier(): String {

        val random =
            ByteArray(32)

        SecureRandom().nextBytes(
            random
        )

        return Base64.encodeToString(
            random,
            Base64.URL_SAFE or
                    Base64.NO_WRAP or
                    Base64.NO_PADDING
        )
    }

    // =============================================================
    // PKCE CODE CHALLENGE
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
    // DP TO PIXELS
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
    // SCREEN REBUILD
    // =============================================================

    private fun rebuildScreen() {

        buildMainScreen()
    }

    // =============================================================
    // COMPATIBILITY REBUILD
    // =============================================================

    private fun rebuild() {

        rebuildScreen()
    }

    // =============================================================
    // SPACER
    // =============================================================

    private fun spacer(
        height: Int = 8
    ): View {

        return View(this).apply {

            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(height)
                )
        }
    }

    // =============================================================
    // HORIZONTAL ROW
    // =============================================================

    private fun row(): LinearLayout {

        return LinearLayout(this).apply {

            orientation =
                LinearLayout.HORIZONTAL

            gravity =
                Gravity.CENTER_VERTICAL

            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
        }
    }

    // =============================================================
    // MESSAGE
    // =============================================================

    private fun showMessage(
        message: String
    ) {

        Toast.makeText(
            this,
            message,
            Toast.LENGTH_SHORT
        ).show()
    }

    // =============================================================
    // BUTTON ENABLE / DISABLE
    // =============================================================

    private fun setButtonEnabled(
        button: Button,
        enabled: Boolean
    ) {

        button.isEnabled =
            enabled

        button.alpha =
            if (enabled) {
                1.0f
            } else {
                0.5f
            }
    }

    // =============================================================
    // ACTIVITY RESULT SAFETY
    // =============================================================

    override fun onResume() {

        super.onResume()

        /*
         * Do not automatically disconnect here.
         *
         * The Deriv WebSocket is maintained by DerivApiClient.
         */
    }

    // =============================================================
    // BACK BUTTON
    // =============================================================

    override fun onBackPressed() {

        if (
            rootScroll.scrollY > 0
        ) {

            rootScroll.smoothScrollTo(
                0,
                0
            )

            return
        }

        super.onBackPressed()
    }

    // =============================================================
    // ACTIVITY DESTROY
    // =============================================================

    override fun onDestroy() {

        /*
         * The connection is closed only when the Activity
         * itself is destroyed.
         *
         * It is NOT disconnected by an arbitrary one-minute
         * timeout.
         */

        try {

            derivApiClient.disconnect()

        } catch (
            _: Exception
        ) {
        }

        super.onDestroy()
    }

    // =============================================================
    // FINAL SAFETY
    // =============================================================

    private fun clearOAuthState() {

        prefs.edit()
            .remove("oauth_state")
            .remove("code_verifier")
            .apply()
    }
}

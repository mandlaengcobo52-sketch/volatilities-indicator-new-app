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
         * The actual project launcher/logo resource can be
         * inserted here when its resource name is available.
         *
         * Do not use android.R.drawable.ic_dialog_info.
         */

        val logo =
            TextView(this).apply {

                text = "V"

                textSize = 30f

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                setTextColor(GREEN)

                gravity = Gravity.CENTER

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
        // BELL
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
    // DERIV ACCOUNT CARD
    // =============================================================

    private fun addAccountCard() {

        val card =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    dp(14),
                    dp(12),
                    dp(14),
                    dp(12)
                )

                setBackgroundColor(CARD)

                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {

                        topMargin = dp(14)

                        bottomMargin = dp(14)
                    }
            }

        val heading =
            TextView(this).apply {

                text =
                    "⚡  DERIV EXECUTION ACCOUNT"

                textSize = 15f

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                setTextColor(YELLOW)
            }

        card.addView(heading)

        val status =
            TextView(this).apply {

                text =
                    if (connected)
                        "🟢  CONNECTION READY"
                    else
                        "🔴  NOT CONNECTED"

                textSize = 14f

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
                    dp(8),
                    0,
                    dp(4)
                )
            }

        card.addView(status)

        val account =
            TextView(this).apply {

                text =
                    if (accountId.isEmpty())
                        "Account: —"
                    else
                        "Account: $accountId"

                textSize = 14f

                setTextColor(WHITE)
            }

        card.addView(account)

        val balance =
            TextView(this).apply {

                text =
                    if (accountCurrency.isEmpty()) {

                        "Balance: —"

                    } else {

                        "Balance: %.2f %s".format(
                            accountBalance,
                            accountCurrency
                        )
                    }

                textSize = 14f

                setTextColor(WHITE)

                setPadding(
                    0,
                    dp(5),
                    0,
                    dp(5)
                )
            }

        card.addView(balance)

        val openPnl =
            TextView(this).apply {

                text = "Open P/L: —"

                textSize = 14f

                setTextColor(WHITE)
            }

        card.addView(openPnl)

        // ---------------------------------------------------------
        // CONNECT / REFRESH
        // ---------------------------------------------------------

        val buttons =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                setPadding(
                    0,
                    dp(12),
                    0,
                    0
                )
            }

        val connect =
            createMainButton(
                if (connected)
                    "✓  CONNECTED"
                else
                    "🔗  CONNECT",
                connected
            )

        connect.setOnClickListener {

            if (!connected) {

                startDerivOAuth()

            } else {

                Toast.makeText(
                    this,
                    "Deriv account already connected",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        val refresh =
            createSecondaryButton(
                "⟳  REFRESH"
            )

        refresh.setOnClickListener {

            refreshDerivConnection()
        }

        buttons.addView(
            connect,
            LinearLayout.LayoutParams(
                0,
                dp(50),
                1f
            ).apply {
                rightMargin = dp(5)
            }
        )

        buttons.addView(
            refresh,
            LinearLayout.LayoutParams(
                0,
                dp(50),
                1f
            ).apply {
                leftMargin = dp(5)
            }
        )

        card.addView(buttons)

        rootLayout.addView(card)
    }
        // =============================================================
    // AUTO TRADE
    // =============================================================

    private fun addAutoTrade() {

        val card =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL

                setPadding(
                    dp(12),
                    dp(7),
                    dp(12),
                    dp(7)
                )

                setBackgroundColor(CARD)

                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(48)
                    ).apply {
                        bottomMargin = dp(8)
                    }
            }

        val label =
            TextView(this).apply {

                text = "AUTO TRADE"

                textSize = 13f

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                setTextColor(WHITE)

                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
            }

        card.addView(label)

        val state =
            TextView(this).apply {

                text =
                    if (autoTradeEnabled)
                        "ON"
                    else
                        "OFF"

                textSize = 13f

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                setTextColor(
                    if (autoTradeEnabled)
                        GREEN
                    else
                        YELLOW
                )

                gravity =
                    Gravity.CENTER

                setPadding(
                    dp(12),
                    dp(4),
                    dp(12),
                    dp(4)
                )

                setOnClickListener {

                    autoTradeEnabled =
                        !autoTradeEnabled

                    prefs.edit()
                        .putBoolean(
                            "auto_trade_enabled",
                            autoTradeEnabled
                        )
                        .apply()

                    rebuild()
                }
            }

        card.addView(
            state,
            LinearLayout.LayoutParams(
                dp(70),
                dp(36)
            )
        )

        rootLayout.addView(card)
    }

    // =============================================================
    // INDEX MONITOR
    // =============================================================

    private fun addIndexMonitor() {

        addSectionTitle("INDEX MONITOR")

        val monitor =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL
            }

        for (index in indexes) {

            monitor.addView(
                createIndexRow(index)
            )
        }

        rootLayout.addView(monitor)
    }

    // =============================================================
    // INDEX ROW
    // =============================================================

    private fun createIndexRow(
        index: String
    ): LinearLayout {

        val colour =
            indexColor(index)

        val row =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL

                setPadding(
                    dp(10),
                    dp(5),
                    dp(5),
                    dp(5)
                )

                setBackgroundColor(CARD)

                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(52)
                    ).apply {

                        bottomMargin =
                            dp(5)
                    }
            }

        // ---------------------------------------------------------
        // INDEX NAME
        // ---------------------------------------------------------

        val name =
            TextView(this).apply {

                text = index

                textSize = 16f

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                setTextColor(colour)

                gravity =
                    Gravity.CENTER_VERTICAL

                layoutParams =
                    LinearLayout.LayoutParams(
                        dp(72),
                        LinearLayout.LayoutParams.MATCH_PARENT
                    )
            }

        row.addView(name)

        // ---------------------------------------------------------
        // SIMPLE MARKET LINE
        // ---------------------------------------------------------

        val graph =
            TextView(this).apply {

                text =
                    "╱╲╱╲╲╱"

                textSize = 14f

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                setTextColor(colour)

                gravity =
                    Gravity.CENTER

                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.MATCH_PARENT,
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

                textSize = 10f

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                setTextColor(YELLOW)

                gravity =
                    Gravity.CENTER

                layoutParams =
                    LinearLayout.LayoutParams(
                        dp(66),
                        LinearLayout.LayoutParams.MATCH_PARENT
                    )
            }

        row.addView(status)

        // ---------------------------------------------------------
        // ARROW
        // ---------------------------------------------------------

        val arrow =
            TextView(this).apply {

                text = "›"

                textSize = 27f

                setTextColor(WHITE)

                gravity =
                    Gravity.CENTER

                layoutParams =
                    LinearLayout.LayoutParams(
                        dp(30),
                        LinearLayout.LayoutParams.MATCH_PARENT
                    )
            }

        arrow.setOnClickListener {

            openIndex(index)
        }

        row.addView(arrow)

        return row
    }

    // =============================================================
    // INDEX DETAILS
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

        addSectionTitle(
            "ADMIN FEATURES"
        )

        val container =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL
            }

        addAdminFeature(
            container,
            "⌂   DASHBOARD"
        )

        addAdminFeature(
            container,
            "▥   INDEX SETTINGS"
        )

        addAdminFeature(
            container,
            "◆   RISK CATEGORIES"
        )

        addAdminFeature(
            container,
            "●●   USER ACCOUNTS"
        )

        addAdminFeature(
            container,
            "♧   NOTIFICATIONS"
        )

        addAdminFeature(
            container,
            "▤   TRADING LOGS"
        )

        addAdminFeature(
            container,
            "⚙   SYSTEM SETTINGS"
        )

        addAdminFeature(
            container,
            "⇥   LOGOUT"
        )

        rootLayout.addView(container)
    }

    // =============================================================
    // ADMIN FEATURE BUTTON
    // =============================================================

    private fun addAdminFeature(
        parent: LinearLayout,
        label: String
    ) {

        val button =
            createSecondaryButton(label)

        button.setOnClickListener {

            handleAdminFeature(label)
        }

        parent.addView(
            button,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44)
            ).apply {

                bottomMargin =
                    dp(5)
            }
        )
    }

    // =============================================================
    // ADMIN FEATURE ACTIONS
    // =============================================================

    private fun handleAdminFeature(
        label: String
    ) {

        when {

            label.contains(
                "USER ACCOUNTS"
            ) -> {

                showAdminMessage(
                    "User Accounts"
                )
            }

            label.contains(
                "INDEX SETTINGS"
            ) -> {

                showAdminMessage(
                    "Index Settings"
                )
            }

            label.contains(
                "RISK CATEGORIES"
            ) -> {

                showAdminMessage(
                    "Risk Categories"
                )
            }

            label.contains(
                "NOTIFICATIONS"
            ) -> {

                showAdminMessage(
                    "Notifications"
                )
            }

            label.contains(
                "TRADING LOGS"
            ) -> {

                showAdminMessage(
                    "Trading Logs"
                )
            }

            label.contains(
                "SYSTEM SETTINGS"
            ) -> {

                showAdminMessage(
                    "System Settings"
                )
            }

            label.contains(
                "LOGOUT"
            ) -> {

                logout()
            }

            else -> {

                showAdminMessage(
                    "Admin Dashboard"
                )
            }
        }
    }

    // =============================================================
    // ADMIN MESSAGE
    // =============================================================

    private fun showAdminMessage(
        message: String
    ) {

        Toast.makeText(
            this,
            message,
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
    // ADMIN MENU
    // =============================================================

    private fun showAdminMenu() {

        val menu =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    dp(16),
                    dp(16),
                    dp(16),
                    dp(16)
                )

                setBackgroundColor(CARD)
            }

        val dialog =
            android.app.Dialog(this)

        val title =
            TextView(this).apply {

                text = "ADMIN MENU"

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

        menu.addView(title)

        val items =
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

        for (item in items) {

            val button =
                createSecondaryButton(item)

            button.setOnClickListener {

                dialog.dismiss()

                handleAdminFeature(item)
            }

            menu.addView(
                button,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(42)
                ).apply {

                    bottomMargin =
                        dp(5)
                }
            )
        }

        dialog.setContentView(menu)

        dialog.window?.setBackgroundDrawableResource(
            android.R.color.transparent
        )

        dialog.show()

        dialog.window?.setLayout(
            dp(300),
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    // =============================================================
    // BOTTOM NAVIGATION
    // =============================================================

    private fun addBottomNavigation() {

        val nav =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER

                setPadding(
                    0,
                    dp(12),
                    0,
                    0
                )
            }

        addNavigationItem(
            nav,
            "⌂",
            "Home",
            true
        )

        addNavigationItem(
            nav,
            "▥",
            "Trade",
            false
        )

        addNavigationItem(
            nav,
            "⌁",
            "Analysis",
            false
        )

        addNavigationItem(
            nav,
            "▤",
            "News",
            false
        )

        addNavigationItem(
            nav,
            "⚙",
            "Settings",
            false
        )

        rootLayout.addView(nav)
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
                        dp(58),
                        1f
                    )
            }

        val iconView =
            TextView(this).apply {

                text = icon

                textSize = 21f

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

                textSize = 9f

                setTextColor(
                    if (selected)
                        GREEN
                    else
                        MUTED
                )

                gravity =
                    Gravity.CENTER
            }

        item.addView(labelView)

        item.setOnClickListener {

            Toast.makeText(
                this@MainActivity,
                label,
                Toast.LENGTH_SHORT
            ).show()
        }

        parent.addView(item)
    }

    // =============================================================
    // SECTION TITLE
    // =============================================================

    private fun addSectionTitle(
        title: String
    ) {

        val view =
            TextView(this).apply {

                text = title

                textSize = 14f

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                setTextColor(WHITE)

                setPadding(
                    0,
                    dp(8),
                    0,
                    dp(8)
                )
            }

        rootLayout.addView(view)
    }

    // =============================================================
    // MAIN BUTTON
    // =============================================================

    private fun createMainButton(
        textValue: String,
        selected: Boolean
    ): Button {

        return Button(this).apply {

            text = textValue

            textSize = 12f

            isAllCaps = false

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

            minHeight = 0

            minimumHeight = 0

            stateListAnimator = null

            setPadding(
                dp(4),
                0,
                dp(4),
                0
            )
        }
    }

    // =============================================================
    // SECONDARY BUTTON
    // =============================================================

    private fun createSecondaryButton(
        textValue: String
    ): Button {

        return Button(this).apply {

            text = textValue

            textSize = 11f

            isAllCaps = false

            setTypeface(
                null,
                Typeface.BOLD
            )

            setTextColor(WHITE)

            setBackgroundColor(CARD2)

            minHeight = 0

            minimumHeight = 0

            stateListAnimator = null

            setPadding(
                dp(5),
                0,
                dp(5),
                0
            )
        }
    }
        // =============================================================
    // START DERIV OAUTH
    // =============================================================

    private fun startDerivOAuth() {

        try {

            val state =
                UUID.randomUUID().toString()

            val verifierBytes =
                ByteArray(32)

            SecureRandom()
                .nextBytes(verifierBytes)

            val verifier =
                Base64.encodeToString(
                    verifierBytes,
                    Base64.URL_SAFE or
                        Base64.NO_WRAP or
                        Base64.NO_PADDING
                )

            val challenge =
                createCodeChallenge(
                    verifier
                )

            oauthState =
                state

            codeVerifier =
                verifier

            prefs.edit()
                .putString(
                    "oauth_state",
                    state
                )
                .putString(
                    "code_verifier",
                    verifier
                )
                .putString(
                    "oauth_mode",
                    executionMode
                )
                .apply()

            val clientId =
                DerivApiClient.clientId(
                    executionMode
                )

            val authorizationUrl =
                Uri.Builder()
                    .scheme("https")
                    .authority(
                        "auth.deriv.com"
                    )
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
                        DerivApiClient
                            .OAUTH_REDIRECT_URI
                    )
                    .appendQueryParameter(
                        "response_type",
                        "code"
                    )
                    .appendQueryParameter(
                        "scope",
                        DerivApiClient
                            .OAUTH_SCOPE
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

            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    authorizationUrl
                )
            )

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
    // OAUTH CALLBACK
    // =============================================================

    private fun handleOAuthCallback(
        callbackIntent: Intent?
    ) {

        if (callbackIntent == null) {
            return
        }

        val uri =
            callbackIntent.data
                ?: return

        if (
            uri.scheme !=
                "volatilitiesindicator" ||
            uri.host !=
                "oauth-callback"
        ) {
            return
        }

        // ---------------------------------------------------------
        // OAUTH ERROR
        // ---------------------------------------------------------

        val error =
            uri.getQueryParameter(
                "error"
            )

        if (!error.isNullOrEmpty()) {

            Toast.makeText(
                this,
                "OAuth error: $error",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        // ---------------------------------------------------------
        // STATE
        // ---------------------------------------------------------

        val returnedState =
            uri.getQueryParameter(
                "state"
            )

        val savedState =
            prefs.getString(
                "oauth_state",
                ""
            )
                ?: ""

        if (
            returnedState.isNullOrEmpty() ||
            returnedState != savedState
        ) {

            Toast.makeText(
                this,
                "OAuth security check failed.",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        // ---------------------------------------------------------
        // CODE
        // ---------------------------------------------------------

        val code =
            uri.getQueryParameter(
                "code"
            )

        val verifier =
            prefs.getString(
                "code_verifier",
                ""
            )
                ?: ""

        if (
            code.isNullOrEmpty() ||
            verifier.isEmpty()
        ) {

            Toast.makeText(
                this,
                "OAuth authorization code missing.",
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

        val selectedMode =
            prefs.getString(
                "oauth_mode",
                executionMode
            )
                ?: executionMode

        val clientId =
            DerivApiClient.clientId(
                selectedMode
            )

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
                    DerivApiClient
                        .OAUTH_REDIRECT_URI
                )
                .add(
                    "code_verifier",
                    verifier
                )
                .build()

        val request =
            Request.Builder()
                .url(
                    DerivApiClient
                        .OAUTH_TOKEN_URL
                )
                .post(
                    requestBody
                )
                .build()

        Toast.makeText(
            this,
            "Connecting to Deriv...",
            Toast.LENGTH_SHORT
        ).show()

        httpClient
            .newCall(request)
            .enqueue(
                object : Callback {

                    override fun onFailure(
                        call: Call,
                        e: IOException
                    ) {

                        runOnUiThread {

                            connected =
                                false

                            Toast.makeText(
                                this@MainActivity,
                                "Connection failed: ${e.message}",
                                Toast.LENGTH_LONG
                            ).show()

                            rebuild()
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

                        if (!response.isSuccessful) {

                            runOnUiThread {

                                connected =
                                    false

                                Toast.makeText(
                                    this@MainActivity,
                                    "OAuth token error: ${response.code}",
                                    Toast.LENGTH_LONG
                                ).show()

                                rebuild()
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

                                    connected =
                                        false

                                    Toast.makeText(
                                        this@MainActivity,
                                        "No access token returned.",
                                        Toast.LENGTH_LONG
                                    ).show()

                                    rebuild()
                                }

                                return
                            }

                            // -------------------------------------------------
                            // SAVE TOKEN
                            // -------------------------------------------------

                            prefs.edit()
                                .putString(
                                    "access_token",
                                    accessToken
                                )
                                .putString(
                                    "oauth_mode",
                                    selectedMode
                                )
                                .apply()

                            /*
                             * IMPORTANT:
                             *
                             * OAuth success does NOT mean the Deriv
                             * execution account is connected.
                             *
                             * The token is now passed to the existing
                             * DerivApiClient.
                             *
                             * DerivApiClient will:
                             *
                             * 1. Request the available accounts.
                             * 2. Find DEMO or REAL.
                             * 3. Obtain the WebSocket connection.
                             * 4. Connect to the selected account.
                             * 5. Return account ID.
                             * 6. Return balance and currency.
                             */

                            runOnUiThread {

                                Toast.makeText(
                                    this@MainActivity,
                                    "OAuth successful. Connecting account...",
                                    Toast.LENGTH_SHORT
                                ).show()

                                rebuild()
                            }

                            derivApiClient
                                .connectWithAccessToken(
                                    accessToken,
                                    selectedMode
                                )

                        } catch (
                            e: Exception
                        ) {

                            runOnUiThread {

                                connected =
                                    false

                                Toast.makeText(
                                    this@MainActivity,
                                    "Invalid OAuth response.",
                                    Toast.LENGTH_LONG
                                ).show()

                                rebuild()
                            }
                        }
                    }
                }
            )
    }

    // =============================================================
    // REFRESH DERIV CONNECTION
    // =============================================================

    private fun refreshDerivConnection() {

        val token =
            prefs.getString(
                "access_token",
                ""
            )
                ?: ""

        if (token.isEmpty()) {

            Toast.makeText(
                this,
                "No Deriv connection found. Press CONNECT.",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        connected =
            false

        rebuild()

        derivApiClient
            .connectWithAccessToken(
                token,
                executionMode
            )
    }

    // =============================================================
    // DERIV CALLBACK
    // =============================================================

    override fun onConnecting(
        message: String
    ) {

        runOnUiThread {

            connected =
                false

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

            connected =
                true

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
    // DERIV ERROR
    // =============================================================

    override fun onError(
        message: String
    ) {

        runOnUiThread {

            connected =
                false

            rebuild()

            Toast.makeText(
                this@MainActivity,
                message,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // =============================================================
    // DERIV DISCONNECTED
    // =============================================================

    override fun onDisconnected() {

        runOnUiThread {

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
    }

    // =============================================================
    // LOGOUT
    // =============================================================

    private fun logout() {

        derivApiClient.disconnect()

        prefs.edit()
            .remove("access_token")
            .remove("oauth_state")
            .remove("code_verifier")
            .remove("oauth_mode")
            .apply()

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

        currentPlatform =
            "USER"

        rebuild()

        Toast.makeText(
            this,
            "Logged out.",
            Toast.LENGTH_SHORT
        ).show()
    }
        // =============================================================
    // REBUILD SCREEN
    // =============================================================

    private fun rebuild() {
        if (!::rootLayout.isInitialized) return

        rootLayout.removeAllViews()

        addHeader()
        addPlatformSelector()
        addExecutionSelector()
        addAccountCard()
        addAutoTrade()
        addIndexMonitor()

        if (currentPlatform == "ADMIN") {
            addAdminFeatures()
        }

        addBottomNavigation()
    }


    // =============================================================
    // DP HELPER
    // =============================================================

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }


    // =============================================================
    // TEXT VIEW HELPER
    // =============================================================

    private fun text(
        value: String,
        size: Float = 14f,
        color: Int = WHITE,
        bold: Boolean = false
    ): TextView {

        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER_VERTICAL

            if (bold) {
                setTypeface(typeface, Typeface.BOLD)
            }

            setPadding(
                dp(4),
                dp(2),
                dp(4),
                dp(2)
            )
        }
    }


    // =============================================================
    // CARD HELPER
    // =============================================================

    private fun card(): LinearLayout {

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(12),
                dp(10),
                dp(12),
                dp(10)
            )

            setBackgroundColor(CARD)

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(
                    dp(8),
                    dp(5),
                    dp(8),
                    dp(5)
                )
            }
        }
    }


    // =============================================================
    // HORIZONTAL ROW HELPER
    // =============================================================

    private fun row(): LinearLayout {

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }


    // =============================================================
    // SMALL SPACER
    // =============================================================

    private fun spacer(height: Int = 8): View {

        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(height)
            )
        }
    }


    // =============================================================
    // TOAST HELPER
    // =============================================================

    private fun showMessage(message: String) {
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
        button.isEnabled = enabled

        button.alpha = if (enabled) {
            1.0f
        } else {
            0.5f
        }
    }
        // =============================================================
    // ACTIVITY LIFECYCLE
    // =============================================================

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)

        if (intent != null) {
            setIntent(intent)
            handleOAuthCallback(intent)
        }
    }


    override fun onDestroy() {
        super.onDestroy()

        try {
            derivApiClient.disconnect()
        } catch (_: Exception) {
        }
    }


    // =============================================================
    // FINAL CLASS
    // =============================================================

}

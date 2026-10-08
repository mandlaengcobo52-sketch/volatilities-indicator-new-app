package za.co.volatilitiesindicator.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
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
import java.util.UUID

class MainActivity : Activity(), DerivApiClient.Callback {

    // ============================================================
    // COLOURS
    // ============================================================

    private val bg = Color.rgb(2, 14, 18)
    private val panel = Color.rgb(5, 27, 34)
    private val panel2 = Color.rgb(7, 35, 43)
    private val panel3 = Color.rgb(8, 43, 51)

    private val green = Color.rgb(0, 245, 90)
    private val cyan = Color.rgb(0, 220, 255)
    private val yellow = Color.rgb(255, 220, 0)
    private val red = Color.rgb(255, 55, 70)
    private val purple = Color.rgb(175, 60, 255)
    private val blue = Color.rgb(35, 135, 255)

    private val white = Color.rgb(240, 246, 248)
    private val muted = Color.rgb(145, 165, 172)

    // ============================================================
    // ROOT
    // ============================================================

    private lateinit var rootScroll: ScrollView
    private lateinit var rootLayout: LinearLayout

    // ============================================================
    // PLATFORM
    // ============================================================

    private var currentPlatform = "USER"
    private var executionMode = "DEMO"

    // ============================================================
    // DERIV
    // ============================================================

    private var connected = false
    private var connecting = false

    private var accountId = ""
    private var accountType = ""
    private var accountBalance = 0.0
    private var accountCurrency = ""

    private val derivApiClient by lazy {
        DerivApiClient(this)
    }

    private val httpClient = OkHttpClient()

    // ============================================================
    // PERSISTENT SETTINGS
    // ============================================================

    private val prefs by lazy {
        getSharedPreferences(
            "volatilities_indicator",
            MODE_PRIVATE
        )
    }

    private var autoTradeEnabled = false

    // ============================================================
    // OAUTH
    // ============================================================

    private var oauthState = ""
    private var codeVerifier = ""

    // ============================================================
    // INDEXES
    // ============================================================

    private val indexes = listOf(
        "R_10",
        "R_25",
        "R_50",
        "R_75",
        "R_100"
    )

    // ============================================================
    // INDEX COLOURS
    // ============================================================

    private fun indexColour(index: String): Int {

        return when (index) {

            "R_10" -> cyan
            "R_25" -> green
            "R_50" -> yellow
            "R_75" -> red
            "R_100" -> purple

            else -> white
        }
    }

    // ============================================================
    // ACTIVITY
    // ============================================================

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        window.statusBarColor = bg
        window.navigationBarColor = bg

        autoTradeEnabled =
            prefs.getBoolean(
                "auto_trade_enabled",
                false
            )

        buildMainScreen()

        handleOAuthCallback(intent)
    }

    override fun onNewIntent(intent: Intent?) {

        super.onNewIntent(intent)

        if (intent != null) {

            setIntent(intent)

            handleOAuthCallback(intent)
        }
    }

    // ============================================================
    // MAIN SCREEN
    // ============================================================

    private fun buildMainScreen() {

        rootScroll = ScrollView(this).apply {

            setBackgroundColor(bg)

            isFillViewport = true

            overScrollMode =
                View.OVER_SCROLL_NEVER
        }

        rootLayout = LinearLayout(this).apply {

            orientation =
                LinearLayout.VERTICAL

            setPadding(
                dp(12),
                dp(8),
                dp(12),
                dp(18)
            )

            setBackgroundColor(bg)
        }

        rootScroll.addView(rootLayout)

        setContentView(rootScroll)

        rebuild()
    }

    // ============================================================
    // REBUILD
    // ============================================================

    private fun rebuild() {

        if (!::rootLayout.isInitialized) {
            return
        }

        rootLayout.removeAllViews()

        addHeader()

        addPlatformSelector()

        addExecutionSelector()

        addAutoTrade()

        addDerivAccount()

        addIndexMonitor()

        if (currentPlatform == "ADMIN") {
            addAdminFeatures()
        }

        addBottomNavigation()
    }

    // ============================================================
    // HEADER
    // ============================================================

    private fun addHeader() {

        val header = LinearLayout(this).apply {

            orientation =
                LinearLayout.HORIZONTAL

            gravity =
                Gravity.CENTER_VERTICAL

            setPadding(
                0,
                dp(4),
                0,
                dp(10)
            )
        }

        // --------------------------------------------------------
        // MENU
        // --------------------------------------------------------

        val menu = TextView(this).apply {

            text = "☰"

            textSize = 28f

            setTextColor(white)

            gravity =
                Gravity.CENTER

            visibility =
                if (currentPlatform == "ADMIN") {
                    View.VISIBLE
                } else {
                    View.VISIBLE
                }

            setOnClickListener {

                if (currentPlatform == "ADMIN") {
                    showAdminMenu()
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "User menu",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        header.addView(
            menu,
            LinearLayout.LayoutParams(
                dp(42),
                dp(52)
            )
        )

        // --------------------------------------------------------
        // APPLICATION ICON
        // --------------------------------------------------------

        val logo = ImageView(this).apply {

            try {

                setImageDrawable(
                    packageManager
                        .getApplicationIcon(
                            packageName
                        )
                )

            } catch (_: Exception) {

                setImageResource(
                    android.R.drawable.ic_menu_info_details
                )
            }

            scaleType =
                ImageView.ScaleType.CENTER_INSIDE
        }

        header.addView(
            logo,
            LinearLayout.LayoutParams(
                dp(54),
                dp(54)
            ).apply {

                rightMargin =
                    dp(8)
            }
        )

        // --------------------------------------------------------
        // TITLE
        // --------------------------------------------------------

        val titleBox =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                gravity =
                    Gravity.CENTER_VERTICAL

                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        dp(58),
                        1f
                    )
            }

        val title =
            TextView(this).apply {

                text =
                    "VOLATILITIES"

                textSize =
                    18f

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                setTextColor(
                    white
                )
            }

        titleBox.addView(title)

        val indicator =
            TextView(this).apply {

                text =
                    "INDICATOR"

                textSize =
                    17f

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                setTextColor(
                    green
                )
            }

        titleBox.addView(indicator)

        val tagline =
            TextView(this).apply {

                text =
                    "TRADE SMARTER TODAY"

                textSize =
                    8f

                setTextColor(
                    muted
                )
            }

        titleBox.addView(tagline)

        header.addView(titleBox)

        // --------------------------------------------------------
        // BELL
        // --------------------------------------------------------

        val bell =
            TextView(this).apply {

                text =
                    "♧"

                textSize =
                    27f

                setTextColor(
                    white
                )

                gravity =
                    Gravity.CENTER

                setOnClickListener {

                    Toast.makeText(
                        this@MainActivity,
                        "Notifications",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

        header.addView(
            bell,
            LinearLayout.LayoutParams(
                dp(46),
                dp(52)
            )
        )

        rootLayout.addView(header)
    }

    // ============================================================
    // PLATFORM SELECTOR
    // ============================================================

    private fun addPlatformSelector() {

        addSectionTitle(
            "PLATFORM"
        )

        val row =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER
            }

        val user =
            createButton(
                "●  USER",
                if (
                    currentPlatform == "USER"
                ) green
                else panel2
            )

        val admin =
            createButton(
                "●●  ADMIN",
                if (
                    currentPlatform == "ADMIN"
                ) green
                else panel2
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
            buttonParams(
                right = 4
            )
        )

        row.addView(
            admin,
            buttonParams(
                left = 4
            )
        )

        rootLayout.addView(row)
    }

    // ============================================================
    // EXECUTION MODE
    // ============================================================

    private fun addExecutionSelector() {

        addSectionTitle(
            "EXECUTION MODE"
        )

        val row =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER
            }

        val demo =
            createButton(
                "▥  DEMO",
                if (
                    executionMode == "DEMO"
                ) green
                else panel2
            )

        val real =
            createButton(
                "▥  REAL",
                if (
                    executionMode == "REAL"
                ) green
                else panel2
            )

        demo.setOnClickListener {

            executionMode =
                "DEMO"

            resetConnectionForMode()

            rebuild()
        }

        real.setOnClickListener {

            executionMode =
                "REAL"

            resetConnectionForMode()

            rebuild()
        }

        row.addView(
            demo,
            buttonParams(
                right = 4
            )
        )

        row.addView(
            real,
            buttonParams(
                left = 4
            )
        )

        rootLayout.addView(row)
    }

    // ============================================================
    // RESET CONNECTION WHEN MODE CHANGES
    // ============================================================

    private fun resetConnectionForMode() {

        connected = false
        connecting = false

        accountId = ""
        accountType = ""
        accountBalance = 0.0
        accountCurrency = ""

        derivApiClient.disconnect()
    }
        // ============================================================
    // AUTO TRADE
    // ============================================================

    private fun addAutoTrade() {

        val box =
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

                setBackgroundColor(
                    panel
                )

                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(48)
                    ).apply {
                        topMargin = dp(8)
                        bottomMargin = dp(8)
                    }
            }

        val title =
            TextView(this).apply {

                text =
                    "AUTO TRADE"

                textSize =
                    13f

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                setTextColor(
                    white
                )

                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
            }

        box.addView(title)

        val state =
            TextView(this).apply {

                text =
                    if (
                        autoTradeEnabled
                    ) {
                        "ON"
                    } else {
                        "OFF"
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
                    ) green
                    else yellow
                )

                gravity =
                    Gravity.CENTER

                setPadding(
                    dp(14),
                    dp(5),
                    dp(14),
                    dp(5)
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

        box.addView(
            state,
            LinearLayout.LayoutParams(
                dp(70),
                dp(34)
            )
        )

        rootLayout.addView(box)
    }

    // ============================================================
    // DERIV EXECUTION ACCOUNT
    // ============================================================

    private fun addDerivAccount() {

        addSectionTitle(
            "DERIV EXECUTION ACCOUNT"
        )

        val box =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    dp(12),
                    dp(10),
                    dp(12),
                    dp(10)
                )

                setBackgroundColor(
                    panel
                )
            }

        // --------------------------------------------------------
        // CONNECTION STATUS
        // --------------------------------------------------------

        val status =
            TextView(this).apply {

                text =
                    when {

                        connected ->
                            "🟢  CONNECTION READY"

                        connecting ->
                            "🟡  CONNECTING..."

                        else ->
                            "🔴  NOT CONNECTED"
                    }

                textSize =
                    14f

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                setTextColor(
                    when {

                        connected ->
                            green

                        connecting ->
                            yellow

                        else ->
                            red
                    }
                )
            }

        box.addView(status)

        // --------------------------------------------------------
        // ACCOUNT
        // --------------------------------------------------------

        val account =
            TextView(this).apply {

                text =
                    if (
                        accountId.isNotEmpty()
                    ) {
                        "Account: $accountId"
                    } else {
                        "Account: —"
                    }

                textSize =
                    13f

                setTextColor(
                    white
                )

                setPadding(
                    0,
                    dp(7),
                    0,
                    dp(2)
                )
            }

        box.addView(account)

        // --------------------------------------------------------
        // BALANCE
        // --------------------------------------------------------

        val balance =
            TextView(this).apply {

                text =
                    if (
                        accountCurrency.isNotEmpty()
                    ) {

                        "Balance: %.2f %s".format(
                            accountBalance,
                            accountCurrency
                        )

                    } else {

                        "Balance: —"
                    }

                textSize =
                    13f

                setTextColor(
                    white
                )

                setPadding(
                    0,
                    dp(2),
                    0,
                    dp(2)
                )
            }

        box.addView(balance)

        // --------------------------------------------------------
        // OPEN P/L
        // --------------------------------------------------------

        val pnl =
            TextView(this).apply {

                text =
                    "Open P/L: —"

                textSize =
                    13f

                setTextColor(
                    white
                )

                setPadding(
                    0,
                    dp(2),
                    0,
                    dp(8)
                )
            }

        box.addView(pnl)

        // --------------------------------------------------------
        // BUTTONS
        // --------------------------------------------------------

        val buttons =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL
            }

        val connect =
            createButton(
                if (
                    connecting
                ) {
                    "CONNECTING..."
                } else {
                    "🔗  CONNECT"
                },
                green
            )

        connect.isEnabled =
            !connecting

        connect.setOnClickListener {

            if (connected) {

                Toast.makeText(
                    this@MainActivity,
                    "Deriv account already connected.",
                    Toast.LENGTH_SHORT
                ).show()

            } else {

                startDerivOAuth()
            }
        }

        val refresh =
            createButton(
                "⟳  REFRESH",
                panel2
            )

        refresh.setOnClickListener {

            refreshDerivConnection()
        }

        buttons.addView(
            connect,
            buttonParams(
                right = 4
            )
        )

        buttons.addView(
            refresh,
            buttonParams(
                left = 4
            )
        )

        box.addView(buttons)

        rootLayout.addView(box)
    }

    // ============================================================
    // INDEX MONITOR
    // ============================================================

    private fun addIndexMonitor() {

        addSectionTitle(
            "INDEX MONITOR"
        )

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

    // ============================================================
    // INDEX ROW
    // ============================================================

    private fun createIndexRow(
        index: String
    ): LinearLayout {

        val colour =
            indexColour(index)

        val row =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL

                setPadding(
                    dp(10),
                    dp(7),
                    dp(6),
                    dp(7)
                )

                setBackgroundColor(
                    panel
                )

                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(52)
                    ).apply {

                        bottomMargin =
                            dp(5)
                    }
            }

        // --------------------------------------------------------
        // INDEX NAME
        // --------------------------------------------------------

        val name =
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
                    colour
                )

                layoutParams =
                    LinearLayout.LayoutParams(
                        dp(72),
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
            }

        row.addView(name)

        // --------------------------------------------------------
        // MINI GRAPH
        // --------------------------------------------------------

        val graph =
            TextView(this).apply {

                text =
                    "╱╲╱╲╲╱"

                textSize =
                    14f

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                setTextColor(
                    colour
                )

                gravity =
                    Gravity.CENTER

                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
            }

        row.addView(graph)

        // --------------------------------------------------------
        // STATUS
        // --------------------------------------------------------

        val waiting =
            TextView(this).apply {

                text =
                    "WAITING"

                textSize =
                    11f

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                setTextColor(
                    yellow
                )

                gravity =
                    Gravity.CENTER
            }

        row.addView(
            waiting,
            LinearLayout.LayoutParams(
                dp(68),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // --------------------------------------------------------
        // ARROW
        // --------------------------------------------------------

        val arrow =
            TextView(this).apply {

                text =
                    "›"

                textSize =
                    28f

                setTextColor(
                    white
                )

                gravity =
                    Gravity.CENTER

                setOnClickListener {

                    openIndex(
                        index
                    )
                }
            }

        row.addView(
            arrow,
            LinearLayout.LayoutParams(
                dp(30),
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )

        return row
    }

    // ============================================================
    // OPEN INDEX
    // ============================================================

    private fun openIndex(
        index: String
    ) {

        Toast.makeText(
            this,
            "$index selected",
            Toast.LENGTH_SHORT
        ).show()
    }

    // ============================================================
    // ADMIN FEATURES
    // ============================================================

    private fun addAdminFeatures() {

        addSectionTitle(
            "ADMIN FEATURES"
        )

        val grid =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL
            }

        addAdminFeatureButton(
            grid,
            "⌂  DASHBOARD"
        )

        addAdminFeatureButton(
            grid,
            "▥  INDEX SETTINGS"
        )

        addAdminFeatureButton(
            grid,
            "◆  RISK CATEGORIES"
        )

        addAdminFeatureButton(
            grid,
            "●●  USER ACCOUNTS"
        )

        addAdminFeatureButton(
            grid,
            "♧  NOTIFICATIONS"
        )

        addAdminFeatureButton(
            grid,
            "▤  TRADING LOGS"
        )

        addAdminFeatureButton(
            grid,
            "⚙  SYSTEM SETTINGS"
        )

        addAdminFeatureButton(
            grid,
            "⇥  LOGOUT"
        )

        rootLayout.addView(grid)
    }

    // ============================================================
    // ADMIN FEATURE BUTTON
    // ============================================================

    private fun addAdminFeatureButton(
        parent: LinearLayout,
        label: String
    ) {

        val button =
            createButton(
                label,
                panel2
            )

        button.setOnClickListener {

            handleAdminFeature(
                label
            )
        }

        parent.addView(
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
        // ============================================================
    // ADMIN FEATURE ACTIONS
    // ============================================================

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

    // ============================================================
    // ADMIN MESSAGE
    // ============================================================

    private fun showAdminMessage(
        title: String
    ) {

        Toast.makeText(
            this,
            title,
            Toast.LENGTH_SHORT
        ).show()
    }

    // ============================================================
    // ADMIN SIDE MENU
    // ============================================================

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

                setBackgroundColor(
                    panel
                )
            }

        val dialog =
            android.app.Dialog(this)

        val title =
            TextView(this).apply {

                text =
                    "ADMIN MENU"

                textSize =
                    18f

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                setTextColor(
                    green
                )

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
                createButton(
                    item,
                    panel2
                )

            button.setOnClickListener {

                dialog.dismiss()

                handleAdminFeature(
                    item
                )
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

        val window =
            dialog.window

        window?.setBackgroundDrawableResource(
            android.R.color.transparent
        )

        window?.setLayout(
            dp(300),
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        dialog.show()

        window?.setLayout(
            dp(300),
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    // ============================================================
    // BOTTOM NAVIGATION
    // ============================================================

    private fun addBottomNavigation() {

        val nav =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER

                setPadding(
                    0,
                    dp(10),
                    0,
                    0
                )
            }

        addNavItem(
            nav,
            "⌂",
            "Home",
            true
        )

        addNavItem(
            nav,
            "▥",
            "Trade",
            false
        )

        addNavItem(
            nav,
            "⌁",
            "Analysis",
            false
        )

        addNavItem(
            nav,
            "▤",
            "News",
            false
        )

        addNavItem(
            nav,
            "⚙",
            "Settings",
            false
        )

        rootLayout.addView(nav)
    }

    // ============================================================
    // NAV ITEM
    // ============================================================

    private fun addNavItem(
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

                setPadding(
                    dp(4),
                    dp(4),
                    dp(4),
                    dp(4)
                )

                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        dp(60),
                        1f
                    )
            }

        val iconView =
            TextView(this).apply {

                text =
                    icon

                textSize =
                    21f

                setTextColor(
                    if (selected)
                        green
                    else
                        muted
                )

                gravity =
                    Gravity.CENTER
            }

        item.addView(iconView)

        val textView =
            TextView(this).apply {

                text =
                    label

                textSize =
                    9f

                setTextColor(
                    if (selected)
                        green
                    else
                        muted
                )

                gravity =
                    Gravity.CENTER
            }

        item.addView(textView)

        item.setOnClickListener {

            Toast.makeText(
                this@MainActivity,
                label,
                Toast.LENGTH_SHORT
            ).show()
        }

        parent.addView(item)
    }

    // ============================================================
    // SECTION TITLE
    // ============================================================

    private fun addSectionTitle(
        title: String
    ) {

        val view =
            TextView(this).apply {

                text =
                    title

                textSize =
                    14f

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                setTextColor(
                    white
                )

                setPadding(
                    0,
                    dp(7),
                    0,
                    dp(7)
                )
            }

        rootLayout.addView(view)
    }

    // ============================================================
    // BUTTON
    // ============================================================

    private fun createButton(
        textValue: String,
        backgroundColour: Int
    ): Button {

        return Button(this).apply {

            text =
                textValue

            textSize =
                12f

            isAllCaps =
                false

            setTypeface(
                null,
                Typeface.BOLD
            )

            setTextColor(
                if (
                    backgroundColour ==
                    green
                ) {
                    Color.BLACK
                } else {
                    white
                }
            )

            setBackgroundColor(
                backgroundColour
            )

            minHeight =
                0

            minimumHeight =
                0

            stateListAnimator =
                null

            setPadding(
                dp(5),
                dp(2),
                dp(5),
                dp(2)
            )
        }
    }

    // ============================================================
    // BUTTON PARAMETERS
    // ============================================================

    private fun buttonParams(
        left: Int = 0,
        right: Int = 0
    ): LinearLayout.LayoutParams {

        return LinearLayout.LayoutParams(
            0,
            dp(52),
            1f
        ).apply {

            if (left > 0) {
                leftMargin =
                    dp(left)
            }

            if (right > 0) {
                rightMargin =
                    dp(right)
            }
        }
    }

    // ============================================================
    // DERIV OAUTH
    // ============================================================

    private fun startDerivOAuth() {

        try {

            connecting = true

            rebuild()

            val state =
                UUID.randomUUID()
                    .toString()

            val verifier =
                DerivApiClient
                    .createCodeVerifier()

            val challenge =
                DerivApiClient
                    .createCodeChallenge(
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
                DerivApiClient
                    .clientId(
                        executionMode
                    )

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
                        "authorize"
                    )
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

            connecting = false

            rebuild()

            Toast.makeText(
                this,
                "OAuth error: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ============================================================
    // OAUTH CALLBACK
    // ============================================================

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

        val error =
            uri.getQueryParameter(
                "error"
            )

        if (
            !error.isNullOrEmpty()
        ) {

            connecting = false

            rebuild()

            Toast.makeText(
                this,
                "OAuth error: $error",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        val returnedState =
            uri.getQueryParameter(
                "state"
            )

        val code =
            uri.getQueryParameter(
                "code"
            )

        val savedState =
            prefs.getString(
                "oauth_state",
                ""
            )
                ?: ""

        val verifier =
            prefs.getString(
                "code_verifier",
                ""
            )
                ?: ""

        if (
            returnedState.isNullOrEmpty() ||
            returnedState != savedState
        ) {

            connecting = false

            rebuild()

            Toast.makeText(
                this,
                "OAuth security check failed.",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        if (
            code.isNullOrEmpty() ||
            verifier.isEmpty()
        ) {

            connecting = false

            rebuild()

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
        // ============================================================
    // OAUTH TOKEN EXCHANGE
    // ============================================================

    private fun exchangeOAuthCode(
        code: String,
        verifier: String
    ) {

        val savedMode =
            prefs.getString(
                "oauth_mode",
                executionMode
            )
                ?: executionMode

        val clientId =
            DerivApiClient.clientId(
                savedMode
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

        httpClient
            .newCall(request)
            .enqueue(
                object : Callback {

                    override fun onFailure(
                        call: Call,
                        e: IOException
                    ) {

                        runOnUiThread {

                            connecting =
                                false

                            connected =
                                false

                            rebuild()

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

                        val body =
                            response.body
                                ?.string()
                                .orEmpty()

                        if (
                            !response.isSuccessful
                        ) {

                            runOnUiThread {

                                connecting =
                                    false

                                connected =
                                    false

                                rebuild()

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
                                    body
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

                                    connecting =
                                        false

                                    connected =
                                        false

                                    rebuild()

                                    Toast.makeText(
                                        this@MainActivity,
                                        "No access token returned.",
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
                                .putString(
                                    "oauth_mode",
                                    savedMode
                                )
                                .apply()

                            /*
                             * IMPORTANT:
                             *
                             * Do NOT set connected=true here.
                             *
                             * The token only proves OAuth succeeded.
                             * DerivApiClient must now:
                             *
                             * 1. Get the Deriv accounts.
                             * 2. Find DEMO or REAL account.
                             * 3. Request the OTP WebSocket URL.
                             * 4. Open the WebSocket.
                             * 5. Read the balance.
                             */

                            runOnUiThread {

                                connecting =
                                    true

                                connected =
                                    false

                                rebuild()

                                Toast.makeText(
                                    this@MainActivity,
                                    "OAuth successful. Connecting to Deriv account...",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }

                            derivApiClient
                                .connectWithAccessToken(
                                    accessToken,
                                    savedMode
                                )

                        } catch (
                            e: Exception
                        ) {

                            runOnUiThread {

                                connecting =
                                    false

                                connected =
                                    false

                                rebuild()

                                Toast.makeText(
                                    this@MainActivity,
                                    "Invalid OAuth response.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
            )
    }

    // ============================================================
    // REFRESH DERIV CONNECTION
    // ============================================================

    private fun refreshDerivConnection() {

        val token =
            prefs.getString(
                "access_token",
                ""
            )
                ?: ""

        if (
            token.isEmpty()
        ) {

            Toast.makeText(
                this,
                "No Deriv connection found. Press CONNECT.",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        connecting =
            true

        connected =
            false

        rebuild()

        derivApiClient
            .connectWithAccessToken(
                token,
                executionMode
            )
    }

    // ============================================================
    // DERIV CALLBACK:
    // CONNECTING
    // ============================================================

    override fun onConnecting(
        message: String
    ) {

        runOnUiThread {

            connecting =
                true

            connected =
                false

            rebuild()
        }
    }

    // ============================================================
    // DERIV CALLBACK:
    // CONNECTED
    // ============================================================

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

            connecting =
                false

            rebuild()

            Toast.makeText(
                this@MainActivity,
                "Deriv account connected: $accountId",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ============================================================
    // DERIV CALLBACK:
    // BALANCE
    // ============================================================

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

            connecting =
                false

            rebuild()
        }
    }

    // ============================================================
    // DERIV CALLBACK:
    // ERROR
    // ============================================================

    override fun onError(
        message: String
    ) {

        runOnUiThread {

            connecting =
                false

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

    // ============================================================
    // DERIV CALLBACK:
    // DISCONNECTED
    // ============================================================

    override fun onDisconnected() {

        runOnUiThread {

            connected =
                false

            connecting =
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

    // ============================================================
    // LOGOUT
    // ============================================================

    private fun logout() {

        derivApiClient.disconnect()

        prefs.edit()
            .remove(
                "access_token"
            )
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

        connected =
            false

        connecting =
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

    // ============================================================
    // DIMENSION
    // ============================================================

    private fun dp(
        value: Int
    ): Int {

        return (
            value *
                resources
                    .displayMetrics
                    .density
            ).toInt()
    }

    // ============================================================
    // ACTIVITY DESTROY
    // ============================================================

    override fun onDestroy() {

        super.onDestroy()
    }
        // ============================================================
    // END OF MAINACTIVITY
    // ============================================================

}

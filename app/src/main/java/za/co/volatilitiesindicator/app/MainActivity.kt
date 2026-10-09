package za.co.volatilitiesindicator.app

import android.app.Activity
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

/**
 * Volatilities Indicator.
 * Shell, branding and navigation only.
 * Deriv connection will be integrated separately.
 */
class MainActivity : Activity() {

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

    private var platform = "USER"
    private var executionMode = "DEMO"
    private var selectedTab = "Home"
    private var drawerOpen = false

    private lateinit var page: LinearLayout
    private lateinit var accountStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0

        render()
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

        addPlatformSelector()
        addExecutionSelector()
        addAccountCard()
        addIndexMonitor()

        if (platform == "ADMIN") {
            addAdminPreview()
        }

        addBottomNavigation(outer)
    }

    // =========================================================
    // HEADER: EXISTING LOGO AND NOTIFICATION BELL
    // =========================================================

    private fun addHeader() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(12))
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
                drawerOpen = false
                selectedTab = item
                render()
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
        page.addView(sectionTitle("PLATFORM"))

        val row = horizontalRow()

        row.addView(
            button("♟  USER", platform == "USER") {
                platform = "USER"
                drawerOpen = false
                render()
            },
            weighted()
        )

        row.addView(space(dp(8)))

        row.addView(
            button("♟♟  ADMIN", platform == "ADMIN") {
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
            },
            weighted()
        )

        row.addView(space(dp(8)))

        row.addView(
            button("▥  REAL", executionMode == "REAL") {
                executionMode = "REAL"
                render()
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
            "●  NOT CONNECTED",
            14f,
            red,
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

        card.addView(
            label("Account:  —", 14f, white, false),
            matchWrap().apply {
                topMargin = dp(7)
            }
        )

        card.addView(
            label("Balance:  —", 14f, white, false),
            matchWrap().apply {
                topMargin = dp(4)
            }
        )

        card.addView(
            label("Open P/L:  —", 14f, white, false),
            matchWrap().apply {
                topMargin = dp(4)
            }
        )

        val buttons = horizontalRow()

        buttons.addView(
            button("↗  CONNECT", true) {
                toast(
                    "Deriv connection will be integrated separately."
                )
            },
            weighted()
        )

        buttons.addView(space(dp(8)))

        buttons.addView(
            button("⟳  REFRESH", false) {
                toast(
                    "Account refresh will be available after connection."
                )
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
    // INDEX MONITOR — VISUAL PLACEHOLDER ONLY
    // =========================================================

    private fun addIndexMonitor() {
        val titleRow = horizontalRow().apply {
            gravity = Gravity.CENTER_VERTICAL

            addView(
                sectionTitle("INDEX MONITOR"),
                LinearLayout.LayoutParams(0, -2, 1f)
            )

            addView(
                label("⌃", 24f, white, true)
            )
        }

        page.addView(titleRow)

        val indexes = listOf(
            Triple("R_10", cyan, "⌁ 〰⌁"),
            Triple("R_25", green, "⌁〰⌁"),
            Triple("R_50", yellow, "〰⌁〰"),
            Triple("R_75", red, "⌁〰⌁"),
            Triple(
                "R_100",
                Color.rgb(190, 75, 255),
                "⌁〰⌁"
            )
        )

        indexes.forEach { (name, tint, spark) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                setPadding(
                    dp(12),
                    dp(8),
                    dp(10),
                    dp(8)
                )

                background = shape(panel, tint, 12, 1)

                setOnClickListener {
                    toast("$name details will be added later.")
                }
            }

            row.addView(
                label(name, 20f, tint, true),
                LinearLayout.LayoutParams(dp(76), -2)
            )

            row.addView(
                label(spark, 17f, tint, false),
                LinearLayout.LayoutParams(0, -2, 1f)
            )

            row.addView(
                label("WAITING", 12f, yellow, true),
                LinearLayout.LayoutParams(dp(76), -2)
            )

            row.addView(
                label("›", 26f, white, false)
            )

            page.addView(
                row,
                matchWrap().apply {
                    bottomMargin = dp(5)
                }
            )
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
}

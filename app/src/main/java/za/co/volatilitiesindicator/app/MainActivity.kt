package za.co.volatilitiesindicator.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    // =============================================================
    // COLOURS
    // =============================================================

    private val bg = Color.rgb(2, 8, 12)
    private val panel = Color.rgb(5, 22, 29)
    private val panel2 = Color.rgb(7, 31, 40)
    private val border = Color.rgb(0, 180, 100)

    private val green = Color.rgb(0, 240, 100)
    private val red = Color.rgb(255, 55, 70)
    private val yellow = Color.rgb(255, 215, 0)
    private val cyan = Color.rgb(0, 210, 255)
    private val purple = Color.rgb(175, 70, 255)

    private val white = Color.rgb(240, 247, 249)
    private val muted = Color.rgb(150, 170, 177)

    private var selectedPlatform = "USER"
    private var selectedMode = "DEMO"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        selectedPlatform = getSharedPreferences(
            "volatilities_settings",
            MODE_PRIVATE
        ).getString("platform", "USER") ?: "USER"

        selectedMode = getSharedPreferences(
            "volatilities_settings",
            MODE_PRIVATE
        ).getString("execution_mode", "DEMO") ?: "DEMO"

        buildScreen()
    }

    // =============================================================
    // MAIN SCREEN
    // =============================================================

    private fun buildScreen() {

        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(16),
                dp(10),
                dp(16),
                dp(20)
            )
        }

        scroll.addView(root)

        // ============================================================
// TOP HEADER
// ============================================================

val header = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

val menuButton = smallIconButton("☰")

menuButton.setOnClickListener {
    Toast.makeText(
        this@MainActivity,
        if (selectedPlatform == "ADMIN") {
            "Admin menu"
        } else {
            "User menu"
        },
        Toast.LENGTH_SHORT
    ).show()
}

header.addView(
    menuButton,
    LinearLayout.LayoutParams(
        dp(46),
        dp(52)
    )
)

val logo = ImageView(this).apply {
    setImageResource(
        R.drawable.volatilities_indicator_logo
    )
    scaleType = ImageView.ScaleType.CENTER_INSIDE
}

header.addView(
    logo,
    LinearLayout.LayoutParams(
        dp(68),
        dp(58)
    ).apply {
        marginStart = dp(2)
        marginEnd = dp(4)
    }
)

val titleBox = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER_VERTICAL
}

val title1 = text(
    "VOLATILITIES",
    19,
    white,
    true
)

val title2 = text(
    "INDICATOR",
    19,
    green,
    true
)

val tagline = text(
    "TRADE SMARTER TODAY",
    7,
    muted,
    true
)

titleBox.addView(title1)
titleBox.addView(title2)
titleBox.addView(tagline)

header.addView(
    titleBox,
    LinearLayout.LayoutParams(
        0,
        -2,
        1f
    )
)

val notification = smallIconButton("🔔")

notification.setOnClickListener {
    Toast.makeText(
        this@MainActivity,
        "No new notifications",
        Toast.LENGTH_SHORT
    ).show()
}

header.addView(
    notification,
    LinearLayout.LayoutParams(
        dp(46),
        dp(52)
    )
)

root.addView(header)

root.addView(spacer(8))

        // =========================================================
        // PLATFORM
        // =========================================================

        root.addView(
            sectionTitle("PLATFORM")
        )

        val platformRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val userButton = selectableButton(
            "👤  USER",
            selectedPlatform == "USER"
        )

        val adminButton = selectableButton(
            "👥  ADMIN",
            selectedPlatform == "ADMIN"
        )

        userButton.setOnClickListener {
            selectedPlatform = "USER"

            savePlatform()

            buildScreen()
        }

        adminButton.setOnClickListener {
            selectedPlatform = "ADMIN"

            savePlatform()

            buildScreen()
        }

        platformRow.addView(
            userButton,
            LinearLayout.LayoutParams(
                0,
                dp(58),
                1f
            ).apply {
                marginEnd = dp(6)
            }
        )

        platformRow.addView(
            adminButton,
            LinearLayout.LayoutParams(
                0,
                dp(58),
                1f
            )
        )

        root.addView(platformRow)

        root.addView(spacer(12))

        // =========================================================
        // EXECUTION MODE
        // =========================================================

        root.addView(
            sectionTitle("EXECUTION MODE")
        )

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val demoButton = selectableButton(
            "📈  DEMO",
            selectedMode == "DEMO"
        )

        val realButton = selectableButton(
            "▥  REAL",
            selectedMode == "REAL"
        )

        demoButton.setOnClickListener {
            selectedMode = "DEMO"

            saveMode()

            buildScreen()
        }

        realButton.setOnClickListener {
            selectedMode = "REAL"

            saveMode()

            buildScreen()
        }

        modeRow.addView(
            demoButton,
            LinearLayout.LayoutParams(
                0,
                dp(58),
                1f
            ).apply {
                marginEnd = dp(6)
            }
        )

        modeRow.addView(
            realButton,
            LinearLayout.LayoutParams(
                0,
                dp(58),
                1f
            )
        )

        root.addView(modeRow)

        root.addView(spacer(12))

        // =========================================================
        // AUTO TRADE
        // =========================================================

        root.addView(
            autoTradeButton()
        )

        root.addView(spacer(14))

        // =========================================================
        // DERIV ACCOUNT
        // =========================================================

        root.addView(
            derivAccountCard()
        )

        root.addView(spacer(14))

        // =========================================================
        // INDEX MONITOR
        // =========================================================

        root.addView(
            sectionTitle("INDEX MONITOR")
        )

        val indexes = listOf(
            "R_10",
            "R_25",
            "R_50",
            "R_75",
            "R_100"
        )

        indexes.forEach { index ->
            root.addView(
                indexRow(index)
            )
        }

        root.addView(spacer(14))

        // =========================================================
        // ADMIN MENU
        // =========================================================

        if (selectedPlatform == "ADMIN") {

            root.addView(
                sectionTitle("ADMINISTRATION")
            )

            root.addView(
                adminMenu()
            )

            root.addView(spacer(14))
        }

        // =========================================================
        // ACCOUNT SUMMARY
        // =========================================================

        root.addView(
            sectionTitle("ACCOUNT SUMMARY")
        )

        root.addView(
            summaryRow()
        )

        root.addView(spacer(14))

        // =========================================================
        // BOTTOM NAVIGATION
        // =========================================================

        root.addView(
            bottomNavigation()
        )

        root.addView(spacer(12))

        root.addView(
            text(
                "VOLATILITIES INDICATOR • VERSION 1.0.0",
                10,
                muted,
                false,
                Gravity.CENTER
            )
        )

        setContentView(scroll)
    }

    // =============================================================
    // DERIV ACCOUNT CARD
    // =============================================================

    private fun derivAccountCard(): View {

        val card = card()

        card.setPadding(
            dp(12),
            dp(12),
            dp(12),
            dp(12)
        )

        card.addView(
            text(
                "⚡  DERIV EXECUTION ACCOUNT",
                15,
                yellow,
                true
            )
        )

        card.addView(
            text(
                "🔴  NOT CONNECTED",
                13,
                red,
                true,
                0,
                5
            )
        )

        card.addView(
            text(
                "Account: —",
                14,
                white
            )
        )

        card.addView(
            text(
                "Balance: —",
                14,
                white
            )
        )

        card.addView(
            text(
                "Open P/L: —",
                14,
                white
            )
        )

        card.addView(spacer(7))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val connect = selectableButton(
            "🔗  CONNECT",
            true
        )

        val refresh = selectableButton(
            "⟳  REFRESH",
            false
        )

        connect.setOnClickListener {

            Toast.makeText(
                this@MainActivity,
                "Deriv connection will be added next",
                Toast.LENGTH_SHORT
            ).show()
        }

        refresh.setOnClickListener {

            Toast.makeText(
                this@MainActivity,
                "Refresh requested",
                Toast.LENGTH_SHORT
            ).show()
        }

        buttons.addView(
            connect,
            LinearLayout.LayoutParams(
                0,
                dp(54),
                1f
            ).apply {
                marginEnd = dp(6)
            }
        )

        buttons.addView(
            refresh,
            LinearLayout.LayoutParams(
                0,
                dp(54),
                1f
            )
        )

        card.addView(buttons)

        return card
    }

    // =============================================================
    // AUTO TRADE
    // =============================================================

    private fun autoTradeButton(): View {

        val preferences = getSharedPreferences(
            "volatilities_settings",
            MODE_PRIVATE
        )

        var autoTradeOn = preferences.getBoolean(
            "auto_trade",
            false
        )

        val button = Button(this)

        fun updateButton() {

            if (autoTradeOn) {

                button.text =
                    "⚡  AUTO TRADE: ON"

                button.setTextColor(
                    Color.BLACK
                )

                button.setBackgroundColor(
                    green
                )

            } else {

                button.text =
                    "⚡  AUTO TRADE: OFF"

                button.setTextColor(
                    white
                )

                button.setBackgroundColor(
                    panel2
                )
            }
        }

        button.apply {

            textSize = 14f

            setTypeface(
                null,
                Typeface.BOLD
            )

            isAllCaps = false

            setOnClickListener {

                autoTradeOn = !autoTradeOn

                preferences.edit()
                    .putBoolean(
                        "auto_trade",
                        autoTradeOn
                    )
                    .apply()

                updateButton()

                Toast.makeText(
                    this@MainActivity,
                    if (autoTradeOn) {
                        "Auto Trade ON"
                    } else {
                        "Auto Trade OFF"
                    },
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        updateButton()

        return button.apply {

            layoutParams =
                LinearLayout.LayoutParams(
                    -1,
                    dp(58)
                )
        }
    }

    // =============================================================
    // INDEX ROW
    // =============================================================

    private fun indexRow(
        index: String
    ): View {

        val row = LinearLayout(this).apply {

            orientation =
                LinearLayout.HORIZONTAL

            gravity =
                Gravity.CENTER_VERTICAL

            setPadding(
                dp(10),
                dp(5),
                dp(8),
                dp(5)
            )

            setBackgroundColor(
                panel
            )
        }

        val colour = when (index) {

            "R_10" ->
                cyan

            "R_25" ->
                green

            "R_50" ->
                yellow

            "R_75" ->
                red

            else ->
                purple
        }

        val name = text(
            index,
            18,
            colour,
            true
        )

        row.addView(
            name,
            LinearLayout.LayoutParams(
                dp(72),
                dp(48)
            )
        )

        val chart = text(
            "╱╲╱╲╱╲",
            16,
            colour,
            true,
            Gravity.CENTER
        )

        row.addView(
            chart,
            LinearLayout.LayoutParams(
                0,
                dp(48),
                1f
            )
        )

        row.addView(
            badge(
                "WAITING",
                yellow
            ),
            LinearLayout.LayoutParams(
                dp(82),
                dp(38)
            )
        )

        row.addView(
            text(
                "›",
                28,
                white,
                false,
                Gravity.CENTER
            ),
            LinearLayout.LayoutParams(
                dp(30),
                dp(48)
            )
        )

        row.setOnClickListener {

            Toast.makeText(
                this@MainActivity,
                "$index selected",
                Toast.LENGTH_SHORT
            ).show()
        }

        return row.apply {

            layoutParams =
                LinearLayout.LayoutParams(
                    -1,
                    dp(58)
                ).apply {
                    bottomMargin = dp(5)
                }
        }
    }

    // =============================================================
    // ADMIN MENU
    // =============================================================

    private fun adminMenu(): View {

        val container = card()

        val items = listOf(
            "⌂  Dashboard",
            "▥  Index Settings",
            "⬟  Risk Categories",
            "👥  User Accounts",
            "♧  Notifications",
            "▤  Trading Logs",
            "⚙  System Settings",
            "⇥  Logout"
        )

        items.forEachIndexed { index, item ->

            val itemView = TextView(this).apply {

                text = item

                textSize = 14f

                setTextColor(
                    if (index == 0) {
                        green
                    } else {
                        white
                    }
                )

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                gravity =
                    Gravity.CENTER_VERTICAL

                setPadding(
                    dp(14),
                    0,
                    dp(10),
                    0
                )

                setBackgroundColor(
                    if (index == 0) {
                        Color.rgb(5, 45, 30)
                    } else {
                        panel2
                    }
                )

                setOnClickListener {

                    Toast.makeText(
                        this@MainActivity,
                        item.replace(
                            Regex("^[^ ]+\\s+"),
                            ""
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            container.addView(
                itemView,
                LinearLayout.LayoutParams(
                    -1,
                    dp(50)
                ).apply {
                    bottomMargin = dp(2)
                }
            )
        }

        return container
    }

    // =============================================================
    // ACCOUNT SUMMARY
    // =============================================================

    private fun summaryRow(): View {

        val row = LinearLayout(this).apply {
            orientation =
                LinearLayout.HORIZONTAL
        }

        listOf(
            "OPEN\n0",
            "TOTAL P/L\n0.00",
            "WINS\n0",
            "LOSSES\n0"
        ).forEachIndexed { i, s ->

            val colour = when (i) {

                1 ->
                    cyan

                2 ->
                    green

                3 ->
                    red

                else ->
                    cyan
            }

            val t = text(
                s,
                11,
                colour,
                true,
                Gravity.CENTER
            )

            t.setBackgroundColor(
                panel
            )

            row.addView(
                t,
                LinearLayout.LayoutParams(
                    0,
                    dp(58),
                    1f
                ).apply {
                    marginEnd = dp(4)
                }
            )
        }

        return row
    }

    // =============================================================
    // BOTTOM NAVIGATION
    // =============================================================

    private fun bottomNavigation(): View {

        val row = LinearLayout(this).apply {

            orientation =
                LinearLayout.HORIZONTAL

            setBackgroundColor(
                panel
            )

            setPadding(
                dp(4),
                dp(4),
                dp(4),
                dp(4)
            )
        }

        val items = listOf(
            "⌂\nHome",
            "▥\nTrade",
            "⌁\nAnalysis",
            "▤\nNews",
            "⚙\nSettings"
        )

        items.forEachIndexed { index, item ->

            val view = text(
                item,
                10,
                if (index == 0) {
                    green
                } else {
                    white
                },
                true,
                Gravity.CENTER
            )

            if (index == 0) {
                view.setBackgroundColor(
                    Color.rgb(4, 55, 35)
                )
            }

            row.addView(
                view,
                LinearLayout.LayoutParams(
                    0,
                    dp(58),
                    1f
                ).apply {
                    marginEnd = dp(2)
                }
            )

            view.setOnClickListener {

                Toast.makeText(
                    this@MainActivity,
                    item.substringAfter("\n"),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        return row
    }

    // =============================================================
    // SELECTABLE BUTTON
    // =============================================================

    private fun selectableButton(
        label: String,
        selected: Boolean
    ): Button {

        return Button(this).apply {

            text = label

            textSize = 14f

            setTypeface(
                null,
                Typeface.BOLD
            )

            isAllCaps = false

            setTextColor(
                if (selected) {
                    Color.BLACK
                } else {
                    white
                }
            )

            setBackgroundColor(
                if (selected) {
                    green
                } else {
                    panel2
                }
            )
        }
    }

    // =============================================================
    // SMALL ICON BUTTON
    // =============================================================

    private fun smallIconButton(
        label: String
    ): Button {

        return Button(this).apply {

            text = label

            textSize = 22f

            setTextColor(
                white
            )

            setBackgroundColor(
                Color.TRANSPARENT
            )

            isAllCaps = false

            setPadding(
                0,
                0,
                0,
                0
            )
        }
    }

    // =============================================================
    // BADGE
    // =============================================================

    private fun badge(
        label: String,
        colour: Int
    ): TextView {

        return text(
            label,
            9,
            colour,
            true,
            Gravity.CENTER
        ).apply {

            setBackgroundColor(
                panel2
            )
        }
    }

    // =============================================================
    // CARD
    // =============================================================

    private fun card(): LinearLayout {

        return LinearLayout(this).apply {

            orientation =
                LinearLayout.VERTICAL

            setPadding(
                dp(12),
                dp(12),
                dp(12),
                dp(12)
            )

            setBackgroundColor(
                panel
            )

            layoutParams =
                LinearLayout.LayoutParams(
                    -1,
                    -2
                ).apply {
                    bottomMargin = dp(4)
                }
        }
    }

    // =============================================================
    // SECTION TITLE
    // =============================================================

    private fun sectionTitle(
        value: String
    ): TextView {

        return text(
            value,
            15,
            white,
            true,
            Gravity.START,
            2
        )
    }

    // =============================================================
    // TEXT
    // =============================================================

    private fun text(
        value: String,
        size: Int,
        colour: Int,
        bold: Boolean = false,
        gravity: Int = Gravity.START,
        top: Int = 0
    ): TextView {

        return TextView(this).apply {

            text = value

            textSize =
                size.toFloat()

            setTextColor(
                colour
            )

            this.gravity =
                gravity

            if (bold) {

                setTypeface(
                    null,
                    Typeface.BOLD
                )
            }

            setPadding(
                0,
                dp(top),
                0,
                dp(2)
            )
        }
    }

    // =============================================================
    // SPACER
    // =============================================================

    private fun spacer(
        height: Int
    ): View {

        return View(this).apply {

            layoutParams =
                LinearLayout.LayoutParams(
                    1,
                    dp(height)
                )
        }
    }

    // =============================================================
    // SAVE PLATFORM
    // =============================================================

    private fun savePlatform() {

        getSharedPreferences(
            "volatilities_settings",
            MODE_PRIVATE
        ).edit()
            .putString(
                "platform",
                selectedPlatform
            )
            .apply()
    }

    // =============================================================
    // SAVE EXECUTION MODE
    // =============================================================

    private fun saveMode() {

        getSharedPreferences(
            "volatilities_settings",
            MODE_PRIVATE
        ).edit()
            .putString(
                "execution_mode",
                selectedMode
            )
            .apply()
    }

    // =============================================================
    // DP
    // =============================================================

    private fun dp(
        value: Int
    ): Int {

        return (
            value *
                resources.displayMetrics.density
            ).toInt()
    }
}

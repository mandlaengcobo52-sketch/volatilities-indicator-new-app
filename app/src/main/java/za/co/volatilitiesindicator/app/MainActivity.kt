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

    private val bg = Color.rgb(2, 9, 12)
    private val panel = Color.rgb(5, 20, 26)
    private val panel2 = Color.rgb(9, 30, 38)

    private val green = Color.rgb(0, 235, 105)
    private val red = Color.rgb(255, 45, 55)
    private val yellow = Color.rgb(255, 215, 0)
    private val cyan = Color.rgb(0, 210, 255)
    private val white = Color.rgb(240, 245, 247)
    private val muted = Color.rgb(150, 170, 178)

    private var platform = "USER"
    private var executionMode = "DEMO"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
    }

    private fun buildScreen() {

        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(16), dp(14), dp(24))
        }

        scroll.addView(root)

        // ---------------------------------------------------------
        // HEADER
        // ---------------------------------------------------------

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val logo = ImageView(this).apply {
            setImageResource(R.drawable.volatilities_indicator_logo)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }

        header.addView(
            logo,
            LinearLayout.LayoutParams(dp(86), dp(86)).apply {
                marginEnd = dp(12)
            }
        )

        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        titleBox.addView(text("VOLATILITIES", 24, white, true))
        titleBox.addView(text("INDICATOR", 24, green, true))
        titleBox.addView(text("TRADING PLATFORM", 11, muted, true))

        header.addView(
            titleBox,
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        root.addView(header)
        root.addView(spacer(16))

        // ---------------------------------------------------------
        // PLATFORM
        // ---------------------------------------------------------

        root.addView(sectionTitle("PLATFORM"))

        val platformRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val userButton = button("👤  USER", platform == "USER", green)
        val adminButton = button("👥  ADMIN", platform == "ADMIN", green)

        userButton.setOnClickListener {
            platform = "USER"
            userButton.background = buttonBackground(true)
            adminButton.background = buttonBackground(false)

            Toast.makeText(
                this,
                "USER platform selected",
                Toast.LENGTH_SHORT
            ).show()
        }

        adminButton.setOnClickListener {
            platform = "ADMIN"
            userButton.background = buttonBackground(false)
            adminButton.background = buttonBackground(true)

            Toast.makeText(
                this,
                "ADMIN platform selected",
                Toast.LENGTH_SHORT
            ).show()
        }

        platformRow.addView(
            userButton,
            LinearLayout.LayoutParams(0, dp(54), 1f).apply {
                marginEnd = dp(6)
            }
        )

        platformRow.addView(
            adminButton,
            LinearLayout.LayoutParams(0, dp(54), 1f)
        )

        root.addView(platformRow)
        root.addView(spacer(12))

        // ---------------------------------------------------------
        // EXECUTION MODE
        // ---------------------------------------------------------

        root.addView(sectionTitle("EXECUTION MODE"))

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val demoButton = button("📈  DEMO", executionMode == "DEMO", green)
        val realButton = button("▥  REAL", executionMode == "REAL", green)

        demoButton.setOnClickListener {
            executionMode = "DEMO"
            demoButton.background = buttonBackground(true)
            realButton.background = buttonBackground(false)

            Toast.makeText(
                this,
                "DEMO execution selected",
                Toast.LENGTH_SHORT
            ).show()
        }

        realButton.setOnClickListener {
            executionMode = "REAL"
            demoButton.background = buttonBackground(false)
            realButton.background = buttonBackground(true)

            Toast.makeText(
                this,
                "REAL execution selected",
                Toast.LENGTH_SHORT
            ).show()
        }

        modeRow.addView(
            demoButton,
            LinearLayout.LayoutParams(0, dp(54), 1f).apply {
                marginEnd = dp(6)
            }
        )

        modeRow.addView(
            realButton,
            LinearLayout.LayoutParams(0, dp(54), 1f)
        )

        root.addView(modeRow)
        root.addView(spacer(14))

        // ---------------------------------------------------------
        // DERIV ACCOUNT
        // ---------------------------------------------------------

        val accountCard = card()

        accountCard.addView(
            text(
                "⚡  DERIV EXECUTION ACCOUNT",
                15,
                yellow,
                true
            )
        )

        val connection = text(
            "🔴  NOT CONNECTED",
            13,
            red,
            true,
            0,
            5
        )

        accountCard.addView(connection)
        accountCard.addView(text("Account: —", 14, white))
        accountCard.addView(text("Balance: —", 14, white))
        accountCard.addView(text("Open P/L: —", 14, white))
        accountCard.addView(spacer(7))

        val accountButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val connect = button("🔗  CONNECT", true, green)
        val refresh = button("⟳  REFRESH", false, green)

        connect.setOnClickListener {
            connection.text = "🟢  CONNECTION READY"
            connection.setTextColor(green)

            Toast.makeText(
                this,
                "Deriv connection selected",
                Toast.LENGTH_SHORT
            ).show()
        }

        refresh.setOnClickListener {
            Toast.makeText(
                this,
                "Account data refreshed",
                Toast.LENGTH_SHORT
            ).show()
        }

        accountButtons.addView(
            connect,
            LinearLayout.LayoutParams(0, dp(50), 1f).apply {
                marginEnd = dp(6)
            }
        )

        accountButtons.addView(
            refresh,
            LinearLayout.LayoutParams(0, dp(50), 1f)
        )

        accountCard.addView(accountButtons)

        root.addView(accountCard)
        root.addView(spacer(14))

        // ---------------------------------------------------------
        // INDEX MONITOR
        // ---------------------------------------------------------

        root.addView(sectionTitle("INDEX MONITOR"))

        val indexes = listOf(
            "R_10" to cyan,
            "R_25" to green,
            "R_50" to yellow,
            "R_75" to red,
            "R_100" to Color.rgb(175, 70, 255)
        )

        indexes.forEach { (index, colour) ->
            root.addView(indexCard(index, colour))
        }

        root.addView(spacer(14))

        // ---------------------------------------------------------
        // ACCOUNT SUMMARY
        // ---------------------------------------------------------

        root.addView(sectionTitle("ACCOUNT SUMMARY"))

        val summary = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val summaryItems = listOf(
            "OPEN\n0" to cyan,
            "TOTAL P/L\n0.00" to green,
            "WINS\n0" to green,
            "LOSSES\n0" to red
        )

        summaryItems.forEach { (value, colour) ->

            val item = text(
                value,
                11,
                colour,
                true,
                Gravity.CENTER
            )

            item.setBackgroundColor(panel)

            summary.addView(
                item,
                LinearLayout.LayoutParams(0, dp(58), 1f).apply {
                    marginEnd = dp(4)
                }
            )
        }

        root.addView(summary)
        root.addView(spacer(14))

        // ---------------------------------------------------------
        // BOTTOM NAVIGATION
        // ---------------------------------------------------------

        root.addView(
            bottomNavigation()
        )

        setContentView(scroll)
    }

    private fun indexCard(
        index: String,
        colour: Int
    ): View {

        val card = card()

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val name = text(
            index,
            18,
            colour,
            true
        )

        top.addView(
            name,
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        top.addView(
            text(
                "WAITING",
                10,
                yellow,
                true,
                Gravity.CENTER
            )
        )

        card.addView(top)

        card.addView(
            text(
                "────────────  WAITING     ›",
                11,
                muted,
                false,
                Gravity.CENTER_VERTICAL,
                5
            )
        )

        card.setOnClickListener {

            Toast.makeText(
                this,
                "$index selected",
                Toast.LENGTH_SHORT
            ).show()
        }

        return card
    }

    private fun bottomNavigation(): View {

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(panel)
            gravity = Gravity.CENTER
        }

        val items = listOf(
            "⌂\nHome",
            "▥\nTrade",
            "⌁\nAnalysis",
            "▤\nNews",
            "⚙\nSettings"
        )

        items.forEachIndexed { index, label ->

            val item = text(
                label,
                10,
                if (index == 0) green else white,
                true,
                Gravity.CENTER
            )

            item.setOnClickListener {

                Toast.makeText(
                    this,
                    label.substringAfter("\n") + " selected",
                    Toast.LENGTH_SHORT
                ).show()
            }

            nav.addView(
                item,
                LinearLayout.LayoutParams(
                    0,
                    dp(64),
                    1f
                )
            )
        }

        return nav
    }

    private fun button(
        label: String,
        selected: Boolean,
        accent: Int
    ): Button {

        return Button(this).apply {

            text = label
            textSize = 13f

            setTextColor(
                if (selected) Color.BLACK else white
            )

            setTypeface(
                null,
                Typeface.BOLD
            )

            background = buttonBackground(selected)

            isAllCaps = false

            stateListAnimator = null
        }
    }

    private fun buttonBackground(
        selected: Boolean
    ): android.graphics.drawable.GradientDrawable {

        return android.graphics.drawable.GradientDrawable().apply {

            cornerRadius = dp(7).toFloat()

            setColor(
                if (selected) green else panel2
            )

            if (!selected) {
                setStroke(
                    dp(1),
                    Color.rgb(18, 55, 65)
                )
            }
        }
    }

    private fun card(): LinearLayout {

        return LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL

            setPadding(
                dp(12),
                dp(12),
                dp(12),
                dp(12)
            )

            setBackgroundColor(panel)

            layoutParams =
                LinearLayout.LayoutParams(
                    -1,
                    -2
                ).apply {
                    bottomMargin = dp(7)
                }
        }
    }

    private fun sectionTitle(
        value: String
    ): TextView {

        return text(
            value,
            14,
            white,
            true,
            Gravity.START,
            0
        )
    }

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
            textSize = size.toFloat()

            setTextColor(colour)

            this.gravity = gravity

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

    private fun dp(
        value: Int
    ): Int {

        return (
            value *
                resources.displayMetrics.density
            ).toInt()
    }
}

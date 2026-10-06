package za.co.volatilitiesindicator.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val bg = Color.rgb(3, 12, 16)
    private val panel = Color.rgb(6, 21, 27)
    private val panel2 = Color.rgb(8, 28, 35)

    private val green = Color.rgb(0, 230, 118)
    private val red = Color.rgb(255, 65, 80)
    private val yellow = Color.rgb(255, 210, 0)
    private val cyan = Color.rgb(0, 210, 255)

    private val white = Color.rgb(235, 245, 248)
    private val muted = Color.rgb(145, 165, 172)

    private var selectedPlatform = "USER"
    private var selectedMode = "DEMO"

    private lateinit var accountStatus: TextView
    private lateinit var accountNumber: TextView
    private lateinit var balance: TextView
    private lateinit var openPl: TextView

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
            setPadding(dp(14), dp(18), dp(14), dp(30))
        }

        scroll.addView(root)

        // ---------------------------------------------------------
        // BRANDING
        // ---------------------------------------------------------

        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val logo = TextView(this).apply {
            text = "VI"
            textSize = 24f
            setTextColor(bg)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setBackgroundColor(green)
        }

        brandRow.addView(
            logo,
            LinearLayout.LayoutParams(dp(58), dp(58)).apply {
                marginEnd = dp(12)
            }
        )

        val brandText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        brandText.addView(
            text(
                "VOLATILITIES",
                27,
                white,
                true
            )
        )

        brandText.addView(
            text(
                "INDICATOR",
                27,
                white,
                true
            )
        )

        brandText.addView(
            text(
                "TRADING PLATFORM",
                14,
                muted,
                true,
                0,
                2
            )
        )

        brandRow.addView(
            brandText,
            LinearLayout.LayoutParams(
                0,
                -2,
                1f
            )
        )

        root.addView(brandRow)
        root.addView(spacer(18))

        // ---------------------------------------------------------
        // PLATFORM
        // ---------------------------------------------------------

        root.addView(sectionTitle("PLATFORM"))

        root.addView(
            rowButtons(
                "USER",
                "ADMIN",
                green
            )
        )

        root.addView(spacer(10))

        // ---------------------------------------------------------
        // EXECUTION MODE
        // ---------------------------------------------------------

        root.addView(sectionTitle("EXECUTION MODE"))

        root.addView(
            rowButtons(
                "DEMO",
                "REAL",
                cyan
            )
        )

        root.addView(spacer(12))

        // ---------------------------------------------------------
        // DERIV ACCOUNT
        // ---------------------------------------------------------

        val account = card()

        account.addView(
            text(
                "⚡  DERIV EXECUTION ACCOUNT",
                15,
                green,
                true
            )
        )

        accountStatus = text(
            "NOT CONNECTED",
            13,
            yellow,
            true,
            0,
            5
        )

        account.addView(accountStatus)

        accountNumber = text(
            "Account: —",
            14,
            white
        )

        balance = text(
            "Balance: —",
            14,
            white
        )

        openPl = text(
            "Open P/L: —",
            14,
            white
        )

        account.addView(accountNumber)
        account.addView(balance)
        account.addView(openPl)

        account.addView(spacer(7))

        val accountButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val connectButton = button(
            "CONNECT",
            green
        )

        connectButton.setOnClickListener {
            connectDeriv()
        }

        val refreshButton = button(
            "REFRESH",
            green
        )

        refreshButton.setOnClickListener {
            refreshAccount()
        }

        accountButtons.addView(
            connectButton,
            LinearLayout.LayoutParams(
                0,
                dp(48),
                1f
            ).apply {
                marginEnd = dp(5)
            }
        )

        accountButtons.addView(
            refreshButton,
            LinearLayout.LayoutParams(
                0,
                dp(48),
                1f
            )
        )

        account.addView(accountButtons)

        root.addView(account)
        root.addView(spacer(12))

        // ---------------------------------------------------------
        // INDEX MONITOR
        // ---------------------------------------------------------

        root.addView(sectionTitle("INDEX MONITOR"))

        listOf(
            "R_10",
            "R_25",
            "R_50",
            "R_75",
            "R_100"
        ).forEach { index ->

            root.addView(
                indexCard(index)
            )
        }

        root.addView(spacer(12))

        // ---------------------------------------------------------
        // ACCOUNT SUMMARY
        // ---------------------------------------------------------

        root.addView(sectionTitle("ACCOUNT SUMMARY"))

        root.addView(summaryRow())

        root.addView(spacer(14))

        root.addView(
            text(
                "VOLATILITIES INDICATOR • NEW ANDROID PLATFORM",
                11,
                muted,
                false,
                Gravity.CENTER
            )
        )

        setContentView(scroll)
    }

    // -------------------------------------------------------------
    // PLATFORM BUTTONS
    // -------------------------------------------------------------

    private fun platformButton(
        label: String,
        accent: Int
    ): Button {

        return button(
            label,
            accent
        ).apply {

            setOnClickListener {

                selectedPlatform = label

                Toast.makeText(
                    this@MainActivity,
                    "$label platform selected",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun modeButton(
        label: String,
        accent: Int
    ): Button {

        return button(
            label,
            accent
        ).apply {

            setOnClickListener {

                selectedMode = label

                Toast.makeText(
                    this@MainActivity,
                    "Execution mode: $label",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // -------------------------------------------------------------
    // BUTTON ROW
    // -------------------------------------------------------------

    private fun rowButtons(
        a: String,
        b: String,
        accent: Int
    ): View {

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val first =
            if (a == "USER" || a == "ADMIN") {
                platformButton(a, accent)
            } else if (a == "DEMO" || a == "REAL") {
                modeButton(a, accent)
            } else {
                button(a, accent)
            }

        val second =
            if (b == "USER" || b == "ADMIN") {
                platformButton(b, accent)
            } else if (b == "DEMO" || b == "REAL") {
                modeButton(b, accent)
            } else {
                button(b, accent)
            }

        row.addView(
            first,
            LinearLayout.LayoutParams(
                0,
                dp(48),
                1f
            ).apply {
                marginEnd = dp(5)
            }
        )

        row.addView(
            second,
            LinearLayout.LayoutParams(
                0,
                dp(48),
                1f
            )
        )

        return row
    }

    // -------------------------------------------------------------
    // DERIV CONNECTION
    // -------------------------------------------------------------

    private fun connectDeriv() {

        AlertDialog.Builder(this)
            .setTitle("Deriv Execution Account")
            .setMessage(
                "The Deriv account connection module will be used here.\n\n" +
                "Selected platform: $selectedPlatform\n" +
                "Execution mode: $selectedMode"
            )
            .setPositiveButton("CONTINUE") { dialog, _ ->

                accountStatus.text = "CONNECTION READY"
                accountStatus.setTextColor(green)

                Toast.makeText(
                    this,
                    "Deriv connection selected",
                    Toast.LENGTH_SHORT
                ).show()

                dialog.dismiss()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    // -------------------------------------------------------------
    // REFRESH
    // -------------------------------------------------------------

    private fun refreshAccount() {

        accountStatus.text = "REFRESHED"
        accountStatus.setTextColor(green)

        Toast.makeText(
            this,
            "Account data refreshed",
            Toast.LENGTH_SHORT
        ).show()
    }

    // -------------------------------------------------------------
    // INDEX CARD
    // -------------------------------------------------------------

    private fun indexCard(
        index: String
    ): View {

        val c = card()

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val name = text(
            index,
            20,
            cyan,
            true
        )

        top.addView(
            name,
            LinearLayout.LayoutParams(
                0,
                -2,
                1f
            )
        )

        top.addView(
            badge(
                "WAITING",
                yellow
            )
        )

        c.addView(top)

        c.addView(
            text(
                "STATUS     WAITING",
                12,
                yellow,
                true,
                0,
                7
            )
        )

        c.addView(
            text(
                "SMC          WAITING",
                12,
                muted
            )
        )

        c.addView(
            text(
                "HPA          WAITING",
                12,
                muted
            )
        )

        c.addView(
            text(
                "MASTER       WAITING",
                12,
                muted
            )
        )

        c.addView(
            text(
                "PTPE         WAITING",
                12,
                muted
            )
        )

        c.addView(
            text(
                "DIRECTION    —",
                12,
                white
            )
        )

        c.addView(
            text(
                "TIMEFRAME    3 DAYS",
                12,
                muted
            )
        )

        c.addView(spacer(7))

        c.addView(
            phaseRow()
        )

        return c
    }

    // -------------------------------------------------------------
    // PHASE
    // -------------------------------------------------------------

    private fun phaseRow(): View {

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val phases = listOf(
            "1\nBUILDING UP",
            "2\nSETUP",
            "3\nNOW",
            "4\nACTIVE",
            "5\nEXPIRED",
            "6\nEXIT TRADE"
        )

        phases.forEachIndexed { i, phase ->

            val color =
                when (i) {
                    1 -> yellow
                    3 -> green
                    else -> muted
                }

            val t = text(
                phase,
                9,
                color,
                true,
                Gravity.CENTER
            )

            row.addView(
                t,
                LinearLayout.LayoutParams(
                    0,
                    dp(42),
                    1f
                )
            )
        }

        return row
    }

    // -------------------------------------------------------------
    // SUMMARY
    // -------------------------------------------------------------

    private fun summaryRow(): View {

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        listOf(
            "OPEN\n0",
            "TOTAL P/L\n0.00",
            "WINS\n0",
            "LOSSES\n0"
        ).forEachIndexed { i, value ->

            val color =
                when (i) {
                    1, 2 -> green
                    3 -> red
                    else -> cyan
                }

            val t = text(
                value,
                12,
                color,
                true,
                Gravity.CENTER
            )

            t.setBackgroundColor(panel)

            row.addView(
                t,
                LinearLayout.LayoutParams(
                    0,
                    dp(60),
                    1f
                ).apply {
                    marginEnd = dp(5)
                }
            )
        }

        return row
    }

    // -------------------------------------------------------------
    // BASIC BUTTON
    // -------------------------------------------------------------

    private fun button(
        label: String,
        accent: Int
    ): Button {

        return Button(this).apply {

            text = label

            textSize = 13f

            setTextColor(white)

            setTypeface(
                null,
                Typeface.BOLD
            )

            setBackgroundColor(panel2)

            isAllCaps = false

            stateListAnimator = null

            setOnClickListener {
                // Default action intentionally empty.
            }
        }
    }

    // -------------------------------------------------------------
    // BADGE
    // -------------------------------------------------------------

    private fun badge(
        label: String,
        color: Int
    ): TextView {

        return text(
            "  $label  ",
            10,
            color,
            true,
            Gravity.CENTER
        ).apply {
            setBackgroundColor(panel2)
        }
    }

    // -------------------------------------------------------------
    // CARD
    // -------------------------------------------------------------

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
                    bottomMargin = dp(8)
                }
        }
    }

    // -------------------------------------------------------------
    // SECTION TITLE
    // -------------------------------------------------------------

    private fun sectionTitle(
        value: String
    ): TextView {

        return text(
            value,
            14,
            white,
            true,
            0,
            4
        )
    }

    // -------------------------------------------------------------
    // TEXT
    // -------------------------------------------------------------

    private fun text(
        value: String,
        size: Int,
        color: Int,
        bold: Boolean = false,
        gravity: Int = Gravity.START,
        top: Int = 0
    ): TextView {

        return TextView(this).apply {

            text = value

            textSize = size.toFloat()

            setTextColor(color)

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

    // -------------------------------------------------------------
    // SPACER
    // -------------------------------------------------------------

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

    // -------------------------------------------------------------
    // DP
    // -------------------------------------------------------------

    private fun dp(
        value: Int
    ): Int {

        return (
            value *
                resources.displayMetrics.density
            ).toInt()
    }
}

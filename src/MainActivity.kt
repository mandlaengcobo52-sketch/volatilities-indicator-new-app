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
    private lateinit var accountId: TextView
    private lateinit var accountBalance: TextView
    private lateinit var accountPL: TextView

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
            setPadding(dp(14), dp(18), dp(14), dp(28))
        }

        scroll.addView(root)

        root.addView(
            text(
                "VOLATILITIES INDICATOR",
                27,
                white,
                true
            )
        )

        root.addView(
            text(
                "TRADING PLATFORM",
                14,
                muted,
                true,
                0,
                2
            )
        )

        root.addView(spacer(10))

        root.addView(sectionTitle("PLATFORM"))

        root.addView(
            rowButtons(
                "USER",
                "ADMIN",
                green,
                {
                    selectPlatform("USER")
                },
                {
                    selectPlatform("ADMIN")
                }
            )
        )

        root.addView(spacer(8))

        root.addView(sectionTitle("EXECUTION MODE"))

        root.addView(
            rowButtons(
                "DEMO",
                "REAL",
                cyan,
                {
                    selectMode("DEMO")
                },
                {
                    selectMode("REAL")
                }
            )
        )

        root.addView(spacer(12))

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

        accountId = text(
            "Account: —",
            14,
            white
        )
        account.addView(accountId)

        accountBalance = text(
            "Balance: —",
            14,
            white
        )
        account.addView(accountBalance)

        accountPL = text(
            "Open P/L: —",
            14,
            white
        )
        account.addView(accountPL)

        account.addView(spacer(6))

        account.addView(
            rowButtons(
                "CONNECT",
                "REFRESH",
                green,
                {
                    connectDeriv()
                },
                {
                    refreshDeriv()
                }
            )
        )

        root.addView(account)

        root.addView(spacer(12))

        root.addView(sectionTitle("INDEX MONITOR"))

        listOf(
            "R_10",
            "R_25",
            "R_50",
            "R_75",
            "R_100"
        ).forEach {
            root.addView(indexCard(it))
        }

        root.addView(spacer(12))

        root.addView(sectionTitle("ACCOUNT SUMMARY"))

        root.addView(summaryRow())

        root.addView(spacer(12))

        root.addView(
            text(
                "Batch 1 • UI foundation • Interactive controls enabled",
                12,
                muted,
                false,
                Gravity.CENTER
            )
        )

        setContentView(scroll)
    }

    private fun selectPlatform(platform: String) {

        selectedPlatform = platform

        Toast.makeText(
            this,
            "$platform platform selected",
            Toast.LENGTH_SHORT
        ).show()

        updateAccountDisplay()
    }

    private fun selectMode(mode: String) {

        selectedMode = mode

        Toast.makeText(
            this,
            "$mode execution mode selected",
            Toast.LENGTH_SHORT
        ).show()

        updateAccountDisplay()
    }

    private fun updateAccountDisplay() {

        accountStatus.text = "NOT CONNECTED"
        accountStatus.setTextColor(yellow)

        accountId.text = "Account: —"
        accountBalance.text = "Balance: —"
        accountPL.text = "Open P/L: —"
    }

    private fun connectDeriv() {

        AlertDialog.Builder(this)
            .setTitle("DERIV CONNECTION")
            .setMessage(
                "Platform: $selectedPlatform\n" +
                "Execution mode: $selectedMode\n\n" +
                "The Deriv account connection is not authenticated yet.\n\n" +
                "The next connection module will use the selected Demo or Real Deriv account."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun refreshDeriv() {

        Toast.makeText(
            this,
            "Refreshing $selectedMode Deriv account...",
            Toast.LENGTH_SHORT
        ).show()

        accountStatus.text = "WAITING FOR CONNECTION"
        accountStatus.setTextColor(yellow)
    }

    private fun indexCard(index: String): View {

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

        c.addView(spacer(6))

        c.addView(phaseRow())

        return c
    }

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

        phases.forEachIndexed { i, p ->

            val color =
                when (i) {
                    1 -> yellow
                    3 -> green
                    else -> muted
                }

            val t = text(
                p,
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
   

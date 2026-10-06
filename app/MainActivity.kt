package za.co.volatilitiesindicator.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

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

        root.addView(text("VOLATILITIES INDICATOR", 27, white, true))
        root.addView(text("TRADING PLATFORM", 14, muted, true, 0, 2))
        root.addView(spacer(10))

        root.addView(sectionTitle("PLATFORM"))
        root.addView(rowButtons("USER", "ADMIN", green))
        root.addView(spacer(8))
        root.addView(sectionTitle("EXECUTION MODE"))
        root.addView(rowButtons("DEMO", "REAL", cyan))
        root.addView(spacer(12))

        val account = card()
        account.addView(text("⚡  DERIV EXECUTION ACCOUNT", 15, green, true))
        account.addView(text("NOT CONNECTED", 13, yellow, true, 0, 5))
        account.addView(text("Account: —", 14, white))
        account.addView(text("Balance: —", 14, white))
        account.addView(text("Open P/L: —", 14, white))
        account.addView(spacer(6))
        account.addView(rowButtons("CONNECT", "REFRESH", green))
        root.addView(account)
        root.addView(spacer(12))

        root.addView(sectionTitle("INDEX MONITOR"))
        listOf("R_10", "R_25", "R_50", "R_75", "R_100").forEach { root.addView(indexCard(it)) }

        root.addView(spacer(12))
        root.addView(sectionTitle("ACCOUNT SUMMARY"))
        root.addView(summaryRow())
        root.addView(spacer(12))
        root.addView(text("Batch 1 • UI foundation", 12, muted, false, Gravity.CENTER))

        setContentView(scroll)
    }

    private fun indexCard(index: String): View {
        val c = card()
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val name = text(index, 20, cyan, true)
        top.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(badge("WAITING", yellow))
        c.addView(top)
        c.addView(text("STATUS     WAITING", 12, yellow, true, 0, 7))
        c.addView(text("SMC          WAITING", 12, muted))
        c.addView(text("HPA          WAITING", 12, muted))
        c.addView(text("MASTER       WAITING", 12, muted))
        c.addView(text("PTPE         WAITING", 12, muted))
        c.addView(text("DIRECTION    —", 12, white))
        c.addView(text("TIMEFRAME    3 DAYS", 12, muted))
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
            val color = if (i == 1) yellow
            else if (i == 3) green
            else muted
            val t = text(p, 9, color, true, Gravity.CENTER)
            row.addView(t, LinearLayout.LayoutParams(0, dp(42), 1f))
        }
        return row
    }

    private fun summaryRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        listOf(
            "OPEN\n0",
            "TOTAL P/L\n0.00",
            "WINS\n0",
            "LOSSES\n0"
        ).forEachIndexed { i, s ->
            val color = when (i) {
                1, 2 -> green
                3 -> red
                else -> cyan
            }
            val t = text(s, 12, color, true, Gravity.CENTER)
            t.setBackgroundColor(panel)
            row.addView(
                t,
                LinearLayout.LayoutParams(0, dp(60), 1f).apply {
                    marginEnd = dp(5)
                }
            )
        }
        return row
    }

    private fun rowButtons(a: String, b: String, accent: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        row.addView(
            button(a, accent),
            LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginEnd = dp(5)
            }
        )
        row.addView(
            button(b, accent),
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )
        return row
    }

    private fun button(label: String, accent: Int): Button = Button(this).apply {
        text = label
        textSize = 13f
        setTextColor(white)
        setTypeface(null, Typeface.BOLD)
        setBackgroundColor(panel2)
        setOnClickListener { }
    }

    private fun badge(label: String, color: Int): TextView =
        text("  $label  ", 10, color, true, Gravity.CENTER).apply {
            setBackgroundColor(panel2)
        }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setBackgroundColor(panel)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(8)
        }
    }

    private fun sectionTitle(s: String) =
        text(s, 14, white, true, 0, 4)

    private fun text(
        s: String,
        size: Int,
        color: Int,
        bold: Boolean = false,
        gravity: Int = Gravity.START,
        top: Int = 0
    ): TextView = TextView(this).apply {
        text = s
        textSize = size.toFloat()
        setTextColor(color)
        this.gravity = gravity
        if (bold) setTypeface(null, Typeface.BOLD)
        setPadding(0, dp(top), 0, dp(2))
    }

    private fun spacer(h: Int) =
        View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, dp(h))
        }

    private fun dp(v: Int) =
        (v * resources.displayMetrics.density).toInt()
}

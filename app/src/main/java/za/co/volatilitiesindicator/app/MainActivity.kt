package za.co.volatilitiesindicator.app

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * VOLATILITIES INDICATOR
 * Fresh light-interface shell.
 *
 * This file intentionally contains presentation/navigation only.
 * Live Deriv values and account/trade actions must be connected to the
 * existing Deriv service after the UI shell is accepted and verified.
 */
class MainActivity : Activity() {

    private val ink = Color.rgb(28, 39, 51)
    private val muted = Color.rgb(112, 125, 140)
    private val green = Color.rgb(20, 150, 92)
    private val red = Color.rgb(205, 70, 70)
    private val amber = Color.rgb(207, 145, 35)
    private val pageColor = Color.rgb(245, 247, 250)
    private val cardColor = Color.WHITE
    private val lineColor = Color.rgb(226, 231, 237)

    private lateinit var content: LinearLayout
    private var selectedTab = "Home"

    private val indexes = listOf("R_10", "R_25", "R_50", "R_75", "R_100")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = pageColor
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        render()
    }

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pageColor)
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(22))
        }

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(bottomNavigation())
        setContentView(root)

        when (selectedTab) {
            "Home" -> showHome()
            "Trade" -> showTrade()
            "Accounts" -> showAccounts()
            "Settings" -> showSettings()
        }
    }

    private fun showHome() {
        content.addView(text("VOLATILITIES INDICATOR", 21f, ink, true))
        content.addView(text("Market monitor", 13f, muted))
        content.addView(space(18))

        val status = horizontal()
        status.addView(text("DERIV CONNECTION", 12f, muted, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        status.addView(pill("DISCONNECTED", red))
        content.addView(status)
        content.addView(space(12))

        val account = card()
        account.addView(text("TRADING ACCOUNT", 12f, muted, true))
        account.addView(space(8))
        account.addView(text("Not connected", 17f, ink, true))
        account.addView(text("Connect a Deriv account to load balance and account details.", 13f, muted))
        account.addView(space(12))
        account.addView(actionButton("ACCOUNT OPTIONS") { openTab("Accounts") })
        content.addView(account)
        content.addView(space(18))

        content.addView(text("INDEX MONITOR", 16f, ink, true))
        content.addView(text("Live prices will appear when market data is connected.", 12f, muted))
        content.addView(space(10))

        indexes.forEach { symbol ->
            val indexCard = card()
            val row = horizontal()
            row.addView(text(symbol, 15f, ink, true),
                LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(pill("WAITING", amber))
            indexCard.addView(row)
            indexCard.addView(space(10))
            val priceRow = horizontal()
            priceRow.addView(text("PRICE", 11f, muted),
                LinearLayout.LayoutParams(0, -2, 1f))
            priceRow.addView(text("--", 16f, ink, true))
            indexCard.addView(priceRow)
            indexCard.addView(space(8))
            indexCard.addView(divider())
            indexCard.addView(space(8))
            indexCard.addView(text("MASTER DECISION", 11f, muted, true))
            indexCard.addView(text("WAITING FOR MARKET DATA", 12f, amber, true))
            content.addView(indexCard)
            content.addView(space(9))
        }
    }

    private fun showTrade() {
        pageHeading("RUNNING TRADES", "Open positions and their current status.")
        val c = card()
        c.addView(text("NO TRADE DATA", 15f, ink, true))
        c.addView(space(6))
        c.addView(text("Running trades will be displayed here after the Deriv account connection is integrated.", 13f, muted))
        content.addView(c)
    }

    private fun showAccounts() {
        pageHeading("ACCOUNTS", "Account access and connection settings.")
        val deriv = card()
        deriv.addView(text("DERIV ACCOUNT", 15f, ink, true))
        deriv.addView(space(6))
        deriv.addView(text("Connection: Disconnected", 13f, muted))
        deriv.addView(space(12))
        deriv.addView(actionButton("CONNECT DERIV") {
            toast("Deriv connection is not wired into this fresh UI shell yet.")
        })
        content.addView(deriv)
        content.addView(space(12))

        val users = card()
        users.addView(text("USER ACCOUNT", 15f, ink, true))
        users.addView(text("Sign-in and registration interface will be added here.", 13f, muted))
        users.addView(space(10))
        users.addView(actionButton("USER ACCOUNT", false) {
            toast("User account screen is not connected yet.")
        })
        content.addView(users)
        content.addView(space(12))

        val admin = card()
        admin.addView(text("ADMIN ACCOUNT", 15f, ink, true))
        admin.addView(text("Administrator access and user management.", 13f, muted))
        admin.addView(space(10))
        admin.addView(actionButton("ADMIN ACCOUNT", false) {
            toast("Admin account screen is not connected yet.")
        })
        content.addView(admin)
    }

    private fun showSettings() {
        pageHeading("SETTINGS", "Application preferences.")
        val c = card()
        c.addView(text("APPLICATION", 15f, ink, true))
        c.addView(space(8))
        c.addView(settingRow("Interface", "Light"))
        c.addView(divider())
        c.addView(settingRow("Market indexes", "5 synthetic indices"))
        c.addView(divider())
        c.addView(settingRow("Notifications", "Not configured"))
        c.addView(divider())
        c.addView(settingRow("Deriv connection", "Disconnected"))
        content.addView(c)
    }

    private fun pageHeading(title: String, subtitle: String) {
        content.addView(text(title, 21f, ink, true))
        content.addView(text(subtitle, 13f, muted))
        content.addView(space(18))
    }

    private fun settingRow(label: String, value: String): View {
        val row = horizontal()
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(12), 0, dp(12))
        row.addView(text(label, 14f, ink, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(text(value, 13f, muted))
        return row
    }

    private fun bottomNavigation(): View {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.WHITE)
            setPadding(dp(6), dp(8), dp(6), dp(8))
            elevation = dp(8).toFloat()
        }
        listOf("Home", "Trade", "Accounts", "Settings").forEach { tab ->
            val selected = selectedTab == tab
            val item = TextView(this).apply {
                text = tab
                textSize = 12f
                gravity = Gravity.CENTER
                setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (selected) green else muted)
                setPadding(dp(4), dp(8), dp(4), dp(8))
                setOnClickListener { openTab(tab) }
            }
            nav.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
        }
        return nav
    }

    private fun openTab(tab: String) {
        selectedTab = tab
        render()
    }

    private fun card(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(cardColor, lineColor, 14)
            elevation = dp(1).toFloat()
        }
    }

    private fun actionButton(label: String, primary: Boolean = true, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            setTextColor(if (primary) Color.WHITE else green)
            background = rounded(if (primary) green else Color.rgb(235, 247, 241),
                if (primary) green else Color.rgb(210, 235, 222), 10)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { action() }
        }
    }

    private fun pill(label: String, color: Int): TextView {
        return TextView(this).apply {
            text = label
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setTextColor(color)
            setPadding(dp(9), dp(5), dp(9), dp(5))
            background = rounded(Color.WHITE, color, 20)
        }
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
        }
    }

    private fun horizontal() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(lineColor)
        layoutParams = LinearLayout.LayoutParams(-1, dp(1))
    }

    private fun space(height: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun rounded(fill: Int, stroke: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fill)
            setCornerRadius(dp(radius).toFloat())
            setStroke(dp(1), stroke)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }
}

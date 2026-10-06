package za.co.volatilitiesindicator.app

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private fun text(
        value: String,
        size: Float,
        bold: Boolean = false
    ): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(Color.BLACK)
            if (bold) {
                setTypeface(null, Typeface.BOLD)
            }
            setPadding(16, 16, 16, 16)
        }
    }

    private fun button(value: String): Button {
        return Button(this).apply {
            text = value
            textSize = 16f
            isAllCaps = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 32, 24, 24)
            setBackgroundColor(Color.WHITE)
        }

        val title = text(
            "VOLATILITIES INDICATOR",
            26f,
            true
        )

        val subtitle = text(
            "TRADING PLATFORM",
            16f,
            true
        )

        val status = text(
            "SYSTEM STATUS\n\nApplication: ONLINE\nTrading Engine: INITIALISING\nDeriv Connection: NOT CONNECTED",
            16f
        )

        val userButton = button("USER")
        val adminButton = button("ADMIN")
        val demoButton = button("DEMO")
        val realButton = button("REAL")

        val account = text(
            "EXECUTION ACCOUNT\n\nMode: DEMO\nAccount: NOT CONNECTED\nBalance: --\nOpen P/L: --",
            16f,
            true
        )

        val indexes = text(
            "INDEX MONITOR\n\nR_10    WAITING\nR_25    WAITING\nR_50    WAITING\nR_75    WAITING\nR_100   WAITING",
            16f
        )

        root.addView(title)
        root.addView(subtitle)

        root.addView(
            text("SELECT PLATFORM", 18f, true)
        )

        root.addView(userButton)
        root.addView(adminButton)

        root.addView(
            text("EXECUTION MODE", 18f, true)
        )

        root.addView(demoButton)
        root.addView(realButton)

        root.addView(account)
        root.addView(status)
        root.addView(indexes)

        userButton.setOnClickListener {
            status.text = "USER MODE\n\nTrading access: ENABLED\nAdmin controls: HIDDEN"
        }

        adminButton.setOnClickListener {
            status.text = "ADMIN MODE\n\nAdministration access: ENABLED\nTrading engine controls: AVAILABLE"
        }

        demoButton.setOnClickListener {
            account.text =
                "EXECUTION ACCOUNT\n\nMode: DEMO\nAccount: NOT CONNECTED\nBalance: --\nOpen P/L: --"
        }

        realButton.setOnClickListener {
            account.text =
                "EXECUTION ACCOUNT\n\nMode: REAL\nAccount: NOT CONNECTED\nBalance: --\nOpen P/L: --"
        }

        setContentView(root)
    }
}

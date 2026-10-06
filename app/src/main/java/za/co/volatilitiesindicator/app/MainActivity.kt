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

    private val bg = Color.rgb(2, 11, 15)
    private val panel = Color.rgb(5, 22, 29)
    private val panel2 = Color.rgb(8, 31, 39)
    private val green = Color.rgb(0, 230, 118)
    private val red = Color.rgb(255, 55, 70)
    private val yellow = Color.rgb(255, 215, 0)
    private val cyan = Color.rgb(0, 210, 255)
    private val white = Color.rgb(240, 247, 249)
    private val muted = Color.rgb(150, 170, 177)

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
            setPadding(dp(14), dp(16), dp(14), dp(28))
        }

        scroll.addView(root)

        // LOGO + TITLE
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
            LinearLayout.LayoutParams(dp(82), dp(82)).apply {
                marginEnd = dp(12)
            }
        )

        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        titleBox.addView(text("VOLATILITIES", 27, white, true))
        titleBox.addView(text("INDICATOR", 27, green, true))
        titleBox.addView(text("TRADING PLATFORM", 13, muted, true, 0, 3))

        header.addView(
            titleBox,
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        root.addView(header)
        root.addView(spacer(18))

        // PLATFORM
        root.addView(sectionTitle("PLATFORM"))
        root.addView(
            rowButtons(
                "👤  USER",
                "👥  ADMIN",
                green
            )
        )

        root.addView(spacer(10))

        // EXECUTION MODE
        root.addView(sectionTitle("EXECUTION MODE"))
        root.addView(
            rowButtons(
                "📈  DEMO",
                "▥  REAL",
                green
            )
        )

        root.addView(spacer(12))

        // DERIV ACCOUNT
        val account = card()

        account.addView(
            text(
                "⚡  DERIV EXECUTION ACCOUNT",
                15,
                yellow,
                true
            )
        )

        account.addView(
            text(
                "🟢  CONNECTION READY",
                13,
                green,
                true,
                0,
                5
            )
        )

        account.addView(text("Account: —", 14, white))
        account.addView(text("Balance: —", 14, white))
        account.addView(text("Open P/L: —", 14, white))
        account.addView(spacer(6))

        account.addView(
            rowButtons(
                "🔗  CONNECT",
                "⟳  REFRESH",
                green
            )
        )

        root.addView(account)
        root.addView(spacer(14))

        // INDEX MONITOR
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

        // SUMMARY
        root.addView(sectionTitle("ACCOUNT SUMMARY"))
        root.addView(summaryRow())

        root.addView(spacer(14))

        root.addView(
            text(
                "VOLATILITIES INDICATOR • VERSION 1.0.0",
                11,
                muted,
                false,
                Gravity.CENTER
            )
        )

        setContentView(scroll)
    }

    private fun indexCard(index: String): View {

        val c = card()

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val indexColor = when (index) {
            "R_10" -> cyan
            "R_25" -> green
            "R_50" -> yellow
            "R_75" -> red
            else -> Color.rgb(175, 70, 255)
        }

        val name = text(index, 20, indexColor, true)

        top.addView(
            name,
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        top.addView(
            badge("WAITING", yellow)
        )

        c.addView(top)

        c.addView(
            text(
                "━━━━━━━━━━  WAITING   ›",
                12,
                muted,
                false,
                0,
                5
            )
        )

        return c
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

            val t = text(
                s,
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

    private fun rowButtons(
        a: String,
        b: String,
        accent: Int
    ): View {

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        row.addView(
            button(a, accent),
            LinearLayout.LayoutParams(
                0,
                dp(58),
                1f
            ).apply {
                marginEnd = dp(6)
            }
        )

        row.addView(
            button(b, accent),
            LinearLayout.LayoutParams(
                0,
                dp(58),
                1f
            )
        )

        return row
    }

    private fun button(
        label: String,
        accent: Int
    ): Button {

        return Button(this).apply {

            text = label
            textSize = 14f

            setTypeface(
                null,
                Typeface.BOLD
            )

            setTextColor(white)
            setBackgroundColor(panel2)

            isAllCaps = false

            setOnClickListener {

                Toast.makeText(
                    this@MainActivity,
                    "$label selected",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

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

    private fun sectionTitle(
        s: String
    ): TextView {

        return text(
            s,
            15,
            white,
            true,
            0,
            4
        )
    }

    private fun text(
        s: String,
        size: Int,
        color: Int,
        bold: Boolean = false,
        gravity: Int = Gravity.START,
        top: Int = 0
    ): TextView {

        return TextView(this).apply {

            text = s
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

    private fun spacer(
        h: Int
    ): View {

        return View(this).apply {
            layoutParams =
                LinearLayout.LayoutParams(
                    1,
                    dp(h)
                )
        }
    }

    private fun dp(
        v: Int
    ): Int {

        return (
            v *
                resources.displayMetrics.density
            ).toInt()
    }
}

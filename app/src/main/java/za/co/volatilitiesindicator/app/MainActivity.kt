
import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private val navy = Color.rgb(25, 48, 75)
    private val green = Color.rgb(20, 145, 95)
    private val backgroundColor = Color.rgb(245, 248, 250)
    private val muted = Color.rgb(115, 130, 145)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(backgroundColor)
        }

        // HEADER
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22.dp(), 22.dp(), 22.dp(), 20.dp())
            setBackgroundColor(Color.WHITE)
        }

        header.addView(label(
            "VOLATILITIES",
            23f, navy, true
        ))

        header.addView(label(
            "INDICATOR",
            17f, green, true
        ))

        header.addView(label(
            "TRADING PLATFORM",
            10f, muted, true
        ).apply {
            letterSpacing = 0.15f
            setPadding(0, 5.dp(), 0, 0)
        })

        root.addView(header)

        // MAIN DISPLAY
        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18.dp(), 22.dp(), 18.dp(), 18.dp())
            layoutParams = LinearLayout.LayoutParams(
                -1, 0, 1f
            )
        }

        val welcome = TextView(this).apply {
            text = "Welcome"
            textSize = 22f
            setTextColor(navy)
            typeface = Typeface.DEFAULT_BOLD
        }

        main.addView(welcome)

        main.addView(label(
            "Your trading workspace",
            13f, muted, false
        ).apply {
            setPadding(0, 5.dp(), 0, 22.dp())
        })

        val display = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(Color.WHITE, 16)
            setPadding(16.dp(), 16.dp(), 16.dp(), 16.dp())
            layoutParams = LinearLayout.LayoutParams(
                -1, 0, 1f
            )
        }

        display.addView(label(
            "MAIN DISPLAY",
            13f, navy, true
        ))

        display.addView(label(
            "Workspace ready",
            12f, muted, false
        ).apply {
            setPadding(0, 8.dp(), 0, 0)
        })

        main.addView(display)
        root.addView(main)

        // BOTTOM NAVIGATION SHELL
        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.WHITE)
            setPadding(8.dp(), 14.dp(), 8.dp(), 14.dp())
        }

        listOf("HOME", "CONNECTION", "EXECUTION").forEachIndexed { index, item ->
            val itemView = TextView(this).apply {
                text = item
                textSize = 11f
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (index == 0) green else muted)
                layoutParams = LinearLayout.LayoutParams(
                    0, -2, 1f
                )
                setPadding(3.dp(), 8.dp(), 3.dp(), 8.dp())
            }
            navigation.addView(itemView)
        }

        root.addView(navigation)

        setContentView(root)
    }

    private fun label(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean
    ): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.dp().toFloat()
        }
    }

    private fun Int.dp(): Int =
        (this * resources.displayMetrics.density).toInt()
}


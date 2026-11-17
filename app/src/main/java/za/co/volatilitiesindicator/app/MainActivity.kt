package za.co.volatilitiesindicator.app

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

    // New light interface palette
    private val pageBackground = Color.rgb(246, 248, 252)
    private val surface = Color.WHITE
    private val primary = Color.rgb(31, 48, 75)
    private val secondary = Color.rgb(103, 119, 140)
    private val accent = Color.rgb(0, 145, 150)
    private val divider = Color.rgb(230, 235, 242)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = pageBackground
        window.navigationBarColor = surface
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pageBackground)
            fitsSystemWindows = true
        }

        // APP HEADER
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22.dp(), 20.dp(), 22.dp(), 18.dp())
            setBackgroundColor(surface)
        }

        header.addView(label("VOLATILITIES", 22f, primary, true))
        header.addView(label("INDICATOR", 15f, accent, true).apply {
            letterSpacing = 0.08f
            setPadding(0, 2.dp(), 0, 0)
        })

        root.addView(header)

        // MAIN CONTENT AREA — intentionally kept empty for the new design.
        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 24.dp(), 20.dp(), 20.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val heading = label("HOME", 12f, secondary, true).apply {
            letterSpacing = 0.12f
            setPadding(2.dp(), 0, 0, 14.dp())
        }
        main.addView(heading)

        val workspace = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(surface, 20)
            setPadding(18.dp(), 18.dp(), 18.dp(), 18.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        main.addView(workspace)
        root.addView(main)

        // BOTTOM NAVIGATION SHELL
        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(surface)
            setPadding(8.dp(), 12.dp(), 8.dp(), 12.dp())
        }

        listOf("HOME", "CONNECTION", "EXECUTION").forEachIndexed { index, item ->
            val itemView = TextView(this).apply {
                text = item
                textSize = 10f
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.04f
                setTextColor(if (index == 0) accent else secondary)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                setPadding(3.dp(), 10.dp(), 3.dp(), 10.dp())
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

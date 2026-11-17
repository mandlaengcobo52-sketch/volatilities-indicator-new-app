package za.co.volatilitiesindicator.app

import android.app.Activity
import android.os.Bundle
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private val pageBackground = Color.rgb(246, 248, 252)
    private val surface = Color.WHITE
    private val primary = Color.rgb(31, 48, 75)
    private val secondary = Color.rgb(103, 119, 140)
    private val accent = Color.rgb(0, 145, 150)
    private val indexBackground = Color.rgb(248, 250, 253)

    private lateinit var pageHeading: TextView
    private lateinit var workspace: LinearLayout
    private val navigationItems = mutableListOf<TextView>()
    private var selectedPage = "HOME"

    private val indexes = listOf("R_10", "R_25", "R_50", "R_75", "R_100")

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

        // HEADER WITH BRAND MARK
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(22.dp(), 18.dp(), 22.dp(), 18.dp())
            setBackgroundColor(surface)
        }

        header.addView(BrandMark(this), LinearLayout.LayoutParams(54.dp(), 54.dp()))

        val brandText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp(), 0, 0, 0)
        }
        brandText.addView(label("VOLATILITIES", 21f, primary, true))
        brandText.addView(label("INDICATOR", 15f, accent, true).apply {
            letterSpacing = 0.08f
            setPadding(0, 2.dp(), 0, 0)
        })
        header.addView(brandText)
        root.addView(header)

        // MAIN WORKSPACE
        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 24.dp(), 20.dp(), 20.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        pageHeading = label("HOME", 12f, secondary, true).apply {
            letterSpacing = 0.12f
            setPadding(2.dp(), 0, 0, 14.dp())
        }
        main.addView(pageHeading)

        workspace = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(surface, 20)
            setPadding(16.dp(), 18.dp(), 16.dp(), 18.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        main.addView(workspace)
        root.addView(main)

        // CLICKABLE BOTTOM NAVIGATION
        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(surface)
            setPadding(8.dp(), 12.dp(), 8.dp(), 12.dp())
        }

        listOf("HOME", "CONNECTION", "EXECUTION").forEach { page ->
            val itemView = TextView(this).apply {
                text = page
                textSize = 10f
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.04f
                isClickable = true
                isFocusable = true
                setPadding(3.dp(), 12.dp(), 3.dp(), 12.dp())
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                setOnClickListener { showPage(page) }
            }
            navigationItems.add(itemView)
            navigation.addView(itemView)
        }

        root.addView(navigation)
        showPage(selectedPage)
        setContentView(root)
    }

    private fun showPage(page: String) {
        selectedPage = page
        pageHeading.text = page
        navigationItems.forEach { item ->
            val active = item.text.toString() == page
            item.setTextColor(if (active) accent else secondary)
            item.alpha = if (active) 1f else 0.82f
        }

        workspace.removeAllViews()
        if (page == "HOME") {
            showIndexes()
        }
    }

    private fun showIndexes() {
        workspace.addView(label("INDEX MONITOR", 12f, primary, true).apply {
            letterSpacing = 0.08f
            setPadding(2.dp(), 0, 0, 12.dp())
        })

        indexes.forEach { index ->
            val indexCard = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(indexBackground, 12)
                setPadding(14.dp(), 13.dp(), 14.dp(), 13.dp())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                ).apply {
                    bottomMargin = 8.dp()
                }
            }

            val name = label(index, 15f, primary, true)
            indexCard.addView(name, LinearLayout.LayoutParams(0, -2, 1f))

            val pricePlaceholder = label("--", 14f, secondary, true).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
            }
            indexCard.addView(pricePlaceholder)

            workspace.addView(indexCard)
        }
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean): TextView {
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

    // Simple vector-style logo: rising bars with a curved underline.
    private inner class BrandMark(context: android.content.Context) : View(context) {
        private val green = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(30, 166, 75)
            style = Paint.Style.FILL
        }
        private val navy = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = primary
            style = Paint.Style.STROKE
            strokeWidth = 5.dp().toFloat()
            strokeCap = Paint.Cap.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val base = h * 0.76f
            val barWidth = w * 0.17f
            val gap = w * 0.07f
            val left = w * 0.12f
            val heights = floatArrayOf(h * 0.30f, h * 0.48f, h * 0.67f)

            heights.forEachIndexed { index, barHeight ->
                val x = left + index * (barWidth + gap)
                canvas.drawRoundRect(
                    x, base - barHeight, x + barWidth, base,
                    3.dp().toFloat(), 3.dp().toFloat(), green
                )
            }

            val curve = Path().apply {
                moveTo(w * 0.10f, h * 0.78f)
                cubicTo(w * 0.32f, h * 0.98f, w * 0.82f, h * 0.92f, w * 0.91f, h * 0.48f)
            }
            canvas.drawPath(curve, navy)
        }
    }
}

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
    private val indexBackgrounds = listOf(
        Color.rgb(238, 246, 255),
        Color.rgb(237, 249, 246),
        Color.rgb(245, 242, 255),
        Color.rgb(255, 247, 237),
        Color.rgb(255, 241, 243)
    )
    private val indexAccents = listOf(
        Color.rgb(48, 112, 190),
        Color.rgb(0, 145, 120),
        Color.rgb(116, 86, 190),
        Color.rgb(205, 125, 35),
        Color.rgb(190, 72, 95)
    )

    private lateinit var pageHeading: TextView
    private lateinit var workspace: LinearLayout
    private val navigationItems = mutableListOf<TextView>()
    private val priceViews = mutableMapOf<String, TextView>()
    private lateinit var feedStatus: TextView
    private var derivApiClient: DerivApiClient? = null
    private var selectedPage = "HOME"

    private val indexSymbols = listOf("R_10", "R_25", "R_50", "R_75", "R_100")

    private val indexes = listOf(
    "VOLATILITY INDEX 10",
    "VOLATILITY INDEX 25",
    "VOLATILITY INDEX 50",
    "VOLATILITY INDEX 75",
    "VOLATILITY INDEX 100"
)

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

        // Public market prices start immediately; no Deriv account login is required.
        startLiveIndexFeed()
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
        priceViews.clear()

        workspace.addView(label("INDEX MONITOR", 11f, primary, true).apply {
            letterSpacing = 0.08f
            setPadding(2.dp(), 0, 0, 8.dp())
        })

        feedStatus = label("CONNECTING TO DERIV...", 9f, secondary, true).apply {
            letterSpacing = 0.04f
            setPadding(2.dp(), 0, 0, 10.dp())
            includeFontPadding = false
        }
        workspace.addView(feedStatus)

        indexes.forEachIndexed { position, index ->
            val symbol = indexSymbols[position]
            val indexCard = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(indexBackgrounds[position], 11)
                setPadding(10.dp(), 8.dp(), 11.dp(), 8.dp())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                ).apply {
                    bottomMargin = 7.dp()
                }
            }

            val accentBar = View(this).apply {
                background = rounded(indexAccents[position], 3)
            }
            indexCard.addView(
                accentBar,
                LinearLayout.LayoutParams(4.dp(), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                    rightMargin = 9.dp()
                }
            )

            val name = label(index, 10.5f, indexAccents[position], true).apply {
                includeFontPadding = false
                maxLines = 1
                isSingleLine = true
                setTextColor(indexAccents[position])
            }
            indexCard.addView(name, LinearLayout.LayoutParams(0, -2, 1f))

            val priceView = label("--", 12f, primary, true).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                includeFontPadding = false
                maxLines = 1
            }
            priceViews[symbol] = priceView
            indexCard.addView(priceView)

            workspace.addView(indexCard)
        }
    }

    private fun startLiveIndexFeed() {
        val client = DerivApiClient(object : DerivApiClient.Callback {
            override fun onConnecting(message: String) = Unit
            override fun onConnected(accountId: String, accountType: String) = Unit
            override fun onBalance(balance: Double, currency: String) = Unit

            override fun onTick(symbol: String, quote: Double, epoch: Long) {
                runOnUiThread {
                    val priceView = priceViews[symbol] ?: return@runOnUiThread
                    priceView.text = formatIndexPrice(quote)
                    priceView.setTextColor(primary)
                    if (::feedStatus.isInitialized) {
                        feedStatus.text = "LIVE  •  DERIV MARKET DATA"
                        feedStatus.setTextColor(accent)
                    }
                }
            }

            override fun onCandles(symbol: String, candles: List<DerivApiClient.Candle>) = Unit
            override fun onMarketDataError(symbol: String, message: String) {
                runOnUiThread {
                    if (priceViews[symbol]?.text == "--") {
                        priceViews[symbol]?.text = "Unavailable"
                    }
                }
            }

            override fun onError(message: String) {
                runOnUiThread {
                    if (::feedStatus.isInitialized) {
                        feedStatus.text = "LIVE PRICE FEED: $message"
                        feedStatus.setTextColor(secondary)
                    }
                }
            }
            override fun onTradeData(json: org.json.JSONObject) = Unit
            override fun onDisconnected() = Unit
        })

        derivApiClient = client
        client.requestHistoricalCandles()
    }

    private fun formatIndexPrice(value: Double): String {
        return java.text.DecimalFormat("#,##0.00##").format(value)
    }

    override fun onDestroy() {
        derivApiClient?.destroy()
        derivApiClient = null
        super.onDestroy()
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

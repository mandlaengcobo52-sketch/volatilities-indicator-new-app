package za.co.volatilitiesindicator.app

import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.*
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

class MainActivity : Activity() {

    // =============================================================
    // VOLATILITIES INDICATOR — MASTER UI
    // =============================================================

    private val BG = Color.rgb(2, 8, 10)
    private val CARD = Color.rgb(4, 28, 34)
    private val CARD2 = Color.rgb(5, 34, 41)
    private val GREEN = Color.rgb(0, 255, 70)
    private val CYAN = Color.rgb(0, 200, 255)
    private val YELLOW = Color.rgb(255, 215, 0)
    private val RED = Color.rgb(255, 55, 75)
    private val PURPLE = Color.rgb(175, 55, 255)
    private val WHITE = Color.WHITE
    private val MUTED = Color.rgb(160, 170, 175)

    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout

    private var currentPlatform = "USER"
    private var executionMode = "DEMO"
    private var connected = false
    private var accountId = "—"
    private var balance = "—"
    private var openPnl = "—"
    private var drawerOpen = false

    private var accountType = ""

    private val prefs by lazy {
        getSharedPreferences("deriv_oauth", MODE_PRIVATE)
    }

    private val http = OkHttpClient()

    private data class IndexSpec(
        val name: String,
        val color: Int
    )

    private val indexes = listOf(
        IndexSpec("R_10", CYAN),
        IndexSpec("R_25", GREEN),
        IndexSpec("R_50", YELLOW),
        IndexSpec("R_75", RED),
        IndexSpec("R_100", PURPLE)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        handleOAuthCallback(intent)
        buildScreen()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            handleOAuthCallback(intent)
        }
    }

    // =============================================================
    // SCREEN
    // =============================================================

    private fun buildScreen() {
        scroll = ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            clipToPadding = false
        }

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
            setBackgroundColor(BG)
        }

        scroll.addView(content)
        setContentView(scroll)
        render()
    }

    private fun render() {
        content.removeAllViews()
        addHeader()
        if (drawerOpen && currentPlatform == "ADMIN") addAdminDrawer()
        addPlatform()
        addExecutionMode()
        addAccountCard()
        addIndexMonitor()
        addAccountSummary()
        addBottomNavigation()
    }

    // =============================================================
    // HEADER
    // =============================================================

    private fun addHeader() {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(18))
        }

        val menu = TextView(this).apply {
            text = "☰"
            textSize = 28f
            setTextColor(WHITE)
            gravity = Gravity.CENTER
            setOnClickListener {
                if (currentPlatform == "ADMIN") {
                    drawerOpen = !drawerOpen
                    render()
                }
            }
        }
        header.addView(menu, LinearLayout.LayoutParams(dp(42), dp(54)))

        val logo = ImageView(this).apply {
            // Preserve the existing application icon/logo.
            setImageDrawable(packageManager.getApplicationIcon(applicationInfo))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        header.addView(
            logo,
            LinearLayout.LayoutParams(dp(72), dp(72)).apply {
                rightMargin = dp(9)
            }
        )

        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val title = TextView(this).apply {
            text = "VOLATILITIES\nINDICATOR"
            textSize = 23f
            setTypeface(null, Typeface.BOLD)
            setTextColor(WHITE)
        }

        val subtitle = TextView(this).apply {
            text = "TRADING PLATFORM"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(MUTED)
            setPadding(0, dp(3), 0, 0)
        }

        brand.addView(title)
        brand.addView(subtitle)
        header.addView(brand)

        val bell = TextView(this).apply {
            text = "🔔"
            textSize = 29f
            gravity = Gravity.CENTER
            setTextColor(YELLOW)
            contentDescription = "Notifications"
        }
                header.addView(bell, LinearLayout.LayoutParams(dp(48), dp(60)))

        content.addView(header)
    }

    // =============================================================
    // PLATFORM
    // =============================================================

    private fun addPlatform() {
        addHeading("PLATFORM")

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val user = bigButton("●  USER", currentPlatform == "USER")
        val admin = bigButton("●●  ADMIN", currentPlatform == "ADMIN")

        user.setOnClickListener {
            currentPlatform = "USER"
            drawerOpen = false
            render()
        }

        admin.setOnClickListener {
            currentPlatform = "ADMIN"
            drawerOpen = false
            render()
        }

        row.addView(user, weightParams(1f, dp(62)))
        row.addView(admin, weightParams(1f, dp(62)))
        content.addView(row)
    }

    // =============================================================
    // EXECUTION MODE
    // =============================================================

    private fun addExecutionMode() {
        addHeading("EXECUTION MODE")

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val demo = bigButton(
            "▟  DEMO",
            executionMode == "DEMO"
        )

        val real = bigButton(
            "▥  REAL",
            executionMode == "REAL"
        )

        demo.setOnClickListener {
            executionMode = "DEMO"
            connected = false
            render()
        }

        real.setOnClickListener {
            executionMode = "REAL"
            connected = false
            render()
        }

        row.addView(demo, weightParams(1f, dp(62)))
        row.addView(real, weightParams(1f, dp(62)))

        content.addView(row)
    }

    // =============================================================
    // DERIV EXECUTION ACCOUNT
    // =============================================================

    private fun addAccountCard() {
        addHeading("DERIV EXECUTION ACCOUNT")

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(
                CARD,
                dp(8),
                Color.rgb(0, 90, 65),
                dp(1)
            )
        }

        val status = TextView(this).apply {
            text = if (connected) {
                "🟢  CONNECTED"
            } else {
                "🔴  NOT CONNECTED"
            }

            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(
                if (connected) GREEN else RED
            )
        }

        card.addView(status)

        card.addView(
            infoText(
                "Account: $accountId",
                WHITE,
                17f
            )
        )

        card.addView(
            infoText(
                "Balance: $balance",
                WHITE,
                17f
            )
        )

        card.addView(
            infoText(
                "Open P/L: $openPnl",
                WHITE,
                17f
            )
        )

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }

        val connect = bigButton(
            if (connected) {
                "🔗  CONNECTED"
            } else {
                "🔗  CONNECT"
            },
            connected
        )

        connect.setOnClickListener {
            if (!connected) {
                startDerivOAuth()
            }
        }

        val refresh = bigButton(
            "⟳  REFRESH",
            false
        )

        refresh.setOnClickListener {
            render()
        }

        buttons.addView(
            connect,
            weightParams(1f, dp(54))
        )

        buttons.addView(
            refresh,
            weightParams(1f, dp(54))
        )

        card.addView(buttons)

        content.addView(
            card,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(18)
            }
        )
    }

    // =============================================================
    // INDEX MONITOR
    // =============================================================

    private fun addIndexMonitor() {
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        titleRow.addView(
            headingView("INDEX MONITOR"),
            LinearLayout.LayoutParams(
                0,
                dp(44),
                1f
            )
        )

        titleRow.addView(
            TextView(this).apply {
                text = "⌃"
                textSize = 25f
                setTextColor(WHITE)
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(
                dp(44),
                dp(44)
            )
        )

        content.addView(titleRow)

        for (spec in indexes) {
            content.addView(
                indexRow(spec)
            )
        }
    }

    private fun indexRow(
        spec: IndexSpec
    ): LinearLayout {

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            setPadding(
                dp(10),
                dp(5),
                dp(5),
                dp(5)
            )

            background = rounded(
                CARD,
                dp(8),
                Color.rgb(0, 65, 75),
                dp(1)
            )

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(62)
            ).apply {
                bottomMargin = dp(5)
            }
        }

        val name = TextView(this).apply {
            text = spec.name
            textSize = 19f
            setTypeface(null, Typeface.BOLD)
            setTextColor(spec.color)
            gravity = Gravity.CENTER_VERTICAL
        }

        row.addView(
            name,
            LinearLayout.LayoutParams(
                dp(82),
                dp(50)
            )
        )

        row.addView(
            PriceGraphView(
                this,
                spec.color
            ),
            LinearLayout.LayoutParams(
                0,
                dp(48),
                1f
            ).apply {
                leftMargin = dp(3)
                rightMargin = dp(5)
            }
        )

        val waiting = TextView(this).apply {
            text = "WAITING"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(YELLOW)
            gravity = Gravity.CENTER

            background = rounded(
                CARD2,
                dp(2),
                Color.TRANSPARENT,
                0
            )
        }

        row.addView(
            waiting,
            LinearLayout.LayoutParams(
                dp(92),
                dp(44)
            )
        )

        row.addView(
            TextView(this).apply {
                text = "›"
                textSize = 34f
                setTextColor(WHITE)
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(
                dp(34),
                dp(50)
            )
        )

        row.setOnClickListener {
            Toast.makeText(
                this,
                "${spec.name} selected",
                Toast.LENGTH_SHORT
            ).show()
        }

        return row
    }
        // =============================================================
    // ACCOUNT SUMMARY
    // =============================================================

    private fun addAccountSummary() {
        addHeading("ACCOUNT SUMMARY")

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val values = listOf(
            Triple("OPEN", "0", CYAN),
            Triple("TOTAL P/L", "0.00", CYAN),
            Triple("WINS", "0", GREEN),
            Triple("LOSSES", "0", RED)
        )

        for ((label, value, color) in values) {

            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER

                setPadding(
                    dp(3),
                    dp(6),
                    dp(3),
                    dp(6)
                )

                background = rounded(
                    CARD,
                    dp(2),
                    Color.TRANSPARENT,
                    0
                )
            }

            box.addView(
                TextView(this).apply {
                    text = label
                    textSize = 11f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(color)
                    gravity = Gravity.CENTER
                }
            )

            box.addView(
                TextView(this).apply {
                    text = value
                    textSize = 15f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(color)
                    gravity = Gravity.CENTER
                }
            )

            row.addView(
                box,
                LinearLayout.LayoutParams(
                    0,
                    dp(76),
                    1f
                ).apply {
                    leftMargin = dp(2)
                    rightMargin = dp(2)
                }
            )
        }

        content.addView(row)
    }

    // =============================================================
    // BOTTOM NAVIGATION
    // =============================================================

    private fun addBottomNavigation() {

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER

            setPadding(
                dp(4),
                dp(8),
                dp(4),
                dp(8)
            )

            background = rounded(
                CARD,
                dp(2),
                Color.TRANSPARENT,
                0
            )
        }

        val items = listOf(
            "⌂" to "Home",
            "▥" to "Trade",
            "⌁" to "Analysis",
            "▤" to "News",
            "⚙" to "Settings"
        )

        for ((icon, label) in items) {

            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }

            box.addView(
                TextView(this).apply {
                    text = icon
                    textSize = 18f
                    setTextColor(
                        if (label == "Home") {
                            GREEN
                        } else {
                            WHITE
                        }
                    )
                    gravity = Gravity.CENTER
                }
            )

            box.addView(
                TextView(this).apply {
                    text = label
                    textSize = 11f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(
                        if (label == "Home") {
                            GREEN
                        } else {
                            WHITE
                        }
                    )
                    gravity = Gravity.CENTER
                }
            )

            box.setOnClickListener {
                Toast.makeText(
                    this@MainActivity,
                    label,
                    Toast.LENGTH_SHORT
                ).show()
            }

            nav.addView(
                box,
                LinearLayout.LayoutParams(
                    0,
                    dp(68),
                    1f
                )
            )
        }

        content.addView(
            nav,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(84)
            ).apply {
                topMargin = dp(10)
            }
        )

        content.addView(
            TextView(this).apply {
                text =
                    "VOLATILITIES INDICATOR • VERSION 1.0.0"

                textSize = 11f
                setTextColor(MUTED)
                gravity = Gravity.CENTER

                setPadding(
                    0,
                    dp(12),
                    0,
                    0
                )
            }
        )
    }

    // =============================================================
    // ADMIN DRAWER
    // =============================================================

    private fun addAdminDrawer() {

        val drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            setPadding(
                dp(14),
                dp(14),
                dp(14),
                dp(14)
            )

            background = rounded(
                Color.rgb(3, 24, 28),
                dp(8),
                GREEN,
                dp(1)
            )
        }

        drawer.addView(
            TextView(this).apply {
                text = "ADMIN MENU"
                textSize = 18f
                setTypeface(null, Typeface.BOLD)
                setTextColor(WHITE)
                setPadding(
                    0,
                    0,
                    0,
                    dp(8)
                )
            }
        )

        val options = listOf(
            "⌂  Dashboard",
            "▥  Index Settings",
            "⬟  Risk Categories",
            "●●  User Accounts",
            "🔔  Notifications",
            "▤  Trading Logs",
            "⚙  System Settings",
            "↪  Logout"
        )

        for (option in options) {

            val item = TextView(this).apply {
                text = option
                textSize = 14f
                setTextColor(WHITE)
                gravity = Gravity.CENTER_VERTICAL

                setPadding(
                    dp(10),
                    0,
                    dp(10),
                    0
                )

                background = rounded(
                    CARD,
                    dp(4),
                    Color.TRANSPARENT,
                    0
                )
            }

            item.setOnClickListener {
                drawerOpen = false
                render()
            }

            drawer.addView(
                item,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(48)
                ).apply {
                    bottomMargin = dp(3)
                }
            )
        }

        content.addView(
            drawer,
            1
        )
    }

    // =============================================================
    // HELPERS
    // =============================================================

    private fun addHeading(text: String) {
        content.addView(
            headingView(text)
        )
    }

    private fun headingView(
        text: String
    ): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 21f
            setTypeface(null, Typeface.BOLD)
            setTextColor(WHITE)
            gravity = Gravity.CENTER_VERTICAL

            setPadding(
                0,
                dp(8),
                0,
                dp(8)
            )
        }
    }

    private fun infoText(
        value: String,
        color: Int,
        size: Float
    ): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)

            setPadding(
                0,
                dp(3),
                0,
                dp(3)
            )
        }
    }

    private fun bigButton(
        textValue: String,
        selected: Boolean
    ): Button {

        val backgroundColor =
            if (selected) GREEN else CARD

        return Button(this).apply {
            text = textValue
            textSize = 16f
            isAllCaps = false

            setTypeface(
                null,
                Typeface.BOLD
            )

            setTextColor(
                if (selected) {
                    Color.BLACK
                } else {
                    WHITE
                }
            )

            background = rounded(
                backgroundColor,
                dp(3),
                Color.TRANSPARENT,
                0
            )

            minHeight = 0
            minimumHeight = 0

            setPadding(
                dp(5),
                0,
                dp(5),
                0
            )
        }
    }

    private fun weightParams(
        weight: Float,
        height: Int
    ): LinearLayout.LayoutParams {

        return LinearLayout.LayoutParams(
            0,
            height,
            weight
        ).apply {
            leftMargin = dp(5)
            rightMargin = dp(5)
        }
    }

    private fun rounded(
        fill: Int,
        radius: Int,
        strokeColor: Int,
        strokeWidth: Int
    ): GradientDrawable {

        return GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius.toFloat()

            if (strokeWidth > 0) {
                setStroke(
                    strokeWidth,
                    strokeColor
                )
            }
        }
    }

    private fun dp(value: Int): Int =
        (
            value *
                resources.displayMetrics.density
            ).toInt()

    // =============================================================
    // INDEX GRAPH
    // =============================================================

    private class PriceGraphView(
        context: android.content.Context,
        private val lineColor: Int
    ) : View(context) {

        private val points = floatArrayOf(
            0.68f,
            0.25f,
            0.62f,
            0.20f,
            0.58f,
            0.15f,
            0.70f,
            0.42f
        )

        private val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }

        override fun onDraw(
            canvas: Canvas
        ) {
            super.onDraw(canvas)

            paint.color = lineColor

            if (
                width <= 0 ||
                height <= 0
            ) {
                return
            }

            val path = Path()

            val step =
                width.toFloat() /
                    (points.size - 1)

            for (i in points.indices) {

                val x = i * step
                val y = height * points[i]

                if (i == 0) {
                    path.moveTo(x, y)
                } else {
                    path.lineTo(x, y)
                }
            }

            canvas.drawPath(
                path,
                paint
            )
        }
    }

    // =============================================================
    // DERIV OAUTH
    // =============================================================

    private fun startDerivOAuth() {
        try {

            val state =
                UUID.randomUUID().toString()

            val verifierBytes =
                ByteArray(32)

            SecureRandom().nextBytes(
                verifierBytes
            )

            val verifier =
                Base64.encodeToString(
                    verifierBytes,
                    Base64.URL_SAFE or
                        Base64.NO_WRAP or
                        Base64.NO_PADDING
                )

            val challenge =
                createCodeChallenge(
                    verifier
                )

            prefs.edit()
                .putString(
                    "oauth_state",
                    state
                )
                .putString(
                    "code_verifier",
                    verifier
                )
                .apply()

            val clientId =
                DerivApiClient.clientId(
                    executionMode
                )

            val redirectUri =
                DerivApiClient.OAUTH_REDIRECT_URI

            val scope =
                DerivApiClient.OAUTH_SCOPE

            // IMPORTANT: the authorization route is /oauth2/auth.
            // Do not change this back to /oauth2/authorize.
            val authorizationUrl =
                Uri.Builder()
                    .scheme("https")
                    .authority("auth.deriv.com")
                    .appendPath("oauth2")
                    .appendPath("auth")
                    .appendQueryParameter(
                        "response_type",
                        "code"
                    )
                    .appendQueryParameter(
                        "client_id",
                        clientId
                    )
                    .appendQueryParameter(
                        "redirect_uri",
                        redirectUri
                    )
                    .appendQueryParameter(
                        "scope",
                        scope
                    )
                    .appendQueryParameter(
                        "state",
                        state
                    )
                    .appendQueryParameter(
                        "code_challenge",
                        challenge
                    )
                    .appendQueryParameter(
                        "code_challenge_method",
                        "S256"
                    )
                    .build()

            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    authorizationUrl
                )
            )

        } catch (e: Exception) {
            Toast.makeText(
                this,
                "OAuth error: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
        private fun createCodeChallenge(
        verifier: String
    ): String {

        val digest =
            MessageDigest.getInstance(
                "SHA-256"
            )

        val hash =
            digest.digest(
                verifier.toByteArray(
                    Charsets.US_ASCII
                )
            )

        return Base64.encodeToString(
            hash,
            Base64.URL_SAFE or
                Base64.NO_WRAP or
                Base64.NO_PADDING
        )
    }

    // =============================================================
    // OAUTH CALLBACK
    // =============================================================

    private fun handleOAuthCallback(
        callbackIntent: Intent?
    ) {

        if (callbackIntent == null) {
            return
        }

        val uri =
            callbackIntent.data
                ?: return

        if (
            uri.scheme !=
                "volatilitiesindicator" ||
            uri.host !=
                "oauth-callback"
        ) {
            return
        }

        val error =
            uri.getQueryParameter(
                "error"
            )

        if (!error.isNullOrEmpty()) {

            Toast.makeText(
                this,
                "OAuth error: $error",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        val returnedState =
            uri.getQueryParameter(
                "state"
            )

        val code =
            uri.getQueryParameter(
                "code"
            )

        val savedState =
            prefs.getString(
                "oauth_state",
                ""
            ) ?: ""

        val verifier =
            prefs.getString(
                "code_verifier",
                ""
            ) ?: ""

        if (
            returnedState.isNullOrEmpty() ||
            returnedState != savedState
        ) {

            Toast.makeText(
                this,
                "OAuth security check failed",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        if (
            code.isNullOrEmpty() ||
            verifier.isEmpty()
        ) {

            Toast.makeText(
                this,
                "OAuth authorization code missing",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        exchangeOAuthCode(
            code,
            verifier
        )
    }

    // =============================================================
    // TOKEN EXCHANGE
    // =============================================================

    private fun exchangeOAuthCode(
        code: String,
        verifier: String
    ) {

        val clientId =
            DerivApiClient.clientId(
                executionMode
            )

        val redirectUri =
            DerivApiClient.OAUTH_REDIRECT_URI

        val body =
            FormBody.Builder()
                .add(
                    "grant_type",
                    "authorization_code"
                )
                .add(
                    "code",
                    code
                )
                .add(
                    "client_id",
                    clientId
                )
                .add(
                    "redirect_uri",
                    redirectUri
                )
                .add(
                    "code_verifier",
                    verifier
                )
                .build()

        val request =
            Request.Builder()
                .url(
                    "https://auth.deriv.com/oauth2/token"
                )
                .post(body)
                .build()

        http.newCall(request).enqueue(
            object : Callback {

                override fun onFailure(
                    call: Call,
                    e: IOException
                ) {

                    runOnUiThread {

                        Toast.makeText(
                            this@MainActivity,
                            "Connection failed: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                override fun onResponse(
                    call: Call,
                    response: Response
                ) {

                    val responseBody =
                        response.body?.string()
                            ?: ""

                    if (!response.isSuccessful) {

                        runOnUiThread {

                            Toast.makeText(
                                this@MainActivity,
                                "OAuth token error: ${response.code}",
                                Toast.LENGTH_LONG
                            ).show()
                        }

                        return
                    }

                    try {

                        val json =
                            JSONObject(
                                responseBody
                            )

                        val accessToken =
                            json.optString(
                                "access_token",
                                ""
                            )

                        if (
                            accessToken.isEmpty()
                        ) {

                            runOnUiThread {

                                Toast.makeText(
                                    this@MainActivity,
                                    "No access token returned",
                                    Toast.LENGTH_LONG
                                ).show()
                            }

                            return
                        }

                        prefs.edit()
                            .putString(
                                "access_token",
                                accessToken
                            )
                            .apply()

                        connected = true

                        accountType =
                            executionMode

                        accountId =
                            json.optString(
                                "loginid",
                                "CONNECTED"
                            )

                        runOnUiThread {

                            Toast.makeText(
                                this@MainActivity,
                                "Deriv OAuth connected",
                                Toast.LENGTH_SHORT
                            ).show()

                            render()
                        }

                    } catch (
                        e: Exception
                    ) {

                        runOnUiThread {

                            Toast.makeText(
                                this@MainActivity,
                                "Invalid OAuth response",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}

package za.co.volatilitiesindicator.app

import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

class MainActivity : Activity() {

    // =============================================================
    // COLOURS
    // =============================================================

    private val bg = Color.rgb(0, 8, 11)
    private val card = Color.rgb(4, 28, 35)
    private val green = Color.rgb(0, 255, 75)
    private val yellow = Color.rgb(255, 220, 0)
    private val red = Color.rgb(255, 55, 75)
    private val cyan = Color.rgb(0, 190, 255)
    private val white = Color.WHITE
    private val muted = Color.rgb(165, 175, 180)

    private lateinit var root: LinearLayout
    private lateinit var scroll: ScrollView

    private var platform = "USER"
    private var executionMode = "REAL"
    private var autoTrade = false

    private var connected = false
    private var accountId = "—"
    private var balance = "—"
    private var openPL = "—"

    private val prefs by lazy {
        getSharedPreferences("vi_oauth", MODE_PRIVATE)
    }

    private val http = OkHttpClient()

    // =============================================================
    // DERIV OAUTH
    // =============================================================

    private val clientId = "34AexAaq11rRv2X001ZfA"

    private val redirectUri =
        "https://volatilitiesindicator.org.za/vi-new/oauth-callback.php"

    // =============================================================
    // ACTIVITY
    // =============================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = bg
        window.navigationBarColor = bg

        buildScreen()

        handleOAuthCallback(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)

        if (intent != null) {
            setIntent(intent)
            handleOAuthCallback(intent)
        }
    }

    // =============================================================
    // BUILD MAIN SCREEN
    // =============================================================

    private fun buildScreen() {

        scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            setPadding(
                dp(22),
                dp(18),
                dp(22),
                dp(28)
            )

            setBackgroundColor(bg)
        }

        scroll.addView(root)

        setContentView(scroll)

        addHeader()
        addPlatform()
        addExecutionMode()
        addAutoTrade()
        addAccount()
        addIndexMonitor()
        addSummary()
        addBottomNavigation()
    }

    // =============================================================
    // HEADER
    // =============================================================

    private fun addHeader() {

        val header = LinearLayout(this).apply {

            orientation = LinearLayout.HORIZONTAL

            gravity = Gravity.CENTER_VERTICAL

            setPadding(
                0,
                0,
                0,
                dp(28)
            )
        }

        // LOGO
        val logo = BrandLogoView(this)

        header.addView(
            logo,
            LinearLayout.LayoutParams(
                dp(72),
                dp(72)
            ).apply {
                rightMargin = dp(16)
            }
        )

        // TITLE
        val titleBox = LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL

            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        text(
            "VOLATILITIES",
            25f,
            white,
            true
        ).also {
            titleBox.addView(it)
        }

        text(
            "INDICATOR",
            25f,
            green,
            true
        ).also {
            titleBox.addView(it)
        }

        text(
            "TRADING PLATFORM",
            15f,
            muted,
            true
        ).also {

            it.setPadding(
                0,
                dp(8),
                0,
                0
            )

            titleBox.addView(it)
        }

        header.addView(titleBox)

        // NOTIFICATION BELL
        val bell = TextView(this).apply {

            text = "🔔"

            textSize = 27f

            gravity = Gravity.CENTER

            setTextColor(white)

            setOnClickListener {

                Toast.makeText(
                    this@MainActivity,
                    "QUALIFYING INDEXES",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        header.addView(
            bell,
            LinearLayout.LayoutParams(
                dp(48),
                dp(60)
            )
        )

        root.addView(header)
    }

    // =============================================================
    // PLATFORM
    // =============================================================

    private fun addPlatform() {

        root.addView(
            sectionTitle("PLATFORM")
        )

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val user = button(
            "👤  USER",
            if (platform == "USER")
                green
            else
                card
        )

        val admin = button(
            "👥  ADMIN",
            if (platform == "ADMIN")
                green
            else
                card
        )

        user.setOnClickListener {

            platform = "USER"

            buildScreen()
        }

        admin.setOnClickListener {

            platform = "ADMIN"

            buildScreen()
        }

        row.addView(
            user,
            weightParams(1f, 8)
        )

        row.addView(
            admin,
            weightParams(1f, 0)
        )

        root.addView(row)
    }

    // =============================================================
    // EXECUTION MODE
    // =============================================================

    private fun addExecutionMode() {

        root.addView(
            sectionTitle("EXECUTION MODE")
        )

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val demo = button(
            "📈  DEMO",
            if (executionMode == "DEMO")
                green
            else
                card
        )

        val real = button(
            "▥  REAL",
            if (executionMode == "REAL")
                green
            else
                card
        )

        demo.setOnClickListener {

            executionMode = "DEMO"

            buildScreen()
        }

        real.setOnClickListener {

            executionMode = "REAL"

            buildScreen()
        }

        row.addView(
            demo,
            weightParams(1f, 8)
        )

        row.addView(
            real,
            weightParams(1f, 0)
        )

        root.addView(row)
    }

    // =============================================================
    // AUTO TRADE
    // =============================================================

    private fun addAutoTrade() {

        val b = button(
            "⚡  AUTO TRADE: ${
                if (autoTrade) "ON" else "OFF"
            }",
            if (autoTrade)
                green
            else
                card
        )

        b.setOnClickListener {

            autoTrade = !autoTrade

            buildScreen()
        }

        root.addView(
            b,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(76)
            ).apply {
                topMargin = dp(18)
            }
        )
    }
        // =============================================================
    // DERIV ACCOUNT
    // =============================================================

    private fun addAccount() {

        root.addView(
            sectionTitle("DERIV EXECUTION ACCOUNT")
        )

        val box = LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL

            setPadding(
                dp(28),
                dp(24),
                dp(28),
                dp(26)
            )

            setBackgroundColor(card)
        }

        val status = text(
            if (connected)
                "🟢  CONNECTED"
            else
                "🔴  NOT CONNECTED",

            20f,

            if (connected)
                green
            else
                red,

            true
        )

        box.addView(status)

        box.addView(
            text(
                "Account: $accountId",
                18f,
                white,
                false
            )
        )

        box.addView(
            text(
                "Balance: $balance",
                18f,
                white,
                false
            )
        )

        box.addView(
            text(
                "Open P/L: $openPL",
                18f,
                white,
                false
            )
        )

        val row = LinearLayout(this).apply {

            orientation = LinearLayout.HORIZONTAL

            setPadding(
                0,
                dp(20),
                0,
                0
            )
        }

        val connect = button(
            if (connected)
                "✓  CONNECTED"
            else
                "🔗  CONNECT",

            green
        )

        connect.setOnClickListener {

            if (!connected) {
                startDerivOAuth()
            }
        }

        val refresh = button(
            "⟳  REFRESH",
            card
        )

        refresh.setOnClickListener {
            refreshAccount()
        }

        row.addView(
            connect,
            weightParams(1f, 8)
        )

        row.addView(
            refresh,
            weightParams(1f, 0)
        )

        box.addView(row)

        root.addView(box)
    }

    // =============================================================
    // INDEX MONITOR
    // =============================================================

    private fun addIndexMonitor() {

        root.addView(
            sectionTitle("INDEX MONITOR")
        )

        val indexes = listOf(

            "R_10" to cyan,

            "R_25" to green,

            "R_50" to yellow,

            "R_75" to red,

            "R_100" to Color.rgb(
                175,
                60,
                255
            )
        )

        indexes.forEach { pair ->

            val name = pair.first

            val accent = pair.second

            val row = LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL

                setPadding(
                    dp(24),
                    dp(18),
                    dp(18),
                    dp(18)
                )

                setBackgroundColor(card)
            }

            val nameView =
                text(
                    name,
                    25f,
                    accent,
                    true
                )

            row.addView(
                nameView,
                LinearLayout.LayoutParams(
                    dp(145),
                    dp(76)
                )
            )

            val ticks =
                text(
                    "⌁⌁⌁⌁⌁⌁",
                    25f,
                    accent,
                    true
                )

            row.addView(
                ticks,
                LinearLayout.LayoutParams(
                    0,
                    dp(76),
                    1f
                )
            )

            val state =
                text(
                    "WAITING",
                    16f,
                    yellow,
                    true
                ).apply {

                    gravity =
                        Gravity.CENTER

                    setBackgroundColor(
                        Color.rgb(
                            5,
                            34,
                            42
                        )
                    )
                }

            row.addView(
                state,
                LinearLayout.LayoutParams(
                    dp(145),
                    dp(76)
                )
            )

            row.setOnClickListener {
                showIndex(name)
            }

            root.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(96)
                ).apply {
                    bottomMargin = dp(10)
                }
            )
        }
    }

    // =============================================================
    // ACCOUNT SUMMARY
    // =============================================================

    private fun addSummary() {

        root.addView(
            sectionTitle("ACCOUNT SUMMARY")
        )

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val open =
            summary(
                "OPEN",
                "0",
                cyan
            )

        val pnl =
            summary(
                "TOTAL P/L",
                "0.00",
                cyan
            )

        val wins =
            summary(
                "WINS",
                "0",
                green
            )

        val losses =
            summary(
                "LOSSES",
                "0",
                red
            )

        row.addView(
            open,
            weightParams(1f, 6)
        )

        row.addView(
            pnl,
            weightParams(1f, 6)
        )

        row.addView(
            wins,
            weightParams(1f, 6)
        )

        row.addView(
            losses,
            weightParams(1f, 0)
        )

        root.addView(row)
    }

    // =============================================================
    // BOTTOM NAVIGATION
    // =============================================================

    private fun addBottomNavigation() {

        val nav = LinearLayout(this).apply {

            orientation =
                LinearLayout.HORIZONTAL

            setPadding(
                dp(10),
                dp(16),
                dp(10),
                dp(4)
            )

            setBackgroundColor(card)
        }

        listOf(

            "⌂\nHome",

            "▥\nTrade",

            "⌁\nAnalysis",

            "▤\nNews",

            "⚙\nSettings"

        ).forEachIndexed { index, value ->

            val item =
                text(
                    value,
                    15f,
                    if (index == 0)
                        green
                    else
                        white,
                    true
                ).apply {

                    gravity =
                        Gravity.CENTER

                    setPadding(
                        0,
                        dp(12),
                        0,
                        dp(12)
                    )
                }

            nav.addView(
                item,
                LinearLayout.LayoutParams(
                    0,
                    dp(82),
                    1f
                )
            )
        }

        root.addView(nav)
    }

    // =============================================================
    // SUMMARY BOX
    // =============================================================

    private fun summary(
        label: String,
        value: String,
        colour: Int
    ): LinearLayout {

        val box = LinearLayout(this).apply {

            orientation =
                LinearLayout.VERTICAL

            gravity =
                Gravity.CENTER

            setPadding(
                dp(5),
                dp(16),
                dp(5),
                dp(16)
            )

            setBackgroundColor(card)
        }

        box.addView(
            text(
                label,
                13f,
                colour,
                true
            ).apply {
                gravity = Gravity.CENTER
            }
        )

        box.addView(
            text(
                value,
                22f,
                colour,
                true
            ).apply {

                gravity =
                    Gravity.CENTER

                setPadding(
                    0,
                    dp(8),
                    0,
                    0
                )
            }
        )

        return box
    }

    // =============================================================
    // INDEX CLICK
    // =============================================================

    private fun showIndex(index: String) {

        Toast.makeText(
            this,
            "$platform — $index",
            Toast.LENGTH_SHORT
        ).show()
    }

    // =============================================================
    // START DERIV OAUTH
    // =============================================================

    private fun startDerivOAuth() {

        try {

            val state =
                UUID.randomUUID().toString()

            val bytes =
                ByteArray(32)

            SecureRandom().nextBytes(bytes)

            val verifier =
                Base64.encodeToString(
                    bytes,
                    Base64.URL_SAFE or
                            Base64.NO_WRAP or
                            Base64.NO_PADDING
                )

            val challenge =
                createChallenge(verifier)

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

            val uri =
                Uri.Builder()
                    .scheme("https")
                    .authority("auth.deriv.com")
                    .appendPath("oauth2")
                    .appendPath("authorize")
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
                        "trade"
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
                    uri
                )
            )

        } catch (e: Exception) {

            Toast.makeText(
                this,
                "Unable to start Deriv connection: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // =============================================================
    // PKCE CHALLENGE
    // =============================================================

    private fun createChallenge(
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
        intent: Intent?
    ) {

        val uri =
            intent?.data ?: return

        if (
            uri.scheme !=
            "volatilitiesindicator"
        ) {
            return
        }

        if (
            uri.host !=
            "oauth-callback"
        ) {
            return
        }

        val error =
            uri.getQueryParameter("error")

        if (!error.isNullOrBlank()) {

            Toast.makeText(
                this,
                "Deriv OAuth error: $error",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        val state =
            uri.getQueryParameter("state")

        val code =
            uri.getQueryParameter("code")

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
            state.isNullOrBlank() ||
            state != savedState
        ) {

            Toast.makeText(
                this,
                "OAuth security check failed",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        if (
            code.isNullOrBlank() ||
            verifier.isBlank()
        ) {

            Toast.makeText(
                this,
                "Authorization code was not returned",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        exchangeCode(
            code,
            verifier
        )
    }

    // =============================================================
    // EXCHANGE AUTHORIZATION CODE
    // =============================================================

    private fun exchangeCode(
        code: String,
        verifier: String
    ) {

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
                            "Deriv connection failed: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                override fun onResponse(
                    call: Call,
                    response: Response
                ) {

                    val raw =
                        response.body?.string()
                            ?: ""

                    if (!response.isSuccessful) {

                        runOnUiThread {

                            Toast.makeText(
                                this@MainActivity,
                                "Deriv OAuth token error: ${response.code}",
                                Toast.LENGTH_LONG
                            ).show()
                        }

                        return
                    }

                    try {

                        val json =
                            JSONObject(raw)

                        val token =
                            json.optString(
                                "access_token",
                                ""
                            )

                        if (token.isBlank()) {

                            throw IllegalStateException(
                                "No access token returned"
                            )
                        }

                        prefs.edit()
                            .putString(
                                "access_token",
                                token
                            )
                            .apply()

                        connectDerivApi(token)

                    } catch (e: Exception) {

                        runOnUiThread {

                            Toast.makeText(
                                this@MainActivity,
                                "Invalid Deriv OAuth response",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        )
    }

    // =============================================================
    // DERIV WEBSOCKET
    // =============================================================

    private fun connectDerivApi(
        token: String
    ) {

        val url =
            "wss://ws.derivws.com/websockets/v3?app_id=$clientId"

        http.newWebSocket(
            Request.Builder()
                .url(url)
                .build(),

            object : WebSocketListener() {

                override fun onOpen(
                    webSocket: WebSocket,
                    response: Response
                ) {

                    val authorize =
                        JSONObject()
                            .put(
                                "authorize",
                                token
                            )

                    webSocket.send(
                        authorize.toString()
                    )
                }

                override fun onMessage(
                    webSocket: WebSocket,
                    text: String
                ) {

                    try {

                        val json =
                            JSONObject(text)

                        // -----------------------------------------
                        // ERROR
                        // -----------------------------------------

                        if (json.has("error")) {

                            val message =
                                json.optJSONObject(
                                    "error"
                                )?.optString(
                                    "message"
                                )
                                    ?: "Deriv authorization failed"

                            runOnUiThread {

                                connected = false

                                Toast.makeText(
                                    this@MainActivity,
                                    message,
                                    Toast.LENGTH_LONG
                                ).show()

                                buildScreen()
                            }

                            webSocket.close(
                                1000,
                                "Authorization failed"
                            )

                            return
                        }

                        // -----------------------------------------
                        // AUTHORIZE
                        // -----------------------------------------

                        if (json.has("authorize")) {

                            val auth =
                                json.getJSONObject(
                                    "authorize"
                                )

                            val loginId =
                                auth.optString(
                                    "loginid",
                                    "—"
                                )

                            val currency =
                                auth.optString(
                                    "currency",
                                    ""
                                )

                            val amount =
                                auth.optDouble(
                                    "balance",
                                    Double.NaN
                                )

                            accountId =
                                loginId

                            balance =
                                if (amount.isNaN()) {

                                    "—"

                                } else {

                                    "%.2f %s"
                                        .format(
                                            amount,
                                            currency
                                        )
                                        .trim()
                                }

                            connected = true

                            runOnUiThread {

                                buildScreen()

                                Toast.makeText(
                                    this@MainActivity,
                                    "Deriv account connected",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }

                            // Balance subscription
                            webSocket.send(
                                JSONObject()
                                    .put(
                                        "balance",
                                        1
                                    )
                                    .put(
                                        "subscribe",
                                        1
                                    )
                                    .toString()
                            )

                            // Open contracts
                            webSocket.send(
                                JSONObject()
                                    .put(
                                        "portfolio",
                                        1
                                    )
                                    .toString()
                            )

                            return
                        }

                        // -----------------------------------------
                        // BALANCE
                        // -----------------------------------------

                        if (json.has("balance")) {

                            val b =
                                json.getJSONObject(
                                    "balance"
                                )

                            val amount =
                                b.optDouble(
                                    "balance",
                                    Double.NaN
                                )

                            val currency =
                                b.optString(
                                    "currency",
                                    ""
                                )

                            if (!amount.isNaN()) {

                                balance =
                                    "%.2f %s"
                                        .format(
                                            amount,
                                            currency
                                        )
                                        .trim()
                            }

                            runOnUiThread {
                                buildScreen()
                            }
                        }

                        // -----------------------------------------
                        // PORTFOLIO
                        // -----------------------------------------

                        if (json.has("portfolio")) {

                            val portfolio =
                                json.optJSONObject(
                                    "portfolio"
                                )

                            val contracts =
                                portfolio
                                    ?.optJSONArray(
                                        "contracts"
                                    )

                            openPL =
                                if (
                                    contracts == null ||
                                    contracts.length() == 0
                                ) {

                                    "0.00"

                                } else {

                                    var total = 0.0

                                    for (
                                        i in
                                        0 until
                                        contracts.length()
                                    ) {

                                        total +=
                                            contracts
                                                .optJSONObject(i)
                                                ?.optDouble(
                                                    "profit",
                                                    0.0
                                                )
                                                ?: 0.0
                                    }

                                    "%.2f"
                                        .format(total)
                                }

                            runOnUiThread {
                                buildScreen()
                            }
                        }

                    } catch (e: Exception) {

                        runOnUiThread {

                            Toast.makeText(
                                this@MainActivity,
                                "Invalid Deriv response",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
                                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?
                ) {

                    runOnUiThread {

                        connected = false

                        Toast.makeText(
                            this@MainActivity,
                            "Deriv connection failed: ${
                                t.message
                                    ?: "network error"
                            }",
                            Toast.LENGTH_LONG
                        ).show()

                        buildScreen()
                    }
                }

                override fun onClosed(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String
                ) {

                    if (code != 1000) {

                        runOnUiThread {

                            connected = false

                            buildScreen()
                        }
                    }
                }
            }
        )
    }

    // =============================================================
    // REFRESH ACCOUNT
    // =============================================================

    private fun refreshAccount() {

        val token =
            prefs.getString(
                "access_token",
                ""
            ) ?: ""

        if (token.isBlank()) {

            connected = false

            accountId = "—"

            balance = "—"

            openPL = "—"

            buildScreen()

            return
        }

        connectDerivApi(token)
    }

    // =============================================================
    // SECTION TITLE
    // =============================================================

    private fun sectionTitle(
        value: String
    ): TextView =

        text(
            value,
            22f,
            white,
            true
        ).apply {

            setPadding(
                0,
                dp(24),
                0,
                dp(12)
            )
        }

    // =============================================================
    // TEXT
    // =============================================================

    private fun text(
        value: String,
        size: Float,
        colour: Int,
        bold: Boolean
    ) =
        TextView(this).apply {

            text = value

            textSize = size

            setTextColor(colour)

            if (bold) {
                setTypeface(
                    null,
                    Typeface.BOLD
                )
            }
        }

    // =============================================================
    // BUTTON
    // =============================================================

    private fun button(
        value: String,
        colour: Int
    ) =
        Button(this).apply {

            text = value

            textSize = 17f

            setTextColor(
                if (
                    colour == green ||
                    colour == yellow
                )
                    Color.BLACK
                else
                    white
            )

            setBackgroundColor(colour)

            setTypeface(
                null,
                Typeface.BOLD
            )

            isAllCaps = false

            minHeight = 0

            minimumHeight = 0

            setPadding(
                dp(8),
                0,
                dp(8),
                0
            )
        }

    // =============================================================
    // WEIGHT PARAMETERS
    // =============================================================

    private fun weightParams(
        weight: Float,
        rightMargin: Int
    ) =
        LinearLayout.LayoutParams(
            0,
            dp(76),
            weight
        ).apply {

            if (rightMargin > 0) {

                this.rightMargin =
                    dp(rightMargin)
            }
        }

    // =============================================================
    // DP
    // =============================================================

    private fun dp(
        value: Int
    ): Int =
        (
            value *
                    resources
                        .displayMetrics
                        .density
            ).toInt()

    // =============================================================
    // VOLATILITIES INDICATOR LOGO
    // =============================================================

    private class BrandLogoView(
        context: android.content.Context
    ) : View(context) {

        private val paint =
            Paint(
                Paint.ANTI_ALIAS_FLAG
            )

        override fun onDraw(
            canvas: Canvas
        ) {

            super.onDraw(canvas)

            val cx =
                width / 2f

            val cy =
                height / 2f

            val radius =
                width * 0.38f

            // White circle
            paint.style =
                Paint.Style.FILL

            paint.color =
                Color.WHITE

            canvas.drawCircle(
                cx,
                cy,
                radius,
                paint
            )

            // VI
            paint.color =
                Color.BLACK

            paint.textAlign =
                Paint.Align.CENTER

            paint.textSize =
                width * 0.32f

            paint.typeface =
                Typeface.DEFAULT_BOLD

            canvas.drawText(
                "VI",
                cx,
                cy -
                    (
                        paint.ascent() +
                                paint.descent()
                        ) / 2f,
                paint
            )

            // Green outer ring
            paint.style =
                Paint.Style.STROKE

            paint.strokeWidth =
                width * 0.055f

            paint.color =
                Color.rgb(
                    0,
                    255,
                    75
                )

            canvas.drawCircle(
                cx,
                cy,
                radius +
                    width * 0.07f,
                paint
            )
        }
    }

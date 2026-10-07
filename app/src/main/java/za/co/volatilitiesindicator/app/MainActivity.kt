package za.co.volatilitiesindicator.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Callback
import okhttp3.Call
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

class MainActivity : Activity() {

    // =============================================================
    // COLOURS
    // =============================================================

    private val bg = Color.rgb(2, 8, 12)
    private val panel = Color.rgb(5, 22, 29)
    private val panel2 = Color.rgb(7, 31, 40)
    private val border = Color.rgb(0, 180, 100)

    private val green = Color.rgb(0, 240, 100)
    private val red = Color.rgb(255, 55, 70)
    private val yellow = Color.rgb(255, 215, 0)
    private val cyan = Color.rgb(0, 210, 255)
    private val purple = Color.rgb(175, 70, 255)

    private val white = Color.rgb(240, 247, 249)
    private val muted = Color.rgb(150, 170, 177)

    // =============================================================
    // SETTINGS
    // =============================================================

    private var selectedPlatform = "USER"
    private var selectedMode = "DEMO"

    private var connected = false
    private var connectedAccountId = ""
    private var connectedAccountType = ""
    private var connectedBalance = 0.0
    private var connectedCurrency = ""
    private var connectionMessage = "NOT CONNECTED"

    // =============================================================
    // OAUTH
    // =============================================================

    private var oauthVerifier = ""
    private var oauthState = ""

    // =============================================================
    // ON CREATE
    // =============================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val settings = getSharedPreferences(
            "volatilities_settings",
            MODE_PRIVATE
        )

        selectedPlatform = settings.getString(
            "platform",
            "USER"
        ) ?: "USER"

        selectedMode = settings.getString(
            "execution_mode",
            "DEMO"
        ) ?: "DEMO"

        buildScreen()

        intent?.data?.let { uri ->
            if (
                uri.scheme == "volatilitiesindicator" &&
                uri.host == "oauth-callback"
            ) {
                handleOAuthCallback(uri)
            }
        }
    }

    // =============================================================
    // NEW INTENT
    // =============================================================

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        setIntent(intent)

        intent.data?.let { uri ->
            if (
                uri.scheme == "volatilitiesindicator" &&
                uri.host == "oauth-callback"
            ) {
                handleOAuthCallback(uri)
            }
        }
    }

    // =============================================================
    // MAIN SCREEN
    // =============================================================

    private fun buildScreen() {

        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(16),
                dp(10),
                dp(16),
                dp(20)
            )
        }

        scroll.addView(root)

        // =========================================================
        // HEADER
        // =========================================================

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val menuButton = smallIconButton("☰")

        menuButton.setOnClickListener {
            Toast.makeText(
                this@MainActivity,
                if (selectedPlatform == "ADMIN") {
                    "Admin menu"
                } else {
                    "User menu"
                },
                Toast.LENGTH_SHORT
            ).show()
        }

        header.addView(
            menuButton,
            LinearLayout.LayoutParams(
                dp(46),
                dp(52)
            )
        )

        val logo = ImageView(this).apply {
            setImageResource(
                R.drawable.volatilities_indicator_logo
            )
            scaleType =
                ImageView.ScaleType.CENTER_INSIDE
        }

        header.addView(
            logo,
            LinearLayout.LayoutParams(
                dp(68),
                dp(58)
            ).apply {
                marginStart = dp(2)
                marginEnd = dp(4)
            }
        )

        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }

        titleBox.addView(
            text(
                "VOLATILITIES",
                19,
                white,
                true
            )
        )

        titleBox.addView(
            text(
                "INDICATOR",
                19,
                green,
                true
            )
        )

        titleBox.addView(
            text(
                "TRADE SMARTER TODAY",
                7,
                muted,
                true
            )
        )

        header.addView(
            titleBox,
            LinearLayout.LayoutParams(
                0,
                -2,
                1f
            )
        )

        val notification =
            smallIconButton("🔔")

        notification.setOnClickListener {
            Toast.makeText(
                this@MainActivity,
                "No new notifications",
                Toast.LENGTH_SHORT
            ).show()
        }

        header.addView(
            notification,
            LinearLayout.LayoutParams(
                dp(46),
                dp(52)
            )
        )

        root.addView(header)
        root.addView(spacer(8))

        // =========================================================
        // PLATFORM
        // =========================================================

        root.addView(sectionTitle("PLATFORM"))

        val platformRow =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
            }

        val userButton =
            selectableButton(
                "👤  USER",
                selectedPlatform == "USER"
            )

        val adminButton =
            selectableButton(
                "👥  ADMIN",
                selectedPlatform == "ADMIN"
            )

        userButton.setOnClickListener {
            selectedPlatform = "USER"
            savePlatform()
            buildScreen()
        }

        adminButton.setOnClickListener {
            selectedPlatform = "ADMIN"
            savePlatform()
            buildScreen()
        }

        platformRow.addView(
            userButton,
            LinearLayout.LayoutParams(
                0,
                dp(58),
                1f
            ).apply {
                marginEnd = dp(6)
            }
        )

        platformRow.addView(
            adminButton,
            LinearLayout.LayoutParams(
                0,
                dp(58),
                1f
            )
        )

        root.addView(platformRow)
        root.addView(spacer(12))

        // =========================================================
        // EXECUTION MODE
        // =========================================================

        root.addView(
            sectionTitle("EXECUTION MODE")
        )

        val modeRow =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
            }

        val demoButton =
            selectableButton(
                "📈  DEMO",
                selectedMode == "DEMO"
            )

        val realButton =
            selectableButton(
                "▥  REAL",
                selectedMode == "REAL"
            )

        demoButton.setOnClickListener {
            selectedMode = "DEMO"
            saveMode()
            buildScreen()
        }

        realButton.setOnClickListener {
            selectedMode = "REAL"
            saveMode()
            buildScreen()
        }

        modeRow.addView(
            demoButton,
            LinearLayout.LayoutParams(
                0,
                dp(58),
                1f
            ).apply {
                marginEnd = dp(6)
            }
        )

        modeRow.addView(
            realButton,
            LinearLayout.LayoutParams(
                0,
                dp(58),
                1f
            )
        )

        root.addView(modeRow)
        root.addView(spacer(12))

        // =========================================================
        // AUTO TRADE
        // =========================================================

        root.addView(
            autoTradeButton()
        )

        root.addView(spacer(14))

        // =========================================================
        // DERIV ACCOUNT
        // =========================================================

        root.addView(
            derivAccountCard()
        )

        root.addView(spacer(14))

        // =========================================================
        // INDEX MONITOR
        // =========================================================

        root.addView(
            sectionTitle("INDEX MONITOR")
        )

        val indexes = listOf(
            "R_10",
            "R_25",
            "R_50",
            "R_75",
            "R_100"
        )

        indexes.forEach { index ->
            root.addView(
                indexRow(index)
            )
        }

        root.addView(spacer(14))

        // =========================================================
        // ADMINISTRATION
        // =========================================================

        if (selectedPlatform == "ADMIN") {

            root.addView(
                sectionTitle("ADMINISTRATION")
            )

            root.addView(
                adminMenu()
            )

            root.addView(spacer(14))
        }

        // =========================================================
        // ACCOUNT SUMMARY
        // =========================================================

        root.addView(
            sectionTitle("ACCOUNT SUMMARY")
        )

        root.addView(
            summaryRow()
        )

        root.addView(spacer(14))

        // =========================================================
        // BOTTOM NAVIGATION
        // =========================================================

        root.addView(
            bottomNavigation()
        )

        root.addView(spacer(12))

        root.addView(
            text(
                "VOLATILITIES INDICATOR • VERSION 1.0.0",
                10,
                muted,
                false,
                Gravity.CENTER
            )
        )

        setContentView(scroll)
    }

    // =============================================================
    // DERIV ACCOUNT CARD
    // =============================================================

    private fun derivAccountCard(): View {

        val card = card()

        card.setPadding(
            dp(12),
            dp(12),
            dp(12),
            dp(12)
        )

        card.addView(
            text(
                "⚡  DERIV EXECUTION ACCOUNT",
                15,
                yellow,
                true
            )
        )

        val statusText =
            if (connected) {
                "🟢  CONNECTED"
            } else {
                "🔴  $connectionMessage"
            }

        card.addView(
            text(
                statusText,
                13,
                if (connected) green else red,
                true,
                0,
                5
            )
        )

        card.addView(
            text(
                if (connected) {
                    "Account: $connectedAccountId"
                } else {
                    "Account: —"
                },
                14,
                white
            )
        )

        card.addView(
            text(
                if (connected) {
                    "Balance: $connectedCurrency %.2f"
                        .format(connectedBalance)
                } else {
                    "Balance: —"
                },
                14,
                white
            )
        )

        card.addView(
            text(
                "Open P/L: —",
                14,
                white
            )
        )

        card.addView(spacer(7))

        val buttons =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
            }

        val connect =
            selectableButton(
                if (connected) {
                    "🔗  CONNECTED"
                } else {
                    "🔗  CONNECT"
                },
                connected
            )

        val refresh =
            selectableButton(
                "⟳  REFRESH",
                false
            )

        connect.setOnClickListener {

            val accountType =
                if (selectedMode == "REAL") {
                    "real"
                } else {
                    "demo"
                }

            startDerivOAuth(accountType)
        }

        refresh.setOnClickListener {

            if (connected) {
                Toast.makeText(
                    this@MainActivity,
                    "Deriv connection is active",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    this@MainActivity,
                    "No Deriv account connected",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        buttons.addView(
            connect,
            LinearLayout.LayoutParams(
                0,
                dp(54),
                1f
            ).apply {
                marginEnd = dp(6)
            }
        )

        buttons.addView(
            refresh,
            LinearLayout.LayoutParams(
                0,
                dp(54),
                1f
            )
        )

        card.addView(buttons)

        return card
    }

    // =============================================================
    // START DERIV OAUTH
    // =============================================================

    private fun startDerivOAuth(
        accountType: String
    ) {

        oauthVerifier =
            generateCodeVerifier()

        oauthState =
            generateCodeVerifier()

        getSharedPreferences(
            "volatilities_oauth",
            MODE_PRIVATE
        )
            .edit()
            .putString(
                "pkce_verifier",
                oauthVerifier
            )
            .putString(
                "oauth_state",
                oauthState
            )
            .putString(
                "account_type",
                accountType
            )
            .apply()

        val challenge =
            generateCodeChallenge(
                oauthVerifier
            )

        val clientId =
            DerivApiClient.clientId(
                accountType
            )

        val uri =
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
                    DerivApiClient.OAUTH_REDIRECT_URI
                )
                .appendQueryParameter(
                    "scope",
                    DerivApiClient.OAUTH_SCOPE
                )
                .appendQueryParameter(
                    "state",
                    oauthState
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

        try {

            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    uri
                )
            )

        } catch (e: Exception) {

            Toast.makeText(
                this,
                "Unable to open Deriv: " +
                    (e.message ?: "Unknown error"),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // =============================================================
    // OAUTH CALLBACK
    // =============================================================

    private fun handleOAuthCallback(
        uri: Uri
    ) {

        val error =
            uri.getQueryParameter("error")

        if (!error.isNullOrEmpty()) {

            val description =
                uri.getQueryParameter(
                    "error_description"
                ) ?: error

            connectionMessage =
                "AUTHORIZATION FAILED"

            Toast.makeText(
                this,
                "Deriv authorization failed: $description",
                Toast.LENGTH_LONG
            ).show()

            buildScreen()

            return
        }

        val code =
            uri.getQueryParameter("code")

        val state =
            uri.getQueryParameter("state")

        if (
            code.isNullOrEmpty() ||
            state.isNullOrEmpty()
        ) {

            connectionMessage =
                "INVALID OAUTH RESPONSE"

            Toast.makeText(
                this,
                "Invalid Deriv OAuth response.",
                Toast.LENGTH_LONG
            ).show()

            buildScreen()

            return
        }

        val preferences =
            getSharedPreferences(
                "volatilities_oauth",
                MODE_PRIVATE
            )

        val savedState =
            preferences.getString(
                "oauth_state",
                ""
            ) ?: ""

        val verifier =
            preferences.getString(
                "pkce_verifier",
                ""
            ) ?: ""

        val accountType =
            preferences.getString(
                "account_type",
                "demo"
            ) ?: "demo"

        if (
            savedState.isEmpty() ||
            verifier.isEmpty()
        ) {

            connectionMessage =
                "OAUTH SESSION EXPIRED"

            Toast.makeText(
                this,
                "OAuth session is missing. Please connect again.",
                Toast.LENGTH_LONG
            ).show()

            buildScreen()
                                }

                                return
                            }

                            getSharedPreferences(
                                "volatilities_oauth",
                                MODE_PRIVATE
                            )
                                .edit()
                                .putString(
                                    "access_token",
                                    accessToken
                                )
                                .putString(
                                    "account_type",
                                    accountType
                                )
                                .apply()

                            runOnUiThread {

                                connected = true

                                connectedAccountType =
                                    accountType

                                connectedAccountId =
                                    "OAuth Connected"

                                connectionMessage =
                                    "CONNECTED"

                                Toast.makeText(
                                    this@MainActivity,
                                    "Deriv account connected successfully.",
                                    Toast.LENGTH_LONG
                                ).show()

                                buildScreen()
                            }

                        } catch (
                            e: Exception
                        ) {

                            runOnUiThread {

                                connected = false

                                connectionMessage =
                                    "INVALID TOKEN RESPONSE"

                                Toast.makeText(
                                    this@MainActivity,
                                    "Invalid response from Deriv.",
                                    Toast.LENGTH_LONG
                                ).show()

                                buildScreen()
                            }
                        }
                    }
                }
            )
    }

    // =============================================================
    // AUTO TRADE
    // =============================================================

    private fun autoTradeButton(): View {

        return selectableButton(
            "⚡  AUTO TRADE",
            false
        ).apply {

            setOnClickListener {

                Toast.makeText(
                    this@MainActivity,
                    "Auto Trade is ready for configuration.",
                    Toast.LENGTH_SHORT
                ).show()
            }

            layoutParams =
                LinearLayout.LayoutParams(
                    -1,
                    dp(54)
                )
        }
    }

    // =============================================================
    // INDEX ROW
    // =============================================================

    private fun indexRow(
        index: String
    ): View {

        val card = card()

        card.setPadding(
            dp(10),
            dp(10),
            dp(10),
            dp(10)
        )

        val titleRow =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
                gravity =
                    Gravity.CENTER_VERTICAL
            }

        titleRow.addView(
            text(
                index,
                16,
                white,
                true
            ),
            LinearLayout.LayoutParams(
                0,
                -2,
                1f
            )
        )

        titleRow.addView(
            badge(
                "WAITING",
                yellow
            ),
            LinearLayout.LayoutParams(
                dp(72),
                dp(25)
            )
        )

        card.addView(titleRow)

        card.addView(
            text(
                "Phase: BUILDING UP",
                12,
                cyan,
                true,
                0,
                5
            )
        )

        card.addView(
            text(
                "Current Price: —",
                12,
                white
            )
        )

        card.addView(
            text(
                "Support: —",
                12,
                green
            )
        )

        card.addView(
            text(
                "Resistance: —",
                12,
                red
            )
        )

        card.addView(
            text(
                "Direction: WAITING",
                12,
                yellow,
                true
            )
        )

        card.addView(
            text(
                "Probability: —",
                12,
                cyan
            )
        )

        card.addView(
            text(
                "Target Price: —",
                12,
                white
            )
        )

        return card
    }

    // =============================================================
    // ADMIN MENU
    // =============================================================

    private fun adminMenu(): View {

        val container = card()

        val items = listOf(
            "USER ACCOUNTS",
            "TRADE SETTINGS",
            "RISK CATEGORIES",
            "INDEX SETTINGS",
            "SYSTEM STATUS"
        )

        items.forEach { item ->

            val button =
                selectableButton(
                    item,
                    false
                )

            button.setOnClickListener {

                Toast.makeText(
                    this@MainActivity,
                    "$item selected",
                    Toast.LENGTH_SHORT
                ).show()
            }

            container.addView(
                button,
                LinearLayout.LayoutParams(
                    -1,
                    dp(48)
                ).apply {
                    bottomMargin = dp(5)
                }
            )
        }

        return container
    }
    // =============================================================
    // ACCOUNT SUMMARY
    // =============================================================

    private fun summaryRow(): View {

        val card = card()

        card.addView(
            text(
                "CONNECTION",
                11,
                muted,
                true
            )
        )

        card.addView(
            text(
                if (connected) {
                    "CONNECTED"
                } else {
                    "NOT CONNECTED"
                },
                15,
                if (connected) green else red,
                true
            )
        )

        card.addView(
            text(
                "Platform: $selectedPlatform",
                12,
                white
            )
        )

        card.addView(
            text(
                "Mode: $selectedMode",
                12,
                white
            )
        )

        card.addView(
            text(
                if (connected) {
                    "Account Type: $connectedAccountType"
                } else {
                    "Account Type: —"
                },
                12,
                white
            )
        )

        return card
    }

    // =============================================================
    // BOTTOM NAVIGATION
    // =============================================================

    private fun bottomNavigation(): View {

        val row =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
                setBackgroundColor(panel)
            }

        val items = listOf(
            "⌂\nHOME",
            "↗\nTRADE",
            "◷\nHISTORY",
            "🔔\nALERTS",
            "▣\nPLANS",
            "👤\nACCOUNT"
        )

        items.forEachIndexed { index, item ->

            val button =
                selectableButton(
                    item,
                    index == 0
                )

            button.textSize = 9f

            button.setOnClickListener {

                Toast.makeText(
                    this@MainActivity,
                    item.substringAfter("\n"),
                    Toast.LENGTH_SHORT
                ).show()
            }

            row.addView(
                button,
                LinearLayout.LayoutParams(
                    0,
                    dp(58),
                    1f
                ).apply {
                    marginEnd =
                        if (index < items.lastIndex) {
                            dp(2)
                        } else {
                            0
                        }
                }
            )
        }

        return row
    }

    // =============================================================
    // SECTION TITLE
    // =============================================================

    private fun sectionTitle(
        value: String
    ): TextView {

        return text(
            value,
            12,
            green,
            true
        ).apply {

            setPadding(
                0,
                dp(3),
                0,
                dp(7)
            )
        }
    }

    // =============================================================
    // CARD
    // =============================================================

    private fun card(): LinearLayout {

        return LinearLayout(this).apply {

            orientation =
                LinearLayout.VERTICAL

            setBackgroundColor(
                panel
            )

            setPadding(
                dp(10),
                dp(10),
                dp(10),
                dp(10)
            )

            layoutParams =
                LinearLayout.LayoutParams(
                    -1,
                    -2
                )
        }
    }

    // =============================================================
    // SELECTABLE BUTTON
    // =============================================================

    private fun selectableButton(
        value: String,
        selected: Boolean
    ): Button {

        return Button(this).apply {

            text = value

            textSize = 12f

            isAllCaps = false

            setTypeface(
                null,
                Typeface.BOLD
            )

            setTextColor(
                if (selected) {
                    Color.BLACK
                } else {
                    white
                }
            )

            setBackgroundColor(
                if (selected) {
                    green
                } else {
                    panel2
                }
            )

            gravity =
                Gravity.CENTER

            setPadding(
                dp(5),
                dp(5),
                dp(5),
                dp(5)
            )
        }
    }
    // =============================================================
    // SMALL ICON BUTTON
    // =============================================================

    private fun smallIconButton(
        value: String
    ): Button {

        return Button(this).apply {

            text = value

            textSize = 20f

            isAllCaps = false

            setTextColor(
                white
            )

            setBackgroundColor(
                panel2
            )

            gravity =
                Gravity.CENTER

            setPadding(
                0,
                0,
                0,
                0
            )
        }
    }

    // =============================================================
    // BADGE
    // =============================================================

    private fun badge(
        value: String,
        colour: Int
    ): TextView {

        return TextView(this).apply {

            text = value

            textSize = 9f

            setTextColor(
                Color.BLACK
            )

            setTypeface(
                null,
                Typeface.BOLD
            )

            gravity =
                Gravity.CENTER

            setBackgroundColor(
                colour
            )
        }
    }

    // =============================================================
    // TEXT
    // =============================================================

    private fun text(
        value: String,
        size: Int,
        colour: Int,
        bold: Boolean = false,
        gravity: Int = Gravity.START,
        top: Int = 0
    ): TextView {

        return TextView(this).apply {

            text = value

            textSize =
                size.toFloat()

            setTextColor(
                colour
            )

            this.gravity =
                gravity

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

    // =============================================================
    // SPACER
    // =============================================================

    private fun spacer(
        height: Int
    ): View {

        return View(this).apply {

            layoutParams =
                LinearLayout.LayoutParams(
                    1,
                    dp(height)
                )
        }
    }

    // =============================================================
    // SAVE PLATFORM
    // =============================================================

    private fun savePlatform() {

        getSharedPreferences(
            "volatilities_settings",
            MODE_PRIVATE
        )
            .edit()
            .putString(
                "platform",
                selectedPlatform
            )
            .apply()
    }

    // =============================================================
    // SAVE EXECUTION MODE
    // =============================================================

    private fun saveMode() {

        getSharedPreferences(
            "volatilities_settings",
            MODE_PRIVATE
        )
            .edit()
            .putString(
                "execution_mode",
                selectedMode
            )
            .apply()
    }

    // =============================================================
    // PKCE VERIFIER
    // =============================================================

    private fun generateCodeVerifier(): String {

        val bytes =
            ByteArray(32)

        SecureRandom().nextBytes(bytes)

        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or
                Base64.NO_WRAP or
                Base64.NO_PADDING
        )
    }

    // =============================================================
    // PKCE CHALLENGE
    // =============================================================

    private fun generateCodeChallenge(
        verifier: String
    ): String {

        val digest =
            MessageDigest.getInstance(
                "SHA-256"
            ).digest(
                verifier.toByteArray(
                    Charsets.US_ASCII
                )
            )

        return Base64.encodeToString(
            digest,
            Base64.URL_SAFE or
                Base64.NO_WRAP or
                Base64.NO_PADDING
        )
    // =============================================================
    // DP
    // =============================================================

    private fun dp(
        value: Int
    ): Int {

        return (
            value *
                resources.displayMetrics.density
            ).toInt()
    }

    // =============================================================
    // CLEANUP
    // =============================================================

    override fun onDestroy() {
        super.onDestroy()

        /*
         * Do not disconnect the Deriv account here.
         * Android may recreate the Activity during configuration
         * changes.
         */
    }
}

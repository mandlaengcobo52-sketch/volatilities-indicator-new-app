package za.co.volatilitiesindicator.app

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

class DerivApiClient(
    private val callback: Callback
) {

    interface Callback {

        fun onConnecting(message: String)

        fun onConnected(
            accountId: String,
            accountType: String
        )

        fun onBalance(
            balance: Double,
            currency: String
        )

        fun onError(message: String)

        fun onDisconnected()
    }

    companion object {

        private const val API_BASE =
            "https://api.derivws.com"

        private const val ACCOUNTS_PATH =
            "/trading/v1/options/accounts"

        private const val DEMO_CLIENT_ID =
            "34fz6VSRiNSQnELXjApVH"

        private const val REAL_CLIENT_ID =
            "34fz9hcgpdwSzNCVeUgMR"

        const val OAUTH_REDIRECT_URI =
            "https://volatilitiesindicator.org.za/accounts/account-callback.php"

        const val OAUTH_AUTH_URL =
            "https://auth.deriv.com/oauth2/auth"

        const val OAUTH_TOKEN_URL =
            "https://auth.deriv.com/oauth2/token"

        const val OAUTH_SCOPE =
            "trade"

        fun clientId(
            accountType: String
        ): String {

            return if (
                accountType.equals(
                    "real",
                    ignoreCase = true
                )
            ) {
                REAL_CLIENT_ID
            } else {
                DEMO_CLIENT_ID
            }
        }

        fun buildOAuthUrl(
            accountType: String,
            state: String,
            codeChallenge: String
        ): String {

            val clientId =
                clientId(accountType)

            return OAUTH_AUTH_URL +
                "?response_type=code" +
                "&client_id=" +
                URLEncoder.encode(
                    clientId,
                    "UTF-8"
                ) +
                "&redirect_uri=" +
                URLEncoder.encode(
                    OAUTH_REDIRECT_URI,
                    "UTF-8"
                ) +
                "&scope=" +
                URLEncoder.encode(
                    OAUTH_SCOPE,
                    "UTF-8"
                ) +
                "&state=" +
                URLEncoder.encode(
                    state,
                    "UTF-8"
                ) +
                "&code_challenge=" +
                URLEncoder.encode(
                    codeChallenge,
                    "UTF-8"
                ) +
                "&code_challenge_method=S256"
        }

        fun createCodeVerifier(): String {

            val bytes =
                ByteArray(32)

            java.security.SecureRandom()
                .nextBytes(bytes)

            return Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(bytes)
        }

        fun createCodeChallenge(
            verifier: String
        ): String {

            val digest =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(
                        verifier.toByteArray(
                            Charsets.US_ASCII
                        )
                    )

            return Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(digest)
        }
    }

    // =============================================================
    // HTTP CLIENT
    // =============================================================

    private val httpClient =
        OkHttpClient.Builder()
            .pingInterval(
                30,
                TimeUnit.SECONDS
            )
            .build()

    // =============================================================
    // WEBSOCKET
    // =============================================================

    private var webSocket: WebSocket? = null

    // =============================================================
    // CONNECTION DATA
    // =============================================================

    private var accessToken: String = ""

    private var selectedAccountId: String = ""

    private var selectedAccountType: String = ""

    // =============================================================
    // MANUAL DISCONNECT FLAG
    //
    // false = connection should remain active/reconnect
    // true  = user deliberately disconnected
    // =============================================================

    private var manualDisconnect = false

    // =============================================================
    // CONNECT WITH ACCESS TOKEN
    // =============================================================

    fun connectWithAccessToken(
        token: String,
        accountType: String
    ) {

        accessToken =
            token.trim()

        selectedAccountType =
            accountType

        manualDisconnect = false

        if (accessToken.isEmpty()) {

            callback.onError(
                "OAuth access token is empty."
            )

            return
        }

        callback.onConnecting(
            "Connecting to Deriv..."
        )

        getAccounts(accountType)
    }
        // =============================================================
    // GET DERIV ACCOUNTS
    // =============================================================

    private fun getAccounts(
        accountType: String
    ) {

        val request =
            Request.Builder()
                .url(
                    API_BASE +
                        ACCOUNTS_PATH
                )
                .addHeader(
                    "Authorization",
                    "Bearer $accessToken"
                )
                .get()
                .build()

        httpClient
            .newCall(request)
            .enqueue(
                object : okhttp3.Callback {

                    override fun onFailure(
                        call: Call,
                        e: IOException
                    ) {

                        callback.onError(
                            "Unable to get Deriv accounts: " +
                                (
                                    e.message
                                        ?: "network error"
                                )
                        )
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response
                    ) {

                        response.use {

                            val body =
                                it.body
                                    ?.string()
                                    .orEmpty()

                            if (!it.isSuccessful) {

                                callback.onError(
                                    "Deriv account request failed: " +
                                        it.code +
                                        " " +
                                        body
                                )

                                return
                            }

                            try {

                                val json =
                                    JSONObject(body)

                                val data =
                                    json.opt("data")

                                var foundAccountId =
                                    ""

                                // -------------------------------------------------
                                // ACCOUNT ARRAY
                                // -------------------------------------------------

                                if (
                                    data is JSONArray
                                ) {

                                    for (
                                        i in 0 until data.length()
                                    ) {

                                        val account =
                                            data.optJSONObject(i)
                                                ?: continue

                                        val type =
                                            account.optString(
                                                "account_type"
                                            )

                                        val id =
                                            account.optString(
                                                "account_id"
                                            )

                                        if (
                                            type.equals(
                                                accountType,
                                                ignoreCase = true
                                            ) &&
                                            id.isNotEmpty()
                                        ) {

                                            foundAccountId =
                                                id

                                            break
                                        }
                                    }

                                // -------------------------------------------------
                                // SINGLE ACCOUNT OBJECT
                                // -------------------------------------------------

                                } else if (
                                    data is JSONObject
                                ) {

                                    val type =
                                        data.optString(
                                            "account_type"
                                        )

                                    val id =
                                        data.optString(
                                            "account_id"
                                        )

                                    if (
                                        type.equals(
                                            accountType,
                                            ignoreCase = true
                                        )
                                    ) {

                                        foundAccountId =
                                            id
                                    }
                                }

                                // -------------------------------------------------
                                // ACCOUNT NOT FOUND
                                // -------------------------------------------------

                                if (
                                    foundAccountId.isEmpty()
                                ) {

                                    callback.onError(
                                        "No $accountType Deriv account was found."
                                    )

                                    return
                                }

                                // -------------------------------------------------
                                // ACCOUNT FOUND
                                // -------------------------------------------------

                                selectedAccountId =
                                    foundAccountId

                                requestWebSocketUrl(
                                    accountType
                                )

                            } catch (
                                e: Exception
                            ) {

                                callback.onError(
                                    "Invalid Deriv account response: " +
                                        (
                                            e.message
                                                ?: "unknown error"
                                        )
                                )
                            }
                        }
                    }
                }
            )
    }


    // =============================================================
    // REQUEST DERIV WEBSOCKET URL
    // =============================================================

    private fun requestWebSocketUrl(
        accountType: String
    ) {

        val url =
            "$API_BASE$ACCOUNTS_PATH/" +
                "$selectedAccountId/otp"

        val request =
            Request.Builder()
                .url(url)
                .addHeader(
                    "Authorization",
                    "Bearer $accessToken"
                )
                .post(
                    RequestBody.create(
                        null,
                        ByteArray(0)
                    )
                )
                .build()

        httpClient
            .newCall(request)
            .enqueue(
                object : okhttp3.Callback {

                    override fun onFailure(
                        call: Call,
                        e: IOException
                    ) {

                        callback.onError(
                            "Unable to create Deriv connection: " +
                                (
                                    e.message
                                        ?: "network error"
                                )
                        )
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response
                    ) {

                        response.use {

                            val body =
                                it.body
                                    ?.string()
                                    .orEmpty()

                            if (!it.isSuccessful) {

                                callback.onError(
                                    "Deriv OTP request failed: " +
                                        it.code +
                                        " " +
                                        body
                                )

                                return
                            }

                            try {

                                val json =
                                    JSONObject(body)

                                val data =
                                    json.optJSONObject(
                                        "data"
                                    )

                                val wsUrl =
                                    data
                                        ?.optString(
                                            "url"
                                        )
                                        .orEmpty()

                                if (
                                    wsUrl.isEmpty()
                                ) {

                                    callback.onError(
                                        "Deriv did not return a WebSocket URL."
                                    )

                                    return
                                }

                                openWebSocket(
                                    wsUrl,
                                    accountType
                                )

                            } catch (
                                e: Exception
                            ) {

                                callback.onError(
                                    "Invalid Deriv connection response: " +
                                        (
                                            e.message
                                                ?: "unknown error"
                                        )
                                )
                            }
                        }
                    }
                }
            )
    }
        // =============================================================
    // OPEN WEBSOCKET
    // =============================================================

    private fun openWebSocket(
        wsUrl: String,
        accountType: String
    ) {

        // A new connection is being deliberately established.
        manualDisconnect = false

        val request =
            Request.Builder()
                .url(wsUrl)
                .build()

        webSocket =
            httpClient.newWebSocket(
                request,
                object : WebSocketListener() {

                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response
                    ) {

                        // -------------------------------------------------
                        // CONNECTION IS READY
                        // -------------------------------------------------

                        callback.onConnected(
                            selectedAccountId,
                            accountType
                        )

                        // -------------------------------------------------
                        // BALANCE SUBSCRIPTION
                        // -------------------------------------------------

                        val balanceRequest =
                            JSONObject()
                                .put(
                                    "balance",
                                    1
                                )
                                .put(
                                    "subscribe",
                                    1
                                )
                                .put(
                                    "req_id",
                                    1
                                )

                        webSocket.send(
                            balanceRequest.toString()
                        )
                    }


                    // =====================================================
                    // RECEIVE MESSAGE
                    // =====================================================

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String
                    ) {

                        try {

                            val json =
                                JSONObject(text)

                            // -------------------------------------------------
                            // BALANCE
                            // -------------------------------------------------

                            val balanceObject =
                                json.optJSONObject(
                                    "balance"
                                )

                            if (
                                balanceObject != null
                            ) {

                                val balance =
                                    balanceObject.optDouble(
                                        "balance",
                                        Double.NaN
                                    )

                                val currency =
                                    balanceObject.optString(
                                        "currency",
                                        ""
                                    )

                                if (
                                    !balance.isNaN()
                                ) {

                                    callback.onBalance(
                                        balance,
                                        currency
                                    )
                                }
                            }

                            // -------------------------------------------------
                            // DERIV ERROR
                            // -------------------------------------------------

                            val errorObject =
                                json.optJSONObject(
                                    "error"
                                )

                            if (
                                errorObject != null
                            ) {

                                callback.onError(
                                    errorObject.optString(
                                        "message",
                                        "Deriv returned an error."
                                    )
                                )
                            }

                        } catch (
                            _: Exception
                        ) {

                            // Ignore malformed/non-critical
                            // messages so they do not terminate
                            // the connection.
                        }
                    }


                    // =====================================================
                    // CONNECTION FAILURE
                    // =====================================================

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?
                    ) {

                        /*
                         * IMPORTANT:
                         *
                         * A network/WebSocket failure is NOT treated
                         * as a deliberate user disconnect.
                         *
                         * We therefore do not call disconnect()
                         * here and we do not clear the access token.
                         */

                        if (
                            manualDisconnect
                        ) {
                            return
                        }

                        callback.onError(
                            "Deriv connection interrupted: " +
                                (
                                    t.message
                                        ?: "connection lost"
                                )
                        )

                        /*
                         * Do not deliberately disconnect the
                         * account here.
                         *
                         * The user remains authenticated and can
                         * reconnect using REFRESH if required.
                         */
                    }


                    // =====================================================
                    // SOCKET CLOSED
                    // =====================================================

                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String
                    ) {

                        /*
                         * Only report a real disconnect when the
                         * user deliberately requested it.
                         *
                         * An ordinary socket closure must not
                         * erase the authenticated account state.
                         */

                        if (
                            manualDisconnect
                        ) {

                            callback.onDisconnected()
                        }
                    }
                }
            )
    }
        private fun scheduleReconnect(
        accountType: String
    ) {

        if (manualDisconnect) {
            return
        }

        if (accessToken.isBlank()) {
            return
        }

        // Prevent multiple reconnect timers from running at the same time.
        reconnectExecutor?.shutdownNow()

        reconnectExecutor =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor()

        reconnectExecutor?.schedule(
            {

                if (manualDisconnect) {
                    return@schedule
                }

                if (accessToken.isBlank()) {
                    return@schedule
                }

                try {

                    callback.onConnecting(
                        "Reconnecting to Deriv..."
                    )

                    getAccounts(accountType)

                } catch (_: Exception) {

                    // The next connection attempt will be scheduled
                    // by the connection failure/close handling.
                }

            },
            5,
            java.util.concurrent.TimeUnit.SECONDS
        )
    }


    private fun cancelReconnect() {

        try {
            reconnectExecutor?.shutdownNow()
        } catch (_: Exception) {
            // Ignore shutdown errors.
        }

        reconnectExecutor = null
    }


    fun disconnect() {

        /*
         * This is the ONLY deliberate disconnect path.
         *
         * Once this is called:
         * - automatic reconnect is stopped
         * - the WebSocket is closed
         * - authentication data is cleared
         * - the application is informed that the account was disconnected
         */

        manualDisconnect = true

        cancelReconnect()

        try {
            webSocket?.close(
                1000,
                "User disconnected"
            )
        } catch (_: Exception) {
            // Ignore close errors.
        }

        webSocket = null

        accessToken = ""

        selectedAccountId = ""

        callback.onDisconnected()
    }


    fun isConnected(): Boolean {
        return webSocket != null &&
                !manualDisconnect
    }
        private fun clearConnectionState() {

        cancelReconnect()

        webSocket = null

        accessToken = ""

        selectedAccountId = ""

        manualDisconnect = true
    }


    override fun finalize() {

        try {
            cancelReconnect()
        } catch (_: Exception) {
            // Ignore cleanup errors.
        }

        try {
            webSocket?.close(
                1000,
                "Client finalized"
            )
        } catch (_: Exception) {
            // Ignore close errors.
        }

        webSocket = null

        super.finalize()
    }
}

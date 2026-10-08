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
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class DerivApiClient(
    private val callback: Callback
) {

    // =============================================================
    // CALLBACK
    // =============================================================

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


    // =============================================================
    // DERIV CONFIGURATION
    // =============================================================

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


        // =========================================================
        // CLIENT ID
        // =========================================================

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


        // =========================================================
        // OAUTH URL
        // =========================================================

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


        // =========================================================
        // PKCE VERIFIER
        // =========================================================

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


        // =========================================================
        // PKCE CHALLENGE
        // =========================================================

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
            .connectTimeout(
                20,
                TimeUnit.SECONDS
            )
            .readTimeout(
                30,
                TimeUnit.SECONDS
            )
            .writeTimeout(
                30,
                TimeUnit.SECONDS
            )
            .pingInterval(
                20,
                TimeUnit.SECONDS
            )
            .build()


    // =============================================================
    // WEBSOCKET
    // =============================================================

    private var webSocket: WebSocket? = null


    // =============================================================
    // APPLICATION-LEVEL PING
    // =============================================================

    private var pingExecutor:
        ScheduledExecutorService? = null


    // =============================================================
    // CONNECTION STATE
    // =============================================================

    private var accessToken: String = ""

    private var selectedAccountId: String = ""

    private var selectedAccountType: String = ""

    private var connectionActive = false
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
            accountType.trim()

        if (accessToken.isEmpty()) {

            callback.onError(
                "OAuth access token is empty."
            )

            return
        }

        // Close any previous connection first.
        stopPing()

        webSocket?.close(
            1000,
            "Replacing previous connection"
        )

        webSocket = null
        connectionActive = false

        callback.onConnecting(
            "Connecting to Deriv..."
        )

        getAccounts(
            selectedAccountType
        )
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
                                // DERIV MAY RETURN AN ARRAY
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
                                }

                                // -------------------------------------------------
                                // DERIV MAY RETURN A SINGLE OBJECT
                                // -------------------------------------------------

                                else if (
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
                                // NO ACCOUNT FOUND
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
    // REQUEST DERIV OTP / WEBSOCKET URL
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

        // Stop any previous application-level ping.
        stopPing()

        webSocket?.close(
            1000,
            "Opening new connection"
        )

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

                        connectionActive = true

                        callback.onConnected(
                            selectedAccountId,
                            accountType
                        )

                        // -------------------------------------------------
                        // START KEEP-ALIVE
                        // -------------------------------------------------

                        startPing()

                        // -------------------------------------------------
                        // SUBSCRIBE TO BALANCE
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


                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String
                    ) {

                        try {

                            val json =
                                JSONObject(text)

                            // -------------------------------------------------
                            // BALANCE UPDATE
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
                            e: Exception
                        ) {

                            callback.onError(
                                "Unable to read Deriv response."
                            )
                        }
                    }


                    // =========================================================
                    // WEBSOCKET FAILURE
                    // =========================================================

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?
                    ) {

                        connectionActive = false

                        stopPing()

                        callback.onError(
                            "Deriv connection lost: " +
                                (
                                    t.message
                                        ?: "unknown error"
                                )
                        )

                        callback.onDisconnected()
                    }


                    // =========================================================
                    // WEBSOCKET CLOSED
                    // =========================================================

                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String
                    ) {

                        connectionActive = false

                        stopPing()

                        callback.onDisconnected()
                    }
                }
            )
    }


    // =============================================================
    // KEEP-ALIVE PING
    // =============================================================

    private fun startPing() {

        stopPing()

        pingExecutor =
            Executors.newSingleThreadScheduledExecutor()

        pingExecutor?.scheduleAtFixedRate(
            {

                if (
                    connectionActive
                ) {

                    val socket =
                        webSocket

                    if (
                        socket != null
                    ) {

                        try {

                            val ping =
                                JSONObject()
                                    .put(
                                        "ping",
                                        1
                                    )

                            socket.send(
                                ping.toString()
                            )

                        } catch (
                            _: Exception
                        ) {
                            // WebSocket callback will
                            // handle an actual failure.
                        }
                    }
                }

            },
            30,
            30,
            TimeUnit.SECONDS
        )
    }


    // =============================================================
    // STOP KEEP-ALIVE PING
    // =============================================================

    private fun stopPing() {

        pingExecutor?.shutdownNow()

        pingExecutor = null
    }
        // =============================================================
    // MANUAL DISCONNECT
    // =============================================================

    fun disconnect() {

        connectionActive = false

        stopPing()

        webSocket?.close(
            1000,
            "User disconnected"
        )

        webSocket = null

        accessToken = ""

        selectedAccountId = ""

        selectedAccountType = ""

        callback.onDisconnected()
    }


    // =============================================================
    // CONNECTION STATUS
    // =============================================================

    fun isConnected(): Boolean {
        return connectionActive &&
            webSocket != null
    }


    // =============================================================
    // CURRENT ACCOUNT
    // =============================================================

    fun getSelectedAccountId(): String {
        return selectedAccountId
    }


    fun getSelectedAccountType(): String {
        return selectedAccountType
    }


    // =============================================================
    // CURRENT TOKEN STATUS
    // =============================================================

    fun hasAccessToken(): Boolean {
        return accessToken.isNotEmpty()
    }


    // =============================================================
    // CLEAN CONNECTION STATE
    // =============================================================

    private fun clearConnectionState() {

        connectionActive = false

        stopPing()

        webSocket = null

        selectedAccountId = ""

        selectedAccountType = ""
    }
        // =============================================================
    // FINAL CLEANUP
    // =============================================================

    override fun finalize() {
        try {
            disconnect()
        } catch (_: Exception) {
        }

        super.finalize()
    }
}

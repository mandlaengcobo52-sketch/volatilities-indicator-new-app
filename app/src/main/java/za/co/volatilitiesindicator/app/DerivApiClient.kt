package za.co.volatilitiesindicator.app

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DerivApiClient(
    private val callback: Callback
) {

    interface Callback {

        fun onConnecting(
            message: String
        )

        fun onConnected(
            accountId: String,
            accountType: String
        )

        fun onBalance(
            balance: Double,
            currency: String
        )

        fun onError(
            message: String
        )

        fun onDisconnected()
    }

    companion object {

        /*
         * CURRENT DERIV API
         *
         * REST base:
         * https://api.derivws.com
         *
         * Authenticated WebSocket URL is obtained
         * through the OTP endpoint.
         */
        private const val API_BASE_URL =
            "https://api.derivws.com"

        private const val ACCOUNTS_URL =
            "$API_BASE_URL/trading/v1/options/accounts"

        private const val OTP_URL =
            "$API_BASE_URL/trading/v1/options/accounts"

        /*
         * Current OAuth token endpoint.
         */
        const val OAUTH_TOKEN_URL =
            "https://auth.deriv.com/oauth2/token"

        /*
         * Current OAuth authorization endpoint.
         *
         * IMPORTANT:
         * It is /oauth2/auth
         * NOT /oauth2/authorize
         */
        const val OAUTH_AUTH_URL =
            "https://auth.deriv.com/oauth2/auth"

        /*
         * This redirect URI must remain exactly the
         * URI registered against the OAuth application.
         */
        const val OAUTH_REDIRECT_URI =
            "https://volatilitiesindicator.org.za/accounts/account-callback.php"

        /*
         * The application requests trading access.
         */
        const val OAUTH_SCOPE =
            "trade"

        /*
         * Existing OAuth client IDs from the project.
         *
         * These are preserved from the ZIP.
         */
        private const val DEMO_CLIENT_ID =
            "34fz6VSRiNSQnELXjApVH"

        private const val REAL_CLIENT_ID =
            "34fz9hcgpdWwSzNCVeUgMR"

        /*
         * Return the client ID selected by the
         * application execution mode.
         */
        fun clientId(
            mode: String
        ): String {

            return if (
                mode.equals(
                    "REAL",
                    ignoreCase = true
                )
            ) {

                REAL_CLIENT_ID

            } else {

                DEMO_CLIENT_ID
            }
        }

        /*
         * Current REST API base.
         */
        const val API_BASE =
            API_BASE_URL
    }

    /*
     * HTTP client.
     *
     * Used for:
     *
     * 1. Getting the user's Options accounts.
     * 2. Getting the authenticated WebSocket OTP.
     */
    private val httpClient =
        OkHttpClient.Builder()
            .connectTimeout(
                30,
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
            .build()

    /*
     * WebSocket client.
     *
     * The actual authenticated WebSocket URL is supplied
     * by Deriv's OTP endpoint.
     */
    private val webSocketClient =
        OkHttpClient.Builder()
            .pingInterval(
                20,
                TimeUnit.SECONDS
            )
            .connectTimeout(
                30,
                TimeUnit.SECONDS
            )
            .readTimeout(
                0,
                TimeUnit.MILLISECONDS
            )
            .writeTimeout(
                30,
                TimeUnit.SECONDS
            )
            .build()

    /*
     * Scheduler for:
     *
     * - application heartbeat
     * - automatic recovery
     */
    private val scheduler:
        ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor()

    private var heartbeatFuture:
        ScheduledFuture<*>? = null

    private var reconnectFuture:
        ScheduledFuture<*>? = null

    private var webSocket:
        WebSocket? = null

    private var accessToken:
        String? = null

    private var accountType:
        String =
        "DEMO"

    private var accountId:
        String? = null

    private var connected =
        false

    private var connecting =
        false

    /*
     * TRUE only when the user deliberately
     * disconnected.
     */
    private var manuallyDisconnected =
        false

    private var reconnectScheduled =
        false

    /*
     * Connect after OAuth succeeds.
     */
    fun connectWithAccessToken(
        token: String,
        accountType: String
    ) {

        val cleanToken =
            token.trim()

        if (
            cleanToken.isEmpty()
        ) {

            callback.onError(
                "Deriv access token is empty."
            )

            return
        }

        accessToken =
            cleanToken

        this.accountType =
            if (
                accountType.equals(
                    "REAL",
                    ignoreCase = true
                )
            ) {

                "REAL"

            } else {

                "DEMO"
            }

        manuallyDisconnected =
            false

        cancelReconnect()

        stopHeartbeat()

        closeSocketOnly()

        callback.onConnecting(
            "Connecting to Deriv account..."
        )

        /*
         * First obtain the account belonging to
         * the requested mode.
         */
        loadAccountsAndConnect()
    }

    /*
     * Compatibility method retained because existing
     * MainActivity may call connect(token).
     */
    fun connect(
        token: String
    ) {

        connectWithAccessToken(
            token,
            "DEMO"
        )
    }

    /*
     * Get all Options accounts associated with
     * the OAuth token.
     *
     * Current Deriv endpoint:
     *
     * GET
     * /trading/v1/options/accounts
     */
    private fun loadAccountsAndConnect() {

        val token =
            accessToken

        if (
            token.isNullOrBlank()
        ) {

            callback.onError(
                "No Deriv access token available."
            )

            return
        }

        if (
            manuallyDisconnected
        ) {

            return
        }

        callback.onConnecting(
            "Loading Deriv ${accountType.lowercase()} account..."
        )

        val request =
            Request.Builder()
                .url(
                    ACCOUNTS_URL
                )
                .addHeader(
                    "Authorization",
                    "Bearer $token"
                )
                .get()
                .build()

        httpClient
            .newCall(
                request
            )
            .enqueue(
                object :
                    okhttp3.Callback {

                    override fun onFailure(
                        call: okhttp3.Call,
                        e: java.io.IOException
                    ) {

                        if (
                            manuallyDisconnected
                        ) {

                            return
                        }

                        callback.onError(
                            "Unable to load Deriv accounts: ${
                                e.message
                                    ?: "network error"
                            }"
                        )

                        scheduleReconnect()
                    }

                    override fun onResponse(
                        call: okhttp3.Call,
                        response: Response
                    ) {

                        val body =
                            response.body
                                ?.string()
                                .orEmpty()

                        if (
                            !response.isSuccessful
                        ) {

                            callback.onError(
                                "Deriv account request failed: HTTP ${
                                    response.code
                                }"
                            )

                            scheduleReconnect()

                            return
                        }

                        try {

                            val json =
                                JSONObject(
                                    body
                                )

                            handleAccountsResponse(
                                json
                            )

                        } catch (
                            e: Exception
                        ) {

                            callback.onError(
                                "Invalid Deriv accounts response: ${
                                    e.message
                                        ?: "invalid response"
                                }"
                            )

                            scheduleReconnect()
                        }
                    }
                }
            )
    }

    /*
     * Process the current Deriv account response.
     */
    private fun handleAccountsResponse(
        json: JSONObject
    ) {

        val data =
            json.optJSONArray(
                "data"
            )

        if (
            data == null ||
            data.length() == 0
        ) {

            callback.onError(
                "No Deriv trading accounts were returned."
            )

            return
        }

        var selectedAccount:
            JSONObject? = null

        for (
            index in 0 until data.length()
        ) {

            val item =
                data.optJSONObject(
                    index
                )
                    ?: continue

            val type =
                item.optString(
                    "account_type",
                    ""
                )

            if (
                type.equals(
                    accountType,
                    ignoreCase = true
                )
            ) {

                selectedAccount =
                    item

                break
            }
        }

        if (
            selectedAccount == null
        ) {

            callback.onError(
                "No ${accountType.lowercase()} Deriv account was found."
            )

            return
        }

        val selectedId =
            selectedAccount
                ?.optString(
                    "account_id",
                    ""
                )
                .orEmpty()

        if (
            selectedId.isEmpty()
        ) {

            callback.onError(
                "Deriv returned an account without an account ID."
            )

            return
        }

        accountId =
            selectedId

        callback.onConnecting(
            "Opening authenticated Deriv connection..."
        )

        requestWebSocketOtp(
            selectedId
        )
    }
        /*
     * Request the one-time WebSocket URL from Deriv.
     *
     * The OTP is valid for a short period and is used
     * only once to establish the authenticated socket.
     */
    private fun requestWebSocketOtp(
        selectedAccountId: String
    ) {

        val token =
            accessToken

        if (
            token.isNullOrBlank()
        ) {

            callback.onError(
                "No Deriv access token available for WebSocket authentication."
            )

            return
        }

        val url =
            "$OTP_URL/$selectedAccountId/otp"

        val request =
            Request.Builder()
                .url(
                    url
                )
                .addHeader(
                    "Authorization",
                    "Bearer $token"
                )
                .post(
                    okhttp3.RequestBody.create(
                        null,
                        ByteArray(0)
                    )
                )
                .build()

        httpClient
            .newCall(
                request
            )
            .enqueue(
                object :
                    okhttp3.Callback {

                    override fun onFailure(
                        call: okhttp3.Call,
                        e: java.io.IOException
                    ) {

                        if (
                            manuallyDisconnected
                        ) {

                            return
                        }

                        callback.onError(
                            "Unable to obtain Deriv WebSocket OTP: ${
                                e.message
                                    ?: "network error"
                            }"
                        )

                        scheduleReconnect()
                    }

                    override fun onResponse(
                        call: okhttp3.Call,
                        response: Response
                    ) {

                        val body =
                            response.body
                                ?.string()
                                .orEmpty()

                        if (
                            !response.isSuccessful
                        ) {

                            callback.onError(
                                "Deriv WebSocket authentication failed: HTTP ${
                                    response.code
                                }"
                            )

                            scheduleReconnect()

                            return
                        }

                        try {

                            val json =
                                JSONObject(
                                    body
                                )

                            val websocketUrl =
                                extractWebSocketUrl(
                                    json
                                )

                            if (
                                websocketUrl.isNullOrBlank()
                            ) {

                                callback.onError(
                                    "Deriv did not return a WebSocket URL."
                                )

                                scheduleReconnect()

                                return
                            }

                            openAuthenticatedWebSocket(
                                websocketUrl
                            )

                        } catch (
                            e: Exception
                        ) {

                            callback.onError(
                                "Invalid Deriv OTP response: ${
                                    e.message
                                        ?: "invalid response"
                                }"
                            )

                            scheduleReconnect()
                        }
                    }
                }
            )
    }

    /*
     * Extract the WebSocket URL.
     *
     * Deriv may return the URL in the data object.
     * We check the common response structures rather
     * than assuming only one JSON layout.
     */
    private fun extractWebSocketUrl(
        json: JSONObject
    ): String? {

        val direct =
            json.optString(
                "websocket_url",
                ""
            )

        if (
            direct.isNotBlank()
        ) {

            return direct
        }

        val data =
            json.optJSONObject(
                "data"
            )

        if (
            data != null
        ) {

            val nested =
                data.optString(
                    "websocket_url",
                    ""
                )

            if (
                nested.isNotBlank()
            ) {

                return nested
            }

            val url =
                data.optString(
                    "url",
                    ""
                )

            if (
                url.isNotBlank()
            ) {

                return url
            }

            val websocket =
                data.optString(
                    "websocket",
                    ""
                )

            if (
                websocket.isNotBlank()
            ) {

                return websocket
            }
        }

        return null
    }

    /*
     * Open the authenticated WebSocket returned by
     * Deriv's OTP endpoint.
     */
    private fun openAuthenticatedWebSocket(
        websocketUrl: String
    ) {

        if (
            manuallyDisconnected
        ) {

            return
        }

        connecting =
            true

        connected =
            false

        callback.onConnecting(
            "Connecting to authenticated Deriv WebSocket..."
        )

        val request =
            Request.Builder()
                .url(
                    websocketUrl
                )
                .build()

        webSocket =
            webSocketClient
                .newWebSocket(
                    request,
                    object :
                        WebSocketListener() {

                        override fun onOpen(
                            socket: WebSocket,
                            response: Response
                        ) {

                            if (
                                manuallyDisconnected
                            ) {

                                socket.close(
                                    1000,
                                    "Manual disconnect"
                                )

                                return
                            }

                            connecting =
                                false

                            connected =
                                true

                            reconnectScheduled =
                                false

                            callback.onConnected(
                                accountId
                                    ?: "",
                                accountType
                            )

                            startHeartbeat()

                            requestBalance(
                                socket
                            )
                        }

                        override fun onMessage(
                            socket: WebSocket,
                            text: String
                        ) {

                            handleWebSocketMessage(
                                text
                            )
                        }

                        override fun onClosing(
                            socket: WebSocket,
                            code: Int,
                            reason: String
                        ) {

                            socket.close(
                                code,
                                reason
                            )
                        }

                        override fun onClosed(
                            socket: WebSocket,
                            code: Int,
                            reason: String
                        ) {

                            connected =
                                false

                            connecting =
                                false

                            stopHeartbeat()

                            callback.onDisconnected()

                            if (
                                !manuallyDisconnected
                            ) {

                                scheduleReconnect()
                            }
                        }

                        override fun onFailure(
                            socket: WebSocket,
                            t: Throwable,
                            response: Response?
                        ) {

                            connected =
                                false

                            connecting =
                                false

                            stopHeartbeat()

                            callback.onError(
                                "Deriv WebSocket error: ${
                                    t.message
                                        ?: "connection failed"
                                }"
                            )

                            callback.onDisconnected()

                            if (
                                !manuallyDisconnected
                            ) {

                                scheduleReconnect()
                            }
                        }
                    }
                )
    }

    /*
     * Request current account balance.
     *
     * This is sent only after the authenticated
     * WebSocket has successfully opened.
     */
     fun requestBalance() {

    val socket =
        webSocket

    if (socket == null) {

        callback.onError(
            "Deriv WebSocket is unavailable."
        )

        return
    }

    requestBalance(
        socket
    )
     }
    fun requestBalance(
        socket: WebSocket
    ) {

        val request =
            JSONObject()

        request.put(
            "balance",
            1
        )

        request.put(
            "req_id",
            System.currentTimeMillis()
                .toInt()
        )

        socket.send(
            request.toString()
        )
    }

    /*
     * Process messages coming from Deriv.
     */
    private fun handleWebSocketMessage(
        text: String
    ) {

        try {

            val json =
                JSONObject(
                    text
                )

            /*
             * Balance response.
             */
            if (
                json.optInt(
                    "msg_type",
                    -1
                ) == 1
            ) {

                /*
                 * Some responses identify themselves
                 * through msg_type = balance.
                 *
                 * Keep the explicit object check below
                 * as the primary balance parser.
                 */
            }

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

                return
            }

            /*
             * Error response.
             */
            val error =
                json.optJSONObject(
                    "error"
                )

            if (
                error != null
            ) {

                val message =
                    error.optString(
                        "message",
                        "Unknown Deriv error"
                    )

                callback.onError(
                    message
                )

                return
            }

        } catch (
            e: Exception
        ) {

            /*
             * Do not disconnect merely because a
             * non-critical WebSocket message could
             * not be parsed.
             */
        }
    }

    /*
     * Keep the connection alive.
     *
     * This replaces the previous behaviour where
     * the application could appear disconnected
     * after roughly one minute.
     */
    private fun startHeartbeat() {

        stopHeartbeat()

        heartbeatFuture =
            scheduler.scheduleAtFixedRate(
                {

                    if (
                        connected &&
                        !manuallyDisconnected
                    ) {

                        val socket =
                            webSocket

                        if (
                            socket != null
                        ) {

                            try {

                                val ping =
                                    JSONObject()

                                ping.put(
                                    "ping",
                                    1
                                )

                                ping.put(
                                    "req_id",
                                    System.currentTimeMillis()
                                        .toInt()
                                )

                                socket.send(
                                    ping.toString()
                                )

                            } catch (
                                _: Exception
                            ) {

                                /*
                                 * The WebSocket failure
                                 * callback will handle
                                 * an actual connection loss.
                                 */
                            }
                        }
                    }

                },
                20,
                20,
                TimeUnit.SECONDS
            )
    }

    /*
     * Stop heartbeat timer.
     */
    private fun stopHeartbeat() {

        heartbeatFuture
            ?.cancel(
                true
            )

        heartbeatFuture =
            null
    }
        /*
     * Schedule a reconnect when the connection is lost.
     *
     * Reconnection is automatic unless the user has
     * deliberately disconnected the Deriv account.
     */
    private fun scheduleReconnect() {

        if (
            manuallyDisconnected
        ) {

            return
        }

        if (
            reconnectScheduled
        ) {

            return
        }

        reconnectScheduled =
            true

        reconnectFuture =
            scheduler.schedule(
                {

                    reconnectScheduled =
                        false

                    if (
                        manuallyDisconnected
                    ) {

                        return@schedule
                    }

                    val token =
                        accessToken

                    if (
                        token.isNullOrBlank()
                    ) {

                        callback.onError(
                            "Cannot reconnect: Deriv access token is unavailable."
                        )

                        return@schedule
                    }

                    connectWithAccessToken(
                        token,
                        accountType
                    )

                },
                5,
                TimeUnit.SECONDS
            )
    }

    /*
     * Cancel a pending automatic reconnect.
     */
    private fun cancelReconnect() {

        reconnectFuture
            ?.cancel(
                true
            )

        reconnectFuture =
            null

        reconnectScheduled =
            false
    }

    /*
     * Close only the WebSocket.
     *
     * This does NOT mean the user deliberately
     * disconnected.
     */
    private fun closeSocketOnly() {

        try {

            webSocket
                ?.close(
                    1000,
                    "Reconnecting"
                )

        } catch (
            _: Exception
        ) {
        }

        webSocket =
            null

        connected =
            false

        connecting =
            false
    }

    /*
     * Manual disconnect.
     *
     * Automatic reconnection is deliberately disabled
     * until connectWithAccessToken() is called again.
     */
    fun disconnect() {

        manuallyDisconnected =
            true

        cancelReconnect()

        stopHeartbeat()

        try {

            webSocket
                ?.close(
                    1000,
                    "Manual disconnect"
                )

        } catch (
            _: Exception
        ) {
        }

        webSocket =
            null

        connected =
            false

        connecting =
            false

        accountId =
            null

        callback.onDisconnected()
    }

    /*
     * Return whether the authenticated WebSocket
     * is currently connected.
     */
    fun isConnected(): Boolean {

        return connected
    }

    /*
     * Return whether the client is currently trying
     * to establish a connection.
     */
    fun isConnecting(): Boolean {

        return connecting
    }

    /*
     * Return the currently selected Deriv account ID.
     */
    fun getAccountId(): String? {

        return accountId
    }

    /*
     * Return the current execution account type.
     */
    fun getAccountType(): String {

        return accountType
    }

    /*
     * Return whether the disconnect was deliberately
     * requested by the user.
     */
    fun isManuallyDisconnected(): Boolean {

        return manuallyDisconnected
    }

    /*
     * Change between DEMO and REAL.
     *
     * The current WebSocket must be closed first because
     * the account-specific OTP must be requested again.
     */
    fun switchAccount(
        newAccountType: String
    ) {

        val normalized =
            if (
                newAccountType.equals(
                    "REAL",
                    ignoreCase = true
                )
            ) {

                "REAL"

            } else {

                "DEMO"
            }

        if (
            normalized == accountType &&
            connected
        ) {

            return
        }

        val token =
            accessToken

        if (
            token.isNullOrBlank()
        ) {

            callback.onError(
                "Cannot switch account: Deriv access token is unavailable."
            )

            return
        }

        accountType =
            normalized

        manuallyDisconnected =
            false

        cancelReconnect()

        stopHeartbeat()

        closeSocketOnly()

        callback.onConnecting(
            "Switching to Deriv $accountType account..."
        )

        loadAccountsAndConnect()
    }

    /*
     * Send a raw WebSocket request.
     *
     * This method is retained for the trading layer so
     * existing application code can communicate with
     * the authenticated Deriv WebSocket.
     */
    fun send(
        request: JSONObject
    ): Boolean {

        if (
            !connected
        ) {

            callback.onError(
                "Deriv is not connected."
            )

            return false
        }

        val socket =
            webSocket

        if (
            socket == null
        ) {

            callback.onError(
                "Deriv WebSocket is unavailable."
            )

            return false
        }

        return try {

            socket.send(
                request.toString()
            )

        } catch (
            e: Exception
        ) {

            callback.onError(
                "Unable to send Deriv request: ${
                    e.message
                        ?: "send failed"
                }"
            )

            false
        }
    }

    /*
     * Request a fresh balance manually.
     */
    fun refreshBalance() {

        if (
            !connected
        ) {

            callback.onError(
                "Deriv is not connected."
            )

            return
        }

        webSocket
            ?.let {
                requestBalance(
                    it
                )
            }
    }

    /*
     * Release all resources when the client is
     * permanently no longer required.
     */
    fun destroy() {

        manuallyDisconnected =
            true

        cancelReconnect()

        stopHeartbeat()

        try {

            webSocket
                ?.close(
                    1000,
                    "Client destroyed"
                )

        } catch (
            _: Exception
        ) {
        }

        webSocket =
            null

        connected =
            false

        connecting =
            false

        try {

            scheduler.shutdownNow()

        } catch (
            _: Exception
        ) {
        }

        try {

            httpClient
                .dispatcher
                .executorService
                .shutdown()

        } catch (
            _: Exception
        ) {
        }

        try {

            webSocketClient
                .dispatcher
                .executorService
                .shutdown()

        } catch (
            _: Exception
        ) {
        }
    }
}

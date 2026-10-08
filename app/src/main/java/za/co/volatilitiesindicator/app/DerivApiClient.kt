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

                            val message =
                                formatApiError(
                                    response.code,
                                    body
                                )

                            callback.onError(
                                "Deriv account request failed: $message"
                            )

                            if (
                                response.code != 400 &&
                                response.code != 401 &&
                                response.code != 403
                            ) {

                                scheduleReconnect()
                            }

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
     * Process the response from:
     *
     * GET /trading/v1/options/accounts
     *
     * Deriv may return data as an array or, depending
     * on the response structure, as a single object.
     */
    private fun handleAccountsResponse(
        json: JSONObject
    ) {

        val accounts =
            mutableListOf<JSONObject>()

        val dataArray =
            json.optJSONArray(
                "data"
            )

        if (
            dataArray != null
        ) {

            for (
                index in 0 until dataArray.length()
            ) {

                dataArray
                    .optJSONObject(index)
                    ?.let {
                        accounts.add(it)
                    }
            }

        } else {

            json.optJSONObject(
                "data"
            )?.let {
                accounts.add(it)
            }
        }

        if (
            accounts.isEmpty()
        ) {

            callback.onError(
                "No Deriv trading accounts were returned."
            )

            return
        }

        var selectedAccount:
            JSONObject? = null

        for (
            item in accounts
        ) {

            val type =
                item.optString(
                    "account_type",
                    ""
                ).trim()

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

            val available =
                accounts.joinToString(
                    ", "
                ) {

                    it.optString(
                        "account_type",
                        "unknown"
                    )
                }

            callback.onError(
                "No ${accountType.lowercase()} Deriv account was found. Available account types: $available"
            )

            return
        }

        val selectedId =
            selectedAccount
                ?.optString(
                    "account_id",
                    ""
                )
                ?.trim()
                .orEmpty()

        if (
            selectedId.isEmpty()
        ) {

            callback.onError(
                "Deriv returned a ${accountType.lowercase()} account without an account ID."
            )

            return
        }

        accountId =
            selectedId

        callback.onConnecting(
            "Opening authenticated Deriv ${accountType.lowercase()} connection..."
        )

        requestWebSocketOtp(
            selectedId
        )
    }

    /*
     * Request the short-lived WebSocket OTP.
     *
     * Current endpoint:
     *
     * POST
     * /trading/v1/options/accounts/{accountId}/otp
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

        if (
            manuallyDisconnected
        ) {

            return
        }

        callback.onConnecting(
            "Authenticating Deriv WebSocket..."
        )

        val url =
            "$OTP_URL/$selectedAccountId/otp"

        /*
         * Current Deriv OTP endpoint does not require
         * a JSON request body.
         *
         * FormBody gives OkHttp a valid empty body.
         */
        val requestBody =
            okhttp3.FormBody
                .Builder()
                .build()

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
                    requestBody
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

                            val message =
                                formatApiError(
                                    response.code,
                                    body
                                )

                            callback.onError(
                                "Deriv WebSocket authentication failed: $message"
                            )

                            if (
                                response.code != 400 &&
                                response.code != 401 &&
                                response.code != 403
                            ) {

                                scheduleReconnect()
                            }

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
                                "Invalid Deriv WebSocket authentication response: ${
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
     * Deriv currently returns the authenticated WebSocket
     * URL in:
     *
     * data.url
     *
     * Other valid response shapes are also accepted.
     */
    private fun extractWebSocketUrl(
        json: JSONObject
    ): String? {

        val directUrl =
            json.optString(
                "websocket_url",
                ""
            ).trim()

        if (
            directUrl.startsWith(
                "ws://"
            ) ||
            directUrl.startsWith(
                "wss://"
            )
        ) {

            return directUrl
        }

        val data =
            json.opt(
                "data"
            )

        if (
            data is JSONObject
        ) {

            val url =
                data.optString(
                    "url",
                    ""
                ).trim()

            if (
                url.startsWith(
                    "ws://"
                ) ||
                url.startsWith(
                    "wss://"
                )
            ) {

                return url
            }

            val websocketUrl =
                data.optString(
                    "websocket_url",
                    ""
                ).trim()

            if (
                websocketUrl.startsWith(
                    "ws://"
                ) ||
                websocketUrl.startsWith(
                    "wss://"
                )
            ) {

                return websocketUrl
            }

            val websocket =
                data.optString(
                    "websocket",
                    ""
                ).trim()

            if (
                websocket.startsWith(
                    "ws://"
                ) ||
                websocket.startsWith(
                    "wss://"
                )
            ) {

                return websocket
            }
        }

        if (
            data is String
        ) {

            val value =
                data.trim()

            if (
                value.startsWith(
                    "ws://"
                ) ||
                value.startsWith(
                    "wss://"
                )
            ) {

                return value
            }
        }

        return null
    }
        /*
     * Open the authenticated Deriv WebSocket.
     *
     * The URL comes directly from Deriv's OTP response.
     */
    private fun openAuthenticatedWebSocket(
        websocketUrl: String
    ) {

        if (
            manuallyDisconnected
        ) {

            return
        }

        callback.onConnecting(
            "Connecting to authenticated Deriv ${accountType.lowercase()} WebSocket..."
        )

        /*
         * Replace the current socket.
         *
         * closeSocketOnly() sets webSocket to null.
         * This is important because callbacks from the old
         * socket must not affect the new connection.
         */
        closeSocketOnly()

        val request =
            Request.Builder()
                .url(
                    websocketUrl
                )
                .build()

        val newSocket =
            webSocketClient.newWebSocket(
                request,
                object :
                    WebSocketListener() {

                    override fun onOpen(
                        socket: WebSocket,
                        response: Response
                    ) {

                        /*
                         * Ignore an obsolete socket.
                         */
                        if (
                            webSocket !== socket
                        ) {

                            return
                        }

                        connected =
                            true

                        connecting =
                            false

                        reconnectScheduled =
                            false

                        callback.onConnected(
                            accountId.orEmpty(),
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

                        /*
                         * Ignore messages from an old socket.
                         */
                        if (
                            webSocket !== socket
                        ) {

                            return
                        }

                        handleWebSocketMessage(
                            text
                        )
                    }

                    override fun onClosing(
                        socket: WebSocket,
                        code: Int,
                        reason: String
                    ) {

                        /*
                         * Ignore closing events from an
                         * obsolete socket.
                         */
                        if (
                            webSocket !== socket
                        ) {

                            return
                        }

                        connected =
                            false

                        stopHeartbeat()
                    }

                    override fun onClosed(
                        socket: WebSocket,
                        code: Int,
                        reason: String
                    ) {

                        /*
                         * IMPORTANT:
                         *
                         * When a connection is replaced,
                         * the old socket can finish closing after
                         * the new socket has already been created.
                         *
                         * Never let that old callback disconnect
                         * the new connection.
                         */
                        if (
                            webSocket !== socket
                        ) {

                            return
                        }

                        connected =
                            false

                        connecting =
                            false

                        stopHeartbeat()

                        webSocket =
                            null

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

                        /*
                         * Ignore failures belonging to an
                         * obsolete WebSocket.
                         */
                        if (
                            webSocket !== socket
                        ) {

                            return
                        }

                        connected =
                            false

                        connecting =
                            false

                        stopHeartbeat()

                        webSocket =
                            null

                        val message =
                            t.message
                                ?.takeIf {
                                    it.isNotBlank()
                                }
                                ?: "WebSocket connection failed"

                        callback.onError(
                            "Deriv WebSocket error: $message"
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

        webSocket =
            newSocket
    }

    /*
     * Process messages received from Deriv.
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
             * Deriv API errors arrive inside:
             *
             * {
             *     "error": {
             *         "code": "...",
             *         "message": "..."
             *     }
             * }
             */
            if (
                json.has(
                    "error"
                )
            ) {

                val error =
                    json.optJSONObject(
                        "error"
                    )

                val message =
                    error
                        ?.optString(
                            "message",
                            ""
                        )
                        ?.takeIf {
                            it.isNotBlank()
                        }
                        ?: "Unknown Deriv WebSocket error"

                callback.onError(
                    "Deriv: $message"
                )

                return
            }

            /*
             * Balance response.
             */
            if (
                json.optString(
                    "msg_type",
                    ""
                ).equals(
                    "balance",
                    ignoreCase = true
                )
            ) {

                handleBalanceMessage(
                    json
                )

                return
            }

            /*
             * Fallback:
             *
             * Some Deriv responses can contain a balance
             * without relying on msg_type.
             */
            if (
                json.has(
                    "balance"
                )
            ) {

                handleBalanceMessage(
                    json
                )
            }

        } catch (
            e: Exception
        ) {

            callback.onError(
                "Invalid Deriv WebSocket message: ${
                    e.message
                        ?: "invalid message"
                }"
            )
        }
    }

    /*
     * Extract balance and currency from the Deriv
     * balance response.
     */
    private fun handleBalanceMessage(
        json: JSONObject
    ) {

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
                ).trim()

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
         * Fallback for a direct numeric balance.
         */
        val directBalance =
            json.optDouble(
                "balance",
                Double.NaN
            )

        if (
            !directBalance.isNaN()
        ) {

            val currency =
                json.optString(
                    "currency",
                    ""
                ).trim()

            callback.onBalance(
                directBalance,
                currency
            )
        }
    }

    /*
     * Request the current balance from Deriv.
     */
    private fun requestBalance(
        socket: WebSocket
    ) {

        if (
            !connected
        ) {

            return
        }

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
     * Heartbeat.
     *
     * The authenticated WebSocket is kept alive by
     * periodically requesting balance.
     */
    private fun startHeartbeat() {

        stopHeartbeat()

        heartbeatFuture =
            scheduler.scheduleAtFixedRate(
                {

                    try {

                        val socket =
                            webSocket

                        if (
                            socket != null &&
                            connected &&
                            !manuallyDisconnected
                        ) {

                            requestBalance(
                                socket
                            )
                        }

                    } catch (
                        _: Exception
                    ) {
                        /*
                         * WebSocket callbacks handle actual
                         * connection failures.
                         */
                    }

                },
                20,
                20,
                TimeUnit.SECONDS
            )
    }

    /*
     * Stop heartbeat.
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
     * Schedule automatic reconnection.
     *
     * Reconnection is deliberately delayed so the app does
     * not continuously hammer the Deriv API when a connection
     * is unavailable.
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

                        return@schedule
                    }

                    callback.onConnecting(
                        "Reconnecting to Deriv ${accountType.lowercase()}..."
                    )

                    loadAccountsAndConnect()

                },
                5,
                TimeUnit.SECONDS
            )
    }

    /*
     * Cancel any pending automatic reconnect.
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
     * Close only the current WebSocket.
     *
     * This does NOT mean the user deliberately disconnected.
     *
     * Therefore automatic reconnection remains possible.
     */
    private fun closeSocketOnly() {

        val socket =
            webSocket

        webSocket =
            null

        connected =
            false

        connecting =
            false

        stopHeartbeat()

        if (
            socket != null
        ) {

            try {

                socket.close(
                    1000,
                    "Replacing connection"
                )

            } catch (
                _: Exception
            ) {
                /*
                 * Nothing else is required.
                 *
                 * The stale-socket checks in onClosed()
                 * and onFailure() prevent the old socket
                 * from affecting a replacement connection.
                 */
            }
        }
    }

    /*
     * Deliberately disconnect from Deriv.
     *
     * This is the only normal path that sets
     * manuallyDisconnected = true.
     *
     * Automatic reconnect will therefore stop.
     */
    fun disconnect() {

        manuallyDisconnected =
            true

        cancelReconnect()

        stopHeartbeat()

        val socket =
            webSocket

        webSocket =
            null

        connected =
            false

        connecting =
            false

        accountId =
            null

        if (
            socket != null
        ) {

            try {

                socket.close(
                    1000,
                    "User disconnected"
                )

            } catch (
                _: Exception
            ) {
                /*
                 * Socket is already being discarded.
                 */
            }
        }

        callback.onDisconnected()
    }

    /*
     * Change between DEMO and REAL using the same
     * OAuth access token.
     *
     * The requested account is resolved again through
     * the current accounts endpoint.
     */
    fun switchAccount(
        mode: String
    ) {

        val normalizedMode =
            if (
                mode.equals(
                    "REAL",
                    ignoreCase = true
                )
            ) {

                "REAL"

            } else {

                "DEMO"
            }

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

        accountType =
            normalizedMode

        manuallyDisconnected =
            false

        cancelReconnect()

        stopHeartbeat()

        closeSocketOnly()

        callback.onConnecting(
            "Switching to Deriv ${normalizedMode.lowercase()} account..."
        )

        loadAccountsAndConnect()
    }

    /*
     * Returns whether the authenticated WebSocket
     * is currently connected.
     */
    fun isConnected(): Boolean {

        return connected &&
            webSocket != null
    }

    /*
     * Returns the currently selected account type.
     */
    fun getAccountType(): String {

        return accountType
    }

    /*
     * Returns the currently selected account ID.
     */
    fun getAccountId(): String {

        return accountId.orEmpty()
    }

    /*
     * Clear the current authentication state.
     *
     * Used when the application logs the user out.
     */
    fun clearAuthentication() {

        disconnect()

        accessToken =
            null

        accountId =
            null

        accountType =
            "DEMO"
    }

    /*
     * Convert the Deriv REST error response into a useful
     * message for the application.
     *
     * Current Deriv format:
     *
     * {
     *     "errors": [
     *         {
     *             "status": 401,
     *             "code": "Unauthorized",
     *             "message": "..."
     *         }
     *     ]
     * }
     */
    private fun formatApiError(
        statusCode: Int,
        body: String
    ): String {

        if (
            body.isBlank()
        ) {

            return "HTTP $statusCode"
        }

        try {

            val json =
                JSONObject(
                    body
                )

            /*
             * Current Deriv error format.
             */
            val errors =
                json.optJSONArray(
                    "errors"
                )

            if (
                errors != null &&
                errors.length() > 0
            ) {

                val messages =
                    mutableListOf<String>()

                for (
                    index in 0 until errors.length()
                ) {

                    val error =
                        errors.optJSONObject(
                            index
                        )

                    if (
                        error != null
                    ) {

                        val code =
                            error.optString(
                                "code",
                                ""
                            ).trim()

                        val message =
                            error.optString(
                                "message",
                                ""
                            ).trim()

                        when {

                            code.isNotEmpty() &&
                                message.isNotEmpty() -> {

                                messages.add(
                                    "$code: $message"
                                )
                            }

                            message.isNotEmpty() -> {

                                messages.add(
                                    message
                                )
                            }

                            code.isNotEmpty() -> {

                                messages.add(
                                    code
                                )
                            }
                        }
                    }
                }

                if (
                    messages.isNotEmpty()
                ) {

                    return messages.joinToString(
                        "; "
                    )
                }
            }

            /*
             * Legacy/single error object.
             */
            val errorObject =
                json.optJSONObject(
                    "error"
                )

            if (
                errorObject != null
            ) {

                val code =
                    errorObject.optString(
                        "code",
                        ""
                    ).trim()

                val message =
                    errorObject.optString(
                        "message",
                        ""
                    ).trim()

                if (
                    code.isNotEmpty() &&
                    message.isNotEmpty()
                ) {

                    return "$code: $message"
                }

                if (
                    message.isNotEmpty()
                ) {

                    return message
                }

                if (
                    code.isNotEmpty()
                ) {

                    return code
                }
            }

            /*
             * Some responses may simply contain "message".
             */
            val message =
                json.optString(
                    "message",
                    ""
                ).trim()

            if (
                message.isNotEmpty()
            ) {

                return message
            }

        } catch (
            _: Exception
        ) {
            /*
             * Fall through to HTTP status below.
             */
        }

        return "HTTP $statusCode"
    }

    /*
     * PKCE CODE VERIFIER
     *
     * Generates a URL-safe random verifier for
     * the OAuth authorization flow.
     */
    companion object OAuthPkce {

        private const val PKCE_LENGTH =
            64

        fun createCodeVerifier(): String {

            val secureRandom =
                java.security.SecureRandom()

            val bytes =
                ByteArray(
                    PKCE_LENGTH
                )

            secureRandom.nextBytes(
                bytes
            )

            return android.util.Base64
                .encodeToString(
                    bytes,
                    android.util.Base64.URL_SAFE or
                        android.util.Base64.NO_WRAP or
                        android.util.Base64.NO_PADDING
                )
                .take(
                    128
                )
        }

        fun createCodeChallenge(
            verifier: String
        ): String {

            val digest =
                java.security.MessageDigest
                    .getInstance(
                        "SHA-256"
                    )

            val hash =
                digest.digest(
                    verifier.toByteArray(
                        Charsets.US_ASCII
                    )
                )

            return android.util.Base64
                .encodeToString(
                    hash,
                    android.util.Base64.URL_SAFE or
                        android.util.Base64.NO_WRAP or
                        android.util.Base64.NO_PADDING
                )
        }
    }
}

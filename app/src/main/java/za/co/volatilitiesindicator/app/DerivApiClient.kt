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

        data class Candle(
        val epoch: Long,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double
    )

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

        fun onTick(
            symbol: String,
            quote: Double,
            epoch: Long
        )

        fun onCandles(
            symbol: String,
            candles: List<Candle>
        ) {
            // Optional callback for historical candle data.
        }

        fun onMarketDataError(symbol: String, message: String) {
            // Optional callback for public market-data errors.
        }

        fun onError(message: String)
        
                fun onTradeData(json: JSONObject) {
            // Optional callback for open trades and trade history.
                }

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
    "34fz9hcgpdwSzNCVeUgMR"

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

    private var marketDataSocket: WebSocket? = null
    private var marketDataEndpointIndex = 0
    private var marketDataAttempt = 0
    private var marketDataTimeout: java.util.concurrent.ScheduledFuture<*>? = null
    private val marketDataEndpoints = listOf(
        "wss://ws.binaryws.com/websockets/v3?app_id=1089",
        "wss://ws.binaryws.com/websockets/v3",
        "wss://api.derivws.com/trading/v1/options/ws/public"
    )

private var marketDataRequestId = 1000
private val candleRequestSymbols = java.util.concurrent.ConcurrentHashMap<Int, String>()

private val candleIndexes = listOf(
    "R_10",
    "R_25",
    "R_50",
    "R_75",
    "R_100"
)

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

                            val message =
                                formatApiError(
                                    response.code,
                                    body
                                )

                            callback.onError(
                                "Deriv account request failed: $message"
                            )

                            /*
                             * Do not continuously retry invalid/expired
                             * credentials.
                             */
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
     * Process the current Deriv account response.
     */
    private fun handleAccountsResponse(
        json: JSONObject
    ) {

        /*
         * Deriv returns the account collection in data.
         *
         * Depending on the API response/version, data can be
         * an array or a single object. Handle both safely.
         */

        val accounts = mutableListOf<JSONObject>()

        val dataArray =
            json.optJSONArray("data")

        if (dataArray != null) {

            for (
                index in 0 until dataArray.length()
            ) {

                dataArray.optJSONObject(index)
                    ?.let { accounts.add(it) }
            }

        } else {

            json.optJSONObject("data")
                ?.let { accounts.add(it) }
        }

        if (accounts.isEmpty()) {

            callback.onError(
                "No Deriv trading accounts were returned."
            )

            return
        }

        var selectedAccount: JSONObject? = null

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

        if (selectedAccount == null) {

            val available =
                accounts.joinToString(", ") {
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

        if (selectedId.isEmpty()) {

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
                    okhttp3.FormBody.Builder()
                        .build()
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

                            val message =
                                formatApiError(
                                    response.code,
                                    body
                                )

                            callback.onError(
                                "Deriv WebSocket authentication failed: $message"
                            )

                            /*
                             * Authentication/account errors should not
                             * hammer the API with automatic retries.
                             */
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
            ).trim()

        if (
            direct.isNotEmpty()
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

            val url =
                data.optString(
                    "url",
                    ""
                ).trim()

            if (
                url.isNotEmpty()
            ) {

                return url
            }

            val websocketUrl =
                data.optString(
                    "websocket_url",
                    ""
                ).trim()

            if (
                websocketUrl.isNotEmpty()
            ) {
                return websocketUrl
            }

            val websocket =
                data.optString(
                    "websocket",
                    ""
                ).trim()
                if (
                websocket.isNotEmpty()
            ) {

                return websocket
            }
        }

        /*
         * Keep a fallback for an unusual response where
         * data itself is represented as a string URL.
         */
        val dataString =
            json.optString(
                "data",
                ""
            ).trim()

        if (
            dataString.startsWith("ws://") ||
            dataString.startsWith("wss://")
        ) {

            return dataString
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
                            subscribeToLiveTicks()

                            startHeartbeat()

                            requestBalance(
                                socket
                            )
                                                        /*
                             * Immediately request the current
                             * account balance after connection.
                             */
                        }

                        override fun onMessage(
                            socket: WebSocket,
                            text: String
                        ) {

                            /*
                             * Ignore messages from an obsolete
                             * WebSocket.
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
                        }

                        override fun onClosed(
                            socket: WebSocket,
                            code: Int,
                            reason: String
                        ) {

                            /*
                             * IMPORTANT:
                             *
                             * An old socket can finish closing after
                             * a replacement socket has already been
                             * created.
                             *
                             * Never let that old callback disconnect
                             * the new socket.
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
                             * Ignore failures from an obsolete socket.
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

        /*
         * Keep the newly created socket as the active socket.
         */
        webSocket =
            webSocket
    }

    /*
     * Process messages received from Deriv.
     */
     // =============================================================
// LIVE INDEX PRICE SUBSCRIPTIONS
// =============================================================

private val liveIndexes = listOf(
    "R_10",
    "R_25",
    "R_50",
    "R_75",
    "R_100"
)

fun subscribeToLiveTicks() {

    if (!connected) {
        callback.onError(
            "Cannot subscribe to prices: Deriv is not connected."
        )
        return
    }

    for (symbol in liveIndexes) {

        val request = JSONObject().apply {
            put("ticks", symbol)
            put("subscribe", 1)
        }

        send(request)
    }
}
// =============================================================
// HISTORICAL CANDLE CONNECTION
// =============================================================

fun requestHistoricalCandles() {
    if (marketDataSocket != null) return
    connectMarketDataEndpoint()
}

private fun connectMarketDataEndpoint() {
    if (marketDataSocket != null) return

    val endpoint = marketDataEndpoints[marketDataEndpointIndex]
    android.util.Log.i("DerivCandles", "Connecting market data endpoint ${marketDataEndpointIndex + 1}/${marketDataEndpoints.size}: $endpoint")
    val request = Request.Builder().url(endpoint).build()

    val socket = webSocketClient.newWebSocket(request, object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (marketDataSocket != null && marketDataSocket !== webSocket) {
                webSocket.close(1000, "Superseded market-data socket")
                return
            }
            marketDataSocket = webSocket
            marketDataAttempt = 0
            marketDataTimeout?.cancel(false)
            marketDataTimeout = scheduler.schedule({
                if (marketDataSocket === webSocket) {
                    android.util.Log.e("DerivCandles", "Market-data endpoint opened but did not complete candle loading within 20 seconds: $endpoint")
                    try { webSocket.close(1011, "Market-data response timeout") } catch (_: Exception) {}
                }
            }, 20, java.util.concurrent.TimeUnit.SECONDS)

            for (symbol in candleIndexes) {
                val requestId = marketDataRequestId++
                candleRequestSymbols[requestId] = symbol
                val message = JSONObject().apply {
                    put("ticks_history", symbol)
                    put("count", 1000)
                    put("end", "latest")
                    put("style", "candles")
                    put("granularity", 3600)
                    put("req_id", requestId)
                }
                webSocket.send(message.toString())
            }
            for (symbol in candleIndexes) {
                val tickRequest = JSONObject().apply {
                    put("ticks", symbol)
                    put("subscribe", 1)
                    put("req_id", marketDataRequestId++)
                }
                webSocket.send(tickRequest.toString())
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val json = JSONObject(text)
                val apiError = json.optJSONObject("error")
                if (apiError != null) {
                    val failedReqId = json.optInt("req_id", -1)
                    val failedSymbol = json.optJSONObject("echo_req")?.optString("ticks_history", "")
                        ?.takeIf { it in candleIndexes }
                        ?: candleRequestSymbols.remove(failedReqId).orEmpty()
                    val message = apiError.optString("message", "Deriv market-data request failed")
                    android.util.Log.e("DerivCandles", "Endpoint $endpoint returned API error: ${apiError.optString("code")} - $message")
                    if (failedSymbol in candleIndexes) callback.onMarketDataError(failedSymbol, message)
                    // An open socket with API errors is not proof that this endpoint supports v3 candle requests.
                    if (failedSymbol in candleIndexes && marketDataEndpointIndex < marketDataEndpoints.lastIndex) {
                        try { webSocket.close(1011, "Endpoint does not support candle request") } catch (_: Exception) {}
                    }
                    return
                }

                val tick = json.optJSONObject("tick")
                if (tick != null) {
                    val tickSymbol = tick.optString("symbol", "")
                    val quote = tick.optDouble("quote", Double.NaN)
                    val epoch = tick.optLong("epoch", 0L)
                    if (tickSymbol in candleIndexes && quote.isFinite()) callback.onTick(tickSymbol, quote, epoch)
                }

                val echoRequest = json.optJSONObject("echo_req")
                val requestId = json.optInt("req_id", -1)
                val symbol = echoRequest?.optString("ticks_history", "")?.takeIf { it in candleIndexes }
                    ?: candleRequestSymbols.remove(requestId).orEmpty()
                if (symbol !in candleIndexes) return

                val candlesJson = json.optJSONArray("candles") ?: json.optJSONObject("history")?.optJSONArray("candles")
                if (candlesJson == null) {
                    callback.onMarketDataError(symbol, "Deriv returned no H1 candles for $symbol.")
                    return
                }
                val candles = mutableListOf<Candle>()
                for (i in 0 until candlesJson.length()) {
                    val item = candlesJson.optJSONObject(i) ?: continue
                    val epoch = item.optLong("epoch", 0L)
                    val open = item.optDouble("open", Double.NaN)
                    val high = item.optDouble("high", Double.NaN)
                    val low = item.optDouble("low", Double.NaN)
                    val close = item.optDouble("close", Double.NaN)
                    if (epoch > 0L && open.isFinite() && high.isFinite() && low.isFinite() && close.isFinite()) {
                        candles.add(Candle(epoch = epoch, open = open, high = high, low = low, close = close))
                    }
                }
                if (candles.isNotEmpty()) {
                    candleRequestSymbols.remove(requestId)
                    callback.onCandles(symbol, candles.sortedBy { it.epoch })
                    marketDataTimeout?.cancel(false)
                    marketDataTimeout = null
                } else {
                    callback.onMarketDataError(symbol, "Deriv returned a candle response, but no valid candles could be read.")
                }
            } catch (e: Exception) {
                android.util.Log.e("DerivCandles", "Candle response parsing failed: ${e.message}; response=$text", e)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            android.util.Log.e("DerivCandles", "Market-data endpoint failed: $endpoint; HTTP=${response?.code}; ${t.message}")
            if (marketDataSocket === webSocket) marketDataSocket = null
            marketDataTimeout?.cancel(false)
            marketDataTimeout = null
            candleRequestSymbols.clear()
            if (marketDataAttempt < marketDataEndpoints.size - 1) {
                marketDataAttempt++
                marketDataEndpointIndex = (marketDataEndpointIndex + 1) % marketDataEndpoints.size
                scheduler.schedule({ connectMarketDataEndpoint() }, 700, java.util.concurrent.TimeUnit.MILLISECONDS)
            } else {
                marketDataAttempt = 0
                for (symbol in candleIndexes) callback.onMarketDataError(symbol, "All configured market-data endpoints failed. Last HTTP=${response?.code ?: "unknown"}; ${t.message ?: "network error"}")
                // Cycle to the first endpoint for the next explicit refresh/retry.
                marketDataEndpointIndex = 0
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (marketDataSocket === webSocket) marketDataSocket = null
            marketDataTimeout?.cancel(false)
            marketDataTimeout = null
            if (code == 1011 && marketDataAttempt < marketDataEndpoints.size - 1) {
                marketDataAttempt++
                marketDataEndpointIndex = (marketDataEndpointIndex + 1) % marketDataEndpoints.size
                scheduler.schedule({ connectMarketDataEndpoint() }, 700, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        }
    })
    // Keep the pending socket reference so repeated calls don't create parallel connections.
    marketDataSocket = socket
}

// =============================================================
// REFRESH HISTORICAL CANDLES
// =============================================================

fun refreshHistoricalCandles() {

    val socket = marketDataSocket

    if (socket == null) {
        requestHistoricalCandles()
        return
    }

    for (symbol in candleIndexes) {

        val requestId = marketDataRequestId++
        candleRequestSymbols[requestId] = symbol

        val message = JSONObject().apply {
            put("ticks_history", symbol)
            put("count", 1000)
            put("end", "latest")
            put("style", "candles")
            put("granularity", 3600)
            put("req_id", requestId)
        }

        val sent = socket.send(message.toString())

        if (!sent) {
            android.util.Log.e(
                "DerivCandles",
                "Failed to request refreshed candles for $symbol"
            )
        }
    }

    android.util.Log.d(
        "DerivCandles",
        "Historical candle refresh requested for all indexes"
    )
}
    private fun handleWebSocketMessage(
        text: String
    ) {

        try {

            val json =
                JSONObject(
                    text
                )
                android.util.Log.d(
    "DerivLiveFeed",
    "WebSocket response: $text"
)
// =============================================================
// PROCESS LIVE TICK RESPONSES
// =============================================================

val tick = json.optJSONObject("tick")

if (tick != null) {

    val symbol = tick.optString("symbol", "")
    val quote = tick.optDouble("quote", Double.NaN)
    val epoch = tick.optLong("epoch", 0L)

    if (
        symbol in liveIndexes &&
        !quote.isNaN() &&
        quote.isFinite()
    ) {
        callback.onTick(
            symbol,
            quote,
            epoch
        )
    }
}
            val tradeMessageType = json.optString("msg_type", "")

            if (
                tradeMessageType == "portfolio" ||
                tradeMessageType == "profit_table" ||
                tradeMessageType == "proposal_open_contract"
            ) {
                callback.onTradeData(json)
            }
            /*
             * Deriv WebSocket error.
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
             * Fallback for responses containing balance
             * without the expected msg_type.
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
     * Process the balance response.
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
         * Fallback if balance is returned directly.
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
     * Internal balance request used by the active
     * WebSocket callbacks and heartbeat.
     */
    private fun requestBalance(
        socket: WebSocket
    ) {

        if (
            !connected
        ) {

            return
        }

        try {

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

        } catch (
            e: Exception
        ) {

            callback.onError(
                "Unable to request Deriv balance: ${
                    e.message
                        ?: "request failed"
                }"
            )
        }
    }

    /*
     * PUBLIC balance refresh.
     *
     * MainActivity calls this method without supplying
     * a WebSocket.
     *
     * This is deliberately PUBLIC.
     */
    fun requestBalance() {

        if (
            !connected
        ) {

            callback.onError(
                "Deriv is not connected."
            )

            return
        }

        val socket =
            webSocket

        if (
            socket == null
        ) {

            callback.onError(
                "Deriv WebSocket is unavailable."
            )

            return
        }

        requestBalance(
            socket
        )
    }

    /*
     * Start the application heartbeat.
     */
    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatFuture = scheduler.scheduleAtFixedRate(
            {
                try {
                    if (manuallyDisconnected || !connected) {
                        return@scheduleAtFixedRate
                    }
                    val socket = webSocket ?: return@scheduleAtFixedRate
                    requestBalance(socket)
                } catch (_: Exception) {
                    // WebSocket callbacks report connection failures.
                }
            },
            20,
            20,
            TimeUnit.SECONDS
            )
    }

    /*
     * Stop the application heartbeat.
     */
    private fun stopHeartbeat() {
        heartbeatFuture?.cancel(true)
        heartbeatFuture = null
    }

    /*
     * Format an HTTP error returned by Deriv.
     *
     * Current Deriv REST errors use:
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

        return try {

            val json =
                JSONObject(
                    body
                )

            val errors =
                json.optJSONArray(
                    "errors"
                )

            if (
                errors != null &&
                errors.length() > 0
            ) {

                val first =
                    errors.optJSONObject(
                        0
                    )

                if (
                    first != null
                ) {

                    val code =
                        first.optString(
                            "code",
                            ""
                        )

                    val message =
                        first.optString(
                            "message",
                            ""
                        )

                    when {

                        code.isNotBlank() &&
                            message.isNotBlank() ->
                            "$code: $message"

                        message.isNotBlank() ->
                            message

                        code.isNotBlank() ->
                            code

                        else ->
                            "HTTP $statusCode"
                    }

                } else {

                    "HTTP $statusCode"
                }

            } else {

                val error =
                    json.optJSONObject(
                        "error"
                    )

                if (
                    error != null
                ) {

                    val code =
                        error.optString(
                            "code",
                            ""
                        )

                    val message =
                        error.optString(
                            "message",
                            ""
                        )

                    when {

                        code.isNotBlank() &&
                            message.isNotBlank() ->
                            "$code: $message"

                        message.isNotBlank() ->
                            message

                        code.isNotBlank() ->
                            code

                        else ->
                            "HTTP $statusCode"
                    }

                } else {

                    val message =
                        json.optString(
                            "message",
                            ""
                        )

                    if (
                        message.isNotBlank()
                    ) {

                        message

                    } else {

                        "HTTP $statusCode"
                    }
                }
            }

        } catch (
            _: Exception
        ) {

            "HTTP $statusCode"
        }
    }

    /*
     * Schedule an automatic reconnect.
     *
     * Automatic reconnection is disabled when the user
     * deliberately disconnects.
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
     * Close only the current WebSocket.
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

        try { marketDataSocket?.close(1000, "Client disconnected") } catch (_: Exception) {}
        marketDataSocket = null
        marketDataTimeout?.cancel(false)
        marketDataTimeout = null

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
try {
    marketDataSocket?.close(1000, "Manual disconnect")
} catch (_: Exception) {
}

marketDataSocket = null

marketDataTimeout?.cancel(false)
marketDataTimeout = null

candleRequestSymbols.clear()
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
     * The account-specific OTP must be requested again.
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
     * Retained for the existing trading layer.
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
     fun requestTradeHistory() {
    val request = JSONObject()
        .put("profit_table", 1)
        .put("limit", 50)
        .put("sort", "DESC")
        .put("description", 1)

    send(request)
     }
         fun requestOpenTrades() {
        val request = JSONObject()
            .put("portfolio", 1)

        send(request)
         }
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
     * Release all resources when the client is permanently
     * no longer required.
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

        try { marketDataSocket?.close(1000, "Client destroyed") } catch (_: Exception) {}
        marketDataSocket = null
        marketDataTimeout?.cancel(false)
        marketDataTimeout = null

        connected =
            false

        connecting =
            false

        accountId =
            null

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

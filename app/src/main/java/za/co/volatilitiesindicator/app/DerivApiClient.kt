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
            // Legacy H1 history callback; no longer used for entry qualification.
        }

        fun onDailyCandles(symbol: String, candles: List<Candle>) {
            // Daily high/low reference levels for entry qualification.
        }

        fun onM15Candles(symbol: String, candles: List<Candle>) {
            // Closed M15 reversal, MSS and CHOCH qualification.
        }

        fun onM5Candles(
            symbol: String,
            candles: List<Candle>
        ) {
            // M5 trailing-stop confirmation data.
        }

        fun onMarketDataError(symbol: String, message: String) {
            // Optional callback for public market-data errors.
        }

        fun onCandleFeedError(symbol: String, timeframe: String, message: String) {
            // Optional callback for Daily, M15 and M5 feed-specific errors.
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
            .retryOnConnectionFailure(true)
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
            .retryOnConnectionFailure(true)
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

    @Volatile
    private var lastAuthenticatedMessageAt: Long = 0L

    private var marketDataSocket: WebSocket? = null
    private var marketDataTimeout: ScheduledFuture<*>? = null
    private var marketDataReconnectFuture: ScheduledFuture<*>? = null
    @Volatile private var marketDataReconnectAttempt: Int = 0

private var marketDataRequestId = 1000
private val candleRequestSymbols = java.util.concurrent.ConcurrentHashMap<Int, String>()
private val m5CandleRequestSymbols = java.util.concurrent.ConcurrentHashMap<Int, String>()
private val dailyCandleRequestSymbols = java.util.concurrent.ConcurrentHashMap<Int, String>()
private val m15CandleRequestSymbols = java.util.concurrent.ConcurrentHashMap<Int, String>()

private val candleIndexes = listOf(
    "R_10",
    "R_25",
    "R_50",
    "R_75",
    "R_100"
)

    private var accessToken:
        String? = null

    // Invalidates in-flight HTTP/OTP/WebSocket callbacks when accounts are switched.
    @Volatile
    private var connectionGeneration: Long = 0L

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
    private var reconnectAttempt = 0

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

        val generation = ++connectionGeneration
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
        loadAccountsAndConnect(generation)
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
    private fun loadAccountsAndConnect(generation: Long) {
        if (generation != connectionGeneration || manuallyDisconnected) return

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
                        if (generation != connectionGeneration || manuallyDisconnected) return
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
                        if (generation != connectionGeneration || manuallyDisconnected) {
                            response.close()
                            return
                        }
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
                                json,
                                generation
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
        json: JSONObject,
        generation: Long
    ) {
        if (generation != connectionGeneration || manuallyDisconnected) return

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
            selectedId,
            generation
        )
    }

    private fun requestWebSocketOtp(
        selectedAccountId: String,
        generation: Long
    ) {
        if (generation != connectionGeneration || manuallyDisconnected) return

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
                        if (generation != connectionGeneration || manuallyDisconnected) return

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
                        if (generation != connectionGeneration || manuallyDisconnected) {
                            response.close()
                            return
                        }
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
                                websocketUrl,
                                generation
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
        websocketUrl: String,
        generation: Long
    ) {

        if (generation != connectionGeneration || manuallyDisconnected) return

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

                            if (generation != connectionGeneration || webSocket !== socket || manuallyDisconnected) {
                                socket.close(1000, "Obsolete account session")
                                return
                            }

                            connecting =
                                false

                            connected =
                                true
                            lastAuthenticatedMessageAt = System.currentTimeMillis()

                            reconnectScheduled =
                                false
                            reconnectAttempt = 0

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

                            // Market history uses Deriv's public data socket; account trading
                            // remains isolated on this authenticated socket.
                            requestHistoricalCandles()

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

                            lastAuthenticatedMessageAt = System.currentTimeMillis()
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
// HISTORICAL CANDLES — DEDICATED OFFICIAL PUBLIC MARKET-DATA SOCKET
// Account authentication and trade execution remain on the authenticated socket.
// Deriv documents market-data requests such as ticks_history on the public socket.
// =============================================================

private fun sendCandleHistoryRequest(
    socket: WebSocket,
    symbol: String,
    timeframe: String,
    count: Int,
    granularity: Int
) {
    val requestId = marketDataRequestId++
    when (timeframe) {
        "H1" -> candleRequestSymbols[requestId] = symbol
        "DAILY" -> dailyCandleRequestSymbols[requestId] = symbol
        "M15" -> m15CandleRequestSymbols[requestId] = symbol
        "M5" -> m5CandleRequestSymbols[requestId] = symbol
    }

    val request = JSONObject().apply {
        put("ticks_history", symbol)
        put("count", count)
        put("end", "latest")
        put("style", "candles")
        put("granularity", granularity)
        put("req_id", requestId)
    }

    val sent = try { socket.send(request.toString()) } catch (_: Exception) { false }
    if (!sent) {
        when (timeframe) {
            "H1" -> candleRequestSymbols.remove(requestId)
            "DAILY" -> dailyCandleRequestSymbols.remove(requestId)
            "M15" -> m15CandleRequestSymbols.remove(requestId)
            "M5" -> m5CandleRequestSymbols.remove(requestId)
        }
        val message = "$timeframe candle request could not be sent for $symbol on the public market-data connection."
        android.util.Log.e("DerivCandles", message)
        if (timeframe == "H1") callback.onMarketDataError(symbol, message)
        else if (timeframe != "M5") callback.onCandleFeedError(symbol, timeframe, message)
    } else {
        android.util.Log.d("DerivCandles", "Sent $timeframe candle request for $symbol; req_id=$requestId")
    }
}

fun requestHistoricalCandles() {
    if (manuallyDisconnected) return
    if (marketDataSocket != null) return
    if (marketDataReconnectFuture?.isDone == false) return

    val endpoint = "wss://ws.derivws.com/websockets/v3?app_id=1089"
    android.util.Log.i("DerivCandles", "Connecting to Deriv classic public market-data WebSocket: $endpoint")
    val request = Request.Builder().url(endpoint).build()

    val socket = webSocketClient.newWebSocket(request, object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (marketDataSocket != null && marketDataSocket !== webSocket) {
                webSocket.close(1000, "Superseded market-data socket")
                return
            }
            marketDataSocket = webSocket
            marketDataReconnectAttempt = 0
            marketDataReconnectFuture?.cancel(false)
            marketDataReconnectFuture = null
            marketDataTimeout?.cancel(false)

            // Clear only stale request registrations from an earlier public-socket session.
            candleRequestSymbols.clear()
            dailyCandleRequestSymbols.clear()
            m15CandleRequestSymbols.clear()
            m5CandleRequestSymbols.clear()

            for (symbol in candleIndexes) {
                sendCandleHistoryRequest(webSocket, symbol, "H1", 1000, 3600)
                sendCandleHistoryRequest(webSocket, symbol, "DAILY", 3, 86400)
                sendCandleHistoryRequest(webSocket, symbol, "M15", 300, 900)
                sendCandleHistoryRequest(webSocket, symbol, "M5", 100, 300)
            }

            marketDataTimeout = scheduler.schedule({
                if (marketDataSocket === webSocket) {
                    val pendingDaily = dailyCandleRequestSymbols.entries.toList()
                    val pendingM15 = m15CandleRequestSymbols.entries.toList()
                    val pendingH1 = candleRequestSymbols.entries.toList()
                    val pendingM5 = m5CandleRequestSymbols.entries.toList()

                    pendingDaily.forEach { (id, symbol) ->
                        dailyCandleRequestSymbols.remove(id)
                        callback.onCandleFeedError(symbol, "DAILY", "No Daily candle response received from Deriv within 25 seconds.")
                    }
                    pendingM15.forEach { (id, symbol) ->
                        m15CandleRequestSymbols.remove(id)
                        callback.onCandleFeedError(symbol, "M15", "No M15 candle response received from Deriv within 25 seconds.")
                    }
                    pendingH1.forEach { (id, symbol) ->
                        candleRequestSymbols.remove(id)
                        callback.onMarketDataError(symbol, "No H1 candle response received from Deriv within 25 seconds.")
                    }
                    pendingM5.forEach { (id, _) -> m5CandleRequestSymbols.remove(id) }
                    android.util.Log.e("DerivCandles", "Public candle response timeout: Daily=${pendingDaily.size}, M15=${pendingM15.size}, H1=${pendingH1.size}, M5=${pendingM5.size}")
                }
            }, 25, TimeUnit.SECONDS)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (marketDataSocket !== webSocket) return
            try {
                val json = JSONObject(text)
                android.util.Log.d("DerivCandles", "Public market-data response: $text")
                val handled = handleCandleHistoryMessage(json)
                if (!handled) {
                    val apiError = json.optJSONObject("error")
                    if (apiError != null) {
                        val message = "${apiError.optString("code", "DerivError")}: ${apiError.optString("message", "Market-data request rejected") }"
                        android.util.Log.e("DerivCandles", "Unmatched public market-data error: $message")
                        candleIndexes.forEach { symbol ->
                            callback.onCandleFeedError(symbol, "DAILY", "Deriv market-data error: $message")
                            callback.onCandleFeedError(symbol, "M15", "Deriv market-data error: $message")
                        }
                    }
                }
            } catch (e: Exception) {
                val message = "Unable to parse Deriv public market-data response: ${e.message ?: "invalid response"}"
                android.util.Log.e("DerivCandles", message, e)
                callback.onError(message)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (marketDataSocket !== webSocket) return
            android.util.Log.w("DerivCandles", "Public market-data socket closing: code=$code reason=$reason")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (marketDataSocket !== webSocket) return
            marketDataSocket = null
            marketDataTimeout?.cancel(false)
            marketDataTimeout = null
            val message = "Deriv public market-data connection closed ($code): ${reason.ifBlank { "no reason supplied" }}"
            reportPendingCandleFailure(message)
            scheduleMarketDataReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (marketDataSocket != null && marketDataSocket !== webSocket) return
            marketDataSocket = null
            marketDataTimeout?.cancel(false)
            marketDataTimeout = null
            val httpCode = response?.code
            val message = if (httpCode != null) {
                "Deriv public market-data WebSocket handshake failed: HTTP $httpCode. ${t.message ?: "No additional detail"}"
            } else {
                "Deriv public market-data WebSocket failed: ${t.message ?: "network error"}"
            }
            android.util.Log.e("DerivCandles", message, t)
            // Keep public market-data failures on the candle-feed diagnostics;
            // do not report them as authenticated account connection failures.
            reportPendingCandleFailure(message)
            candleIndexes.forEach { symbol ->
                callback.onCandleFeedError(symbol, "DAILY", message)
                callback.onCandleFeedError(symbol, "M15", message)
            }
            scheduleMarketDataReconnect()
        }
    })

    // OkHttp may call onOpen before newWebSocket() returns. Store the returned socket
    // only when no newer socket has already replaced it.
    if (marketDataSocket == null) marketDataSocket = socket
}

private fun reportPendingCandleFailure(message: String) {
    dailyCandleRequestSymbols.values.toSet().forEach { callback.onCandleFeedError(it, "DAILY", message) }
    m15CandleRequestSymbols.values.toSet().forEach { callback.onCandleFeedError(it, "M15", message) }
    candleRequestSymbols.values.toSet().forEach { callback.onMarketDataError(it, message) }
    dailyCandleRequestSymbols.clear()
    m15CandleRequestSymbols.clear()
    candleRequestSymbols.clear()
    m5CandleRequestSymbols.clear()
}

private fun scheduleMarketDataReconnect() {
    if (manuallyDisconnected || !connected || marketDataReconnectFuture?.isDone == false) return
    if (marketDataReconnectAttempt >= 5) {
        callback.onError("Deriv market-data reconnect stopped after 5 attempts. Account connection remains separate and active.")
        return
    }
    marketDataReconnectAttempt++
    val delaySeconds = (marketDataReconnectAttempt * 3L).coerceAtMost(15L)
    marketDataReconnectFuture = scheduler.schedule({
        marketDataReconnectFuture = null
        if (!manuallyDisconnected && connected && marketDataSocket == null) requestHistoricalCandles()
    }, delaySeconds, TimeUnit.SECONDS)
}

fun refreshHistoricalCandles() {
    val socket = marketDataSocket
    if (socket == null) {
        requestHistoricalCandles()
        return
    }
    for (symbol in candleIndexes) {
        sendCandleHistoryRequest(socket, symbol, "H1", 1000, 3600)
        sendCandleHistoryRequest(socket, symbol, "DAILY", 3, 86400)
        sendCandleHistoryRequest(socket, symbol, "M15", 300, 900)
    }
}

fun refreshM5Candles() {
    val socket = marketDataSocket
    if (socket == null) {
        requestHistoricalCandles()
        return
    }
    for (symbol in candleIndexes) {
        sendCandleHistoryRequest(socket, symbol, "M5", 100, 300)
    }
}

/** Process only responses that match one of this client's registered candle requests. */
private fun handleCandleHistoryMessage(json: JSONObject): Boolean {
    val echo = json.optJSONObject("echo_req")
    val requestId = json.optInt("req_id", echo?.optInt("req_id", -1) ?: -1)

    val h1Symbol = if (requestId >= 0) candleRequestSymbols.remove(requestId) else null
    val dailySymbol = if (requestId >= 0) dailyCandleRequestSymbols.remove(requestId) else null
    val m15Symbol = if (requestId >= 0) m15CandleRequestSymbols.remove(requestId) else null
    val m5Symbol = if (requestId >= 0) m5CandleRequestSymbols.remove(requestId) else null

    val echoSymbol = echo?.optString("ticks_history", "")?.takeIf { it in candleIndexes }
    val granularity = echo?.optInt("granularity", -1) ?: -1
    val symbol = h1Symbol ?: dailySymbol ?: m15Symbol ?: m5Symbol ?: echoSymbol ?: return false
    val timeframe = when {
        dailySymbol != null || granularity == 86400 -> "DAILY"
        m15Symbol != null || granularity == 900 -> "M15"
        m5Symbol != null || granularity == 300 -> "M5"
        else -> "H1"
    }

    // Some Deriv responses may omit the top-level req_id. In that case use
    // echo_req.symbol + granularity to retire the matching pending request.
    if (requestId < 0 || (h1Symbol == null && dailySymbol == null && m15Symbol == null && m5Symbol == null)) {
        val registry = when (timeframe) {
            "DAILY" -> dailyCandleRequestSymbols
            "M15" -> m15CandleRequestSymbols
            "M5" -> m5CandleRequestSymbols
            else -> candleRequestSymbols
        }
        val matchingId = registry.entries.firstOrNull { it.value == symbol }?.key
        if (matchingId != null) registry.remove(matchingId)
    }

    val apiError = json.optJSONObject("error")
    if (apiError != null) {
        val code = apiError.optString("code", "DerivError")
        val message = apiError.optString("message", "Deriv rejected the $timeframe candle request.")
        val detail = "$code: $message"
        android.util.Log.e("DerivCandles", "$timeframe request failed for $symbol (req_id=$requestId): $detail")
        if (timeframe == "H1") callback.onMarketDataError(symbol, detail)
        else if (timeframe != "M5") callback.onCandleFeedError(symbol, timeframe, detail)
        finishCandleTimeoutIfComplete()
        return true
    }

    val array = json.optJSONArray("candles")
        ?: json.optJSONObject("history")?.optJSONArray("candles")
    if (array == null) {
        // Do not mistake unrelated account/tick responses for candle responses.
        if (echoSymbol == null || granularity !in setOf(300, 900, 3600, 86400)) return false
        val message = "Deriv response for $timeframe $symbol contained no candle array."
        android.util.Log.e("DerivCandles", message)
        if (timeframe == "H1") callback.onMarketDataError(symbol, message)
        else if (timeframe != "M5") callback.onCandleFeedError(symbol, timeframe, message)
        finishCandleTimeoutIfComplete()
        return true
    }

    val candles = ArrayList<Candle>(array.length())
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        val epoch = item.optLong("epoch", 0L)
        val open = item.optDouble("open", Double.NaN)
        val high = item.optDouble("high", Double.NaN)
        val low = item.optDouble("low", Double.NaN)
        val close = item.optDouble("close", Double.NaN)
        if (epoch > 0L && open.isFinite() && high.isFinite() && low.isFinite() && close.isFinite()) {
            candles.add(Candle(epoch, open, high, low, close))
        }
    }

    if (candles.isEmpty()) {
        val message = "Deriv returned an empty or invalid $timeframe candle list for $symbol."
        android.util.Log.e("DerivCandles", message)
        if (timeframe == "H1") callback.onMarketDataError(symbol, message)
        else if (timeframe != "M5") callback.onCandleFeedError(symbol, timeframe, message)
    } else {
        val sorted = candles.sortedBy { it.epoch }
        android.util.Log.i("DerivCandles", "Received ${sorted.size} $timeframe candles for $symbol (req_id=$requestId)")
        when (timeframe) {
            "DAILY" -> callback.onDailyCandles(symbol, sorted)
            "M15" -> callback.onM15Candles(symbol, sorted)
            "M5" -> callback.onM5Candles(symbol, sorted)
            "H1" -> callback.onCandles(symbol, sorted)
        }
    }

    finishCandleTimeoutIfComplete()
    return true
}

private fun finishCandleTimeoutIfComplete() {
    if (candleRequestSymbols.isEmpty() && dailyCandleRequestSymbols.isEmpty() &&
        m15CandleRequestSymbols.isEmpty() && m5CandleRequestSymbols.isEmpty()) {
        marketDataTimeout?.cancel(false)
        marketDataTimeout = null
    }
}

    private fun handleWebSocketMessage(
        text: String
    ) {

        try {

            val json =
                JSONObject(
                    text
                )
            // Route candle responses from this same authenticated WebSocket.
            // The handler ignores unrelated account, proposal, and tick messages.
            handleCandleHistoryMessage(json)
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

            /*
             * Forward every response needed by the trade execution state machine.
             * Proposal and buy responses are essential: MainActivity matches their
             * req_id values to pending orders and advances PROPOSAL -> BUY -> OPEN.
             * Deriv error responses also need to reach that handler so it can release
             * the correct index lock instead of waiting for the proposal timeout.
             */
            if (
                tradeMessageType == "portfolio" ||
                tradeMessageType == "profit_table" ||
                tradeMessageType == "proposal_open_contract" ||
                tradeMessageType == "proposal" ||
                tradeMessageType == "buy" ||
                json.has("error")
            ) {
                callback.onTradeData(json)
            }

            /*
             * Deriv WebSocket error. Forwarding above allows the pending trade
 Forwarding above allows the pending trade
             * request to be resolved; this callback preserves general error reporting.
             */
            if (json.has("error")) {
                val error = json.optJSONObject("error")
                val message = error
                ?.optString("message", "")
                    ?.takeIf { it.isNotBlank() }
                    ?: "Unknown Deriv WebSocket error"

                callback.onError("Deriv: $message")
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
                    val socket = webSocket ?: run {
                        scheduleReconnect()
                        return@scheduleAtFixedRate
                    }
                    val now = System.currentTimeMillis()
                    val lastMessage = lastAuthenticatedMessageAt
                    if (lastMessage > 0L && now - lastMessage > 65_000L) {
                        // A half-open network can leave OkHttp's socket reference present without useful traffic.
                        connected = false
                        connecting = false
                        stopHeartbeat()
                        try { socket.cancel() } catch (_: Exception) {}
                        if (webSocket === socket) webSocket = null
                        callback.onDisconnected()
                        scheduleReconnect()
                        return@scheduleAtFixedRate
                    }
                    requestBalance(socket)
                } catch (_: Exception) {
                    // The WebSocket listener or stale-connection watchdog will retry safely.
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

        reconnectScheduled = true
        // Back off repeated resets instead of hammering the OAuth/account/WebSocket flow.
        // Delay sequence: 5s, 10s, 20s, then 30s maximum. Successful connection resets it.
        val delaySeconds = minOf(30L, 5L shl reconnectAttempt.coerceAtMost(3))
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(4)
        reconnectFuture = scheduler.schedule({
            reconnectScheduled = false
            if (manuallyDisconnected) return@schedule

            val token = accessToken
            if (token.isNullOrBlank()) {
                callback.onError("Cannot reconnect: Deriv access token is unavailable.")
                return@schedule
            }

            connectWithAccessToken(token, accountType)
        }, delaySeconds, TimeUnit.SECONDS)
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

        try { marketDataSocket?.close(1000, "Account reconnect") } catch (_: Exception) {}
        marketDataSocket = null
        marketDataTimeout?.cancel(false)
        marketDataTimeout = null
        marketDataReconnectFuture?.cancel(false)
        marketDataReconnectFuture = null
        candleRequestSymbols.clear()
        dailyCandleRequestSymbols.clear()
        m15CandleRequestSymbols.clear()
        m5CandleRequestSymbols.clear()

        connected =
            false
        lastAuthenticatedMessageAt = 0L

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

        ++connectionGeneration
        manuallyDisconnected =
            true
        accessToken = null

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

        webSocket = null
        try { marketDataSocket?.close(1000, "Manual disconnect") } catch (_: Exception) {}
        marketDataSocket = null
        marketDataTimeout?.cancel(false)
        marketDataTimeout = null
        marketDataReconnectFuture?.cancel(false)
        marketDataReconnectFuture = null
        candleRequestSymbols.clear()
        dailyCandleRequestSymbols.clear()
        m15CandleRequestSymbols.clear()
        m5CandleRequestSymbols.clear()
        connected =
            false

        connecting =
            false

        accountId =
            null

        callback.onDisconnected()
    }

    /**
     * Disconnect only the authenticated trading account.
     * The public market-data socket remains active for the index monitor.
     * In-flight account HTTP/OTP/WebSocket callbacks are invalidated.
     */
    fun disconnectAccount() {
        ++connectionGeneration
        accessToken = null
        cancelReconnect()
        stopHeartbeat()

        try {
            webSocket?.close(1000, "Platform user changed")
        } catch (_: Exception) {
        }
        webSocket = null
        connected = false
        connecting = false
        accountId = null
        callback.onDisconnected()
    }

    /*
     * Return whether the authenticated WebSocket
     * is currently connected.
     */
    fun isConnected(): Boolean {

        return connected
    }

    /**
     * Recheck the authenticated Deriv connection when the Activity returns to
     * the foreground. A socket can appear connected after Android has suspended
     * the app/network, so also check the age of the last authenticated message.
     * This does not reconnect after an intentional manual disconnect.
     */
    fun ensureConnectedOnResume(token: String, selectedAccountType: String) {
        if (manuallyDisconnected || token.isBlank() || connecting) return

        val socket = webSocket
        val lastMessageAt = lastAuthenticatedMessageAt
        val stale = lastMessageAt > 0L &&
            System.currentTimeMillis() - lastMessageAt > 65_000L

        if (connected && socket != null && !stale) return

        // Resume recovery should not wait behind a delayed retry scheduled while
        // the app was backgrounded. connectWithAccessToken cancels old work safely.
        cancelReconnect()
        connectWithAccessToken(token, selectedAccountType)
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

        val generation = ++connectionGeneration
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

        loadAccountsAndConnect(generation)
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
    if (!connected) {
        callback.onError("Cannot request open trades: Deriv is not connected.")
        return
    }

    val request = JSONObject()
        .put("portfolio", 1)

    send(request)

    val liveRequest = JSONObject()
        .put("proposal_open_contract", 1)
        .put("subscribe", 1)

    send(liveRequest)
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
        marketDataReconnectFuture?.cancel(false)
        marketDataReconnectFuture = null

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

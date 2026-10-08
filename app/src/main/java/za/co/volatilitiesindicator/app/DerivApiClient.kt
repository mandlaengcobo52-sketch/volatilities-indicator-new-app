package za.co.volatilitiesindicator.app

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DerivApiClient(
    private val callback: Callback
) {

    interface Callback {
        fun onConnecting(message: String)
        fun onConnected(accountId: String, accountType: String)
        fun onBalance(balance: Double, currency: String)
        fun onError(message: String)
        fun onDisconnected()
    }

    companion object {

        private const val DERIV_URL =
            "wss://ws.derivws.com/websockets/v3?app_id=1089"

        private const val HEARTBEAT_SECONDS = 20L

        private const val RECONNECT_DELAY_SECONDS = 3L

        /*
         * Existing OAuth client IDs used by the application.
         */
        private const val DEMO_CLIENT_ID =
            "34fz6VSRiNSQnELXjApVH"

        private const val REAL_CLIENT_ID =
            "34fz9hcgpdWwSzNCVeUgMR"

        const val OAUTH_REDIRECT_URI =
            "https://volatilitiesindicator.org.za/accounts/account-callback.php"

        const val OAUTH_SCOPE =
            "trade"

        const val OAUTH_TOKEN_URL =
            "https://auth.deriv.com/oauth2/token"

        /**
         * Existing MainActivity compatibility method.
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
    }

    /*
     * WebSocket client.
     *
     * pingInterval prevents an idle WebSocket from being
     * silently dropped by the HTTP/WebSocket layer.
     */
    private val client =
        OkHttpClient.Builder()
            .pingInterval(
                HEARTBEAT_SECONDS,
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
     * Scheduler used only for:
     *
     * 1. Deriv application heartbeat
     * 2. Unexpected connection recovery
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
        String = "DEMO"

    private var connected =
        false

    private var connecting =
        false

    /*
     * TRUE only when the user/application deliberately
     * requested a disconnect.
     */
    private var manuallyDisconnected =
        false

    private var reconnectScheduled =
        false

    /**
     * Existing MainActivity entry point.
     *
     * This method is deliberately preserved because
     * MainActivity already calls it after OAuth succeeds.
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
            accountType

        /*
         * A new explicit connection request means that
         * automatic reconnect is allowed.
         */
        manuallyDisconnected =
            false

        cancelReconnect()

        stopHeartbeat()

        /*
         * Replace an old socket without sending a
         * false "disconnected" event to the UI.
         */
        closeSocketOnly()

        callback.onConnecting(
            "Connecting to Deriv..."
        )

        openSocket()
    }

    /**
     * Compatibility method.
     */
    fun connect(
        token: String
    ) {

        connectWithAccessToken(
            token,
            "DEMO"
        )
    }

    /**
     * Open the Deriv WebSocket.
     */
    private fun openSocket() {

        if (
            manuallyDisconnected
        ) {
            return
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

        /*
         * Prevent duplicate sockets.
         */
        if (
            connecting ||
            webSocket != null
        ) {
            return
        }

        connecting =
            true

        val request =
            Request.Builder()
                .url(
                    DERIV_URL
                )
                .build()

        webSocket =
            client.newWebSocket(
                request,
                createWebSocketListener(
                    token
                )
            )
    }
        /**
     * Create the WebSocket listener.
     */
    private fun createWebSocketListener(
        token: String
    ): WebSocketListener {

        return object : WebSocketListener() {

            override fun onOpen(
                socket: WebSocket,
                response: Response
            ) {

                connecting =
                    false

                if (
                    manuallyDisconnected
                ) {

                    socket.close(
                        1000,
                        "Manual disconnect"
                    )

                    return
                }

                webSocket =
                    socket

                callback.onConnecting(
                    "Authorizing Deriv account..."
                )

                authorize(
                    socket,
                    token
                )
            }

            override fun onMessage(
                socket: WebSocket,
                text: String
            ) {

                handleMessage(
                    socket,
                    text
                )
            }

            override fun onFailure(
                socket: WebSocket,
                t: Throwable,
                response: Response?
            ) {

                connecting =
                    false

                connected =
                    false

                if (
                    webSocket == socket
                ) {

                    webSocket =
                        null
                }

                stopHeartbeat()

                /*
                 * IMPORTANT:
                 *
                 * This is an unexpected socket failure.
                 * It is NOT treated as a manual disconnect.
                 *
                 * Therefore the client attempts to reconnect.
                 */
                if (
                    !manuallyDisconnected
                ) {

                    scheduleReconnect()
                }
            }

            override fun onClosed(
                socket: WebSocket,
                code: Int,
                reason: String
            ) {

                connecting =
                    false

                connected =
                    false

                if (
                    webSocket == socket
                ) {

                    webSocket =
                        null
                }

                stopHeartbeat()

                /*
                 * A socket can close for network reasons.
                 * Only the explicit manual disconnect should
                 * prevent reconnection.
                 */
                if (
                    !manuallyDisconnected
                ) {

                    scheduleReconnect()
                } else {

                    callback.onDisconnected()
                }
            }
        }
    }

    /**
     * Authorize the Deriv WebSocket using the OAuth token.
     */
    private fun authorize(
        socket: WebSocket,
        token: String
    ) {

        try {

            val request =
                JSONObject()
                    .put(
                        "authorize",
                        token
                    )

            socket.send(
                request.toString()
            )

        } catch (
            e: Exception
        ) {

            callback.onError(
                "Unable to authorize Deriv account: ${
                    e.message
                        ?: "unknown error"
                }"
            )
        }
    }

    /**
     * Handle all incoming Deriv messages.
     */
    private fun handleMessage(
        socket: WebSocket,
        text: String
    ) {

        try {

            val json =
                JSONObject(text)

            /*
             * Deriv API error.
             */
            if (
                json.has("error")
            ) {

                val error =
                    json.optJSONObject(
                        "error"
                    )

                val message =
                    error
                        ?.optString(
                            "message"
                        )
                        ?.takeIf {
                            it.isNotBlank()
                        }
                        ?: "Deriv API error"

                callback.onError(
                    message
                )

                /*
                 * Do not deliberately disconnect here.
                 *
                 * The socket itself determines whether the
                 * connection remains usable.
                 */
                return
            }

            /*
             * Successful authorization.
             */
            if (
                json.has("authorize")
            ) {

                val authorize =
                    json.optJSONObject(
                        "authorize"
                    )

                if (
                    authorize != null
                ) {

                    val accountId =
                        authorize.optString(
                            "loginid",
                            ""
                        )

                    val returnedAccountType =
                        authorize.optString(
                            "account_type",
                            accountType
                        )

                    val balance =
                        authorize.optDouble(
                            "balance",
                            Double.NaN
                        )

                    val currency =
                        authorize.optString(
                            "currency",
                            ""
                        )

                    connected =
                        true

                    connecting =
                        false

                    callback.onConnected(
                        accountId,
                        returnedAccountType
                    )

                    /*
                     * Immediately display the balance returned
                     * by the authorization response.
                     */
                    if (
                        !balance.isNaN()
                    ) {

                        callback.onBalance(
                            balance,
                            currency
                        )
                    }

                    /*
                     * Subscribe to live balance updates.
                     */
                    subscribeToBalance(
                        socket
                    )

                    /*
                     * Start the application heartbeat only
                     * after successful authorization.
                     */
                    startHeartbeat(
                        socket
                    )
                }

                return
            }

            /*
             * Live balance subscription/update.
             */
            if (
                json.has("balance")
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

                return
            }

            /*
             * Application-level ping response.
             */
            if (
                json.has("ping")
            ) {

                return
            }

        } catch (
            e: Exception
        ) {

            callback.onError(
                "Invalid Deriv response: ${
                    e.message
                        ?: "unknown error"
                }"
            )
        }
    }
        /**
     * Subscribe to the live account balance.
     */
    private fun subscribeToBalance(
        socket: WebSocket
    ) {

        if (
            manuallyDisconnected
        ) {
            return
        }

        try {

            val request =
                JSONObject()
                    .put(
                        "balance",
                        1
                    )
                    .put(
                        "subscribe",
                        1
                    )

            socket.send(
                request.toString()
            )

        } catch (
            e: Exception
        ) {

            callback.onError(
                "Unable to subscribe to balance: ${
                    e.message
                        ?: "unknown error"
                }"
            )
        }
    }

    /**
     * Start the application-level heartbeat.
     *
     * OkHttp already sends WebSocket ping frames through
     * pingInterval(). This second heartbeat sends a Deriv
     * API ping so the Deriv session remains active as well.
     */
    private fun startHeartbeat(
        socket: WebSocket
    ) {

        stopHeartbeat()

        heartbeatFuture =
            scheduler.scheduleAtFixedRate(
                {

                    if (
                        manuallyDisconnected ||
                        !connected ||
                        webSocket != socket
                    ) {

                        return@scheduleAtFixedRate
                    }

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

                        /*
                         * Do not manually disconnect here.
                         *
                         * If the socket is actually broken,
                         * OkHttp will trigger onFailure().
                         */
                    }

                },
                HEARTBEAT_SECONDS,
                HEARTBEAT_SECONDS,
                TimeUnit.SECONDS
            )
    }

    /**
     * Stop the heartbeat without disconnecting the
     * WebSocket for any other reason.
     */
    private fun stopHeartbeat() {

        try {

            heartbeatFuture?.cancel(
                false
            )

        } catch (
            _: Exception
        ) {
        }

        heartbeatFuture =
            null
    }

    /**
     * Schedule recovery after an unexpected WebSocket
     * failure.
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

        if (
            accessToken.isNullOrBlank()
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

                    reconnectFuture =
                        null

                    if (
                        manuallyDisconnected
                    ) {
                        return@schedule
                    }

                    callback.onConnecting(
                        "Reconnecting to Deriv..."
                    )

                    openSocket()

                },
                RECONNECT_DELAY_SECONDS,
                TimeUnit.SECONDS
            )
    }

    /**
     * Cancel a pending automatic reconnect.
     */
    private fun cancelReconnect() {

        try {

            reconnectFuture?.cancel(
                false
            )

        } catch (
            _: Exception
        ) {
        }

        reconnectFuture =
            null

        reconnectScheduled =
            false
    }

    /**
     * Close the current WebSocket without telling the
     * application that the user disconnected.
     *
     * Used only when replacing an existing socket.
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

        try {

            socket?.close(
                1000,
                "Replacing connection"
            )

        } catch (
            _: Exception
        ) {
        }
    }
        /**
     * Manually disconnect the Deriv account.
     *
     * This is the deliberate disconnect operation.
     *
     * Automatic socket failures DO NOT call this method.
     */
    fun disconnect() {

        manuallyDisconnected =
            true

        connected =
            false

        connecting =
            false

        cancelReconnect()

        stopHeartbeat()

        accessToken =
            null

        val socket =
            webSocket

        webSocket =
            null

        try {

            socket?.close(
                1000,
                "Manual disconnect"
            )

        } catch (
            _: Exception
        ) {
        }

        callback.onDisconnected()
    }

    /**
     * Check whether the Deriv connection is currently
     * active.
     */
    fun isConnected(): Boolean {

        return connected &&
                webSocket != null &&
                !manuallyDisconnected
    }

    /**
     * Compatibility helper.
     */
    fun hasActiveConnection(): Boolean {

        return isConnected()
    }

    /**
     * Check whether an OAuth token is currently stored.
     */
    fun hasAccessToken(): Boolean {

        return !accessToken.isNullOrBlank()
    }

    /**
     * Send a raw Deriv WebSocket request through the
     * existing connection.
     *
     * This never creates another WebSocket.
     */
    fun send(
        request: JSONObject
    ): Boolean {

        val socket =
            webSocket

        if (
            socket == null ||
            !connected ||
            manuallyDisconnected
        ) {

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
                        ?: "unknown error"
                }"
            )

            false
        }
    }

    /**
     * Request the current balance.
     */
    fun requestBalance(): Boolean {

        val request =
            JSONObject()
                .put(
                    "balance",
                    1
                )
                .put(
                    "subscribe",
                    1
                )

        return send(
            request
        )
    }

    /**
     * Send a Deriv API ping using the existing connection.
     */
    fun ping(): Boolean {

        val request =
            JSONObject()
                .put(
                    "ping",
                    1
                )

        return send(
            request
        )
    }

    /**
     * Called when the application is placed in the
     * background.
     *
     * IMPORTANT:
     * This deliberately does NOTHING that disconnects
     * the Deriv WebSocket.
     */
    fun onAppBackgrounded() {

        /*
         * Do not disconnect.
         *
         * The connection must remain alive.
         */
        if (
            isConnected()
        ) {

            ping()
        }
    }

    /**
     * Called when the application returns to the foreground.
     *
     * If the existing connection is still alive, it is reused.
     * If it was unexpectedly lost, reconnect using the existing
     * OAuth token.
     */
    fun onAppForegrounded() {

        if (
            manuallyDisconnected
        ) {
            return
        }

        if (
            isConnected()
        ) {

            ping()

            return
        }

        if (
            !hasAccessToken()
        ) {
            return
        }

        callback.onConnecting(
            "Reconnecting to Deriv..."
        )

        cancelReconnect()

        openSocket()
    }
        /**
     * Permanently release the Deriv client resources.
     *
     * This should only be used when the application itself
     * is being permanently shut down.
     *
     * It must NOT be called simply because MainActivity
     * enters the background.
     */
    fun shutdown() {

        manuallyDisconnected =
            true

        connected =
            false

        connecting =
            false

        cancelReconnect()

        stopHeartbeat()

        accessToken =
            null

        val socket =
            webSocket

        webSocket =
            null

        try {

            socket?.cancel()

        } catch (
            _: Exception
        ) {
        }

        try {

            scheduler.shutdownNow()

        } catch (
            _: Exception
        ) {
        }

        try {

            client.dispatcher
                .executorService
                .shutdown()

        } catch (
            _: Exception
        ) {
        }

        try {

            client.connectionPool
                .evictAll()

        } catch (
            _: Exception
        ) {
        }
    }

    /**
     * Compatibility alias for existing application code.
     */
    fun close() {

        shutdown()
    }
}

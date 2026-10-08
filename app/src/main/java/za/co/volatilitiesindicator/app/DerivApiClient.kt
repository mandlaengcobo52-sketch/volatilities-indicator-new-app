package za.co.volatilitiesindicator.app

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ScheduledExecutorService
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

        private const val HEARTBEAT_SECONDS = 25L
        private const val RECONNECT_DELAY_SECONDS = 3L
    }

    private val client: OkHttpClient =
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

    private var webSocket: WebSocket? = null

    private var accessToken: String? = null

    private var manuallyDisconnected = false

    private var reconnectScheduled = false

    private var connected = false

    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor()

    /**
     * Connect to Deriv using the supplied OAuth token.
     */
    fun connect(token: String) {

        if (token.isBlank()) {
            callback.onError(
                "Deriv access token is empty."
            )
            return
        }

        accessToken = token

        /*
         * This is an intentional user connection.
         * A later socket failure may therefore reconnect.
         */
        manuallyDisconnected = false

        reconnectScheduled = false

        callback.onConnecting(
            "Connecting to Deriv..."
        )

        openSocket()
    }

    /**
     * Open the WebSocket.
     */
    private fun openSocket() {

        if (manuallyDisconnected) {
            return
        }

        val token = accessToken

        if (token.isNullOrBlank()) {
            callback.onError(
                "No Deriv access token available."
            )
            return
        }

        /*
         * Do not create multiple sockets at the same time.
         */
        if (
            webSocket != null &&
            connected
        ) {
            return
        }

        val request = Request.Builder()
            .url(DERIV_URL)
            .build()

        webSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {

                override fun onOpen(
                    socket: WebSocket,
                    response: Response
                ) {
                    if (manuallyDisconnected) {
                        socket.close(
                            1000,
                            "Manual disconnect"
                        )
                        return
                    }

                    webSocket = socket

                    connected = true

                    reconnectScheduled = false

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
                    connected = false

                    webSocket = null

                    val message =
                        t.message
                            ?.takeIf {
                                it.isNotBlank()
                            }
                            ?: "Unknown error"

                    if (!manuallyDisconnected) {

                        callback.onError(
                            "Deriv connection interrupted: $message"
                        )

                        scheduleReconnect()
                    }
                }

                override fun onClosed(
                    socket: WebSocket,
                    code: Int,
                    reason: String
                ) {
                    connected = false

                    if (webSocket == socket) {
                        webSocket = null
                    }

                    if (manuallyDisconnected) {
                        callback.onDisconnected()
                    } else {
                        scheduleReconnect()
                    }
                }
            }
        )
    }

    /**
     * Authorize the Deriv account.
     */
    private fun authorize(
        socket: WebSocket,
        token: String
    ) {

        try {

            val request = JSONObject()
                .put(
                    "authorize",
                    token
                )

            socket.send(
                request.toString()
            )

        } catch (e: Exception) {

            callback.onError(
                "Unable to authorize Deriv account: ${
                    e.message
                        ?: "unknown error"
                }"
            )
        }
    }
        /**
     * Process messages received from Deriv.
     */
    private fun handleMessage(
        socket: WebSocket,
        text: String
    ) {

        try {

            val json = JSONObject(text)

            /*
             * API error.
             */
            if (json.has("error")) {

                val error =
                    json.optJSONObject("error")

                val message =
                    error
                        ?.optString("message")
                        ?.takeIf {
                            it.isNotBlank()
                        }
                        ?: "Deriv API error"

                callback.onError(
                    message
                )

                return
            }

            /*
             * Authorization response.
             */
            if (json.has("authorize")) {

                val authorize =
                    json.optJSONObject(
                        "authorize"
                    )

                if (authorize != null) {

                    val accountId =
                        authorize.optString(
                            "loginid",
                            ""
                        )

                    val accountType =
                        authorize.optString(
                            "account_type",
                            ""
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

                    connected = true

                    callback.onConnected(
                        accountId,
                        accountType
                    )

                    /*
                     * Send the current balance immediately
                     * when Deriv supplies it during authorization.
                     */
                    if (!balance.isNaN()) {

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
                     * Start the application-level heartbeat.
                     */
                    startHeartbeatIfNeeded(
                        socket
                    )
                }

                return
            }

            /*
             * Balance subscription response.
             */
            if (json.has("balance")) {

                val balanceObject =
                    json.optJSONObject(
                        "balance"
                    )

                if (balanceObject != null) {

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

                    if (!balance.isNaN()) {

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
            if (json.has("ping")) {
                return
            }

        } catch (e: Exception) {

            callback.onError(
                "Invalid Deriv response: ${
                    e.message
                        ?: "unknown error"
                }"
            )
        }
    }

    /**
     * Subscribe to live account balance.
     */
    private fun subscribeToBalance(
        socket: WebSocket
    ) {

        try {

            val request = JSONObject()
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

        } catch (e: Exception) {

            callback.onError(
                "Unable to subscribe to balance: ${
                    e.message
                        ?: "unknown error"
                }"
            )
        }
    }

    /**
     * Keep the application-level Deriv connection alive.
     *
     * OkHttp also sends WebSocket ping frames through
     * pingInterval(). This additional Deriv ping helps prevent
     * the application session from being treated as idle.
     */
    private fun startApplicationHeartbeat(
        socket: WebSocket
    ) {

        scheduler.scheduleAtFixedRate(
            {

                if (
                    !manuallyDisconnected &&
                    connected &&
                    webSocket == socket
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

                    } catch (_: Exception) {

                        /*
                         * The WebSocket failure will be handled
                         * by OkHttp's WebSocketListener.
                         */
                    }
                }

            },
            HEARTBEAT_SECONDS,
            HEARTBEAT_SECONDS,
            TimeUnit.SECONDS
        )
    }

    /**
     * Start heartbeat once the account is connected.
     */
    private fun startHeartbeatIfNeeded(
        socket: WebSocket
    ) {

        /*
         * The scheduler remains available for the lifetime
         * of this client.
         */
        startApplicationHeartbeat(
            socket
        )
    }
        /**
     * Reconnect automatically after an unexpected network
     * or WebSocket failure.
     *
     * This is NOT a deliberate disconnect.
     */
    private fun scheduleReconnect() {

        if (manuallyDisconnected) {
            return
        }

        if (reconnectScheduled) {
            return
        }

        reconnectScheduled = true

        scheduler.schedule(
            {

                reconnectScheduled = false

                if (!manuallyDisconnected) {

                    callback.onConnecting(
                        "Reconnecting to Deriv..."
                    )

                    openSocket()
                }

            },
            RECONNECT_DELAY_SECONDS,
            TimeUnit.SECONDS
        )
    }

    /**
     * MANUAL DISCONNECT ONLY.
     *
     * This method intentionally terminates the Deriv connection.
     *
     * It must only be called when the user deliberately
     * disconnects the account.
     */
    fun disconnect() {

        manuallyDisconnected = true

        connected = false

        reconnectScheduled = false

        accessToken = null

        val socket = webSocket

        webSocket = null

        try {

            socket?.close(
                1000,
                "Manual disconnect"
            )

        } catch (_: Exception) {
        }

        callback.onDisconnected()
    }

    /**
     * Returns true when the Deriv WebSocket is currently
     * connected and has not been manually disconnected.
     */
    fun isConnected(): Boolean {

        return connected &&
                webSocket != null &&
                !manuallyDisconnected
    }

    /**
     * Release resources when this client is permanently
     * destroyed.
     *
     * This must NOT be called merely because the Activity
     * goes into the background.
     */
    fun shutdown() {

        manuallyDisconnected = true

        connected = false

        reconnectScheduled = false

        accessToken = null

        try {

            webSocket?.cancel()

        } catch (_: Exception) {
        }

        webSocket = null

        try {

            scheduler.shutdownNow()

        } catch (_: Exception) {
        }

        try {

            client.dispatcher
                .executorService
                .shutdown()

        } catch (_: Exception) {
        }

        try {

            client.connectionPool
                .evictAll()

        } catch (_: Exception) {
        }
    }
        /*
     * The DerivApiClient intentionally does not disconnect
     * when the Activity is paused, stopped, refreshed, or
     * temporarily moved to the background.
     *
     * The connection remains under the control of the client
     * until disconnect() or shutdown() is explicitly called.
     */

    /**
     * Check whether the client currently has an active
     * WebSocket connection.
     */
    fun hasActiveConnection(): Boolean {

        return webSocket != null &&
                connected &&
                !manuallyDisconnected
    }

    /**
     * Send a request through the existing Deriv connection.
     *
     * This method does not create another connection and does
     * not disconnect the existing connection.
     */
    fun send(request: JSONObject): Boolean {

        val socket = webSocket

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

        } catch (e: Exception) {

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
     *
     * This uses the existing WebSocket connection.
     */
    fun requestBalance(): Boolean {

        val request = JSONObject()
            .put(
                "balance",
                1
            )
            .put(
                "subscribe",
                1
            )

        return send(request)
    }

    /**
     * Send a Deriv ping manually when required.
     *
     * This does NOT create a new connection.
     */
    fun ping(): Boolean {

        val request = JSONObject()
            .put(
                "ping",
                1
            )

        return send(request)
    }
        /**
     * Close the Deriv connection permanently from the
     * application's point of view.
     *
     * This is reserved for permanent cleanup only.
     */
    fun close() {

        shutdown()
    }
}

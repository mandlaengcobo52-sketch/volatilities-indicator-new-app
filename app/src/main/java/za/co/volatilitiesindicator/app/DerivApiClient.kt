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

        fun onError(
            message: String
        )

        fun onDisconnected()
    }

    companion object {

        // =========================================================
        // DERIV API
        // =========================================================

        private const val API_BASE =
            "https://api.derivws.com"

        private const val ACCOUNTS_PATH =
            "/trading/v1/options/accounts"

        // =========================================================
        // DERIV OAUTH CLIENT IDS
        // =========================================================

        private const val DEMO_CLIENT_ID =
            "34fz6VSRiNSQnELXjApVH"

        private const val REAL_CLIENT_ID =
            "34fz9hcgpdwSzNCVeUgMR"

        // =========================================================
        // OAUTH
        // =========================================================

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
        // BUILD OAUTH URL
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
                encode(clientId) +
                "&redirect_uri=" +
                encode(OAUTH_REDIRECT_URI) +
                "&scope=" +
                encode(OAUTH_SCOPE) +
                "&state=" +
                encode(state) +
                "&code_challenge=" +
                encode(codeChallenge) +
                "&code_challenge_method=S256"
        }

        private fun encode(
            value: String
        ): String {

            return URLEncoder
                .encode(
                    value,
                    "UTF-8"
                )
        }
    }

    // =============================================================
    // HTTP CLIENT
    // =============================================================

    private val httpClient =
        OkHttpClient()

    // =============================================================
    // WEBSOCKET
    // =============================================================

    private var webSocket: WebSocket? = null

    // =============================================================
    // AUTHENTICATION
    // =============================================================

    private var accessToken: String = ""

    private var selectedAccountId: String = ""

    private var selectedAccountType: String = ""

    // =============================================================
    // CONNECT USING OAUTH ACCESS TOKEN
    // =============================================================

    fun connectWithAccessToken(
        token: String,
        accountType: String
    ) {

        val cleanToken =
            token.trim()

        if (cleanToken.isEmpty()) {

            callback.onError(
                "OAuth access token is empty."
            )

            return
        }

        accessToken =
            cleanToken

        selectedAccountType =
            accountType.trim()

        callback.onConnecting(
            "Connecting to Deriv..."
        )

        getAccounts(
            selectedAccountType
        )
    }

    // =============================================================
    // GET ACCOUNTS
    // =============================================================

    private fun getAccounts(
        accountType: String
    ) {

        if (accessToken.isEmpty()) {

            callback.onError(
                "No Deriv access token is available."
            )

            return
        }

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
                .addHeader(
                    "Accept",
                    "application/json"
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

                            if (body.isBlank()) {

                                callback.onError(
                                    "Deriv returned an empty account response."
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

                                // =================================================
                                // DATA = ARRAY
                                // =================================================

                                if (
                                    data is JSONArray
                                ) {

                                    for (
                                        i in 0 until
                                            data.length()
                                    ) {

                                        val account =
                                            data.optJSONObject(
                                                i
                                            )
                                                ?: continue

                                        val type =
                                            account.optString(
                                                "account_type",
                                                ""
                                            )

                                        val id =
                                            account.optString(
                                                "account_id",
                                                ""
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

                                // =================================================
                                // DATA = OBJECT
                                // =================================================

                                else if (
                                    data is JSONObject
                                ) {

                                    val type =
                                        data.optString(
                                            "account_type",
                                            ""
                                        )

                                    val id =
                                        data.optString(
                                            "account_id",
                                            ""
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
                                    }
                                }

                                // =================================================
                                // FALLBACK: SOME RESPONSES MAY RETURN
                                // ACCOUNT ID DIRECTLY
                                // =================================================

                                if (
                                    foundAccountId.isEmpty()
                                ) {

                                    val directAccountId =
                                        json.optString(
                                            "account_id",
                                            ""
                                        )

                                    if (
                                        directAccountId.isNotEmpty()
                                    ) {

                                        foundAccountId =
                                            directAccountId
                                    }
                                }

                                // =================================================
                                // ACCOUNT NOT FOUND
                                // =================================================

                                if (
                                    foundAccountId.isEmpty()
                                ) {

                                    callback.onError(
                                        "No $accountType Deriv account was found."
                                    )

                                    return
                                }

                                selectedAccountId =
                                    foundAccountId

                                callback.onConnecting(
                                    "Deriv account found. Establishing live connection..."
                                )

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
    // REQUEST AUTHENTICATED WEBSOCKET URL
    // =============================================================

    private fun requestWebSocketUrl(
        accountType: String
    ) {

        if (
            selectedAccountId.isEmpty()
        ) {

            callback.onError(
                "Deriv account ID is missing."
            )

            return
        }

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
                .addHeader(
                    "Accept",
                    "application/json"
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

                            if (body.isBlank()) {

                                callback.onError(
                                    "Deriv returned an empty OTP response."
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
                                            "url",
                                            ""
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

                                callback.onConnecting(
                                    "Opening live Deriv connection..."
                                )

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
    // OPEN AUTHENTICATED WEBSOCKET
    // =============================================================

    private fun openWebSocket(
        wsUrl: String,
        accountType: String
    ) {

        webSocket?.cancel()

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

                        callback.onConnected(
                            selectedAccountId,
                            accountType
                        )

                        // =============================================
                        // REQUEST LIVE BALANCE
                        // =============================================

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

                            // =========================================
                            // BALANCE
                            // =========================================

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

                            // =========================================
                            // DERIV ERROR
                            // =========================================

                            val errorObject =
                                json.optJSONObject(
                                    "error"
                                )

                            if (
                                errorObject != null
                            ) {

                                val message =
                                    errorObject.optString(
                                        "message",
                                        "Deriv returned an error."
                                    )

                                callback.onError(
                                    message
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

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?
                    ) {

                        callback.onError(
                            "Deriv connection lost: " +
                                (
                                    t.message
                                        ?: "unknown error"
                                )
                        )

                        callback.onDisconnected()
                    }

                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String
                    ) {

                        callback.onDisconnected()
                    }
                }
            )
    }
     =============================================================
    // DISCONNECT
    // =============================================================

    fun disconnect() {

        webSocket?.close(
            1000,
            "User disconnected"
        )

        webSocket =
            null

        accessToken =
            ""

        selectedAccountId =
            ""

        selectedAccountType =
            ""

        callback.onDisconnected()
    }
}


package za.co.volatilitiesindicator.app

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

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
        private const val API_BASE =
            "https://api.derivws.com"

        private const val ACCOUNTS_PATH =
            "/trading/v1/options/accounts"

        private const val APP_ID =
    "34BJFqqY1XNvSPy4PqzNT"
    }

    private val httpClient = OkHttpClient()
    private var webSocket: WebSocket? = null

    private var pat: String = ""
    private var selectedAccountId: String = ""

    fun connect(
        personalAccessToken: String,
        accountType: String
    ) {
        pat = personalAccessToken.trim()

        if (pat.isEmpty()) {
            callback.onError("Personal Access Token is required.")
            return
        }

        callback.onConnecting("Connecting to Deriv...")

        getAccounts(accountType)
    }

    private fun getAccounts(accountType: String) {

        val request = Request.Builder()
            .url("$API_BASE$ACCOUNTS_PATH")
            .get()
            .addHeader(
                "Authorization",
                "Bearer $pat"
            )
            .addHeader(
                "Deriv-App-ID",
                APP_ID
            )
            .build()

        httpClient.newCall(request).enqueue(
            object : okhttp3.Callback {

                override fun onFailure(
                    call: okhttp3.Call,
                    e: java.io.IOException
                ) {
                    callback.onError(
                        "Unable to connect to Deriv: ${e.message}"
                    )
                }

                override fun onResponse(
                    call: okhttp3.Call,
                    response: Response
                ) {

                    response.use {

                        val body = it.body?.string().orEmpty()

                        if (!it.isSuccessful) {
                            callback.onError(
                                "Deriv account request failed (${it.code}): $body"
                            )
                            return
                        }

                        try {

                            val json = JSONObject(body)
                            val data = json.opt("data")

                            var foundAccountId: String? = null

                            if (data is JSONArray) {

                                for (i in 0 until data.length()) {

                                    val account =
                                        data.optJSONObject(i)
                                            ?: continue

                                    val type =
                                        account.optString(
                                            "account_type"
                                        )

                                    if (
                                        type.equals(
                                            accountType,
                                            ignoreCase = true
                                        )
                                    ) {
                                        foundAccountId =
                                            account.optString(
                                                "account_id"
                                            )
                                        break
                                    }
                                }

                            } else if (data is JSONObject) {

                                val type =
                                    data.optString(
                                        "account_type"
                                    )

                                if (
                                    type.equals(
                                        accountType,
                                        ignoreCase = true
                                    )
                                ) {
                                    foundAccountId =
                                        data.optString(
                                            "account_id"
                                        )
                                }
                            }

                            if (
                                foundAccountId.isNullOrBlank()
                            ) {
                                callback.onError(
                                    "No ${accountType.uppercase()} Deriv account was found."
                                )
                                return
                            }

                            selectedAccountId =
                                foundAccountId

                            requestWebSocketUrl(
                                selectedAccountId,
                                accountType
                            )

                        } catch (e: Exception) {

                            callback.onError(
                                "Invalid Deriv account response: ${e.message}"
                            )
                        }
                    }
                }
            }
        )
    }

    private fun requestWebSocketUrl(
        accountId: String,
        accountType: String
    ) {

        callback.onConnecting(
            "Authenticating Deriv account..."
        )

        val request = Request.Builder()
            .url(
                "$API_BASE$ACCOUNTS_PATH/$accountId/otp"
            )
            .post(
                okhttp3.RequestBody.create(
                    null,
                    ByteArray(0)
                )
            )
            .addHeader(
                "Authorization",
                "Bearer $pat"
            )
            .addHeader(
                "Deriv-App-ID",
                APP_ID
            )
            .build()

        httpClient.newCall(request).enqueue(
            object : okhttp3.Callback {

                override fun onFailure(
                    call: okhttp3.Call,
                    e: java.io.IOException
                ) {
                    callback.onError(
                        "Failed to authenticate account: ${e.message}"
                    )
                }

                override fun onResponse(
                    call: okhttp3.Call,
                    response: Response
                ) {

                    response.use {

                        val body = it.body?.string().orEmpty()

                        if (!it.isSuccessful) {
                            callback.onError(
                                "Deriv authentication failed (${it.code}): $body"
                            )
                            return
                        }

                        try {

                            val json =
                                JSONObject(body)

                            val data =
                                json.getJSONObject("data")

                            val wsUrl =
                                data.getString("url")

                            openWebSocket(
                                wsUrl,
                                accountId,
                                accountType
                            )

                        } catch (e: Exception) {

                            callback.onError(
                                "Invalid Deriv authentication response: ${e.message}"
                            )
                        }
                    }
                }
            }
        )
    }

    private fun openWebSocket(
        wsUrl: String,
        accountId: String,
        accountType: String
    ) {

        callback.onConnecting(
            "Opening live Deriv connection..."
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

                        callback.onConnected(
                            accountId,
                            accountType
                        )

                        val balanceRequest =
                            JSONObject().apply {

                                put(
                                    "balance",
                                    1
                                )

                                put(
                                    "subscribe",
                                    1
                                )

                                put(
                                    "req_id",
                                    1
                                )
                            }

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

                            if (
                                json.optString(
                                    "msg_type"
                                ) == "balance"
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
                                            0.0
                                        )

                                    val currency =
                                        balanceObject.optString(
                                            "currency",
                                            ""
                                        )

                                    callback.onBalance(
                                        balance,
                                        currency
                                    )
                                }
                            }

                            if (
                                json.has("error")
                            ) {

                                val error =
                                    json.optJSONObject(
                                        "error"
                                    )

                                callback.onError(
                                    error?.optString(
                                        "message",
                                        "Deriv returned an error."
                                    )
                                        ?: "Deriv returned an error."
                                )
                            }

                        } catch (e: Exception) {

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
                            "Deriv connection lost: ${t.message}"
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

    fun disconnect() {

        webSocket?.close(
            1000,
            "User disconnected"
        )

        webSocket = null
    }
}

                           

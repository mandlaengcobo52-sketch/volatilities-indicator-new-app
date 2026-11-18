package za.co.volatilitiesindicator.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.app.AlertDialog
import android.widget.EditText
import android.widget.ArrayAdapter
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.math.BigDecimal
import java.math.RoundingMode
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.Locale
import java.util.Calendar
import java.util.TimeZone
import org.json.JSONArray

class MainActivity : Activity(), DerivApiClient.Callback {

    // User-approved Volatilities Indicator palette.
    // Gold: #FFEFCF10 | Cyan: #FF14D6EF | Red: #FFE91E1E | Green: #FF6AEB40
    private val ink = Color.rgb(239, 207, 16) // gold text; avoid white typography
    private val muted = Color.rgb(166, 183, 176)
    private val green = Color.rgb(106, 235, 64)       // #FF6AEB40
    private val cyan = Color.rgb(20, 214, 239)        // #FF14D6EF
    private val red = Color.rgb(233, 30, 30)           // #FFE91E1E
    private val amber = Color.rgb(239, 207, 16)        // #FFEFCF10
    private val pageColor = Color.rgb(3, 8, 6)         // deep black-green
    private val cardColor = Color.rgb(7, 22, 17)       // dark green-black
    private val lineColor = Color.rgb(24, 91, 59)      // dark green borders

    private lateinit var content: LinearLayout
    private var selectedTab = "Home"

    private val indexes = listOf("R_10", "R_25", "R_50", "R_75", "R_100")
    private val latestPrices = mutableMapOf<String, Double>()
    private val priceViews = mutableMapOf<String, TextView>()
    private val indexStatusViews = mutableMapOf<String, TextView>()
    private val marketStatusViews = mutableMapOf<String, TextView>()
    private val levelStatusViews = mutableMapOf<String, TextView>()
    private val recoveryStatusViews = mutableMapOf<String, TextView>()

    private data class AppNotification(
        val id: Long,
        val page: String,
        val title: String,
        val message: String,
        val timestamp: Long,
        var read: Boolean = false
    )

    private val appNotifications = mutableListOf<AppNotification>()
    private val notificationPrefs by lazy {
        getSharedPreferences("vi_notifications", MODE_PRIVATE)
    }
    private val notificationChannelId = "vi_app_alerts"
    private val notificationPermissionRequest = 9001

    private var marketFeedConnected = false
    private var accountConnected = false
    private var accountLabel = "Not connected"
    private var accountBalanceLabel = "Balance unavailable"
    private var executionMode = "DEMO"
    private var accountStatusView: TextView? = null
    private var accountBalanceView: TextView? = null
    private var accountIdView: TextView? = null
    private val prefs by lazy { getSharedPreferences("deriv_oauth", MODE_PRIVATE) }
    private val httpClient = OkHttpClient()
    private var oauthState = ""
    private var codeVerifier = ""
    private var feedPillView: TextView? = null

    private var derivService: DerivConnectionService? = null
    private var serviceBound = false
    private val candleRefreshHandler = Handler(Looper.getMainLooper())
    private val candleRefreshTask = object : Runnable {
        override fun run() {
            derivService?.client?.refreshHistoricalCandles()
            candleRefreshHandler.postDelayed(this, 60_000L)
        }
    }

    private data class Position(
        val localId: String,
        val symbol: String,
        val direction: String,
        val role: String,
        val stake: Double,
        val originalId: String = "",
        var contractId: Long = 0L,
        var profit: Double = 0.0,
        var peakProfit: Double = 0.0,
        var protectedProfit: Double = 0.0,
        var status: String = "PROPOSAL",
        var closedAt: Long = 0L,
        var canSell: Boolean = false
    )

    private val positions = linkedMapOf<String, Position>()
    private val pendingRequests = mutableMapOf<Int, String>()
    private val dailyLevels = mutableMapOf<String, Pair<Double, Double>>() // high, low
    private val candlesByIndex = mutableMapOf<String, List<DerivApiClient.Candle>>()
    private val originalTriggered = mutableSetOf<String>()
    private val recoveryWaiting = mutableSetOf<String>()
    private val recoveryMss = mutableMapOf<String, String>()
    private val recoveryChoch = mutableMapOf<String, String>()
    private var requestCounter = 7000
    private var dailyCapital = 0.0
    private var accountCurrency = "USD"
    private var tradingDayKey = ""
    private var tradeStatusView: TextView? = null

    private val tradePrefs by lazy { getSharedPreferences("vi_trade_engine", MODE_PRIVATE) }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? DerivConnectionService.LocalBinder)?.getService()
                ?: return
            derivService = service
            service.attachActivity(this@MainActivity)
            // Public market feed: does not require signing in to a Deriv account.
            service.client.requestHistoricalCandles()
            candleRefreshHandler.removeCallbacks(candleRefreshTask)
            candleRefreshHandler.postDelayed(candleRefreshTask, 60_000L)
            executionMode = if (prefs.getString("oauth_mode", executionMode).equals("REAL", true)) "REAL" else "DEMO"
            val tokenKey = if (executionMode == "REAL") "real_token" else "demo_token"
            val savedToken = prefs.getString(tokenKey, "").orEmpty()
            if (savedToken.isNotBlank()) {
                service.client.ensureConnectedOnResume(savedToken, executionMode)
            }
            runOnUiThread { updateConnectionLabels("Connecting to live market feed…") }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            derivService?.detachActivity(this@MainActivity)
            derivService = null
            serviceBound = false
            marketFeedConnected = false
            runOnUiThread { updateConnectionLabels("Market feed disconnected") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = pageColor
        window.navigationBarColor = pageColor
        window.decorView.systemUiVisibility = 0
        tradingDayKey = tradePrefs.getString("day_key", "").orEmpty()
        dailyCapital = tradePrefs.getFloat("daily_capital", 0f).toDouble()
        loadPositions()
        loadNotifications()
        originalTriggered.addAll(tradePrefs.getStringSet("triggered_$tradingDayKey", emptySet()) ?: emptySet())
        createNotificationChannel()
        requestNotificationPermissionIfNeeded()
        handleNotificationIntent(intent, false)
        render()
        startMarketFeedService()
        handleOAuthCallback(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
        handleOAuthCallback(intent)
    }

    override fun onResume() {
        super.onResume()
        executionMode = if (prefs.getString("oauth_mode", executionMode).equals("REAL", true)) "REAL" else "DEMO"
        val tokenKey = if (executionMode == "REAL") "real_token" else "demo_token"
        val savedToken = prefs.getString(tokenKey, "").orEmpty()
        if (savedToken.isNotBlank() && derivService != null &&
            derivService?.client?.isConnected() != true &&
            derivService?.client?.isConnecting() != true
        ) {
            derivService?.client?.ensureConnectedOnResume(savedToken, executionMode)
        }
    }

    private fun startMarketFeedService() {
        if (serviceBound) return
        val intent = Intent(this, DerivConnectionService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            serviceBound = bindService(intent, serviceConnection, BIND_AUTO_CREATE)
            if (!serviceBound) updateConnectionLabels("Unable to bind market feed service")
        } catch (e: Exception) {
            updateConnectionLabels("Market feed unavailable: ${e.message ?: "service error"}")
        }
    }

    override fun onDestroy() {
        candleRefreshHandler.removeCallbacks(candleRefreshTask)
        derivService?.detachActivity(this)
        if (serviceBound) {
            try { unbindService(serviceConnection) } catch (_: Exception) {}
            serviceBound = false
        }
        super.onDestroy()
    }

    override fun onConnecting(message: String) {
        runOnUiThread {
            accountConnected = false
            accountStatusView?.text = message
            accountStatusView?.setTextColor(amber)
            if (selectedTab == "Accounts") render()
        }
    }

    override fun onConnected(accountId: String, accountType: String) {
        accountConnected = true
        executionMode = if (accountType.equals("REAL", true)) "REAL" else "DEMO"
        accountLabel = "$accountId ($accountType)"
        addNotification("Accounts", "Deriv account connected", "$accountLabel is connected.")
        derivService?.client?.requestOpenTrades()
        runOnUiThread {
            accountIdView?.text = accountLabel
            accountStatusView?.text = "CONNECTED"
            accountStatusView?.setTextColor(green)
            toast("Deriv $executionMode account connected.")
            renderIfHome()
        }
    }

    override fun onBalance(balance: Double, currency: String) {
        accountBalanceLabel = String.format(Locale.US, "%.2f %s", balance, currency)
        accountCurrency = currency
        val today = utcDayKey(System.currentTimeMillis() / 1000L)
        if (tradingDayKey != today) {
            tradingDayKey = today
            dailyCapital = balance
            originalTriggered.clear()
            tradePrefs.edit().putString("day_key", today).putFloat("daily_capital", balance.toFloat()).apply()
        } else if (dailyCapital <= 0.0) {
            dailyCapital = tradePrefs.getFloat("daily_capital", balance.toFloat()).toDouble()
        }
        runOnUiThread {
            accountBalanceView?.text = accountBalanceLabel
            renderIfHome()
        }
    }

    override fun onTick(symbol: String, quote: Double, epoch: Long) {
        if (symbol !in indexes || !quote.isFinite()) return
        latestPrices[symbol] = quote
        marketFeedConnected = true
        if (accountConnected) evaluateOriginalEntries(symbol, quote)
        runOnUiThread {
            feedPillView?.text = "LIVE"
            feedPillView?.setTextColor(green)
            priceViews[symbol]?.text = formatPrice(quote)
            indexStatusViews[symbol]?.apply {
                text = "LIVE"
                setTextColor(green)
            }
            updateMasterDecisionViews(symbol)
        }
    }

    override fun onCandles(symbol: String, candles: List<DerivApiClient.Candle>) {
        if (symbol !in indexes) return
        if (candles.isNotEmpty()) {
            marketFeedConnected = true
            candlesByIndex[symbol] = candles.sortedBy { it.epoch }
            calculatePreviousDailyLevels(symbol, candles)
            evaluateRecoverySignals(symbol, candles)
            val lastClose = candles.last().close
            if (!latestPrices.containsKey(symbol)) latestPrices[symbol] = lastClose
            runOnUiThread {
                feedPillView?.text = "LIVE"
                feedPillView?.setTextColor(green)
                priceViews[symbol]?.text = formatPrice(latestPrices[symbol] ?: lastClose)
                indexStatusViews[symbol]?.apply {
                    text = "LIVE"
                    setTextColor(green)
                }
                updateMasterDecisionViews(symbol)
                updateRecoveryStatusView(symbol)
            }
            if (selectedTab == "Home") runOnUiThread { updateMasterDecisionViews(symbol); updateRecoveryStatusView(symbol) }
        }
    }

    override fun onMarketDataError(symbol: String, message: String) {
        runOnUiThread {
            indexStatusViews[symbol]?.apply {
                text = "FEED ERROR"
                setTextColor(red)
            }
            marketStatusViews[symbol]?.text = message
            addNotification("Home", "Market data error · ${displayIndexName(symbol)}", message)
            updateConnectionLabels("Market data error: $symbol")
        }
    }

    override fun onError(message: String) {
        runOnUiThread {
            addNotification("Accounts", "Deriv connection issue", message)
            if (accountConnected) {
                toast(message)
            } else {
                accountLabel = "Connection failed"
                accountBalanceLabel = message
                accountStatusView?.text = "CONNECTION FAILED"
                accountStatusView?.setTextColor(red)
                accountIdView?.text = accountLabel
                accountBalanceView?.text = message
                if (selectedTab == "Accounts") render()
            }
        }
    }

    override fun onTradeData(json: JSONObject) {
        val reqId = json.optInt("req_id", -1)
        val localId = pendingRequests[reqId]
        val error = json.optJSONObject("error")
        if (error != null) {
            if (localId != null) {
                pendingRequests.remove(reqId)
                positions[localId]?.status = "ERROR: ${error.optString("message", "Deriv request failed")}" 
                runOnUiThread { if (selectedTab == "Trade") render() }
            }
            return
        }
        when (json.optString("msg_type")) {
            "proposal" -> {
                if (localId == null) return
                pendingRequests.remove(reqId)
                val proposal = json.optJSONObject("proposal") ?: return
                val proposalId = proposal.optString("id", "")
                val position = positions[localId] ?: return
                if (proposalId.isBlank()) { position.status = "PROPOSAL FAILED"; return }
                position.status = "BUYING"
                val buyReq = nextRequestId()
                pendingRequests[buyReq] = localId
                val buy = JSONObject().put("buy", proposalId).put("price", roundStake(position.stake)).put("req_id", buyReq)
                derivService?.client?.send(buy)
            }
            "buy" -> {
                if (localId == null) return
                pendingRequests.remove(reqId)
                val buy = json.optJSONObject("buy") ?: return
                val position = positions[localId] ?: return
                position.contractId = buy.optLong("contract_id", 0L)
                if (position.contractId <= 0L) { position.status = "BUY FAILED"; return }
                position.status = "OPEN"
                savePositions()
                addNotification("Trade", "${displayIndexName(position.symbol)} ${position.role.lowercase(Locale.US)} opened", "${position.direction} contract opened. Stake: ${formatMoney(position.stake)}.")
                subscribePosition(position.contractId)
                runOnUiThread { if (selectedTab == "Trade") render() }
            }
            "proposal_open_contract" -> handleOpenContract(json.optJSONObject("proposal_open_contract") ?: return)
            "sell" -> {
                if (localId != null) {
                    pendingRequests.remove(reqId)
                    positions[localId]?.status = "CLOSING MANUALLY"
                    savePositions()
                    runOnUiThread { if (selectedTab == "Trade") render() }
                }
            }
            "portfolio" -> {
                val list = json.optJSONObject("portfolio")?.optJSONArray("contracts") ?: return
                for (i in 0 until list.length()) {
                    val c = list.optJSONObject(i) ?: continue
                    val id = c.optLong("contract_id", 0L)
                    if (id > 0L) subscribePosition(id)
                }
            }
        }
    }

    override fun onDisconnected() {
        val wasConnected = accountConnected
        accountConnected = false
        if (wasConnected) addNotification("Accounts", "Deriv account disconnected", "The authenticated Deriv account connection was lost.")
        accountLabel = "Not connected"
        accountBalanceLabel = "Balance unavailable"
        runOnUiThread {
            accountStatusView?.text = "ACCOUNT NOT CONNECTED"
            accountStatusView?.setTextColor(red)
            renderIfHome()
        }
    }


    private fun handleNotificationIntent(intent: Intent?, shouldRender: Boolean = true) {
        if (intent == null) return
        val page = intent.getStringExtra("notification_page")
        val notificationId = intent.getLongExtra("notification_id", -1L)
        if (!page.isNullOrBlank() && page in listOf("Home", "Trade", "Accounts", "Settings")) {
            selectedTab = page
        }
        if (notificationId > 0L) {
            appNotifications.firstOrNull { it.id == notificationId }?.read = true
            saveNotifications()
        }
        intent.removeExtra("notification_page")
        intent.removeExtra("notification_id")
        if (shouldRender && !page.isNullOrBlank()) render()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                notificationPermissionRequest
            )
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                notificationChannelId,
                "Volatilities Indicator Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Market, trading, recovery and account notifications"
            }
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun addNotification(page: String, title: String, message: String) {
        runOnUiThread {
            val duplicate = appNotifications.any {
                it.page == page && it.title == title && it.message == message && !it.read
            }
            if (duplicate) return@runOnUiThread

            val now = System.currentTimeMillis()
            val item = AppNotification(now, page, title, message, now)
            appNotifications.add(0, item)
            while (appNotifications.size > 250) appNotifications.removeAt(appNotifications.lastIndex)
            saveNotifications()
            showAndroidNotification(item)
            if (selectedTab == page) render()
        }
    }

    private fun showAndroidNotification(item: AppNotification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return

        val tapIntent = Intent(this, MainActivity::class.java).apply {
            putExtra("notification_page", item.page)
            putExtra("notification_id", item.id)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            item.id.hashCode(),
            tapIntent,
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, notificationChannelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setPriority(Notification.PRIORITY_DEFAULT)
        }
        builder.setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(item.title)
            .setContentText(item.message)
            .setStyle(Notification.BigTextStyle().bigText(item.message))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(item.id.hashCode(), builder.build())
    }

    private fun saveNotifications() {
        val array = JSONArray()
        appNotifications.forEach { item ->
            array.put(JSONObject()
                .put("id", item.id)
                .put("page", item.page)
                .put("title", item.title)
                .put("message", item.message)
                .put("timestamp", item.timestamp)
                .put("read", item.read))
        }
        notificationPrefs.edit().putString("items", array.toString()).apply()
    }

    private fun loadNotifications() {
        val raw = notificationPrefs.getString("items", "") ?: return
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                appNotifications.add(AppNotification(
                    obj.optLong("id"),
                    obj.optString("page", "Home"),
                    obj.optString("title"),
                    obj.optString("message"),
                    obj.optLong("timestamp"),
                    obj.optBoolean("read", false)
                ))
            }
        } catch (_: Exception) {
            appNotifications.clear()
        }
    }

    private fun notificationBar(): View {
        val row = horizontal()
        row.addView(text(selectedTab.uppercase(Locale.US), 13f, amber, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        val unread = appNotifications.count { it.page == selectedTab && !it.read }
        val bell = TextView(this).apply {
            text = if (unread > 0) "🔔 $unread" else "🔔"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(if (unread > 0) amber else green)
            setPadding(dp(13), dp(8), dp(13), dp(8))
            background = rounded(cardColor, lineColor, 22)
            contentDescription = "$unread unread $selectedTab notifications"
            setOnClickListener { showNotificationsDialog() }
        }
        row.addView(bell)
        return row
    }

    private fun styleNotificationDialog(dialog: AlertDialog) {
        dialog.window?.setBackgroundDrawable(GradientDrawable().apply {
            setColor(cardColor)
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), lineColor)
        })
        dialog.findViewById<TextView>(android.R.id.alertTitle)?.setTextColor(amber)
        dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(amber)
        dialog.listView?.setBackgroundColor(cardColor)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(green)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(green)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(green)
    }

    private fun showNotificationsDialog() {
        val pageItems = appNotifications.filter { it.page == selectedTab }
        if (pageItems.isEmpty()) {
            val emptyDialog = AlertDialog.Builder(this)
                .setTitle("$selectedTab notifications")
                .setMessage("There are no notifications for this page.")
                .setPositiveButton("CLOSE", null)
                .create()
            emptyDialog.setOnShowListener { styleNotificationDialog(emptyDialog) }
            emptyDialog.show()
            return
        }
        val labels = pageItems.map { item ->
            val state = if (item.read) "READ" else "UNREAD"
            val time = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(item.timestamp))
            "$state · ${item.title}\n${item.message}\n$time"
        }
        val notificationAdapter = object : ArrayAdapter<String>(
            this, android.R.layout.simple_list_item_1, labels
        ) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val row = super.getView(position, convertView, parent)
                (row as? TextView)?.apply {
                    setTextColor(amber)
                    setBackgroundColor(cardColor)
                    textSize = 16f
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                }
                return row
            }
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("$selectedTab notifications (${pageItems.count { !it.read }} unread)")
            .setAdapter(notificationAdapter) { _, which ->
                val item = pageItems[which]
                item.read = true
                saveNotifications()
                val detailDialog = AlertDialog.Builder(this)
                    .setTitle(item.title)
                    .setMessage(item.message)
                    .setPositiveButton("CLOSE") { _, _ -> render() }
                    .create()
                detailDialog.setOnShowListener { styleNotificationDialog(detailDialog) }
                detailDialog.show()
            }
            .setNeutralButton("MARK ALL READ") { _, _ ->
                pageItems.forEach { it.read = true }
                saveNotifications()
                render()
            }
            .setNegativeButton("CLOSE", null)
            .create()
        dialog.setOnShowListener { styleNotificationDialog(dialog) }
        dialog.show()
    }

    private fun referenceLevelsText(symbol: String): String {
        val levels = dailyLevels[symbol] ?: return "REFERENCE HIGH: --\nREFERENCE LOW: --"
        return "PREVIOUS DAY HIGH: ${formatPrice(levels.first)}\nPREVIOUS DAY LOW: ${formatPrice(levels.second)}"
    }

    private fun masterDecisionText(symbol: String): String {
        if (!marketFeedConnected || latestPrices[symbol] == null) {
            return "Waiting for live market price data."
        }
        val levels = dailyLevels[symbol]
            ?: return "Waiting for completed daily candles to establish the previous-day reference high and low."
        if (!accountConnected) {
            return "Reference levels are available. Waiting for Deriv account connection before entry conditions can be evaluated."
        }
        val price = latestPrices[symbol] ?: return "Waiting for live market price data."
        return when {
            price >= levels.first -> "Price is at or above the previous-day high. The high-side entry condition is being evaluated."
            price <= levels.second -> "Price is at or below the previous-day low. The low-side entry condition is being evaluated."
            else -> "Waiting for price to reach the previous-day high (${formatPrice(levels.first)}) or low (${formatPrice(levels.second)})."
        }
    }

    private fun recoveryStatusText(symbol: String): String {
        val waitingOriginal = positions.values.firstOrNull {
            it.symbol == symbol && it.role == "ORIGINAL" && it.status == "CLOSED" && it.localId in recoveryWaiting
        }
        if (waitingOriginal == null) {
            val openOriginal = positions.values.any {
                it.symbol == symbol && it.role == "ORIGINAL" && it.status != "CLOSED" && !it.status.startsWith("ERROR")
            }
            return if (openOriginal) {
                "Waiting for the original trade to close before recovery evaluation."
            } else {
                "No eligible closed original trade. Recovery is waiting for an original trade to complete."
            }
        }
        val mss = recoveryMss[symbol]
        val choch = recoveryChoch[symbol]
        return when {
            mss.isNullOrBlank() && choch.isNullOrBlank() -> "Original trade closed. Waiting for confirmed candle-close MSS and CHOCH."
            !mss.isNullOrBlank() && choch.isNullOrBlank() -> "MSS confirmed ${mss.lowercase(Locale.US)}. Waiting for CHOCH confirmation."
            mss.isNullOrBlank() && !choch.isNullOrBlank() -> "CHOCH confirmed ${choch.lowercase(Locale.US)}. Waiting for MSS confirmation."
            mss == choch -> "MSS and CHOCH agree (${mss.orEmpty().lowercase(Locale.US)}). Recovery conditions are aligned."
            else -> "MSS and CHOCH disagree. Waiting for matching confirmation."
        }
    }

    private fun updateMasterDecisionViews(symbol: String) {
        levelStatusViews[symbol]?.text = referenceLevelsText(symbol)
        marketStatusViews[symbol]?.text = masterDecisionText(symbol)
    }

    private fun updateRecoveryStatusView(symbol: String) {
        recoveryStatusViews[symbol]?.text = recoveryStatusText(symbol)
    }

    private fun formatMoney(value: Double): String =
        String.format(Locale.US, "%.2f %s", value, accountCurrency)

    private fun nextRequestId(): Int = ++requestCounter

    private fun utcDayKey(epochSeconds: Long): String {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US)
        c.timeInMillis = epochSeconds * 1000L
        return String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    private fun calculatePreviousDailyLevels(symbol: String, candles: List<DerivApiClient.Candle>) {
        val grouped = candles.groupBy { it.epoch / 86400L }.toSortedMap()
        if (grouped.size < 2) return
        val currentDay = System.currentTimeMillis() / 1000L / 86400L
        val completed = grouped.filterKeys { it < currentDay }.entries.lastOrNull() ?: return
        val high = completed.value.maxOf { it.high }
        val low = completed.value.minOf { it.low }
        if (high > low && high.isFinite() && low.isFinite()) {
            val previous = dailyLevels[symbol]
            dailyLevels[symbol] = high to low
            if (previous == null || previous.first != high || previous.second != low) {
                addNotification(
                    "Home",
                    "${displayIndexName(symbol)} reference levels updated",
                    "Previous completed-day high: ${formatPrice(high)}; low: ${formatPrice(low)}."
                )
            }
        }
    }

    private fun evaluateOriginalEntries(symbol: String, quote: Double) {
        if (!accountConnected || dailyCapital <= 0.0 || !quote.isFinite()) return
        val levels = dailyLevels[symbol] ?: return
        val today = utcDayKey(System.currentTimeMillis() / 1000L)
        val stake = dailyCapital * 0.25
        val riseKey = "$today|$symbol|RISE"
        val fallKey = "$today|$symbol|FALL"
        if (quote <= levels.second && riseKey !in originalTriggered) {
            originalTriggered.add(riseKey)
            openPosition(symbol, "RISE", "ORIGINAL", stake)
        }
        if (quote >= levels.first && fallKey !in originalTriggered) {
            originalTriggered.add(fallKey)
            openPosition(symbol, "FALL", "ORIGINAL", stake)
        }
        tradePrefs.edit().putStringSet("triggered_$today", originalTriggered.toSet()).apply()
    }

    private fun roundStake(value: Double): Double =
        BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toDouble()

    private fun openPosition(symbol: String, direction: String, role: String, stake: Double, originalId: String = "") {
        val roundedStake = roundStake(stake)
        if (!accountConnected || !roundedStake.isFinite() || roundedStake <= 0.0) return
        val localId = UUID.randomUUID().toString()
        val p = Position(localId, symbol, direction, role, roundedStake, originalId)
        positions[localId] = p
        val req = nextRequestId()
        pendingRequests[req] = localId
        // Deriv Rise/Fall mapping: RISE = PUT; FALL = CALL.
        val contractType = if (direction.equals("RISE", true)) "PUT" else "CALL"
        val proposal = JSONObject()
            .put("proposal", 1)
            .put("amount", roundStake(roundedStake))
            .put("basis", "stake")
            .put("contract_type", contractType)
            .put("currency", accountCurrency)
            .put("duration", 3)
            .put("duration_unit", "d")
            .put("underlying_symbol", symbol)
            .put("req_id", req)
        p.status = "REQUESTING PROPOSAL"
        if (role == "RECOVERY") {
            addNotification("Trade", "${displayIndexName(symbol)} recovery setup", "Recovery ${direction.lowercase(Locale.US)} proposal requested after the existing recovery conditions aligned.")
        }
        derivService?.client?.send(proposal)
        savePositions()
        runOnUiThread { if (selectedTab == "Trade") render() }
    }

    private fun subscribePosition(contractId: Long) {
        val req = JSONObject().put("proposal_open_contract", 1).put("contract_id", contractId).put("subscribe", 1)
        derivService?.client?.send(req)
    }

    private fun handleOpenContract(contract: JSONObject) {
        val contractId = contract.optLong("contract_id", 0L)
        val p = positions.values.firstOrNull { it.contractId == contractId } ?: return
        val wasAlreadyClosed = p.status == "CLOSED"
        p.profit = contract.optDouble("profit", p.profit)
        if (p.profit > p.peakProfit) p.peakProfit = p.profit
        val percent = if (p.stake > 0.0) (p.peakProfit / p.stake) * 100.0 else 0.0
        if (percent >= 30.0) {
            val level = kotlin.math.floor(percent / 10.0) * 10.0
            val protected = p.stake * (level / 100.0)
            if (protected > p.protectedProfit) p.protectedProfit = protected
        }
        p.canSell = contract.optInt("is_valid_to_sell", 0) == 1
        val isSold = contract.optInt("is_sold", 0) == 1 || contract.optInt("is_expired", 0) == 1
        if (!isSold && p.protectedProfit > 0.0 && p.profit <= p.protectedProfit && contract.optInt("is_valid_to_sell", 0) == 1) {
            val sell = JSONObject().put("sell", contractId).put("price", 0)
            derivService?.client?.send(sell)
            p.status = "CLOSING BY TRAILING"
        } else if (isSold) {
            p.status = "CLOSED"
            if (!wasAlreadyClosed) {
                addNotification("Trade", "${displayIndexName(p.symbol)} trade closed", "${p.role} ${p.direction} trade closed. Final reported profit: ${formatMoney(p.profit)}.")
            }
            p.closedAt = contract.optLong("sell_time", System.currentTimeMillis() / 1000L) * 1000L
            if (p.role == "ORIGINAL" && p.localId !in recoveryWaiting) {
                recoveryWaiting.add(p.localId)
                if (!wasAlreadyClosed) {
                    addNotification("Trade", "${displayIndexName(p.symbol)} recovery waiting", "Original trade closed. Waiting for the existing recovery MSS and CHOCH confirmation logic.")
                }
                savePositions()
            }
        } else {
            p.status = "OPEN"
        }
        savePositions()
        runOnUiThread { if (selectedTab == "Trade") render() }
    }

    // Manual close applies only to the selected contract. It never closes its opposite contract.
    private fun closePositionManually(localId: String) {
        val p = positions[localId] ?: return
        if (p.status == "CLOSED" || p.contractId <= 0L) return
        if (!p.canSell) {
            toast("Deriv does not currently allow this contract to be sold.")
            return
        }
        val reqId = nextRequestId()
        pendingRequests[reqId] = localId
        val request = JSONObject()
            .put("sell", p.contractId)
            .put("price", 0)
            .put("req_id", reqId)
        p.status = "CLOSING MANUALLY"
        p.canSell = false
        savePositions()
        derivService?.client?.send(request)
        runOnUiThread { if (selectedTab == "Trade") render() }
    }

    private fun evaluateRecoverySignals(symbol: String, candles: List<DerivApiClient.Candle>) {
        if (!accountConnected || candles.size < 5) return
        val closed = candles.sortedBy { it.epoch }.dropLast(1)
        if (closed.size < 4) return
       val last = closed.last()
        val prior = closed.dropLast(1)
        val swingHighs = mutableListOf<Double>()
        val swingLows = mutableListOf<Double>()
        for (i in 1 until prior.lastIndex) {
            if (prior[i].high > prior[i - 1].high && prior[i].high > prior[i + 1].high) swingHighs.add(prior[i].high)
            if (prior[i].low < prior[i - 1].low && prior[i].low < prior[i + 1].low) swingLows.add(prior[i].low)
        }
        val lastHigh = swingHighs.lastOrNull() ?: return
        val lastLow = swingLows.lastOrNull() ?: return
        val mssDirection = when {
            last.close > lastHigh -> "RISE"
            last.close < lastLow -> "FALL"
            else -> ""
        }
        if (mssDirection.isNotBlank() && recoveryMss[symbol] != mssDirection) {
            recoveryMss[symbol] = mssDirection
            addNotification("Trade", "${displayIndexName(symbol)} recovery MSS detected", "A closed-candle market-structure shift was detected in the ${mssDirection.lowercase(Locale.US)} direction.")
        }
        // CHOCH is the closed-candle break of the opposing one-candle swing.
        val chochDirection = when {
            last.close > lastHigh && last.close > last.open -> "RISE"
            last.close < lastLow && last.close < last.open -> "FALL"
            else -> ""
        }
        if (chochDirection.isNotBlank() && recoveryChoch[symbol] != chochDirection) {
            recoveryChoch[symbol] = chochDirection
            addNotification("Trade", "${displayIndexName(symbol)} recovery CHOCH detected", "A closed-candle CHOCH was detected in the ${chochDirection.lowercase(Locale.US)} direction.")
        }
        val mss = recoveryMss[symbol]
        val choch = recoveryChoch[symbol]
        if (mss.isNullOrBlank() || mss != choch) {
            runOnUiThread { updateRecoveryStatusView(symbol) }
            return
        }
        val eligible = positions.values.filter { it.symbol == symbol && it.role == "ORIGINAL" && it.status == "CLOSED" && it.localId in recoveryWaiting }
        val original = eligible.firstOrNull() ?: return
        val recoveryStake = original.stake + original.profit.coerceAtLeast(0.0)
        recoveryWaiting.remove(original.localId)
        addNotification("Trade", "${displayIndexName(symbol)} recovery confirmed", "MSS and CHOCH agree on ${mss.lowercase(Locale.US)}. Recovery proposal is being prepared.")
        recoveryMss.remove(symbol)
        recoveryChoch.remove(symbol)
        openPosition(symbol, mss, "RECOVERY", recoveryStake, original.localId)
    }

    private fun savePositions() {
        val array = JSONArray()
        positions.values.forEach { p ->
            array.put(JSONObject().put("id", p.localId).put("symbol", p.symbol).put("direction", p.direction)
                .put("role", p.role).put("stake", p.stake).put("original", p.originalId)
                .put("contract", p.contractId).put("profit", p.profit).put("peak", p.peakProfit)
                .put("protected", p.protectedProfit).put("status", p.status)
                .put("closedAt", p.closedAt))
        }
        tradePrefs.edit().putString("positions", array.toString()).apply()
    }

    private fun loadPositions() {
        val raw = tradePrefs.getString("positions", "") ?: return
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val p = Position(o.getString("id"), o.getString("symbol"), o.getString("direction"), o.getString("role"), o.getDouble("stake"), o.optString("original"))
                p.contractId = o.optLong("contract")
                p.profit = o.optDouble("profit")
                p.peakProfit = o.optDouble("peak")
                p.protectedProfit = o.optDouble("protected")
                p.status = o.optString("status", "OPEN")
                p.closedAt = o.optLong("closedAt", 0L)
                positions[p.localId] = p
                if (p.contractId > 0 && p.status != "CLOSED") subscribePosition(p.contractId)
            }
        } catch (_: Exception) { }
    }

    private fun showAccountModeDialog() {
        val modes = arrayOf("DEMO ACCOUNT", "REAL ACCOUNT")
        AlertDialog.Builder(this)
            .setTitle("Connect Deriv account")
            .setItems(modes) { _, which ->
                executionMode = if (which == 1) "REAL" else "DEMO"
                prefs.edit().putString("oauth_mode", executionMode).apply()
                beginDerivConnection()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun beginDerivConnection() {
        val tokenKey = if (executionMode == "REAL") "real_token" else "demo_token"
        val savedToken = prefs.getString(tokenKey, "").orEmpty()
        if (savedToken.isNotBlank()) {
            connectWithToken(savedToken)
        } else {
            startDerivOAuth()
        }
    }

    private fun startDerivOAuth() {
        oauthState = "android-" + UUID.randomUUID().toString()
        codeVerifier = generateCodeVerifier()
        prefs.edit()
            .putString("oauth_state", oauthState)
            .putString("code_verifier", codeVerifier)
            .putString("oauth_mode", executionMode)
            .apply()

        val challenge = generateCodeChallenge(codeVerifier)
        val authorizationUrl = Uri.parse(DerivApiClient.OAUTH_AUTH_URL).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", DerivApiClient.clientId(executionMode))
            .appendQueryParameter("redirect_uri", DerivApiClient.OAUTH_REDIRECT_URI)
            .appendQueryParameter("scope", DerivApiClient.OAUTH_SCOPE)
            .appendQueryParameter("state", oauthState)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()

        try {
            startActivity(Intent(Intent.ACTION_VIEW, authorizationUrl))
        } catch (e: Exception) {
            toast("Unable to open Deriv login: ${e.message ?: "browser unavailable"}")
        }
    }

    private fun handleOAuthCallback(callbackIntent: Intent?) {
        val data = callbackIntent?.data ?: return
        if (data.scheme != "volatilitiesindicator" || data.host != "oauth-callback") return

        val error = data.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            toast("Deriv authorization failed: $error")
            return
        }

        val code = data.getQueryParameter("code")
        val returnedState = data.getQueryParameter("state")
        val expectedState = prefs.getString("oauth_state", "").orEmpty()
        if (code.isNullOrBlank() || expectedState.isBlank() || returnedState != expectedState) {
            toast("Deriv sign-in failed: authorization code or security state is invalid.")
            return
        }

        executionMode = if (prefs.getString("oauth_mode", "DEMO").equals("REAL", true)) "REAL" else "DEMO"
        exchangeOAuthCode(code)
    }

    private fun exchangeOAuthCode(code: String) {
        val verifier = prefs.getString("code_verifier", "").orEmpty()
        if (verifier.isBlank()) {
            toast("Deriv sign-in failed: PKCE verifier is missing.")
            return
        }

        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("client_id", DerivApiClient.clientId(executionMode))
            .add("redirect_uri", DerivApiClient.OAUTH_REDIRECT_URI)
            .add("code_verifier", verifier)
            .build()

        val request = Request.Builder()
            .url(DerivApiClient.OAUTH_TOKEN_URL)
            .post(body)
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { toast("Deriv sign-in failed: ${e.message ?: "network error"}") }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseText = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    runOnUiThread { toast("Deriv token exchange failed (HTTP ${response.code}).") }
                    return
                }
                val token = try {
                    JSONObject(responseText).optString("access_token", "")
                } catch (_: Exception) { "" }

                if (token.isBlank()) {
                    runOnUiThread { toast("Deriv did not return an access token.") }
                    return
                }

                val tokenKey = if (executionMode == "REAL") "real_token" else "demo_token"
                prefs.edit()
                    .putString(tokenKey, token)
                    .remove("oauth_state")
                    .remove("code_verifier")
                    .apply()

                runOnUiThread { connectWithToken(token) }
            }
        })
    }

    private fun connectWithToken(token: String) {
        if (token.isBlank()) {
            toast("Deriv access token is empty.")
            return
        }
        val service = derivService
        if (service == null) {
            toast("Deriv service is starting. Please try Connect again.")
            startMarketFeedService()
            return
        }

        accountConnected = false
        accountLabel = "Connecting…"
        accountBalanceLabel = "Balance loading…"
        renderIfHome()
        service.client.connectWithAccessToken(token, executionMode)
    }

    private fun disconnectDerivAccount() {
        val tokenKey = if (executionMode == "REAL") "real_token" else "demo_token"
        prefs.edit()
            .remove(tokenKey)
            .apply()
        try { derivService?.client?.disconnectAccount() } catch (_: Exception) {}
        accountConnected = false
        accountLabel = "Not connected"
        accountBalanceLabel = "Balance unavailable"
        addNotification("Accounts", "Deriv account disconnected", "The Deriv account was disconnected from the application.")
        render()
        toast("Deriv account disconnected.")
    }

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(
            bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(
            hash, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    private fun render() {
        priceViews.clear()
        indexStatusViews.clear()
        marketStatusViews.clear()
        levelStatusViews.clear()
        recoveryStatusViews.clear()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pageColor)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(22))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(bottomNavigation())
        setContentView(root)

        content.addView(notificationBar())
        content.addView(space(12))

        when (selectedTab) {
            "Home" -> showHome()
            "Trade" -> showTrade()
            "Accounts" -> showAccounts()
            "Settings" -> showSettings()
        }
    }

    private fun showHome() {
        val brand = horizontal()
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.volatilities_indicator_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Volatilities Indicator logo"
        }
        brand.addView(logo, LinearLayout.LayoutParams(dp(58), dp(58)))
        val brandText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        brandText.addView(text("VOLATILITIES", 19f, ink, true))
        brandText.addView(text("INDICATOR", 19f, ink, true))
        brand.addView(brandText)
        content.addView(brand)
        content.addView(space(3))
        content.addView(text("Market monitor", 13f, muted))
        content.addView(space(18))
        val status = horizontal()
        status.addView(text("DERIV MARKET FEED", 12f, muted, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        val feedPill = pill(if (marketFeedConnected) "LIVE" else "CONNECTING", if (marketFeedConnected) green else amber)
        feedPillView = feedPill
        status.addView(feedPill)
        content.addView(status)
        content.addView(space(12))
        val account = card()
        account.addView(text("TRADING ACCOUNT", 12f, muted, true))
        account.addView(space(8))
        val accountName = text(if (accountConnected) accountLabel else "Not connected", 17f, ink, true)
        accountIdView = accountName
        account.addView(accountName)
        val balanceText = text(accountBalanceLabel, 13f, muted)
        accountBalanceView = balanceText
        account.addView(balanceText)
        val statusText = text(if (accountConnected) "CONNECTED" else "ACCOUNT NOT CONNECTED", 12f,
            if (accountConnected) green else red, true)
        accountStatusView = statusText
        account.addView(statusText)
        account.addView(space(12))
        account.addView(actionButton(if (accountConnected) "ACCOUNT OPTIONS" else "CONNECT DERIV ACCOUNT") {
            if (accountConnected) openTab("Accounts") else showAccountModeDialog()
        })
        content.addView(account)
        content.addView(space(18))

        content.addView(text("INDEX MONITOR", 16f, ink, true))
        content.addView(text("Live Deriv prices for all five synthetic indices.", 12f, muted))
        content.addView(space(10))

        indexes.forEach { symbol ->
            val indexCard = card()
            val row = horizontal()
            row.addView(text(displayIndexName(symbol), 15f, ink, true),
                LinearLayout.LayoutParams(0, -2, 1f))
            val statusView = pill(
                if (latestPrices.containsKey(symbol)) "LIVE" else "CONNECTING",
                if (latestPrices.containsKey(symbol)) green else amber
            )
            indexStatusViews[symbol] = statusView
            row.addView(statusView)
            indexCard.addView(row)
            indexCard.addView(space(10))

            val priceRow = horizontal()
            priceRow.addView(text("LIVE PRICE", 11f, muted),
                LinearLayout.LayoutParams(0, -2, 1f))
            val priceView = text(latestPrices[symbol]?.let { formatPrice(it) } ?: "--", 17f, ink, true)
            priceViews[symbol] = priceView
            priceRow.addView(priceView)
            indexCard.addView(priceRow)
            indexCard.addView(space(8))
            indexCard.addView(divider())
            indexCard.addView(space(8))
            indexCard.addView(text("MASTER DECISION", 11f, muted, true))
            indexCard.addView(text("WAITING", 12f, amber, true))
            val levelsView = text(referenceLevelsText(symbol), 12f, ink, true)
            levelStatusViews[symbol] = levelsView
            indexCard.addView(levelsView)
            indexCard.addView(space(6))
            val feedDetail = text(masterDecisionText(symbol), 11f, muted)
            marketStatusViews[symbol] = feedDetail
            indexCard.addView(text("WAITING FOR", 10f, muted, true))
            indexCard.addView(feedDetail)
            indexCard.addView(space(8))
            indexCard.addView(divider())
            indexCard.addView(space(8))
            indexCard.addView(text("RECOVERY STATUS", 11f, muted, true))
            val recoveryView = text(recoveryStatusText(symbol), 11f, muted)
            recoveryStatusViews[symbol] = recoveryView
            indexCard.addView(recoveryView)
            content.addView(indexCard)
            content.addView(space(9))
        }
    }

    private fun showTrade() {
        pageHeading("RUNNING TRADES", "Original and recovery contracts.")

        val summary = card()
        summary.addView(text("DAILY STARTING CAPITAL", 12f, amber, true))
        summary.addView(space(5))
        summary.addView(text(String.format(Locale.US, "%.2f %s", dailyCapital, accountCurrency), 21f, ink, true))
        summary.addView(text("ACCOUNT MODE: $executionMode", 11f, muted, true))
        summary.addView(text("Stake per original contract: 25%", 12f, muted))
        content.addView(summary)
        content.addView(space(16))

        content.addView(text("RUNNING TRADES", 16f, amber, true))
        content.addView(space(8))
        val running = positions.values.filter {
            it.status != "CLOSED" && it.status != "EXPIRED" && !it.status.startsWith("ERROR")
        }
        if (running.isEmpty()) {
            val empty = card()
            empty.addView(text("NO RUNNING TRADES", 15f, ink, true))
            empty.addView(space(5))
            empty.addView(text("No open contracts are currently recorded.", 13f, muted))
            content.addView(empty)
        } else {
            running.forEach { p ->
                val c = card()
                c.addView(text("${displayIndexName(p.symbol)} · ${p.role}", 15f, amber, true))
                c.addView(space(4))
                c.addView(text("DIRECTION: ${p.direction}", 14f, if (p.direction.equals("RISE", true) || p.direction.equals("BUY", true)) green else red, true))
                c.addView(text("Contract: ${if (p.contractId > 0) p.contractId else "Pending"}", 11f, muted))
                c.addView(text("Stake: ${formatMoney(p.stake)}", 12f, ink))
                c.addView(text("Status: ${p.status}", 12f, green, true))
                c.addView(text("Profit / Loss: ${formatMoney(p.profit)}", 14f, if (p.profit >= 0) green else red, true))
                c.addView(text("Trailing protection: ${formatMoney(p.protectedProfit)}", 11f, muted))
                c.addView(space(8))
                val closeButton = actionButton(
                    if (p.canSell && p.contractId > 0L && p.status == "OPEN") "CLOSE TRADE" else "CLOSE UNAVAILABLE",
                    primary = p.canSell && p.contractId > 0L && p.status == "OPEN"
                ) { closePositionManually(p.localId) }
                closeButton.isEnabled = p.canSell && p.contractId > 0L && p.status == "OPEN"
                closeButton.alpha = if (closeButton.isEnabled) 1.0f else 0.55f
                c.addView(closeButton)
                content.addView(c)
                content.addView(space(9))
            }
        }

        content.addView(space(8))
        content.addView(divider())
        content.addView(space(14))
        content.addView(text("TRADE HISTORY", 16f, amber, true))
        content.addView(text("Completed original and recovery contracts.", 12f, muted))
        content.addView(space(8))

        val history = positions.values.filter { it.status == "CLOSED" || it.status == "EXPIRED" }
            .sortedByDescending { it.closedAt }
        if (history.isEmpty()) {
            val emptyHistory = card()
            emptyHistory.addView(text("NO TRADE HISTORY", 14f, ink, true))
            emptyHistory.addView(space(4))
            emptyHistory.addView(text("Completed trades will appear here with their final results.", 12f, muted))
            content.addView(emptyHistory)
        } else {
            history.forEach { p ->
                val c = card()
                c.addView(text("${displayIndexName(p.symbol)} · ${p.role}", 14f, amber, true))
                c.addView(text("DIRECTION: ${p.direction}", 13f, if (p.direction.equals("RISE", true) || p.direction.equals("BUY", true)) green else red, true))
                c.addView(text("Stake: ${formatMoney(p.stake)}", 12f, muted))
                c.addView(text("FINAL P/L: ${formatMoney(p.profit)}", 14f, if (p.profit >= 0) green else red, true))
                val closedText = if (p.closedAt > 0L) {
                    java.text.DateFormat.getDateTimeInstance().format(java.util.Date(p.closedAt))
                } else "Closing time unavailable"
                c.addView(text("Closed: $closedText", 11f, muted))
                content.addView(c)
                content.addView(space(8))
            }
        }

        tradeStatusView = text(
            if (accountConnected) "● EXECUTION CONNECTED" else "● CONNECT A DERIV ACCOUNT TO ENABLE EXECUTION",
            12f, if (accountConnected) green else amber, true
        )
        content.addView(space(8))
        content.addView(tradeStatusView)
    }

    private fun showAccounts() {
        pageHeading("ACCOUNTS", "Account access and connection settings.")
        val deriv = card()
        deriv.addView(text("DERIV ACCOUNT", 15f, ink, true))
        deriv.addView(space(6))
        deriv.addView(text("Account mode: $executionMode", 13f, muted))
        deriv.addView(text(if (accountConnected) accountLabel else "Connection: Disconnected", 13f, muted))
        deriv.addView(text(accountBalanceLabel, 13f, muted))
        deriv.addView(space(12))
        deriv.addView(actionButton(if (accountConnected) "SWITCH / CONNECT ACCOUNT" else "CONNECT DERIV") {
            showAccountModeDialog()
        })
        deriv.addView(space(8))
        deriv.addView(actionButton("DISCONNECT DERIV", false) {
            disconnectDerivAccount()
        })
        content.addView(deriv)
        content.addView(space(12))

        val users = card()
        users.addView(text("USER ACCOUNT", 15f, ink, true))
        users.addView(text("Sign-in and registration interface will be added here.", 13f, muted))
        users.addView(space(10))
        users.addView(actionButton("USER ACCOUNT", false) {
            toast("User account screen is not connected yet.")
        })
        content.addView(users)
        content.addView(space(12))

        val admin = card()
        admin.addView(text("ADMIN ACCOUNT", 15f, ink, true))
        admin.addView(text("Administrator access and user management.", 13f, muted))
        admin.addView(space(10))
        admin.addView(actionButton("ADMIN ACCOUNT", false) {
            toast("Admin account screen is not connected yet.")
        })
        content.addView(admin)
    }

    private fun showSettings() {
        pageHeading("SETTINGS", "Application preferences.")
        val c = card()
        c.addView(text("APPLICATION", 15f, ink, true))
        c.addView(space(8))
        c.addView(settingRow("Interface", "Original dark · green · gold"))
        c.addView(divider())
        c.addView(settingRow("Market indexes", "VIX 10, VIX 25, VIX 50, VIX 75, VIX 100"))
        c.addView(divider())
        c.addView(settingRow("Market feed", if (marketFeedConnected) "Connected" else "Connecting"))
        c.addView(divider())
        c.addView(settingRow("Trading account", "$executionMode · " + if (accountConnected) "Connected" else "Not connected"))
        content.addView(c)
    }

    private fun updateConnectionLabels(message: String) {
        if (selectedTab == "Home") {
            feedPillView?.text = if (marketFeedConnected) "LIVE" else "CONNECTING"
            feedPillView?.setTextColor(if (marketFeedConnected) green else amber)
        }
    }

    private fun renderIfHome() {
        if (selectedTab == "Home") render()
    }

    private fun pageHeading(title: String, subtitle: String) {
        content.addView(text(title, 21f, amber, true))
        content.addView(text(subtitle, 13f, muted))
        content.addView(space(18))
    }

    private fun settingRow(label: String, value: String): View {
        val row = horizontal()
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(12), 0, dp(12))
        row.addView(text(label, 14f, ink, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(text(value, 13f, muted))
        return row
    }

    private fun bottomNavigation(): View {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(pageColor)
            setPadding(dp(6), dp(8), dp(6), dp(8))
            elevation = dp(8).toFloat()
        }
        listOf("Home", "Trade", "Accounts", "Settings").forEach { tab ->
            val item = TextView(this).apply {
                text = tab
                textSize = 12f
                gravity = Gravity.CENTER
                setTypeface(null, if (selectedTab == tab) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (selectedTab == tab) green else amber)
                setPadding(dp(4), dp(8), dp(4), dp(8))
                setOnClickListener { openTab(tab) }
            }
            nav.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
        }
        return nav
    }

    private fun openTab(tab: String) {
        selectedTab = tab
        render()
    }

    private fun card(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(cardColor, lineColor, 14)
            elevation = dp(1).toFloat()
        }
    }

    private fun actionButton(label: String, primary: Boolean = true, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            setTextColor(if (primary) Color.rgb(2, 12, 7) else amber)
            background = rounded(if (primary) green else cardColor,
                if (primary) green else lineColor, 10)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { action() }
        }
    }

    private fun pill(label: String, color: Int): TextView {
        return TextView(this).apply {
            text = label
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setTextColor(color)
            setPadding(dp(9), dp(5), dp(9), dp(5))
            background = rounded(cardColor, color, 20)
        }
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
        }
    }

    private fun horizontal() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(lineColor)
        layoutParams = LinearLayout.LayoutParams(-1, dp(1))
    }

    private fun space(height: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun rounded(fill: Int, stroke: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fill)
            setCornerRadius(dp(radius).toFloat())
            setStroke(dp(1), stroke)
        }
    }

    private fun displayIndexName(symbol: String): String {
        return when (symbol) {
            "R_10" -> "VIX 10"
            "R_25" -> "VIX 25"
            "R_50" -> "VIX 50"
            "R_75" -> "VIX 75"
            "R_100" -> "VIX 100"
            else -> symbol
        }
    }

    private fun formatPrice(value: Double): String =
        String.format(Locale.US, "%,.2f", value)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }
}


package za.co.volatilitiesindicator.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.json.JSONObject

class DerivConnectionService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): DerivConnectionService = this@DerivConnectionService
    }

    private val binder = LocalBinder()

    @Volatile
    private var activityCallback: DerivApiClient.Callback? = null

    val client: DerivApiClient by lazy {
        DerivApiClient(object : DerivApiClient.Callback {

            override fun onConnecting(message: String) {
                activityCallback?.onConnecting(message)
            }

            override fun onConnected(
                accountId: String,
                accountType: String
            ) {
                activityCallback?.onConnected(accountId, accountType)
            }

            override fun onBalance(
                balance: Double,
                currency: String
            ) {
                activityCallback?.onBalance(balance, currency)
            }

            override fun onTick(
                symbol: String,
                quote: Double,
                epoch: Long
            ) {
                activityCallback?.onTick(symbol, quote, epoch)
            }

            override fun onCandles(
                symbol: String,
                candles: List<DerivApiClient.Candle>
            ) {
                activityCallback?.onCandles(symbol, candles)
            }

            override fun onMarketDataError(
                symbol: String,
                message: String
            ) {
                activityCallback?.onMarketDataError(symbol, message)
            }

            override fun onError(message: String) {
                activityCallback?.onError(message)
            }

            override fun onTradeData(json: JSONObject) {
                activityCallback?.onTradeData(json)
            }

            override fun onDisconnected() {
                activityCallback?.onDisconnected()
            }
        })
    }

    fun attachActivity(callback: DerivApiClient.Callback) {
        activityCallback = callback
    }

    fun detachActivity(callback: DerivApiClient.Callback) {
        if (activityCallback === callback) {
            activityCallback = null
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        activityCallback = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Deriv connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Maintains the Deriv connection."
                setShowBadge(false)
            }

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(
            this,
            MainActivity::class.java
        ).apply {
            flags =
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.volatilities_indicator_logo)
            .setContentTitle("Volatilities Indicator")
            .setContentText("Maintaining Deriv connection")
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "deriv_connection_service"
        const val NOTIFICATION_ID = 7401
    }
}

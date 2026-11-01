package za.co.volatilitiesindicator.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class DerivBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {

        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }

        val settings = context.getSharedPreferences(
            "volatilities_app_settings",
            Context.MODE_PRIVATE
        )

        val authPrefs = context.getSharedPreferences(
            "vi_platform_auth",
            Context.MODE_PRIVATE
        )

        val derivPrefs = context.getSharedPreferences(
            "deriv_oauth",
            Context.MODE_PRIVATE
        )

        val executionMode = settings.getString(
            "executionMode",
            "DEMO"
        ) ?: "DEMO"

        val manuallyDisconnected = settings.getBoolean(
            "deriv_manually_disconnected",
            false
        )

        val platformToken = authPrefs.getString(
            "mobile_auth_token",
            ""
        ).orEmpty()

        val derivToken = derivPrefs.getString(
            if (executionMode.equals("REAL", true))
                "real_token"
            else
                "demo_token",
            ""
        ).orEmpty()

        if (
            manuallyDisconnected ||
            platformToken.isBlank() ||
            derivToken.isBlank()
        ) {
            return
        }

        val serviceIntent = Intent(
            context,
            DerivConnectionService::class.java
        ).apply {
            action = DerivConnectionService.ACTION_BOOT_RECONNECT
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (_: Exception) {
            // Android may restrict background service starts.
            // The app can reconnect when the user opens it.
        }
    }
}

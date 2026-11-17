package za.co.volatilitiesindicator.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.net.Uri
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.util.UUID
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Bundle
import android.os.Handler
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.Toast
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
/** Volatilities Indicator Android app. */
class MainActivity : Activity(), DerivApiClient.Callback {

    // Original Volatilities Indicator colour palette — preserved.
    private val bg = Color.rgb(3, 8, 6)
    private val panel = Color.rgb(8, 20, 18)
    private val panel2 = Color.rgb(16, 34, 39)
    private val green = Color.rgb(0, 255, 92)
    private val greenDark = Color.rgb(0, 120, 48)
    private val white = Color.rgb(255, 220, 45)
    private val muted = Color.rgb(242, 248, 245)
    private val yellow = Color.rgb(255, 220, 45)
    private val red = Color.rgb(255, 65, 75)
    private val cyan = Color.rgb(0, 225, 235)
    private val blue = Color.rgb(70, 155, 255)

    private val appPrefs by lazy {
    getSharedPreferences("volatilities_app_settings", MODE_PRIVATE)
}
private val notificationsEnabled: Boolean
    get() = appPrefs.getBoolean("notificationsEnabled", false)
private var platform = "USER"
    set(value) {
        field = value
        appPrefs.edit().putString("platform", value).apply()
    }

private var executionMode = "DEMO"
    set(value) {
        field = value
        appPrefs.edit().putString("executionMode", value).apply()
    }

private var selectedTab = "Home"
    set(value) {
        field = value
        appPrefs.edit().putString("selectedTab", value).apply()
    }

private var drawerOpen = false

    private lateinit var page: LinearLayout
    private var mainScrollView: ScrollView? = null
    private val profileRefreshHandler by lazy { Handler(mainLooper) }
    private val profileRefreshRunnable = object : Runnable {
        override fun run() {
            if (authToken.isNotBlank() && platform != "ADMIN") refreshProfile(showMessage = false)
            profileRefreshHandler.postDelayed(this, 15_000L)
        }
    }

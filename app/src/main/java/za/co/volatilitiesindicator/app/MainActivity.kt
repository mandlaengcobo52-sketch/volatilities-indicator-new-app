package za.co.volatilitiesindicator.app

import android.app.Activity
import android.os.Bundle
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }

        val title = TextView(this).apply {
            text = "VOLATILITIES INDICATOR"
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
        }

        val subtitle = TextView(this).apply {
            text = "\nNEW ANDROID APP\n\nApplication started successfully."
            textSize = 18f
        }

        root.addView(title)
        root.addView(subtitle)

        setContentView(root)
    }
}

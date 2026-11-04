package app.momoding.test

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Isolated target for real accessibility gestures; no account, network or user data. */
class ScreenFixtureActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("m41_screen_fixture", MODE_PRIVATE) }
    private lateinit var counter: TextView

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 100, 40, 40)
        }
        layout.addView(TextView(this).apply { text = "M41 Screen Fixture"; textSize = 24f })
        counter = TextView(this).apply { textSize = 22f }
        layout.addView(counter)
        layout.addView(Button(this).apply {
            text = "M41 MARK"
            setOnClickListener { setCount(prefs.getInt("count", 0) + 1) }
        })
        layout.addView(Button(this).apply { text = "M41 RESET"; setOnClickListener { setCount(0) } })
        layout.addView(EditText(this).apply { hint = "M41 fixture text" })
        setContentView(layout)
        if (intent.getBooleanExtra("reset", false)) setCount(0) else setCount(prefs.getInt("count", 0))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("reset", false)) setCount(0)
    }

    private fun setCount(value: Int) {
        prefs.edit().putInt("count", value).commit()
        counter.text = "M41 counter: $value"
    }
}

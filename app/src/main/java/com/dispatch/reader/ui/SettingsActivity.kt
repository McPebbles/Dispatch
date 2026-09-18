package com.dispatch.reader.ui

import android.content.res.Configuration
import android.os.Bundle
import android.widget.ImageButton
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.dispatch.reader.R
import com.dispatch.reader.shell.Frame

/**
 * Settings.
 *
 * This is the screen Tombot got wrong: it was left on a stock ActionBar theme
 * with no edge-to-edge and no inset handling while the main screen was
 * carefully fixed, and the review that "fixed the frame" never opened it. So it
 * uses the same [Frame] as every other activity here, with a real bar in its
 * own layout, and `tools/verify_frame.py` asserts as much for all seven.
 *
 * The bar wears the accent rather than the neutral surface the reading screen
 * uses: settings belong to the app. The light system icons come from Frame's
 * luminance rule with nothing hard-coded.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var frame: Frame

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        frame = Frame(
            activity = this,
            barWrapper = findViewById(R.id.topBarWrapper),
            barRow = findViewById(R.id.topBarRow),
            content = findViewById(R.id.content),
        )
        frame.install()
        applyBar()

        findViewById<ImageButton>(R.id.backButton).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.content, SettingsFragment())
                .commit()
        }
    }

    private fun applyBar() {
        frame.applyBarColour(ContextCompat.getColor(this, R.color.accent))
    }

    override fun onResume() {
        super.onResume()
        applyBar()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyBar()
        frame.requestInsets()
    }
}

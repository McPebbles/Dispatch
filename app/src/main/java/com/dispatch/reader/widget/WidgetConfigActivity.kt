package com.dispatch.reader.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.dispatch.reader.App
import com.dispatch.reader.R
import com.dispatch.reader.data.Stream
import com.dispatch.reader.shell.Frame
import com.dispatch.reader.ui.StreamEditActivity
import com.dispatch.reader.util.Safely

/**
 * The widget's gear: which stream it shows, and whether rows carry a byline.
 *
 * Two entry points, one screen:
 *
 *  - **placement**, when the launcher starts it because the widget declares
 *    `android:configure`. The contract there is strict — the result must be
 *    `RESULT_CANCELED` until the reader confirms, or a widget the user backed
 *    out of is left on the home screen anyway;
 *  - **reconfiguration**, from the gear on an existing widget. Android 12 can
 *    reopen a configuration activity itself, but only from the widget picker's
 *    own affordance, which is not discoverable from the home screen — hence the
 *    gear, and hence [EXTRA_RECONFIGURE], which tells this screen the widget
 *    already exists and its current settings should be shown rather than the
 *    defaults.
 */
class WidgetConfigActivity : AppCompatActivity() {

    private lateinit var frame: Frame
    private lateinit var group: RadioGroup
    private lateinit var bylineSwitch: SwitchCompat

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var reconfigure = false
    private var streamId = Stream.ALL_ID

    private val app: App get() = application as App

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        reconfigure = intent?.getBooleanExtra(EXTRA_RECONFIGURE, false) == true

        // Before anything else: assume the reader backs out.
        setResult(Activity.RESULT_CANCELED, resultIntent())
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContentView(R.layout.activity_widget_config)
        frame = Frame(
            activity = this,
            barWrapper = findViewById(R.id.topBarWrapper),
            barRow = findViewById(R.id.topBarRow),
            content = findViewById(R.id.content),
        )
        frame.install()
        frame.applyBarColour(ContextCompat.getColor(this, R.color.top_bar))

        findViewById<TextView>(R.id.screenTitle).setText(R.string.widget_config_title)
        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        group = findViewById(R.id.streamGroup)
        bylineSwitch = findViewById(R.id.bylineSwitch)
        bylineSwitch.isChecked = WidgetPrefs.byline(this, widgetId)
        streamId = WidgetPrefs.streamId(this, widgetId)

        // The full builder rather than a cut-down dialog: a stream made for a
        // widget is a stream like any other, and a second creation path is how
        // two screens end up disagreeing about what a stream is. populate()
        // runs again in onResume, so a stream made here is selectable the
        // moment the reader comes back.
        findViewById<Button>(R.id.newStreamButton).setOnClickListener {
            startActivity(Intent(this, StreamEditActivity::class.java))
        }
        findViewById<Button>(R.id.doneButton).setOnClickListener { save() }

        populate()
    }

    override fun onResume() {
        super.onResume()
        populate()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        frame.applyBarColour(ContextCompat.getColor(this, R.color.top_bar))
        frame.requestInsets()
    }

    private fun populate() {
        app.io.execute {
            val streams = Safely.call({ app.repo.streams() }, emptyList())
            runOnUiThread {
                group.removeAllViews()
                addChoice(Stream.ALL_ID, getString(R.string.stream_all))
                addChoice(Stream.SAVED_ID, getString(R.string.stream_saved))
                for (stream in streams) {
                    addChoice(
                        stream.id,
                        getString(R.string.stream_with_count, stream.name, stream.feedCount),
                    )
                }
                // A stream deleted while this screen was open leaves nothing
                // checked; fall back rather than showing an unset radio group.
                if (group.checkedRadioButtonId == View.NO_ID) {
                    streamId = Stream.ALL_ID
                    group.check(idFor(Stream.ALL_ID))
                }
            }
        }
    }

    private fun addChoice(id: Long, label: String) {
        val button = RadioButton(this).apply {
            text = label
            this.id = idFor(id)
            isChecked = id == streamId
            setPadding(paddingLeft, dp(12), paddingRight, dp(12))
            setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
                if (checked) streamId = id
            }
        }
        group.addView(button)
    }

    /**
     * A stable view id per stream.
     *
     * `RadioGroup` needs distinct ids to enforce single selection, and the
     * stream ids start at 1 while [Stream.SAVED_ID] is -1, so they are shifted
     * into a range that cannot collide with generated ids.
     */
    private fun idFor(streamId: Long): Int = (ID_BASE + streamId).toInt()

    private fun save() {
        WidgetPrefs.setStreamId(this, widgetId, streamId)
        WidgetPrefs.setByline(this, widgetId, bylineSwitch.isChecked)
        NewsWidgetProvider.refreshOne(this, widgetId)
        setResult(Activity.RESULT_OK, resultIntent())
        finish()
    }

    private fun resultIntent(): Intent =
        Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_RECONFIGURE = "reconfigure"
        /** Half of the aapt id space: above every generated id, below every R.id. */
        private const val ID_BASE = 1_064_304_640L
    }
}

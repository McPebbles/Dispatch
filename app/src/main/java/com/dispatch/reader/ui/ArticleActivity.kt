package com.dispatch.reader.ui

import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import com.dispatch.reader.App
import com.dispatch.reader.R
import com.dispatch.reader.data.Article
import com.dispatch.reader.img.ImageStore
import com.dispatch.reader.shell.Frame
import com.dispatch.reader.util.Prefs
import com.dispatch.reader.util.Safely
import com.dispatch.reader.util.Time
import com.dispatch.reader.web.ExternalLinks

/**
 * One story, as the feed tells it.
 *
 * This screen shows **only what the feed provided** — headline, byline, date,
 * the publisher's own picture, and whatever text came in `content:encoded`,
 * `<summary>` or `<description>` — and then hands the reader to their chosen
 * browser for the rest. That is a deliberate boundary, not a missing feature:
 *
 *  - the app makes no request the feed did not advertise, so reading a story
 *    here does not tell the publisher anything;
 *  - there is no in-app WebView, so there is no surface for a publisher's
 *    scripts, cookies or consent walls to appear on;
 *  - the full article is one tap away, in a browser the reader chose, with
 *    whatever blocking and privacy settings they have set up there.
 *
 * The HTML is rendered with [HtmlCompat] into a TextView rather than a WebView.
 * `HtmlCompat` ignores `<script>` and `<style>`, and with no image getter it
 * drops `<img>` too, so what is left is text, links and basic emphasis.
 */
class ArticleActivity : AppCompatActivity() {

    private lateinit var frame: Frame
    private var article: Article? = null
    private val app: App get() = application as App

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_article)

        frame = Frame(
            activity = this,
            barWrapper = findViewById(R.id.topBarWrapper),
            barRow = findViewById(R.id.topBarRow),
            content = findViewById(R.id.content),
        )
        frame.install()
        frame.applyBarColour(ContextCompat.getColor(this, R.color.top_bar))

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.saveButton).setOnClickListener { toggleSaved() }
        findViewById<ImageButton>(R.id.shareButton).setOnClickListener { share() }

        val id = intent.getLongExtra(EXTRA_ARTICLE_ID, -1L)
        if (id <= 0) {
            finish()
            return
        }
        load(id)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        frame.applyBarColour(ContextCompat.getColor(this, R.color.top_bar))
        frame.requestInsets()
    }

    private fun load(id: Long) {
        app.io.execute {
            val loaded = Safely.call({ app.repo.article(id) }, null)
            if (loaded != null && Prefs.markReadOnOpen(this) && !loaded.read) {
                Safely.run { app.repo.setRead(id, true) }
            }
            runOnUiThread {
                if (loaded == null) {
                    finish()
                    return@runOnUiThread
                }
                article = loaded
                bind(loaded)
            }
        }
    }

    private fun bind(article: Article) {
        val scale = Prefs.scaleFor(Prefs.textSize(this))

        findViewById<TextView>(R.id.articleFeed).text = article.feedTitle
        findViewById<TextView>(R.id.articleTitle).apply {
            text = article.title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f * scale)
        }

        val meta = StringBuilder()
        article.author?.takeIf { it.isNotBlank() }?.let { meta.append(it) }
        val dateText = Time.full(this, article.publishedAt)
        if (dateText.isNotEmpty()) {
            if (meta.isNotEmpty()) meta.append("  ·  ")
            meta.append(dateText)
        }
        findViewById<TextView>(R.id.articleMeta).apply {
            text = meta
            visibility = if (meta.isEmpty()) View.GONE else View.VISIBLE
        }

        val image = findViewById<ImageView>(R.id.articleImage)
        if (article.imageUrl.isNullOrBlank() || !ImageStore.enabled(this)) {
            image.visibility = View.GONE
        } else {
            image.visibility = View.VISIBLE
            ImageStore.load(image, article.imageUrl, HERO_PX) { image.visibility = View.GONE }
        }

        val body = findViewById<TextView>(R.id.articleBody)
        val html = article.bodyHtml?.takeIf { it.isNotBlank() }
        val text: CharSequence = if (html != null) {
            rerouteLinks(HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT))
        } else {
            article.summary.orEmpty()
        }
        body.text = text
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * scale)
        body.movementMethod = LinkMovementMethod.getInstance()
        body.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE

        findViewById<TextView>(R.id.articleNoText).visibility =
            if (text.isEmpty()) View.VISIBLE else View.GONE

        findViewById<Button>(R.id.readFull).setOnClickListener {
            ExternalLinks.open(this, article.link)
        }
        findViewById<Button>(R.id.copyLink).setOnClickListener {
            ExternalLinks.copy(this, article.link)
        }
        updateSaveIcon(article.saved)
    }

    /**
     * Send links inside the feed's text through the app's browser picker.
     *
     * Without this they would be `URLSpan`s, which open with a bare
     * `ACTION_VIEW` — i.e. the *system* default browser, not the one the reader
     * chose for this app. That would be a quiet hole in the picker: the "read
     * full article" button would honour the choice and a link in the same
     * paragraph would not.
     */
    private fun rerouteLinks(source: CharSequence): CharSequence {
        val spannable = SpannableStringBuilder(source)
        val spans = spannable.getSpans(0, spannable.length, URLSpan::class.java)
        for (span in spans) {
            val start = spannable.getSpanStart(span)
            val end = spannable.getSpanEnd(span)
            val url = span.url ?: continue
            spannable.removeSpan(span)
            spannable.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        ExternalLinks.open(this@ArticleActivity, url)
                    }
                },
                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return spannable
    }

    private fun toggleSaved() {
        val current = article ?: return
        val next = !current.saved
        article = current.copy(saved = next)
        updateSaveIcon(next)
        app.io.execute {
            Safely.run { app.repo.setSaved(current.id, next) }
        }
        Toast.makeText(
            this,
            getString(if (next) R.string.saved_added else R.string.saved_removed),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun updateSaveIcon(saved: Boolean) {
        findViewById<ImageButton>(R.id.saveButton).apply {
            setImageResource(if (saved) R.drawable.ic_saved_on else R.drawable.ic_saved_off)
            contentDescription = getString(if (saved) R.string.action_unsave else R.string.action_save)
        }
    }

    private fun share() {
        val current = article ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, current.title)
            putExtra(Intent.EXTRA_TEXT, current.title + "\n" + current.link)
        }
        Safely.run { startActivity(Intent.createChooser(intent, getString(R.string.action_share))) }
    }

    companion object {
        const val EXTRA_ARTICLE_ID = "article_id"

        /** 1080px covers a full-width hero on a phone without decoding a 4K jpeg. */
        private const val HERO_PX = 1080
    }
}

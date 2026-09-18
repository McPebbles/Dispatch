package com.dispatch.reader.img

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import com.dispatch.reader.R
import com.dispatch.reader.feed.Http
import com.dispatch.reader.util.Prefs
import com.dispatch.reader.util.Safely
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Thumbnails, without an image-loading library.
 *
 * Coil or Glide would be a few lines here and several hundred kilobytes and an
 * OkHttp dependency there. What this app needs is narrow enough to write:
 * a memory cache, a disk cache, downsampled decoding, and a blocking path for
 * the widget (whose `RemoteViewsFactory` already runs off the main thread and
 * *must* return a bitmap synchronously).
 *
 * Two privacy properties fall out of reusing [Http]:
 *
 *  - images are fetched with the same no-cookie, no-cache, https-only client as
 *    feeds, so loading a thumbnail cannot set a publisher cookie;
 *  - the fetch is skipped entirely when the reader turns images off, which
 *    means a reader who does not want their device talking to publisher CDNs
 *    can have exactly that.
 */
object ImageStore {

    private const val MEMORY_FRACTION = 8
    private const val DISK_BUDGET_BYTES = 48L * 1024 * 1024
    private const val MAX_IMAGE_BYTES = 3 * 1024 * 1024
    private const val DIR = "thumbs"

    private lateinit var appContext: Context
    private lateinit var cacheDir: File
    private lateinit var memory: LruCache<String, Bitmap>
    private val main = Handler(Looper.getMainLooper())

    /**
     * Two threads.
     *
     * Enough that a scrolling list is not waiting on one slow CDN, few enough
     * that a fling does not open twenty sockets. Separate from the database
     * executor so a slow image can never delay a read/unread write.
     */
    private val pool: ExecutorService = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "dispatch-img").apply { priority = Thread.MIN_PRIORITY + 2 }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        cacheDir = File(appContext.cacheDir, DIR).apply { mkdirs() }
        val limitKb = (Runtime.getRuntime().maxMemory() / 1024 / MEMORY_FRACTION).toInt()
        memory = object : LruCache<String, Bitmap>(limitKb) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
        }
    }

    /** True when the reader has not turned images off. */
    fun enabled(context: Context): Boolean = Prefs.loadImages(context)

    /**
     * Blocking fetch-and-decode, for the widget.
     *
     * Returns null rather than a placeholder: the widget's layout hides the
     * image view when there is nothing to show, which reads better than a grey
     * box in a 4×4 tile.
     */
    fun getBlocking(url: String?, maxPx: Int): Bitmap? {
        if (url.isNullOrBlank() || !this::appContext.isInitialized) return null
        if (!enabled(appContext)) return null
        val key = key(url, maxPx)
        memory.get(key)?.let { return it }

        val file = File(cacheDir, key)
        if (file.exists()) {
            decode(file.readBytes(), maxPx)?.let {
                memory.put(key, it)
                file.setLastModified(System.currentTimeMillis())
                return it
            }
        }

        val response = Http.get(url, maxBytes = MAX_IMAGE_BYTES)
        val bytes = response.bytes ?: return null
        val bitmap = decode(bytes, maxPx) ?: return null
        Safely.run {
            file.writeBytes(bytes)
            trimDisk()
        }
        memory.put(key, bitmap)
        return bitmap
    }

    /**
     * Asynchronous load into a list row.
     *
     * The view's tag carries the key it is currently showing, which is what
     * keeps a recycled row from ending up with the previous article's picture
     * when the network answers out of order. The tag id is a real resource id
     * (`res/values/ids.xml`): `View.setTag(int, Object)` rejects anything else
     * at run time, which is a crash a compiler cannot see coming.
     */
    fun load(view: ImageView, url: String?, maxPx: Int, onMissing: () -> Unit = {}) {
        val context = view.context
        if (url.isNullOrBlank() || !enabled(context)) {
            view.setTag(R.id.image_url_tag, null)
            view.setImageDrawable(null)
            onMissing()
            return
        }
        val key = key(url, maxPx)
        view.setTag(R.id.image_url_tag, key)

        memory.get(key)?.let {
            view.setImageBitmap(it)
            return
        }
        view.setImageDrawable(null)

        pool.execute {
            val bitmap = Safely.call({ getBlocking(url, maxPx) }, null)
            main.post {
                if (view.getTag(R.id.image_url_tag) != key) return@post
                if (bitmap != null) view.setImageBitmap(bitmap) else onMissing()
            }
        }
    }

    fun clear() {
        Safely.run {
            memory.evictAll()
            cacheDir.listFiles()?.forEach { it.delete() }
        }
    }

    fun diskBytes(): Long =
        Safely.call({ cacheDir.listFiles()?.sumOf { it.length() } ?: 0L }, 0L)

    // ------------------------------------------------------------- internals

    /**
     * Decode at no more than [maxPx] on the long edge.
     *
     * Publishers' feed images are frequently full-resolution — The Guardian's
     * widest `media:content` is over 2,000px and NPR's `content:encoded` image
     * is 8,256px — and decoding one of those into a 96dp row would allocate
     * tens of megabytes for a thumbnail.
     */
    private fun decode(bytes: ByteArray, maxPx: Int): Bitmap? = Safely.call({
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return@call null
        var sample = 1
        while (longest / sample > maxPx * 2) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }, null)

    private fun key(url: String, maxPx: Int): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$maxPx|$url".toByteArray())
        return buildString(digest.size * 2) {
            for (b in digest) append("%02x".format(b))
        }
    }

    /** Oldest-first eviction once the cache is over budget. */
    private fun trimDisk() {
        val files = cacheDir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= DISK_BUDGET_BYTES) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= DISK_BUDGET_BYTES) break
            total -= file.length()
            file.delete()
        }
    }
}

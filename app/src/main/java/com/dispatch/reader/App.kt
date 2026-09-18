package com.dispatch.reader

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.dispatch.reader.data.Repo
import com.dispatch.reader.data.Seed
import com.dispatch.reader.feed.SyncScheduler
import com.dispatch.reader.img.ImageStore
import com.dispatch.reader.util.Prefs
import com.dispatch.reader.util.Safely
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The process. Holds the two things everything else needs — the database and
 * the background executor — and nothing else.
 *
 * There is no dependency-injection framework, no analytics initialiser, no
 * crash reporter and no Play services bootstrap. A reader that runs on
 * GrapheneOS should be explainable in full from this file outwards.
 */
class App : Application() {

    lateinit var repo: Repo
        private set

    /**
     * One background thread for database work.
     *
     * Single, not a pool: every write in this app is small, and serialising
     * them removes a whole class of "which thread wrote last" bug. The only
     * concurrency is inside [com.dispatch.reader.feed.Sync], which fans out
     * network calls and hands the results back to be written here.
     */
    lateinit var io: ExecutorService
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.materialiseDefaults(this)
        applyTheme()
        repo = Repo(this)
        io = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "dispatch-io").apply { priority = Thread.NORM_PRIORITY - 1 }
        }
        ImageStore.init(this)

        io.execute {
            Safely.run { Seed.ifNeeded(this, repo) }
            Safely.run { SyncScheduler.reschedule(this, repo) }
        }
    }

    fun applyTheme() {
        AppCompatDelegate.setDefaultNightMode(
            when (Prefs.theme(this)) {
                Prefs.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                Prefs.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    companion object {
        @Volatile
        private var instance: App? = null

        /**
         * The process-wide instance.
         *
         * The widget's [android.widget.RemoteViewsService.RemoteViewsFactory]
         * is created by the system in this process but outside any activity, so
         * it needs a way to reach the repository that does not go through one.
         */
        fun get(): App? = instance
    }
}

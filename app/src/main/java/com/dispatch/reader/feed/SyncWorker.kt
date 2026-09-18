package com.dispatch.reader.feed

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.dispatch.reader.App
import com.dispatch.reader.util.Prefs
import java.util.concurrent.TimeUnit

/**
 * The background refresh.
 *
 * WorkManager is AndroidX and schedules through the platform's own JobScheduler
 * — **no Play services**. That matters here: the other way to keep a reader
 * current is a push service, and push on Android means Firebase Cloud
 * Messaging, which means Google Play services, which is the thing this whole
 * suite exists to avoid. Polling on the user's own schedule is the honest
 * trade, and it is why there are no notifications either.
 */
class SyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val app = applicationContext as? App ?: return Result.success()
        // force = false: this is the run that honours each stream's cadence.
        // A feed in a 15-minute stream and a feed in a daily one are both
        // reached by this job, and only the ones that are due are fetched.
        val summary = Sync.refreshAll(applicationContext, app.repo, force = false)
        // A failed feed is not a failed job: one publisher being down must not
        // cause WorkManager to back off the whole schedule. Only a run in which
        // every feed failed is worth retrying, and that usually means the
        // network was not really there.
        return if (summary.feedsTried > 0 && summary.feedsFailed == summary.feedsTried) {
            Result.retry()
        } else {
            Result.success()
        }
    }
}

/**
 * When the refresh runs, and under what conditions.
 *
 * Fifteen minutes is WorkManager's floor for periodic work; anything shorter is
 * silently rounded up, so the settings screen does not offer it. "Manual only"
 * cancels the work entirely rather than scheduling something that does nothing
 * — an app that keeps a job registered it never uses is lying to the battery
 * screen.
 */
object SyncScheduler {

    const val WORK_NAME = "dispatch-periodic-sync"

    /**
     * @param repo needed because the job's interval is no longer just the
     *   Settings value: a stream with a 15-minute cadence means the job has to
     *   run every 15 minutes even if the app-wide default is daily. The job
     *   runs at the shortest cadence anyone asked for and [Sync.refreshAll]
     *   then fetches only what is due.
     */
    fun reschedule(context: Context, repo: com.dispatch.reader.data.Repo) {
        val manager = WorkManager.getInstance(context.applicationContext)
        val minutes = Sync.shortestCadence(repo, Prefs.syncIntervalMinutes(context)).toLong()
        if (minutes <= 0L) {
            manager.cancelUniqueWork(WORK_NAME)
            return
        }
        val interval = maxOf(minutes, 15L)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (Prefs.syncOnWifiOnly(context)) NetworkType.UNMETERED else NetworkType.CONNECTED
            )
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<SyncWorker>(interval, TimeUnit.MINUTES)
            .setConstraints(constraints)
            // A flex window lets the system batch this with whatever else it is
            // waking for, which is most of the difference between a background
            // refresh that costs battery and one that does not.
            .setInitialDelay(2, TimeUnit.MINUTES)
            .build()

        manager.enqueueUniquePeriodicWork(
            WORK_NAME,
            // UPDATE rather than KEEP: the interval or the network constraint
            // may be exactly what changed, and KEEP would leave the old job in
            // place while the settings screen showed the new value.
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}

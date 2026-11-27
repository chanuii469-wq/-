package com.adlull.android.filter

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.adlull.android.Prefs
import java.util.concurrent.TimeUnit

class FilterUpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val failed = FilterManager.updateAll()
        return if (failed.isEmpty() || runAttemptCount >= 2) Result.success() else Result.retry()
    }

    companion object {
        private const val PERIODIC = "filter_update_periodic"
        private const val ONCE = "filter_update_once"

        private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(ctx: Context) {
            val wm = WorkManager.getInstance(ctx)
            if (Prefs.autoUpdateFilters) {
                val req = PeriodicWorkRequestBuilder<FilterUpdateWorker>(1, TimeUnit.DAYS)
                    .setConstraints(constraints).build()
                wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
            } else {
                wm.cancelUniqueWork(PERIODIC)
            }
        }

        fun runOnce(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<FilterUpdateWorker>().setConstraints(constraints).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(ONCE, ExistingWorkPolicy.KEEP, req)
        }
    }
}

package com.scratchy.app

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/** Android runs this about every 15 minutes (with internet) so devices catch up even when the app is closed. */
class SyncWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        if (!Sync.connected(applicationContext)) return Result.success()
        val out = Sync.runBlocking(applicationContext, null)
        return if (out.ok || out.consent != null) Result.success() else Result.retry()
    }
}

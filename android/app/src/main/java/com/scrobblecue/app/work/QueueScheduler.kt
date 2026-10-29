package com.scrobblecue.app.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Plans sending of the scrobble queue in the background, also when the app is closed. */
@Singleton
class QueueScheduler @Inject constructor(@ApplicationContext private val context: Context) {

    /** Sends the queue as soon as the phone is online; retries with growing pauses if Last.fm is busy. */
    fun sendWhenOnline() {
        val request = OneTimeWorkRequestBuilder<SendQueueWorker>()
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // APPEND_OR_REPLACE: a send that is already running is followed by another one, so scrobbles
        // added meanwhile are not missed
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        cancelLiveTick()
    }

    /** Live mode: runs [LiveTickWorker] at [at] (a track change), replacing the previously planned run. */
    fun liveTick(at: Instant) {
        // +1 s: run just after the track has ended, so its scrobble is due
        val delay = Duration.between(Instant.now(), at).plusSeconds(1).coerceAtLeast(Duration.ZERO)
        val request = OneTimeWorkRequestBuilder<LiveTickWorker>()
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(LIVE_TICK, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelLiveTick() {
        WorkManager.getInstance(context).cancelUniqueWork(LIVE_TICK)
    }

    private companion object {
        const val WORK_NAME = "send-scrobble-queue"
        const val LIVE_TICK = "live-tick"
    }
}

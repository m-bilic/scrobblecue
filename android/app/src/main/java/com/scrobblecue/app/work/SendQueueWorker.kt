package com.scrobblecue.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.scrobblecue.app.data.scrobble.FlushResult
import com.scrobblecue.app.data.scrobble.QueueSender
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Background job that sends the scrobble queue. WorkManager runs it when the phone is online. */
@HiltWorker
class SendQueueWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sender: QueueSender,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = when (sender.flush()) {
        FlushResult.RETRY_LATER -> Result.retry()
        // DONE, or waiting for the user (e.g. reconnect): the next app start tries again
        else -> Result.success()
    }
}

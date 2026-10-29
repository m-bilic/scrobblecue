package com.scrobblecue.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.scrobblecue.app.data.live.LiveRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Runs at each track change during live mode, also when the app is closed (see [LiveRepository.tick]). */
@HiltWorker
class LiveTickWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val live: LiveRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        live.tick()
        return Result.success()
    }
}

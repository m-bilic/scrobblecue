package com.scrobblecue.app.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.scrobblecue.app.data.live.LiveRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The buttons in the live-mode notification: Stop, Start side B, Done. */
@AndroidEntryPoint
class LiveActionReceiver : BroadcastReceiver() {

    @Inject
    lateinit var live: LiveRepository

    override fun onReceive(context: Context, intent: Intent) {
        // goAsync: the receiver may finish its (short) database and network work after onReceive returns
        val pending = goAsync()
        scope.launch {
            try {
                when (intent.action) {
                    ACTION_STOP -> live.stop()
                    ACTION_NEXT_SIDE -> live.startNextSide()
                    ACTION_DONE -> live.end()
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_STOP = "com.scrobblecue.app.live.STOP"
        const val ACTION_NEXT_SIDE = "com.scrobblecue.app.live.NEXT_SIDE"
        const val ACTION_DONE = "com.scrobblecue.app.live.DONE"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}

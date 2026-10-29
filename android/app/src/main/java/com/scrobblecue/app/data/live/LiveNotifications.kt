package com.scrobblecue.app.data.live

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.scrobblecue.app.MainActivity
import com.scrobblecue.app.R
import com.scrobblecue.app.work.LiveActionReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The live-mode notification: while a side plays it shows the current track with a countdown and a Stop
 * button; when the side is done it asks "Flip to side B?". Without the notification permission, live
 * mode still works – the user just sees it in the app only.
 */
@Singleton
class LiveNotifications @Inject constructor(@ApplicationContext private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PLAYING, "Now playing", NotificationManager.IMPORTANCE_LOW).apply {
                description = "The side that is playing live, with the current track"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_FLIP, "Flip reminder", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Reminds you to flip the record when a side has finished"
            },
        )
    }

    fun showPlaying(session: LiveSession, track: LiveTrack?) {
        val builder = base(CHANNEL_PLAYING)
            .setOngoing(true)
            .setSilent(true)
            .setContentTitle(track?.let { "${it.position} · ${it.title}" } ?: session.tracklist.album)
            .setContentText("${session.tracklist.album} · Side ${session.side}")
            .addAction(0, "Stop", action(LiveActionReceiver.ACTION_STOP))
        // The record may be a little ahead of the estimated lengths: allow flipping during the last track
        val next = session.nextSide()
        if (next != null && track != null && track == session.tracks.lastOrNull()) {
            builder.addAction(0, "Start side ${next.label}", action(LiveActionReceiver.ACTION_NEXT_SIDE))
        }
        if (track != null) {
            // Counts down to the end of the track by itself, without updates from the app
            builder.setWhen(track.end.toEpochMilli()).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        }
        notify(builder)
    }

    fun showFinished(session: LiveSession) {
        val next = session.nextSide()
        val builder = base(CHANNEL_FLIP)
            .setAutoCancel(true)
            .setContentTitle("Side ${session.side} is done")
            .setContentText(if (next != null) "Flip to side ${next.label}?" else "That was the last side of ${session.tracklist.album}.")
        if (next != null) builder.addAction(0, "Start side ${next.label}", action(LiveActionReceiver.ACTION_NEXT_SIDE))
        builder.addAction(0, "Done", action(LiveActionReceiver.ACTION_DONE))
        notify(builder)
    }

    fun cancel() = manager.cancel(NOTIFICATION_ID)

    private fun base(channel: String) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentIntent(
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )

    private fun action(action: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        action.hashCode(),
        Intent(context, LiveActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notify(builder: NotificationCompat.Builder) {
        val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (allowed) manager.notify(NOTIFICATION_ID, builder.build())
    }

    private companion object {
        const val CHANNEL_PLAYING = "live-playing"
        const val CHANNEL_FLIP = "live-flip"
        const val NOTIFICATION_ID = 1
    }
}

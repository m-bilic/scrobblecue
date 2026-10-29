package com.scrobblecue.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.scrobblecue.app.data.api.External
import com.scrobblecue.app.work.QueueScheduler
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import javax.inject.Inject

@HiltAndroidApp
class ScrobbleCueApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var queueScheduler: QueueScheduler

    @Inject
    @External
    lateinit var externalHttp: OkHttpClient

    // WorkManager asks for this on first use; the Hilt factory lets workers receive dependencies
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    // Cover images load with the same identified HTTP client as the Discogs API calls
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { externalHttp })) }
        .build()

    override fun onCreate() {
        super.onCreate()
        // Anything left in the queue from earlier is sent as soon as there is a connection
        queueScheduler.sendWhenOnline()
    }
}

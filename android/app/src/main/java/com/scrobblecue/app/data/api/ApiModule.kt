package com.scrobblecue.app.data.api

import com.scrobblecue.app.BuildConfig
import com.scrobblecue.app.api.apis.DiscogsAuthControllerApi
import com.scrobblecue.app.api.apis.DiscogsCollectionControllerApi
import com.scrobblecue.app.api.apis.LastFmAuthControllerApi
import com.scrobblecue.app.api.apis.LastFmScrobbleControllerApi
import com.scrobblecue.app.api.apis.MeControllerApi
import com.scrobblecue.app.api.apis.TimelineControllerApi
import com.scrobblecue.app.api.apis.TracklistControllerApi
import com.scrobblecue.app.data.credentials.CredentialStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.create
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** HTTP client for the ScrobbleCue backend: sends the user's sealed bundle. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class Backend

/** HTTP client for public APIs (Discogs releases, MusicBrainz): sends no credentials. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class External

/** Discogs and MusicBrainz ask every client to identify itself. */
const val USER_AGENT = "ScrobbleCue/${BuildConfig.VERSION_NAME} +https://github.com/m-bilic/scrobblecue"

@Module
@InstallIn(SingletonComponent::class)
object ApiModule {

    @Provides
    @Singleton
    @Backend
    fun backendHttp(credentials: CredentialStore): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("X-Client-Version", "android/${BuildConfig.VERSION_NAME}")
            // Apps send the bundle as a Bearer token; the website uses a cookie instead
            credentials.currentBundle()?.let { request.header("Authorization", "Bearer $it") }
            chain.proceed(request.build())
        }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    @External
    fun externalHttp(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
        }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun retrofit(@Backend http: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.API_BASE_URL)
        .client(http)
        // Scalars first: the collection endpoint returns Discogs' JSON as a plain string
        .addConverterFactory(ScalarsConverterFactory.create())
        .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType()))
        .build()

    // The interfaces below are generated from api/openapi.json (see app/build.gradle.kts)

    @Provides
    fun lastFmAuthApi(retrofit: Retrofit): LastFmAuthControllerApi = retrofit.create()

    @Provides
    fun lastFmScrobbleApi(retrofit: Retrofit): LastFmScrobbleControllerApi = retrofit.create()

    @Provides
    fun discogsAuthApi(retrofit: Retrofit): DiscogsAuthControllerApi = retrofit.create()

    @Provides
    fun discogsCollectionApi(retrofit: Retrofit): DiscogsCollectionControllerApi = retrofit.create()

    @Provides
    fun meApi(retrofit: Retrofit): MeControllerApi = retrofit.create()

    @Provides
    fun tracklistApi(retrofit: Retrofit): TracklistControllerApi = retrofit.create()

    @Provides
    fun timelineApi(retrofit: Retrofit): TimelineControllerApi = retrofit.create()
}

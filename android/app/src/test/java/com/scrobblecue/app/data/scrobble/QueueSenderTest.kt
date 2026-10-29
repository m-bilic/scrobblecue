package com.scrobblecue.app.data.scrobble

import androidx.room.Room
import com.scrobblecue.app.api.apis.LastFmScrobbleControllerApi
import com.scrobblecue.app.api.models.NowPlayingRequest
import com.scrobblecue.app.api.models.RecentResponse
import com.scrobblecue.app.api.models.RecentScrobble
import com.scrobblecue.app.api.models.ScrobbleRequest
import com.scrobblecue.app.api.models.ScrobbleResponse
import com.scrobblecue.app.api.models.ScrobbleResult
import com.scrobblecue.app.data.db.AppDatabase
import com.scrobblecue.app.data.db.Play
import com.scrobblecue.app.data.db.PlayScope
import com.scrobblecue.app.data.db.PlayStatus
import com.scrobblecue.app.data.db.QueueState
import com.scrobblecue.app.data.db.QueuedScrobble
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import retrofit2.Response
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The local queue against a real (in-memory) Room database and a fake Last.fm API: scrobbles leave the
 * queue only when Last.fm has answered, and uncertain ones are checked before they are sent again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QueueSenderTest {

    private lateinit var db: AppDatabase
    private val lastFm = FakeLastFm()
    private lateinit var sender: QueueSender

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        sender = QueueSender(db.scrobbles(), lastFm, Clock.fixed(NOW, ZoneOffset.UTC))
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun confirmedScrobblesLeaveTheQueueAndAreCounted() = runBlocking {
        addPlay(tracks = 2)
        lastFm.answer = { request -> results(request, ignore = setOf("Track 1")) }

        assertEquals(FlushResult.DONE, sender.flush())

        assertEquals(emptyList<QueuedScrobble>(), db.scrobbles().queue())
        val play = db.scrobbles().play(PLAY)!!
        assertEquals(PlayStatus.DONE, play.status)
        assertEquals(1, play.accepted)
        assertEquals(1, play.ignored)
    }

    @Test
    fun withoutAnAnswerScrobblesBecomeUncertainAndAreCheckedBeforeResending() = runBlocking {
        addPlay(tracks = 2)
        lastFm.answer = { throw IOException("offline") }

        assertEquals(FlushResult.RETRY_LATER, sender.flush())
        assertEquals(listOf(QueueState.UNCERTAIN, QueueState.UNCERTAIN), db.scrobbles().queue().map { it.state })
        assertEquals(PlayStatus.WAITING, db.scrobbles().play(PLAY)!!.status)

        // The first one did reach Last.fm before the connection dropped
        lastFm.recent = listOf(RecentScrobble(artist = "Artist", track = "track 0 ", timestamp = timestamp(0)))
        lastFm.answer = { request -> results(request) }

        assertEquals(FlushResult.DONE, sender.flush())
        assertEquals(1, lastFm.recentCalls)
        assertEquals(listOf("Track 1"), lastFm.sent.last().scrobbles.map { it.track })
        assertEquals(PlayStatus.DONE, db.scrobbles().play(PLAY)!!.status)
        assertEquals(emptyList<QueuedScrobble>(), db.scrobbles().queue())
    }

    @Test
    fun unreadableAnswerFromLastFmIsTreatedAsUncertain() = runBlocking {
        addPlay(tracks = 1)
        lastFm.answer = { problem(502, "lastfm-error") }

        assertEquals(FlushResult.RETRY_LATER, sender.flush())

        assertEquals(QueueState.UNCERTAIN, db.scrobbles().queue().single().state)
    }

    @Test
    fun revokedSessionKeepsTheScrobblesUntilTheUserReconnects() = runBlocking {
        addPlay(tracks = 1)
        lastFm.answer = { problem(401, "lastfm-session-revoked") }

        assertEquals(FlushResult.NEEDS_USER, sender.flush())

        val play = db.scrobbles().play(PLAY)!!
        assertEquals(PlayStatus.WAITING, play.status)
        assertEquals("Last.fm needs to be connected again. Your scrobbles are kept and sent afterwards.", play.message)
        assertEquals(QueueState.PENDING, db.scrobbles().queue().single().state)
    }

    @Test
    fun sendsAtMostFiftyScrobblesPerRequest() = runBlocking {
        addPlay(tracks = 120)
        lastFm.answer = { request -> results(request) }

        assertEquals(FlushResult.DONE, sender.flush())

        assertEquals(listOf(50, 50, 20), lastFm.sent.map { it.scrobbles.size })
        assertEquals(120, db.scrobbles().play(PLAY)!!.accepted)
    }

    @Test
    fun liveScrobblesWaitUntilTheirTrackHasEnded() = runBlocking {
        val play = Play(PLAY, 1, "Album", "Artist", "", PlayScope.SIDE, "A", NOW.toString(), 0, PlayStatus.SENDING)
        db.scrobbles().addPlay(
            play,
            listOf(
                QueuedScrobble("ended", PLAY, 0, "Artist", "Ended", "Album", null, 200, START.toString(), QueueState.PENDING, sendAfter = NOW.toString()),
                QueuedScrobble("playing", PLAY, 1, "Artist", "Playing", "Album", null, 200, NOW.toString(), QueueState.PENDING, sendAfter = NOW.plusSeconds(1).toString()),
            ),
        )
        lastFm.answer = { request -> results(request) }

        assertEquals(FlushResult.DONE, sender.flush())

        assertEquals(listOf("Ended"), lastFm.sent.single().scrobbles.map { it.track })
        assertEquals(listOf("playing"), db.scrobbles().queue().map { it.id })
        assertEquals(PlayStatus.SENDING, db.scrobbles().play(PLAY)!!.status)
    }

    @Test
    fun stoppingLiveModeDropsTracksThatHaveNotFinished() = runBlocking {
        val play = Play(PLAY, 1, "Album", "Artist", "", PlayScope.SIDE, "A", NOW.toString(), 0, PlayStatus.SENDING)
        db.scrobbles().addPlay(
            play,
            listOf(QueuedScrobble("playing", PLAY, 0, "Artist", "Playing", "Album", null, 200, NOW.toString(), QueueState.PENDING, sendAfter = NOW.plusSeconds(60).toString())),
        )

        db.scrobbles().stopLive(PLAY, NOW.toString(), keepTimestamps = emptyList())

        assertEquals(emptyList<QueuedScrobble>(), db.scrobbles().queue())
        assertEquals(PlayStatus.DONE, db.scrobbles().play(PLAY)!!.status)
    }

    @Test
    fun stoppingLiveModeSendsATrackThatPlayedLongEnough() = runBlocking {
        val play = Play(PLAY, 1, "Album", "Artist", "", PlayScope.SIDE, "A", NOW.toString(), 0, PlayStatus.SENDING)
        val almostDone = NOW.minusSeconds(150).toString()
        db.scrobbles().addPlay(
            play,
            listOf(
                QueuedScrobble("last", PLAY, 0, "Artist", "Last", "Album", null, 200, almostDone, QueueState.PENDING, sendAfter = NOW.plusSeconds(50).toString()),
            ),
        )
        lastFm.answer = { request -> results(request) }

        db.scrobbles().stopLive(PLAY, NOW.toString(), keepTimestamps = listOf(almostDone))

        assertEquals(FlushResult.DONE, sender.flush())
        assertEquals(listOf("Last"), lastFm.sent.single().scrobbles.map { it.track })
        assertEquals(PlayStatus.DONE, db.scrobbles().play(PLAY)!!.status)
    }

    private suspend fun addPlay(tracks: Int) {
        val play = Play(
            id = PLAY,
            releaseId = 15588394,
            album = "Album",
            albumArtist = "Artist",
            cover = "",
            scope = PlayScope.SIDE,
            side = "A",
            endedAt = timestamp(tracks),
            createdAt = 0,
            status = PlayStatus.SENDING,
        )
        val queued = (0 until tracks).map { i ->
            QueuedScrobble("q$i", PLAY, i, "Artist", "Track $i", "Album", null, 200, timestamp(i), QueueState.PENDING)
        }
        db.scrobbles().addPlay(play, queued)
    }

    private fun timestamp(i: Int) = START.plusSeconds(200L * i).toString()

    private fun results(request: ScrobbleRequest, ignore: Set<String> = emptySet()) = Response.success(
        ScrobbleResponse(
            request.scrobbles.map {
                if (it.track in ignore) ScrobbleResult(ignoredCode = 1, status = ScrobbleResult.Status.IGNORED)
                else ScrobbleResult(status = ScrobbleResult.Status.ACCEPTED)
            },
        ),
    )

    private fun problem(status: Int, code: String): Response<ScrobbleResponse> = Response.error(
        status,
        """{"type":"https://scrobblecue.app/errors/$code","status":$status}"""
            .toResponseBody("application/problem+json".toMediaType()),
    )

    private class FakeLastFm : LastFmScrobbleControllerApi {
        var answer: (ScrobbleRequest) -> Response<ScrobbleResponse> = { error("no answer set") }
        var recent: List<RecentScrobble> = emptyList()
        var recentCalls = 0
        val sent = mutableListOf<ScrobbleRequest>()

        override suspend fun scrobble(scrobbleRequest: ScrobbleRequest): Response<ScrobbleResponse> =
            answer(scrobbleRequest).also { sent += scrobbleRequest }

        override suspend fun recent(from: String, to: String): Response<RecentResponse> {
            recentCalls++
            return Response.success(RecentResponse(recent))
        }

        override suspend fun nowPlaying(nowPlayingRequest: NowPlayingRequest): Response<Unit> = Response.success(Unit)
    }

    private companion object {
        const val PLAY = "play-1"
        val START: Instant = Instant.parse("2026-10-28T19:00:00Z")

        /** Long after every instant-mode scrobble in these tests. */
        val NOW: Instant = Instant.parse("2026-10-29T12:00:00Z")
    }
}

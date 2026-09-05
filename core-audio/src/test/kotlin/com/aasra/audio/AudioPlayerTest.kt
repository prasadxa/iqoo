package com.aasra.audio

import android.media.AudioTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.`when`

class AudioPlayerTest {
    @Test
    fun failedPlaybackStartReleasesTheEchoHold() {
        val fixture = Fixture()
        doThrow(IllegalStateException("Device unavailable")).`when`(fixture.track).play()
        runCatching { fixture.player.play(ShortArray(320)) }
        assertFalse(fixture.player.isPlaying)
        assertEquals(listOf(true, false), fixture.events)
    }

    @Test
    fun cancelledPlaybackNeverCreatesOrStartsATrack() {
        val fixture = Fixture()
        fixture.player.play(ShortArray(320)) { false }
        assertEquals(emptyList<Boolean>(), fixture.events)
        org.mockito.Mockito.verify(fixture.track, org.mockito.Mockito.never()).play()
    }

    @Test
    fun flushInvalidatesAWritingSentenceAndNextTurnStartsFromAResetHead() {
        val fixture = Fixture()
        var checks = 0
        fixture.player.play(ShortArray(4096)) {
            checks++
            if (checks == 3) fixture.player.flush()
            true
        }
        assertFalse(fixture.player.isPlaying)
        fixture.player.play(ShortArray(320))
        assertTrue(fixture.player.isPlaying)
        fixture.head = 320
        assertFalse(fixture.player.isPlaying)
    }

    @Test
    fun drainingNotifiesOnceAndNextSentenceRearmsEvenOnAnAlreadyPlayingTrack() {
        val fixture = Fixture()
        fixture.player.play(ShortArray(320))
        assertTrue(fixture.player.isPlaying)
        fixture.head = 320
        assertFalse(fixture.player.isPlaying)
        assertFalse(fixture.player.isPlaying)
        fixture.player.play(ShortArray(320))
        assertTrue(fixture.player.isPlaying)
        assertEquals(listOf(true, false, true), fixture.events)
    }

    @Test
    fun aWriteInProgressCannotReportTheSpeakerAsDrained() {
        val fixture = Fixture()
        `when`(fixture.track.write(any(ShortArray::class.java), anyInt(), anyInt())).thenAnswer {
            assertTrue("The first write is still in progress", fixture.player.isPlaying)
            it.getArgument<Int>(2)
        }
        fixture.player.play(ShortArray(320))
    }

    @Test
    fun stopDiscardsBufferedAudioInsteadOfOpeningTheMicDuringAnAsyncDrain() {
        val fixture = Fixture()
        fixture.player.play(ShortArray(320))
        fixture.player.stop()
        org.mockito.Mockito.verify(fixture.track).pause()
        org.mockito.Mockito.verify(fixture.track).flush()
        assertFalse(fixture.player.isPlaying)
    }

    // Inject only the Android device boundary; exercise the real player accounting.
    private class Fixture {
        val track = mock(AudioTrack::class.java)
        val player = AudioPlayer()
        val events = mutableListOf<Boolean>()
        var head = 0
        var state = AudioTrack.PLAYSTATE_STOPPED

        init {
            AudioPlayer::class.java.getDeclaredField("track").apply { isAccessible = true }.set(player, track)
            `when`(track.playState).thenAnswer { state }
            `when`(track.playbackHeadPosition).thenAnswer { head }
            `when`(track.write(any(ShortArray::class.java), anyInt(), anyInt())).thenAnswer { it.getArgument<Int>(2) }
            doAnswer { state = AudioTrack.PLAYSTATE_PLAYING; null }.`when`(track).play()
            doAnswer { state = AudioTrack.PLAYSTATE_PAUSED; null }.`when`(track).pause()
            doAnswer { head = 0; null }.`when`(track).flush()
            player.playbackListener = object : AudioPlayer.PlaybackListener {
                override fun onPlayingChanged(playing: Boolean) { events.add(playing) }
            }
        }
    }
}

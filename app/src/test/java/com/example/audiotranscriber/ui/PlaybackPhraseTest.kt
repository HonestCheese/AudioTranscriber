package com.example.audiotranscriber.ui

import com.example.audiotranscriber.model.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackPhraseTest {
    private val phrases = listOf(
        TranscriptSegment(0, 100, 500, "Первая"),
        TranscriptSegment(1, 500, 900, "Вторая"),
        TranscriptSegment(0, 1200, 1500, "Третья")
    )

    @Test fun switchesAtBoundaryAndKeepsSilenceEmpty() {
        assertNull(currentPlaybackPhrase(phrases, 99))
        assertEquals(0, currentPlaybackPhrase(phrases, 100)?.speakerId)
        assertEquals(1, currentPlaybackPhrase(phrases, 500)?.speakerId)
        assertNull(currentPlaybackPhrase(phrases, 900))
        assertNull(currentPlaybackPhrase(phrases, 1600))
    }

    @Test fun seekingBackReturnsEarlierPhrase() {
        assertEquals("Третья", currentPlaybackPhrase(phrases, 1300)?.text)
        assertEquals("Первая", currentPlaybackPhrase(phrases, 200)?.text)
        assertNull(currentPlaybackPhrase(emptyList(), 200))
    }
}

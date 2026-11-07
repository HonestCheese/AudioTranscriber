package com.example.audiotranscriber.ml

import org.junit.Assert.assertEquals
import org.junit.Test

class Stage01SpeakersTest {
    @Test fun assignsStableIdsByFirstAppearance() {
        val matcher = Stage01Speakers(listOf(
            Stage01Speakers.Interval(3.0, 5.0, 7),
            Stage01Speakers.Interval(0.0, 2.0, 42)
        ))
        assertEquals(0, matcher.speakerAt(1.0))
        assertEquals(1, matcher.speakerAt(4.0))
    }

    @Test fun keepsGapsUnknownAndUsesHalfOpenIntervals() {
        val matcher = Stage01Speakers(listOf(
            Stage01Speakers.Interval(0.0, 1.0, 8),
            Stage01Speakers.Interval(1.0, 2.0, 9)
        ))
        assertEquals(1, matcher.speakerAt(1.0))
        assertEquals(-1, matcher.speakerAt(2.0))
        assertEquals(-1, matcher.speakerAt(2.1))
        assertEquals(-1, Stage01Speakers(emptyList()).speakerAt(0.0))
    }

    @Test fun choosesClosestCenterInsteadOfLongestInterval() {
        val matcher = Stage01Speakers(listOf(
            Stage01Speakers.Interval(0.0, 10.0, 42),
            Stage01Speakers.Interval(1.0, 3.0, 7)
        ))
        assertEquals(1, matcher.speakerAt(2.0))
        assertEquals(0, matcher.speakerAt(5.0))
    }
}

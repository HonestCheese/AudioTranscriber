package com.example.audiotranscriber.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class EcapaClusteringTest {
    private fun voice(a: Float, b: Float): FloatArray {
        val length = sqrt(a * a + b * b)
        return floatArrayOf(a / length, b / length)
    }

    @Test fun repeatedVoiceIsNotSplitWhenAnEarlyOutlierAppears() {
        val a = voice(1f, 0f)
        val b = voice(0f, 1f)
        val slightlyDifferent = voice(0.8f, 0.6f)
        val labels = EcapaDiarizer.cluster(listOf(a, b, slightlyDifferent, a, b, a, b), -1)
        assertEquals(labels[0], labels[3])
        assertEquals(labels[0], labels[5])
        assertEquals(labels[1], labels[4])
        assertEquals(labels[1], labels[6])
        assertEquals(2, labels.toSet().size)
    }

    @Test fun weakTwoWindowFragmentMergesIntoSimilarStableSpeaker() {
        val a = voice(1f, 0f)
        val weak = voice(0.57f, 0.821f)
        val other = voice(-1f, 0f)
        val vectors = listOf(a, a, a, a, a, weak, weak, other, other, other, other, other)

        val labels = EcapaDiarizer.cluster(vectors, -1)

        assertEquals(labels[0], labels[5])
        assertEquals(labels[0], labels[6])
        assertNotEquals(labels[0], labels[7])
        assertEquals(2, labels.toSet().size)
    }

    @Test fun acousticallyDistinctShortSpeakerIsNotMergedAsNoise() {
        val a = voice(1f, 0f)
        val shortSpeaker = voice(0f, 1f)
        val other = voice(-1f, 0f)
        val vectors = listOf(a, a, a, a, a, shortSpeaker, shortSpeaker,
            other, other, other, other, other)

        val labels = EcapaDiarizer.cluster(vectors, -1)

        assertNotEquals(labels[0], labels[5])
        assertNotEquals(labels[5], labels[7])
        assertEquals(3, labels.toSet().size)
    }

    @Test fun explicitSpeakerCountLimitsClusters() {
        val labels = EcapaDiarizer.cluster(listOf(
            voice(1f, 0f), voice(0f, 1f), voice(-1f, 0f),
            voice(1f, 0f), voice(0f, 1f), voice(-1f, 0f)
        ), 2)
        assertTrue(labels.toSet().size <= 2)
    }

    @Test fun shortExchangeKeepsTwoDistinctSpeakers() {
        val labels = EcapaDiarizer.cluster(listOf(voice(1f, 0f), voice(0f, 1f)), -1)
        assertEquals(2, labels.toSet().size)
    }
}

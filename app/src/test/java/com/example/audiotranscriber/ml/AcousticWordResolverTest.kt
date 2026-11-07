package com.example.audiotranscriber.ml

import com.example.audiotranscriber.model.VoiceCandidate
import com.example.audiotranscriber.model.WordSpeakerEvidence
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AcousticWordResolverTest {
    private fun word(index: Int, a: Float, b: Float): WordSpeakerEvidence =
        WordSpeakerEvidence(index, "word" + index, index * 300, index * 300 + 250,
            listOf(VoiceCandidate(0, 0.6f, a), VoiceCandidate(1, 0.5f, b)), 0.1f, -1)

    @Test fun smoothingRemovesOneWordSpuriousFlip() {
        val words = listOf(word(0, .85f, .15f), word(1, .49f, .51f), word(2, .80f, .20f))
        assertArrayEquals(intArrayOf(0, 0, 0), AcousticWordResolver.resolve(words))
    }

    @Test fun acousticTurnCanStillSwitch() {
        val words = listOf(word(0, .9f, .1f), word(1, .9f, .1f),
            word(2, .1f, .9f), word(3, .1f, .9f))
        assertArrayEquals(intArrayOf(0, 0, 1, 1), AcousticWordResolver.resolve(words))
    }

    @Test fun llmCannotOverrideConfidentEvidenceOrInventId() {
        val confident = word(0, .95f, .05f).copy(acousticReliability = .9f)
        assertEquals(0, AcousticWordResolver.accept(confident, 0, 1))
        assertEquals(0, AcousticWordResolver.accept(word(0, .51f, .49f), 0, 19))
    }

    @Test fun llmCanChangeAmbiguousWordToExistingSpeaker() {
        assertEquals(1, AcousticWordResolver.accept(word(0, .51f, .49f), 0, 1))
    }
}

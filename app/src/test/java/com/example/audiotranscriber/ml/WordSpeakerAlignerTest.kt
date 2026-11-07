package com.example.audiotranscriber.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WordSpeakerAlignerTest {
    @Test
    fun assignsStableSpeakerIdsByFirstAppearance() {
        val result = WordSpeakerAligner.align(
            words = listOf(
                WordSpeakerAligner.Word("Первый", 0.1, 0.3),
                WordSpeakerAligner.Word("Второй", 2.1, 2.3)
            ),
            diarizationSegments = listOf(
                WordSpeakerAligner.DiarizationInterval(2.0, 3.0, 7),
                WordSpeakerAligner.DiarizationInterval(0.0, 1.0, 42)
            )
        )

        assertEquals(0, result.words[0].speakerContextId)
        assertEquals(1, result.words[1].speakerContextId)
        assertEquals(listOf(0, 1), result.speakers.map { it.speakerId })
    }

    @Test
    fun keepsWordsWithoutTemporalOverlapUnknown() {
        val result = WordSpeakerAligner.align(
            words = listOf(WordSpeakerAligner.Word("Неизвестно", 2.0, 2.2)),
            diarizationSegments = listOf(
                WordSpeakerAligner.DiarizationInterval(0.0, 1.0, 42)
            )
        )

        assertEquals(-1, result.words.single().speakerRawId)
        assertEquals(-1, result.words.single().speakerContextId)
    }

    @Test
    fun viterbiCanSmoothAnIsolatedAmbiguousWord() {
        val result = WordSpeakerAligner.align(
            words = listOf(
                WordSpeakerAligner.Word("мы", 0.05, 0.15),
                WordSpeakerAligner.Word("сейчас", 0.18, 0.25),
                WordSpeakerAligner.Word("начинаем", 0.28, 0.36),
                WordSpeakerAligner.Word("работать", 0.42, 0.52),
                WordSpeakerAligner.Word("вместе", 0.56, 0.66)
            ),
            diarizationSegments = listOf(
                WordSpeakerAligner.DiarizationInterval(0.0, 0.32, 42),
                WordSpeakerAligner.DiarizationInterval(0.30, 0.35, 7),
                WordSpeakerAligner.DiarizationInterval(0.40, 0.70, 42)
            )
        )

        val ambiguous = result.words[2]
        assertEquals(1, ambiguous.speakerRawId)
        assertEquals(0, ambiguous.speakerContextId)
        assertNotEquals(ambiguous.speakerRawId, ambiguous.speakerContextId)
    }

    @Test
    fun buildsUtterancesOnSpeakerChangesAndLongPauses() {
        val words = listOf(
            assignment(0, "Привет", 0, 300, 0),
            assignment(1, "всем", 350, 600, 0),
            assignment(2, "Здравствуйте", 700, 1000, 1),
            assignment(3, "ещё", 2200, 2400, 1)
        )

        val segments = WordSpeakerAligner.buildUtterances(words)

        assertEquals(3, segments.size)
        assertEquals("Привет всем", segments[0].text)
        assertEquals(0, segments[0].speakerId)
        assertEquals("Здравствуйте", segments[1].text)
        assertEquals(1, segments[1].speakerId)
        assertEquals("ещё", segments[2].text)
    }

    private fun assignment(
        index: Int,
        text: String,
        start: Int,
        end: Int,
        speaker: Int
    ) = com.example.audiotranscriber.model.WordSpeakerAssignment(
        index, text, start, end, speaker, speaker, 1f
    )
}

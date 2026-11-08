package com.example.audiotranscriber.ml

import org.junit.Assert.*
import org.junit.Test

class TitaNetEnrollmentTest {
    @Test fun thresholdAndMarginRejectAmbiguousProfiles() {
        val a = TitaNetEnrollment.Profile("profile_a", "A", floatArrayOf(1f, 0f))
        val b = TitaNetEnrollment.Profile("profile_b", "B", floatArrayOf(1f, 0f))
        assertEquals("profile_a", TitaNetEnrollment.acceptedProfile(floatArrayOf(2f, 0f), listOf(a)))
        assertNull(TitaNetEnrollment.acceptedProfile(floatArrayOf(1f, 0f), listOf(a, b)))
        assertNull(TitaNetEnrollment.acceptedProfile(floatArrayOf(0f, 1f), listOf(a)))
        assertNull(TitaNetEnrollment.acceptedProfile(floatArrayOf(1f, 0f), emptyList()))
    }
    @Test fun differentEmbeddingDimensionsAreNotCompared() {
        val wrong = TitaNetEnrollment.Profile("profile_wrong", "Wrong model", floatArrayOf(1f, 0f, 0f))
        assertNull(TitaNetEnrollment.acceptedProfile(floatArrayOf(1f, 0f), listOf(wrong)))
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonFiniteEmbedding() { TitaNetEnrollment.normalize(floatArrayOf(Float.NaN)) }
}

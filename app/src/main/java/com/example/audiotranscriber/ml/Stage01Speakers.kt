package com.example.audiotranscriber.ml

import kotlin.math.abs

/** Midpoint matching from Stage 01, independent of the native runtime. */
internal class Stage01Speakers(intervals: List<Interval>) {
    data class Interval(val start: Double, val end: Double, val speaker: Int)
    private val intervals = intervals.sortedBy { it.start }
    private val ids = this.intervals.map { it.speaker }.distinct().withIndex()
        .associate { it.value to it.index }

    fun speakerAt(midpoint: Double): Int {
        val selected = intervals.filter { midpoint >= it.start && midpoint < it.end }
            .minByOrNull { abs(midpoint - (it.start + it.end) / 2) }
        return selected?.let { ids[it.speaker] } ?: -1
    }
}

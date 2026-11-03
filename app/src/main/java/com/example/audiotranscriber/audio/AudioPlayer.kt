package com.example.audiotranscriber.audio

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri

class AudioPlayer(private val context: Context) {
    private var player: MediaPlayer? = null
    var onCompletion: (() -> Unit)? = null

    val duration: Int get() = player?.duration ?: 0
    val currentPosition: Int get() = player?.currentPosition ?: 0
    val isPlaying: Boolean get() = player?.isPlaying == true

    fun load(uri: Uri) {
        release()
        val next = MediaPlayer()
        try {
            next.setDataSource(context, uri)
            next.prepare()
            next.setOnCompletionListener { onCompletion?.invoke() }
            player = next
        } catch (e: Exception) {
            next.release()
            throw e
        }
    }
    fun play() { player?.start() }
    fun pause() { if (isPlaying) player?.pause() }
    fun seekTo(position: Int) { player?.seekTo(position.coerceIn(0, duration)) }
    fun release() { player?.release(); player = null }
}

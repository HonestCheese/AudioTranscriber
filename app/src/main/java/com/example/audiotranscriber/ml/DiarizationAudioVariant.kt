package com.example.audiotranscriber.ml

enum class DiarizationAudioVariant(
    val label: String,
    val requiresDenoiser: Boolean
) {
    ORIGINAL("Original", false),
    DPDFNET_MILD("DPDFNet mild", true),
    DPDFNET_COMPRESSOR("DPDFNet + gentle compressor", true)
}

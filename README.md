# AudioTranscriber — Android, offline ASR and word-level diarization

## Pipeline (v1.4)

1. **GigaAM v3 RNN-T** recognizes Russian speech and returns approximate word timestamps. The recognition implementation and original model ZIP are unchanged.
2. **ECAPA-TDNN** extracts acoustic speaker vectors over overlapping **2-second audio windows (0.5-second stride)**. A voice clustering stage produces speaker prototypes. Each GigaAM word gets ranked voice candidates by comparing its surrounding windows to those prototypes. An isolated short word is **not** enough for a reliable voice embedding.
3. **AcousticWordResolver** calculates a deterministic word-level speaker sequence. It discourages unlikely single-word flips and serves as a fallback when the LLM cannot run.
4. **Qwen3 0.6B Q4_K_M** runs locally on Android via llama.cpp. It receives time-stamped words, voice candidates and relative scores. It proposes speaker labels for 32 words at a time with 5 neighboring context words on each side. A safety gate disallows nonexistent speakers, weak candidates and changes contradicting strong audio evidence. If a model fails or outputs invalid JSON, the acoustic result remains.
5. Final word labels are merged back into the application's usual transcript segments and saved.

**Voice weights are not calibrated probabilities.** They are softmax-normalized cosine similarities; `acoustic_reliability` is also a heuristic.

## Installation

- Android Studio with **Gradle JDK 21**, Android SDK 35, arm64 Android phone (API 26+).
- Build: `./gradlew :app:assembleDebug :app:testDebugUnitTest`.
- Gradle downloads ECAPA ONNX and Qwen3 GGUF on the **build machine**. Qwen GGUF is pinned by SHA-256 to `ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a`. The model increases APK size by roughly **397 MB**.
- The phone does not download LLM weights or send audio/text to any server. The app still does not require INTERNET permission.
- Existing installed GigaAM weights and recorded files remain unchanged by a normal in-place app update. If GigaAM isn't installed, import the original Colab model ZIP or all seven `android_models.partXX` files as before.
- On the first processing run, Android copies bundled ECAPA and Qwen3 to its private data directory. Leave sufficient free space (multiple GB including previous models and APK). Stages are run serially to reduce simultaneous RAM usage.

## Word-by-word diagnostics

The normal exported `transcript` segments remain compatible. JSON export also includes `word_metadata`, with every original GigaAM word's:
- index, word text and start/end timestamps;
- candidate speakers, cosine similarities and normalized relative scores;
- acoustic reliability, selected final speaker ID and `changed_by_llm` flag.

Saved transcripts preserve this information; old transcripts without it still open.

## Limitations and validation

- Word-level scores are derived from overlapping audio context windows, **not** embeddings computed from single syllables. Rapid interruptions and simultaneous speech may contaminate the windows.
- LLM does not hear voices, cannot identify a real person by name, and cannot prove whose utterance was spoken. It uses semantics only to resolve ambiguous candidate assignments.
- The automatic clustering cutoff is heuristic (0.64 cosine similarity); for many speakers the discovery stage caps at six voices. This can merge genuinely different participants, so evaluate accuracy on representative recordings.
- LLM receives *local* chunks, not the entire long transcript in one prompt. On low-RAM devices it may fail and the app keeps the acoustic fallback.
- Word timestamps from GigaAM are approximate. Evaluate diarization error rate, speed, memory and JSON outputs on real audio and a physical phone.
- Build and actual end-to-end runtime quality are not yet verified; the initial implementation is experimental.

## Model sources and licenses

- ASR: `salute-developers/GigaAM` (existing model ZIP).
- ECAPA: `kenrunio/transcriber-speaker-onnx`, SpeechBrain ECAPA ONNX.
- Qwen: https://huggingface.co/unsloth/Qwen3-0.6B-GGUF, `Qwen3-0.6B-Q4_K_M.gguf` (Apache-2.0). Download pinned by file SHA rather than a mutable release tag.
- Android LLM runtime: `dev.ffmpegkit-maintained:llama-android:0.1.1` (prebuilt arm64 llama.cpp AAR, MIT).

No application-layer network API or server inference is involved.

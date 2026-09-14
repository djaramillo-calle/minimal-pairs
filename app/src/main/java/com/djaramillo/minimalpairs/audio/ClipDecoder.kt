package com.djaramillo.minimalpairs.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.djaramillo.minimalpairs.clips.ClipPack
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One clip decoded to 16-bit PCM mono, silence trimmed, ready for an AudioTrack. */
class PreparedClip(val pcm: ShortArray, val sampleRate: Int) {
    val durationMs: Int get() = Pcm.durationMs(pcm.size, sampleRate)
}

/**
 * WebM/Opus → PCM through the platform `MediaExtractor` + `MediaCodec` in
 * synchronous mode. One 4 KB clip decodes in roughly 10–30 ms on a modern
 * phone. Stereo or odd sample rates are tolerated (downmixed; the rate is
 * passed through to the AudioTrack, which resamples).
 */
object ClipDecoder {
    private const val TIMEOUT_US = 10_000L

    @Throws(IOException::class)
    fun decode(source: ClipPack.Source): PreparedClip {
        val extractor = MediaExtractor()
        try {
            when (source) {
                is ClipPack.Source.Asset ->
                    extractor.setDataSource(source.afd.fileDescriptor, source.afd.startOffset, source.afd.length)
                is ClipPack.Source.Plain -> extractor.setDataSource(source.file.absolutePath)
            }
            return decode(extractor, trimSilence = true)
        } finally {
            extractor.release()
        }
    }

    /**
     * Any container the platform can read, straight from a file.
     *
     * [trimSilence] is off for a Say-it attempt: the silence around a model
     * clip is noise to a player, but the silence around a recording is part of
     * what an assessment measures, and cutting into the first or last phoneme
     * would lower a score for a reason the learner never made.
     */
    @Throws(IOException::class)
    fun decode(file: File, trimSilence: Boolean = true): PreparedClip {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            return decode(extractor, trimSilence)
        } finally {
            extractor.release()
        }
    }

    private fun decode(extractor: MediaExtractor, trimSilence: Boolean): PreparedClip {
        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) { trackIndex = i; format = f; break }
        }
        if (trackIndex < 0 || format == null) throw IOException("no audio track")
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        extractor.selectTrack(trackIndex)

        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoding = AudioFormat.ENCODING_PCM_16BIT

        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (e: Exception) {
            throw IOException("no decoder for $mime: ${e.message}")
        }
        val out = ShortBuilder(sampleRate.coerceAtLeast(8000) * channels.coerceAtLeast(1) * 2)
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var idleRounds = 0
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex) ?: throw IOException("null input buffer")
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex >= 0 -> {
                        idleRounds = 0
                        val buf = codec.getOutputBuffer(outIndex)
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            out.append(buf, encoding)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                    else -> {
                        // INFO_TRY_AGAIN_LATER: bail out if the codec stalls after EOS was queued.
                        if (inputDone && ++idleRounds > 500) throw IOException("decoder stalled")
                    }
                }
            }
        } finally {
            try { codec.stop() } catch (e: IllegalStateException) { /* never started */ }
            codec.release()
        }
        val interleaved = out.toArray()
        if (interleaved.isEmpty()) throw IOException("decoder produced no audio")
        val mono = Pcm.toMono(interleaved, channels.coerceAtLeast(1))
        val shaped = if (trimSilence) Pcm.trim(mono, sampleRate) else mono
        return PreparedClip(shaped, sampleRate)
    }

    /** Growable ShortArray fed from codec output buffers (16-bit or float PCM). */
    private class ShortBuilder(initial: Int) {
        private var data = ShortArray(initial.coerceAtLeast(1024))
        private var size = 0

        fun append(buf: ByteBuffer, encoding: Int) {
            val bb = buf.order(ByteOrder.nativeOrder())
            when (encoding) {
                AudioFormat.ENCODING_PCM_FLOAT -> {
                    val fb = bb.asFloatBuffer()
                    ensure(fb.remaining())
                    while (fb.hasRemaining()) {
                        val v = (fb.get() * 32767f).coerceIn(-32768f, 32767f)
                        data[size++] = v.toInt().toShort()
                    }
                }
                AudioFormat.ENCODING_PCM_8BIT -> {
                    ensure(bb.remaining())
                    while (bb.hasRemaining()) {
                        data[size++] = ((bb.get().toInt() and 0xFF) - 128 shl 8).toShort()
                    }
                }
                else -> {
                    val sb = bb.asShortBuffer()
                    val n = sb.remaining()
                    ensure(n)
                    sb.get(data, size, n)
                    size += n
                }
            }
        }

        private fun ensure(extra: Int) {
            if (size + extra > data.size) {
                var cap = data.size
                while (cap < size + extra) cap *= 2
                data = data.copyOf(cap)
            }
        }

        fun toArray(): ShortArray = data.copyOf(size)
    }
}

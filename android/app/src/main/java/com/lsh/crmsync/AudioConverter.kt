package com.lsh.crmsync

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

/**
 * 갤럭시 통화 녹음은 확장자만 .m4a이고 실제로는 3GP(AMR/AAC) 형식이라 음성 인식 서버가 못 읽는 경우가 있음.
 * 폰에서 디코딩 → AAC로 다시 인코딩 → 표준 .m4a(MP4)로 저장해서 보냄.
 */
object AudioConverter {
    private const val TIMEOUT_US = 10_000L
    private const val OUT_MIME = MediaFormat.MIMETYPE_AUDIO_AAC

    /** 성공하면 변환된 파일, 실패하면 null (그때는 원본을 그대로 보냄) */
    fun toM4a(ctx: Context, src: Uri, out: File): File? {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            extractor = MediaExtractor().apply { setDataSource(ctx, src, null) }
            var track = -1
            var inFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; inFormat = f; break }
            }
            if (track < 0 || inFormat == null) return null
            extractor.selectTrack(track)
            val inMime = inFormat.getString(MediaFormat.KEY_MIME)!!

            decoder = MediaCodec.createDecoderByType(inMime).apply { configure(inFormat, null, null, 0); start() }

            var sampleRate = inFormat.getIntOr(MediaFormat.KEY_SAMPLE_RATE, 16000)
            var channels = inFormat.getIntOr(MediaFormat.KEY_CHANNEL_COUNT, 1)

            out.delete()
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxTrack = -1

            val pending = ArrayDeque<ByteArray>()   // 디코딩된 PCM 조각 (인코더로 넘길 것)
            var pendingOffset = 0
            var extractorDone = false
            var decoderDone = false
            var encoderInputDone = false
            var encoderDone = false
            var pcmBytesFed = 0L
            val info = MediaCodec.BufferInfo()

            fun makeEncoder(): MediaCodec {
                val f = MediaFormat.createAudioFormat(OUT_MIME, sampleRate, channels).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, if (channels > 1) 96_000 else 48_000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
                }
                return MediaCodec.createEncoderByType(OUT_MIME).apply { configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); start() }
            }

            var guard = 0
            while (!encoderDone) {
                if (++guard > 5_000_000) return null // 혹시 모를 무한 루프 방지

                // 1) 원본 → 디코더
                if (!extractorDone) {
                    val ii = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (ii >= 0) {
                        val buf = decoder.getInputBuffer(ii)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            decoder.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            extractorDone = true
                        } else {
                            decoder.queueInputBuffer(ii, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // 2) 디코더 → PCM 모으기
                if (!decoderDone) {
                    val oi = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                    when {
                        oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = decoder.outputFormat
                            sampleRate = f.getIntOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                            channels = f.getIntOr(MediaFormat.KEY_CHANNEL_COUNT, channels)
                        }
                        oi >= 0 -> {
                            if (info.size > 0) {
                                val b = decoder.getOutputBuffer(oi)!!
                                b.position(info.offset); b.limit(info.offset + info.size)
                                val arr = ByteArray(info.size); b.get(arr); pending.addLast(arr)
                            }
                            decoder.releaseOutputBuffer(oi, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decoderDone = true
                        }
                    }
                }

                // 인코더는 디코더가 실제 샘플레이트를 알려준 뒤(첫 PCM이 나온 뒤) 만든다
                if (encoder == null && (pending.isNotEmpty() || decoderDone)) encoder = makeEncoder()
                val enc = encoder ?: continue

                // 3) PCM → 인코더
                if (!encoderInputDone && (pending.isNotEmpty() || decoderDone)) {
                    val ii = enc.dequeueInputBuffer(TIMEOUT_US)
                    if (ii >= 0) {
                        val buf: ByteBuffer = enc.getInputBuffer(ii)!!
                        buf.clear()
                        var written = 0
                        val ptsUs = pcmBytesFed * 1_000_000L / (2L * channels * sampleRate)
                        while (pending.isNotEmpty() && buf.remaining() > 0) {
                            val head = pending.first()
                            val n = minOf(head.size - pendingOffset, buf.remaining())
                            buf.put(head, pendingOffset, n)
                            pendingOffset += n; written += n
                            if (pendingOffset >= head.size) { pending.removeFirst(); pendingOffset = 0 }
                        }
                        pcmBytesFed += written
                        if (written == 0 && decoderDone && pending.isEmpty()) {
                            enc.queueInputBuffer(ii, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            encoderInputDone = true
                        } else {
                            enc.queueInputBuffer(ii, 0, written, ptsUs, 0)
                        }
                    }
                }

                // 4) 인코더 → 파일
                val oi = enc.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        muxTrack = muxer.addTrack(enc.outputFormat); muxer.start(); muxerStarted = true
                    }
                    oi >= 0 -> {
                        val b = enc.getOutputBuffer(oi)!!
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isConfig && info.size > 0 && muxerStarted) {
                            b.position(info.offset); b.limit(info.offset + info.size)
                            muxer.writeSampleData(muxTrack, b, info)
                        }
                        enc.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encoderDone = true
                    }
                }
            }
            if (!muxerStarted) return null
            muxer.stop(); muxerStarted = false
            return if (out.length() > 0) out else null
        } catch (e: Exception) {
            return null
        } finally {
            try { if (muxerStarted) muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }
    }

    private fun MediaFormat.getIntOr(key: String, def: Int) = if (containsKey(key)) getInteger(key) else def
}

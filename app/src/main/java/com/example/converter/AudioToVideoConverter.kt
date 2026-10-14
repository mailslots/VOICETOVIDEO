package com.example.converter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import com.example.model.AudioItem
import com.example.model.VideoConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException

class AudioToVideoConverter(private val context: Context) {

    companion object {
        private const val TAG = "AudioToVideoConverter"
        private const val TIMEOUT_US = 10_000L
    }

    private val frameRenderer = VideoFrameRenderer()

    suspend fun convert(
        audioItem: AudioItem,
        outputFile: File,
        config: VideoConfig,
        onProgress: (progress: Float, stageMessage: String) -> Unit,
        isCancelled: () -> Boolean
    ): File = withContext(Dispatchers.IO) {
        val width = config.aspectRatio.width
        val height = config.aspectRatio.height
        val fps = config.fps
        val frameDurationUs = 1_000_000L / fps

        var tempVideoFile: File? = null
        var tempAacFile: File? = null
        var localSourceFile: File? = null

        try {
            onProgress(0.02f, "Reading audio source...")

            // 1. Copy audio to a local cache file if needed (so MediaExtractor has reliable file descriptor)
            val audioSource = resolveAudioSource(audioItem.uri).also {
                if (it.isTemp) localSourceFile = it.file
            }

            // 2. Inspect audio track and duration
            val audioMeta = inspectAudio(audioSource.file)
            val durationUs = if (audioMeta.durationUs > 0) audioMeta.durationUs else (audioItem.durationMs * 1000L)
            val durationSec = (durationUs / 1_000_000.0).coerceAtLeast(1.0)
            val totalFrames = (durationSec * fps).toInt().coerceAtLeast(1)

            Log.d(TAG, "Audio duration: ${durationSec}s, total frames: $totalFrames, mime: ${audioMeta.mime}")

            if (isCancelled()) throw CancellationException("Conversion cancelled by user")

            // 3. Ensure audio is in AAC format for MP4 muxing
            val aacAudioFile = if (audioMeta.isAac) {
                audioSource.file
            } else {
                onProgress(0.05f, "Transcoding audio to AAC...")
                val transAac = File(context.cacheDir, "temp_aac_${System.currentTimeMillis()}.mp4")
                tempAacFile = transAac
                transcodeAudioToAac(audioSource.file, transAac, audioMeta, isCancelled)
                transAac
            }

            if (isCancelled()) throw CancellationException("Conversion cancelled by user")

            // 4. Generate Video Track into temporary MP4
            tempVideoFile = File(context.cacheDir, "temp_vid_${System.currentTimeMillis()}.mp4")
            encodeVideoTrack(
                tempVideoFile = tempVideoFile,
                width = width,
                height = height,
                fps = fps,
                frameDurationUs = frameDurationUs,
                totalFrames = totalFrames,
                audioItem = audioItem,
                videoConfig = config,
                onProgress = onProgress,
                isCancelled = isCancelled
            )

            if (isCancelled()) throw CancellationException("Conversion cancelled by user")

            // 5. Mux Video Track + Audio Track into Final MP4
            onProgress(0.92f, "Muxing final video and audio...")
            muxVideoAndAudio(tempVideoFile, aacAudioFile, outputFile, isCancelled)

            // 6. Match created & modified timestamp of output video with original audio
            if (audioItem.createdAt > 0) {
                outputFile.setLastModified(audioItem.createdAt)
            }

            onProgress(1.0f, "Completed")
            outputFile
        } finally {
            try { tempVideoFile?.delete() } catch (_: Exception) {}
            try { tempAacFile?.delete() } catch (_: Exception) {}
            try { localSourceFile?.delete() } catch (_: Exception) {}
        }
    }

    private data class AudioSourceInfo(val file: File, val isTemp: Boolean)
    private data class AudioMetaInfo(
        val mime: String,
        val isAac: Boolean,
        val durationUs: Long,
        val sampleRate: Int,
        val channelCount: Int,
        val trackIndex: Int
    )

    private fun resolveAudioSource(uri: Uri): AudioSourceInfo {
        if (uri.scheme == "file") {
            val file = File(uri.path ?: "")
            if (file.exists()) return AudioSourceInfo(file, false)
        }

        // Copy content:// URI stream to cache
        val temp = File(context.cacheDir, "input_audio_${System.currentTimeMillis()}.tmp")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(temp).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalArgumentException("Cannot open audio stream from: $uri")

        return AudioSourceInfo(temp, true)
    }

    private fun inspectAudio(audioFile: File): AudioMetaInfo {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(audioFile.absolutePath)
            var audioIndex = -1
            var mime = ""
            var durationUs = 0L
            var sampleRate = 44100
            var channels = 2

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val m = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (m.startsWith("audio/")) {
                    audioIndex = i
                    mime = m
                    if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        durationUs = format.getLong(MediaFormat.KEY_DURATION)
                    }
                    if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    break
                }
            }

            if (audioIndex < 0) {
                throw IllegalStateException("No audio track found in file")
            }

            val isAac = mime.contains("mp4a-latm", ignoreCase = true) || mime.contains("aac", ignoreCase = true)
            return AudioMetaInfo(mime, isAac, durationUs, sampleRate, channels, audioIndex)
        } finally {
            extractor.release()
        }
    }

    private fun encodeVideoTrack(
        tempVideoFile: File,
        width: Int,
        height: Int,
        fps: Int,
        frameDurationUs: Long,
        totalFrames: Int,
        audioItem: AudioItem,
        videoConfig: VideoConfig,
        onProgress: (progress: Float, stageMessage: String) -> Unit,
        isCancelled: () -> Boolean
    ) {
        val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            val colorFormat = selectColorFormat(MediaFormat.MIMETYPE_VIDEO_AVC)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, videoConfig.videoBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val chosenColor = videoFormat.getInteger(MediaFormat.KEY_COLOR_FORMAT)
        val isNv12 = chosenColor == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val muxer = MediaMuxer(tempVideoFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var videoTrackIndex = -1
        var muxerStarted = false

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val argb = IntArray(width * height)
        val yuv = ByteArray(width * height * 3 / 2)

        val bufferInfo = MediaCodec.BufferInfo()
        var inputFrameIndex = 0
        var inputDone = false

        val title = audioItem.displayName.ifEmpty { audioItem.fileName }
        val dateText = "RECORDED: ${audioItem.formattedCreatedAt}"
        val durationText = "Duration: ${audioItem.formattedDuration}"

        try {
            while (true) {
                if (isCancelled()) throw CancellationException("Conversion cancelled")

                // Feed input frames
                if (!inputDone) {
                    val inIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        if (inputFrameIndex < totalFrames) {
                            // Render frame on canvas
                            frameRenderer.renderFrame(
                                canvas = canvas,
                                width = width,
                                height = height,
                                audioTitle = title,
                                timestampText = dateText,
                                durationText = durationText,
                                frameIndex = inputFrameIndex,
                                totalFrames = totalFrames
                            )

                            bitmap.getPixels(argb, 0, width, 0, 0, width, height)
                            if (isNv12) {
                                YuvConverter.argbToNv12(argb, width, height, yuv)
                            } else {
                                YuvConverter.argbToI420(argb, width, height, yuv)
                            }

                            val inputBuffer = encoder.getInputBuffer(inIndex)!!
                            inputBuffer.clear()
                            inputBuffer.put(yuv)

                            val ptsUs = inputFrameIndex * frameDurationUs
                            encoder.queueInputBuffer(inIndex, 0, yuv.size, ptsUs, 0)

                            inputFrameIndex++

                            // Update progress (from 10% to 88%)
                            val pct = 0.10f + ((inputFrameIndex.toFloat() / totalFrames) * 0.78f)
                            val displayPercent = (pct * 100).toInt()
                            onProgress(pct, "Rendering video: frame $inputFrameIndex of $totalFrames ($displayPercent%)")
                        } else {
                            // End of stream
                            encoder.queueInputBuffer(inIndex, 0, 0, inputFrameIndex * frameDurationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        }
                    }
                }

                // Drain output from encoder
                val outIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = encoder.outputFormat
                    videoTrackIndex = muxer.addTrack(newFormat)
                    muxer.start()
                    muxerStarted = true
                } else if (outIndex >= 0) {
                    val outBuffer = encoder.getOutputBuffer(outIndex)!!
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0 && muxerStarted) {
                        outBuffer.position(bufferInfo.offset)
                        outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(videoTrackIndex, outBuffer, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(outIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                }
            }
        } finally {
            try { encoder.stop() } catch (_: Exception) {}
            try { encoder.release() } catch (_: Exception) {}
            try {
                if (muxerStarted) {
                    muxer.stop()
                }
                muxer.release()
            } catch (_: Exception) {}
            bitmap.recycle()
        }
    }

    private fun muxVideoAndAudio(
        videoFile: File,
        audioFile: File,
        outputFile: File,
        isCancelled: () -> Boolean
    ) {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            var videoTrack = -1
            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrack = i
                    break
                }
            }
            if (videoTrack < 0) throw IllegalStateException("No video track found in rendered video")
            val muxerVideoTrack = muxer.addTrack(videoExtractor.getTrackFormat(videoTrack))
            videoExtractor.selectTrack(videoTrack)

            audioExtractor.setDataSource(audioFile.absolutePath)
            var audioTrack = -1
            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    break
                }
            }
            if (audioTrack < 0) throw IllegalStateException("No audio track found in audio source")
            val muxerAudioTrack = muxer.addTrack(audioExtractor.getTrackFormat(audioTrack))
            audioExtractor.selectTrack(audioTrack)

            muxer.start()

            // Copy video packets
            val bufferSize = 256 * 1024
            val buffer = ByteBuffer.allocate(bufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            while (true) {
                if (isCancelled()) throw CancellationException("Conversion cancelled")
                buffer.clear()
                val sampleSize = videoExtractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = videoExtractor.sampleTime
                bufferInfo.flags = videoExtractor.sampleFlags
                muxer.writeSampleData(muxerVideoTrack, buffer, bufferInfo)
                videoExtractor.advance()
            }

            // Copy audio packets
            while (true) {
                if (isCancelled()) throw CancellationException("Conversion cancelled")
                buffer.clear()
                val sampleSize = audioExtractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = audioExtractor.sampleTime
                bufferInfo.flags = audioExtractor.sampleFlags
                muxer.writeSampleData(muxerAudioTrack, buffer, bufferInfo)
                audioExtractor.advance()
            }
        } finally {
            try { videoExtractor.release() } catch (_: Exception) {}
            try { audioExtractor.release() } catch (_: Exception) {}
            try {
                muxer.stop()
                muxer.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping final muxer: ${e.message}")
            }
        }
    }

    private fun transcodeAudioToAac(
        sourceFile: File,
        targetFile: File,
        audioMeta: AudioMetaInfo,
        isCancelled: () -> Boolean
    ) {
        val extractor = MediaExtractor()
        extractor.setDataSource(sourceFile.absolutePath)
        extractor.selectTrack(audioMeta.trackIndex)

        val srcFormat = extractor.getTrackFormat(audioMeta.trackIndex)
        val decoder = MediaCodec.createDecoderByType(audioMeta.mime)
        decoder.configure(srcFormat, null, null, 0)
        decoder.start()

        val sampleRate = audioMeta.sampleRate.coerceAtLeast(16000)
        val channelCount = audioMeta.channelCount.coerceIn(1, 2)
        val aacFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(aacFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val muxer = MediaMuxer(targetFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var audioTrackIndex = -1
        var muxerStarted = false

        val bufferInfo = MediaCodec.BufferInfo()
        var extractDone = false
        var decodeDone = false

        try {
            while (!decodeDone) {
                if (isCancelled()) throw CancellationException("Conversion cancelled")

                if (!extractDone) {
                    val inIdx = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inBuf = decoder.getInputBuffer(inIdx)!!
                        inBuf.clear()
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            extractDone = true
                        } else {
                            val pts = extractor.sampleTime
                            decoder.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                            extractor.advance()
                        }
                    }
                }

                // Dequeue PCM from decoder and feed to encoder
                val decOutIdx = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (decOutIdx >= 0) {
                    val pcmBuf = decoder.getOutputBuffer(decOutIdx)!!
                    val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

                    val encInIdx = encoder.dequeueInputBuffer(TIMEOUT_US)
                    if (encInIdx >= 0) {
                        val encInBuf = encoder.getInputBuffer(encInIdx)!!
                        encInBuf.clear()
                        if (bufferInfo.size > 0) {
                            pcmBuf.position(bufferInfo.offset)
                            pcmBuf.limit(bufferInfo.offset + bufferInfo.size)
                            encInBuf.put(pcmBuf)
                        }
                        val flags = if (isEos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                        encoder.queueInputBuffer(encInIdx, 0, bufferInfo.size, bufferInfo.presentationTimeUs, flags)
                    }
                    decoder.releaseOutputBuffer(decOutIdx, false)
                    if (isEos) decodeDone = true
                }

                // Drain encoder
                val encOutIdx = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    audioTrackIndex = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                } else if (encOutIdx >= 0) {
                    val encOutBuf = encoder.getOutputBuffer(encOutIdx)!!
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0 && muxerStarted) {
                        encOutBuf.position(bufferInfo.offset)
                        encOutBuf.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(audioTrackIndex, encOutBuf, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(encOutIdx, false)
                }
            }
        } finally {
            try { extractor.release() } catch (_: Exception) {}
            try { decoder.stop(); decoder.release() } catch (_: Exception) {}
            try { encoder.stop(); encoder.release() } catch (_: Exception) {}
            try {
                if (muxerStarted) muxer.stop()
                muxer.release()
            } catch (_: Exception) {}
        }
    }

    private fun selectColorFormat(mimeType: String): Int {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in codecList.codecInfos) {
            if (!info.isEncoder) continue
            try {
                val caps = info.getCapabilitiesForType(mimeType)
                if (caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)) {
                    return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                }
                if (caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar)) {
                    return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                }
            } catch (_: Exception) {}
        }
        return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
    }
}

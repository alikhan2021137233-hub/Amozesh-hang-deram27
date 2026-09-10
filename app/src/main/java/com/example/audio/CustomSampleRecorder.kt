package com.example.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.math.abs

/**
 * Handles recording real handpan strikes from the microphone for custom note sampling.
 * Records raw PCM 16-bit at 44.1kHz and converts it to a standard playable WAV file.
 */
class CustomSampleRecorder(private val context: Context) {

    enum class StopMode { SAVE, CANCEL }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var isRecording = false
    private var microphoneLease: AudioResourceCoordinator.Lease? = null
    @Volatile
    private var stopMode = StopMode.SAVE

    companion object {
        private const val TAG = "CustomSampleRecorder"
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        fun getCustomSampleFile(context: Context, noteNumber: Int): File {
            val dir = File(context.filesDir, "custom_handpan_samples")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            return File(dir, "note_${noteNumber}.wav")
        }

        fun hasCustomSample(context: Context, noteNumber: Int): Boolean {
            val file = getCustomSampleFile(context, noteNumber)
            return file.exists() && file.length() > 44
        }

        fun deleteCustomSample(context: Context, noteNumber: Int): Boolean {
            val file = getCustomSampleFile(context, noteNumber)
            return if (file.exists()) file.delete() else false
        }

        fun deleteAllCustomSamples(context: Context) {
            val dir = File(context.filesDir, "custom_handpan_samples")
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startRecording(
        noteNumber: Int,
        maxDurationMs: Long = 3000L,
        onAmplitudeChange: (Float) -> Unit = {},
        onFinished: (Boolean, File?) -> Unit,
        onCancelled: () -> Unit = {}
    ) {
        if (isRecording) {
            cancelRecording()
        }

        microphoneLease = AudioResourceCoordinator.tryAcquire("custom-sample-recorder")
        if (microphoneLease == null) {
            onFinished(false, null)
            return
        }

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT).coerceAtLeast(4096)
        
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                audioRecord?.release()
                audioRecord = null
                microphoneLease?.close()
                microphoneLease = null
                onFinished(false, null)
                return
            }

            val targetWavFile = getCustomSampleFile(context, noteNumber)
            val tempRawFile = File(context.cacheDir, "temp_rec_${System.currentTimeMillis()}.pcm")

            audioRecord?.startRecording()
            stopMode = StopMode.SAVE
            isRecording = true

            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                var totalBytesRead = 0L
                val startTimeNanos = System.nanoTime()
                var peakAmplitude = 0
                var clippingCount = 0
                var sumSquares = 0.0
                var totalSamples = 0L

                try {
                    FileOutputStream(tempRawFile).use { fos ->
                        val buffer = ShortArray(bufferSize / 2)
                        val byteBuffer = ByteArray(bufferSize)

                        while (isActive && isRecording) {
                            val readShorts = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                            if (readShorts > 0) {
                                var frameMaxAmp = 0
                                for (i in 0 until readShorts) {
                                    val sample = buffer[i]
                                    val absSample = abs(sample.toInt())
                                    if (absSample > frameMaxAmp) frameMaxAmp = absSample
                                    if (absSample > peakAmplitude) peakAmplitude = absSample
                                    if (absSample >= 32760) clippingCount++

                                    val sD = sample.toDouble() / 32768.0
                                    sumSquares += sD * sD
                                    totalSamples++

                                    val idx = i * 2
                                    byteBuffer[idx] = (sample.toInt() and 0xFF).toByte()
                                    byteBuffer[idx + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
                                }

                                fos.write(byteBuffer, 0, readShorts * 2)
                                totalBytesRead += readShorts * 2

                                val normalizedAmp = (frameMaxAmp / 32767f).coerceIn(0f, 1f)
                                withContext(Dispatchers.Main) {
                                    onAmplitudeChange(normalizedAmp)
                                }
                            }

                            if ((System.nanoTime() - startTimeNanos) / 1_000_000L >= maxDurationMs) {
                                break
                            }
                        }
                    }

                    val durationMs = (System.nanoTime() - startTimeNanos) / 1_000_000L
                    val overallRms = if (totalSamples > 0) kotlin.math.sqrt(sumSquares / totalSamples).toFloat() else 0f
                    val normalizedPeak = peakAmplitude / 32767f

                    if (durationMs < 200L || totalBytesRead < 4000L) {
                        tempRawFile.delete()
                        withContext(Dispatchers.Main) {
                            isRecording = false
                            onFinished(false, null)
                        }
                        return@launch
                    }

                    // Check if audio was too silent (user didn't hit the handpan)
                    if (normalizedPeak < 0.05f && overallRms < 0.008f) {
                        tempRawFile.delete()
                        withContext(Dispatchers.Main) {
                            isRecording = false
                            onFinished(false, null)
                        }
                        return@launch
                    }

                    if (stopMode == StopMode.CANCEL) {
                        tempRawFile.delete()
                        withContext(Dispatchers.Main) {
                            isRecording = false
                            onCancelled()
                        }
                        return@launch
                    }

                    // Convert PCM to a temporary WAV, validate it, then replace the target atomically.
                    rawPcmToWav(tempRawFile, targetWavFile, SAMPLE_RATE, 1, 16)
                    tempRawFile.delete()

                    withContext(Dispatchers.Main) {
                        isRecording = false
                        onFinished(true, targetWavFile)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during audio recording", e)
                    tempRawFile.delete()
                    withContext(Dispatchers.Main) {
                        isRecording = false
                        onFinished(false, null)
                    }
                } finally {
                    try {
                        audioRecord?.stop()
                        audioRecord?.release()
                    } catch (_: Exception) {}
                    audioRecord = null
                    microphoneLease?.close()
                    microphoneLease = null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            isRecording = false
            microphoneLease?.close()
            microphoneLease = null
            onFinished(false, null)
        }
    }

    fun stopAndSave() {
        requestStop(StopMode.SAVE)
    }

    fun cancelRecording() {
        requestStop(StopMode.CANCEL)
    }

    private fun requestStop(mode: StopMode) {
        if (!isRecording) return
        stopMode = mode
        isRecording = false
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            // ignore
        }
    }

    fun stopRecording() = cancelRecording()

    fun release() {
        stopRecording()
    }

    private fun rawPcmToWav(rawFile: File, wavFile: File, sampleRate: Int, channels: Int, bitDepth: Int) {
        val rawDataSize = rawFile.length()
        require(rawDataSize > 0L) { "Cannot write an empty WAV file" }
        val totalDataLen = rawDataSize + 36
        val byteRate = (sampleRate * channels * bitDepth) / 8
        val tempWavFile = File(wavFile.parentFile, "${wavFile.name}.tmp")

        try {
            RandomAccessFile(tempWavFile, "rw").use { raf ->
                raf.setLength(0L)
            // RIFF header
            raf.writeBytes("RIFF")
            raf.writeInt(Integer.reverseBytes(totalDataLen.toInt()))
            raf.writeBytes("WAVE")
            // fmt chunk
            raf.writeBytes("fmt ")
            raf.writeInt(Integer.reverseBytes(16)) // Subchunk1Size (16 for PCM)
            raf.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt()) // AudioFormat (1 = PCM)
            raf.writeShort(java.lang.Short.reverseBytes(channels.toShort()).toInt()) // NumChannels
            raf.writeInt(Integer.reverseBytes(sampleRate)) // SampleRate
            raf.writeInt(Integer.reverseBytes(byteRate)) // ByteRate
            raf.writeShort(java.lang.Short.reverseBytes(((channels * bitDepth) / 8).toShort()).toInt()) // BlockAlign
            raf.writeShort(java.lang.Short.reverseBytes(bitDepth.toShort()).toInt()) // BitsPerSample
            // data chunk
            raf.writeBytes("data")
            raf.writeInt(Integer.reverseBytes(rawDataSize.toInt()))

            // Write raw bytes
            rawFile.inputStream().use { input ->
                val buffer = ByteArray(4096)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    raf.write(buffer, 0, read)
                }
            }
            }
            require(tempWavFile.length() == rawDataSize + 44L) { "Invalid WAV length" }
            if (wavFile.exists() && !wavFile.delete()) {
                error("Could not replace existing sample")
            }
            require(tempWavFile.renameTo(wavFile)) { "Could not commit WAV file" }
        } catch (error: Exception) {
            tempWavFile.delete()
            throw error
        }
    }
}

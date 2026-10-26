package ts4droid.app.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import android.util.Log
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import io.github.jaredmdobson.concentus.OpusSignal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ts4droid.app.data.TalkMode
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Microphone capture + Opus encoding, and per-speaker Opus decoding + mixing + playback.
 * Everything runs at 48 kHz mono in 20 ms frames, which is what TeamSpeak's Opus codecs use.
 */
class AudioEngine(private val context: Context) {
    companion object {
        private const val TAG = "AudioEngine"
        const val SAMPLE_RATE = 48000
        const val FRAME = 960 // 20 ms
        private const val PREBUFFER = FRAME * 3 // 60 ms jitter buffer before a speaker starts playing
        private const val MAX_BUFFER = SAMPLE_RATE / 2 // drop audio older than 500 ms
        private const val VAD_HANGOVER_FRAMES = 20 // keep sending 400 ms after speech stops
        private const val TALKING_TIMEOUT_MS = 300L
    }

    /** Sends one encoded frame (or an empty array as end-of-transmission). */
    fun interface VoiceSink {
        fun send(opus: ByteArray, end: Boolean)
    }

    @Volatile var talkMode = TalkMode.PUSH_TO_TALK
    @Volatile var pushToTalkPressed = false
    @Volatile var vadThresholdDb = -40f
    @Volatile var inputMuted = false
    @Volatile var outputMuted = false
    @Volatile var playbackGain = 1f
    @Volatile var bitrate = 32000
    @Volatile var voiceSink: VoiceSink? = null

    /** Microphone level in dBFS for the UI meter. */
    val inputLevelDb = MutableStateFlow(-90f)
    /** True while our own voice is being transmitted. */
    val transmitting = MutableStateFlow(false)
    private val _talking = MutableStateFlow<Set<Int>>(emptySet())
    /** Client ids currently heard speaking. */
    val talking: StateFlow<Set<Int>> = _talking

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var captureThread: Thread? = null
    private var playbackThread: Thread? = null
    @Volatile private var running = false
    private val streams = HashMap<Int, SpeakerStream>()

    fun start(speakerphone: Boolean) {
        if (running) return
        running = true
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        setSpeakerphone(speakerphone)
        playbackThread = Thread(::playbackLoop, "ts-playback").also { it.start() }
        captureThread = Thread(::captureLoop, "ts-capture").also { it.start() }
    }

    fun stop() {
        running = false
        captureThread?.join(1000)
        playbackThread?.join(1000)
        captureThread = null
        playbackThread = null
        synchronized(streams) { streams.clear() }
        _talking.value = emptySet()
        transmitting.value = false
        inputLevelDb.value = -90f
        if (Build.VERSION.SDK_INT >= 31) audioManager.clearCommunicationDevice()
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = false
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    /** Speaker on, or the best private output: Bluetooth / wired headset, then the earpiece. */
    fun setSpeakerphone(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 31) {
            val devices = audioManager.availableCommunicationDevices
            val preferred = if (on) {
                listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
            } else {
                listOf(
                    AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                )
            }
            val device = preferred.firstNotNullOfOrNull { type -> devices.firstOrNull { it.type == type } }
            if (device != null) audioManager.setCommunicationDevice(device) else audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = on
        }
    }

    // ---------------------------------------------------------------- receiving

    private class SpeakerStream {
        val decoder = OpusDecoder(SAMPLE_RATE, 1)
        var buffer = ShortArray(MAX_BUFFER * 2)
        var size = 0
        var playing = false
        @Volatile var ended = false
        var lastPacketId = -1
        var lastPacketAt = 0L

        fun append(pcm: ShortArray, n: Int) {
            if (size + n > MAX_BUFFER) {
                // Too far behind: keep only the newest audio to bound latency.
                val drop = size + n - MAX_BUFFER
                if (drop >= size) size = 0 else {
                    System.arraycopy(buffer, drop, buffer, 0, size - drop)
                    size -= drop
                }
            }
            System.arraycopy(pcm, 0, buffer, size, n)
            size += n
        }
    }

    private val decodeBuf = ShortArray(5760)

    /**
     * Called from the network thread for every incoming voice packet. The decoder and the packet
     * bookkeeping are only touched by this thread; the stream lock is held just to hand the decoded
     * samples to the playback thread, so slow decoding never stalls playback.
     */
    fun onVoicePacket(clientId: Int, packetId: Int, opus: ByteArray) {
        if (!running) return
        val stream = synchronized(streams) { streams.getOrPut(clientId) { SpeakerStream() } }
        val now = System.currentTimeMillis()
        if (opus.isEmpty()) {
            // End of this speaker's transmission: let the buffer drain, then reset.
            synchronized(stream) {
                stream.lastPacketAt = now
                stream.ended = true
            }
            stream.lastPacketId = -1
            return
        }
        if (stream.ended) {
            synchronized(stream) { stream.ended = false }
            stream.decoder.resetState()
        }
        try {
            val last = stream.lastPacketId
            if (last >= 0) {
                val gap = (packetId - last) and 0xFFFF
                if (gap == 0 || gap > 0x8000) return // duplicate or reordered late packet
                // Conceal up to 3 lost frames with Opus packet loss concealment.
                for (i in 1 until min(gap, 4)) {
                    val n = stream.decoder.decode(null, 0, 0, decodeBuf, 0, FRAME, false)
                    synchronized(stream) { stream.append(decodeBuf, n) }
                }
            }
            stream.lastPacketId = packetId
            val n = stream.decoder.decode(opus, 0, opus.size, decodeBuf, 0, decodeBuf.size, false)
            synchronized(stream) {
                stream.lastPacketAt = now
                stream.append(decodeBuf, n)
            }
        } catch (e: Exception) {
            Log.w(TAG, "decode failed for client $clientId: $e")
        }
    }

    fun removeSpeaker(clientId: Int) {
        synchronized(streams) { streams.remove(clientId) }
    }

    private fun playbackLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(max(minBuf, FRAME * 2 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()

        val mix = IntArray(FRAME)
        val out = ShortArray(FRAME)
        var tick = 0
        try {
            while (running) {
                mix.fill(0)
                val active = synchronized(streams) { streams.entries.toList() }
                for ((_, s) in active) {
                    synchronized(s) {
                        if (!s.playing && (s.size >= PREBUFFER || (s.ended && s.size > 0))) s.playing = true
                        if (!s.playing) return@synchronized
                        val n = min(FRAME, s.size)
                        for (i in 0 until n) mix[i] += s.buffer[i]
                        System.arraycopy(s.buffer, n, s.buffer, 0, s.size - n)
                        s.size -= n
                        // Buffer ran dry: wait for a new prebuffer so we do not play choppy audio.
                        if (s.size == 0) s.playing = false
                    }
                }
                val gain = if (outputMuted) 0f else playbackGain
                for (i in 0 until FRAME) {
                    val v = (mix[i] * gain).toInt()
                    out[i] = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
                track.write(out, 0, FRAME) // blocks, which paces this loop at real time

                if (++tick % 5 == 0) {
                    val now = System.currentTimeMillis()
                    val talkingNow = active.filter { (_, s) ->
                        synchronized(s) { !s.ended && now - s.lastPacketAt < TALKING_TIMEOUT_MS }
                    }.map { it.key }.toSet()
                    if (talkingNow != _talking.value) _talking.value = talkingNow
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "playback failed", e)
        } finally {
            track.stop()
            track.release()
        }
    }

    // ---------------------------------------------------------------- sending

    @SuppressLint("MissingPermission") // checked by the UI before connecting
    private fun captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, FRAME * 2 * 4)
            )
        } catch (e: Exception) {
            Log.e(TAG, "cannot open microphone", e)
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "microphone not initialized")
            record.release()
            return
        }
        val effects = listOfNotNull(
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(record.audioSessionId) else null,
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId) else null,
            if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(record.audioSessionId) else null,
        )
        effects.forEach { runCatching { it.enabled = true } }

        val encoder = OpusEncoder(SAMPLE_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP)
        encoder.setSignalType(OpusSignal.OPUS_SIGNAL_VOICE)
        encoder.setComplexity(5) // pure-Java encoder: trade a little quality for CPU
        var currentBitrate = bitrate
        encoder.setBitrate(currentBitrate)

        val pcm = ShortArray(FRAME)
        val packet = ByteArray(1275)
        var hangover = 0
        var sending = false
        var sentFrames = 0
        var encodeNanos = 0L
        record.startRecording()
        try {
            while (running) {
                var read = 0
                while (read < FRAME && running) {
                    val n = record.read(pcm, read, FRAME - read)
                    if (n < 0) throw IllegalStateException("AudioRecord.read returned $n")
                    read += n
                }
                if (!running) break

                var sum = 0.0
                for (s in pcm) sum += s.toDouble() * s
                val rms = sqrt(sum / FRAME)
                val db = if (rms < 1) -90f else (20 * log10(rms / 32768.0)).toFloat()
                inputLevelDb.value = db

                val wantSend = !inputMuted && when (talkMode) {
                    TalkMode.PUSH_TO_TALK -> pushToTalkPressed
                    TalkMode.CONTINUOUS -> true
                    TalkMode.VOICE_ACTIVATION -> {
                        if (db >= vadThresholdDb) hangover = VAD_HANGOVER_FRAMES else if (hangover > 0) hangover--
                        hangover > 0
                    }
                }

                val sink = voiceSink
                if (wantSend && sink != null) {
                    if (bitrate != currentBitrate) {
                        currentBitrate = bitrate
                        encoder.setBitrate(currentBitrate)
                    }
                    val t0 = System.nanoTime()
                    val len = encoder.encode(pcm, 0, FRAME, packet, 0, packet.size)
                    if (len > 0) sink.send(packet.copyOf(len), false)
                    encodeNanos += System.nanoTime() - t0
                    if (++sentFrames % 250 == 0) {
                        Log.d(TAG, "sent $sentFrames frames, avg encode+send ${encodeNanos / 250 / 1000} us")
                        encodeNanos = 0
                    }
                    sending = true
                } else if (sending) {
                    sink?.send(ByteArray(0), true)
                    encoder.resetState()
                    sending = false
                }
                if (transmitting.value != sending) transmitting.value = sending
            }
        } catch (e: Exception) {
            Log.e(TAG, "capture failed", e)
        } finally {
            if (sending) voiceSink?.send(ByteArray(0), true)
            transmitting.value = false
            runCatching { record.stop() }
            record.release()
            effects.forEach { it.release() }
        }
    }
}

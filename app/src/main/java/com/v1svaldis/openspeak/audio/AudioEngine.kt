package com.v1svaldis.openspeak.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.v1svaldis.openspeak.core.NativeCore
import com.v1svaldis.openspeak.data.TalkMode
import org.json.JSONObject

/**
 * Voice controls for the UI. Capture, Opus coding, jitter buffers and mixing all run in the Rust
 * core on AAudio threads; this class only forwards settings, routes the audio (speaker, earpiece,
 * headset) and exposes what the core reports (who talks, our level).
 */
class AudioEngine(context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    var talkMode = TalkMode.PUSH_TO_TALK
        set(value) { field = value; pushSettings() }
    var pushToTalkPressed = false
        set(value) { field = value; NativeCore.nativePushToTalk(value) }
    var vadThresholdDb = -40f
        set(value) { field = value; pushSettings() }
    var playbackGain = 1f
        set(value) { field = value; pushSettings() }

    /** Microphone level in dBFS for the UI meter. */
    val inputLevelDb = MutableStateFlow(-90f)
    /** True while our own voice is being transmitted. */
    val transmitting = MutableStateFlow(false)
    private val _talking = MutableStateFlow<Set<Int>>(emptySet())
    /** Client ids currently heard speaking. */
    val talking: StateFlow<Set<Int>> = _talking

    private fun pushSettings() {
        val mode = when (talkMode) {
            TalkMode.PUSH_TO_TALK -> "ptt"
            TalkMode.VOICE_ACTIVATION -> "vad"
            TalkMode.CONTINUOUS -> "on"
        }
        NativeCore.nativeAudioSettings(
            JSONObject().put("mode", mode).put("vadThresholdDb", vadThresholdDb.toDouble())
                .put("gain", playbackGain.toDouble()).toString()
        )
    }

    /** Called for the core's periodic `audio` event. */
    fun onStats(talking: Set<Int>, tx: Boolean, levelDb: Float) {
        if (_talking.value != talking) _talking.value = talking
        if (transmitting.value != tx) transmitting.value = tx
        inputLevelDb.value = levelDb
    }

    /** Puts Android into call mode (echo cancellation, call volume) when a connection starts. */
    fun begin(speakerphone: Boolean) {
        pushSettings()
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        setSpeakerphone(speakerphone)
    }

    fun end() {
        pushToTalkPressed = false
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
}

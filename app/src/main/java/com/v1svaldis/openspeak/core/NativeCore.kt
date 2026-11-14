package com.v1svaldis.openspeak.core

/**
 * Bridge to the Rust core (`core/` → libopenspeak_core.so): TeamSpeak protocol, voice and the
 * stream signaling relay. Commands go in as strings/JSON; everything the core reports comes back
 * through [Listener.onEvent] as a JSON object with a `"t"` (type) field, on a native thread.
 */
object NativeCore {
    fun interface Listener {
        fun onEvent(json: String)
    }

    init {
        System.loadLibrary("openspeak_core")
    }

    @JvmStatic external fun nativeInit(listener: Listener)

    /** Params: address, nickname, serverPassword, channel, channelPassword, token, identity, hardwareId, inputMuted, outputMuted, seq. */
    @JvmStatic external fun nativeConnect(paramsJson: String)

    @JvmStatic external fun nativeDisconnect()

    /** Any client command; [argsJson] is an array of `[key, value]` pairs. [errorPrefix] non-null = report failures. */
    @JvmStatic external fun nativeCommand(name: String, argsJson: String, errorPrefix: String?)

    @JvmStatic external fun nativeJoinChannel(id: Long, password: String?)

    /** `{"mode": "ptt"|"vad"|"on", "vadThresholdDb": -40, "gain": 1.0}` */
    @JvmStatic external fun nativeAudioSettings(json: String)

    @JvmStatic external fun nativePushToTalk(pressed: Boolean)

    @JvmStatic external fun nativeSetMuted(input: Boolean, output: Boolean)

    @JvmStatic external fun nativeVersion(): String
}

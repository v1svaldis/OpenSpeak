package com.v1svaldis.openspeak.ts

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.v1svaldis.openspeak.VoiceService
import com.v1svaldis.openspeak.audio.AudioEngine
import com.v1svaldis.openspeak.core.NativeCore
import com.v1svaldis.openspeak.data.Bookmark
import com.v1svaldis.openspeak.data.Prefs
import com.v1svaldis.openspeak.stream.StreamManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * The single live connection to a TeamSpeak server plus the state the UI renders: channel tree,
 * clients, chat. The protocol, voice and crypto run in the Rust core ([NativeCore]); this object
 * turns the core's events into StateFlows and the UI's actions into core commands.
 */
object TsSession {
    private const val TAG = "TsSession"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var appContext: Context
    lateinit var prefs: Prefs
        private set
    lateinit var audio: AudioEngine
        private set
    lateinit var streams: StreamManager
        private set

    private val _state = MutableStateFlow<ConnState>(ConnState.Disconnected)
    val state: StateFlow<ConnState> = _state
    private val _channels = MutableStateFlow<Map<Int, TsChannel>>(emptyMap())
    val channels: StateFlow<Map<Int, TsChannel>> = _channels
    private val _clients = MutableStateFlow<Map<Int, TsClient>>(emptyMap())
    val clients: StateFlow<Map<Int, TsClient>> = _clients
    private val _ownId = MutableStateFlow(0)
    val ownId: StateFlow<Int> = _ownId
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages
    private val _serverName = MutableStateFlow("")
    val serverName: StateFlow<String> = _serverName
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** Short one-off notices for a snackbar (pokes, errors of user actions). */
    val notices: SharedFlow<String> = _notices
    private val _inputMuted = MutableStateFlow(false)
    val inputMuted: StateFlow<Boolean> = _inputMuted
    private val _outputMuted = MutableStateFlow(false)
    val outputMuted: StateFlow<Boolean> = _outputMuted

    var currentBookmark: Bookmark? = null
        private set

    /** Id of the current connection attempt; events of older attempts carry an older one and are dropped. */
    private val seq = AtomicLong(0)
    private var welcomeShown = false
    private var audioActive = false

    /** Core events are handled one at a time, in order, off the core's own threads. */
    private val events = Executors.newSingleThreadExecutor { Thread(it, "core-events") }

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = Prefs(appContext)
        audio = AudioEngine(appContext)
        streams = StreamManager(appContext, object : StreamManager.Signaling {
            override val ownClientId: Int get() = _ownId.value
            override fun command(name: String, vararg params: Pair<String, String>) = streamCommand(name, params)
        })
        NativeCore.nativeInit { json -> events.execute { onEvent(json) } }
        Log.i(TAG, "core ${NativeCore.nativeVersion()}")
    }

    val ownChannelId: Int get() = _clients.value[_ownId.value]?.channelId ?: 0

    // ------------------------------------------------------------------ connect

    fun connect(bookmark: Bookmark) {
        if (_state.value is ConnState.Connecting || _state.value == ConnState.Connected) return
        currentBookmark = bookmark
        resetModel()
        val n = seq.incrementAndGet()
        _state.value = ConnState.Connecting("Подготовка…")
        ContextCompat.startForegroundService(appContext, Intent(appContext, VoiceService::class.java))
        val hwid = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID) ?: "openspeak"
        val params = JSONObject()
            .put("seq", n)
            .put("address", bookmark.address)
            .put("nickname", bookmark.nickname)
            .put("serverPassword", bookmark.serverPassword)
            .put("channel", bookmark.defaultChannel)
            .put("channelPassword", bookmark.channelPassword)
            .put("token", bookmark.privilegeKey)
            .put("identity", prefs.identity.orEmpty())
            .put("hardwareId", hwid)
            .put("inputMuted", _inputMuted.value)
            .put("outputMuted", _outputMuted.value)
        NativeCore.nativeConnect(params.toString())
    }

    /**
     * Leaves the server or cancels a connection attempt. The UI switches back at once; the core
     * says goodbye to the server in the background.
     */
    fun disconnect() {
        seq.incrementAndGet()
        NativeCore.nativeDisconnect()
        _state.value = ConnState.Disconnected
        cleanup()
    }

    /** Called when the user dismisses an error screen. */
    fun acknowledge() {
        if (_state.value is ConnState.Failed) _state.value = ConnState.Disconnected
    }

    private fun cleanup() {
        if (audioActive) {
            audioActive = false
            audio.end()
        }
        streams.reset()
        appContext.stopService(Intent(appContext, VoiceService::class.java))
    }

    private fun resetModel() {
        _channels.value = emptyMap()
        _clients.value = emptyMap()
        _messages.value = emptyList()
        _ownId.value = 0
        _serverName.value = ""
        welcomeShown = false
    }

    // ------------------------------------------------------------------ actions

    fun joinChannel(channelId: Int, password: String? = null) =
        NativeCore.nativeJoinChannel(channelId.toLong(), password.orEmpty())

    fun sendChannelMessage(text: String) {
        if (_state.value != ConnState.Connected) return
        command("sendtextmessage", "Сообщение не отправлено", "targetmode" to "2", "target" to ownChannelId.toString(), "msg" to text)
        val me = _clients.value[_ownId.value]?.nickname.orEmpty()
        addMessage(ChatMessage(System.currentTimeMillis(), 2, me, text, own = true))
    }

    fun sendPrivateMessage(clientId: Int, text: String) {
        if (_state.value != ConnState.Connected) return
        command("sendtextmessage", "Сообщение не отправлено", "targetmode" to "1", "target" to clientId.toString(), "msg" to text)
        val name = _clients.value[clientId]?.nickname ?: "#$clientId"
        addMessage(ChatMessage(System.currentTimeMillis(), 1, "→ $name", text, own = true))
    }

    fun poke(clientId: Int, text: String) =
        command("clientpoke", "Не удалось отправить poke", "clid" to clientId.toString(), "msg" to text)

    fun setInputMuted(muted: Boolean) {
        _inputMuted.value = muted
        NativeCore.nativeSetMuted(_inputMuted.value, _outputMuted.value)
    }

    fun setOutputMuted(muted: Boolean) {
        _outputMuted.value = muted
        NativeCore.nativeSetMuted(_inputMuted.value, _outputMuted.value)
    }

    private fun command(name: String, errorPrefix: String?, vararg params: Pair<String, String>) {
        val args = JSONArray()
        params.forEach { (k, v) -> args.put(JSONArray().put(k).put(v)) }
        NativeCore.nativeCommand(name, args.toString(), errorPrefix)
    }

    /** Stream signaling; the core sends commands in the order they arrive. */
    private fun streamCommand(name: String, params: Array<out Pair<String, String>>) {
        if (_state.value != ConnState.Connected) return
        Log.d(TAG, "-> $name")
        command(name, null, *params)
    }

    /** Asks the server which streams the clients in our channel are running (it announces new ones by itself). */
    private fun refreshStreamInfo() {
        val own = ownChannelId
        val ids = _clients.value.values.filter { it.channelId == own && it.id != _ownId.value }.map { it.id }
        scope.launch {
            for (id in ids) {
                streamCommand("requeststreaminfo", arrayOf("clid" to id.toString()))
                delay(400) // stay clear of the server's flood protection
            }
        }
    }

    private var pendingProjection: Intent? = null

    /** Step 1 of screen sharing: the user granted MediaProjection; the service must become a media-projection service first. */
    fun requestScreenShare(projectionData: Intent) {
        if (_state.value != ConnState.Connected) return
        pendingProjection = projectionData
        ContextCompat.startForegroundService(
            appContext, Intent(appContext, VoiceService::class.java).setAction(VoiceService.ACTION_SCREEN_SHARE)
        )
    }

    /** Step 2, called by [VoiceService] once it runs as a media-projection foreground service. */
    fun onScreenShareServiceReady() {
        val data = pendingProjection ?: return
        pendingProjection = null
        val metrics = appContext.resources.displayMetrics
        // Keep the long side at most 1280 px: plenty for a phone screen and light on the encoder.
        val scale = minOf(1f, 1280f / maxOf(metrics.widthPixels, metrics.heightPixels))
        val w = (metrics.widthPixels * scale).toInt() and -2
        val h = (metrics.heightPixels * scale).toInt() and -2
        val nick = _clients.value[_ownId.value]?.nickname ?: "Android"
        streams.startScreenShare(data, "Экран $nick", w, h, 15)
    }

    fun stopScreenShare() {
        streams.stopScreenShare()
        appContext.startService(Intent(appContext, VoiceService::class.java).setAction(VoiceService.ACTION_SCREEN_SHARE_STOPPED))
    }

    private fun addMessage(m: ChatMessage) {
        _messages.update { (it + m).takeLast(500) }
    }

    // ------------------------------------------------------------------ core events

    private fun onEvent(json: String) {
        try {
            val e = JSONObject(json)
            if (e.optLong("seq", -1) != seq.get()) return
            when (e.optString("t")) {
                "state" -> onState(e.getString("state"), e.optString("detail"))
                "model" -> onModel(e)
                "audio" -> onAudio(e)
                "text" -> onText(e)
                "poke" -> {
                    val from = e.optString("from")
                    val text = e.optString("text")
                    _notices.tryEmit("Poke от $from: $text")
                    addMessage(ChatMessage(System.currentTimeMillis(), 0, from, "poke: $text"))
                }
                "raw" -> {
                    val args = e.getJSONObject("args")
                    val map = HashMap<String, String>()
                    args.keys().forEach { map[it] = args.getString(it) }
                    streams.onNotify(e.getString("name"), map)
                }
                "identity" -> prefs.identity = e.getString("value")
                "notice" -> _notices.tryEmit(e.getString("text"))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "bad core event: ${json.take(200)}", t)
        }
    }

    private fun onState(state: String, detail: String) {
        when (state) {
            "connecting" -> _state.value = ConnState.Connecting(detail.ifBlank { "Подключение…" })
            "connected" -> {
                if (!audioActive) {
                    audioActive = true
                    audio.talkMode = prefs.talkMode
                    audio.vadThresholdDb = prefs.vadThresholdDb
                    audio.playbackGain = prefs.playbackGain
                    audio.begin(prefs.speakerphone)
                }
                _state.value = ConnState.Connected
                refreshStreamInfo()
            }
            "failed" -> {
                cleanup()
                _state.value = ConnState.Failed(detail.ifBlank { "Соединение с сервером потеряно" })
            }
            "disconnected" -> {
                cleanup()
                _state.value = ConnState.Disconnected
            }
        }
    }

    private fun onModel(e: JSONObject) {
        _serverName.value = e.optString("server")
        val welcome = e.optString("welcome")
        if (!welcomeShown && welcome.isNotBlank()) {
            welcomeShown = true
            addMessage(ChatMessage(System.currentTimeMillis(), 0, "", welcome))
        }

        val channels = HashMap<Int, TsChannel>()
        val ch = e.getJSONArray("channels")
        for (i in 0 until ch.length()) {
            val c = ch.getJSONObject(i)
            val id = c.getInt("id")
            channels[id] = TsChannel(
                id = id,
                parentId = c.optInt("parent"),
                order = c.optInt("order"),
                name = c.optString("name"),
                topic = c.optString("topic"),
                hasPassword = c.optBoolean("hasPassword"),
                codec = c.optInt("codec", 4),
                maxClients = c.optInt("maxClients", -1),
                isDefault = c.optBoolean("isDefault"),
            )
        }
        val clients = HashMap<Int, TsClient>()
        val cl = e.getJSONArray("clients")
        for (i in 0 until cl.length()) {
            val c = cl.getJSONObject(i)
            val id = c.getInt("id")
            clients[id] = TsClient(
                id = id,
                channelId = c.optInt("channel"),
                nickname = c.optString("name"),
                type = c.optInt("type"),
                inputMuted = c.optBoolean("inputMuted"),
                outputMuted = c.optBoolean("outputMuted"),
                away = c.optBoolean("away"),
                awayMessage = c.optString("awayMessage"),
                talkPower = c.optInt("talkPower"),
                isTalker = c.optBoolean("isTalker"),
            )
        }

        val own = e.optInt("own")
        val oldClients = _clients.value
        val oldOwnChannel = ownChannelId
        if (channels != _channels.value) _channels.value = channels
        if (clients != oldClients) _clients.value = clients
        _ownId.value = own

        for (id in oldClients.keys - clients.keys) streams.onClientLeft(id)
        val newOwnChannel = clients[own]?.channelId ?: 0
        if (oldOwnChannel != 0 && newOwnChannel != oldOwnChannel && _state.value == ConnState.Connected) refreshStreamInfo()
    }

    private fun onAudio(e: JSONObject) {
        val arr = e.optJSONArray("talking")
        val talking = HashSet<Int>()
        if (arr != null) for (i in 0 until arr.length()) talking += arr.getInt(i)
        audio.onStats(talking, e.optBoolean("tx"), e.optDouble("level", -90.0).toFloat())
    }

    private fun onText(e: JSONObject) {
        // Our own messages are added to the log when sent.
        if (e.optInt("fromId") == _ownId.value) return
        addMessage(ChatMessage(System.currentTimeMillis(), e.optInt("mode"), e.optString("from"), e.optString("text")))
    }
}

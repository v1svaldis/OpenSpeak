package ts4droid.app.ts

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.github.manevolent.ts3j.command.CommandException
import com.github.manevolent.ts3j.command.ConnectionRejectedException
import com.github.manevolent.ts3j.command.SingleCommand
import com.github.manevolent.ts3j.command.parameter.CommandSingleParameter
import com.github.manevolent.ts3j.enums.CodecType
import com.github.manevolent.ts3j.event.ChannelCreateEvent
import com.github.manevolent.ts3j.event.ChannelDeletedEvent
import com.github.manevolent.ts3j.event.ChannelEditedEvent
import com.github.manevolent.ts3j.event.ChannelListEvent
import com.github.manevolent.ts3j.event.ChannelMovedEvent
import com.github.manevolent.ts3j.event.ClientJoinEvent
import com.github.manevolent.ts3j.event.ClientLeaveEvent
import com.github.manevolent.ts3j.event.ClientMovedEvent
import com.github.manevolent.ts3j.event.ClientPokeEvent
import com.github.manevolent.ts3j.event.ClientUpdatedEvent
import com.github.manevolent.ts3j.event.DisconnectedEvent
import com.github.manevolent.ts3j.event.TS3Listener
import com.github.manevolent.ts3j.event.TextMessageEvent
import com.github.manevolent.ts3j.identity.LocalIdentity
import com.github.manevolent.ts3j.protocol.ProtocolRole
import com.github.manevolent.ts3j.protocol.TS3DNS
import com.github.manevolent.ts3j.protocol.client.ClientConnectionState
import com.github.manevolent.ts3j.protocol.socket.client.LocalTeamspeakClientSocket
import com.github.manevolent.ts3j.util.Ts3Crypt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ts4droid.app.VoiceService
import ts4droid.app.audio.AudioEngine
import ts4droid.app.data.Bookmark
import ts4droid.app.data.Prefs
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.TimeoutException

/**
 * The single live connection to a TeamSpeak server (the TS3 voice protocol, which TeamSpeak 6
 * servers speak as well) plus the state the UI renders: channel tree, clients, chat.
 */
object TsSession {
    private const val TAG = "TsSession"
    private const val DEFAULT_PORT = 9987
    private const val IDENTITY_LEVEL = 8

    // A signed official client version. TeamSpeak 6 servers reject clients older than 3.6.0
    // and verify this signature, so it has to be one of the known official builds.
    private const val VERSION_PLATFORM = "Android"
    private const val VERSION_STRING = "3.6.0 [Build: 1667819930]"
    private const val VERSION_SIGN =
        "DMxIGKedSqo66DGSTd/vCa4bP0QQPP5TYXQcjML9KARTXD9cUmsSzWVSuZN5L4wQQRDcKJ2w9QWxHZP1KQrRCw=="

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var appContext: Context
    lateinit var prefs: Prefs
        private set
    lateinit var audio: AudioEngine
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
    private var client: LocalTeamspeakClientSocket? = null
    private var connectJob: Job? = null
    @Volatile private var userDisconnect = false
    @Volatile private var lastError: Throwable? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = Prefs(appContext)
        audio = AudioEngine(appContext)
    }

    val ownChannelId: Int get() = _clients.value[_ownId.value]?.channelId ?: 0

    // ------------------------------------------------------------------ connect

    fun connect(bookmark: Bookmark) {
        if (_state.value is ConnState.Connecting || _state.value == ConnState.Connected) return
        currentBookmark = bookmark
        userDisconnect = false
        lastError = null
        resetModel()
        _state.value = ConnState.Connecting("Подготовка…")
        ContextCompat.startForegroundService(appContext, Intent(appContext, VoiceService::class.java))

        connectJob = scope.launch {
            try {
                doConnect(bookmark)
            } catch (e: Throwable) {
                Log.w(TAG, "connect failed", e)
                cleanup()
                _state.value = if (userDisconnect) ConnState.Disconnected else ConnState.Failed(describe(e))
            }
        }
    }

    private suspend fun doConnect(bookmark: Bookmark) {
        val identity = prefs.loadIdentity() ?: run {
            _state.value = ConnState.Connecting("Создание личности (identity)…")
            LocalIdentity.generateNew(IDENTITY_LEVEL).also { prefs.saveIdentity(it) }
        }

        _state.value = ConnState.Connecting("Поиск сервера…")
        val address = resolve(bookmark.address)

        val c = try {
            openConnectionWaitingForGhost(bookmark, identity, address)
        } catch (e: ConnectionRejectedException) {
            // The server wants a stronger identity: raise its security level once and retry.
            val needed = e.extraMessage?.toIntOrNull()
            if (e.errorId != ConnectionRejectedException.CLIENT_COULD_NOT_VALIDATE_IDENTITY || needed == null) throw e
            cleanup(stopService = false)
            _state.value = ConnState.Connecting("Повышение уровня безопасности личности до $needed…")
            identity.improveSecurity(needed)
            prefs.saveIdentity(identity)
            lastError = null
            openConnection(bookmark, identity, address)
        }
        onConnected(c)
    }

    /**
     * If the app was killed or lost its network, our previous session stays on the server until it
     * times out and the server refuses the same identity as a "clone". Wait that out instead of failing.
     */
    private suspend fun openConnectionWaitingForGhost(
        bookmark: Bookmark, identity: LocalIdentity, address: InetSocketAddress,
    ): LocalTeamspeakClientSocket {
        val deadline = System.currentTimeMillis() + 90_000
        while (true) {
            try {
                return openConnection(bookmark, identity, address)
            } catch (e: ConnectionRejectedException) {
                if (e.errorId != CLIENT_TOO_MANY_CLONES || System.currentTimeMillis() > deadline || userDisconnect) throw e
                cleanup(stopService = false)
                lastError = null
                _state.value = ConnState.Connecting("Предыдущее подключение ещё активно на сервере, ждём его завершения…")
                delay(5000)
            }
        }
    }

    private const val CLIENT_TOO_MANY_CLONES = 0x0209

    private fun openConnection(
        bookmark: Bookmark, identity: LocalIdentity, address: InetSocketAddress,
    ): LocalTeamspeakClientSocket {
        val c = LocalTeamspeakClientSocket()
        client = c
        c.setIdentity(identity)
        c.setNickname(bookmark.nickname.ifBlank { "Android" })
        c.setClientVersion(VERSION_PLATFORM, VERSION_STRING, VERSION_SIGN)
        c.setHWID(android.provider.Settings.Secure.getString(appContext.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "ts4droid")
        // TeamSpeak clients send passwords as base64(SHA-1(password)), never in plain text.
        if (bookmark.defaultChannel.isNotBlank()) {
            c.setOption("client.default_channel", bookmark.defaultChannel)
            if (bookmark.channelPassword.isNotEmpty())
                c.setOption("client.default_channel_password", Ts3Crypt.hashPassword(bookmark.channelPassword))
        }
        if (bookmark.serverPassword.isNotEmpty())
            c.setOption("client.server_password", Ts3Crypt.hashPassword(bookmark.serverPassword))
        if (bookmark.privilegeKey.isNotBlank()) c.setOption("client.default_token", bookmark.privilegeKey)
        c.setExceptionHandler { e ->
            Log.w(TAG, "protocol error", e)
            lastError = e
        }
        c.addListener(listener)
        c.setVoiceHandler { p ->
            val codec = p.codecType
            if (codec == CodecType.OPUS_VOICE || codec == CodecType.OPUS_MUSIC)
                audio.onVoicePacket(p.clientId, p.packetId, p.codecData)
        }

        _state.value = ConnState.Connecting("Подключение к ${address.hostString}:${address.port}…")
        try {
            c.connect(address, null, 20000L)
        } catch (e: Exception) {
            // Surface the server's own reason (wrong password, banned, ...) when there is one.
            val rejected = (generateSequence<Throwable>(e) { it.cause } + generateSequence(lastError) { it.cause })
                .filterIsInstance<ConnectionRejectedException>().firstOrNull()
            throw rejected ?: (if (e is TimeoutException) lastError else null) ?: e
        }
        return c
    }

    private suspend fun onConnected(c: LocalTeamspeakClientSocket) {
        _ownId.value = c.clientId
        _serverName.value = c.serverProperties["virtualserver_name"].orEmpty()
        c.serverProperties["virtualserver_welcomemessage"]?.takeIf { it.isNotBlank() }?.let {
            addMessage(ChatMessage(System.currentTimeMillis(), 0, "", it))
        }

        audio.talkMode = prefs.talkMode
        audio.vadThresholdDb = prefs.vadThresholdDb
        audio.playbackGain = prefs.playbackGain
        audio.inputMuted = _inputMuted.value
        audio.outputMuted = _outputMuted.value
        audio.voiceSink = AudioEngine.VoiceSink { opus, end ->
            val cl = client ?: return@VoiceSink
            try {
                val codec = sendCodec()
                if (end) cl.sendVoiceEnd(codec) else cl.sendVoice(codec, opus)
            } catch (e: Exception) {
                Log.w(TAG, "voice send failed: $e")
            }
        }
        updateBitrate()
        audio.start(prefs.speakerphone)
        _state.value = ConnState.Connected
        if (_inputMuted.value || _outputMuted.value) pushMuteState()

        // ts3j notices timeouts itself but does not always fire an event; watch the state.
        while (scope.isActive && client === c) {
            delay(1000)
            if (c.state == ClientConnectionState.DISCONNECTED) {
                if (!userDisconnect && _state.value == ConnState.Connected) {
                    cleanup()
                    _state.value = ConnState.Failed("Соединение с сервером потеряно")
                }
                break
            }
        }
    }

    private fun resolve(input: String): InetSocketAddress {
        var host = input.trim().removePrefix("ts3server://").substringBefore('?').trimEnd('/')
        var port: Int? = null
        // host:port, [v6]:port, or bare v6
        if (host.startsWith("[")) {
            val end = host.indexOf(']')
            port = host.substring(end + 1).removePrefix(":").toIntOrNull()
            host = host.substring(1, end)
        } else if (host.count { it == ':' } == 1) {
            port = host.substringAfter(':').toIntOrNull()
            host = host.substringBefore(':')
        }
        require(host.isNotBlank()) { "Не указан адрес сервера" }
        if (port == null && !host.first().isDigit() && !host.contains(':')) {
            // Domain names may publish the real address via TSDNS / SRV records.
            runCatching { TS3DNS.lookup(host).firstOrNull() }.getOrNull()?.let { return it }
        }
        return InetSocketAddress(InetAddress.getByName(host), port ?: DEFAULT_PORT)
    }

    fun disconnect() {
        userDisconnect = true
        val c = client
        scope.launch {
            runCatching { withTimeoutOrNull(3000) { c?.disconnect("Leaving") } }
            cleanup()
            connectJob?.cancel()
            _state.value = ConnState.Disconnected
        }
    }

    /** Called when the user dismisses an error screen. */
    fun acknowledge() {
        if (_state.value is ConnState.Failed) _state.value = ConnState.Disconnected
    }

    private fun cleanup(stopService: Boolean = true) {
        audio.voiceSink = null
        audio.stop()
        val c = client
        client = null
        runCatching { c?.close() }
        if (stopService) appContext.stopService(Intent(appContext, VoiceService::class.java))
    }

    private fun resetModel() {
        _channels.value = emptyMap()
        _clients.value = emptyMap()
        _messages.value = emptyList()
        _ownId.value = 0
        _serverName.value = ""
    }

    private fun describe(e: Throwable): String {
        var t: Throwable = e
        while (t.cause != null && (t.message.isNullOrBlank() || t.message!!.startsWith("Problem establishing") ||
                    t is java.io.IOException && t.cause is com.github.manevolent.ts3j.command.CommandProcessException)
        ) t = t.cause!!
        return when {
            t is ConnectionRejectedException -> rejectionText(t)
            t is TimeoutException -> "Сервер не отвечает (проверьте адрес и порт UDP)"
            t is java.net.UnknownHostException -> "Не удалось найти сервер: ${t.message}"
            t is IllegalArgumentException -> t.message ?: t.toString()
            else -> t.message ?: t.toString()
        }
    }

    private fun rejectionText(e: ConnectionRejectedException): String = when (e.errorId) {
        0x0207 -> "Сервер требует уровень безопасности личности ${e.extraMessage}"
        0x0209 -> "Эта личность уже подключена к серверу"
        0x0403 -> "Сервер переполнен"
        0x0404 -> "Неверный пароль сервера"
        0x030d -> "Неверный пароль канала"
        0x0309 -> "Канал переполнен"
        0x0d01 -> "Вы забанены на этом сервере${e.extraMessage?.let { ": $it" } ?: ""}"
        else -> e.message ?: "Сервер отклонил подключение (код ${e.errorId})"
    }

    // ------------------------------------------------------------------ actions

    fun joinChannel(channelId: Int, password: String? = null) = command("Не удалось перейти в канал") {
        it.joinChannel(channelId, password?.takeIf { p -> p.isNotEmpty() })
    }

    fun sendChannelMessage(text: String) = command("Сообщение не отправлено") {
        it.sendChannelMessage(ownChannelId, text)
    }

    fun sendPrivateMessage(clientId: Int, text: String) = command("Сообщение не отправлено") {
        it.sendPrivateMessage(clientId, text)
        val name = _clients.value[clientId]?.nickname ?: "#$clientId"
        addMessage(ChatMessage(System.currentTimeMillis(), 1, "→ $name", text, own = true))
    }

    fun poke(clientId: Int, text: String) = command("Не удалось отправить poke") { it.clientPoke(clientId, text) }

    fun setInputMuted(muted: Boolean) {
        _inputMuted.value = muted
        audio.inputMuted = muted
        pushMuteState()
    }

    fun setOutputMuted(muted: Boolean) {
        _outputMuted.value = muted
        audio.outputMuted = muted
        pushMuteState()
    }

    private fun pushMuteState() = command(null) {
        it.executeCommand(
            SingleCommand(
                "clientupdate", ProtocolRole.CLIENT,
                CommandSingleParameter("client_input_muted", if (_inputMuted.value) "1" else "0"),
                CommandSingleParameter("client_output_muted", if (_outputMuted.value) "1" else "0"),
            )
        ).get()
    }

    private fun command(errorPrefix: String?, block: (LocalTeamspeakClientSocket) -> Unit) {
        val c = client ?: return
        scope.launch {
            try {
                block(c)
            } catch (e: Exception) {
                Log.w(TAG, "command failed", e)
                if (errorPrefix != null) {
                    val msg = (e as? CommandException)?.message ?: e.cause?.message ?: e.message ?: e.toString()
                    _notices.tryEmit("$errorPrefix: $msg")
                }
            }
        }
    }

    private fun sendCodec(): CodecType =
        when (_channels.value[ownChannelId]?.codec) {
            CodecType.OPUS_MUSIC.index -> CodecType.OPUS_MUSIC
            else -> CodecType.OPUS_VOICE
        }

    private val channelQuality = HashMap<Int, Int>()

    private fun updateBitrate() {
        val q = channelQuality[ownChannelId] ?: 6
        val music = sendCodec() == CodecType.OPUS_MUSIC
        audio.bitrate = if (music) 32000 + q * 6000 else 12000 + q * 3000
    }

    private fun addMessage(m: ChatMessage) {
        _messages.update { (it + m).takeLast(500) }
    }

    // ------------------------------------------------------------------ server events

    private fun Map<String, String>.int(key: String, default: Int = 0) = this[key]?.toIntOrNull() ?: default
    private fun Map<String, String>.bool(key: String) = this[key] == "1"

    private fun channelFrom(m: Map<String, String>, old: TsChannel?): TsChannel {
        m["channel_codec_quality"]?.toIntOrNull()?.let { channelQuality[m.int("cid")] = it }
        return TsChannel(
            id = m.int("cid"),
            parentId = m["cpid"]?.toIntOrNull() ?: m["pid"]?.toIntOrNull() ?: old?.parentId ?: 0,
            order = m["channel_order"]?.toIntOrNull() ?: old?.order ?: 0,
            name = m["channel_name"] ?: old?.name ?: "",
            topic = m["channel_topic"] ?: old?.topic ?: "",
            hasPassword = m["channel_flag_password"]?.let { it == "1" } ?: old?.hasPassword ?: false,
            codec = m["channel_codec"]?.toIntOrNull() ?: old?.codec ?: 4,
            maxClients = m["channel_maxclients"]?.toIntOrNull() ?: old?.maxClients ?: -1,
            isDefault = m["channel_flag_default"]?.let { it == "1" } ?: old?.isDefault ?: false,
        )
    }

    private fun clientUpdate(old: TsClient, m: Map<String, String>) = old.copy(
        nickname = m["client_nickname"] ?: old.nickname,
        inputMuted = m["client_input_muted"]?.let { it == "1" } ?: old.inputMuted,
        outputMuted = m["client_output_muted"]?.let { it == "1" } ?: old.outputMuted,
        away = m["client_away"]?.let { it == "1" } ?: old.away,
        awayMessage = m["client_away_message"] ?: old.awayMessage,
        talkPower = m["client_talk_power"]?.toIntOrNull() ?: old.talkPower,
        isTalker = m["client_is_talker"]?.let { it == "1" } ?: old.isTalker,
    )

    private val listener = object : TS3Listener {
        override fun onChannelList(e: ChannelListEvent) {
            val m = e.map
            _channels.update { it + (e.channelId to channelFrom(m, it[e.channelId])) }
        }

        override fun onChannelCreate(e: ChannelCreateEvent) {
            _channels.update { it + (e.channelId to channelFrom(e.map, null)) }
        }

        override fun onChannelEdit(e: ChannelEditedEvent) {
            _channels.update { it + (e.channelId to channelFrom(e.map, it[e.channelId])) }
            if (e.channelId == ownChannelId) updateBitrate()
        }

        override fun onChannelMoved(e: ChannelMovedEvent) {
            _channels.update { map ->
                val old = map[e.channelId] ?: return@update map
                map + (e.channelId to old.copy(parentId = e.channelParentId, order = e.channelOrder))
            }
        }

        override fun onChannelDeleted(e: ChannelDeletedEvent) {
            _channels.update { it - e.channelId }
        }

        override fun onClientJoin(e: ClientJoinEvent) {
            val m = e.map
            val c = TsClient(
                id = e.clientId,
                channelId = e.clientTargetId,
                nickname = m["client_nickname"].orEmpty(),
                uid = m["client_unique_identifier"].orEmpty(),
                type = m.int("client_type"),
                inputMuted = m.bool("client_input_muted"),
                outputMuted = m.bool("client_output_muted"),
                away = m.bool("client_away"),
                awayMessage = m["client_away_message"].orEmpty(),
                talkPower = m.int("client_talk_power"),
                isTalker = m.bool("client_is_talker"),
                platform = m["client_platform"].orEmpty(),
            )
            _clients.update { it + (c.id to c) }
        }

        override fun onClientMoved(e: ClientMovedEvent) {
            _clients.update { map ->
                val old = map[e.clientId] ?: return@update map
                map + (e.clientId to old.copy(channelId = e.targetChannelId))
            }
            if (e.clientId == _ownId.value) {
                updateBitrate()
                // Moved by someone else (or the server) — tell the user.
                if (e.map.int("reasonid") == 1 && e.invokerId != _ownId.value)
                    _notices.tryEmit("Вас переместил ${e.invokerName}")
            }
        }

        override fun onClientLeave(e: ClientLeaveEvent) {
            _clients.update { it - e.clientId }
            audio.removeSpeaker(e.clientId)
        }

        override fun onClientChanged(e: ClientUpdatedEvent) {
            _clients.update { map ->
                val old = map[e.clientId] ?: return@update map
                map + (e.clientId to clientUpdate(old, e.map))
            }
        }

        override fun onTextMessage(e: TextMessageEvent) {
            val mode = e.map.int("targetmode")
            val own = e.invokerId == _ownId.value
            // Our own private messages are echoed into the log when sent, with the recipient name.
            if (own && mode == 1) return
            addMessage(ChatMessage(System.currentTimeMillis(), mode, e.invokerName, e.message, own))
        }

        override fun onClientPoke(e: ClientPokeEvent) {
            val text = e.map["msg"].orEmpty()
            _notices.tryEmit("Poke от ${e.invokerName}: $text")
            addMessage(ChatMessage(System.currentTimeMillis(), 0, e.invokerName, "poke: $text"))
        }

        override fun onDisconnected(e: DisconnectedEvent) {
            if (userDisconnect) return
            val reason = e.map["reasonmsg"].orEmpty()
            val text = when (e.map.int("reasonid")) {
                5 -> "Вас выгнали с сервера (${e.invokerName})"
                6 -> "Вас забанили (${e.invokerName})"
                11 -> "Сервер остановлен"
                else -> "Отключено сервером"
            } + if (reason.isNotBlank()) ": $reason" else ""
            scope.launch {
                cleanup()
                _state.value = ConnState.Failed(text)
            }
        }
    }
}

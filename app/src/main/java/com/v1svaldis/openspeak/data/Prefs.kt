package com.v1svaldis.openspeak.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A saved server ("bookmark"). */
data class Bookmark(
    val id: Long = System.currentTimeMillis(),
    val label: String = "",
    val address: String = "",
    val nickname: String = "",
    val serverPassword: String = "",
    val defaultChannel: String = "",
    val channelPassword: String = "",
    val privilegeKey: String = "",
) {
    val title: String get() = label.ifBlank { address }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("label", label).put("address", address).put("nickname", nickname)
        .put("serverPassword", serverPassword).put("defaultChannel", defaultChannel)
        .put("channelPassword", channelPassword).put("privilegeKey", privilegeKey)

    companion object {
        fun fromJson(o: JSONObject) = Bookmark(
            id = o.optLong("id"),
            label = o.optString("label"),
            address = o.optString("address"),
            nickname = o.optString("nickname"),
            serverPassword = o.optString("serverPassword"),
            defaultChannel = o.optString("defaultChannel"),
            channelPassword = o.optString("channelPassword"),
            privilegeKey = o.optString("privilegeKey"),
        )
    }
}

enum class TalkMode { PUSH_TO_TALK, VOICE_ACTIVATION, CONTINUOUS }

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("openspeak", Context.MODE_PRIVATE)

    var bookmarks: List<Bookmark>
        get() {
            val arr = JSONArray(sp.getString("bookmarks", "[]"))
            return (0 until arr.length()).map { Bookmark.fromJson(arr.getJSONObject(it)) }
        }
        set(value) {
            val arr = JSONArray()
            value.forEach { arr.put(it.toJson()) }
            sp.edit().putString("bookmarks", arr.toString()).apply()
        }

    var talkMode: TalkMode
        get() = runCatching { TalkMode.valueOf(sp.getString("talkMode", null)!!) }.getOrDefault(TalkMode.PUSH_TO_TALK)
        set(value) = sp.edit().putString("talkMode", value.name).apply()

    /** Voice activation threshold in dBFS (-60..0). */
    var vadThresholdDb: Float
        get() = sp.getFloat("vadThresholdDb", -40f)
        set(value) = sp.edit().putFloat("vadThresholdDb", value).apply()

    var speakerphone: Boolean
        get() = sp.getBoolean("speakerphone", true)
        set(value) = sp.edit().putBoolean("speakerphone", value).apply()

    /** Playback gain, 0.25..4.0 */
    var playbackGain: Float
        get() = sp.getFloat("playbackGain", 1f)
        set(value) = sp.edit().putFloat("playbackGain", value).apply()

    /**
     * Identity as TeamSpeak's own string (`<counter>V<key>`), the value of `identity=` in the desktop
     * client's .ini export. Older versions stored the whole .ini; it is read transparently.
     */
    var identity: String?
        get() = sp.getString("identity", null)?.let(::parseIdentity)
        set(value) = sp.edit().putString("identity", value).apply()

    /** The identity as a desktop-compatible .ini export. */
    val identityExport: String?
        get() = identity?.let { "[Identity]\nidentity=\"$it\"\n" }

    /** Accepts an .ini export or the bare string; false if it is not an identity. */
    fun importIdentity(text: String): Boolean {
        val id = parseIdentity(text) ?: return false
        identity = id
        return true
    }

    companion object {
        private val IDENTITY = Regex("""(\d+V[A-Za-z0-9+/]+=*)""")

        fun parseIdentity(text: String): String? {
            val value = Regex("""identity\s*=\s*"?([^"\r\n]+)""").find(text)?.groupValues?.get(1) ?: text
            return IDENTITY.matchEntire(value.trim().trim('"'))?.value
        }
    }
}

package com.v1svaldis.openspeak.ts

data class TsChannel(
    val id: Int,
    val parentId: Int,
    /** Id of the sibling channel this one is sorted below (0 = first). */
    val order: Int,
    val name: String,
    val topic: String = "",
    val hasPassword: Boolean = false,
    val codec: Int = 4,
    val maxClients: Int = -1,
    val isDefault: Boolean = false,
)

data class TsClient(
    val id: Int,
    val channelId: Int,
    val nickname: String,
    val uid: String = "",
    /** 0 = regular client, 1 = server query. */
    val type: Int = 0,
    val inputMuted: Boolean = false,
    val outputMuted: Boolean = false,
    val away: Boolean = false,
    val awayMessage: String = "",
    val talkPower: Int = 0,
    val isTalker: Boolean = false,
    val platform: String = "",
)

data class ChatMessage(
    val time: Long,
    /** 1 = private, 2 = channel, 3 = server, 0 = system notice. */
    val targetMode: Int,
    val fromName: String,
    val text: String,
    val own: Boolean = false,
)

sealed interface ConnState {
    data object Disconnected : ConnState
    data class Connecting(val step: String) : ConnState
    data object Connected : ConnState
    data class Failed(val reason: String) : ConnState
}

/** One row of the flattened channel tree for display. */
sealed interface TreeRow {
    data class ChannelRow(val channel: TsChannel, val depth: Int, val userCount: Int) : TreeRow
    data class ClientRow(val client: TsClient, val depth: Int) : TreeRow
}

/** Turns the flat channel and client maps into the ordered tree a TeamSpeak client shows. */
fun buildTree(channels: Map<Int, TsChannel>, clients: Map<Int, TsClient>): List<TreeRow> {
    val byParent = channels.values.groupBy { it.parentId }
    val clientsByChannel = clients.values
        .filter { it.type == 0 }
        .groupBy { it.channelId }
        .mapValues { (_, list) -> list.sortedWith(compareByDescending<TsClient> { it.talkPower }.thenBy { it.nickname.lowercase() }) }

    fun ordered(siblings: List<TsChannel>): List<TsChannel> {
        // channel_order points at the sibling directly above; walk the chain starting at 0.
        val byOrder = siblings.associateBy { it.order }
        val result = ArrayList<TsChannel>(siblings.size)
        val seen = HashSet<Int>()
        var prev = 0
        while (true) {
            val next = byOrder[prev] ?: break
            if (!seen.add(next.id)) break
            result += next
            prev = next.id
        }
        // Anything the chain did not reach (inconsistent data) goes last, by id.
        siblings.filter { it.id !in seen }.sortedBy { it.id }.forEach { result += it }
        return result
    }

    val rows = ArrayList<TreeRow>()
    fun walk(parent: Int, depth: Int) {
        for (ch in ordered(byParent[parent].orEmpty())) {
            val inChannel = clientsByChannel[ch.id].orEmpty()
            rows += TreeRow.ChannelRow(ch, depth, inChannel.size)
            inChannel.forEach { rows += TreeRow.ClientRow(it, depth + 1) }
            walk(ch.id, depth + 1)
        }
    }
    walk(0, 0)
    return rows
}

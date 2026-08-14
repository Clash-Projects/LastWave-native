package com.lastwave.app.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/** Same Last.fm single-object-vs-array quirk as [RecentTracksListSerializer]
 *  and [TopTrackListSerializer], applied to user.getfriends' `user` field —
 *  a user with exactly one friend gets a bare object back instead of a
 *  one-element array. */
object FriendListSerializer : KSerializer<List<FriendEntry>> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("FriendList")

    override fun deserialize(decoder: Decoder): List<FriendEntry> {
        val input = decoder as? JsonDecoder
            ?: error("FriendListSerializer only supports JSON decoding")
        val element = input.decodeJsonElement()
        return when (element) {
            is JsonArray -> element.map { input.json.decodeFromJsonElement(FriendEntry.serializer(), it) }
            is JsonObject -> listOf(input.json.decodeFromJsonElement(FriendEntry.serializer(), element))
            else -> emptyList()
        }
    }

    override fun serialize(encoder: Encoder, value: List<FriendEntry>) {
        throw UnsupportedOperationException("Friend list is read-only — the app never sends it back to Last.fm")
    }
}

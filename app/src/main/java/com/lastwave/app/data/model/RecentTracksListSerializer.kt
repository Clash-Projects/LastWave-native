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

/**
 * Last.fm's `track` (and `artist`/`album` in other list responses) comes back
 * as a single JSON object when there's exactly one result, or a JSON array
 * when there's more than one — there is no consistent envelope. home.js
 * handled this with `Array.isArray(raw) ? raw : [raw]`; this is the same
 * fix at the deserialization layer so every call site just sees a List.
 */
object RecentTracksListSerializer : KSerializer<RecentTracksList> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("RecentTracksList")

    override fun deserialize(decoder: Decoder): RecentTracksList {
        val input = decoder as? JsonDecoder
            ?: error("RecentTracksListSerializer only supports JSON decoding")
        val element = input.decodeJsonElement()
        val tracks = when (element) {
            is JsonArray -> element.map { input.json.decodeFromJsonElement(RecentTrack.serializer(), it) }
            is JsonObject -> listOf(input.json.decodeFromJsonElement(RecentTrack.serializer(), element))
            else -> emptyList()
        }
        return RecentTracksList(tracks)
    }

    override fun serialize(encoder: Encoder, value: RecentTracksList) {
        throw UnsupportedOperationException("RecentTracksList is read-only — the app never sends it back to Last.fm")
    }
}

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

/** Same Last.fm single-object-vs-array quirk as [RecentTracksListSerializer],
 *  applied to user.gettoptracks' `track` field. */
object TopTrackListSerializer : KSerializer<TopTrackList> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("TopTrackList")

    override fun deserialize(decoder: Decoder): TopTrackList {
        val input = decoder as? JsonDecoder
            ?: error("TopTrackListSerializer only supports JSON decoding")
        val element = input.decodeJsonElement()
        val tracks = when (element) {
            is JsonArray -> element.map { input.json.decodeFromJsonElement(TopTrackEntry.serializer(), it) }
            is JsonObject -> listOf(input.json.decodeFromJsonElement(TopTrackEntry.serializer(), element))
            else -> emptyList()
        }
        return TopTrackList(tracks)
    }

    override fun serialize(encoder: Encoder, value: TopTrackList) {
        throw UnsupportedOperationException("TopTrackList is read-only — the app never sends it back to Last.fm")
    }
}

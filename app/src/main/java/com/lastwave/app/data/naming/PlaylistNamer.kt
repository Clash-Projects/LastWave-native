package com.lastwave.app.data.naming

/**
 * Faithful port of app.js's playlist naming system (v4): every generated or
 * saved playlist gets a unique, premium-feeling 1-3 word name assembled from
 * large adjective/noun word banks (colors, weather, light, mood, space,
 * nature, mythology, time) — e.g. "Velvet Horizon", "Neon Mirage",
 * "Arctic Dreams". Never appends numbers; if a composed name collides with
 * an existing title, another is composed from the same banks until unique.
 *
 * Used by every playlist-creation flow: Generate (all modes), Start Mix From
 * Track, Genre Detail's Start Mix / Discover More / Explore This Genre, and
 * Discover's Save As Playlist — exactly as in the original, so names stay
 * varied across the whole app rather than repeating within one mode/genre.
 */
object PlaylistNamer {

    private val ADJECTIVES = listOf(
        // colors
        "Crimson", "Violet", "Amber", "Emerald", "Sapphire", "Scarlet", "Ivory", "Obsidian",
        "Cobalt", "Copper", "Golden", "Silver", "Jade", "Ruby", "Indigo", "Pearl",
        // weather / light
        "Electric", "Radiant", "Frozen", "Stormy", "Misty", "Blazing", "Frosted", "Luminous",
        "Fading", "Glowing", "Shimmering", "Windswept", "Sunlit", "Moonlit", "Hazy", "Rainswept",
        // mood
        "Silent", "Restless", "Wild", "Faded", "Quiet", "Wistful", "Feral", "Serene",
        "Fractured", "Wandering", "Distant", "Hollow", "Tender", "Bold", "Gentle", "Dreamy",
        // space / time
        "Midnight", "Lunar", "Solar", "Arctic", "Celestial", "Nocturnal", "Endless", "Ancient",
        "Eternal", "Timeless", "Drifting", "Hidden", "Velvet", "Neon", "Glass", "Forgotten",
    )

    private val NOUNS = listOf(
        // space
        "Horizon", "Orbit", "Nebula", "Eclipse", "Comet", "Nova", "Zenith", "Meridian",
        "Solstice", "Constellation", "Cosmos", "Galaxy", "Halo", "Aurora", "Starlight", "Satellite",
        // nature
        "Tides", "Storm", "Tempest", "Cascade", "Glacier", "Canyon", "Meadow", "Grove",
        "Summit", "Valley", "Wildfire", "Monsoon", "Frost", "Ember", "Bloom", "Thicket",
        "Harbor", "Lagoon", "Prairie", "Oasis",
        // mythology
        "Phoenix", "Oracle", "Siren", "Valkyrie", "Elysium", "Odyssey", "Atlas", "Titan",
        "Muse", "Chimera",
        // emotion / abstract
        "Echo", "Echoes", "Mirage", "Dreams", "Reverie", "Pulse", "Drift", "Wanderlust",
        "Shadows", "Sparks", "Voyage", "Whisper", "Serenade", "Solitude",
        // places / time
        "Skyline", "Twilight", "Daybreak", "Sanctuary", "Labyrinth", "Wilderness",
    )

    private fun composeOnce(): String {
        val r = Math.random()
        return when {
            r < 0.12 -> NOUNS.random()
            r < 0.90 -> "${ADJECTIVES.random()} ${NOUNS.random()}"
            else -> {
                val a1 = ADJECTIVES.random()
                var a2 = ADJECTIVES.random()
                if (a2 == a1) a2 = ADJECTIVES.random()
                "$a1 $a2 ${NOUNS.random()}"
            }
        }
    }

    private fun composeThreeWord(): String {
        val a1 = ADJECTIVES.random()
        var a2 = ADJECTIVES.random()
        if (a2 == a1) a2 = ADJECTIVES.random()
        return "$a1 $a2 ${NOUNS.random()}"
    }

    /**
     * Generates a name guaranteed unique among [existingTitles] (case-
     * insensitive, trimmed) — never appends numbers. Retries up to 80 times
     * with 1-3 word compositions, then 200 more times widened to 3-word
     * combinations, before giving up (practically unreachable given the
     * bank sizes: tens of thousands of combinations).
     */
    fun generateUniqueName(existingTitles: Collection<String>): String {
        val taken = existingTitles.map { it.lowercase().trim() }.toHashSet()
        var candidate = ""
        repeat(80) {
            candidate = composeOnce()
            if (candidate.lowercase() !in taken) return candidate
        }
        repeat(200) {
            candidate = composeThreeWord()
            if (candidate.lowercase() !in taken) return candidate
        }
        return candidate
    }

    /** Subtitle text shown under a playlist title — port of
     *  _generatePlaylistSubtitle(). [mode] matches PlaylistMode.storageValue. */
    fun subtitleFor(mode: String, tagInput: String? = null, seedTrackName: String? = null, seedArtistInput: String? = null): String =
        when (mode) {
            "tag" -> if (!tagInput.isNullOrBlank()) {
                "Genre Mix \u00b7 ${tagInput.split(" ").joinToString(" ") { w -> w.replaceFirstChar(Char::uppercase) }}"
            } else {
                "Genre Mix"
            }
            "recent" -> "Recent Tracks"
            "top", "library" -> "Top Tracks"
            "mix" -> "My Mix"
            "recommendations" -> "Recommendations"
            "similar-tracks", "start-mix" -> if (!seedTrackName.isNullOrBlank()) "Track Mix \u00b7 $seedTrackName" else "Track Mix"
            "similar-artists" -> if (!seedArtistInput.isNullOrBlank()) "Artist Mix \u00b7 $seedArtistInput" else "Artist Mix"
            "discover" -> "Discover Feed"
            else -> "Mix"
        }
}

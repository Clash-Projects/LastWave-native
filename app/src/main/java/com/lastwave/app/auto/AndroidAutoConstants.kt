package com.lastwave.app.auto

object AndroidAutoConstants {
    const val MEDIA_ROOT_ID = "root_lastwave"
    const val CATEGORY_RECENTS = "category_recents"
    const val CATEGORY_FAVORITES = "category_favorites"
    const val CATEGORY_PLAYLISTS = "category_playlists"
    const val CATEGORY_OFFLINE = "category_offline"
    const val CATEGORY_TOP_TRACKS = "category_top_tracks"
    const val CATEGORY_RECOMMENDED = "category_recommended"

    const val PREFIX_PLAYLIST = "playlist_"
    const val PREFIX_TRACK = "track_"
    const val PREFIX_OFFLINE = "offline_"

    const val ACTION_FAVORITE = "com.lastwave.app.ACTION_FAVORITE"
    const val ACTION_TOGGLE_SHUFFLE = "com.lastwave.app.ACTION_TOGGLE_SHUFFLE"
    const val ACTION_CYCLE_REPEAT = "com.lastwave.app.ACTION_CYCLE_REPEAT"

    // Content style keys for modern Android Auto Media Center
    const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
    const val CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
    const val CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
    const val CONTENT_STYLE_GRID_ITEM_HINT_VALUE = 2
    const val CONTENT_STYLE_LIST_ITEM_HINT_VALUE = 1
}

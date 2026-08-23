package com.lastwave.app.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloaded_tracks")
data class DownloadedTrackEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val artist: String,
    val album: String = "",
    val artworkUrl: String? = null,
    val filePath: String,
    val mediaStoreUri: String? = null,
    val fileSizeBytes: Long = 0L,
    val formatBadge: String = "AUDIO",
    val durationMs: Long = 0L,
    val bitrateKbps: Int? = null,
    val isQobuz: Boolean = false,
    val hasLyrics: Boolean = false,
    val syncedLyrics: String? = null,
    val plainLyrics: String? = null,
    val lrcFilePath: String? = null,
    val downloadedAtMillis: Long = System.currentTimeMillis(),
)


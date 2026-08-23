package com.lastwave.app.data.download

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioTagWriter @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    companion object {
        private const val TAG = "AudioTagWriter"
        private const val FRAME_TITLE = "TIT2"
        private const val FRAME_ARTIST = "TPE1"
        private const val FRAME_ALBUM = "TALB"
        private const val FRAME_PICTURE = "APIC"
        private const val PIC_TYPE_COVER_FRONT: Byte = 0x03
    }

    /**
     * Embeds metadata (Title, Artist, Album) and cover art directly into the audio file.
     * Uses pure ID3v2.3 tag construction compatible with Android MediaPlayer, ExoPlayer,
     * Poweramp, Samsung Music, Apple Music, and Windows Media Player.
     */
    fun embedMetadata(
        audioFile: File,
        title: String,
        artist: String,
        album: String? = null,
        artworkUrl: String? = null,
    ) {
        if (!audioFile.exists() || audioFile.length() <= 0) return

        try {
            val artworkBytes = if (!artworkUrl.isNullOrBlank()) downloadArtworkBytes(artworkUrl) else null
            val id3TagBytes = buildId3v2Tag(
                title = title,
                artist = artist,
                album = album,
                artworkBytes = artworkBytes,
            )

            // Read original audio payload (skipping existing ID3v2 header if present)
            val audioPayloadOffset = detectExistingId3v2TagLength(audioFile)
            val tempTaggedFile = File.createTempFile("tagged_", ".tmp", audioFile.parentFile)

            FileOutputStream(tempTaggedFile).use { out ->
                out.write(id3TagBytes)
                FileInputStream(audioFile).use { input ->
                    if (audioPayloadOffset > 0) {
                        input.skip(audioPayloadOffset)
                    }
                    input.copyTo(out)
                }
                out.flush()
            }

            // Replace original file with the fully tagged file
            if (tempTaggedFile.exists() && tempTaggedFile.length() > id3TagBytes.size) {
                if (audioFile.delete()) {
                    tempTaggedFile.renameTo(audioFile)
                } else {
                    FileOutputStream(audioFile).use { fos ->
                        tempTaggedFile.inputStream().use { it.copyTo(fos) }
                    }
                    tempTaggedFile.delete()
                }
            } else {
                tempTaggedFile.delete()
            }

            Log.d(TAG, "Successfully embedded ID3v2 tags and cover art into ${audioFile.name}")
        } catch (e: Throwable) {
            Log.w(TAG, "Could not embed tags into ${audioFile.name}: ${e.message}", e)
        }
    }

    /**
     * Constructs a full ID3v2.3 tag payload.
     */
    fun buildId3v2Tag(
        title: String,
        artist: String,
        album: String?,
        artworkBytes: ByteArray?,
    ): ByteArray {
        val framesOut = ByteArrayOutputStream()

        if (title.isNotBlank()) {
            writeTextFrame(framesOut, FRAME_TITLE, title)
        }
        if (artist.isNotBlank()) {
            writeTextFrame(framesOut, FRAME_ARTIST, artist)
        }
        if (!album.isNullOrBlank()) {
            writeTextFrame(framesOut, FRAME_ALBUM, album)
        }
        if (artworkBytes != null && artworkBytes.isNotEmpty()) {
            writePictureFrame(framesOut, artworkBytes)
        }

        val frameData = framesOut.toByteArray()
        val tagSize = frameData.size

        val headerOut = ByteArrayOutputStream(10 + tagSize)
        // 1. "ID3" identifier
        headerOut.write('I'.code)
        headerOut.write('D'.code)
        headerOut.write('3'.code)
        // 2. Version 2.3.0
        headerOut.write(0x03)
        headerOut.write(0x00)
        // 3. Flags
        headerOut.write(0x00)
        // 4. Synchsafe size (4 bytes, 7 bits each)
        headerOut.write((tagSize shr 21) and 0x7F)
        headerOut.write((tagSize shr 14) and 0x7F)
        headerOut.write((tagSize shr 7) and 0x7F)
        headerOut.write(tagSize and 0x7F)

        headerOut.write(frameData)
        return headerOut.toByteArray()
    }

    private fun writeTextFrame(out: ByteArrayOutputStream, frameId: String, text: String) {
        val textBytes = text.toByteArray(StandardCharsets.UTF_8)
        val payloadLength = 1 + textBytes.size // 1 byte encoding + UTF-8 bytes

        // Frame Header: ID (4 bytes)
        out.write(frameId.toByteArray(StandardCharsets.ISO_8859_1))
        // Frame Header: Size (4 bytes big-endian)
        out.write((payloadLength shr 24) and 0xFF)
        out.write((payloadLength shr 16) and 0xFF)
        out.write((payloadLength shr 8) and 0xFF)
        out.write(payloadLength and 0xFF)
        // Frame Header: Flags (2 bytes)
        out.write(0x00)
        out.write(0x00)

        // Frame Body: Encoding (0x03 for UTF-8)
        out.write(0x03)
        out.write(textBytes)
    }

    private fun writePictureFrame(out: ByteArrayOutputStream, imageBytes: ByteArray) {
        val isPng = imageBytes.size > 8 && imageBytes[0] == 0x89.toByte() && imageBytes[1] == 0x50.toByte()
        val mime = if (isPng) "image/png" else "image/jpeg"
        val mimeBytes = mime.toByteArray(StandardCharsets.ISO_8859_1)

        // Encoding (1) + MIME + null (len + 1) + PicType (1) + Desc null (1) + imageBytes
        val payloadLength = 1 + mimeBytes.size + 1 + 1 + 1 + imageBytes.size

        // Frame Header: ID ("APIC")
        out.write(FRAME_PICTURE.toByteArray(StandardCharsets.ISO_8859_1))
        // Frame Header: Size (4 bytes big-endian)
        out.write((payloadLength shr 24) and 0xFF)
        out.write((payloadLength shr 16) and 0xFF)
        out.write((payloadLength shr 8) and 0xFF)
        out.write(payloadLength and 0xFF)
        // Frame Header: Flags (2 bytes)
        out.write(0x00)
        out.write(0x00)

        // Frame Body:
        out.write(0x00) // Encoding ISO-8859-1 for MIME and description
        out.write(mimeBytes)
        out.write(0x00) // Null terminator for MIME
        out.write(PIC_TYPE_COVER_FRONT.toInt()) // 0x03 = Front Cover
        out.write(0x00) // Empty description + null terminator
        out.write(imageBytes)
    }

    private fun detectExistingId3v2TagLength(file: File): Long {
        if (!file.exists() || file.length() < 10) return 0L
        return try {
            FileInputStream(file).use { input ->
                val header = ByteArray(10)
                if (input.read(header) == 10 && header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte()) {
                    val size = ((header[6].toInt() and 0x7F) shl 21) or
                        ((header[7].toInt() and 0x7F) shl 14) or
                        ((header[8].toInt() and 0x7F) shl 7) or
                        (header[9].toInt() and 0x7F)
                    (10 + size).toLong()
                } else 0L
            }
        } catch (_: Exception) {
            0L
        }
    }

    fun downloadArtworkBytes(artworkUrl: String): ByteArray? {
        return try {
            val request = Request.Builder()
                .url(artworkUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bytes = response.body?.bytes()
                if (bytes != null && bytes.isNotEmpty()) bytes else null
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to download artwork for tagging: ${e.message}")
            null
        }
    }
}

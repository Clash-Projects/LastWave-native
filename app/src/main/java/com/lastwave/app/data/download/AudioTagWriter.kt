package com.lastwave.app.data.download

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
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
        private const val FRAME_ALBUM_ARTIST = "TPE2"
        private const val FRAME_ALBUM = "TALB"
        private const val FRAME_LYRICS = "USLT"
        private const val FRAME_PICTURE = "APIC"
        private const val PIC_TYPE_COVER_FRONT: Byte = 0x03

        // FLAC metadata block types
        private const val FLAC_TYPE_VORBIS_COMMENT = 4
        private const val FLAC_TYPE_PICTURE = 6
        private const val FLAC_MAGIC = "fLaC"

        /** Cap on embedded artwork so a giant image can't balloon the audio file. */
        private const val MAX_ARTWORK_BYTES = 2 * 1024 * 1024
        private const val MAX_ARTWORK_DOWNLOAD_BYTES = 12 * 1024 * 1024
        private const val MAX_ARTWORK_EDGE = 1600
    }

    /**
     * Embeds metadata (Title, Artist, Album), cover art and lyrics directly into
     * the audio file using the format each container actually understands:
     *  - FLAC  -> native Vorbis comments + PICTURE metadata block (offset-free,
     *             spec-correct; a leading ID3 chunk would break strict parsers)
     *  - M4A   -> iTunes-style udta/meta/ilst atoms inserted inside the moov box
     *             with fast-start chunk offsets adjusted when necessary
     *  - WebM  -> native Matroska tags plus an attached front-cover image
     *  - other -> ID3v2.3 prepend (MP3 and tolerant players)
     *
     * Every path validates its output in a temp file before atomically
     * replacing the original — any anomaly leaves the audio untouched.
     */
    fun embedMetadata(
        audioFile: File,
        title: String,
        artist: String,
        album: String? = null,
        artworkUrl: String? = null,
        lyrics: String? = null,
    ): Boolean {
        if (!audioFile.exists() || audioFile.length() <= 0) return false

        return try {
            val artworkBytes = if (!artworkUrl.isNullOrBlank()) {
                downloadArtworkBytes(artworkUrl)?.let { normalizedArtwork(it) }
            } else null

            val kind = detectContainerKind(audioFile)
            val ok = when (kind) {
                // A FLAC whose metadata chain doesn't parse cleanly is left
                // untouched — prepending ID3 there is nonstandard and can
                // break strict extractors' format sniffing.
                ContainerKind.FLAC -> embedIntoFlac(audioFile, title, artist, album, artworkBytes, lyrics)
                // Keep MP4/WebM metadata native; ID3 prepends corrupt their container contract.
                ContainerKind.MP4 -> embedIntoMp4(audioFile, title, artist, album, artworkBytes, lyrics)
                ContainerKind.WEBM -> embedIntoWebm(audioFile, title, artist, album, artworkBytes, lyrics)
                ContainerKind.OGG -> false
                else -> embedId3Prepend(audioFile, title, artist, album, artworkBytes, lyrics)
            }
            if (ok) {
                Log.d(TAG, "Embedded ${kind.name} tags into ${audioFile.name}")
            } else {
                Log.w(TAG, "Tagging skipped (structure not safely writable) for ${audioFile.name}")
            }
            ok
        } catch (e: Throwable) {
            Log.w(TAG, "Could not embed tags into ${audioFile.name}: ${e.message}", e)
            false
        }
    }

    private enum class ContainerKind { FLAC, MP4, WEBM, OGG, OTHER }

    private fun detectContainerKind(file: File): ContainerKind {
        return try {
            FileInputStream(file).use { input ->
                val header = ByteArray(12)
                val n = input.read(header)
                if (n < 12) return ContainerKind.OTHER
                when {
                    header[0] == 'f'.code.toByte() && header[1] == 'L'.code.toByte() &&
                        header[2] == 'a'.code.toByte() && header[3] == 'C'.code.toByte() -> ContainerKind.FLAC
                    header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
                        header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte() -> ContainerKind.MP4
                    header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() &&
                        header[2] == 0xDF.toByte() && header[3] == 0xA3.toByte() -> ContainerKind.WEBM
                    header[0] == 'O'.code.toByte() && header[1] == 'g'.code.toByte() &&
                        header[2] == 'g'.code.toByte() && header[3] == 'S'.code.toByte() -> ContainerKind.OGG
                    else -> ContainerKind.OTHER
                }
            }
        } catch (_: Exception) {
            ContainerKind.OTHER
        }
    }

    // ─────────────────────────── ID3v2.3 (fallback / MP3) ───────────────────────────

    private fun embedId3Prepend(
        audioFile: File,
        title: String,
        artist: String,
        album: String?,
        artworkBytes: ByteArray?,
        lyrics: String?,
    ): Boolean {
        val id3TagBytes = buildId3v2Tag(
            title = title,
            artist = artist,
            album = album,
            artworkBytes = artworkBytes,
            lyrics = lyrics,
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

        return replaceOriginal(audioFile, tempTaggedFile, minimumValidLength = id3TagBytes.size + 1)
    }

    /**
     * Constructs a full ID3v2.3 tag payload. Text frames use UTF-16LE with BOM
     * (encoding 0x01) — UTF-8 (0x03) is ILLEGAL in v2.3 and made strict readers
     * silently drop every frame ("downloaded songs have no metadata").
     */
    fun buildId3v2Tag(
        title: String,
        artist: String,
        album: String?,
        artworkBytes: ByteArray?,
        lyrics: String? = null,
    ): ByteArray {
        val framesOut = ByteArrayOutputStream()

        if (title.isNotBlank()) {
            writeTextFrame(framesOut, FRAME_TITLE, title)
        }
        if (artist.isNotBlank()) {
            writeTextFrame(framesOut, FRAME_ARTIST, artist)
            writeTextFrame(framesOut, FRAME_ALBUM_ARTIST, artist)
        }
        if (!album.isNullOrBlank()) {
            writeTextFrame(framesOut, FRAME_ALBUM, album)
        }
        if (!lyrics.isNullOrBlank()) {
            writeLyricsFrame(framesOut, lyrics)
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

    private fun utf16WithBom(text: String): ByteArray {
        val out = ByteArrayOutputStream(2 + text.length * 2)
        // UTF-16LE BOM
        out.write(0xFF)
        out.write(0xFE)
        text.forEach { ch ->
            val code = ch.code
            out.write(code and 0xFF)
            out.write((code shr 8) and 0xFF)
        }
        return out.toByteArray()
    }

    private fun writeFrameHeader(out: ByteArrayOutputStream, frameId: String, payloadLength: Int) {
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
    }

    private fun writeTextFrame(out: ByteArrayOutputStream, frameId: String, text: String) {
        val textBytes = utf16WithBom(text)
        val payloadLength = 1 + textBytes.size // 1 byte encoding + UTF-16 bytes
        writeFrameHeader(out, frameId, payloadLength)
        // Encoding 0x01 = UTF-16 with BOM (spec-legal in ID3v2.3)
        out.write(0x01)
        out.write(textBytes)
    }

    private fun writeLyricsFrame(out: ByteArrayOutputStream, lyrics: String) {
        val lyricBytes = utf16WithBom(lyrics)
        val language = "eng".toByteArray(StandardCharsets.ISO_8859_1)
        // Body: encoding(1) + language(3) + empty UTF-16 content descriptor
        // (terminated by 0x00 0x00) + lyrics text
        val payloadLength = 1 + 3 + 2 + lyricBytes.size
        writeFrameHeader(out, FRAME_LYRICS, payloadLength)
        out.write(0x01)
        out.write(language)
        out.write(0x00)
        out.write(0x00)
        out.write(lyricBytes)
    }

    private fun writePictureFrame(out: ByteArrayOutputStream, imageBytes: ByteArray) {
        val isPng = isPngBytes(imageBytes)
        val mime = if (isPng) "image/png" else "image/jpeg"
        val mimeBytes = mime.toByteArray(StandardCharsets.ISO_8859_1)

        // Encoding (1) + MIME + null (len + 1) + PicType (1) + Desc null (1) + imageBytes
        val payloadLength = 1 + mimeBytes.size + 1 + 1 + 1 + imageBytes.size
        writeFrameHeader(out, FRAME_PICTURE, payloadLength)

        // Frame Body:
        out.write(0x00) // Encoding ISO-8859-1 for MIME and description
        out.write(mimeBytes)
        out.write(0x00) // Null terminator for MIME
        out.write(PIC_TYPE_COVER_FRONT.toInt()) // 0x03 = Front Cover
        out.write(0x00) // Empty description + null terminator
        out.write(imageBytes)
    }

    // ─────────────────────────── FLAC (Vorbis comments + PICTURE) ───────────────────────────

    /**
     * Rewrites the FLAC metadata chain as: fLaC + STREAMINFO + VORBIS_COMMENT +
     * PICTURE + remaining original blocks. FLAC frames carry absolute sample
     * positions (no byte offsets anywhere in the stream), so inserting metadata
     * blocks cannot corrupt playback. Existing comment/picture blocks are
     * replaced by ours; any structural surprise aborts untouched.
     *
     * Streams via RandomAccessFile — hi-res FLACs run tens of MB and must
     * never be loaded into RAM whole.
     */
    private fun embedIntoFlac(
        audioFile: File,
        title: String,
        artist: String,
        album: String?,
        artworkBytes: ByteArray?,
        lyrics: String?,
    ): Boolean {
        val blocks = mutableListOf<FlacBlockRef>()
        var framesStart = -1L

        // Phase 1 — parse the metadata chain (no output written yet).
        java.io.RandomAccessFile(audioFile, "r").use { raf ->
            val magic = ByteArray(4)
            if (raf.read(magic) < 4 || String(magic, StandardCharsets.US_ASCII) != FLAC_MAGIC) return false

            var offset = 4L
            while (true) {
                raf.seek(offset)
                val header = ByteArray(4)
                if (raf.read(header) < 4) return false
                val headerByte = header[0].toInt() and 0xFF
                val isLast = (headerByte and 0x80) != 0
                val type = headerByte and 0x7F
                val bodyLen = ((header[1].toInt() and 0xFF) shl 16) or
                    ((header[2].toInt() and 0xFF) shl 8) or
                    (header[3].toInt() and 0xFF)
                val bodyStart = offset + 4
                if (bodyStart + bodyLen > raf.length()) return false
                blocks.add(FlacBlockRef(type, bodyStart, bodyLen))
                offset = bodyStart + bodyLen
                if (isLast) {
                    framesStart = offset
                    break
                }
            }
        }

        val streamInfo = blocks.firstOrNull { it.type == 0 } ?: return false
        // STREAMINFO is kept verbatim; our VORBIS_COMMENT/PICTURE replace any
        // existing ones; everything else (seektable, cuesheet, padding…) rides along.
        val keptBlocks = blocks.filter {
            it.type != 0 && it.type != FLAC_TYPE_VORBIS_COMMENT && it.type != FLAC_TYPE_PICTURE
        }

        val comments = mutableListOf<String>()
        if (title.isNotBlank()) comments += "TITLE=$title"
        if (artist.isNotBlank()) comments += "ARTIST=$artist"
        if (artist.isNotBlank()) comments += "ALBUMARTIST=$artist"
        if (!album.isNullOrBlank()) comments += "ALBUM=$album"
        if (!lyrics.isNullOrBlank()) comments += "LYRICS=$lyrics"

        data class OutBlock(val type: Int, val fromSource: FlacBlockRef?, val generated: ByteArray?)
        val outChain = mutableListOf<OutBlock>()
        outChain.add(OutBlock(0, streamInfo, null))
        outChain.add(OutBlock(FLAC_TYPE_VORBIS_COMMENT, null, buildVorbisCommentBody(comments)))
        if (artworkBytes != null && artworkBytes.isNotEmpty()) {
            outChain.add(OutBlock(FLAC_TYPE_PICTURE, null, buildFlacPictureBody(artworkBytes)))
        }
        keptBlocks.forEach { outChain.add(OutBlock(it.type, it, null)) }

        // Phase 2 — stream the tagged copy. Any failure here throws so the
        // catch below removes the partial temp file.
        val tempFile = File.createTempFile("tagged_", ".flac", audioFile.parentFile)
        try {
            FileOutputStream(tempFile).buffered().use { out ->
                out.write("fLaC".toByteArray(StandardCharsets.US_ASCII))
                java.io.RandomAccessFile(audioFile, "r").use { raf ->
                    outChain.forEachIndexed { index, block ->
                        val isFinal = index == outChain.lastIndex
                        out.write(((if (isFinal) 0x80 else 0x00) or block.type) and 0xFF)
                        val len = block.fromSource?.bodyLen ?: block.generated!!.size
                        out.write((len shr 16) and 0xFF)
                        out.write((len shr 8) and 0xFF)
                        out.write(len and 0xFF)
                        val source = block.fromSource
                        if (source != null) {
                            raf.seek(source.bodyStart)
                            val buffer = ByteArray(64 * 1024)
                            var remaining = source.bodyLen.toLong()
                            while (remaining > 0) {
                                val chunk = raf.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                if (chunk <= 0) throw java.io.IOException("FLAC block truncated while copying")
                                out.write(buffer, 0, chunk)
                                remaining -= chunk
                            }
                        } else {
                            out.write(block.generated!!)
                        }
                    }
                }

                // Audio frames tail — byte-offset free, safe to copy verbatim.
                FileInputStream(audioFile).use { input ->
                    var skipped = 0L
                    while (skipped < framesStart) {
                        val s = input.skip(framesStart - skipped)
                        if (s <= 0) break
                        skipped += s
                    }
                    input.copyTo(out)
                }
                out.flush()
            }
        } catch (_: Exception) {
            tempFile.delete()
            return false
        }

        // Sanity: tagged output must start with fLaC and contain the full frame region.
        val valid = tempFile.length() > framesStart &&
            FileInputStream(tempFile).use { input ->
                val head = ByteArray(4)
                input.read(head) == 4 && String(head, StandardCharsets.US_ASCII) == FLAC_MAGIC
            }
        if (!valid) {
            tempFile.delete()
            return false
        }
        return replaceOriginal(audioFile, tempFile, minimumValidLength = 9)
    }

    private data class FlacBlockRef(val type: Int, val bodyStart: Long, val bodyLen: Int)

    /** Little-endian Vorbis comment block body (the FLAC variant has no framing bit). */
    private fun buildVorbisCommentBody(comments: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        val vendor = "LastWave".toByteArray(StandardCharsets.UTF_8)
        writeLe32(out, vendor.size)
        out.write(vendor)
        writeLe32(out, comments.size)
        comments.forEach { comment ->
            val bytes = comment.toByteArray(StandardCharsets.UTF_8)
            writeLe32(out, bytes.size)
            out.write(bytes)
        }
        return out.toByteArray()
    }

    /** Big-endian METADATA_BLOCK_PICTURE body. */
    private fun buildFlacPictureBody(imageBytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val mime = if (isPngBytes(imageBytes)) "image/png" else "image/jpeg"
        val mimeBytes = mime.toByteArray(StandardCharsets.US_ASCII)
        writeBe32(out, PIC_TYPE_COVER_FRONT.toInt() and 0xFF) // front cover
        writeBe32(out, mimeBytes.size)
        out.write(mimeBytes)
        writeBe32(out, 0) // description length (empty)
        writeBe32(out, 0) // width
        writeBe32(out, 0) // height
        writeBe32(out, 0) // depth
        writeBe32(out, 0) // colors
        writeBe32(out, imageBytes.size)
        out.write(imageBytes)
        return out.toByteArray()
    }

    // ─────────────────────────── MP4/M4A (iTunes ilst atoms) ───────────────────────────

    /** Appends native Matroska tags and an attached cover inside the WebM Segment. */
    private fun embedIntoWebm(
        audioFile: File,
        title: String,
        artist: String,
        album: String?,
        artworkBytes: ByteArray?,
        lyrics: String?,
    ): Boolean {
        val metadata = buildWebmMetadata(title, artist, album, artworkBytes, lyrics)
        if (metadata.isEmpty()) return false

        val fileSize = audioFile.length()
        val segment = java.io.RandomAccessFile(audioFile, "r").use { raf ->
            val ebml = readEbmlElementHeader(raf, 0L, fileSize) ?: return false
            if (ebml.id != 0x1A45DFA3L || ebml.dataSize == null) return false

            var offset = ebml.dataStart + ebml.dataSize
            var found: EbmlElementHeader? = null
            while (offset < fileSize && found == null) {
                val element = readEbmlElementHeader(raf, offset, fileSize) ?: return false
                if (element.id == 0x18538067L) {
                    found = element
                } else {
                    val size = element.dataSize ?: return false
                    offset = element.dataStart + size
                }
            }
            found ?: return false
        }

        // Appending is safe only when the Segment already reaches end-of-file.
        if (segment.dataSize != null && segment.dataStart + segment.dataSize != fileSize) return false
        val replacementSize = segment.dataSize?.let { oldSize ->
            encodeEbmlSize(oldSize + metadata.size, segment.sizeLength) ?: return false
        }

        val tempFile = File.createTempFile("tagged_", ".webm", audioFile.parentFile)
        return try {
            FileInputStream(audioFile).use { input ->
                FileOutputStream(tempFile).buffered().use { output -> input.copyTo(output) }
            }
            if (replacementSize != null) {
                java.io.RandomAccessFile(tempFile, "rw").use { raf ->
                    raf.seek(segment.sizeStart)
                    raf.write(replacementSize)
                }
            }
            FileOutputStream(tempFile, true).buffered().use { output ->
                output.write(metadata)
                output.flush()
            }
            if (tempFile.length() != fileSize + metadata.size) {
                tempFile.delete()
                false
            } else {
                replaceOriginal(audioFile, tempFile, minimumValidLength = 16)
            }
        } catch (_: Exception) {
            tempFile.delete()
            false
        }
    }

    private data class EbmlElementHeader(
        val id: Long,
        val sizeStart: Long,
        val sizeLength: Int,
        val dataStart: Long,
        /** Null is the EBML unknown-size sentinel. */
        val dataSize: Long?,
    )

    private fun readEbmlElementHeader(
        raf: java.io.RandomAccessFile,
        offset: Long,
        fileSize: Long,
    ): EbmlElementHeader? {
        val id = readEbmlVint(raf, offset, fileSize, keepMarker = true, maxLength = 4) ?: return null
        val sizeStart = offset + id.second
        val size = readEbmlVint(raf, sizeStart, fileSize, keepMarker = false, maxLength = 8) ?: return null
        val dataStart = sizeStart + size.second
        val unknownValue = (1L shl (7 * size.second)) - 1L
        val dataSize = if (size.first == unknownValue) null else size.first
        if (dataStart > fileSize || (dataSize != null && dataStart + dataSize > fileSize)) return null
        return EbmlElementHeader(id.first, sizeStart, size.second, dataStart, dataSize)
    }

    private fun readEbmlVint(
        raf: java.io.RandomAccessFile,
        offset: Long,
        fileSize: Long,
        keepMarker: Boolean,
        maxLength: Int,
    ): Pair<Long, Int>? {
        if (offset >= fileSize) return null
        raf.seek(offset)
        val first = raf.read()
        if (first <= 0) return null
        var marker = 0x80
        var length = 1
        while ((first and marker) == 0 && length <= maxLength) {
            marker = marker ushr 1
            length++
        }
        if (length > maxLength || offset + length > fileSize) return null
        var value = if (keepMarker) first.toLong() else (first and (marker - 1)).toLong()
        repeat(length - 1) {
            val next = raf.read()
            if (next < 0) return null
            value = (value shl 8) or next.toLong()
        }
        return value to length
    }

    private fun buildWebmMetadata(
        title: String,
        artist: String,
        album: String?,
        artworkBytes: ByteArray?,
        lyrics: String?,
    ): ByteArray {
        val tagBody = ByteArrayOutputStream()
        // TargetTypeValue 30 is Matroska's TRACK/SONG level.
        val target = ebmlElement(byteArrayOf(0x68, 0xCA.toByte()), byteArrayOf(30))
        tagBody.write(ebmlElement(byteArrayOf(0x63, 0xC0.toByte()), target))
        addWebmSimpleTag(tagBody, "TITLE", title)
        addWebmSimpleTag(tagBody, "ARTIST", artist)
        addWebmSimpleTag(tagBody, "ALBUMARTIST", artist)
        if (!album.isNullOrBlank()) addWebmSimpleTag(tagBody, "ALBUM", album)
        if (!lyrics.isNullOrBlank()) addWebmSimpleTag(tagBody, "LYRICS", lyrics)

        val output = ByteArrayOutputStream()
        val tag = ebmlElement(byteArrayOf(0x73, 0x73), tagBody.toByteArray())
        output.write(ebmlElement(byteArrayOf(0x12, 0x54, 0xC3.toByte(), 0x67), tag))

        if (artworkBytes != null && artworkBytes.isNotEmpty()) {
            val png = isPngBytes(artworkBytes)
            val attachedFile = ByteArrayOutputStream()
            attachedFile.write(ebmlElement(byteArrayOf(0x46, 0x7E), "Front cover".toByteArray(StandardCharsets.UTF_8)))
            attachedFile.write(ebmlElement(byteArrayOf(0x46, 0x6E), (if (png) "cover.png" else "cover.jpg").toByteArray(StandardCharsets.UTF_8)))
            attachedFile.write(ebmlElement(byteArrayOf(0x46, 0x60), (if (png) "image/png" else "image/jpeg").toByteArray(StandardCharsets.US_ASCII)))
            attachedFile.write(ebmlElement(byteArrayOf(0x46, 0xAE.toByte()), byteArrayOf(1)))
            attachedFile.write(ebmlElement(byteArrayOf(0x46, 0x5C), artworkBytes))
            val attachment = ebmlElement(byteArrayOf(0x61, 0xA7.toByte()), attachedFile.toByteArray())
            output.write(ebmlElement(byteArrayOf(0x19, 0x41, 0xA4.toByte(), 0x69), attachment))
        }
        return output.toByteArray()
    }

    private fun addWebmSimpleTag(out: ByteArrayOutputStream, name: String, value: String) {
        if (value.isBlank()) return
        val body = ByteArrayOutputStream()
        body.write(ebmlElement(byteArrayOf(0x45, 0xA3.toByte()), name.toByteArray(StandardCharsets.US_ASCII)))
        body.write(ebmlElement(byteArrayOf(0x44, 0x87.toByte()), value.toByteArray(StandardCharsets.UTF_8)))
        out.write(ebmlElement(byteArrayOf(0x67, 0xC8.toByte()), body.toByteArray()))
    }

    private fun ebmlElement(id: ByteArray, payload: ByteArray): ByteArray {
        val size = encodeEbmlSize(payload.size.toLong()) ?: return ByteArray(0)
        return ByteArrayOutputStream(id.size + size.size + payload.size).apply {
            write(id)
            write(size)
            write(payload)
        }.toByteArray()
    }

    private fun encodeEbmlSize(value: Long, forcedLength: Int? = null): ByteArray? {
        if (value < 0) return null
        val lengths = forcedLength?.let { listOf(it) } ?: (1..8).toList()
        val length = lengths.firstOrNull { candidate ->
            val maxKnown = (1L shl (7 * candidate)) - 2L
            value <= maxKnown
        } ?: return null
        val bytes = ByteArray(length)
        var remaining = value
        for (index in length - 1 downTo 0) {
            bytes[index] = (remaining and 0xFF).toByte()
            remaining = remaining ushr 8
        }
        bytes[0] = (bytes[0].toInt() or (1 shl (8 - length))).toByte()
        return bytes
    }

    /** Writes native iTunes metadata and adjusts fast-start chunk offsets. */
    private fun embedIntoMp4(
        audioFile: File,
        title: String,
        artist: String,
        album: String?,
        artworkBytes: ByteArray?,
        lyrics: String?,
    ): Boolean {
        // Phase 1 — walk top-level boxes via headers only.
        var moovStart = -1L
        var moovSize = -1L
        var offset = 0L
        val fileSize = audioFile.length()
        java.io.RandomAccessFile(audioFile, "r").use { raf ->
            while (offset + 8 <= fileSize) {
                raf.seek(offset)
                val head = ByteArray(8)
                if (raf.read(head) < 8) return false
                val size = ((head[0].toLong() and 0xFF) shl 24) or
                    ((head[1].toLong() and 0xFF) shl 16) or
                    ((head[2].toLong() and 0xFF) shl 8) or
                    (head[3].toLong() and 0xFF)
                if (size < 8 || offset + size > fileSize) return false
                val type = String(head, 4, 4, StandardCharsets.US_ASCII)
                if (type == "moov") {
                    moovStart = offset
                    moovSize = size
                }
                offset += size
            }
        }
        if (offset != fileSize) return false                          // trailing garbage / odd layout
        if (moovStart < 0 || moovSize < 8 || moovSize - 8 > Int.MAX_VALUE) return false

        // Build the small in-memory atom tree (metadata only).
        val ilstItems = ByteArrayOutputStream()
        addMp4TextItem(ilstItems, "\u00A9nam", title)       // ©nam
        addMp4TextItem(ilstItems, "\u00A9ART", artist)      // ©ART
        addMp4TextItem(ilstItems, "aART", artist)
        if (!album.isNullOrBlank()) addMp4TextItem(ilstItems, "\u00A9alb", album)
        if (!lyrics.isNullOrBlank()) addMp4TextItem(ilstItems, "\u00A9lyr", lyrics)
        if (artworkBytes != null && artworkBytes.isNotEmpty()) {
            addMp4CoverItem(ilstItems, artworkBytes)
        }
        val ilstBox = wrapBox("ilst", ilstItems.toByteArray())

        val hdlrBody = byteArrayOf(
            0x00, 0x00, 0x00, 0x00,                         // version + flags
            0x00, 0x00, 0x00, 0x00,                         // pre_defined
            'm'.code.toByte(), 'd'.code.toByte(), 'i'.code.toByte(), 'r'.code.toByte(),
            'a'.code.toByte(), 'p'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(),
            0x00, 0x00, 0x00, 0x00,                         // reserved
            0x00, 0x00, 0x00, 0x00,                         // reserved
            0x00,                                           // empty handler name
        )
        val hdlrBox = wrapBox("hdlr", hdlrBody)
        val metaBody = ByteArrayOutputStream()
        metaBody.write(0x00); metaBody.write(0x00); metaBody.write(0x00); metaBody.write(0x00) // version+flags (FullBox)
        metaBody.write(hdlrBox)
        metaBody.write(ilstBox)
        val udtaBox = wrapBox("udta", wrapBox("meta", metaBody.toByteArray()))

        val newMoovSize = moovSize + udtaBox.size
        if (newMoovSize > 0x7FFFFFFFL) return false

        val moovBody = ByteArray((moovSize - 8).toInt())
        java.io.RandomAccessFile(audioFile, "r").use { raf ->
            raf.seek(moovStart + 8)
            raf.readFully(moovBody)
        }
        // When moov precedes mdat, adding metadata shifts media bytes forward.
        // Patch every absolute stco/co64 chunk offset that points past moov.
        if (!patchMp4ChunkOffsets(
                bytes = moovBody,
                start = 0,
                end = moovBody.size,
                shiftedRegionStart = moovStart + moovSize,
                delta = udtaBox.size,
            )
        ) return false

        // Phase 2 — stream the preserved prefix, patched moov, metadata and tail.
        val tempFile = File.createTempFile("tagged_", ".m4a", audioFile.parentFile)
        try {
            FileOutputStream(tempFile).buffered().use { out ->
                // Preserve ftyp/mdat/free and every other box before moov.
                // The previous writer started the output at moov, silently
                // dropping the actual audio payload and always failing its
                // own length validation.
                FileInputStream(audioFile).use { input ->
                    copyExactly(input, out, moovStart)
                }

                out.write((newMoovSize ushr 24).toInt() and 0xFF)
                out.write((newMoovSize ushr 16).toInt() and 0xFF)
                out.write((newMoovSize ushr 8).toInt() and 0xFF)
                out.write(newMoovSize.toInt() and 0xFF)
                out.write("moov".toByteArray(StandardCharsets.US_ASCII))

                out.write(moovBody)
                out.write(udtaBox)

                // Preserve boxes after moov (fast-start and fragmented MP4).
                FileInputStream(audioFile).use { input ->
                    val tailStart = moovStart + moovSize
                    var skipped = 0L
                    while (skipped < tailStart) {
                        val amount = input.skip(tailStart - skipped)
                        if (amount <= 0) throw java.io.EOFException("MP4 ended before media payload")
                        skipped += amount
                    }
                    copyExactly(input, out, fileSize - tailStart)
                }
                out.flush()
            }
        } catch (_: Exception) {
            tempFile.delete()
            return false
        }

        val valid = tempFile.length() == fileSize + udtaBox.size
        if (!valid) {
            tempFile.delete()
            return false
        }
        return replaceOriginal(audioFile, tempFile, minimumValidLength = 16)
    }

    private fun patchMp4ChunkOffsets(
        bytes: ByteArray,
        start: Int,
        end: Int,
        shiftedRegionStart: Long,
        delta: Int,
    ): Boolean {
        var offset = start
        while (offset < end) {
            if (offset + 8 > end) return false
            val size32 = readBeUInt32(bytes, offset)
            val type = String(bytes, offset + 4, 4, StandardCharsets.ISO_8859_1)
            var headerSize = 8
            val boxSize = when (size32) {
                0L -> (end - offset).toLong()
                1L -> {
                    if (offset + 16 > end) return false
                    headerSize = 16
                    readBeUInt64(bytes, offset + 8) ?: return false
                }
                else -> size32
            }
            if (boxSize < headerSize || boxSize > end - offset) return false
            val boxEnd = offset + boxSize.toInt()

            when (type) {
                "trak", "mdia", "minf", "stbl" -> {
                    if (!patchMp4ChunkOffsets(bytes, offset + headerSize, boxEnd, shiftedRegionStart, delta)) {
                        return false
                    }
                }
                "stco" -> {
                    val payload = offset + headerSize
                    if (payload + 8 > boxEnd) return false
                    val count = readBeUInt32(bytes, payload + 4)
                    if (count > (boxEnd - payload - 8) / 4L) return false
                    repeat(count.toInt()) { index ->
                        val entry = payload + 8 + index * 4
                        val original = readBeUInt32(bytes, entry)
                        if (original >= shiftedRegionStart) {
                            val shifted = original + delta
                            if (shifted > 0xFFFF_FFFFL) return false
                            writeBeUInt32(bytes, entry, shifted)
                        }
                    }
                }
                "co64" -> {
                    val payload = offset + headerSize
                    if (payload + 8 > boxEnd) return false
                    val count = readBeUInt32(bytes, payload + 4)
                    if (count > (boxEnd - payload - 8) / 8L) return false
                    repeat(count.toInt()) { index ->
                        val entry = payload + 8 + index * 8
                        val original = readBeUInt64(bytes, entry) ?: return false
                        if (original >= shiftedRegionStart) {
                            if (Long.MAX_VALUE - original < delta.toLong()) return false
                            writeBeUInt64(bytes, entry, original + delta)
                        }
                    }
                }
            }
            offset = boxEnd
        }
        return offset == end
    }

    private fun readBeUInt32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)

    private fun writeBeUInt32(bytes: ByteArray, offset: Int, value: Long) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun readBeUInt64(bytes: ByteArray, offset: Int): Long? {
        if ((bytes[offset].toInt() and 0x80) != 0) return null
        var value = 0L
        repeat(8) { index -> value = (value shl 8) or (bytes[offset + index].toLong() and 0xFF) }
        return value
    }

    private fun writeBeUInt64(bytes: ByteArray, offset: Int, value: Long) {
        repeat(8) { index -> bytes[offset + 7 - index] = (value ushr (index * 8)).toByte() }
    }

    /** data-box flags: 1 = UTF-8 text, 13 = JPEG, 14 = PNG. */
    private fun addMp4TextItem(out: ByteArrayOutputStream, name: String, value: String) {
        if (value.isBlank()) return
        val payload = value.toByteArray(StandardCharsets.UTF_8)
        val dataBody = ByteArrayOutputStream()
        dataBody.write(0x00); dataBody.write(0x00); dataBody.write(0x00); dataBody.write(0x01) // version+flags: UTF-8
        dataBody.write(0x00); dataBody.write(0x00); dataBody.write(0x00); dataBody.write(0x00) // locale
        dataBody.write(payload)
        val dataBox = wrapBox("data", dataBody.toByteArray())
        // Atom names are exactly 4 bytes; © (U+00A9) is ONE byte in ISO-8859-1
        // but TWO bytes in UTF-8, which would corrupt the box header.
        val nameBytes = name.toByteArray(StandardCharsets.ISO_8859_1)
        require(nameBytes.size == 4)
        out.write((8 + dataBox.size) ushr 24 and 0xFF)
        out.write((8 + dataBox.size) ushr 16 and 0xFF)
        out.write((8 + dataBox.size) ushr 8 and 0xFF)
        out.write((8 + dataBox.size) and 0xFF)
        out.write(nameBytes)
        out.write(dataBox)
    }

    private fun addMp4CoverItem(out: ByteArrayOutputStream, imageBytes: ByteArray) {
        val isPng = isPngBytes(imageBytes)
        val typeFlag = if (isPng) 0x0E else 0x0D // 14 = PNG, 13 = JPEG
        val dataBody = ByteArrayOutputStream()
        dataBody.write(0x00); dataBody.write(0x00); dataBody.write(0x00); dataBody.write(typeFlag)
        dataBody.write(0x00); dataBody.write(0x00); dataBody.write(0x00); dataBody.write(0x00) // locale
        dataBody.write(imageBytes)
        val dataBox = wrapBox("data", dataBody.toByteArray())
        val name = "covr".toByteArray(StandardCharsets.US_ASCII)
        out.write((8 + dataBox.size) ushr 24 and 0xFF)
        out.write((8 + dataBox.size) ushr 16 and 0xFF)
        out.write((8 + dataBox.size) ushr 8 and 0xFF)
        out.write((8 + dataBox.size) and 0xFF)
        out.write(name)
        out.write(dataBox)
    }

    private fun wrapBox(type: String, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(8 + body.size)
        val size = 8 + body.size
        out.write(size ushr 24 and 0xFF)
        out.write(size ushr 16 and 0xFF)
        out.write(size ushr 8 and 0xFF)
        out.write(size and 0xFF)
        out.write(type.toByteArray(StandardCharsets.US_ASCII))
        out.write(body)
        return out.toByteArray()
    }

    // ─────────────────────────── shared helpers ───────────────────────────

    /** Atomically swaps [tempFile] over [target] after basic sanity checks. */
    private fun replaceOriginal(target: File, tempFile: File, minimumValidLength: Int): Boolean {
        if (!tempFile.exists() || tempFile.length() < minimumValidLength || !target.exists()) {
            tempFile.delete()
            return false
        }

        val backup = File.createTempFile("untagged_", ".bak", target.parentFile)
        backup.delete()
        if (!target.renameTo(backup)) {
            tempFile.delete()
            return false
        }

        val replaced = tempFile.renameTo(target)
        if (replaced) {
            backup.delete()
            return true
        }

        // Same-directory rename should normally be atomic. Restore the exact
        // original if the filesystem refuses it; never leave a missing file.
        target.delete()
        val restored = backup.renameTo(target)
        tempFile.delete()
        if (!restored) Log.e(TAG, "Could not restore original audio file ${target.name}")
        return false
    }

    private fun isPngBytes(bytes: ByteArray): Boolean =
        bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()

    private fun copyExactly(input: InputStream, output: OutputStream, byteCount: Long) {
        val buffer = ByteArray(64 * 1024)
        var remaining = byteCount
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) throw java.io.EOFException("Audio file ended while copying")
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun writeLe32(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }

    private fun writeBe32(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeBe32Into(out: FileOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun readU32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

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

    /**
     * Downloads artwork and guarantees a player-safe encoding: YouTube-style
     * CDNs can serve WebP even when the URL looks like an image URL, and WebP
     * payloads inside APIC/covr render as broken covers in most players. Large
     * or unsupported images are resized and re-encoded as compatible JPEG.
     */
    fun downloadArtworkBytes(artworkUrl: String): ByteArray? {
        return try {
            val request = Request.Builder()
                .url(artworkUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()
            val response = okHttpClient.newCall(request).execute()
            response.use { res ->
                if (!res.isSuccessful) return null
                val body = res.body ?: return null
                if (body.contentLength() > MAX_ARTWORK_DOWNLOAD_BYTES) return null
                val output = ByteArrayOutputStream(
                    body.contentLength().coerceIn(0L, MAX_ARTWORK_DOWNLOAD_BYTES.toLong()).toInt(),
                )
                body.byteStream().use { input ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_ARTWORK_DOWNLOAD_BYTES) return null
                        output.write(buffer, 0, read)
                    }
                }
                output.toByteArray().takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to download artwork for tagging: ${e.message}")
            null
        }
    }

    /** Normalizes arbitrary image bytes to plain JPEG/PNG (never WebP/HEIF). */
    private fun normalizedArtwork(bytes: ByteArray): ByteArray? {
        val isJpeg = bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
        val isPng = isPngBytes(bytes)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val largestEdge = maxOf(bounds.outWidth, bounds.outHeight)
        if ((isJpeg || isPng) && bytes.size <= MAX_ARTWORK_BYTES && largestEdge in 1..MAX_ARTWORK_EDGE) {
            return bytes
        }

        return runCatching {
            var sampleSize = 1
            while (largestEdge > 0 && largestEdge / sampleSize > MAX_ARTWORK_EDGE * 2) {
                sampleSize *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return@runCatching null
            val scaled = if (maxOf(decoded.width, decoded.height) > MAX_ARTWORK_EDGE) {
                val scale = MAX_ARTWORK_EDGE.toFloat() / maxOf(decoded.width, decoded.height)
                Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * scale).toInt().coerceAtLeast(1),
                    (decoded.height * scale).toInt().coerceAtLeast(1),
                    true,
                ).also { if (it !== decoded) decoded.recycle() }
            } else {
                decoded
            }

            try {
                for (quality in listOf(92, 86, 80, 74, 68)) {
                    val output = ByteArrayOutputStream()
                    if (scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                        val result = output.toByteArray()
                        if (result.isNotEmpty() && result.size <= MAX_ARTWORK_BYTES) return@runCatching result
                    }
                }
                null
            } finally {
                scaled.recycle()
            }
        }.getOrNull()
    }
}

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
        private const val OGG_CAPTURE_PATTERN = "OggS"
        private const val OPUS_HEAD = "OpusHead"
        private const val OPUS_TAGS = "OpusTags"
        private const val OGG_MAX_SEGMENTS = 255
        private const val OGG_CRC_POLYNOMIAL = 0x04C11DB7

        /** Cap on embedded artwork so a giant image can't balloon the audio file. */
        private const val MAX_ARTWORK_BYTES = 2 * 1024 * 1024
        private const val MAX_ARTWORK_DOWNLOAD_BYTES = 12 * 1024 * 1024
        private const val MAX_ARTWORK_EDGE = 1600
        private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

        private val OGG_CRC_TABLE = IntArray(256) { index ->
            var remainder = index shl 24
            repeat(8) {
                remainder = if ((remainder and Int.MIN_VALUE) != 0) {
                    (remainder shl 1) xor OGG_CRC_POLYNOMIAL
                } else {
                    remainder shl 1
                }
            }
            remainder
        }
    }

    /**
     * Embeds metadata (Title, Artist, Album), cover art and lyrics directly into
     * the audio file using the format each container actually understands:
     *  - FLAC  -> native Vorbis comments + PICTURE metadata block (offset-free,
     *             spec-correct; a leading ID3 chunk would break strict parsers)
     *  - M4A   -> iTunes-style udta/meta/ilst atoms inserted inside the moov box
     *             with fast-start chunk offsets adjusted when necessary
     *  - Opus  -> native OpusTags/Vorbis comments with METADATA_BLOCK_PICTURE
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
        year: String? = null,
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
                ContainerKind.FLAC -> embedIntoFlac(audioFile, title, artist, album, artworkBytes, lyrics, year)
                // Keep MP4/WebM metadata native; ID3 prepends corrupt their container contract.
                ContainerKind.MP4 -> embedIntoMp4(audioFile, title, artist, album, artworkBytes, lyrics, year)
                ContainerKind.WEBM -> embedIntoWebm(audioFile, title, artist, album, artworkBytes, lyrics, year)
                ContainerKind.OGG -> embedIntoOggOpus(audioFile, title, artist, album, artworkBytes, lyrics, year)
                else -> embedId3Prepend(audioFile, title, artist, album, artworkBytes, lyrics, year)
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
            java.io.RandomAccessFile(file, "r").use { input ->
                val payloadOffset = detectExistingId3v2TagLength(file)
                if (payloadOffset + 12 > input.length()) return ContainerKind.OTHER
                input.seek(payloadOffset)
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
        year: String? = null,
    ): Boolean {
        val id3TagBytes = buildId3v2Tag(
            title = title,
            artist = artist,
            album = album,
            artworkBytes = artworkBytes,
            lyrics = lyrics,
            year = year,
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
        year: String? = null,
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
        if (!year.isNullOrBlank()) {
            writeTextFrame(framesOut, "TYER", year)
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
        year: String? = null,
    ): Boolean {
        val blocks = mutableListOf<FlacBlockRef>()
        var framesStart = -1L
        val flacStart = detectExistingId3v2TagLength(audioFile)

        // Phase 1 — parse the metadata chain (no output written yet).
        java.io.RandomAccessFile(audioFile, "r").use { raf ->
            raf.seek(flacStart)
            val magic = ByteArray(4)
            if (raf.read(magic) < 4 || String(magic, StandardCharsets.US_ASCII) != FLAC_MAGIC) return false

            var offset = flacStart + 4L
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
        val originalComments = blocks.firstOrNull { it.type == FLAC_TYPE_VORBIS_COMMENT }
            ?.let { readFlacComments(audioFile, it) }
            .orEmpty()
        val keptBlocks = blocks.filter {
            it.type != 0 &&
                it.type != FLAC_TYPE_VORBIS_COMMENT &&
                !(it.type == FLAC_TYPE_PICTURE && artworkBytes != null)
        }

        val replacedFields = buildSet {
            addAll(setOf("TITLE", "ARTIST", "ALBUMARTIST", "ALBUM", "LYRICS", "UNSYNCEDLYRICS", "DATE", "YEAR"))
            if (artworkBytes != null) {
                addAll(setOf("METADATA_BLOCK_PICTURE", "COVERART", "COVERARTMIME"))
            }
        }
        val comments = originalComments
            .filterNot { it.substringBefore('=').trim().uppercase() in replacedFields }
            .toMutableList()
        if (title.isNotBlank()) comments += "TITLE=$title"
        if (artist.isNotBlank()) comments += "ARTIST=$artist"
        if (artist.isNotBlank()) comments += "ALBUMARTIST=$artist"
        if (!album.isNullOrBlank()) comments += "ALBUM=$album"
        if (!lyrics.isNullOrBlank()) comments += "LYRICS=$lyrics"
        if (!year.isNullOrBlank()) {
            comments += "DATE=$year"
            comments += "YEAR=$year"
        }

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
        val valid = tempFile.length() > 8L &&
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

    private fun readFlacComments(audioFile: File, block: FlacBlockRef): List<String> {
        return try {
            if (block.bodyLen <= 0) return emptyList()
            val body = ByteArray(block.bodyLen)
            java.io.RandomAccessFile(audioFile, "r").use { source ->
                source.seek(block.bodyStart)
                source.readFully(body)
            }
            parseVorbisComments(body, 0)
        } catch (_: Exception) {
            emptyList()
        }
    }

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
        val (width, height, depth) = imageDimensions(imageBytes)
        writeBe32(out, PIC_TYPE_COVER_FRONT.toInt() and 0xFF) // front cover
        writeBe32(out, mimeBytes.size)
        out.write(mimeBytes)
        writeBe32(out, 0) // description length (empty)
        writeBe32(out, width)
        writeBe32(out, height)
        writeBe32(out, depth)
        writeBe32(out, 0) // colors
        writeBe32(out, imageBytes.size)
        out.write(imageBytes)
        return out.toByteArray()
    }

    // ─────────────────────────── MP4/M4A (iTunes ilst atoms) ───────────────────────────

    // --------------------------- Ogg Opus (OpusTags) ---------------------------

    private data class OggPageRef(
        val offset: Long,
        val headerType: Int,
        val granulePosition: Long,
        val serialNumber: Int,
        val sequenceNumber: Int,
        val lacing: ByteArray,
        val dataStart: Long,
        val dataLength: Int,
        val totalLength: Int,
    )

    private data class OpusTagsLocation(
        val startPage: Int,
        val endPage: Int,
        val endSegment: Int,
        val serialNumber: Int,
        val sequenceNumber: Int,
        val packet: ByteArray,
    )

    /** Replaces the OpusTags packet with native Vorbis comments and a
     * METADATA_BLOCK_PICTURE comment. Audio packets are copied byte-for-byte;
     * only Ogg page headers, sequence numbers and checksums are rebuilt. */
    private fun embedIntoOggOpus(
        audioFile: File,
        title: String,
        artist: String,
        album: String?,
        artworkBytes: ByteArray?,
        lyrics: String?,
        year: String? = null,
    ): Boolean {
        val pages = readOggPages(audioFile)
        if (pages.isEmpty()) return false
        val location = findOpusTags(audioFile, pages) ?: return false
        val newPacket = buildOpusTagsPacket(location.packet, title, artist, album, artworkBytes, lyrics, year)
        if (newPacket.isEmpty()) return false

        val tempFile = File.createTempFile("tagged_", ".opus", audioFile.parentFile)
        return try {
            java.io.RandomAccessFile(audioFile, "r").use { source ->
                FileOutputStream(tempFile).buffered().use { output ->
                    for (index in 0 until location.startPage) {
                        val page = pages[index]
                        copyFromRandomAccess(source, output, page.offset, page.totalLength.toLong())
                    }

                    var nextSequence = writeOggPacketPages(
                        output,
                        newPacket,
                        location.serialNumber,
                        location.sequenceNumber,
                    )

                    val originalLastTagPage = pages[location.endPage]
                    val trailingLacing = originalLastTagPage.lacing.copyOfRange(
                        location.endSegment + 1,
                        originalLastTagPage.lacing.size,
                    )
                    if (trailingLacing.isNotEmpty()) {
                        val consumedData = originalLastTagPage.lacing
                            .take(location.endSegment + 1)
                            .sumOf { it.toInt() and 0xFF }
                        val trailingLength = trailingLacing.sumOf { it.toInt() and 0xFF }
                        val trailingData = ByteArray(trailingLength)
                        source.seek(originalLastTagPage.dataStart + consumedData)
                        source.readFully(trailingData)
                        writeOggPage(
                            output,
                            originalLastTagPage.headerType and 0xFE,
                            originalLastTagPage.granulePosition,
                            location.serialNumber,
                            nextSequence++,
                            trailingLacing,
                            trailingData,
                        )
                    }

                    for (index in (location.endPage + 1) until pages.size) {
                        val page = pages[index]
                        if (page.serialNumber == location.serialNumber) {
                            val data = ByteArray(page.dataLength)
                            source.seek(page.dataStart)
                            source.readFully(data)
                            writeOggPage(
                                output,
                                page.headerType,
                                page.granulePosition,
                                page.serialNumber,
                                nextSequence++,
                                page.lacing,
                                data,
                            )
                        } else {
                            copyFromRandomAccess(source, output, page.offset, page.totalLength.toLong())
                        }
                    }
                    output.flush()
                }
            }

            val valid = tempFile.length() > 64L && FileInputStream(tempFile).use { input ->
                val header = ByteArray(4)
                input.read(header) == 4 && String(header, StandardCharsets.US_ASCII) == OGG_CAPTURE_PATTERN
            }
            if (!valid) {
                tempFile.delete()
                false
            } else {
                replaceOriginal(audioFile, tempFile, minimumValidLength = 64)
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not rewrite Ogg Opus tags: ${error.message}", error)
            tempFile.delete()
            false
        }
    }

    private fun readOggPages(file: File): List<OggPageRef> {
        return try {
            java.io.RandomAccessFile(file, "r").use { source ->
                val pages = mutableListOf<OggPageRef>()
                var offset = detectExistingId3v2TagLength(file)
                while (offset < source.length()) {
                    if (source.length() - offset < 27L) return emptyList()
                    source.seek(offset)
                    val header = ByteArray(27)
                    source.readFully(header)
                    if (String(header, 0, 4, StandardCharsets.US_ASCII) != OGG_CAPTURE_PATTERN || header[4].toInt() != 0) {
                        return emptyList()
                    }
                    val segmentCount = header[26].toInt() and 0xFF
                    val lacing = ByteArray(segmentCount)
                    source.readFully(lacing)
                    val dataLength = lacing.sumOf { it.toInt() and 0xFF }
                    val dataStart = offset + 27L + segmentCount
                    val totalLength = 27 + segmentCount + dataLength
                    if (dataStart + dataLength > source.length()) return emptyList()
                    pages += OggPageRef(
                        offset = offset,
                        headerType = header[5].toInt() and 0xFF,
                        granulePosition = readLe64(header, 6),
                        serialNumber = readLe32(header, 14),
                        sequenceNumber = readLe32(header, 18),
                        lacing = lacing,
                        dataStart = dataStart,
                        dataLength = dataLength,
                        totalLength = totalLength,
                    )
                    offset += totalLength
                }
                if (offset == source.length()) pages else emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun findOpusTags(file: File, pages: List<OggPageRef>): OpusTagsLocation? {
        java.io.RandomAccessFile(file, "r").use { source ->
            var packetIndex = 0
            var packetStartPage = -1
            var packetStartSegment = -1
            var packetSerial = 0
            val packet = ByteArrayOutputStream()

            pages.forEachIndexed { pageIndex, page ->
                var dataOffset = 0
                page.lacing.forEachIndexed { segmentIndex, rawLength ->
                    val length = rawLength.toInt() and 0xFF
                    if (packetIndex <= 1) {
                        if (packet.size() == 0) {
                            packetStartPage = pageIndex
                            packetStartSegment = segmentIndex
                            packetSerial = page.serialNumber
                        } else if (page.serialNumber != packetSerial) {
                            return null
                        }
                        if (length > 0) {
                            val bytes = ByteArray(length)
                            source.seek(page.dataStart + dataOffset)
                            source.readFully(bytes)
                            packet.write(bytes)
                        }
                    }
                    dataOffset += length

                    if (length < 255) {
                        val completed = packet.toByteArray()
                        if (packetIndex == 0 && !completed.startsWithAscii(OPUS_HEAD)) return null
                        if (packetIndex == 1) {
                            if (!completed.startsWithAscii(OPUS_TAGS) || packetStartSegment != 0) return null
                            return OpusTagsLocation(
                                packetStartPage,
                                pageIndex,
                                segmentIndex,
                                packetSerial,
                                pages[packetStartPage].sequenceNumber,
                                completed,
                            )
                        }
                        packetIndex++
                        packet.reset()
                    }
                }
            }
        }
        return null
    }

    private fun buildOpusTagsPacket(
        originalPacket: ByteArray,
        title: String,
        artist: String,
        album: String?,
        artworkBytes: ByteArray?,
        lyrics: String?,
        year: String? = null,
    ): ByteArray {
        val replacedFields = buildSet {
            addAll(setOf("TITLE", "ARTIST", "ALBUMARTIST", "ALBUM", "LYRICS", "UNSYNCEDLYRICS", "DATE", "YEAR"))
            if (artworkBytes != null) {
                addAll(setOf("METADATA_BLOCK_PICTURE", "COVERART", "COVERARTMIME"))
            }
        }
        val comments = parseOpusComments(originalPacket)
            .filterNot { it.substringBefore('=').trim().uppercase() in replacedFields }
            .toMutableList()
        if (title.isNotBlank()) comments += "TITLE=$title"
        if (artist.isNotBlank()) {
            comments += "ARTIST=$artist"
            comments += "ALBUMARTIST=$artist"
        }
        if (!album.isNullOrBlank()) comments += "ALBUM=$album"
        if (!lyrics.isNullOrBlank()) comments += "LYRICS=$lyrics"
        if (!year.isNullOrBlank()) {
            comments += "DATE=$year"
            comments += "YEAR=$year"
        }
        if (artworkBytes != null && artworkBytes.isNotEmpty()) {
            comments += "METADATA_BLOCK_PICTURE=${base64NoWrap(buildFlacPictureBody(artworkBytes))}"
        }

        val out = ByteArrayOutputStream()
        out.write(OPUS_TAGS.toByteArray(StandardCharsets.US_ASCII))
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

    private fun parseOpusComments(packet: ByteArray): List<String> {
        if (!packet.startsWithAscii(OPUS_TAGS)) return emptyList()
        return parseVorbisComments(packet, 8)
    }

    private fun parseVorbisComments(bytes: ByteArray, startOffset: Int): List<String> {
        return try {
            var offset = startOffset
            if (offset < 0 || offset + 4 > bytes.size) return emptyList()
            val vendorLength = readLe32(bytes, offset)
            if (vendorLength < 0 || offset + 4L + vendorLength > bytes.size) return emptyList()
            offset += 4 + vendorLength
            if (offset + 4 > bytes.size) return emptyList()
            val count = readLe32(bytes, offset)
            offset += 4
            if (count !in 0..10_000) return emptyList()
            buildList {
                repeat(count) {
                    if (offset + 4 > bytes.size) return@buildList
                    val length = readLe32(bytes, offset)
                    offset += 4
                    if (length < 0 || offset + length.toLong() > bytes.size) return@buildList
                    add(String(bytes, offset, length, StandardCharsets.UTF_8))
                    offset += length
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeOggPacketPages(
        output: OutputStream,
        packet: ByteArray,
        serialNumber: Int,
        firstSequence: Int,
    ): Int {
        val fullSegments = packet.size / 255
        val totalSegments = fullSegments + 1
        var segmentOffset = 0
        var dataOffset = 0
        var sequence = firstSequence
        var firstPage = true
        while (segmentOffset < totalSegments) {
            val count = minOf(OGG_MAX_SEGMENTS, totalSegments - segmentOffset)
            val lacing = ByteArray(count)
            var dataLength = 0
            repeat(count) { localIndex ->
                val globalIndex = segmentOffset + localIndex
                val length = if (globalIndex < fullSegments) 255 else packet.size % 255
                lacing[localIndex] = length.toByte()
                dataLength += length
            }
            val data = packet.copyOfRange(dataOffset, dataOffset + dataLength)
            writeOggPage(
                output,
                if (firstPage) 0 else 0x01,
                0L,
                serialNumber,
                sequence++,
                lacing,
                data,
            )
            segmentOffset += count
            dataOffset += dataLength
            firstPage = false
        }
        return sequence
    }

    private fun writeOggPage(
        output: OutputStream,
        headerType: Int,
        granulePosition: Long,
        serialNumber: Int,
        sequenceNumber: Int,
        lacing: ByteArray,
        data: ByteArray,
    ) {
        require(lacing.size <= OGG_MAX_SEGMENTS)
        require(lacing.sumOf { it.toInt() and 0xFF } == data.size)
        val page = ByteArrayOutputStream(27 + lacing.size + data.size)
        page.write(OGG_CAPTURE_PATTERN.toByteArray(StandardCharsets.US_ASCII))
        page.write(0)
        page.write(headerType and 0xFF)
        writeLe64(page, granulePosition)
        writeLe32(page, serialNumber)
        writeLe32(page, sequenceNumber)
        writeLe32(page, 0)
        page.write(lacing.size)
        page.write(lacing)
        page.write(data)
        val bytes = page.toByteArray()
        val crc = oggCrc(bytes)
        bytes[22] = crc.toByte()
        bytes[23] = (crc ushr 8).toByte()
        bytes[24] = (crc ushr 16).toByte()
        bytes[25] = (crc ushr 24).toByte()
        output.write(bytes)
    }

    private fun oggCrc(bytes: ByteArray): Int {
        var crc = 0
        for (byte in bytes) {
            val index = ((crc ushr 24) xor (byte.toInt() and 0xFF)) and 0xFF
            crc = (crc shl 8) xor OGG_CRC_TABLE[index]
        }
        return crc
    }

    private fun ByteArray.startsWithAscii(value: String): Boolean {
        val prefix = value.toByteArray(StandardCharsets.US_ASCII)
        if (size < prefix.size) return false
        return prefix.indices.all { this[it] == prefix[it] }
    }

    private fun imageDimensions(bytes: ByteArray): Triple<Int, Int, Int> {
        if (isPngBytes(bytes) && bytes.size >= 24) {
            val width = readBeUInt32(bytes, 16).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val height = readBeUInt32(bytes, 20).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            return Triple(width.coerceAtLeast(0), height.coerceAtLeast(0), 32)
        }
        if (bytes.size >= 10 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
            val startOfFrameMarkers = setOf(0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF)
            var offset = 2
            while (offset + 9 < bytes.size) {
                if ((bytes[offset].toInt() and 0xFF) != 0xFF) {
                    offset++
                    continue
                }
                var markerOffset = offset + 1
                while (markerOffset < bytes.size && (bytes[markerOffset].toInt() and 0xFF) == 0xFF) markerOffset++
                if (markerOffset >= bytes.size) break
                val marker = bytes[markerOffset].toInt() and 0xFF
                if (marker == 0xD8 || marker == 0xD9 || marker == 0x01) {
                    offset = markerOffset + 1
                    continue
                }
                if (markerOffset + 2 >= bytes.size) break
                val segmentLength = ((bytes[markerOffset + 1].toInt() and 0xFF) shl 8) or
                    (bytes[markerOffset + 2].toInt() and 0xFF)
                if (segmentLength < 2 || markerOffset + 1L + segmentLength > bytes.size) break
                if (marker in startOfFrameMarkers && segmentLength >= 8) {
                    val precision = bytes[markerOffset + 3].toInt() and 0xFF
                    val height = ((bytes[markerOffset + 4].toInt() and 0xFF) shl 8) or
                        (bytes[markerOffset + 5].toInt() and 0xFF)
                    val width = ((bytes[markerOffset + 6].toInt() and 0xFF) shl 8) or
                        (bytes[markerOffset + 7].toInt() and 0xFF)
                    val components = bytes.getOrNull(markerOffset + 8)?.toInt()?.and(0xFF) ?: 3
                    return Triple(width, height, (precision * components).coerceAtLeast(1))
                }
                offset = markerOffset + 1 + segmentLength
            }
        }
        return Triple(0, 0, 0)
    }

    private fun base64NoWrap(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val output = CharArray(((bytes.size + 2) / 3) * 4)
        var source = 0
        var target = 0
        while (source < bytes.size) {
            val first = bytes[source++].toInt() and 0xFF
            val hasSecond = source < bytes.size
            val second = if (hasSecond) bytes[source++].toInt() and 0xFF else 0
            val hasThird = source < bytes.size
            val third = if (hasThird) bytes[source++].toInt() and 0xFF else 0
            output[target++] = BASE64_ALPHABET[first ushr 2]
            output[target++] = BASE64_ALPHABET[((first and 0x03) shl 4) or (second ushr 4)]
            output[target++] = if (hasSecond) BASE64_ALPHABET[((second and 0x0F) shl 2) or (third ushr 6)] else '='
            output[target++] = if (hasThird) BASE64_ALPHABET[third and 0x3F] else '='
        }
        return String(output)
    }

    private fun readLe32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun readLe64(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        repeat(8) { index ->
            value = value or ((bytes[offset + index].toLong() and 0xFFL) shl (index * 8))
        }
        return value
    }

    private fun writeLe64(out: ByteArrayOutputStream, value: Long) {
        repeat(8) { index -> out.write((value ushr (index * 8)).toInt() and 0xFF) }
    }

    private fun copyFromRandomAccess(
        source: java.io.RandomAccessFile,
        output: OutputStream,
        offset: Long,
        byteCount: Long,
    ) {
        source.seek(offset)
        val buffer = ByteArray(64 * 1024)
        var remaining = byteCount
        while (remaining > 0) {
            val read = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) throw java.io.EOFException("Ogg stream ended while copying")
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    /** Appends native Matroska tags and an attached cover inside the WebM Segment. */
    private data class WebmChildRef(
        val id: Long,
        val elementStart: Long,
        val elementLength: Long,
    )

    private fun embedIntoWebm(
        audioFile: File,
        title: String,
        artist: String,
        album: String?,
        artworkBytes: ByteArray?,
        lyrics: String?,
        year: String? = null,
    ): Boolean {
        val metadata = buildWebmMetadata(title, artist, album, artworkBytes, lyrics, year)
        if (metadata.isEmpty()) return false

        val fileSize = audioFile.length()
        val segment: EbmlElementHeader
        val children: List<WebmChildRef>?
        java.io.RandomAccessFile(audioFile, "r").use { raf ->
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
            segment = found ?: return false
            children = collectSegmentChildren(raf, segment, fileSize)
        }

        // Preferred path: splice Tags/Attachments in right before the first
        // Cluster, matching where real muxers place them. Many players and
        // file managers only scan the header region of a WebM/Matroska file
        // (stopping once they hit audio data) rather than the whole file, so
        // metadata tacked onto the very end after all Clusters is often
        // invisible to them even though it's structurally valid EBML.
        // Skipped when a Cues index is present, since its byte offsets would
        // need rewriting too and we can't safely do that here — in that case
        // we fall back to the old end-of-file append.
        val hasCues = children?.any { it.id == 0x1C53BB6BL } == true
        if (children != null && !hasCues) {
            if (spliceWebmMetadataBeforeFirstCluster(audioFile, segment, children, metadata)) {
                return true
            }
            // Fall through to the legacy append if splicing failed for any reason.
        }

        return appendWebmMetadataAtEnd(audioFile, fileSize, segment, metadata)
    }

    /** Walks a Segment's direct children, recording each one's byte range. */
    private fun collectSegmentChildren(
        raf: java.io.RandomAccessFile,
        segment: EbmlElementHeader,
        fileSize: Long,
    ): List<WebmChildRef>? {
        val segmentEnd = segment.dataSize?.let { segment.dataStart + it } ?: fileSize
        val children = mutableListOf<WebmChildRef>()
        var offset = segment.dataStart
        while (offset < segmentEnd) {
            val element = readEbmlElementHeader(raf, offset, fileSize) ?: return null
            // A child with unknown size (e.g. a still-being-written live Cluster)
            // can't be safely relocated — bail out and let the caller fall back
            // to the plain end-of-file append instead.
            val size = element.dataSize ?: return null
            val elementLength = (element.dataStart - offset) + size
            children += WebmChildRef(element.id, offset, elementLength)
            offset += elementLength
        }
        return children
    }

    /**
     * Rebuilds the file with a fresh Tags/Attachments block inserted right
     * before the first Cluster, dropping any existing SeekHead (its seek
     * offsets would be stale after the shift — a stale index is worse than
     * none, since compliant readers fall back to a normal sequential scan
     * when SeekHead is simply absent) and any existing Tags/Attachments so
     * we don't leave duplicates behind.
     */
    private fun spliceWebmMetadataBeforeFirstCluster(
        audioFile: File,
        segment: EbmlElementHeader,
        children: List<WebmChildRef>,
        metadata: ByteArray,
    ): Boolean {
        val dropIds = setOf(0x114D9B74L, 0x1254C367L, 0x1941A469L) // SeekHead, Tags, Attachments
        val insertBefore = children.firstOrNull { it.id == 0x1F43B675L }?.elementStart

        var removedBytes = 0L
        val tempFile = File.createTempFile("tagged_", ".webm", audioFile.parentFile)
        return try {
            java.io.RandomAccessFile(audioFile, "r").use { source ->
                FileOutputStream(tempFile).buffered().use { output ->
                    // EBML header through the Segment's own id/size bytes.
                    copyFromRandomAccess(source, output, 0L, segment.dataStart)

                    var metadataWritten = false
                    for (child in children) {
                        if (insertBefore != null && child.elementStart == insertBefore) {
                            output.write(metadata)
                            metadataWritten = true
                        }
                        if (child.id in dropIds) {
                            removedBytes += child.elementLength
                            continue
                        }
                        copyFromRandomAccess(source, output, child.elementStart, child.elementLength)
                    }
                    // No Cluster at all (shouldn't happen for real audio, but
                    // be safe) — just place the metadata at the end.
                    if (!metadataWritten) output.write(metadata)
                    output.flush()
                }
            }

            if (segment.dataSize != null) {
                val newDataSize = segment.dataSize - removedBytes + metadata.size
                val newSizeBytes = encodeEbmlSize(newDataSize, segment.sizeLength) ?: run {
                    tempFile.delete()
                    return false
                }
                java.io.RandomAccessFile(tempFile, "rw").use { raf ->
                    raf.seek(segment.sizeStart)
                    raf.write(newSizeBytes)
                }
            }

            val validHeader = tempFile.length() > 16L && FileInputStream(tempFile).use { input ->
                val head = ByteArray(4)
                input.read(head) == 4 &&
                    head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() &&
                    head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte()
            }
            if (!validHeader) {
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

    /** Legacy path: appends the Tags/Attachments block after all existing Segment data. */
    private fun appendWebmMetadataAtEnd(
        audioFile: File,
        fileSize: Long,
        segment: EbmlElementHeader,
        metadata: ByteArray,
    ): Boolean {
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
        year: String? = null,
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
        if (!year.isNullOrBlank()) addWebmSimpleTag(tagBody, "DATE", year)

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
        year: String? = null,
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
                val size32 = ((head[0].toLong() and 0xFF) shl 24) or
                    ((head[1].toLong() and 0xFF) shl 16) or
                    ((head[2].toLong() and 0xFF) shl 8) or
                    (head[3].toLong() and 0xFF)
                val size = when (size32) {
                    0L -> fileSize - offset
                    1L -> {
                        if (offset + 16 > fileSize) return false
                        raf.seek(offset + 8)
                        val extHead = ByteArray(8)
                        if (raf.read(extHead) < 8) return false
                        readBeUInt64(extHead, 0) ?: return false
                    }
                    else -> size32
                }
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
        if (!year.isNullOrBlank()) addMp4TextItem(ilstItems, "\u00A9day", year)
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

        val moovBody = ByteArray((moovSize - 8).toInt())
        java.io.RandomAccessFile(audioFile, "r").use { raf ->
            raf.seek(moovStart + 8)
            raf.readFully(moovBody)
        }

        // Clean out any existing udta boxes so we don't produce duplicate udta boxes
        // which standard players and Android MediaMetadataRetriever ignore.
        val cleanedMoovBody = removeTopLevelBoxes(moovBody, setOf("udta"))
        val delta = (cleanedMoovBody.size + udtaBox.size) - moovBody.size
        val newMoovSize = 8L + cleanedMoovBody.size + udtaBox.size
        if (newMoovSize > 0x7FFFFFFFL) return false

        // When moov precedes mdat, adding metadata shifts media bytes forward.
        // Patch every absolute stco/co64 chunk offset that points past moov.
        if (delta != 0) {
            if (!patchMp4ChunkOffsets(
                    bytes = cleanedMoovBody,
                    start = 0,
                    end = cleanedMoovBody.size,
                    shiftedRegionStart = moovStart + moovSize,
                    delta = delta,
                )
            ) return false
        }

        // Phase 2 — stream the preserved prefix, patched moov, metadata and tail.
        val tempFile = File.createTempFile("tagged_", ".m4a", audioFile.parentFile)
        try {
            FileOutputStream(tempFile).buffered().use { out ->
                // Preserve ftyp/mdat/free and every other box before moov.
                FileInputStream(audioFile).use { input ->
                    copyExactly(input, out, moovStart)
                }

                out.write((newMoovSize ushr 24).toInt() and 0xFF)
                out.write((newMoovSize ushr 16).toInt() and 0xFF)
                out.write((newMoovSize ushr 8).toInt() and 0xFF)
                out.write(newMoovSize.toInt() and 0xFF)
                out.write("moov".toByteArray(StandardCharsets.US_ASCII))

                out.write(cleanedMoovBody)
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

        val valid = tempFile.length() == fileSize + delta
        if (!valid) {
            tempFile.delete()
            return false
        }
        return replaceOriginal(audioFile, tempFile, minimumValidLength = 16)
    }

    private fun removeTopLevelBoxes(bytes: ByteArray, boxTypes: Set<String>): ByteArray {
        val out = ByteArrayOutputStream()
        var offset = 0
        while (offset + 8 <= bytes.size) {
            val size32 = readBeUInt32(bytes, offset)
            val type = String(bytes, offset + 4, 4, StandardCharsets.ISO_8859_1)
            val boxSize = when (size32) {
                0L -> (bytes.size - offset).toLong()
                1L -> {
                    if (offset + 16 > bytes.size) return bytes
                    readBeUInt64(bytes, offset + 8) ?: return bytes
                }
                else -> size32
            }
            if (boxSize < 8 || offset + boxSize > bytes.size) return bytes
            val boxLen = boxSize.toInt()
            if (type !in boxTypes) {
                out.write(bytes, offset, boxLen)
            }
            offset += boxLen
        }
        if (offset == bytes.size) {
            return out.toByteArray()
        }
        return bytes
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

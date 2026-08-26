package com.lastwave.app.data.download

import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Losslessly moves Opus packets from YouTube's WebM container into Ogg Opus.
 * No audio is decoded or re-encoded, so the downloaded stream stays bit-exact.
 */
internal object WebmOpusRemuxer {
    private const val TAG = "WebmOpusRemuxer"
    private const val OGG_CAPTURE_PATTERN = "OggS"
    private const val OPUS_HEAD = "OpusHead"
    private const val OPUS_TAGS = "OpusTags"
    private const val OGG_MAX_SEGMENTS = 255
    private const val TARGET_PAGE_PAYLOAD = 60 * 1024
    private const val MAX_PACKET_BYTES = 2 * 1024 * 1024
    private const val OGG_CRC_POLYNOMIAL = 0x04C11DB7

    private val oggCrcTable = IntArray(256) { index ->
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

    fun remux(inputWebm: File, outputOpus: File): Boolean {
        if (!inputWebm.isFile || inputWebm.length() <= 0L || inputWebm == outputOpus) return false
        if (outputOpus.exists() && outputOpus.length() > 0L) return false

        val extractor = MediaExtractor()
        var completed = false
        return try {
            extractor.setDataSource(inputWebm.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.equals("audio/opus", ignoreCase = true) == true
            } ?: return false

            val format = extractor.getTrackFormat(trackIndex)
            val opusHead = format.byteArray("csd-0")
                ?.takeIf { it.startsWithAscii(OPUS_HEAD) && it.size >= 19 }
                ?: return false
            val preSkip = (opusHead[10].toInt() and 0xFF) or
                ((opusHead[11].toInt() and 0xFF) shl 8)
            val declaredDurationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(0L)
            } else {
                0L
            }

            extractor.selectTrack(trackIndex)
            outputOpus.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs()) return false
            }

            val serialNumber = ((inputWebm.length() xor System.nanoTime()).toInt()).let {
                if (it == 0) 1 else it
            }
            var sequenceNumber = 0
            var totalSamples = 0L
            var previousPageGranule = 0L
            var lastPacketSamples = 0
            var packetCount = 0
            var sampleBuffer = ByteBuffer.allocateDirect(
                format.integerOrNull(MediaFormat.KEY_MAX_INPUT_SIZE)
                    ?.coerceIn(4 * 1024, MAX_PACKET_BYTES)
                    ?: 64 * 1024,
            )

            FileOutputStream(outputOpus).buffered().use { output ->
                writePacketPage(
                    output = output,
                    packet = opusHead,
                    headerType = 0x02,
                    granulePosition = 0L,
                    serialNumber = serialNumber,
                    sequenceNumber = sequenceNumber++,
                )
                writePacketPage(
                    output = output,
                    packet = emptyOpusTags(),
                    headerType = 0,
                    granulePosition = 0L,
                    serialNumber = serialNumber,
                    sequenceNumber = sequenceNumber++,
                )

                val pageLacing = ByteArrayOutputStream()
                val pagePayload = ByteArrayOutputStream()

                fun flushAudioPage(endOfStream: Boolean) {
                    if (pageLacing.size() == 0) return
                    var pageGranule = totalSamples
                    if (endOfStream && declaredDurationUs > 0L) {
                        val declaredEnd = preSkip.toLong() +
                            ((declaredDurationUs * 48_000L + 500_000L) / 1_000_000L)
                        val earliestValidEnd = (totalSamples - lastPacketSamples).coerceAtLeast(previousPageGranule)
                        if (declaredEnd in earliestValidEnd..totalSamples) pageGranule = declaredEnd
                    }
                    writeOggPage(
                        output = output,
                        headerType = if (endOfStream) 0x04 else 0,
                        granulePosition = pageGranule,
                        serialNumber = serialNumber,
                        sequenceNumber = sequenceNumber++,
                        lacing = pageLacing.toByteArray(),
                        data = pagePayload.toByteArray(),
                    )
                    previousPageGranule = pageGranule
                    pageLacing.reset()
                    pagePayload.reset()
                }

                while (true) {
                    val reportedSize = extractor.sampleSize
                    if (reportedSize < 0L) break
                    if (reportedSize > MAX_PACKET_BYTES) {
                        throw IllegalArgumentException("Opus packet is unexpectedly large: $reportedSize bytes")
                    }
                    if (reportedSize > sampleBuffer.capacity()) {
                        sampleBuffer = ByteBuffer.allocateDirect(reportedSize.toInt())
                    }
                    sampleBuffer.clear()
                    val read = extractor.readSampleData(sampleBuffer, 0)
                    if (read < 0) break
                    if (read == 0) {
                        if (!extractor.advance()) break
                        continue
                    }

                    val packet = ByteArray(read)
                    repeat(read) { index -> packet[index] = sampleBuffer.get(index) }
                    val packetSamples = opusPacketDurationSamples(packet)
                        ?: throw IllegalArgumentException("Invalid Opus packet in WebM")
                    val packetLacing = packetLacing(read)

                    if (pageLacing.size() + packetLacing.size > OGG_MAX_SEGMENTS ||
                        pagePayload.size() + read > TARGET_PAGE_PAYLOAD
                    ) {
                        flushAudioPage(endOfStream = false)
                    }

                    pageLacing.write(packetLacing)
                    pagePayload.write(packet)
                    totalSamples += packetSamples
                    lastPacketSamples = packetSamples
                    packetCount++

                    if (!extractor.advance()) break
                }

                if (packetCount == 0) throw IllegalArgumentException("WebM contains no Opus audio packets")
                flushAudioPage(endOfStream = true)
                output.flush()
            }

            isValidOgg(outputOpus).also { completed = it }
        } catch (error: Exception) {
            Log.w(TAG, "Could not remux ${inputWebm.name} to Ogg Opus", error)
            false
        } finally {
            runCatching { extractor.release() }
            if (!completed) runCatching { outputOpus.delete() }
        }
    }

    private fun emptyOpusTags(): ByteArray = ByteArrayOutputStream().apply {
        write(OPUS_TAGS.toByteArray(StandardCharsets.US_ASCII))
        val vendor = "LastWave".toByteArray(StandardCharsets.UTF_8)
        writeLe32(this, vendor.size)
        write(vendor)
        writeLe32(this, 0)
    }.toByteArray()

    private fun packetLacing(packetSize: Int): ByteArray {
        val fullSegments = packetSize / 255
        return ByteArray(fullSegments + 1) { index ->
            if (index < fullSegments) 255.toByte() else (packetSize % 255).toByte()
        }
    }

    private fun writePacketPage(
        output: OutputStream,
        packet: ByteArray,
        headerType: Int,
        granulePosition: Long,
        serialNumber: Int,
        sequenceNumber: Int,
    ) {
        writeOggPage(
            output = output,
            headerType = headerType,
            granulePosition = granulePosition,
            serialNumber = serialNumber,
            sequenceNumber = sequenceNumber,
            lacing = packetLacing(packet.size),
            data = packet,
        )
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

    private fun opusPacketDurationSamples(packet: ByteArray): Int? {
        if (packet.isEmpty()) return null
        val toc = packet[0].toInt() and 0xFF
        val configuration = toc ushr 3
        val samplesPerFrame = when {
            configuration < 12 -> when (configuration and 0x03) {
                0 -> 480
                1 -> 960
                2 -> 1_920
                else -> 2_880
            }
            configuration < 16 -> if ((configuration and 0x01) == 0) 480 else 960
            else -> 120 shl (configuration and 0x03)
        }
        val frameCount = when (toc and 0x03) {
            0 -> 1
            1, 2 -> 2
            else -> if (packet.size >= 2) packet[1].toInt() and 0x3F else return null
        }
        if (frameCount !in 1..48) return null
        return (samplesPerFrame * frameCount).takeIf { it in 120..5_760 }
    }

    private fun MediaFormat.byteArray(key: String): ByteArray? = runCatching {
        getByteBuffer(key)?.duplicate()?.let { source ->
            source.rewind()
            ByteArray(source.remaining()).also { bytes -> source.get(bytes) }
        }
    }.getOrNull()

    private fun MediaFormat.integerOrNull(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    private fun ByteArray.startsWithAscii(value: String): Boolean {
        val prefix = value.toByteArray(StandardCharsets.US_ASCII)
        return size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
    }

    private fun writeLe32(out: ByteArrayOutputStream, value: Int) {
        repeat(4) { index -> out.write((value ushr (index * 8)) and 0xFF) }
    }

    private fun writeLe64(out: ByteArrayOutputStream, value: Long) {
        repeat(8) { index -> out.write(((value ushr (index * 8)) and 0xFF).toInt()) }
    }

    private fun oggCrc(bytes: ByteArray): Int {
        var crc = 0
        for (byte in bytes) {
            val index = ((crc ushr 24) xor (byte.toInt() and 0xFF)) and 0xFF
            crc = (crc shl 8) xor oggCrcTable[index]
        }
        return crc
    }

    private fun isValidOgg(file: File): Boolean = runCatching {
        file.length() > 64L && FileInputStream(file).use { input ->
            val header = ByteArray(4)
            input.read(header) == 4 && String(header, StandardCharsets.US_ASCII) == OGG_CAPTURE_PATTERN
        }
    }.getOrDefault(false)
}

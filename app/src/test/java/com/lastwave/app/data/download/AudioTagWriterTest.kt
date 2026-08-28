package com.lastwave.app.data.download

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

class AudioTagWriterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var writer: AudioTagWriter
    private lateinit var okHttpClient: OkHttpClient

    @Before
    fun setUp() {
        okHttpClient = OkHttpClient()
        writer = AudioTagWriter(okHttpClient)
    }

    @Test
    fun buildId3v2Tag_containsExpectedFramesAndUTF16Encoding() {
        val sampleArtwork = byteArrayOf(
            0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
            0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte(),
            0x00, 0x00, 0x00, 0x0D,
            'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte(),
            0x00, 0x00, 0x00, 0x10,
            0x00, 0x00, 0x00, 0x10,
            0x08, 0x06, 0x00, 0x00, 0x00,
        )

        val id3Bytes = writer.buildId3v2Tag(
            title = "Bohemian Rhapsody",
            artist = "Queen",
            album = "A Night at the Opera",
            artworkBytes = sampleArtwork,
            lyrics = "[00:01.00]Is this the real life?",
            year = "1975",
        )

        assertThat(id3Bytes.size).isGreaterThan(10)
        // Check ID3 header
        assertThat(id3Bytes[0]).isEqualTo('I'.code.toByte())
        assertThat(id3Bytes[1]).isEqualTo('D'.code.toByte())
        assertThat(id3Bytes[2]).isEqualTo('3'.code.toByte())
        assertThat(id3Bytes[3]).isEqualTo(0x03.toByte()) // version 2.3

        val str = String(id3Bytes, StandardCharsets.ISO_8859_1)
        assertThat(str).contains("TIT2")
        assertThat(str).contains("TPE1")
        assertThat(str).contains("TALB")
        assertThat(str).contains("TYER")
        assertThat(str).contains("USLT")
        assertThat(str).contains("APIC")
    }

    @Test
    fun embedMetadata_intoFlac_preservesStreamInfoAndAddsVorbisComments() {
        val flacFile = tempFolder.newFile("test.flac")
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(StandardCharsets.US_ASCII))
        // STREAMINFO block (type 0, last block: 0x80 or 0, size 34)
        out.write(0x80) // isLast = true, type = 0
        out.write(0x00)
        out.write(0x00)
        out.write(34)
        out.write(ByteArray(34)) // 34 zero bytes for streaminfo
        // Fake audio frame bytes
        out.write(byteArrayOf(0xFF.toByte(), 0xF8.toByte(), 0x00, 0x00, 0x01, 0x02, 0x03))
        flacFile.writeBytes(out.toByteArray())

        val success = writer.embedMetadata(
            audioFile = flacFile,
            title = "Starboy",
            artist = "The Weeknd",
            album = "Starboy",
            artworkUrl = null,
            lyrics = "I'm tryna put you in the worst mood",
            year = "2016",
        )

        assertThat(success).isTrue()
        val taggedBytes = flacFile.readBytes()
        val taggedStr = String(taggedBytes, StandardCharsets.UTF_8)
        assertThat(taggedBytes.size).isGreaterThan(40)
        assertThat(String(taggedBytes.copyOfRange(0, 4), StandardCharsets.US_ASCII)).isEqualTo("fLaC")
        assertThat(taggedStr).contains("TITLE=Starboy")
        assertThat(taggedStr).contains("ARTIST=The Weeknd")
        assertThat(taggedStr).contains("ALBUM=Starboy")
        assertThat(taggedStr).contains("DATE=2016")
    }

    @Test
    fun embedMetadata_intoMp4_replacesExistingUdtaAndAddsItunesAtoms() {
        val mp4File = tempFolder.newFile("test.m4a")
        val out = ByteArrayOutputStream()

        // 1. ftyp box
        val ftypBody = "M4A \u0000\u0000\u0002\u0000isomiso2".toByteArray(StandardCharsets.ISO_8859_1)
        val ftypSize = 8 + ftypBody.size
        out.write(ftypSize ushr 24 and 0xFF)
        out.write(ftypSize ushr 16 and 0xFF)
        out.write(ftypSize ushr 8 and 0xFF)
        out.write(ftypSize and 0xFF)
        out.write("ftyp".toByteArray(StandardCharsets.US_ASCII))
        out.write(ftypBody)

        // 2. moov box with a dummy old udta box
        val oldUdtaBody = "old_encoder_info".toByteArray(StandardCharsets.ISO_8859_1)
        val oldUdtaSize = 8 + oldUdtaBody.size
        val oldUdtaBox = ByteArrayOutputStream().apply {
            write(oldUdtaSize ushr 24 and 0xFF)
            write(oldUdtaSize ushr 16 and 0xFF)
            write(oldUdtaSize ushr 8 and 0xFF)
            write(oldUdtaSize and 0xFF)
            write("udta".toByteArray(StandardCharsets.US_ASCII))
            write(oldUdtaBody)
        }.toByteArray()

        val mvhdBody = ByteArray(32)
        val mvhdSize = 8 + mvhdBody.size
        val mvhdBox = ByteArrayOutputStream().apply {
            write(mvhdSize ushr 24 and 0xFF)
            write(mvhdSize ushr 16 and 0xFF)
            write(mvhdSize ushr 8 and 0xFF)
            write(mvhdSize and 0xFF)
            write("mvhd".toByteArray(StandardCharsets.US_ASCII))
            write(mvhdBody)
        }.toByteArray()

        val moovBodyBytes = ByteArrayOutputStream().apply {
            write(mvhdBox)
            write(oldUdtaBox)
        }.toByteArray()

        val moovSize = 8 + moovBodyBytes.size
        out.write(moovSize ushr 24 and 0xFF)
        out.write(moovSize ushr 16 and 0xFF)
        out.write(moovSize ushr 8 and 0xFF)
        out.write(moovSize and 0xFF)
        out.write("moov".toByteArray(StandardCharsets.US_ASCII))
        out.write(moovBodyBytes)

        // 3. mdat box
        val mdatBody = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)
        val mdatSize = 8 + mdatBody.size
        out.write(mdatSize ushr 24 and 0xFF)
        out.write(mdatSize ushr 16 and 0xFF)
        out.write(mdatSize ushr 8 and 0xFF)
        out.write(mdatSize and 0xFF)
        out.write("mdat".toByteArray(StandardCharsets.US_ASCII))
        out.write(mdatBody)

        mp4File.writeBytes(out.toByteArray())

        val success = writer.embedMetadata(
            audioFile = mp4File,
            title = "Blinding Lights",
            artist = "The Weeknd",
            album = "After Hours",
            artworkUrl = null,
            lyrics = "I said, ooh, I'm blinded by the lights",
            year = "2020",
        )

        assertThat(success).isTrue()
        val taggedBytes = mp4File.readBytes()
        val taggedStr = String(taggedBytes, StandardCharsets.ISO_8859_1)

        // Verify old udta was replaced and new iTunes atoms exist
        assertThat(taggedStr).doesNotContain("old_encoder_info")
        assertThat(taggedStr).contains("\u00A9nam")
        assertThat(taggedStr).contains("\u00A9ART")
        assertThat(taggedStr).contains("\u00A9alb")
        assertThat(taggedStr).contains("\u00A9day")
        assertThat(taggedStr).contains("\u00A9lyr")
        assertThat(taggedStr).contains("ilst")
    }
}

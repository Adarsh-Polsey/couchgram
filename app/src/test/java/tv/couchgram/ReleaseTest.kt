package tv.couchgram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The first five cases follow a common detailed caption format (titles made up); the rest are other names a channel may hold. */
class ReleaseTest {
    @Test
    fun hdripX264() {
        val r = Release.parse(
            "Monsoon Letters 2026 Malayalam HQ HDRip - 1080p - x264 - DD+5.1 - 640Kbps & AAC 2.0 - 2.5GB - ESub\n\n🎬 1080p x264 | ⏳ 01:59:39\n🔊 Malayalam",
            "Monsoon Letters 2026 Malayalam HQ HDRip - 1080p - x264 - DD+.mkv",
        )
        assertEquals("Monsoon Letters", r.title)
        assertEquals(2026, r.year)
        assertEquals(listOf("Malayalam"), r.languages)
        assertEquals("1080p", r.resolution)
        assertEquals("H.264", r.codec)
        assertFalse(r.tenBit)
        assertEquals("HDRip", r.source)
        assertEquals(listOf("Dolby 5.1", "AAC"), r.audio)
        assertTrue(r.subtitles)
        assertEquals(1 * 3600 + 59 * 60 + 39, r.runtimeSec)
        assertEquals("H.264", r.version)
        assertEquals("Monsoon Letters (2026) · H.264", r.label)
    }

    @Test
    fun ds4kNetflixWithGroupAndExtension() {
        val r = Release.parse(
            "Monsoon Letters 2026 MAL-HIN DS4K 1080p NF WEB-Rip E-AC3 5.1 10bit HEVC - NOGRP.mkv\n\n🎬 1080p HEVC 10bit | ⏳ 01:59:39\n🔊 Hindi, Malayalam",
            "Monsoon Letters 2026 MAL-HIN DS4K 1080p NF WEB-Rip E-AC3 5.1.mkv",
        )
        assertEquals("Monsoon Letters", r.title)
        assertEquals(listOf("Hindi", "Malayalam"), r.languages) // 🔊 line wins over MAL-HIN
        assertEquals("HEVC", r.codec)
        assertTrue(r.tenBit)
        assertTrue(r.fromFourK)
        assertEquals("Netflix", r.service)
        assertEquals("WEBRip", r.source)
        assertEquals(listOf("Dolby 5.1"), r.audio)
        assertFalse(r.subtitles)
        assertEquals("HEVC 10-bit · DS4K", r.version)
    }

    @Test
    fun hdripHevcWithoutPipe() {
        val r = Release.parse(
            "Monsoon Letters 2026 Malayalam HQ HDRip 1080p - HEVC - x265 - DD+5.1 - 192Kbps and AAC 2.0 - 1.4GB - ESub\n\n🎬 1080p HEVC ⏳ 01:59:39\n🔊 Malayalam",
            "Monsoon Letters 2026 Malayalam HQ HDRip 1080p - HEVC - x265 .mkv",
        )
        assertEquals("Monsoon Letters", r.title)
        assertEquals("HEVC", r.version)
        assertEquals(7179, r.runtimeSec)
        assertEquals(listOf("Dolby 5.1", "AAC"), r.audio)
    }

    @Test
    fun spacedDotsAndAtmos() {
        val r = Release.parse(
            "Tea  For  Three  2025 Malayalam WEB-DL - 1080p HQ - AVC - DD+5 1 ATMOS - 448Kbps & AAC 2 0 - 2 8GB - ESub\n\n🎬 1080p  ⏳ 2:24:13\n🔊 Malayalam",
            "Tea  For  Three  2025 Malayalam WEB-DL - 1080p HQ - AVC - DD+5.mkv",
        )
        assertEquals("Tea For Three", r.title)
        assertEquals(2025, r.year)
        assertEquals("WEB-DL", r.source)
        assertEquals("H.264", r.codec)
        assertEquals(listOf("Dolby Atmos", "AAC"), r.audio)
        assertEquals(2 * 3600 + 24 * 60 + 13, r.runtimeSec)
    }

    @Test
    fun tagsBeforeLanguage() {
        val r = Release.parse(
            "Monsoon Letters 2026 1080p 10bit DS4K NF WEBRip MAL-HIN DDP5.1 x265 HEVC ESub-NOGRP.mkv\n\n🎬 1080p HEVC 10bit ⏳ 01:59:39\n🔊 Malayalam, Hindi",
            "Monsoon Letters 2026 1080p 10bit DS4K NF WEBRip MAL-HI.mkv",
        )
        assertEquals("Monsoon Letters (2026)", r.titleWithYear)
        assertEquals(listOf("Malayalam", "Hindi"), r.languages)
        assertEquals("HEVC 10-bit · DS4K", r.version)
        assertEquals(listOf("Dolby 5.1"), r.audio)
        assertTrue(r.subtitles)
    }

    @Test
    fun dottedFileNameWithoutCaption() {
        val r = Release.parse("", "Tea.For.Three.2025.Malayalam.WEB-DL.1080p.HQ.AVC.DD+5.1.ATMOS.mkv")
        assertEquals("Tea For Three", r.title)
        assertEquals(2025, r.year)
        assertEquals(listOf("Malayalam"), r.languages)
        assertEquals(listOf("Dolby Atmos"), r.audio)
    }

    @Test
    fun sitePrefixAndParenthesisedYear() {
        val r = Release.parse("www.example.boo - Monsoon Letters (2026) Malayalam HQ HDRip - 720p - H.264.mkv", "")
        assertEquals("Monsoon Letters", r.title)
        assertEquals(2026, r.year)
        assertEquals("720p · H.264", r.version)
    }

    @Test
    fun dottedTitleIsNotASite() {
        assertEquals("Find Me In Winter", Release.parse("", "Find.Me.In.Winter.2002.1080p.BluRay.x264.mkv").title)
    }

    @Test
    fun yearsInTheTitle() {
        assertEquals("1999", Release.parse("1999 2023 Malayalam HDRip 1080p x264", "").title)
        val colony = Release.parse("Colony 2049 2017 1080p BluRay x265", "")
        assertEquals("Colony 2049", colony.title)
        assertEquals(2017, colony.year)
        assertEquals("Hindi Lessons", Release.parse("Hindi Lessons 2017 Hindi 1080p WEB-DL", "").title)
    }

    @Test
    fun plainUploadsKeepTheirNames() {
        assertEquals("Studio edit v2", Release.parse("", "Studio_edit_v2.mp4").title)
        assertEquals("IMG 2034", Release.parse("", "IMG_2034.MOV").title) // 2034 is not a release year
        val sentence = Release.parse("Shot this today at the studio", "Wedding.Teaser.2026.1080p.x265.mp4")
        assertEquals("Wedding Teaser", sentence.title) // a chatty caption loses to a release-style file name
        assertEquals("", Release.parse("", "").title)
    }
}

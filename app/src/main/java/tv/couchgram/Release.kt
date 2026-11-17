package tv.couchgram

import androidx.compose.runtime.Immutable
import java.util.Calendar
import java.util.Locale

/**
 * A video's release name, split into what the library shows.
 *
 * Detailed captions often look like this:
 *
 *     Monsoon Letters 2026 MAL-HIN DS4K 1080p NF WEB-Rip E-AC3 5.1 10bit HEVC - NOGRP.mkv
 *
 *     🎬 1080p HEVC 10bit | ⏳ 01:59:39
 *     🔊 Hindi, Malayalam
 *
 * The first line is a detailed file name, as media servers parse them: title, year, then technical tags in any order. The
 * 🎬 ⏳ 🔊 lines, when present, are cleaner and win over the name. Without a caption the file
 * name is parsed the same way, so a plain upload like "Studio edit v2.mp4" keeps its name.
 */
@Immutable
data class Release(
    val title: String,
    val year: Int? = null,
    val languages: List<String> = emptyList(),
    /** "4K", "1080p", "720p"… */
    val resolution: String? = null,
    /** "HEVC", "H.264", "AV1", "VP9". */
    val codec: String? = null,
    val tenBit: Boolean = false,
    /** "WEB-DL", "WEBRip", "HDRip", "BluRay"… */
    val source: String? = null,
    /** The streaming service it came from: "Netflix", "Prime Video"… */
    val service: String? = null,
    /** "DS4K": downscaled from a 4K master, usually the sharpest kind of 1080p. */
    val fromFourK: Boolean = false,
    /** "Dolby Atmos", "Dolby 5.1", "AAC", in the order the name lists them. */
    val audio: List<String> = emptyList(),
    val subtitles: Boolean = false,
    val runtimeSec: Int = 0,
) {
    /** What tells versions of one film apart: "HEVC 10-bit · DS4K", "720p · H.264". 1080p is the norm, so it's left out. */
    val version: String
        get() = listOfNotNull(
            resolution?.takeIf { it != "1080p" },
            codec?.let { if (tenBit) "$it 10-bit" else it } ?: "10-bit".takeIf { tenBit },
            "DS4K".takeIf { fromFourK },
        ).joinToString(" · ")

    /** "Monsoon Letters (2026)". */
    val titleWithYear: String get() = if (year != null && title.isNotEmpty()) "$title ($year)" else title

    /** One line for the player and the channel reports: "Monsoon Letters (2026) · HEVC 10-bit · DS4K". */
    val label: String get() = listOf(titleWithYear, version).filter { it.isNotEmpty() }.joinToString(" · ")

    companion object {
        private const val TITLE_MAX = 120
        private const val FIRST_YEAR = 1920
        private val LAST_YEAR = Calendar.getInstance().get(Calendar.YEAR) + 1

        private val EXTENSION = Regex("""\.(mkv|mp4|m4v|mov|webm|ts|3gp|avi)\s*$""", RegexOption.IGNORE_CASE)

        /**
         * "www.site.boo - ", "site.com - ", "@channel ", "[Group] " in front of the title. A bare
         * domain needs the dash after it, so a dotted name like "Find.Me.In.Winter" stays whole.
         */
        private val PREFIX = Regex(
            """^\s*(?:www\.\S+\s*[-–:|]*|[\w-]+\.(?:com|net|org|in|me|to|boo|cc|tv|xyz|lol|fyi|io|co|ws|ms|li|pm|ph|world|site)\s*[-–:|]+|@\w+\s*[-–:|]*|\[[^\]]*]\s*[-–:|]*)\s*""",
            RegexOption.IGNORE_CASE,
        )
        private val H26X = Regex("""\bh\.?(26[45])\b""", RegexOption.IGNORE_CASE)
        /** Short decimals (5.1, 2.5GB) keep their dot; every other dot and underscore separates words. */
        private val DECIMAL = Regex("""(?<!\d)(\d{1,2})\.(\d{1,2})(?!\d)""")
        private const val KEPT_DOT = '\u2024'
        private val BRACKETS = Regex("""[()\[\]{}]""")
        private val SPACES = Regex("""\s+""")
        private val TRAILING_JUNK = Regex("""[\s\-–:|,.]+$""")
        private val SEPARATORS = setOf("-", "–", "|", "&", "+", "and")

        private val YEAR = Regex("""(19|20)\d{2}""")
        private val RESOLUTION = Regex("""\b(2160p|4k|uhd|1440p|1080p|720p|576p|480p|360p)\b""")
        private val TEN_BIT = Regex("""\b10[ -]?bit\b""")
        private val CODECS = listOf(
            Regex("""\b(x265|h265|hevc)\b""") to "HEVC",
            Regex("""\b(x264|h264|avc)\b""") to "H.264",
            Regex("""\bav1\b""") to "AV1",
            Regex("""\bvp9\b""") to "VP9",
        )
        private val SOURCES = listOf(
            Regex("""\bweb[ -]?dl\b""") to "WEB-DL",
            Regex("""\bweb[ -]?rip\b""") to "WEBRip",
            Regex("""\bhd[ -]?rip\b""") to "HDRip",
            Regex("""\b(blu[ -]?ray|bd[ -]?rip|br[ -]?rip)\b""") to "BluRay",
            Regex("""\bdvd[ -]?rip\b""") to "DVDRip",
            Regex("""\bhdtv\b""") to "HDTV",
            Regex("""\b(hd[ -]?cam|cam[ -]?rip|pre[ -]?dvd|hd[ -]?ts|telesync)\b""") to "Theatre print",
        )
        private val SERVICES = listOf(
            Regex("""\bnf\b""") to "Netflix",
            Regex("""\b(amzn|amazon|prime)\b""") to "Prime Video",
            Regex("""\b(jhs|dsnp|hotstar|jiohotstar)\b""") to "JioHotstar",
            Regex("""\bzee5\b""") to "ZEE5",
            Regex("""\bsonyliv\b""") to "Sony LIV",
            Regex("""\bsun ?nxt\b""") to "Sun NXT",
            Regex("""\b(mmax|manoramamax)\b""") to "ManoramaMAX",
        )
        private val DS4K = Regex("""\bds4k\b""")
        private val ATMOS = Regex("""\batmos\b""")
        private val DOLBY_PLUS = Regex("""(?:\bdd\+|\bddp|\be-?ac-?3)\s*([1-7])(?:[ .]([01]))?""")
        private val DOLBY = Regex("""(?:\bdd(?![+p])|(?<![e-])\bac-?3)\s*([1-7])(?:[ .]([01]))?""")
        private val AAC = Regex("""\baac\b""")
        private val SUBTITLES = Regex("""\b(e?subs?|msubs?)\b""")

        /** Words that only ever appear after the title. Language names don't count: a film can be called "Hindi Lessons". */
        private val STRONG_TAG = Regex(
            """^(\d{3,4}p|4k|uhd|x26[45]|h26[45]|hevc|avc|av1|10bit|ds4k|nf|amzn|web(-?(dl|rip))?|hd-?rip|blu-?ray|bd-?rip|br-?rip|dvd-?rip|hdtv|hd-?cam|pre-?dvd|ddp?\d.*|dd\+.*|e-?ac-?3|aac|atmos|e?subs?)$""",
        )

        private val LANGUAGES = mapOf(
            "malayalam" to "Malayalam", "mal" to "Malayalam",
            "hindi" to "Hindi", "hin" to "Hindi",
            "tamil" to "Tamil", "tam" to "Tamil",
            "telugu" to "Telugu", "tel" to "Telugu",
            "kannada" to "Kannada", "kan" to "Kannada",
            "english" to "English", "eng" to "English",
            "bengali" to "Bengali", "ben" to "Bengali",
            "marathi" to "Marathi", "mar" to "Marathi",
            "punjabi" to "Punjabi",
            "korean" to "Korean", "kor" to "Korean",
            "japanese" to "Japanese", "jpn" to "Japanese",
            "spanish" to "Spanish", "french" to "French",
        )
        private val FULL_LANGUAGE_NAMES = LANGUAGES.values.map { it.lowercase(Locale.ROOT) }.toSet()

        private val CLAPPER = Regex("""🎬([^⏳\n]*)""")
        private val HOURGLASS = Regex("""⏳\s*(?:(\d{1,2}):)?(\d{1,2}):(\d{2})""")
        private val SPEAKER = Regex("""🔊([^\n]*)""")
        private val LIST_SPLIT = Regex("""\s*(?:,|&|/|\|| and )\s*""")

        /** [caption]: the message caption (may be blank); [fileName]: the uploaded file's name (may be blank). */
        fun parse(caption: String, fileName: String): Release {
            val lines = caption.lines().map { it.trim() }
            val nameLine = lines.firstOrNull { line -> line.isNotEmpty() && listOf("🎬", "⏳", "🔊").none { line.startsWith(it) } }
            val fromCaption = nameLine?.let(::parseName)
            val fromFile = fileName.takeIf { it.isNotBlank() }?.let(::parseName)
            // A caption that's just a sentence ("New edit!") loses to a file name that parses as a release.
            val (base, _) = when {
                fromCaption == null -> fromFile ?: (Release("") to false)
                !fromCaption.second && fromFile?.second == true -> fromFile
                else -> fromCaption
            }
            return base.withCaptionLines(caption)
        }

        /** Returns the release and whether anything in [raw] looked like a release name (a year or a tag). */
        private fun parseName(raw: String): Pair<Release, Boolean> {
            var s = raw.trim().replace(EXTENSION, "")
            while (true) {
                val stripped = s.replaceFirst(PREFIX, "")
                if (stripped == s || stripped.isBlank()) break
                s = stripped
            }
            s = s.replace(H26X, "h$1").replace(DECIMAL, "$1$KEPT_DOT$2")
                .replace('.', ' ').replace('_', ' ').replace(KEPT_DOT, '.')
                .replace(BRACKETS, " ")
            val tokens = s.split(SPACES).filter { it.isNotEmpty() && it.lowercase(Locale.ROOT) !in SEPARATORS }
            if (tokens.isEmpty()) return Release("") to false
            val lower = tokens.map { it.lowercase(Locale.ROOT) }

            // Title ends at the year, taking the last year before the first tag: "Colony 2049 2017 1080p".
            val firstStrong = lower.indexOfFirst { STRONG_TAG.matches(it) }.let { if (it < 0) tokens.size else it }
            val yearAt = (firstStrong - 1 downTo 1).firstOrNull { isYear(lower[it]) }
            val titleEnd = yearAt ?: run {
                val firstTag = lower.indexOfFirst { STRONG_TAG.matches(it) || it in FULL_LANGUAGE_NAMES }
                if (firstTag > 0) firstTag else firstStrong
            }
            val title = tokens.take(titleEnd).joinToString(" ").replace(TRAILING_JUNK, "").take(TITLE_MAX)
            val tail = lower.drop(titleEnd).joinToString(" ")

            val dolbyPlus = DOLBY_PLUS.find(tail)
            val dolby = if (dolbyPlus == null) DOLBY.find(tail) else null
            val audio = buildList {
                when {
                    ATMOS.containsMatchIn(tail) -> add("Dolby Atmos")
                    dolbyPlus != null -> add("Dolby ${channels(dolbyPlus)}")
                    dolby != null -> add("Dolby ${channels(dolby)}")
                }
                if (AAC.containsMatchIn(tail)) add("AAC")
            }
            val release = Release(
                title = title,
                year = yearAt?.let { tokens[it].toInt() },
                languages = lower.drop(titleEnd).flatMap { it.split('-', '+', ',') }.mapNotNull { LANGUAGES[it] }.distinct(),
                resolution = RESOLUTION.find(tail)?.value?.let { if (it == "2160p" || it == "4k" || it == "uhd") "4K" else it },
                codec = CODECS.firstOrNull { it.first.containsMatchIn(tail) }?.second,
                tenBit = TEN_BIT.containsMatchIn(tail),
                source = SOURCES.firstOrNull { it.first.containsMatchIn(tail) }?.second,
                service = SERVICES.firstOrNull { it.first.containsMatchIn(tail) }?.second,
                fromFourK = DS4K.containsMatchIn(tail),
                audio = audio,
                subtitles = SUBTITLES.containsMatchIn(tail),
            )
            return release to (yearAt != null || firstStrong < tokens.size)
        }

        private fun isYear(token: String) = YEAR.matches(token) && token.toInt() in FIRST_YEAR..LAST_YEAR

        private fun channels(match: MatchResult) = "${match.groupValues[1]}.${match.groupValues[2].ifEmpty { "1" }}"

        /** The bot's 🎬 ⏳ 🔊 lines win over what the name said. */
        private fun Release.withCaptionLines(caption: String): Release {
            var r = this
            CLAPPER.find(caption)?.groupValues?.get(1)?.lowercase(Locale.ROOT)?.let { line ->
                RESOLUTION.find(line)?.value?.let { r = r.copy(resolution = if (it == "2160p" || it == "4k") "4K" else it) }
                CODECS.firstOrNull { it.first.containsMatchIn(line) }?.let { r = r.copy(codec = it.second) }
                if (TEN_BIT.containsMatchIn(line)) r = r.copy(tenBit = true)
            }
            HOURGLASS.find(caption)?.let { m ->
                val (h, min, sec) = m.destructured
                r = r.copy(runtimeSec = (h.toIntOrNull() ?: 0) * 3600 + min.toInt() * 60 + sec.toInt())
            }
            SPEAKER.find(caption)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { line ->
                val spoken = line.split(LIST_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
                    .map { LANGUAGES[it.lowercase(Locale.ROOT)] ?: it.replaceFirstChar { c -> c.titlecase(Locale.ROOT) } }
                    .distinct()
                if (spoken.isNotEmpty()) r = r.copy(languages = spoken)
            }
            return r
        }
    }
}

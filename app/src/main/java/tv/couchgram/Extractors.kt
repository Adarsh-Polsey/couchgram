package tv.couchgram

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import tv.couchgram.mkv.MatroskaExtractor

/**
 * Media3's extractors, with the patched MKV one ([tv.couchgram.mkv.MatroskaExtractor]) in place of
 * Media3's: some MKVs keep their index behind a second SeekHead at the end of the file, which
 * Media3's treats as "no index", so every seek restarted the film at 00:00.
 */
@OptIn(UnstableApi::class)
class PatchedExtractorsFactory : ExtractorsFactory {
    private val defaults = DefaultExtractorsFactory()
    private var subtitleParserFactory: SubtitleParser.Factory = DefaultSubtitleParserFactory()
    private var transcodeText = true
    private var parseHagc = true

    override fun createExtractors(): Array<Extractor> = patched(defaults.createExtractors())

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        patched(defaults.createExtractors(uri, responseHeaders))

    override fun experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled: Boolean): ExtractorsFactory = apply {
        defaults.experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled)
        transcodeText = textTrackTranscodingEnabled
    }

    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory = apply {
        defaults.setSubtitleParserFactory(subtitleParserFactory)
        this.subtitleParserFactory = subtitleParserFactory
    }

    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies: Int): ExtractorsFactory = apply {
        defaults.experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies)
    }

    override fun setParseHagcMetadata(parseHagcMetadata: Boolean): ExtractorsFactory = apply {
        defaults.setParseHagcMetadata(parseHagcMetadata)
        parseHagc = parseHagcMetadata
    }

    /** Same flags as Media3's factory gives its own MKV extractor. */
    private fun patched(extractors: Array<Extractor>): Array<Extractor> = Array(extractors.size) { i ->
        val extractor = extractors[i]
        if (extractor.underlyingImplementation is androidx.media3.extractor.mkv.MatroskaExtractor) {
            MatroskaExtractor(
                subtitleParserFactory,
                (if (transcodeText) 0 else MatroskaExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA) or
                    (if (parseHagc) 0 else MatroskaExtractor.FLAG_DISABLE_HAGC_METADATA),
            )
        } else {
            extractor
        }
    }
}

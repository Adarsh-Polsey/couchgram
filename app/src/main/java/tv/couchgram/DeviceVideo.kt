package tv.couchgram

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

/**
 * The largest video this device decodes in hardware (H.264 or HEVC), so size warnings fit the TV:
 * a 1080p Fire TV Stick warns about 4K files, a 4K one doesn't. Probes the standard sizes, since
 * a decoder's own limits are often aligned oddly (1920x1088).
 */
object DeviceVideo {
    private data class Tier(val label: String, val width: Int, val height: Int)

    private val TIERS = listOf(
        Tier("4K", 3840, 2160),
        Tier("1440p", 2560, 1440),
        Tier("1080p", 1920, 1088),
        Tier("720p", 1280, 720),
    )
    private val FALLBACK = TIERS[2]

    private val max: Tier by lazy {
        val decoders = runCatching { hardwareDecoders() }.getOrDefault(emptyList())
        TIERS.firstOrNull { tier -> decoders.any { it.isSizeSupported(tier.width, tier.height) } }
            ?: FALLBACK
    }

    /** "4K", "1080p"…: what the TV plays, for the UI. */
    val label: String get() = max.label

    /** The stream itself is bigger than the decoders take (the player checks the real track). */
    fun tooBig(width: Int, height: Int) = width > max.width || height > max.height

    /**
     * Clearly too big, from Telegram's stored size. It can be off by a little (a 1920x804 film was
     * listed as 1700x1120), so only a third past the limit counts; the player checks the real stream.
     */
    fun clearlyTooBig(width: Int, height: Int) = width > max.width * 4 / 3 || height > max.height * 4 / 3

    private fun hardwareDecoders(): List<MediaCodecInfo.VideoCapabilities> =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && it.isHardware() }
            .flatMap { info ->
                info.supportedTypes
                    .filter { it.equals("video/avc", ignoreCase = true) || it.equals("video/hevc", ignoreCase = true) }
                    .mapNotNull { runCatching { info.getCapabilitiesForType(it).videoCapabilities }.getOrNull() }
            }

    private fun MediaCodecInfo.isHardware(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isHardwareAccelerated
        else !name.startsWith("OMX.google.", ignoreCase = true) && !name.startsWith("c2.android.", ignoreCase = true)
}

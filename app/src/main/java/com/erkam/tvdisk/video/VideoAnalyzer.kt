package com.erkam.tvdisk.video

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns

enum class VideoAction { COPY, REMUX, CONVERT }

data class VideoInfo(
    val uri: Uri,
    val name: String,
    val size: Long,
    val ext: String,
    val videoMime: String?,
    val width: Int,
    val height: Int,
    val audioMime: String?,
    val durationUs: Long,
    val action: VideoAction,
    val reason: String,
) {
    val description: String
        get() {
            val parts = mutableListOf<String>()
            parts += ext.uppercase().ifEmpty { "?" }
            if (videoMime != null) parts += codecName(videoMime)
            if (width > 0) parts += "${width}×${height}"
            if (audioMime != null) parts += codecName(audioMime)
            parts += formatSize(size)
            return parts.joinToString(" · ")
        }
}

object VideoAnalyzer {
    const val FAT32_MAX = 4L * 1024 * 1024 * 1024 - 1

    private val okAudio = setOf(MediaFormat.MIMETYPE_AUDIO_AAC, MediaFormat.MIMETYPE_AUDIO_MPEG)
    private val okContainers = setOf("mp4", "m4v", "avi")

    fun analyze(ctx: Context, uri: Uri): VideoInfo {
        var name = "video"
        var size = -1L
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        val ext = name.substringAfterLast('.', "").lowercase()

        var vMime: String? = null
        var aMime: String? = null
        var w = 0; var h = 0
        var dur = 0L
        var readable = true
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("video/") && vMime == null) {
                    vMime = m
                    if (f.containsKey(MediaFormat.KEY_WIDTH)) w = f.getInteger(MediaFormat.KEY_WIDTH)
                    if (f.containsKey(MediaFormat.KEY_HEIGHT)) h = f.getInteger(MediaFormat.KEY_HEIGHT)
                    if (f.containsKey(MediaFormat.KEY_DURATION)) dur = f.getLong(MediaFormat.KEY_DURATION)
                } else if (m.startsWith("audio/") && aMime == null) {
                    aMime = m
                    if (dur == 0L && f.containsKey(MediaFormat.KEY_DURATION)) dur = f.getLong(MediaFormat.KEY_DURATION)
                }
            }
        } catch (e: Exception) {
            readable = false
        } finally {
            ex.release()
        }

        val big = size > FAT32_MAX
        if (!readable || vMime == null) {
            // Telefonun okuyamadığı biçim (ör. AVI). Eski TV'ler AVI'yi çoğunlukla açar.
            return if (ext == "avi" && !big)
                VideoInfo(uri, name, size, ext, null, 0, 0, null, 0, VideoAction.COPY,
                    "AVI dosyası olduğu gibi kopyalanır (eski TV'lerin çoğu açar)")
            else
                VideoInfo(uri, name, size, ext, vMime, w, h, aMime, dur, VideoAction.CONVERT,
                    "Biçim tanınmadı, TV'ye uygun hale dönüştürmeyi deneyeceğim")
        }

        val maxD = maxOf(w, h); val minD = minOf(w, h)
        val sizeOk = maxD <= 1920 && minD <= 1088
        val isH264 = vMime == MediaFormat.MIMETYPE_VIDEO_AVC
        val isMpeg4 = vMime == MediaFormat.MIMETYPE_VIDEO_MPEG4
        val videoOk = (isH264 || isMpeg4) && sizeOk
        val audioOk = aMime == null || aMime in okAudio
        val contOk = ext in okContainers

        val (action, reason) = when {
            big -> VideoAction.CONVERT to "4 GB'tan büyük; FAT32'ye sığması için 720p'ye küçültülecek"
            videoOk && audioOk && contOk -> VideoAction.COPY to "TV'ye uygun, olduğu gibi kopyalanır"
            isH264 && sizeOk -> VideoAction.REMUX to
                (if (!contOk) "${ext.uppercase()} kabı MP4'e çevrilecek (hızlı)" else "Ses TV'ye uygun hale getirilecek (hızlı)")
            !sizeOk -> VideoAction.CONVERT to "Çözünürlük yüksek; 720p H.264'e dönüştürülecek (uzun sürer)"
            else -> VideoAction.CONVERT to "${codecName(vMime)} eski TV'de açılmaz; H.264'e dönüştürülecek (uzun sürer)"
        }
        return VideoInfo(uri, name, size, ext, vMime, w, h, aMime, dur, action, reason)
    }
}

fun codecName(mime: String): String = when (mime) {
    MediaFormat.MIMETYPE_VIDEO_AVC -> "H.264"
    MediaFormat.MIMETYPE_VIDEO_HEVC -> "H.265"
    MediaFormat.MIMETYPE_VIDEO_MPEG4 -> "MPEG-4"
    MediaFormat.MIMETYPE_VIDEO_VP9 -> "VP9"
    MediaFormat.MIMETYPE_VIDEO_VP8 -> "VP8"
    "video/av01" -> "AV1"
    MediaFormat.MIMETYPE_AUDIO_AAC -> "AAC"
    MediaFormat.MIMETYPE_AUDIO_MPEG -> "MP3"
    MediaFormat.MIMETYPE_AUDIO_OPUS -> "Opus"
    MediaFormat.MIMETYPE_AUDIO_VORBIS -> "Vorbis"
    MediaFormat.MIMETYPE_AUDIO_AC3 -> "AC3"
    "audio/eac3" -> "E-AC3"
    else -> mime.substringAfter('/').uppercase()
}

fun formatSize(b: Long): String = when {
    b < 0 -> "?"
    b >= 1_000_000_000 -> String.format(java.util.Locale("tr"), "%.2f GB", b / 1e9)
    b >= 1_000_000 -> String.format(java.util.Locale("tr"), "%.0f MB", b / 1e6)
    else -> String.format(java.util.Locale("tr"), "%.0f KB", b / 1e3)
}

/** TV'lerin sorunsuz göstermesi için Türkçe karakterleri sadeleştirir. */
fun tvSafeName(name: String): String {
    val map = mapOf('ç' to 'c', 'Ç' to 'C', 'ğ' to 'g', 'Ğ' to 'G', 'ı' to 'i', 'İ' to 'I',
        'ö' to 'o', 'Ö' to 'O', 'ş' to 's', 'Ş' to 'S', 'ü' to 'u', 'Ü' to 'U')
    val sb = StringBuilder()
    for (ch in name) {
        val c = map[ch] ?: ch
        sb.append(if (c.code in 32..126 && c !in "\\/:*?\"<>|") c else '_')
    }
    return sb.toString().trim().ifEmpty { "video" }.take(120)
}

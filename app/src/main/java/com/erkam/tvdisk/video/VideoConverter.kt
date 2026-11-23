package com.erkam.tvdisk.video

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File

/** Videoyu eski TV'lerin açtığı MP4 (H.264 + AAC) biçimine getirir. Ana iş parçacığından çağrılmalı. */
@OptIn(UnstableApi::class)
class VideoConverter(private val ctx: Context) {

    private var transformer: Transformer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var poller: Runnable? = null

    /**
     * maxShortSide: çıktının kısa kenarı en fazla bu olur (zayıf telefon 720, güçlü telefon 1080).
     */
    fun start(
        info: VideoInfo,
        out: File,
        maxShortSide: Int,
        onProgress: (Int) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit,
    ) {
        out.delete()
        // Ekranda görünen boyut (dik videolarda en/boy yer değiştirir)
        val swap = info.rotation == 90 || info.rotation == 270
        val dispW = if (swap) info.height else info.width
        val dispH = if (swap) info.width else info.height
        val shortSide = minOf(dispW, dispH)
        val big = info.size > VideoAnalyzer.FAT32_MAX

        var targetShort = 0
        if (info.action == VideoAction.CONVERT) {
            targetShort = when {
                shortSide == 0 -> 0
                shortSide > maxShortSide -> maxShortSide
                big -> shortSide - 8 // 4 GB'ı aşan dosyayı yeniden sıkıştırmaya zorla
                else -> 0
            }
        }
        val outShort = if (targetShort > 0) targetShort else if (shortSide > 0) shortSide else maxShortSide

        // Kalite: 1080p ≈ 6 Mbit/sn, 720p ≈ 3 Mbit/sn; uzun filmler 4 GB sınırına sığacak şekilde
        var bitrate = when {
            outShort > 720 -> 6_000_000
            outShort > 480 -> 3_000_000
            else -> 1_800_000
        }
        val durSec = info.durationUs / 1_000_000L
        if (durSec > 0) {
            val fit = (3_900_000_000L * 8 / durSec - 256_000).toInt()
            bitrate = minOf(bitrate, fit).coerceAtLeast(800_000)
        }

        val encoder = DefaultEncoderFactory.Builder(ctx)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder().setBitrate(bitrate).build()
            )
            .setEnableFallback(true)
            .build()

        val t = Transformer.Builder(ctx)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoder)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    stopPolling()
                    transformer = null
                    onDone()
                }

                override fun onError(composition: Composition, exportResult: ExportResult,
                                     exportException: ExportException) {
                    stopPolling()
                    transformer = null
                    out.delete()
                    onError("Dönüştürülemedi (${exportException.errorCodeName})")
                }
            })
            .build()
        transformer = t

        val effects = if (targetShort > 0 && dispW > 0 && dispH > 0) {
            val scale = targetShort.toDouble() / shortSide
            val w = (Math.round(dispW * scale / 2) * 2).toInt()
            val h = (Math.round(dispH * scale / 2) * 2).toInt()
            Effects(listOf(), listOf(Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT)))
        } else if (info.action == VideoAction.CONVERT && shortSide == 0) {
            Effects(listOf(), listOf(Presentation.createForHeight(maxShortSide)))
        } else Effects.EMPTY

        val item = EditedMediaItem.Builder(MediaItem.fromUri(info.uri))
            .setEffects(effects)
            .build()
        t.start(item, out.absolutePath)

        val holder = ProgressHolder()
        val r = object : Runnable {
            override fun run() {
                val tr = transformer ?: return
                if (tr.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                handler.postDelayed(this, 700)
            }
        }
        poller = r
        handler.postDelayed(r, 700)
    }

    fun cancel() {
        stopPolling()
        transformer?.cancel()
        transformer = null
    }

    private fun stopPolling() {
        poller?.let { handler.removeCallbacks(it) }
        poller = null
    }
}

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

    fun start(
        info: VideoInfo,
        out: File,
        onProgress: (Int) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit,
    ) {
        out.delete()
        val encoder = DefaultEncoderFactory.Builder(ctx)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder().setBitrate(2_500_000).build()
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

        val effects = if (info.action == VideoAction.CONVERT) {
            val h = minOf(info.width, info.height)
            // Zayıf telefonda hızlı olsun diye 720p; zaten küçükse ve 4 GB'tan büyükse 480p'ye zorla
            val target = when {
                h == 0 || h > 720 -> 720
                info.size > VideoAnalyzer.FAT32_MAX -> 480
                else -> 0
            }
            if (target > 0) Effects(listOf(), listOf(Presentation.createForHeight(target))) else Effects.EMPTY
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

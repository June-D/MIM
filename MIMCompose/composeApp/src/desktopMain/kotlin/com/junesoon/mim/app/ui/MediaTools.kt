package com.junesoon.mim.app.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.FileDialog
import java.awt.Frame
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Paths
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import org.jetbrains.skia.Image as SkiaImage

/** 桌面端媒体工具：选图、解码、录音与播放。 */
object MediaTools {

    /** 图片上限与服务端保持一致（8MB）。 */
    const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
    const val MAX_VOICE_BYTES = 8 * 1024 * 1024

    private var playing: Clip? = null

    /** 选一张图片；返回字节、建议文件名与 MIME。取消时返回 null。 */
    fun pickImage(): PickedImage? {
        val dialog = FileDialog(null as Frame?, "选择图片", FileDialog.LOAD).apply {
            setFilenameFilter { _, name ->
                val lower = name.lowercase()
                lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                    lower.endsWith(".webp") || lower.endsWith(".gif")
            }
            isVisible = true
        }
        val file = dialog.file ?: return null
        val path = Paths.get(dialog.directory, file)
        val bytes = runCatching { Files.readAllBytes(path) }.getOrNull() ?: return null
        if (bytes.size > MAX_IMAGE_BYTES) {
            throw IllegalStateException("图片超过 8MB")
        }
        val mime = when (path.fileName.toString().substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
        return PickedImage(bytes, file, mime)
    }

    fun decodeImage(bytes: ByteArray): ImageBitmap? = runCatching {
        SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull()

    fun playVoice(bytes: ByteArray) {
        stopVoice()
        runCatching {
            val stream = AudioSystem.getAudioInputStream(ByteArrayInputStream(bytes))
            val clip = AudioSystem.getClip()
            clip.open(stream)
            clip.start()
            playing = clip
        }
    }

    fun stopVoice() {
        runCatching { playing?.stop() }
        runCatching { playing?.close() }
        playing = null
    }

    data class PickedImage(val bytes: ByteArray, val name: String, val mime: String)
}

/**
 * 录音：16kHz / 16bit / 单声道，停止时写成 WAV。
 *
 * 没有麦克风或系统拒绝权限时 start() 返回 false，界面据此提示，
 * 不假装录到了音频。
 */
class VoiceRecorder {

    private val format = AudioFormat(16_000f, 16, 1, true, false)
    private var line: TargetDataLine? = null
    private var worker: Thread? = null
    private val pcm = ByteArrayOutputStream()
    private var startedAt = 0L

    fun start(): Boolean {
        if (line != null) {
            return true
        }
        val info = DataLine.Info(TargetDataLine::class.java, format)
        if (!AudioSystem.isLineSupported(info)) {
            return false
        }
        val opened = runCatching {
            (AudioSystem.getLine(info) as TargetDataLine).apply {
                open(format)
                start()
            }
        }.getOrNull() ?: return false
        pcm.reset()
        startedAt = System.nanoTime()
        line = opened
        worker = Thread {
            val buffer = ByteArray(4096)
            while (opened.isOpen) {
                val read = opened.read(buffer, 0, buffer.size)
                if (read > 0) {
                    pcm.write(buffer, 0, read)
                }
            }
        }.apply { isDaemon = true; start() }
        return true
    }

    /** 停止并返回 WAV 与毫秒时长；没有有效音频时返回 null。 */
    fun stop(): RecordedVoice? {
        val current = line ?: return null
        runCatching { current.stop() }
        runCatching { current.close() }
        line = null
        worker?.join(800)
        worker = null
        val durationMs = (System.nanoTime() - startedAt) / 1_000_000
        val raw = pcm.toByteArray()
        pcm.reset()
        if (raw.size < 800) {
            return null
        }
        val wav = ByteArrayOutputStream()
        runCatching {
            AudioSystem.write(
                AudioInputStream(ByteArrayInputStream(raw), format, raw.size.toLong()),
                AudioFileFormat.Type.WAVE,
                wav,
            )
        }.getOrNull() ?: return null
        return RecordedVoice(wav.toByteArray(), durationMs)
    }

    data class RecordedVoice(val wav: ByteArray, val durationMs: Long)
}

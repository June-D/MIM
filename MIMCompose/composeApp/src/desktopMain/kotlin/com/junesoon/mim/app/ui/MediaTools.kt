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

    /**
     * 单个附件上限。
     *
     * 与服务端 `mim.media.max-bytes`（18MB 密文）对齐：8MB 明文加密后约 15MB，
     * 留出余量；超限时明确报错，不做静默截断。
     */
    const val MAX_FILE_BYTES = 8 * 1024 * 1024

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

    /** 选任意文件；返回字节、文件名与 MIME。取消时返回 null。 */
    fun pickFile(): PickedFile? {
        val dialog = FileDialog(null as Frame?, "选择要发送的文件", FileDialog.LOAD).apply {
            isVisible = true
        }
        val file = dialog.file ?: return null
        val path = Paths.get(dialog.directory, file)
        val size = runCatching { Files.size(path) }.getOrNull() ?: return null
        if (size > MAX_FILE_BYTES) {
            throw IllegalStateException("文件超过 ${MAX_FILE_BYTES / 1024 / 1024}MB")
        }
        val bytes = runCatching { Files.readAllBytes(path) }.getOrNull() ?: return null
        return PickedFile(bytes, file, mimeOf(file))
    }

    /**
     * 另存为：默认文件名取消息里带的名字。
     *
     * 只保留末段文件名，避免对端发来的名字里带路径分隔符把文件写到别处。
     */
    fun saveFile(suggestedName: String, bytes: ByteArray): String? {
        val safe = suggestedName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .ifBlank { "mim-file" }
        val dialog = FileDialog(null as Frame?, "保存文件", FileDialog.SAVE).apply {
            file = safe
            isVisible = true
        }
        val name = dialog.file ?: return null
        val target = Paths.get(dialog.directory, name)
        Files.write(target, bytes)
        return target.toString()
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

    data class PickedFile(val bytes: ByteArray, val name: String, val mime: String)

    /** 只做展示用的常见类型映射；未知扩展名一律 octet-stream，不瞎猜。 */
    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "pdf" -> "application/pdf"
            "txt", "log", "md" -> "text/plain"
            "csv" -> "text/csv"
            "json" -> "application/json"
            "zip" -> "application/zip"
            "7z" -> "application/x-7z-compressed"
            "gz", "tgz" -> "application/gzip"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "apk" -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }

    /** 字节数转人类可读，列表与卡片共用一套写法。 */
    fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0)
        else -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
    }
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

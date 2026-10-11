package dev.nasexplorer

import android.webkit.MimeTypeMap
import java.text.DateFormat
import java.util.Date

enum class Kind { Video, Audio, Image, Other }

private val videoExt = setOf("mp4", "mkv", "avi", "mov", "wmv", "webm", "m4v", "ts", "3gp", "flv", "mpg", "mpeg")
private val audioExt = setOf("mp3", "flac", "m4a", "aac", "wav", "ogg", "opus", "wma")
private val imageExt = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")

fun kindOf(name: String): Kind {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        in videoExt -> Kind.Video
        in audioExt -> Kind.Audio
        in imageExt -> Kind.Image
        else -> Kind.Other
    }
}

fun iconFor(e: NasEntry): String = if (e.isDir) "📁" else when (kindOf(e.name)) {
    Kind.Video -> "🎬"
    Kind.Audio -> "🎵"
    Kind.Image -> "🖼️"
    Kind.Other -> "📄"
}

fun mimeOf(name: String): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
        ?: "application/octet-stream"

fun isHidden(name: String) =
    name.startsWith(".") || name.startsWith("@") || name.startsWith("#") || name.startsWith("$")

fun humanSize(b: Long): String {
    if (b < 1024) return "$b B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = b.toDouble()
    var i = -1
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return String.format("%.1f %s", v, units[i])
}

fun humanDate(ms: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

fun Throwable.friendly(): String =
    generateSequence(this) { it.cause }.mapNotNull { it.message }.firstOrNull() ?: javaClass.simpleName

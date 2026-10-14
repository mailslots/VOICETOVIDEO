package com.example.model

import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AudioItem(
    val id: String,
    val uri: Uri,
    val fileName: String,
    val displayName: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val createdAt: Long, // milliseconds
    val mimeType: String,
    val isSelected: Boolean = true,
    val status: ConversionStatus = ConversionStatus.Idle
) {
    val formattedDuration: String
        get() {
            if (durationMs <= 0) return "--:--"
            val totalSeconds = durationMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }

    val formattedSize: String
        get() {
            val kb = sizeBytes / 1024.0
            val mb = kb / 1024.0
            return when {
                mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
                kb >= 1.0 -> String.format(Locale.US, "%.0f KB", kb)
                else -> "$sizeBytes B"
            }
        }

    val formattedCreatedAt: String
        get() {
            if (createdAt <= 0) return "Unknown date"
            val sdf = SimpleDateFormat("MMM d, yyyy • hh:mm a", Locale.getDefault())
            return sdf.format(Date(createdAt))
        }

    val formattedCreatedAtShort: String
        get() {
            if (createdAt <= 0) return "Unknown"
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            return sdf.format(Date(createdAt))
        }
}

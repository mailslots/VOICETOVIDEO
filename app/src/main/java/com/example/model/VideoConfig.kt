package com.example.model

enum class VideoAspectRatio(val title: String, val width: Int, val height: Int) {
    PORTRAIT_9_16("Portrait (720x1280)", 720, 1280),
    LANDSCAPE_16_9("Landscape (1280x720)", 1280, 720),
    SQUARE_1_1("Square (720x720)", 720, 720)
}

data class VideoConfig(
    val aspectRatio: VideoAspectRatio = VideoAspectRatio.PORTRAIT_9_16,
    val deleteSourceAudio: Boolean = true, // User requested: "then delete the audio that convert already"
    val fps: Int = 10,
    val videoBitrate: Int = 1_500_000
)

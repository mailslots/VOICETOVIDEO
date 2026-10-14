package com.example.model

data class BatchSummary(
    val totalFiles: Int,
    val successCount: Int,
    val failedCount: Int,
    val deletedAudioCount: Int,
    val totalDurationMs: Long
)

data class BatchConversionState(
    val isConverting: Boolean = false,
    val totalFiles: Int = 0,
    val completedCount: Int = 0,
    val currentFileIndex: Int = 0, // 1-based (e.g. 1 of 5)
    val currentFileName: String = "",
    val currentFileProgress: Float = 0f, // 0.0 to 1.0 (0% to 100%)
    val overallProgress: Float = 0f, // 0.0 to 1.0 (0% to 100%)
    val stageMessage: String = "",
    val isCancelled: Boolean = false,
    val summary: BatchSummary? = null
)

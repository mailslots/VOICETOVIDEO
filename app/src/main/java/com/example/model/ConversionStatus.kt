package com.example.model

import java.io.File

sealed interface ConversionStatus {
    object Idle : ConversionStatus
    object Pending : ConversionStatus
    data class InProgress(
        val progress: Float, // 0.0 to 1.0
        val stageMessage: String
    ) : ConversionStatus
    data class Completed(
        val videoFile: File,
        val outputSizeBytes: Long,
        val sourceDeleted: Boolean,
        val conversionTimeMs: Long
    ) : ConversionStatus
    data class Failed(
        val errorMessage: String
    ) : ConversionStatus
}

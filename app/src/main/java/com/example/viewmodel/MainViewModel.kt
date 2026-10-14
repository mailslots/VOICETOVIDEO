package com.example.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.converter.AudioToVideoConverter
import com.example.data.AudioRepository
import com.example.model.AudioItem
import com.example.model.BatchConversionState
import com.example.model.BatchSummary
import com.example.model.ConversionStatus
import com.example.model.VideoConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.CancellationException

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AudioRepository(application)
    private val converter = AudioToVideoConverter(application)

    private val _audioList = MutableStateFlow<List<AudioItem>>(emptyList())
    val audioList: StateFlow<List<AudioItem>> = _audioList.asStateFlow()

    private val _batchState = MutableStateFlow(BatchConversionState())
    val batchState: StateFlow<BatchConversionState> = _batchState.asStateFlow()

    private val _videoConfig = MutableStateFlow(VideoConfig())
    val videoConfig: StateFlow<VideoConfig> = _videoConfig.asStateFlow()

    private val _previewVideoFile = MutableStateFlow<File?>(null)
    val previewVideoFile: StateFlow<File?> = _previewVideoFile.asStateFlow()

    val selectedCount: StateFlow<Int> = _audioList.map { list ->
        list.count { it.isSelected }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private var conversionJob: Job? = null
    @Volatile
    private var isCancelledFlag = false

    init {
        // Pre-populate with realistic demo recordings so user can immediately test
        loadDemoRecordings()
    }

    fun addUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val newItems = repository.resolveUris(uris)
            _audioList.update { current ->
                val existingIds = current.map { it.id }.toSet()
                current + newItems.filter { it.id !in existingIds }
            }
        }
    }

    fun scanFolder(treeUri: Uri) {
        viewModelScope.launch {
            val folderItems = repository.scanFolder(treeUri)
            _audioList.update { current ->
                val existingIds = current.map { it.id }.toSet()
                current + folderItems.filter { it.id !in existingIds }
            }
        }
    }

    fun scanMediaStore() {
        viewModelScope.launch {
            val mediaItems = repository.scanMediaStoreRecordings()
            _audioList.update { current ->
                val existingIds = current.map { it.id }.toSet()
                current + mediaItems.filter { it.id !in existingIds }
            }
        }
    }

    fun loadDemoRecordings() {
        viewModelScope.launch {
            val demos = repository.generateDemoRecordings()
            _audioList.update { current ->
                val existingNames = current.map { it.fileName }.toSet()
                current + demos.filter { it.fileName !in existingNames }
            }
        }
    }

    fun toggleItemSelection(id: String) {
        if (_batchState.value.isConverting) return
        _audioList.update { list ->
            list.map { item ->
                if (item.id == id) item.copy(isSelected = !item.isSelected) else item
            }
        }
    }

    fun selectAll() {
        if (_batchState.value.isConverting) return
        _audioList.update { list ->
            list.map { it.copy(isSelected = true) }
        }
    }

    fun deselectAll() {
        if (_batchState.value.isConverting) return
        _audioList.update { list ->
            list.map { it.copy(isSelected = false) }
        }
    }

    fun removeItem(id: String) {
        if (_batchState.value.isConverting) return
        _audioList.update { list -> list.filter { it.id != id } }
    }

    fun clearAll() {
        if (_batchState.value.isConverting) return
        _audioList.value = emptyList()
        _batchState.value = BatchConversionState()
    }

    fun updateConfig(config: VideoConfig) {
        _videoConfig.value = config
    }

    fun setDeleteSourceAudio(delete: Boolean) {
        _videoConfig.update { it.copy(deleteSourceAudio = delete) }
    }

    fun openVideoPreview(file: File) {
        _previewVideoFile.value = file
    }

    fun closeVideoPreview() {
        _previewVideoFile.value = null
    }

    fun dismissSummary() {
        _batchState.update { it.copy(summary = null) }
    }

    fun cancelConversion() {
        isCancelledFlag = true
        _batchState.update { it.copy(isCancelled = true, stageMessage = "Cancelling conversion...") }
        conversionJob?.cancel()
    }

    fun startConversion() {
        val selectedItems = _audioList.value.filter { it.isSelected }
        if (selectedItems.isEmpty() || _batchState.value.isConverting) return

        isCancelledFlag = false
        val totalCount = selectedItems.size
        val outputDir = repository.getOutputDirectory()
        val config = _videoConfig.value

        conversionJob = viewModelScope.launch {
            _batchState.value = BatchConversionState(
                isConverting = true,
                totalFiles = totalCount,
                completedCount = 0,
                currentFileIndex = 1,
                currentFileName = selectedItems[0].fileName,
                currentFileProgress = 0f,
                overallProgress = 0f,
                stageMessage = "Starting batch conversion...",
                isCancelled = false,
                summary = null
            )

            // Mark all selected items as Pending
            _audioList.update { list ->
                list.map { item ->
                    if (item.isSelected) item.copy(status = ConversionStatus.Pending) else item
                }
            }

            var successCount = 0
            var failedCount = 0
            var deletedAudioCount = 0
            val batchStartTime = System.currentTimeMillis()

            for ((index, item) in selectedItems.withIndex()) {
                if (isCancelledFlag) break

                val fileIndex1Based = index + 1
                val baseOverallProgress = index.toFloat() / totalCount

                _batchState.update {
                    it.copy(
                        currentFileIndex = fileIndex1Based,
                        currentFileName = item.fileName,
                        currentFileProgress = 0f,
                        overallProgress = baseOverallProgress,
                        stageMessage = "Processing file $fileIndex1Based of $totalCount: ${item.displayName}"
                    )
                }

                // Update item status to InProgress
                _audioList.update { list ->
                    list.map {
                        if (it.id == item.id) it.copy(status = ConversionStatus.InProgress(0f, "Initializing...")) else it
                    }
                }

                val itemStartTime = System.currentTimeMillis()
                val safeBaseName = item.displayName.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
                val outputFile = File(outputDir, "${safeBaseName}_${System.currentTimeMillis() % 10000}.mp4")

                try {
                    val resultFile = converter.convert(
                        audioItem = item,
                        outputFile = outputFile,
                        config = config,
                        onProgress = { fileProgress, stageText ->
                            val currentFileOverall = baseOverallProgress + (fileProgress / totalCount)
                            _batchState.update { state ->
                                state.copy(
                                    currentFileProgress = fileProgress,
                                    overallProgress = currentFileOverall.coerceIn(0f, 1f),
                                    stageMessage = stageText
                                )
                            }
                            _audioList.update { list ->
                                list.map {
                                    if (it.id == item.id) it.copy(status = ConversionStatus.InProgress(fileProgress, stageText)) else it
                                }
                            }
                        },
                        isCancelled = { isCancelledFlag }
                    )

                    var sourceWasDeleted = false
                    if (config.deleteSourceAudio) {
                        sourceWasDeleted = repository.deleteSourceAudio(item)
                        if (sourceWasDeleted) {
                            deletedAudioCount++
                        }
                    }

                    val convTime = System.currentTimeMillis() - itemStartTime
                    successCount++

                    _audioList.update { list ->
                        list.map {
                            if (it.id == item.id) {
                                it.copy(
                                    status = ConversionStatus.Completed(
                                        videoFile = resultFile,
                                        outputSizeBytes = resultFile.length(),
                                        sourceDeleted = sourceWasDeleted,
                                        conversionTimeMs = convTime
                                    )
                                )
                            } else it
                        }
                    }

                    _batchState.update { state ->
                        state.copy(
                            completedCount = successCount + failedCount,
                            overallProgress = (index + 1).toFloat() / totalCount
                        )
                    }

                } catch (e: CancellationException) {
                    Log.d("MainViewModel", "Conversion cancelled for ${item.fileName}")
                    _audioList.update { list ->
                        list.map {
                            if (it.id == item.id) it.copy(status = ConversionStatus.Idle) else it
                        }
                    }
                    break
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Error converting ${item.fileName}", e)
                    failedCount++
                    val errorMsg = e.message ?: "Conversion failed"
                    _audioList.update { list ->
                        list.map {
                            if (it.id == item.id) it.copy(status = ConversionStatus.Failed(errorMsg)) else it
                        }
                    }
                    _batchState.update { state ->
                        state.copy(
                            completedCount = successCount + failedCount,
                            overallProgress = (index + 1).toFloat() / totalCount
                        )
                    }
                }
            }

            val totalDuration = System.currentTimeMillis() - batchStartTime
            val summary = BatchSummary(
                totalFiles = totalCount,
                successCount = successCount,
                failedCount = failedCount,
                deletedAudioCount = deletedAudioCount,
                totalDurationMs = totalDuration
            )

            _batchState.value = BatchConversionState(
                isConverting = false,
                totalFiles = totalCount,
                completedCount = successCount + failedCount,
                currentFileIndex = totalCount,
                currentFileName = "",
                currentFileProgress = 1f,
                overallProgress = 1f,
                stageMessage = if (isCancelledFlag) "Batch cancelled" else "Batch completed",
                isCancelled = isCancelledFlag,
                summary = summary
            )
        }
    }
}

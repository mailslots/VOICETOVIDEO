package com.example.data

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import com.example.model.AudioItem
import com.example.model.ConversionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.sin

class AudioRepository(private val context: Context) {

    companion object {
        private const val TAG = "AudioRepository"
        val SUPPORTED_EXTENSIONS = setOf("m4a", "mp3", "wav", "aac", "ogg", "3gp", "amr", "flac")
    }

    fun getOutputDirectory(): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: File(context.filesDir, "ConvertedVideos")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    suspend fun resolveUris(uris: List<Uri>): List<AudioItem> = withContext(Dispatchers.IO) {
        uris.mapNotNull { uri ->
            try {
                resolveSingleUri(uri)
            } catch (e: Exception) {
                Log.e(TAG, "Error resolving URI $uri: ${e.message}")
                null
            }
        }
    }

    suspend fun scanFolder(treeUri: Uri): List<AudioItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<AudioItem>()
        try {
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            scanDocumentDirectory(treeUri, rootDocId, items)
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning folder: ${e.message}")
        }
        items
    }

    private fun scanDocumentDirectory(treeUri: Uri, docId: String, outItems: MutableList<AudioItem>) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(childrenUri, projection, null, null, null)
            cursor?.use {
                val idIdx = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                val modIdx = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                while (it.moveToNext()) {
                    val childDocId = it.getString(idIdx)
                    val name = it.getString(nameIdx) ?: "Audio_${UUID.randomUUID().toString().take(6)}"
                    val mime = it.getString(mimeIdx) ?: ""
                    val size = it.getLong(sizeIdx)
                    val lastMod = it.getLong(modIdx)

                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        // Recursively scan subfolder
                        scanDocumentDirectory(treeUri, childDocId, outItems)
                    } else {
                        val ext = name.substringAfterLast('.', "").lowercase()
                        if (mime.startsWith("audio/") || ext in SUPPORTED_EXTENSIONS) {
                            val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId)
                            val displayName = name.substringBeforeLast('.')

                            var durationMs = 0L
                            try {
                                val retriever = MediaMetadataRetriever()
                                retriever.setDataSource(context, documentUri)
                                val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                durationMs = dur?.toLongOrNull() ?: 0L
                                retriever.release()
                            } catch (_: Exception) {}

                            val createdAt = if (lastMod > 0) lastMod else System.currentTimeMillis()

                            outItems.add(
                                AudioItem(
                                    id = documentUri.toString(),
                                    uri = documentUri,
                                    fileName = name,
                                    displayName = displayName,
                                    sizeBytes = size,
                                    durationMs = durationMs,
                                    createdAt = createdAt,
                                    mimeType = mime.ifEmpty { "audio/*" },
                                    isSelected = true,
                                    status = ConversionStatus.Idle
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error listing children for docId $docId: ${e.message}")
        } finally {
            cursor?.close()
        }
    }

    suspend fun scanMediaStoreRecordings(): List<AudioItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<AudioItem>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.MIME_TYPE
        )

        try {
            val cursor = context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"
            )

            cursor?.use {
                val idCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val sizeCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val durCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dateCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                val mimeCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)

                var count = 0
                while (it.moveToNext() && count < 50) {
                    val id = it.getLong(idCol)
                    val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    val name = it.getString(nameCol) ?: "Recording_$id"
                    val size = it.getLong(sizeCol)
                    val duration = it.getLong(durCol)
                    val dateSec = it.getLong(dateCol)
                    val mime = it.getString(mimeCol) ?: "audio/mp4"

                    val displayName = name.substringBeforeLast('.')
                    items.add(
                        AudioItem(
                            id = contentUri.toString(),
                            uri = contentUri,
                            fileName = name,
                            displayName = displayName,
                            sizeBytes = size,
                            durationMs = duration,
                            createdAt = if (dateSec > 0) dateSec * 1000L else System.currentTimeMillis(),
                            mimeType = mime,
                            isSelected = true,
                            status = ConversionStatus.Idle
                        )
                    )
                    count++
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying MediaStore: ${e.message}")
        }

        items
    }

    fun deleteSourceAudio(audioItem: AudioItem): Boolean {
        return try {
            if (audioItem.uri.scheme == "content") {
                try {
                    DocumentsContract.deleteDocument(context.contentResolver, audioItem.uri)
                } catch (_: Exception) {
                    context.contentResolver.delete(audioItem.uri, null, null) > 0
                }
            } else if (audioItem.uri.scheme == "file") {
                val file = File(audioItem.uri.path ?: "")
                file.delete()
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete audio file: ${e.message}")
            false
        }
    }

    suspend fun generateDemoRecordings(): List<AudioItem> = withContext(Dispatchers.IO) {
        val samplesDir = File(context.filesDir, "DemoRecordings")
        if (!samplesDir.exists()) samplesDir.mkdirs()

        val demos = listOf(
            Triple("Voice Memo - Project Brainstorm.wav", 440.0, 4),
            Triple("Phone Call - Client Briefing.wav", 523.25, 5),
            Triple("Voice Note - Grocery Checklist.wav", 659.25, 3)
        )

        val items = mutableListOf<AudioItem>()
        val currentTime = System.currentTimeMillis()

        demos.forEachIndexed { index, (fileName, freq, durationSec) ->
            val sampleFile = File(samplesDir, fileName)
            createSampleWavFile(sampleFile, freq, durationSec)
            val fileTime = currentTime - (index * 3600_000L * 4)
            sampleFile.setLastModified(fileTime)

            val uri = Uri.fromFile(sampleFile)
            val displayName = fileName.substringBeforeLast('.')
            items.add(
                AudioItem(
                    id = uri.toString(),
                    uri = uri,
                    fileName = fileName,
                    displayName = displayName,
                    sizeBytes = sampleFile.length(),
                    durationMs = durationSec * 1000L,
                    createdAt = fileTime,
                    mimeType = "audio/wav",
                    isSelected = true,
                    status = ConversionStatus.Idle
                )
            )
        }

        items
    }

    private fun resolveSingleUri(uri: Uri): AudioItem {
        var fileName = "Audio_${UUID.randomUUID().toString().take(6)}"
        var sizeBytes = 0L
        var lastModified = 0L

        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            val modIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

            if (cursor.moveToFirst()) {
                if (nameIdx >= 0) fileName = cursor.getString(nameIdx) ?: fileName
                if (sizeIdx >= 0) sizeBytes = cursor.getLong(sizeIdx)
                if (modIdx >= 0) lastModified = cursor.getLong(modIdx)
            }
        }

        var durationMs = 0L
        var mimeType = context.contentResolver.getType(uri) ?: "audio/*"

        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            durationMs = dur?.toLongOrNull() ?: 0L
            val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            if (!mime.isNullOrEmpty()) mimeType = mime
            retriever.release()
        } catch (_: Exception) {}

        if (lastModified <= 0) {
            lastModified = System.currentTimeMillis()
        }

        val displayName = fileName.substringBeforeLast('.')

        return AudioItem(
            id = uri.toString(),
            uri = uri,
            fileName = fileName,
            displayName = displayName,
            sizeBytes = sizeBytes,
            durationMs = durationMs,
            createdAt = lastModified,
            mimeType = mimeType,
            isSelected = true,
            status = ConversionStatus.Idle
        )
    }

    private fun createSampleWavFile(file: File, frequency: Double, durationSeconds: Int) {
        val sampleRate = 44100
        val numSamples = durationSeconds * sampleRate
        val pcmData = ShortArray(numSamples)

        for (i in 0 until numSamples) {
            val envelope = (sin(Math.PI * i / numSamples)).toFloat()
            val sample = (sin(2.0 * Math.PI * i * frequency / sampleRate) * 0.7 +
                    sin(2.0 * Math.PI * i * (frequency * 1.5) / sampleRate) * 0.3) * envelope * Short.MAX_VALUE * 0.5
            pcmData[i] = sample.toInt().toShort()
        }

        val byteRate = sampleRate * 2
        val dataSize = numSamples * 2
        val totalSize = 36 + dataSize

        FileOutputStream(file).use { fos ->
            fos.write("RIFF".toByteArray())
            fos.write(intToByteArray(totalSize))
            fos.write("WAVE".toByteArray())

            fos.write("fmt ".toByteArray())
            fos.write(intToByteArray(16))
            fos.write(shortToByteArray(1))
            fos.write(shortToByteArray(1))
            fos.write(intToByteArray(sampleRate))
            fos.write(intToByteArray(byteRate))
            fos.write(shortToByteArray(2))
            fos.write(shortToByteArray(16))

            fos.write("data".toByteArray())
            fos.write(intToByteArray(dataSize))

            val buffer = ByteArray(2)
            for (sample in pcmData) {
                buffer[0] = (sample.toInt() and 0xff).toByte()
                buffer[1] = ((sample.toInt() shr 8) and 0xff).toByte()
                fos.write(buffer)
            }
        }
    }

    private fun intToByteArray(value: Int): ByteArray = byteArrayOf(
        (value and 0xff).toByte(),
        ((value shr 8) and 0xff).toByte(),
        ((value shr 16) and 0xff).toByte(),
        ((value shr 24) and 0xff).toByte()
    )

    private fun shortToByteArray(value: Short): ByteArray = byteArrayOf(
        (value.toInt() and 0xff).toByte(),
        ((value.toInt() shr 8) and 0xff).toByte()
    )
}

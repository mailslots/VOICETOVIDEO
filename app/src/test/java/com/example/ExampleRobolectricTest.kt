package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.converter.YuvConverter
import com.example.data.AudioRepository
import com.example.model.AudioItem
import com.example.model.ConversionStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context matches app name`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("VTV (Voice2Video)", appName)
    }

    @Test
    fun `audio item formatted helpers work correctly`() {
        val item = AudioItem(
            id = "test-1",
            uri = android.net.Uri.parse("file:///dummy.m4a"),
            fileName = "voice_memo_test.m4a",
            displayName = "voice_memo_test",
            sizeBytes = 2 * 1024 * 1024L, // 2MB
            durationMs = 90_000L, // 1m 30s
            createdAt = 1700000000000L,
            mimeType = "audio/mp4a-latm"
        )

        assertEquals("01:30", item.formattedDuration)
        assertTrue(item.formattedSize.contains("MB"))
        assertNotNull(item.formattedCreatedAt)
    }

    @Test
    fun `yuv converter produces valid byte array sizes`() {
        val width = 16
        val height = 16
        val argb = IntArray(width * height) { 0xFF00FF00.toInt() } // pure green
        val nv12 = ByteArray(width * height * 3 / 2)
        val i420 = ByteArray(width * height * 3 / 2)

        YuvConverter.argbToNv12(argb, width, height, nv12)
        YuvConverter.argbToI420(argb, width, height, i420)

        assertEquals(width * height * 3 / 2, nv12.size)
        assertEquals(width * height * 3 / 2, i420.size)
        // First byte should be non-zero luminance
        assertTrue(nv12[0].toInt() != 0)
    }

    @Test
    fun `audio repository generates valid demo recordings`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = AudioRepository(context)

        val demos = repository.generateDemoRecordings()
        assertTrue(demos.isNotEmpty())
        assertEquals(3, demos.size)

        for (demo in demos) {
            assertTrue(demo.fileName.endsWith(".wav"))
            assertTrue(demo.durationMs > 0)
            val file = File(demo.uri.path ?: "")
            assertTrue(file.exists())
            assertTrue(file.length() > 44) // Bigger than standard WAV header
        }
    }
}

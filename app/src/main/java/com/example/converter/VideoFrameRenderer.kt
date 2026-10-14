package com.example.converter

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.sin

class VideoFrameRenderer {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#38BDF8")
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(10f, 0f, 4f, Color.parseColor("#99000000"))
    }
    private val timestampPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8") // Vibrant cyan accent
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(6f, 0f, 2f, Color.parseColor("#88000000"))
    }
    private val subTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8") // Slate gray
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }

    fun renderFrame(
        canvas: Canvas,
        width: Int,
        height: Int,
        audioTitle: String,
        timestampText: String,
        durationText: String,
        frameIndex: Int,
        totalFrames: Int
    ) {
        // 1. Draw rich background gradient (Midnight Navy to Obsidian)
        bgPaint.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            intArrayOf(Color.parseColor("#090D16"), Color.parseColor("#111827"), Color.parseColor("#0D131F")),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // 2. Ambient center glow
        val centerX = width / 2f
        val centerY = height / 2f
        glowPaint.shader = RadialGradient(
            centerX, centerY, width * 0.65f,
            intArrayOf(Color.parseColor("#2238BDF8"), Color.parseColor("#0A818CF8"), Color.TRANSPARENT),
            floatArrayOf(0f, 0.4f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(centerX, centerY, width * 0.65f, glowPaint)

        // 3. Top category badge
        val tagText = "• VTV (Voice2Video) • Designed by Phubet •"
        subTextPaint.textSize = (width * 0.024f).coerceIn(14f, 22f)
        subTextPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(tagText, centerX, height * 0.15f, subTextPaint)

        // 4. Centered Title (Big, Readable Text)
        val maxTitleWidth = (width * 0.84f).toInt()
        val titleSize = (width * 0.065f).coerceIn(28f, 54f)
        titlePaint.textSize = titleSize

        val titleLayout = StaticLayout.Builder.obtain(
            audioTitle, 0, audioTitle.length, titlePaint, maxTitleWidth
        ).setAlignment(Layout.Alignment.ALIGN_CENTER)
         .setMaxLines(4)
         .build()

        val titleHeight = titleLayout.height
        val titleTop = centerY - (titleHeight / 2f) - (height * 0.04f)

        canvas.save()
        canvas.translate(centerX - (maxTitleWidth / 2f), titleTop)
        titleLayout.draw(canvas)
        canvas.restore()

        // 5. Creation Timestamp directly below title in center
        val timestampSize = (width * 0.038f).coerceIn(18f, 32f)
        timestampPaint.textSize = timestampSize
        timestampPaint.textAlign = Paint.Align.CENTER
        val timestampY = titleTop + titleHeight + (height * 0.045f)
        canvas.drawText(timestampText, centerX, timestampY, timestampPaint)

        // 6. Metadata pill badge (Duration & Audio Info)
        val metaText = if (durationText.isNotEmpty()) "$durationText • High Quality MP4" else "High Quality MP4"
        subTextPaint.textSize = (width * 0.028f).coerceIn(15f, 24f)
        subTextPaint.textAlign = Paint.Align.CENTER
        val textWidth = subTextPaint.measureText(metaText)
        val pillPaddingH = width * 0.04f
        val pillHeight = height * 0.045f
        val pillY = timestampY + (height * 0.035f)
        val pillRect = RectF(
            centerX - (textWidth / 2f) - pillPaddingH,
            pillY,
            centerX + (textWidth / 2f) + pillPaddingH,
            pillY + pillHeight
        )

        pillPaint.color = Color.parseColor("#1E293B")
        canvas.drawRoundRect(pillRect, pillHeight / 2f, pillHeight / 2f, pillPaint)
        canvas.drawRoundRect(pillRect, pillHeight / 2f, pillHeight / 2f, pillStrokePaint)

        val fontMetrics = subTextPaint.fontMetrics
        val textCenterY = pillRect.centerY() - (fontMetrics.ascent + fontMetrics.descent) / 2f
        canvas.drawText(metaText, centerX, textCenterY, subTextPaint)

        // 7. Dynamic animated waveform bars at the bottom
        drawWaveformVisualizer(canvas, width, height, frameIndex, totalFrames)
    }

    private fun drawWaveformVisualizer(
        canvas: Canvas,
        width: Int,
        height: Int,
        frameIndex: Int,
        totalFrames: Int
    ) {
        val barCount = 28
        val barWidth = width * 0.016f
        val barSpacing = width * 0.012f
        val totalVisualizerWidth = (barCount * barWidth) + ((barCount - 1) * barSpacing)
        val startX = (width - totalVisualizerWidth) / 2f
        val bottomY = height * 0.90f
        val maxBarHeight = height * 0.12f

        for (i in 0 until barCount) {
            val progressPhase = (frameIndex.toFloat() / 10f) + (i * 0.35f)
            val waveFactor = (sin(progressPhase.toDouble()) * 0.4 + sin((progressPhase * 0.5).toDouble()) * 0.3 + 0.5).toFloat()
            val barH = (maxBarHeight * waveFactor.coerceIn(0.12f, 0.95f))

            val x = startX + i * (barWidth + barSpacing)
            val topY = bottomY - barH

            // Bar color gradient
            val colorRatio = i.toFloat() / barCount
            val r = (6 + (129 * colorRatio)).toInt()
            val g = (182 + (-42 * colorRatio)).toInt()
            val b = (212 + (34 * colorRatio)).toInt()
            wavePaint.color = Color.rgb(r, g, b)

            val barRect = RectF(x, topY, x + barWidth, bottomY)
            canvas.drawRoundRect(barRect, barWidth / 2f, barWidth / 2f, wavePaint)
        }
    }
}

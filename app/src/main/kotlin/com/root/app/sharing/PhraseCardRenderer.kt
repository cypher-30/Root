package com.root.app.sharing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.annotation.SuppressLint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import com.root.app.R
import com.root.app.data.PhraseEntity
import com.root.app.ui.root.RootGeometry
import androidx.compose.ui.graphics.asAndroidPath
import java.io.IOException
import kotlin.math.max

data class PhraseCardPalette(
    val paper: Int,
    val ink: Int,
    val secondaryInk: Int,
    val pigment: Int,
    val rule: Int,
)

class PhraseCardException(message: String) : IOException(message)

/** StaticLayout preserves shaping, diacritics, wrapping, and complete phrases in the exported PNG. */
object PhraseCardRenderer {
    private const val WIDTH = 1080
    private const val MARGIN = 80
    private const val TEXT_WIDTH = WIDTH - MARGIN * 2

    fun render(
        context: Context,
        phrase: PhraseEntity,
        languageName: String,
        palette: PhraseCardPalette,
    ): Bitmap {
        if (phrase.answer.isBlank() || phrase.prompt.isBlank()) {
            throw PhraseCardException("This phrase needs both a word and its meaning before it can be shared.")
        }
        if (phrase.answer.length + phrase.prompt.length + languageName.length > 12_000) {
            throw PhraseCardException("This phrase is too long for a readable image. Choose a shorter phrase.")
        }
        val serif = context.resources.getFont(R.font.source_serif)
        val sans = context.resources.getFont(R.font.inter)
        val labelPaint = textPaint(sans, 28f, palette.secondaryInk)
        val language = layout(languageName.ifBlank { "Your language" }, labelPaint)
        val answer = fittedLayout(phrase.answer, serif, palette.ink, 128f, 64f, 1300)
        val meaning = fittedLayout(phrase.prompt, sans, palette.secondaryInk, 42f, 32f, 1000)
        val dividerY = MARGIN + language.height + 40f
        val bodyTop = dividerY + 100f
        val bodyHeight = answer.height + 40f + meaning.height
        val height = max(1350, (bodyTop + bodyHeight + 260f).toInt())
        if (height > 8192) {
            throw PhraseCardException("This phrase is too long for a readable image. Choose a shorter phrase.")
        }

        val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(palette.paper)
        val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.rule
            strokeWidth = 2f
        }
        drawLayout(canvas, language, MARGIN.toFloat(), MARGIN.toFloat())
        canvas.drawLine(MARGIN.toFloat(), dividerY, (WIDTH - MARGIN).toFloat(), dividerY, rulePaint)
        val availableHeight = height - 240f - bodyTop
        val answerTop = bodyTop + max(0f, (availableHeight - bodyHeight) / 2f)
        drawLayout(canvas, answer, MARGIN.toFloat(), answerTop)
        drawLayout(canvas, meaning, MARGIN.toFloat(), answerTop + answer.height + 40f)

        val footerY = height - 180f
        canvas.drawLine(MARGIN.toFloat(), footerY, (WIDTH - MARGIN).toFloat(), footerY, rulePaint)
        val brandPaint = textPaint(serif, 68f, palette.ink)
        canvas.drawText("Root", MARGIN + 88f, height - 78f, brandPaint)
        val footer = textPaint(sans, 24f, palette.secondaryInk)
        footer.textAlign = Paint.Align.RIGHT
        canvas.drawText("A word to keep. A word to give.", (WIDTH - MARGIN).toFloat(), height - 80f, footer)
        val pigment = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.ink
            strokeWidth = 2f
            strokeCap = Paint.Cap.SQUARE
            style = Paint.Style.STROKE
        }
        canvas.save()
        canvas.translate(MARGIN.toFloat(), height - 148f)
        canvas.scale(0.72f, 0.72f)
        RootGeometry.branches().forEach { canvas.drawPath(it.asAndroidPath(), pigment) }
        pigment.style = Paint.Style.FILL
        canvas.drawCircle(50f, 12f, 2f, pigment)
        canvas.restore()
        return bitmap
    }

    private fun textPaint(font: Typeface, size: Float, colorValue: Int) =
        TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = font
            textSize = size
            color = colorValue
        }

    private fun fittedLayout(
        text: String,
        font: Typeface,
        color: Int,
        initialSize: Float,
        minimumSize: Float,
        preferredHeight: Int,
    ): StaticLayout {
        var size = initialSize
        var result = layout(text, textPaint(font, size, color))
        while (result.height > preferredHeight && size > minimumSize) {
            size = max(minimumSize, size - 8f)
            result = layout(text, textPaint(font, size, color))
        }
        return result
    }

    // This constant is inlined; StaticLayout supports this break strategy since API 23.
    @SuppressLint("InlinedApi")
    private fun layout(text: String, paint: TextPaint): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, TEXT_WIDTH)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setLineSpacing(8f, 1.04f)
            .setIncludePad(true)
            .build()

    private fun drawLayout(canvas: Canvas, layout: StaticLayout, x: Float, y: Float) {
        canvas.save()
        canvas.translate(x, y)
        layout.draw(canvas)
        canvas.restore()
    }
}

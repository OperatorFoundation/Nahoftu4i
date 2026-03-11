package org.nahoft.nahoft.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import org.nahoft.nahoft.R
import org.operatorfoundation.signalbridge.models.AudioLevelInfo

/**
 * VU meter view for displaying audio signal level during receive.
 *
 * Renders a horizontal bar with a gradient track showing signal quality zones,
 * a fill driven by the current RMS level, and a peak-hold tick marker.
 */
class VuMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr)
{
    companion object
    {
        // Tune these as needed
        const val LEVEL_TOO_WEAK  = 0.08f
        const val LEVEL_WARN      = 0.50f
        const val LEVEL_CLIP_WARN = 0.90f

        private const val SEGMENT_COUNT   = 60
        private const val SEGMENT_GAP_DP  = 2f
        private const val MIN_HEIGHT_DP   = 6f
        private const val MAX_HEIGHT_DP   = 28f
        private const val CORNER_RADIUS_DP = 2f
        private const val PEAK_TICK_WIDTH_DP = 3f
        private const val LABEL_MARGIN_DP = 4f
        private const val DEFAULT_VIEW_HEIGHT_DP = 64f  // bar + zone labels + status label
    }

    // Current levels — set by update()
    private var currentLevel = 0f
    private var peakLevel    = 0f

    // False until the first update() call — prevents "too weak" label at rest
    private var isActive = false

    // Pre-scaled pixel values — computed once in onSizeChanged
    private var segmentGapPx    = 0f
    private var minHeightPx     = 0f
    private var maxHeightPx     = 0f
    private var cornerRadiusPx  = 0f
    private var peakTickWidthPx = 0f
    private var labelMarginPx   = 0f

    // Reusable rect to avoid allocation in onDraw
    private val segRect = RectF()

    // ── Colors ────────────────────────────────────────────────────────────────
    private val colorGreen  by lazy { ContextCompat.getColor(context, R.color.caribbeanGreen) }
    private val colorOrange by lazy { ContextCompat.getColor(context, R.color.tangerine) }
    private val colorRed    by lazy { ContextCompat.getColor(context, R.color.madderLake) }

    // Dim versions of each zone color for unlit segments
    private val dimGreen  by lazy { colorGreen  and 0x00FFFFFF or 0x26000000 }
    private val dimOrange by lazy { colorOrange and 0x00FFFFFF or 0x26000000 }
    private val dimRed    by lazy { colorRed    and 0x00FFFFFF or 0x26000000 }

    // ── Paints ────────────────────────────────────────────────────────────────
    private val segPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize  = 11f * resources.displayMetrics.scaledDensity
    }

    private val zoneLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9f * resources.displayMetrics.scaledDensity
        color    = 0xFF88AACC.toInt()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Call from the audio level flow collector on the main thread. */
    fun update(info: AudioLevelInfo)
    {
        currentLevel = info.currentLevel
        peakLevel    = info.peakLevel
        isActive     = true
        invalidate()
    }

    /** Call when the session stops to blank the meter. */
    fun reset()
    {
        currentLevel = 0f
        peakLevel    = 0f
        isActive     = false
        invalidate()
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int)
    {
        val defaultH = (DEFAULT_VIEW_HEIGHT_DP * resources.displayMetrics.density).toInt()
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            resolveSize(defaultH, heightMeasureSpec)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int)
    {
        super.onSizeChanged(w, h, oldw, oldh)
        val dp = resources.displayMetrics.density
        segmentGapPx    = SEGMENT_GAP_DP   * dp
        minHeightPx     = MIN_HEIGHT_DP    * dp
        maxHeightPx     = MAX_HEIGHT_DP    * dp
        cornerRadiusPx  = CORNER_RADIUS_DP * dp
        peakTickWidthPx = PEAK_TICK_WIDTH_DP * dp
        labelMarginPx   = LABEL_MARGIN_DP  * dp
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas)
    {
        super.onDraw(canvas)

        val w = width.toFloat()

        // Total gap space between segments
        val totalGapWidth = segmentGapPx * (SEGMENT_COUNT - 1)
        val segmentWidth  = (w - totalGapWidth) / SEGMENT_COUNT

        val litCount  = (currentLevel * SEGMENT_COUNT).toInt()
        val peakIndex = (peakLevel    * SEGMENT_COUNT).toInt() - 1

        // ── Draw segments ─────────────────────────────────────────────────────
        for (i in 0 until SEGMENT_COUNT)
        {
            val pos = (i + 1).toFloat() / SEGMENT_COUNT

            // Height grows linearly from minHeightPx to maxHeightPx
            val segH = minHeightPx + (maxHeightPx - minHeightPx) * (i.toFloat() / (SEGMENT_COUNT - 1))

            val x = i * (segmentWidth + segmentGapPx)
            // Align all segments to the bottom of the bar area
            val top = maxHeightPx - segH

            segRect.set(x, top, x + segmentWidth, maxHeightPx)

            segPaint.color = when
            {
                i == peakIndex -> 0xFFFFFFFF.toInt()         // White peak tick
                i < litCount   -> litColor(pos)              // Lit: zone color
                else           -> dimColor(pos)              // Unlit: dim zone tint
            }

            canvas.drawRoundRect(segRect, cornerRadiusPx, cornerRadiusPx, segPaint)
        }

        // ── Zone labels ───────────────────────────────────────────────────────
        val zoneY = maxHeightPx + labelMarginPx + zoneLabelPaint.textSize
        drawZoneLabels(canvas, w, zoneY)



        // ── Status label ──────────────────────────────────────────────────────
        if (isActive)
        {
            val (statusText, statusColor) = statusLabel()
            val statusY = zoneY + labelMarginPx + labelPaint.textSize
            labelPaint.color = statusColor
            canvas.drawText(statusText, 0f, statusY, labelPaint)

            // ── RMS readout (right-aligned, same row as status) ───────────────────
            val rmsText = "${(currentLevel * 100).toInt()}% RMS · peak ${(peakLevel * 100).toInt()}%"
            labelPaint.color = 0xFFFFFFFF.toInt()
            val rmsX = w - labelPaint.measureText(rmsText)
            canvas.drawText(rmsText, rmsX, statusY, labelPaint)
        }
    }

    /**
     * Draws GOOD / WARN / CLIP zone labels beneath the segments,
     * each centered over its respective zone.
     */
    private fun drawZoneLabels(canvas: Canvas, viewWidth: Float, y: Float)
    {
        val goodEndX  = LEVEL_WARN      * viewWidth
        val warnEndX  = LEVEL_CLIP_WARN * viewWidth

        // GOOD — right-aligned to the end of the good zone
        zoneLabelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("GOOD", goodEndX - labelMarginPx, y, zoneLabelPaint)

        // WARN — centered in the warn zone
        zoneLabelPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("WARN", (goodEndX + warnEndX) / 2f, y, zoneLabelPaint)

        // CLIP — left-aligned from the start of the clip zone
        zoneLabelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("CLIP", warnEndX + labelMarginPx, y, zoneLabelPaint)
    }

    /**
     * Returns the lit color for a segment at the given normalized position.
     */
    private fun litColor(pos: Float): Int = when
    {
        pos > LEVEL_CLIP_WARN -> colorRed
        pos > LEVEL_WARN      -> colorOrange
        pos > LEVEL_TOO_WEAK  -> colorGreen
        else                  -> colorRed  // Too-weak zone also red
    }

    /**
     * Returns the dim (unlit) color for a segment at the given normalized position.
     */
    private fun dimColor(pos: Float): Int = when
    {
        pos > LEVEL_CLIP_WARN -> dimRed
        pos > LEVEL_WARN      -> dimOrange
        pos > LEVEL_TOO_WEAK  -> dimGreen
        else                  -> dimRed
    }

    /**
     * Returns the status label text and color for the current signal levels.
     * Clipping takes priority over weak signal.
     */
    private fun statusLabel(): Pair<String, Int> = when
    {
        peakLevel    >= LEVEL_CLIP_WARN -> Pair(context.getString(R.string.audio_signal_clipping),  colorRed)
        currentLevel <  LEVEL_TOO_WEAK  -> Pair(context.getString(R.string.audio_signal_too_weak),  colorOrange)
        else                            -> Pair(context.getString(R.string.audio_signal_good),       colorGreen)
    }
}
package com.rohit.share_scan

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.color.MaterialColors
import com.rohit.share_scan.databinding.ItemDetailScoreBinding
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The stock's "💰 बड़े पैसे की चाल" card in its detail: the evening's score and its six parts (each opens into its
 * sum), today's signals, the last 20 sessions' delivery as bars with the volume as a line, the deals and promoters
 * netted, and each signal's track record.
 */
class BigMoneyCard(
    private val activity: AppCompatActivity,
    private val book: BigMoneyBook,
    private val symbol: String,
) {
    private val dp = activity.resources.displayMetrics.density

    fun bind(container: LinearLayout) {
        container.removeAllViews()
        container.addView(text(activity.getString(R.string.big_money_card_title), R.color.text_primary, 17f, bold = true))
        if (book.date == null) {
            container.addView(text(activity.getString(R.string.big_money_no_file), R.color.text_secondary, 13f))
            return
        }
        val entry = book.of(symbol)
        if (entry == null) {
            container.addView(text(activity.getString(R.string.big_money_card_missing, BigMoneyText.day(book.date)), R.color.text_secondary, 13f))
            return
        }
        val tone = when {
            entry.score >= BigMoneyText.STRONG -> R.color.bull
            entry.score >= 30 -> R.color.warn
            else -> R.color.neutral
        }
        container.addView(text(activity.getString(R.string.big_money_card_score, entry.score), tone, 15f, bold = true, top = 4))
        container.addView(text(activity.getString(R.string.big_money_card_date, BigMoneyText.day(book.date)), R.color.text_secondary, 12f, top = 0))

        val signals = BigMoneyBook.Signal.entries.filter { it in entry.signals }
        container.addView(
            if (signals.isEmpty()) {
                text(activity.getString(R.string.big_money_signals_none), R.color.text_secondary, 13.5f, top = 8)
            } else {
                text("", R.color.text_primary, 13.5f, top = 8).apply {
                    text = SpannableStringBuilder().apply {
                        signals.forEachIndexed { i, s ->
                            if (i > 0) append("\n")
                            val start = length
                            append(BigMoneyText.signal(activity, s))
                            setSpan(ForegroundColorSpan(color(BigMoneyText.tone(s))), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                }
            },
        )

        for (part in BigMoneyText.parts(activity, entry)) addPart(container, part)

        container.addView(text(activity.getString(R.string.big_money_chart, entry.deliverySeries.size), R.color.text_secondary, 12f, top = 14))
        container.addView(
            DeliveryBarsView(activity).apply {
                deliveries = entry.deliverySeries
                volumes = entry.volumeSeries
                barColor = color(R.color.bull)
                lastColor = color(R.color.warn)
                lineColor = color(R.color.text_secondary)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (96 * dp).roundToInt()).apply { topMargin = (6 * dp).roundToInt() },
        )

        val deal = entry.deal
        container.addView(
            text(
                if (deal == null) {
                    activity.getString(R.string.big_money_deal_none)
                } else {
                    activity.getString(
                        if (deal.shares >= 0) R.string.big_money_deal_line_buy else R.string.big_money_deal_line_sell,
                        BigMoneyText.shares(deal.shares),
                        BigMoneyText.rupees(deal.rupees),
                        BigMoneyText.streak(activity, deal),
                    )
                },
                R.color.text_primary,
                13.5f,
                top = 12,
            ),
        )
        if (entry.circular.isNotEmpty()) {
            container.addView(text(activity.getString(R.string.big_money_circular, entry.circular.joinToString(", ")), R.color.warn, 13f, top = 4))
        }
        val promoter = entry.promoter
        container.addView(
            text(
                if (promoter == null) {
                    activity.getString(R.string.big_money_promoter_none)
                } else {
                    activity.getString(
                        if (promoter.rupees >= 0) R.string.big_money_promoter_line_buy else R.string.big_money_promoter_line_sell,
                        BigMoneyText.rupees(promoter.rupees),
                        promoter.buys,
                        promoter.sells,
                    )
                },
                R.color.text_primary,
                13.5f,
                top = 6,
            ),
        )

        BigMoneyText.promotersStale(activity, book)?.let { container.addView(text(it, R.color.warn, 12.5f, top = 4)) }
        for (signal in signals) {
            val line = BigMoneyText.signal(activity, signal) + ": " + BigMoneyText.record(activity, book.records[signal.key], signal.bearish)
            container.addView(text(line, R.color.text_secondary, 12.5f, top = 8))
        }
        if (entry.score >= BigMoneyText.STRONG) {
            val line = activity.getString(R.string.big_money_score_strong) + ": " + BigMoneyText.record(activity, book.records["score60"], false)
            container.addView(text(line, R.color.text_secondary, 12.5f, top = 8))
        }
        container.addView(text(activity.getString(R.string.big_money_caution), R.color.text_secondary, 11f, top = 10))
    }

    /** A part of the score as the detail's score rows draw it; a tap shows the sum behind it. */
    private fun addPart(container: LinearLayout, part: BigMoneyText.Part) {
        val row = ItemDetailScoreBinding.inflate(activity.layoutInflater, container, false)
        val tone = when {
            part.points < 0 -> R.color.bear
            part.points > 0 -> R.color.bull
            else -> R.color.neutral
        }
        row.partValue.text = BigMoneyText.points(part.points)
        row.partValue.setTextColor(color(tone))
        row.partWeight.text = activity.getString(R.string.big_money_part_max, part.max)
        row.partBar.setIndicatorColor(color(tone))
        row.partBar.setProgressCompat((abs(part.points) / part.max * 100).roundToInt().coerceIn(0, 100), false)
        row.root.contentDescription = activity.getString(R.string.big_money_part_description, part.name, BigMoneyText.points(part.points), part.max)
        container.addView(row.root)
        val body = text(part.body, R.color.text_secondary, 12.5f, top = 0).apply {
            setPadding((8 * dp).roundToInt(), 0, 0, (6 * dp).roundToInt())
            isVisible = false
        }
        container.addView(body)
        val ripple = TypedValue().also { activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }
        row.root.setBackgroundResource(ripple.resourceId)
        fun label(open: Boolean) {
            row.partName.text = SpannableStringBuilder(part.name).apply {
                val start = length
                append(if (open) " ▴" else " ▾")
                setSpan(
                    ForegroundColorSpan(MaterialColors.getColor(row.partName, androidx.appcompat.R.attr.colorPrimary)),
                    start,
                    length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        label(false)
        row.root.setOnClickListener {
            body.isVisible = !body.isVisible
            label(body.isVisible)
        }
    }

    private fun text(value: String, colour: Int, size: Float, bold: Boolean = false, top: Int = 6): TextView =
        TextView(activity).apply {
            text = value
            setTextColor(color(colour))
            textSize = size
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (top * dp).roundToInt()
            }
        }

    private fun color(id: Int) = ContextCompat.getColor(activity, id)
}

/** A share's last sessions of delivery % as bars (0–100%, the latest in its own colour) with its volume as a line. */
class DeliveryBarsView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var deliveries: List<Double?> = emptyList()
        set(value) {
            field = value
            invalidate()
        }
    var volumes: List<Long?> = emptyList()
        set(value) {
            field = value
            invalidate()
        }
    var barColor: Int = 0
    var lastColor: Int = 0
    var lineColor: Int = 0

    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        val n = deliveries.size
        if (n == 0) return
        val slot = width.toFloat() / n
        val gap = slot * 0.2f
        deliveries.forEachIndexed { i, d ->
            d ?: return@forEachIndexed
            bar.color = if (i == n - 1) lastColor else barColor
            bar.alpha = if (i == n - 1) 255 else 150
            val top = height * (1f - (d / 100.0).toFloat().coerceIn(0f, 1f))
            canvas.drawRect(i * slot + gap / 2, top, (i + 1) * slot - gap / 2, height.toFloat(), bar)
        }
        val most = volumes.filterNotNull().maxOrNull()?.takeIf { it > 0 } ?: return
        line.color = lineColor
        val path = Path()
        var started = false
        volumes.forEachIndexed { i, v ->
            v ?: return@forEachIndexed
            val x = i * slot + slot / 2
            val y = height * (1f - 0.9f * v.toFloat() / most) + line.strokeWidth
            if (started) {
                path.lineTo(x, y)
            } else {
                path.moveTo(x, y)
                started = true
            }
        }
        canvas.drawPath(path, line)
    }
}

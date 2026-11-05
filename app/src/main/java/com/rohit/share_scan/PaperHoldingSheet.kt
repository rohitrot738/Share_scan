package com.rohit.share_scan

import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.rohit.share_scan.PositionSize.Product
import com.rohit.share_scan.databinding.ItemHelpBinding
import com.rohit.share_scan.databinding.SheetListBinding
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A paper position in full, opened by a tap on it in Paper Trading ([PaperSheet]): the price now and the profit or
 * loss, how it was taken (shares, average, the money or margin, when, who chose it), its stop-loss and target with
 * what each would leave after charges, the breakeven, the charges paid and still to pay, and its orders
 * ([PaperEngine.holding]); then closing it, changing its levels, a new order and the stock. [render] reads the account
 * again (Paper Trading calls it after each price refresh), and the sheet closes itself once the position is gone.
 */
class PaperHoldingSheet(
    private val activity: AppCompatActivity,
    private val symbol: String,
    private val product: Product,
    /** An order as the orders tab shows it: its title and its lines. */
    private val order: (PaperOrder) -> Pair<String, CharSequence>,
    private val onExit: (PaperPosition) -> Unit,
    private val onLevels: (PaperPosition) -> Unit,
    private val onMore: (PaperPosition) -> Unit,
    private val onOpen: (PaperPosition) -> Unit,
) {
    private val ui = SheetListBinding.inflate(activity.layoutInflater)
    private val dp = activity.resources.displayMetrics.density
    private var dialog: BottomSheetDialog? = null

    val showing: Boolean get() = dialog?.isShowing == true

    fun show() {
        dialog = activity.showSheet(ui.root, ui.sheetTopBar, onBack = {}, fullHeight = true)
        render()
    }

    fun dismiss() {
        dialog?.dismiss()
    }

    fun render() {
        val book = PaperStore.load(activity)
        val p = PaperEngine.position(book, symbol, product)
        if (p == null) {
            dialog?.dismiss()
            return
        }
        val h = PaperEngine.holding(book, p)
        val opened = WHEN.withLocale(activity.dateLocale()).format(Instant.ofEpochMilli(p.openedAt).atZone(MarketClock.INDIA))
        ui.listTitle.text = activity.getString(R.string.paper_position_title, p.symbol, PaperText.side(activity, p.side), abs(p.qty))
        ui.listSubtitle.text = activity.getString(R.string.paper_detail_subtitle, PaperText.product(activity, p.product), opened)
        ui.listItems.removeAllViews()

        section(R.string.paper_detail_now) {
            row(R.string.paper_detail_ltp, h.priceAt?.let { PaperText.money(h.price) + " · " + candleTime(activity, it) } ?: activity.getString(R.string.paper_detail_no_price))
            row(R.string.paper_detail_pnl, PaperText.signed(h.pnl) + " (" + Cr360Format.signed(h.pnlPct, 2) + "%)", tone(h.pnl))
            row(R.string.paper_detail_value, PaperText.money(h.value))
            row(R.string.paper_detail_net, PaperText.signed(h.net), tone(h.net))
        }

        section(R.string.paper_detail_entry) {
            row(R.string.paper_detail_shares, PaperText.side(activity, p.side) + " " + abs(p.qty))
            row(R.string.paper_detail_avg, PaperText.money(p.avg))
            if (p.product == Product.MIS) {
                row(R.string.paper_detail_margin, PaperText.money(p.margin))
            } else {
                row(R.string.paper_detail_invested, PaperText.money(h.invested))
            }
            row(R.string.paper_detail_opened, opened)
            if (p.product == Product.MIS) {
                note(activity.getString(R.string.paper_position_square_off), R.color.warn)
            } else {
                val days = ChronoUnit.DAYS.between(MarketClock.now(p.openedAt).toLocalDate(), MarketClock.now().toLocalDate())
                row(R.string.paper_detail_held, activity.getString(R.string.paper_detail_days, days.toInt()))
            }
            row(R.string.paper_detail_source, activity.getString(if (p.source == PaperSource.MANUAL) R.string.paper_split_manual else R.string.paper_split_app))
        }

        section(R.string.paper_detail_levels) {
            row(R.string.paper_detail_stop, level(p.stop, h.atStop, h.price), h.atStop?.let(::tone))
            row(R.string.paper_detail_target, level(p.target, h.atTarget, h.price), h.atTarget?.let(::tone))
            row(R.string.paper_detail_breakeven, PaperText.money(h.breakeven))
            note(activity.getString(if (p.qty > 0) R.string.paper_detail_breakeven_long else R.string.paper_detail_breakeven_short), R.color.text_secondary)
        }

        section(R.string.paper_detail_charges) {
            row(R.string.paper_detail_charges_paid, PaperText.money(p.entryCharges))
            row(R.string.paper_detail_charges_exit, PaperText.money(h.exitCharges))
        }

        heading(R.string.paper_detail_orders)
        for (o in h.orders) {
            val (title, body) = order(o)
            val card = ItemHelpBinding.inflate(activity.layoutInflater, ui.listItems, true)
            card.helpTitle.text = title
            card.helpBody.text = body
            card.helpBody.setTextColor(color(R.color.text_primary))
        }

        button(R.string.paper_action_exit) { onExit(p) }
        button(R.string.paper_action_levels) { onLevels(p) }
        button(R.string.paper_action_more) { onMore(p) }
        button(R.string.paper_action_open) { onOpen(p) }
    }

    /** "₹950.00 (−9.5% दूर) · लगा तो −₹629.10"; just the price without one to measure from; "नहीं लगा" when unset. */
    private fun level(level: Double?, leaves: Double?, price: Double): String = when {
        level == null -> activity.getString(R.string.paper_detail_level_none)
        leaves == null -> PaperText.money(level)
        else -> activity.getString(R.string.paper_detail_level, PaperText.money(level), Cr360Format.signed((level / price - 1.0) * 100.0, 1) + "%", unbroken(PaperText.signed(leaves)))
    }

    /** "−₹629.10" kept on one line: a word joiner stops the line breaking between the sign and the rupee. */
    private fun unbroken(amount: String): String = amount.replace("₹", "\u2060₹")

    /** A card titled [title] whose [rows] are label–value lines. */
    private fun section(@StringRes title: Int, rows: LinearLayout.() -> Unit) {
        val card = ItemHelpBinding.inflate(activity.layoutInflater, ui.listItems, true)
        card.helpTitle.setText(title)
        card.helpBody.isVisible = false
        (card.helpBody.parent as LinearLayout).rows()
    }

    private fun LinearLayout.row(@StringRes label: Int, value: String, @ColorRes tone: Int? = null) {
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        line.addView(
            TextView(context).apply {
                setText(label)
                textSize = 13f
                setTextColor(color(R.color.text_secondary))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        line.addView(
            TextView(context).apply {
                text = value
                textSize = 13.5f
                gravity = Gravity.END
                setTextColor(color(tone ?: R.color.text_primary))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f).apply { marginStart = (12 * dp).roundToInt() },
        )
        addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * dp).roundToInt() })
    }

    private fun LinearLayout.note(text: String, @ColorRes tone: Int) {
        addView(
            TextView(context).apply {
                this.text = text
                textSize = 12.5f
                setTextColor(color(tone))
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (6 * dp).roundToInt() },
        )
    }

    private fun heading(@StringRes text: Int) {
        ui.listItems.addView(
            TextView(activity).apply {
                setText(text)
                setTextColor(color(R.color.text_primary))
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (16 * dp).roundToInt() },
        )
    }

    private fun button(@StringRes text: Int, onClick: () -> Unit) {
        ui.listItems.addView(
            MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                setText(text)
                isAllCaps = false
                setOnClickListener { onClick() }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * dp).roundToInt() },
        )
    }

    @ColorRes
    private fun tone(value: Double): Int = if (value >= 0.0) R.color.bull else R.color.bear

    private fun color(@ColorRes id: Int): Int = ContextCompat.getColor(activity, id)

    private companion object {
        val WHEN: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm")
    }
}

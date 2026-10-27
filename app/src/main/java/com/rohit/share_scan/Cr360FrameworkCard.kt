package com.rohit.share_scan

import android.app.Activity
import android.graphics.Typeface
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.rohit.share_scan.Cr360Book.Fact
import com.rohit.share_scan.Cr360Framework.Flag
import com.rohit.share_scan.Cr360Framework.Model
import com.rohit.share_scan.Cr360Framework.Penalty
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The valuation framework's part of the R/S card ([Cr360Framework]): the intrinsic value and buy price, the model and
 * margin of safety, where the price stands, and the flags, a line each; and, opened, the full calculation behind them.
 * The operator red flag comes once the stock's news has ([news]).
 */
class Cr360FrameworkCard(
    private val activity: Activity,
    private val framework: Cr360Framework,
    private val stock: ScanResult,
) {
    private lateinit var flagsBox: LinearLayout
    private var news: List<NewsItem>? = null

    /** Fills [box]: the summary, then the flags (refilled when the news comes). */
    fun bind(box: LinearLayout) {
        box.removeAllViews()
        val fw = framework
        val value = fw.value
        val buy = fw.buyPrice
        if (value != null) {
            box.addView(view(if (buy != null) get(R.string.fw_value_line, price(value), price(buy)) else get(R.string.fw_value_only, price(value)), 15f).apply {
                setTypeface(typeface, Typeface.BOLD)
            })
        }
        val margin = fw.marginPct
        val size = sizeLabel()
        box.addView(
            view(if (margin != null && size != null) get(R.string.fw_summary, modelName(fw.model), num(margin, 1), size) else modelName(fw.model), 12f, R.color.text_secondary),
        )
        fw.fromBuy(stock.price)?.let { gap ->
            val under = gap <= 0
            box.addView(view(get(if (under) R.string.fw_price_below else R.string.fw_price_above, price(stock.price), num(abs(gap), 1)), 13f, if (under) R.color.bull else R.color.warn))
        }
        flagsBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        box.addView(flagsBox)
        fillFlags()
    }

    /** The stock's news came ([items]; null when it could not be read): the operator red flag is judged now. */
    fun news(items: List<NewsItem>?) {
        news = items
        if (::flagsBox.isInitialized) fillFlags()
    }

    private val redFlag: Boolean get() = Cr360Framework.operatorRedFlag(stock.rvol, news, System.currentTimeMillis())

    private fun fillFlags() {
        val box = flagsBox
        box.removeAllViews()
        val fw = framework
        val red = redFlag
        fun line(@StringRes id: Int, @ColorRes tone: Int, vararg args: Any) = box.addView(view(get(id, *args), 13f, tone))
        if (fw.has(Flag.REJECT)) line(R.string.fw_flag_reject, R.color.bear, fact(Fact.PLEDGE, 1))
        if (fw.has(Flag.HIGH_RISK)) line(R.string.fw_flag_high_risk, R.color.bear, fact(Fact.INTEREST_COVER, 2))
        if (fw.has(Flag.MANIPULATION)) line(R.string.fw_flag_manipulation, R.color.bear, fact(Fact.CASH_TO_PROFIT, 2))
        if (fw.has(Flag.LOW_ROA)) line(R.string.fw_flag_low_roa, R.color.bear, fact(Fact.ROA, 2))
        if (red) line(R.string.fw_flag_red, R.color.bear, num(stock.rvol, 1))
        if (fw.blocked(red)) {
            box.addView(view(get(R.string.fw_flag_blocked), 13f, R.color.bear).apply { setTypeface(typeface, Typeface.BOLD) })
        }
        if (fw.best(red)) {
            box.addView(view(get(R.string.fw_flag_best), 13f, R.color.bull).apply { setTypeface(typeface, Typeface.BOLD) })
        }
        if (fw.has(Flag.BUY_ZONE)) line(R.string.fw_flag_buy_zone, R.color.bull, rupees(Fact.EMA_200), rupees(Fact.GAP_LOW), rupees(Fact.GAP_HIGH))
        if (fw.has(Flag.WEEKLY_SWEEP)) fw[Fact.SWEPT_LOW]?.let { line(R.string.fw_flag_sweep, R.color.bull, price(it)) }
        if (fw.has(Flag.CYCLICAL_WARNING)) line(R.string.fw_flag_cyclical, R.color.warn)
        if (fw.has(Flag.PEG_LOW)) line(R.string.fw_flag_peg_low, R.color.bull, fact(Fact.PEG, 2))
        if (fw.has(Flag.PEG_HIGH)) line(R.string.fw_flag_peg_high, R.color.warn, fact(Fact.PEG, 2))
        if (fw.penalties.isNotEmpty()) {
            val names = fw.penalties.map {
                get(
                    when (it) {
                        Penalty.AUDITOR -> R.string.fw_penalty_auditor
                        Penalty.DEBT -> R.string.fw_penalty_debt
                        Penalty.INSTITUTIONS -> R.string.fw_penalty_institutions
                        Penalty.ROCE -> R.string.fw_penalty_roce
                    },
                )
            }
            line(R.string.fw_penalties, R.color.warn, names.joinToString(", "))
        }
        box.isVisible = box.childCount > 0
    }

    /**
     * The full calculation, into [body]: the model's working, the penalties, the margin of safety, the screen, the
     * chart; under its own heading unless it opens from the line that already says so ([titled] false).
     */
    fun calculation(body: LinearLayout, titled: Boolean = true) {
        val fw = framework
        fun line(text: String, @ColorRes tone: Int = R.color.text_primary) = body.addView(view("• $text", 13f, tone))
        fun heading(@StringRes id: Int) = body.addView(view(get(id), 13f).apply { setTypeface(typeface, Typeface.BOLD) })
        val raw = fw.rawValue

        if (titled) heading(R.string.fw_open)
        line(get(R.string.fw_calc_model, modelName(fw.model), stock.sector.ifEmpty { modelName(fw.sectorModel) }))
        if (fw.model != fw.sectorModel) line(get(R.string.fw_calc_fallback, modelName(fw.sectorModel)), R.color.warn)
        when (fw.model) {
            Model.BANK -> {
                val book = fw[Fact.BOOK_VALUE]
                val adjusted = fw[Fact.ADJUSTED_BOOK]
                val npa = fw[Fact.NET_NPA]
                if (book != null && adjusted != null && npa != null) {
                    line(get(R.string.fw_calc_bank_book, price(book), price(book - adjusted), num(npa, 2), price(adjusted)))
                } else if (book != null) {
                    line(get(R.string.fw_calc_bank_no_npa, price(book)))
                }
                val pb = fw[Fact.PRICE_TO_BOOK]
                if (pb != null && adjusted != null && raw != null) {
                    val id = if (fw[Fact.SECTOR_PB] == 1.0) R.string.fw_calc_bank_pb_sector else R.string.fw_calc_bank_pb
                    line(get(id, num(pb, 2), price(adjusted), price(raw)))
                }
                fw[Fact.ROA]?.let { line(get(R.string.fw_calc_roa, num(it, 2)), if (it < 1.0) R.color.bear else R.color.text_primary) }
            }
            Model.ASSET, Model.CYCLICAL -> {
                val own = fw[Fact.EV_EBITDA_OWN]
                val now = fw[Fact.EV_EBITDA_NOW]
                if (own != null) line(if (now != null) get(R.string.fw_calc_ev_multiple, num(own, 2), num(now, 2)) else get(R.string.fw_calc_ev_multiple_only, num(own, 2)))
                val ebitda = fw[Fact.EBITDA_CR]
                val debt = fw[Fact.DEBT_CR]
                val shares = fw[Fact.SHARES_CR]
                if (ebitda != null) line(get(if (fw.model == Model.CYCLICAL) R.string.fw_calc_ebitda_mid else R.string.fw_calc_ebitda, crore(ebitda)))
                if (own != null && ebitda != null && debt != null && shares != null && shares > 0) {
                    val before = (own * ebitda - debt) / shares
                    line(get(R.string.fw_calc_ev_value, num(own, 2), crore(ebitda), crore(debt), Cr360Format.amount(shares), price(before)))
                    line(get(R.string.fw_calc_ev_note), R.color.text_secondary)
                    fw[Fact.ROCE]?.let { roce ->
                        if (Penalty.ROCE in fw.penalties) line(get(R.string.fw_calc_roce_cut, num(roce, 1), price(raw ?: before * 0.8)), R.color.warn)
                        else line(get(R.string.fw_calc_roce_ok, num(roce, 1)))
                    }
                }
            }
            Model.CASH -> {
                val pe = fw[Fact.PE]
                val growth = fw[Fact.EPS_GROWTH]
                val peg = fw[Fact.PEG]
                if (pe != null && growth != null && peg != null) line(get(R.string.fw_calc_peg, num(pe, 1), num(growth, 1), num(peg, 2)))
                val fair = fw[Fact.FAIR_PE]
                val cap = fw[Fact.PE_CAP]
                val base = fw[Fact.BASE_PE]
                if (fair != null && cap != null) {
                    line(
                        if (base != null) {
                            val which = get(if (fw[Fact.SECTOR_PE] == 1.0) R.string.fw_calc_peg_sector else R.string.fw_calc_peg_own)
                            get(R.string.fw_calc_peg_fair, which, num(base, 1), num(cap, 1), num(fair, 1))
                        } else {
                            get(R.string.fw_calc_peg_band, num(fair, 1))
                        },
                    )
                    raw?.let { line(get(R.string.fw_calc_peg_value, price(it / fair), num(fair, 1), price(it))) }
                }
            }
            Model.GENERAL -> {
                if (fw.sectorModel == Model.CASH) fw[Fact.EPS_GROWTH]?.let { line(get(R.string.fw_calc_peg_slow, num(it, 1))) }
                raw?.let { line(get(R.string.fw_calc_general, price(it))) }
            }
        }

        // The penalties, then the margin of safety.
        val value = fw.value
        val after = fw.penalties - Penalty.ROCE
        if (raw != null && value != null) {
            if (after.isEmpty()) {
                line(get(R.string.fw_calc_no_penalties, price(value)))
            } else {
                if (Penalty.AUDITOR in after) line(get(R.string.fw_calc_penalty_auditor, fw.auditorResigned?.let { day(it) } ?: "—"), R.color.bear)
                if (Penalty.DEBT in after) line(get(R.string.fw_calc_penalty_debt), R.color.warn)
                if (Penalty.INSTITUTIONS in after) line(get(R.string.fw_calc_penalty_institutions), R.color.warn)
                line(get(R.string.fw_calc_after_penalties, price(raw), num(fw.penaltyFactor, 3), price(value)))
            }
        }
        val margin = fw.marginPct
        val buy = fw.buyPrice
        if (value != null && margin != null && buy != null) {
            line(get(R.string.fw_calc_margin, num(margin, 1), sizeLabel() ?: "", price(value), num(1 - margin / 100, 3), price(buy)))
            fw.fromBuy(stock.price)?.let { gap ->
                line(get(if (gap <= 0) R.string.fw_price_below else R.string.fw_price_above, price(stock.price), num(abs(gap), 1)), if (gap <= 0) R.color.bull else R.color.warn)
            }
        }

        heading(R.string.fw_calc_screen)
        line(fw[Fact.PLEDGE]?.let { get(R.string.fw_calc_pledge, num(it, 2)) } ?: get(R.string.fw_calc_pledge_none), if (fw.has(Flag.REJECT)) R.color.bear else R.color.text_primary)
        if (fw.sectorModel == Model.BANK) {
            line(get(R.string.fw_calc_lender), R.color.text_secondary)
        } else {
            fw[Fact.INTEREST_COVER]?.let { line(get(R.string.fw_calc_icr, num(it, 2)), if (fw.has(Flag.HIGH_RISK)) R.color.bear else R.color.text_primary) }
            fw[Fact.CASH_TO_PROFIT]?.let { line(get(R.string.fw_calc_cash, num(it, 2)), if (fw.has(Flag.MANIPULATION)) R.color.bear else R.color.text_primary) }
        }
        if (redFlag) line(get(R.string.fw_flag_red, num(stock.rvol, 1)), R.color.bear)

        heading(R.string.fw_calc_chart)
        fw[Fact.EMA_200]?.let { line(get(if (stock.price > it) R.string.fw_calc_ema_above else R.string.fw_calc_ema_below, price(it))) }
        val low = fw[Fact.GAP_LOW]
        val high = fw[Fact.GAP_HIGH]
        line(if (low != null && high != null) get(R.string.fw_calc_gap, price(low), price(high)) else get(R.string.fw_calc_no_gap))
        line(fw[Fact.SWEPT_LOW]?.let { get(R.string.fw_calc_sweep, price(it)) } ?: get(R.string.fw_calc_no_sweep))
        line(get(R.string.fw_calc_best_rule), if (fw.best(redFlag)) R.color.bull else R.color.text_secondary)
    }

    private fun sizeLabel(): String? = framework.size?.let {
        get(
            when (it) {
                Cr360Framework.Size.LARGE -> R.string.fw_size_large
                Cr360Framework.Size.MID -> R.string.fw_size_mid
                Cr360Framework.Size.SMALL -> R.string.fw_size_small
            },
        )
    }

    private fun modelName(model: Model): String = get(
        when (model) {
            Model.GENERAL -> R.string.fw_model_general
            Model.BANK -> R.string.fw_model_bank
            Model.ASSET -> R.string.fw_model_asset
            Model.CYCLICAL -> R.string.fw_model_cyclical
            Model.CASH -> R.string.fw_model_cash
        },
    )

    /** A fact's rupee figure as prices read ("1,364.77"); "—" when missing. */
    private fun rupees(key: Fact): String = framework[key]?.let { price(it) } ?: "—"

    private fun fact(key: Fact, digits: Int): String = framework[key]?.let { num(it, digits) } ?: "—"

    private fun get(@StringRes id: Int, vararg args: Any): String = activity.getString(id, *args)

    private fun num(value: Double, digits: Int): String = Cr360Format.number(value, digits)

    private fun price(value: Double): String = StockFormat.price(value)

    /** "₹1,51,612 Cr"; under ₹100 Cr to a decimal. */
    private fun crore(value: Double): String = get(R.string.fw_cr, Cr360Format.amount(value))

    private fun day(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US))

    private fun view(text: CharSequence, size: Float, @ColorRes tone: Int = R.color.text_primary): TextView = TextView(activity).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(ContextCompat.getColor(activity, tone))
        fontFeatureSettings = "tnum"
        setLineSpacing(3 * resources.displayMetrics.density, 1f)
        setPadding(0, (2 * resources.displayMetrics.density).roundToInt(), 0, 0)
    }
}

package com.rohit.share_scan

import android.content.Context
import android.content.res.ColorStateList
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.rohit.share_scan.databinding.ItemStockBinding
import com.rohit.share_scan.databinding.ItemStockCompactBinding
import java.util.Locale
import kotlin.math.roundToInt

class StockAdapter(
    private val onOpen: (ScanResult) -> Unit,
    private val onStar: (ScanResult) -> Unit = {},
    /** The card's 📰 mark: the stock on its खबरें tab. */
    private val onNews: (ScanResult) -> Unit = onOpen,
) : ListAdapter<RankedStock, StockAdapter.Holder>(Diff) {

    /** Every stock's 360CR ([Cr360Book]); the card adds the stock's score to its meta line. */
    var cr360: Cr360Book = Cr360Book.EMPTY

    /** The learned setups ([SetupBook]); once there are some, the card names the stock's in place of the app's own. */
    var setups: SetupBook = SetupBook.EMPTY

    /** Each stock's 📰 mark: its exchange filings of the last day ([NewsRepository.badges]). */
    var news: Map<String, NewsBadge> = emptyMap()

    /** One line a stock in a table (Settings → Compact list) rather than a card; set before the list is shown. */
    var compact: Boolean = false

    /** A card ([card]) or a compact table line ([row]). */
    class Holder(root: View, val card: ItemStockBinding?, val row: ItemStockCompactBinding?) : RecyclerView.ViewHolder(root) {
        /** The tier whose colour stains the rank circle's and tier pill's glass, so a rebind keeps the same glass. */
        var glassTier: StockFormat.Tier? = null
    }

    override fun getItemViewType(position: Int): Int = if (compact) ROW else CARD

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val inflater = LayoutInflater.from(parent.context)
        val holder = if (viewType == ROW) {
            val row = ItemStockCompactBinding.inflate(inflater, parent, false)
            Holder(row.root, null, row)
        } else {
            val card = ItemStockBinding.inflate(inflater, parent, false)
            card.root.wearCardGlass()
            Holder(card.root, card, null)
        }
        fun stockAt(): ScanResult? = holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { getItem(it).stock }
        holder.itemView.setOnClickListener { stockAt()?.let(onOpen) }
        (holder.card?.newsBadge ?: holder.row!!.rowNews).setOnClickListener { stockAt()?.let(onNews) }
        (holder.card?.star ?: holder.row!!.rowStar).setOnClickListener { stockAt()?.let(onStar) }
        // A table line shows a star only once its stock is on the watchlist; a long press adds or removes it.
        holder.row?.root?.setOnLongClickListener {
            val stock = stockAt() ?: return@setOnLongClickListener false
            onStar(stock)
            true
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        holder.row?.let {
            bindRow(it, item)
            return
        }
        val stock = item.stock
        val ui = holder.card!!
        val context = ui.root.context
        fun color(id: Int) = ContextCompat.getColor(context, id)

        val tier = StockFormat.tier(stock.score)
        val (tierLabel, tierColor) = when (tier) {
            StockFormat.Tier.STRONG -> R.string.tier_strong to R.color.bull
            StockFormat.Tier.WATCH -> R.string.tier_watch to R.color.warn
            StockFormat.Tier.WEAK -> R.string.tier_weak to R.color.neutral
        }
        if (holder.glassTier != tier) {
            holder.glassTier = tier
            ui.rank.wearChipGlass(null, tintBoost = 1.5f, accent = color(tierColor))
            ui.tier.wearChipGlass(null, tintBoost = 1.5f, accent = color(tierColor))
        }
        val score = StockFormat.score(stock.score)

        val ranked = item.rank != RankedStock.UNRANKED
        // A starred stock outside the filters or this scan has no place in the list: its circle shows a star.
        ui.rank.text = if (ranked) item.rank.toString() else context.getString(R.string.item_rank_none)
        ui.rank.setTextColor(color(tierColor))

        ui.symbol.text = stock.symbol
        val name = listOf(StockSearch.shortName(stock.name), stock.sector).filter { it.isNotEmpty() }.joinToString(" · ")
        ui.companyName.isVisible = name.isNotEmpty()
        ui.companyName.text = name
        val exchange = MarketStyle.of(stock.market)
        ui.exchange.setText(exchange.label)
        ui.exchange.setTextColor(color(exchange.text))
        ui.exchange.backgroundTintList = ColorStateList.valueOf(color(exchange.container))
        bindRankBadge(ui.rankBadge, item.badge)
        val newsDescription = bindNewsBadge(ui.newsBadge, news[stock.symbol])
        ui.change.text = context.getString(R.string.item_change, StockFormat.change(stock.change5d))
        ui.change.setTextColor(color(if (stock.change5d >= 0) R.color.bull else R.color.bear))
        val meta = SpannableStringBuilder(
            context.getString(
                R.string.item_meta,
                stock.marketCapCr?.let(StockFormat::crore) ?: context.getString(R.string.stat_empty),
                StockFormat.shares(stock.volume),
            ),
        )
        val research = cr360.of(stock.symbol)?.takeIf { it.score != null }
        val researchDescription = research?.let { entry ->
            val score = String.format(Locale.US, "%.0f", entry.score)
            meta.append(" · ")
            val start = meta.length
            meta.append(context.getString(R.string.item_cr360, score))
            meta.setSpan(ForegroundColorSpan(color(entry.state.tone)), start, meta.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            context.getString(R.string.item_cr360_description, score, context.getString(entry.state.label))
        }
        ui.meta.text = meta
        ui.price.text = context.getString(R.string.item_price, StockFormat.price(stock.price))

        val spike = stock.rvol >= StockFormat.SPIKE_RVOL
        // A live session's RVOL is its volume so far scaled to a full day: an estimate, marked ≈.
        ui.rvol.text = context.getString(if (stock.live) R.string.item_rvol_live else R.string.item_rvol, StockFormat.rvol(stock.rvol))
        ui.rvol.setTextColor(color(if (spike) R.color.warn else R.color.neutral))
        ui.rvol.backgroundTintList =
            ColorStateList.valueOf(color(if (spike) R.color.warn_container else R.color.neutral_container))

        ui.tier.setText(tierLabel)
        ui.tier.setTextColor(color(tierColor))

        ui.scoreBar.color = color(tierColor)
        ui.scoreBar.fraction = (stock.score / 100).toFloat()
        ui.score.text = score
        ui.score.setTextColor(color(tierColor))

        // A floor of three months or more; one that held six months is green, as the Support 6M chip lists it.
        val floor = stock.support?.takeIf { it.ageSessions >= SupportFloors.LONG_AGE }
        ui.supportLine.isVisible = floor != null
        val supportDescription = floor?.let {
            val age = StockFormat.supportAge(it)
            ui.supportLine.text = context.getString(R.string.item_support, StockFormat.price(it.level), age, it.touches)
            ui.supportLine.setTextColor(color(if (it.long) R.color.bull else R.color.text_secondary))
            context.getString(R.string.item_support_description, StockFormat.price(it.level), age, it.touches)
        }

        val setupDescription = if (setups.rules.isNotEmpty()) {
            // A learned setup takes the place of the app's own: the best one whose conditions all hold today.
            // Else the best rally setup: what came before stocks went up.
            val learned = setups.best(stock.setupMask)
            val rally = if (learned == null) setups.rallySetup(stock.setupMask) else null
            ui.setupLine.isVisible = learned != null || rally != null
            when {
                learned != null -> context.getString(
                    R.string.item_setup_learned,
                    SetupBook.percent(setups.shown(learned)),
                    SetupLabels.describe(context, learned.conditions),
                )
                rally != null -> context.getString(R.string.item_setup_rally, SetupBook.percent(rally.all), SetupLabels.describe(context, rally.conditions))
                else -> null
            }?.also { text -> ui.setupLine.text = text }
        } else {
            val setup = stock.structure?.let { TradePlan.of(stock.price, it) }?.let { BreakoutSetup.of(stock, it) }
            val highProbability = setup == BreakoutSetup.Kind.HIGH_PROBABILITY
            ui.setupLine.isVisible = highProbability
            if (highProbability) {
                context.getString(R.string.item_setup_high, StockFormat.rvol(stock.rvol)).also { ui.setupLine.text = it }
            } else {
                null
            }
        }

        // A mistake learned from trades that went the wrong way, whose conditions all hold today.
        val mistake = setups.mistake(stock.setupMask)
        ui.mistakeLine.isVisible = mistake != null
        val mistakeDescription = mistake?.let {
            context.getString(R.string.item_setup_mistake, SetupBook.percent(it.all), SetupLabels.describe(context, it.conditions))
                .also { text -> ui.mistakeLine.text = text }
        }

        // A starred stock no chart has come in for yet: its symbol, and a word that its prices are on their way.
        val charted = stock.charted
        ui.rvol.isVisible = charted
        ui.tier.isVisible = charted
        // Without a chart the exchange is not known either.
        ui.exchange.isVisible = charted
        if (!charted) {
            ui.change.setText(R.string.item_no_data)
            ui.change.setTextColor(color(R.color.neutral))
            ui.meta.setText(R.string.item_no_data_meta)
            ui.price.text = context.getString(R.string.stat_empty)
            ui.score.text = context.getString(R.string.stat_empty)
            ui.score.setTextColor(color(R.color.neutral))
        }

        ui.star.setImageResource(if (item.starred) R.drawable.ic_star else R.drawable.ic_star_outline)
        ImageViewCompat.setImageTintList(
            ui.star,
            ColorStateList.valueOf(color(if (item.starred) R.color.warn else R.color.text_secondary)),
        )
        ui.star.contentDescription =
            context.getString(if (item.starred) R.string.list_star_remove else R.string.list_star_add, stock.symbol)

        ui.root.contentDescription = listOfNotNull(
            when {
                !charted -> context.getString(R.string.item_description_no_data, stock.symbol)
                ranked -> context.getString(R.string.item_description, item.rank, stock.symbol, score)
                else -> context.getString(R.string.item_description_unranked, stock.symbol, score)
            },
            context.getString(exchange.description),
            supportDescription,
            setupDescription,
            mistakeDescription,
            researchDescription,
            newsDescription,
            badgeDescription(context, item.badge),
            if (item.starred) context.getString(R.string.list_starred_description) else null,
        ).joinToString(", ")
    }

    /**
     * A compact table line: rank, ticker with its exchange, rank-move and 📰 marks and the company under it, the
     * price, the 5-day change with RVOL under it, and the score. As the redesign draws it, the numbers are plain
     * white, the change green or red and RVOL muted; the score turns gold, with a ★, for the watchlist's stocks. The
     * rest of the card (setups, support, R/S, market cap) is in the stock's detail, a tap away.
     */
    private fun bindRow(ui: ItemStockCompactBinding, item: RankedStock) {
        val stock = item.stock
        val context = ui.root.context
        fun color(id: Int) = ContextCompat.getColor(context, id)
        val ranked = item.rank != RankedStock.UNRANKED
        ui.rowRank.text = if (ranked) context.getString(R.string.compact_rank, item.rank) else context.getString(R.string.item_rank_none)
        ui.rowSymbol.text = stock.symbol
        ui.rowCompany.text = StockSearch.shortName(stock.name).ifEmpty { stock.sector }
        val exchange = MarketStyle.of(stock.market)
        ui.rowExchange.setText(exchange.label)
        ui.rowExchange.setTextColor(color(exchange.text))
        ui.rowExchange.backgroundTintList = ColorStateList.valueOf(color(exchange.container))
        bindRankBadge(ui.rowBadge, item.badge)
        val badge = news[stock.symbol]
        ui.rowNews.isVisible = badge != null
        val newsDescription = badge?.let {
            context.getString(R.string.item_news_description, it.count, context.getString(NewsLabels.label(it.kind)))
        }
        val score = StockFormat.score(stock.score)
        val charted = stock.charted
        ui.rowExchange.isVisible = charted
        if (charted) {
            ui.rowPrice.text = context.getString(R.string.item_price, StockFormat.price(stock.price))
            ui.rowChange.text = StockFormat.signedChange(stock.change5d)
            ui.rowChange.setTextColor(color(if (stock.change5d >= 0) R.color.bull else R.color.bear))
            ui.rowRvol.text = context.getString(if (stock.live) R.string.item_rvol_live else R.string.item_rvol, StockFormat.rvol(stock.rvol))
            ui.rowRvol.setTextColor(color(R.color.text_secondary))
            ui.rowScore.text = score
            ui.rowScore.setTextColor(color(if (item.starred) R.color.warn else R.color.text_primary))
        } else {
            val empty = context.getString(R.string.stat_empty)
            ui.rowPrice.text = empty
            ui.rowChange.text = empty
            ui.rowChange.setTextColor(color(R.color.neutral))
            ui.rowRvol.setText(R.string.compact_no_data)
            ui.rowRvol.setTextColor(color(R.color.text_secondary))
            ui.rowScore.text = empty
            ui.rowScore.setTextColor(color(R.color.neutral))
        }
        ui.rowStar.isVisible = item.starred
        ui.rowStar.contentDescription = context.getString(R.string.list_star_remove, stock.symbol)
        // The score keeps to the column's edge, or makes room for the star.
        ui.rowScore.updatePadding(right = if (item.starred) 0 else (SCORE_EDGE_DP * context.resources.displayMetrics.density).roundToInt())
        ViewCompat.replaceAccessibilityAction(
            ui.root,
            AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK,
            context.getString(if (item.starred) R.string.list_star_remove else R.string.list_star_add, stock.symbol),
            null,
        )
        ui.root.contentDescription = listOfNotNull(
            when {
                !charted -> context.getString(R.string.item_description_no_data, stock.symbol)
                ranked -> context.getString(R.string.item_description, item.rank, stock.symbol, score)
                else -> context.getString(R.string.item_description_unranked, stock.symbol, score)
            },
            context.getString(exchange.description),
            newsDescription,
            badgeDescription(context, item.badge),
            if (item.starred) context.getString(R.string.list_starred_description) else null,
        ).joinToString(", ")
    }

    /** "NEW" for a fresh top-100 entry, a green ▲ or red ▼ for a rank move, hidden otherwise. */
    private fun bindRankBadge(view: TextView, badge: RankBadge?) {
        val context = view.context
        val (text, textColor, container) = when (badge) {
            null -> {
                view.isVisible = false
                return
            }
            RankBadge.New -> Triple(context.getString(R.string.list_rank_new), R.color.brand_primary, R.color.primary_container)
            is RankBadge.Moved -> if (badge.places > 0) {
                Triple(context.getString(R.string.list_rank_up, badge.places), R.color.bull, R.color.bull_container)
            } else {
                Triple(context.getString(R.string.list_rank_down, -badge.places), R.color.bear, R.color.bear_container)
            }
        }
        view.isVisible = true
        view.text = text
        view.setTextColor(ContextCompat.getColor(context, textColor))
        view.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, container))
    }

    /** "📰 नतीजे", or "📰 नतीजे +2" with more filings; returns what a screen reader says for it. */
    private fun bindNewsBadge(view: TextView, badge: NewsBadge?): String? {
        view.isVisible = badge != null
        badge ?: return null
        val context = view.context
        val label = context.getString(NewsLabels.label(badge.kind))
        view.text = if (badge.count > 1) {
            context.getString(R.string.item_news_badge_more, label, badge.count - 1)
        } else {
            context.getString(R.string.item_news_badge, label)
        }
        val (text, container) = NewsLabels.colors(badge.kind)
        view.setTextColor(ContextCompat.getColor(context, text))
        view.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, container))
        return context.getString(R.string.item_news_description, badge.count, label)
    }

    private fun badgeDescription(context: Context, badge: RankBadge?): String? = when (badge) {
        null -> null
        RankBadge.New -> context.getString(R.string.list_rank_new_description, RankHistory.TOP)
        is RankBadge.Moved -> if (badge.places > 0) {
            context.getString(R.string.list_rank_up_description, badge.places)
        } else {
            context.getString(R.string.list_rank_down_description, -badge.places)
        }
    }

    private companion object {
        const val CARD = 0
        const val ROW = 1

        /** The score's gap to a table line's edge, as the SCORE column name keeps (compactColumns). */
        const val SCORE_EDGE_DP = 8
    }

    private object Diff : DiffUtil.ItemCallback<RankedStock>() {
        override fun areItemsTheSame(oldItem: RankedStock, newItem: RankedStock) =
            oldItem.stock.symbol == newItem.stock.symbol

        override fun areContentsTheSame(oldItem: RankedStock, newItem: RankedStock) = oldItem == newItem
    }
}

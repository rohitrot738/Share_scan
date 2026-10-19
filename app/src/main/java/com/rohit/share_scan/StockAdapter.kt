package com.rohit.share_scan

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.rohit.share_scan.databinding.ItemStockBinding

class StockAdapter(
    private val onOpen: (ScanResult) -> Unit,
    private val onStar: (ScanResult) -> Unit = {},
) : ListAdapter<RankedStock, StockAdapter.Holder>(Diff) {

    class Holder(val ui: ItemStockBinding) : RecyclerView.ViewHolder(ui.root) {
        /** The tier whose colour stains the rank circle's and tier pill's glass, so a rebind keeps the same glass. */
        var glassTier: StockFormat.Tier? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val holder = Holder(ItemStockBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        holder.ui.root.wearCardGlass()
        holder.ui.root.setOnClickListener {
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) onOpen(getItem(position).stock)
        }
        holder.ui.star.setOnClickListener {
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) onStar(getItem(position).stock)
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        val stock = item.stock
        val ui = holder.ui
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
        ui.change.text = context.getString(R.string.item_change, StockFormat.change(stock.change5d))
        ui.change.setTextColor(color(if (stock.change5d >= 0) R.color.bull else R.color.bear))
        ui.meta.text = context.getString(
            R.string.item_meta,
            stock.marketCapCr?.let(StockFormat::crore) ?: context.getString(R.string.stat_empty),
            StockFormat.shares(stock.volume),
        )
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

    private fun badgeDescription(context: Context, badge: RankBadge?): String? = when (badge) {
        null -> null
        RankBadge.New -> context.getString(R.string.list_rank_new_description, RankHistory.TOP)
        is RankBadge.Moved -> if (badge.places > 0) {
            context.getString(R.string.list_rank_up_description, badge.places)
        } else {
            context.getString(R.string.list_rank_down_description, -badge.places)
        }
    }

    private object Diff : DiffUtil.ItemCallback<RankedStock>() {
        override fun areItemsTheSame(oldItem: RankedStock, newItem: RankedStock) =
            oldItem.stock.symbol == newItem.stock.symbol

        override fun areContentsTheSame(oldItem: RankedStock, newItem: RankedStock) = oldItem == newItem
    }
}

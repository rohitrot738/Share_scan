package com.rohit.share_scan

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.rohit.share_scan.databinding.ItemStockBinding
import kotlin.math.roundToInt

class StockAdapter(
    private val onOpen: (ScanResult) -> Unit,
) : ListAdapter<RankedStock, StockAdapter.Holder>(Diff) {

    class Holder(val ui: ItemStockBinding) : RecyclerView.ViewHolder(ui.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val holder = Holder(ItemStockBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        holder.ui.root.setOnClickListener {
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) onOpen(getItem(position).stock)
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        val stock = item.stock
        val ui = holder.ui
        val context = ui.root.context
        fun color(id: Int) = ContextCompat.getColor(context, id)

        val (tierLabel, tierColor, tierContainer) = when (StockFormat.tier(stock.score)) {
            StockFormat.Tier.STRONG -> Triple(R.string.tier_strong, R.color.bull, R.color.bull_container)
            StockFormat.Tier.WATCH -> Triple(R.string.tier_watch, R.color.warn, R.color.warn_container)
            StockFormat.Tier.WEAK -> Triple(R.string.tier_weak, R.color.neutral, R.color.neutral_container)
        }
        val score = StockFormat.score(stock.score)

        ui.rank.text = item.rank.toString()
        ui.rank.setTextColor(color(tierColor))
        ui.rank.backgroundTintList = ColorStateList.valueOf(color(tierContainer))

        ui.symbol.text = stock.symbol
        ui.change.text = context.getString(R.string.item_change, StockFormat.change(stock.change5d))
        ui.change.setTextColor(color(if (stock.change5d >= 0) R.color.bull else R.color.bear))
        ui.meta.text = context.getString(
            R.string.item_meta,
            stock.marketCapCr?.let(StockFormat::crore) ?: context.getString(R.string.stat_empty),
            StockFormat.shares(stock.volume),
        )
        ui.price.text = context.getString(R.string.item_price, StockFormat.price(stock.price))

        val spike = stock.rvol >= StockFormat.SPIKE_RVOL
        ui.rvol.text = context.getString(R.string.item_rvol, StockFormat.rvol(stock.rvol))
        ui.rvol.setTextColor(color(if (spike) R.color.warn else R.color.neutral))
        ui.rvol.backgroundTintList =
            ColorStateList.valueOf(color(if (spike) R.color.warn_container else R.color.neutral_container))

        ui.tier.setText(tierLabel)
        ui.tier.setTextColor(color(tierColor))
        ui.tier.backgroundTintList = ColorStateList.valueOf(color(tierContainer))

        ui.scoreBar.setIndicatorColor(color(tierColor))
        ui.scoreBar.setProgressCompat((stock.score * 10).roundToInt(), false)
        ui.score.text = score
        ui.score.setTextColor(color(tierColor))

        ui.root.contentDescription = context.getString(R.string.item_description, item.rank, stock.symbol, score)
    }

    private object Diff : DiffUtil.ItemCallback<RankedStock>() {
        override fun areItemsTheSame(oldItem: RankedStock, newItem: RankedStock) =
            oldItem.stock.symbol == newItem.stock.symbol

        override fun areContentsTheSame(oldItem: RankedStock, newItem: RankedStock) = oldItem == newItem
    }
}

package com.rohit.share_scan

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.animation.doOnEnd
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.ChipGroup
import com.rohit.share_scan.databinding.ItemStockAccordionBinding
import com.rohit.share_scan.databinding.ItemStockBentoBinding
import com.rohit.share_scan.databinding.ItemStockBinding
import com.rohit.share_scan.databinding.ItemStockCompactBinding
import com.rohit.share_scan.databinding.ItemStockSwipeBinding
import java.util.Locale
import kotlin.math.roundToInt

class StockAdapter(
    private val onOpen: (ScanResult) -> Unit,
    private val onStar: (ScanResult) -> Unit = {},
    /** The card's 📰 mark: the stock on its खबरें tab. */
    private val onNews: (ScanResult) -> Unit = onOpen,
    /** The accordion's and swipe card's Chart button: the chart site picked in Settings. */
    private val onChart: (ScanResult) -> Unit = onOpen,
) : ListAdapter<RankedStock, StockAdapter.Holder>(Diff) {

    /** Every stock's 360CR ([Cr360Book]); the card adds the stock's score to its meta line. */
    var cr360: Cr360Book = Cr360Book.EMPTY

    /** The learned setups ([SetupBook]); once there are some, the card names the stock's in place of the app's own. */
    var setups: SetupBook = SetupBook.EMPTY

    /** Each stock's 📰 mark: its exchange filings of the last day ([NewsRepository.badges]). */
    var news: Map<String, NewsBadge> = emptyMap()

    /** How a stock is drawn (Menu → Layout, or the background's own); set before the list is shown. */
    var style: ItemStyle = ItemStyle.CARD

    /** The accordion, bento and swipe cards wear the Space Glass background's plain look rather than glass. */
    var flat: Boolean = false

    /** The accordion card that is open, by its symbol; one at a time. */
    private var expanded: String? = null

    /** The swipe card slid open, closed when another opens. */
    private var openSwipe: SwipeRevealLayout? = null

    /** One stock in whichever [style] the list uses; the binding for that style is set, the others null. */
    class Holder(
        root: View,
        val card: ItemStockBinding? = null,
        val row: ItemStockCompactBinding? = null,
        val accordion: ItemStockAccordionBinding? = null,
        val bento: ItemStockBentoBinding? = null,
        val swipe: ItemStockSwipeBinding? = null,
    ) : RecyclerView.ViewHolder(root) {
        /** The tier whose colour stains the rank circle's and tier pill's glass, so a rebind keeps the same glass. */
        var glassTier: StockFormat.Tier? = null

        /** A running open or close of an accordion card. */
        var unfold: ValueAnimator? = null
    }

    override fun getItemViewType(position: Int): Int = style.ordinal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val inflater = LayoutInflater.from(parent.context)
        val holder = when (ItemStyle.entries[viewType]) {
            ItemStyle.ROW -> ItemStockCompactBinding.inflate(inflater, parent, false).let { Holder(it.root, row = it) }
            ItemStyle.CARD -> ItemStockBinding.inflate(inflater, parent, false).let {
                it.root.wearCardGlass()
                Holder(it.root, card = it)
            }
            ItemStyle.ACCORDION -> ItemStockAccordionBinding.inflate(inflater, parent, false).let {
                it.root.dress()
                if (flat) it.accScoreBar.wearFlat()
                Holder(it.root, accordion = it)
            }
            ItemStyle.BENTO -> ItemStockBentoBinding.inflate(inflater, parent, false).let {
                it.root.dress()
                Holder(it.root, bento = it)
            }
            ItemStyle.SWIPE -> ItemStockSwipeBinding.inflate(inflater, parent, false).let {
                it.swipeCard.dress()
                if (flat) it.swipeScoreBar.wearFlat()
                Holder(it.root, swipe = it)
            }
        }
        fun stockAt(): ScanResult? = holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { getItem(it).stock }
        val star = View.OnClickListener { stockAt()?.let(onStar) }
        val starOnLongPress = View.OnLongClickListener {
            val stock = stockAt() ?: return@OnLongClickListener false
            onStar(stock)
            true
        }
        holder.card?.let { ui ->
            ui.root.setOnClickListener { stockAt()?.let(onOpen) }
            ui.newsBadge.setOnClickListener { stockAt()?.let(onNews) }
            ui.star.setOnClickListener(star)
        }
        // A table line shows a star only once its stock is on the watchlist; a long press adds or removes it.
        holder.row?.let { ui ->
            ui.root.setOnClickListener { stockAt()?.let(onOpen) }
            ui.rowNews.setOnClickListener { stockAt()?.let(onNews) }
            ui.rowStar.setOnClickListener(star)
            ui.root.setOnLongClickListener(starOnLongPress)
        }
        holder.accordion?.let { ui ->
            ui.accHeader.setOnClickListener { holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(::toggle) }
            ui.accHeader.setOnLongClickListener(starOnLongPress)
            ui.accAlert.setOnClickListener(star)
            ui.accChart.setOnClickListener { stockAt()?.let(onChart) }
            ui.accDetails.setOnClickListener { stockAt()?.let(onOpen) }
        }
        holder.bento?.let { ui ->
            ui.root.setOnClickListener { stockAt()?.let(onOpen) }
            ui.root.setOnLongClickListener(starOnLongPress)
        }
        holder.swipe?.let { ui ->
            ui.swipeCard.setOnClickListener { stockAt()?.let(onOpen) }
            ui.swipeStar.setOnClickListener {
                ui.root.close()
                stockAt()?.let(onStar)
            }
            ui.swipeChart.setOnClickListener {
                ui.root.close()
                stockAt()?.let(onChart)
            }
            ui.root.onOpened = { opened ->
                if (openSwipe !== opened) openSwipe?.close()
                openSwipe = opened
            }
        }
        return holder
    }

    private fun MaterialCardView.dress() {
        if (flat) wearFlat() else wearCardGlass()
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        // Opening or closing an accordion card only unfolds it; the rest of the card stays as it is.
        val accordion = holder.accordion
        if (accordion != null && payloads.isNotEmpty() && payloads.all { it === UNFOLD }) {
            unfold(holder, accordion, getItem(position).stock.symbol == expanded, animate = true)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        holder.row?.let {
            bindRow(it, item)
            return
        }
        holder.accordion?.let {
            bindAccordion(holder, it, item)
            return
        }
        holder.bento?.let {
            bindBento(holder, it, item)
            return
        }
        holder.swipe?.let {
            bindSwipe(holder, it, item)
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

    /**
     * An accordion card: closed, one slim line (rank, ticker, price, score); open, the score bar with the 5-day change,
     * the stock's figures as capsules, its setup and the Alert, Chart and Details buttons.
     */
    private fun bindAccordion(holder: Holder, ui: ItemStockAccordionBinding, item: RankedStock) {
        val stock = item.stock
        val context = ui.root.context
        fun color(id: Int) = ContextCompat.getColor(context, id)
        val (tierLabel, tierColor, tierContainer) = tierColors(stock.score)
        val ranked = item.rank != RankedStock.UNRANKED
        val charted = stock.charted
        ui.accRank.text = if (ranked) item.rank.toString() else context.getString(R.string.item_rank_none)
        dressRank(holder, ui.accRank, StockFormat.tier(stock.score), tierColor, tierContainer)
        ui.accSymbol.text = stock.symbol
        bindExchange(ui.accExchange, stock.market, charted)
        ui.accStar.isVisible = item.starred
        val score = StockFormat.score(stock.score)
        ui.accPrice.text = if (charted) context.getString(R.string.item_price, StockFormat.price(stock.price)) else context.getString(R.string.stat_empty)
        ui.accScore.text = if (charted) score else context.getString(R.string.stat_empty)
        ui.accScore.setTextColor(color(if (charted) tierColor else R.color.neutral))
        ui.accScore.backgroundTintList = ColorStateList.valueOf(color(if (charted) tierContainer else R.color.neutral_container))

        ui.accCompany.text = listOf(StockSearch.shortName(stock.name), stock.sector).filter { it.isNotEmpty() }.joinToString(" · ")
        ui.accCompany.isVisible = ui.accCompany.text.isNotEmpty()
        ui.accTier.setText(tierLabel)
        ui.accTier.setTextColor(color(tierColor))
        ui.accTier.backgroundTintList = ColorStateList.valueOf(color(tierContainer))
        ui.accScoreBar.color = color(tierColor)
        ui.accScoreBar.fraction = (stock.score / 100).toFloat()
        ui.accChange.text = if (charted) StockFormat.signedChange(stock.change5d) else context.getString(R.string.stat_empty)
        ui.accChange.setTextColor(color(if (!charted) R.color.neutral else if (stock.change5d >= 0) R.color.bull else R.color.bear))
        bindMetrics(ui.accMetrics, stock)
        val setup = setupText(context, stock)
        ui.accSetup.isVisible = setup != null
        setup?.let { (text, tone) ->
            ui.accSetup.text = text
            ui.accSetup.setTextColor(color(tone))
        }
        ui.accAlert.setText(if (item.starred) R.string.layout_action_alert_on else R.string.layout_action_alert)
        ui.accAlert.contentDescription =
            context.getString(if (item.starred) R.string.list_star_remove else R.string.list_star_add, stock.symbol)
        ui.accChart.isEnabled = charted
        ui.accDetails.isEnabled = charted
        unfold(holder, ui, stock.symbol == expanded, animate = false)
        ui.root.contentDescription = description(context, item, score, charted)
    }

    /** Opens the accordion card at [position] and closes the one open before, or closes it when it is the open one. */
    private fun toggle(position: Int) {
        val symbol = getItem(position).stock.symbol
        val before = expanded?.let { open -> currentList.indexOfFirst { it.stock.symbol == open } } ?: -1
        expanded = if (expanded == symbol) null else symbol
        if (before >= 0 && before != position) notifyItemChanged(before, UNFOLD)
        notifyItemChanged(position, UNFOLD)
    }

    /** Shows or hides the card's body; [animate] grows or shrinks it with a little spring, its chevron turning. */
    private fun unfold(holder: Holder, ui: ItemStockAccordionBinding, open: Boolean, animate: Boolean) {
        holder.unfold?.cancel()
        holder.unfold = null
        val body = ui.accBody
        ui.accHeader.contentDescription = ui.root.context.getString(
            if (open) R.string.layout_collapse else R.string.layout_expand,
            ui.accSymbol.text,
        )
        val turn = if (open) -90f else 90f
        if (!animate || !ui.root.isAttachedToWindow || ui.root.width == 0) {
            body.isVisible = open
            body.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            body.requestLayout()
            ui.accChevron.rotation = turn
            return
        }
        if (body.isVisible == open && body.layoutParams.height == ViewGroup.LayoutParams.WRAP_CONTENT) {
            ui.accChevron.animate().rotation(turn).setDuration(UNFOLD_MS).start()
            return
        }
        val from = if (body.isVisible) body.height else 0
        body.measure(
            View.MeasureSpec.makeMeasureSpec(ui.root.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val to = if (open) body.measuredHeight else 0
        body.isVisible = true
        body.layoutParams.height = from
        body.requestLayout()
        ui.accChevron.animate().rotation(turn).setDuration(UNFOLD_MS).start()
        holder.unfold = ValueAnimator.ofInt(from, to).apply {
            duration = UNFOLD_MS
            if (open) interpolator = OvershootInterpolator(UNFOLD_SPRING)
            addUpdateListener {
                body.layoutParams.height = (it.animatedValue as Int).coerceAtLeast(0)
                body.requestLayout()
            }
            doOnEnd {
                body.isVisible = open
                body.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                body.requestLayout()
            }
            start()
        }
    }

    /**
     * A bento tile: rank and exchange over the ticker, a dial of the score, the company, the last [SPARK_SESSIONS]
     * closes as a line in the colour of their way, and the price with the 5-day change.
     */
    private fun bindBento(holder: Holder, ui: ItemStockBentoBinding, item: RankedStock) {
        val stock = item.stock
        val context = ui.root.context
        fun color(id: Int) = ContextCompat.getColor(context, id)
        val (_, tierColor, _) = tierColors(stock.score)
        val ranked = item.rank != RankedStock.UNRANKED
        val charted = stock.charted
        ui.bentoRank.text = if (ranked) context.getString(R.string.compact_rank, item.rank) else context.getString(R.string.item_rank_none)
        bindExchange(ui.bentoExchange, stock.market, charted)
        ui.bentoStar.isVisible = item.starred
        ui.bentoSymbol.text = stock.symbol
        ui.bentoCompany.text = StockSearch.shortName(stock.name).ifEmpty { stock.sector }
        val score = StockFormat.score(stock.score)
        ui.bentoDial.color = color(if (charted) tierColor else R.color.neutral)
        ui.bentoDial.score = if (charted) stock.score else 0.0
        ui.bentoScore.text = if (charted) stock.score.roundToInt().toString() else context.getString(R.string.stat_empty)
        ui.bentoScore.setTextColor(color(if (charted) tierColor else R.color.neutral))
        val closes = stock.structure?.closes?.takeLast(SPARK_SESSIONS).orEmpty()
        ui.bentoLine.values = closes
        ui.bentoLine.color = color(if ((closes.lastOrNull() ?: 0.0) >= (closes.firstOrNull() ?: 0.0)) R.color.bull else R.color.bear)
        ui.bentoLine.isInvisible = closes.size < 2
        ui.bentoPrice.text = if (charted) context.getString(R.string.item_price, StockFormat.price(stock.price)) else context.getString(R.string.stat_empty)
        ui.bentoChange.isVisible = charted
        ui.bentoChange.text = StockFormat.change(stock.change5d)
        ui.bentoChange.setTextColor(color(if (stock.change5d >= 0) R.color.bull else R.color.bear))
        ui.bentoChange.backgroundTintList = ColorStateList.valueOf(color(if (stock.change5d >= 0) R.color.bull_container else R.color.bear_container))
        ViewCompat.replaceAccessibilityAction(
            ui.root,
            AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK,
            context.getString(if (item.starred) R.string.list_star_remove else R.string.list_star_add, stock.symbol),
            null,
        )
        ui.root.contentDescription = description(context, item, score, charted)
    }

    /**
     * A swipe card: rank, ticker and company with the price and 5-day change, the figures as capsules, and the tier,
     * score bar and score. Slid to the left it shows ★ Watchlist and Chart behind it.
     */
    private fun bindSwipe(holder: Holder, ui: ItemStockSwipeBinding, item: RankedStock) {
        val stock = item.stock
        val context = ui.root.context
        fun color(id: Int) = ContextCompat.getColor(context, id)
        ui.root.close(animate = false)
        if (openSwipe === ui.root) openSwipe = null
        val (tierLabel, tierColor, tierContainer) = tierColors(stock.score)
        val ranked = item.rank != RankedStock.UNRANKED
        val charted = stock.charted
        ui.swipeRank.text = if (ranked) item.rank.toString() else context.getString(R.string.item_rank_none)
        dressRank(holder, ui.swipeRank, StockFormat.tier(stock.score), tierColor, tierContainer)
        ui.swipeSymbol.text = stock.symbol
        bindExchange(ui.swipeExchange, stock.market, charted)
        bindRankBadge(ui.swipeBadge, item.badge)
        ui.swipeStarMark.isVisible = item.starred
        ui.swipeCompany.text = listOf(StockSearch.shortName(stock.name), stock.sector).filter { it.isNotEmpty() }.joinToString(" · ")
        ui.swipePrice.text = if (charted) context.getString(R.string.item_price, StockFormat.price(stock.price)) else context.getString(R.string.stat_empty)
        ui.swipeChange.text = if (charted) StockFormat.change(stock.change5d) else context.getString(R.string.item_no_data)
        ui.swipeChange.setTextColor(color(if (!charted) R.color.neutral else if (stock.change5d >= 0) R.color.bull else R.color.bear))
        bindMetrics(ui.swipeMetrics, stock)
        ui.swipeTier.setText(tierLabel)
        ui.swipeTier.setTextColor(color(tierColor))
        ui.swipeTier.backgroundTintList = ColorStateList.valueOf(color(tierContainer))
        ui.swipeTier.isVisible = charted
        ui.swipeScoreBar.color = color(tierColor)
        ui.swipeScoreBar.fraction = (stock.score / 100).toFloat()
        val score = StockFormat.score(stock.score)
        ui.swipeScore.text = if (charted) score else context.getString(R.string.stat_empty)
        ui.swipeScore.setTextColor(color(if (charted) tierColor else R.color.neutral))
        // The round buttons behind: the star lit while the stock is on the watchlist.
        val glass = color(R.color.text_primary).withAlpha(0.12f)
        ui.swipeStarIcon.backgroundTintList = ColorStateList.valueOf(glass)
        ui.swipeChartIcon.backgroundTintList = ColorStateList.valueOf(glass)
        ui.swipeStarIcon.setImageResource(if (item.starred) R.drawable.ic_star else R.drawable.ic_star_outline)
        ui.swipeStarLabel.setText(if (item.starred) R.string.layout_action_unwatch else R.string.layout_action_watchlist)
        ui.swipeStar.contentDescription =
            context.getString(if (item.starred) R.string.list_star_remove else R.string.list_star_add, stock.symbol)
        ui.swipeChart.isEnabled = charted
        ui.swipeChart.alpha = if (charted) 1f else 0.38f
        ui.swipeCard.contentDescription = listOf(
            description(context, item, score, charted),
            context.getString(R.string.layout_swipe_hint, stock.symbol),
        ).joinToString(", ")
        // Without the swipe, the same two actions are in the accessibility menu.
        ViewCompat.replaceAccessibilityAction(
            ui.swipeCard,
            AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK,
            context.getString(if (item.starred) R.string.list_star_remove else R.string.list_star_add, stock.symbol),
        ) { _, _ ->
            onStar(stock)
            true
        }
    }

    /** The stock's figures as small capsules: market cap, volume, RVOL, its support floor and R/S. */
    private fun bindMetrics(group: ChipGroup, stock: ScanResult) {
        val context = group.context
        fun color(id: Int) = ContextCompat.getColor(context, id)
        group.removeAllViews()
        if (!stock.charted) return
        val muted = if (flat) Color.WHITE.withAlpha(0.06f) else color(R.color.neutral_container)
        fun capsule(text: String, tone: Int = R.color.text_secondary, container: Int? = null) {
            val view = TextView(context, null, 0, R.style.Pill).apply {
                this.text = text
                textSize = 11f
                setTextColor(color(tone))
                backgroundTintList = ColorStateList.valueOf(container?.let(::color) ?: muted)
            }
            group.addView(view)
        }
        stock.marketCapCr?.let { capsule(context.getString(R.string.cap_mcap, StockFormat.crore(it))) }
        if (stock.volume > 0.0) capsule(context.getString(R.string.cap_volume, StockFormat.shares(stock.volume)))
        val spike = stock.rvol >= StockFormat.SPIKE_RVOL
        capsule(
            context.getString(R.string.cap_rvol, StockFormat.rvol(stock.rvol)),
            if (spike) R.color.warn else R.color.text_secondary,
            if (spike) R.color.warn_container else null,
        )
        stock.support?.takeIf { it.ageSessions >= SupportFloors.LONG_AGE }?.let {
            capsule(
                context.getString(R.string.cap_support, StockFormat.price(it.level)),
                if (it.long) R.color.bull else R.color.text_secondary,
                if (it.long) R.color.bull_container else null,
            )
        }
        cr360.of(stock.symbol)?.takeIf { it.score != null }?.let { entry ->
            capsule(context.getString(R.string.cap_rs, String.format(Locale.US, "%.0f", entry.score)), entry.state.tone)
        }
    }

    /** The learned (or the app's own) setup line, as the card shows it, with its colour; null without one. */
    private fun setupText(context: Context, stock: ScanResult): Pair<String, Int>? {
        setups.mistake(stock.setupMask)?.let {
            return context.getString(R.string.item_setup_mistake, SetupBook.percent(it.all), SetupLabels.describe(context, it.conditions)) to
                R.color.bear
        }
        if (setups.rules.isNotEmpty()) {
            setups.best(stock.setupMask)?.let {
                return context.getString(R.string.item_setup_learned, SetupBook.percent(setups.shown(it)), SetupLabels.describe(context, it.conditions)) to
                    R.color.bull
            }
            return setups.rallySetup(stock.setupMask)?.let {
                context.getString(R.string.item_setup_rally, SetupBook.percent(it.all), SetupLabels.describe(context, it.conditions)) to R.color.bull
            }
        }
        val setup = stock.structure?.let { TradePlan.of(stock.price, it) }?.let { BreakoutSetup.of(stock, it) }
        return if (setup == BreakoutSetup.Kind.HIGH_PROBABILITY) {
            context.getString(R.string.item_setup_high, StockFormat.rvol(stock.rvol)) to R.color.bull
        } else {
            null
        }
    }

    /** The tier's label, colour and container colour. */
    private fun tierColors(score: Double): Triple<Int, Int, Int> = when (StockFormat.tier(score)) {
        StockFormat.Tier.STRONG -> Triple(R.string.tier_strong, R.color.bull, R.color.bull_container)
        StockFormat.Tier.WATCH -> Triple(R.string.tier_watch, R.color.warn, R.color.warn_container)
        StockFormat.Tier.WEAK -> Triple(R.string.tier_weak, R.color.neutral, R.color.neutral_container)
    }

    /** The rank circle in the tier's glass, or its plain container colour in the flat look. */
    private fun dressRank(holder: Holder, view: TextView, tier: StockFormat.Tier, tierColor: Int, tierContainer: Int) {
        val context = view.context
        view.setTextColor(ContextCompat.getColor(context, tierColor))
        if (holder.glassTier == tier) return
        holder.glassTier = tier
        if (flat) {
            view.setBackgroundResource(R.drawable.bg_circle)
            view.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, tierContainer))
        } else {
            view.wearChipGlass(null, tintBoost = 1.5f, accent = ContextCompat.getColor(context, tierColor))
        }
    }

    /** The exchange pill (NSE, SME or BSE); hidden for a stock with no chart yet, whose exchange is not known. */
    private fun bindExchange(view: TextView, market: Market, charted: Boolean) {
        val context = view.context
        val exchange = MarketStyle.of(market)
        view.isVisible = charted
        view.setText(exchange.label)
        view.setTextColor(ContextCompat.getColor(context, exchange.text))
        view.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, exchange.container))
    }

    /** What a screen reader says for a stock in the accordion, bento and swipe styles. */
    private fun description(context: Context, item: RankedStock, score: String, charted: Boolean): String {
        val stock = item.stock
        return listOfNotNull(
            when {
                !charted -> context.getString(R.string.item_description_no_data, stock.symbol)
                item.rank != RankedStock.UNRANKED -> context.getString(R.string.item_description, item.rank, stock.symbol, score)
                else -> context.getString(R.string.item_description_unranked, stock.symbol, score)
            },
            if (charted) context.getString(MarketStyle.of(stock.market).description) else null,
            if (charted) context.getString(R.string.item_price, StockFormat.price(stock.price)) else null,
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
        /** The score's gap to a table line's edge, as the SCORE column name keeps (compactColumns). */
        const val SCORE_EDGE_DP = 8

        /** The payload that opens or closes an accordion card without binding it again. */
        val UNFOLD = Any()
        const val UNFOLD_MS = 260L
        const val UNFOLD_SPRING = 0.8f

        /** Closes a bento tile's line shows: about three weeks. */
        const val SPARK_SESSIONS = 15
    }

    private object Diff : DiffUtil.ItemCallback<RankedStock>() {
        override fun areItemsTheSame(oldItem: RankedStock, newItem: RankedStock) =
            oldItem.stock.symbol == newItem.stock.symbol

        override fun areContentsTheSame(oldItem: RankedStock, newItem: RankedStock) = oldItem == newItem
    }
}

package com.rohit.share_scan

import androidx.annotation.ColorRes
import androidx.annotation.StringRes

/** The badge a market gets on cards: NSE in the theme accent, SME in amber, BSE in the second accent. */
data class MarketStyle(
    @StringRes val label: Int,
    @StringRes val description: Int,
    @ColorRes val text: Int,
    @ColorRes val container: Int,
) {
    companion object {
        fun of(market: Market): MarketStyle = when (market) {
            Market.NSE -> MarketStyle(R.string.market_nse, R.string.market_nse_description, R.color.brand_primary, R.color.primary_container)
            Market.NSE_SME -> MarketStyle(R.string.market_sme, R.string.market_sme_description, R.color.warn, R.color.warn_container)
            Market.BSE -> MarketStyle(R.string.market_bse, R.string.market_bse_description, R.color.accent_secondary, R.color.neutral_container)
        }
    }
}

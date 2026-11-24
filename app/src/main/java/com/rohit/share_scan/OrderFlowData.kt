package com.rohit.share_scan

import java.io.BufferedReader
import java.io.Reader
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime

internal data class OrderFlowLevel(
    val price: BigDecimal,
    var bidVolume: Long = 0,
    var askVolume: Long = 0,
) {
    val totalVolume: Long get() = bidVolume + askVolume
    val delta: Long get() = askVolume - bidVolume
}

internal data class OrderFlowBar(
    val symbol: String,
    val start: Instant,
    val levels: Map<BigDecimal, OrderFlowLevel>,
) {
    val bidVolume: Long get() = levels.values.sumOf { it.bidVolume }
    val askVolume: Long get() = levels.values.sumOf { it.askVolume }
    val totalVolume: Long get() = bidVolume + askVolume
    val delta: Long get() = askVolume - bidVolume
    val deltaPercent: Double get() = if (totalVolume == 0L) 0.0 else delta * 100.0 / totalVolume
    val poc: BigDecimal? get() = levels.values.maxByOrNull { it.totalVolume }?.price
}

internal data class OrderFlowDataset(
    val sourceName: String,
    val tradeCount: Int,
    val barsBySymbol: Map<String, List<OrderFlowBar>>,
) {
    val symbols: List<String> get() = barsBySymbol.keys.sorted()
}

/**
 * Imports vendor-normalized executed trades only. This is not an NSE native feed decoder:
 * every row must already contain an explicit aggressor side and a timezone-aware timestamp.
 */
internal object OrderFlowCsv {
    private const val MAX_TRADES = 250_000
    private val required = listOf("symbol", "timestamp", "price", "quantity", "aggressor")

    fun decode(reader: Reader, sourceName: String): OrderFlowDataset {
        val input = reader.buffered()
        val first = input.readLine()?.removePrefix("\uFEFF")
            ?: throw IllegalArgumentException("CSV file is empty")
        val headers = parseLine(first).map { it.trim().lowercase() }
        val positions = headers.withIndex().associate { it.value to it.index }
        val missing = required.filterNot(positions::containsKey)
        require(missing.isEmpty()) { "CSV header is missing: ${missing.joinToString(", ")}" }

        data class MutableBar(val symbol: String, val start: Instant, val levels: MutableMap<BigDecimal, OrderFlowLevel>)
        val bars = linkedMapOf<Pair<String, Long>, MutableBar>()
        val seenIds = HashSet<String>()
        var accepted = 0
        var lineNumber = 1

        input.lineSequence().forEach { line ->
            lineNumber++
            if (line.isBlank()) return@forEach
            require(accepted < MAX_TRADES) { "CSV exceeds the safe limit of $MAX_TRADES trades" }
            val fields = parseLine(line)
            fun field(name: String): String {
                val index = positions.getValue(name)
                require(index < fields.size) { "Missing $name at CSV row $lineNumber" }
                return fields[index].trim()
            }

            val symbol = field("symbol").uppercase()
            require(symbol.isNotBlank()) { "Empty symbol at CSV row $lineNumber" }
            val timestamp = try {
                OffsetDateTime.parse(field("timestamp")).toInstant()
            } catch (error: Exception) {
                throw IllegalArgumentException("Timestamp must include a timezone at CSV row $lineNumber", error)
            }
            val price = field("price").toBigDecimalOrNull()
                ?: throw IllegalArgumentException("Invalid price at CSV row $lineNumber")
            require(price > BigDecimal.ZERO) { "Price must be positive at CSV row $lineNumber" }
            val quantity = field("quantity").toLongOrNull()
                ?: throw IllegalArgumentException("Quantity must be a whole number at CSV row $lineNumber")
            require(quantity > 0) { "Quantity must be positive at CSV row $lineNumber" }
            val aggressor = field("aggressor").lowercase()
            require(aggressor == "buy" || aggressor == "sell") {
                "Aggressor must be explicitly buy or sell at CSV row $lineNumber"
            }

            val tradeId = positions["trade_id"]?.let { fields.getOrNull(it)?.trim()?.takeIf(String::isNotEmpty) }
            if (tradeId != null && !seenIds.add("$symbol:$tradeId")) return@forEach

            val minute = timestamp.epochSecond - timestamp.epochSecond % 60
            val key = symbol to minute
            val bar = bars.getOrPut(key) {
                MutableBar(symbol, Instant.ofEpochSecond(minute), linkedMapOf())
            }
            val normalizedPrice = price.stripTrailingZeros()
            val level = bar.levels.getOrPut(normalizedPrice) { OrderFlowLevel(normalizedPrice) }
            if (aggressor == "buy") level.askVolume += quantity else level.bidVolume += quantity
            accepted++
        }

        val grouped = bars.values
            .groupBy { it.symbol }
            .mapValues { (_, symbolBars) ->
                symbolBars.sortedBy { it.start }.map { bar ->
                    OrderFlowBar(bar.symbol, bar.start, bar.levels.toSortedMap())
                }
            }
        require(accepted > 0) { "No valid executed trades found in the CSV" }
        return OrderFlowDataset(sourceName, accepted, grouped)
    }

    /** CSV fields with quoted commas and doubled quote escaping; each trade occupies one line. */
    private fun parseLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    result += field.toString()
                    field.setLength(0)
                }
                else -> field.append(char)
            }
            index++
        }
        require(!quoted) { "Unclosed quote in CSV row" }
        result += field.toString()
        return result
    }
}

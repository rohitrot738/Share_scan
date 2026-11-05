package com.rohit.share_scan

import org.json.JSONObject

/**
 * Real pictures for a company's products section, found on the internet once and carried in the app
 * (assets/product-photos.json, the pictures under assets/product-photos/): its logo (from Wikidata, Wikimedia Commons
 * or its own website's icon), its website's share picture, and a photo for each of its products where Wikimedia
 * Commons has one naming the company. [AssetImages] shows them. A product without a photo keeps its drawn picture
 * ([ProductLabels.image]). Free of Android types so it can be unit tested.
 */
class ProductPhotos(private val entries: Map<String, Company>) {
    data class Company(
        val logo: String? = null,
        /** The share picture of the company's own website, often its products in use. */
        val banner: String? = null,
        /** A photo for each product key that has one. */
        val photos: Map<String, String> = emptyMap(),
        /** Who took a Commons picture and its licence, by the picture's file. */
        val credits: Map<String, String> = emptyMap(),
    )

    val size: Int get() = entries.size

    fun of(symbol: String): Company? = entries[symbol]

    companion object {
        val EMPTY = ProductPhotos(emptyMap())

        /** The pictures in [json]; [EMPTY] for null, broken or unexpected input. Only files of the app's own are kept. */
        fun parse(json: String?): ProductPhotos {
            if (json.isNullOrBlank()) return EMPTY
            return runCatching {
                val root = JSONObject(json)
                if (root.optInt("v") != 1) return EMPTY
                val stocks = root.optJSONObject("stocks") ?: return EMPTY
                val entries = HashMap<String, Company>(stocks.length() * 2)
                for (symbol in stocks.keys()) {
                    val row = stocks.optJSONObject(symbol) ?: continue
                    val company = Company(
                        logo = file(row.optString("logo")),
                        banner = file(row.optString("banner")),
                        photos = map(row.optJSONObject("p")) { key, path -> key.takeIf(Products::known)?.let { it to file(path) } },
                        credits = map(row.optJSONObject("c")) { path, credit -> file(path)?.let { it to credit.takeIf(String::isNotBlank) } },
                    )
                    if (company.logo != null || company.banner != null || company.photos.isNotEmpty()) entries[symbol] = company
                }
                ProductPhotos(entries)
            }.getOrDefault(EMPTY)
        }

        /** [path] when it names one of the app's product pictures ("product-photos/MRF/tyre.webp"); otherwise null. */
        private fun file(path: String?): String? =
            path?.takeIf { it.startsWith(FOLDER) && it.endsWith(".webp") && ".." !in it }

        private const val FOLDER = "product-photos/"

        /** [obj]'s pairs through [pair], which leaves a pair out by giving null for it or for its value. */
        private fun map(obj: JSONObject?, pair: (String, String) -> Pair<String, String?>?): Map<String, String> {
            obj ?: return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (key in obj.keys()) {
                val (k, v) = pair(key, obj.optString(key)) ?: continue
                if (v != null) out[k] = v
            }
            return out
        }
    }
}

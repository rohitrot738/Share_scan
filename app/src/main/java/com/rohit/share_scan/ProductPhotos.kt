package com.rohit.share_scan

import org.json.JSONObject

/**
 * Real pictures for a company's products section, found on the internet once and carried in the app
 * (assets/product-photos.json, the pictures under assets/product-photos/): its logo (from Wikidata, Wikimedia Commons
 * or its own website's icon), its website's share picture, and a photo for each of its products where Wikimedia
 * Commons has one naming the company. [AssetImages] shows them. A product without a photo keeps its drawn picture
 * ([ProductLabels.image]). Its brands, from its own website, come from a file of their own (assets/brands.json, the
 * pictures under assets/brand-photos/), grouped by the product they are. Free of Android types so it can be unit
 * tested.
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
        /** Its brands, a group for each product key, in the order its website shows them. */
        val brands: List<BrandGroup> = emptyList(),
        /** Where the brand pictures are from, such as "itcportal.com"; null when not known. */
        val brandSource: String? = null,
    )

    /** A brand and its picture, null when there is none. */
    data class Brand(val name: String, val picture: String?)

    /** The brands of one [product] (a [Products] key). */
    data class BrandGroup(val product: String, val brands: List<Brand>)

    val size: Int get() = entries.size

    fun of(symbol: String): Company? = entries[symbol]

    companion object {
        val EMPTY = ProductPhotos(emptyMap())

        /**
         * The pictures in [json] and the brands in [brands]; [EMPTY] for null, broken or unexpected pictures, and no
         * brands for broken ones. Only files of the app's own are kept.
         */
        fun parse(json: String?, brands: String? = null): ProductPhotos {
            val photos = pictures(json)
            val byCompany = brands(brands)
            if (byCompany.isEmpty()) return photos
            val entries = HashMap(photos.entries)
            for ((symbol, pair) in byCompany) {
                entries[symbol] = (entries[symbol] ?: Company()).copy(brands = pair.first, brandSource = pair.second)
            }
            return ProductPhotos(entries)
        }

        private fun pictures(json: String?): ProductPhotos {
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

        /**
         * Each company's brand groups and where they are from: {"v":1,"stocks":{"ITC":{"src":"itcportal.com","g":[
         * {"k":"food","b":[["Aashirvaad","brand-photos/ITC/aashirvaad.webp"]]}]}}}. A group of a product the app does
         * not know, a brand without a name and a picture not of the app's own are left out; empty for broken input.
         */
        private fun brands(json: String?): Map<String, Pair<List<BrandGroup>, String?>> {
            if (json.isNullOrBlank()) return emptyMap()
            return runCatching {
                val root = JSONObject(json)
                if (root.optInt("v") != 1) return emptyMap()
                val stocks = root.optJSONObject("stocks") ?: return emptyMap()
                val out = HashMap<String, Pair<List<BrandGroup>, String?>>()
                for (symbol in stocks.keys()) {
                    val row = stocks.optJSONObject(symbol) ?: continue
                    val groups = row.optJSONArray("g") ?: continue
                    val list = (0 until groups.length()).mapNotNull { i ->
                        val group = groups.optJSONObject(i) ?: return@mapNotNull null
                        val key = group.optString("k").takeIf(Products::known) ?: return@mapNotNull null
                        val items = group.optJSONArray("b") ?: return@mapNotNull null
                        val brands = (0 until items.length()).mapNotNull { j ->
                            val item = items.optJSONArray(j) ?: return@mapNotNull null
                            val name = item.optString(0).trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                            Brand(name, file(item.optString(1), BRAND_FOLDER))
                        }
                        BrandGroup(key, brands).takeIf { brands.isNotEmpty() }
                    }
                    if (list.isNotEmpty()) out[symbol] = list to row.optString("src").takeIf { it.isNotBlank() }
                }
                out
            }.getOrDefault(emptyMap())
        }

        /** [path] when it names one of the app's pictures in [folder] ("product-photos/MRF/tyre.webp"); otherwise null. */
        private fun file(path: String?, folder: String = FOLDER): String? =
            path?.takeIf { it.startsWith(folder) && it.endsWith(".webp") && ".." !in it }

        private const val FOLDER = "product-photos/"
        private const val BRAND_FOLDER = "brand-photos/"

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

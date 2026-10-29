package com.rohit.share_scan

/**
 * What a company makes or sells, as cr360/products.py reads it from the company's Screener profile (the file the app
 * carries, [ProductBook], or the daily 360CR file, [Cr360Book.Entry.products]): each product's key with its emoji,
 * which stands for its picture in text. The names and pictures are in [ProductLabels]. Free of Android types so it can
 * be unit tested.
 */
object Products {
    /** Pictures on a card when Settings → "Cards पर products के icon" is on. */
    const val CARD_ICONS = 3

    /** Every key cr360/products.py can give, in its order, with its icon. */
    val ICONS: Map<String, String> = linkedMapOf(
        "bank" to "🏦", "loans" to "💰", "insurance" to "☂️", "investing" to "📈", "payments" to "💳",
        "car" to "🚗", "suv" to "🚙", "bike" to "🏍️", "auto3w" to "🛺", "truck" to "🚚", "bus" to "🚌", "tractor" to "🚜",
        "ev" to "🔌", "tyre" to "🛞", "autoparts" to "⚙️", "battery" to "🔋",
        "aircraft" to "✈️", "ship" to "🚢", "rail" to "🚆", "defence" to "🛡️", "logistics" to "🚛", "port" to "⚓",
        "api" to "⚗️", "medicine" to "💊", "vaccine" to "💉", "hospital" to "🏥", "diagnostics" to "🔬", "meddevice" to "🩺",
        "ayurveda" to "🌿",
        "cement" to "🧱", "steel" to "🔩", "metals" to "🪙", "mining" to "⛏️", "chemicals" to "🧪", "fertiliser" to "🌱",
        "plastics" to "🪣", "pipes" to "🚰", "paint" to "🎨", "tiles" to "🛁", "paper" to "📄", "packaging" to "📦",
        "textile" to "🧵", "apparel" to "👕", "footwear" to "👟", "jewellery" to "💍", "watch" to "⌚", "bags" to "👜",
        "cable" to "🪢", "light" to "💡", "appliance" to "🌀", "electrical" to "🧲", "electronics" to "📺",
        "telecom" to "📶", "software" to "💻", "internet" to "🌐",
        "media" to "🎬", "news" to "🗞️", "gaming" to "🎮", "toys" to "🧸",
        "edibleoil" to "🌻", "oil" to "🛢️", "gas" to "🔥", "power" to "⚡", "solar" to "☀️",
        "household" to "🧺", "personalcare" to "🧴",
        "food" to "🍪", "dairy" to "🥛", "tea" to "☕", "sugar" to "🍬", "grains" to "🌾", "beverage" to "🥤", "alcohol" to "🍺",
        "tobacco" to "🚬", "spices" to "🌶️", "meat" to "🍗", "animalfeed" to "🐄",
        "restaurant" to "🍽️", "hotel" to "🏨", "retail" to "🛒", "furniture" to "🛋️", "cookware" to "🍳",
        "machinery" to "🏭", "construction" to "🏗️", "realty" to "🏘️", "water" to "💧", "recycling" to "♻️",
        "education" to "🎓", "staffing" to "👷",
    )

    /** The app knows [key] (a newer file may carry products this version cannot name; they are left out). */
    fun known(key: String): Boolean = key in ICONS

    fun icon(key: String): String = ICONS[key].orEmpty()

    /** The first [max] products' icons side by side, for a card's company line; empty without any. */
    fun icons(keys: List<String>, max: Int = CARD_ICONS): String = keys.take(max).joinToString("") { icon(it) }
}

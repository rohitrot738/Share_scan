package com.rohit.share_scan

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * The app's languages (Menu → भाषा और Font). [tag] is the BCP 47 tag the app's locale is set to, and the folder its
 * strings are kept under. Hinglish, the app's own mix of Hindi and English words, is the default (`values/`); its
 * strings are also copied at build time under `hi-Latn`, so that once it is picked a phone set to another language
 * the app speaks still shows Hinglish. [nativeName] is how the language names itself in the picker.
 */
enum class AppLanguage(val tag: String, val nativeName: String) {
    HINGLISH("hi-Latn", "Hinglish"),
    HINDI("hi", "हिन्दी"),
    ENGLISH("en", "English"),
    BENGALI("bn", "বাংলা"),
    GUJARATI("gu", "ગુજરાતી"),
    MARATHI("mr", "मराठी"),
    TELUGU("te", "తెలుగు"),
    TAMIL("ta", "தமிழ்"),
    KANNADA("kn", "ಕನ್ನಡ"),
    MALAYALAM("ml", "മലയാളം"),
    PUNJABI("pa", "ਪੰਜਾਬੀ"),
    ODIA("or", "ଓଡ଼ିଆ");

    /** The locale dates and month names are written in: Hindi's for Hinglish, the language's own in India for the rest. */
    val dateLocale: Locale
        get() = if (this == HINGLISH) Locale.forLanguageTag("hi-IN") else Locale.forLanguageTag("$tag-IN")

    companion object {
        val DEFAULT = HINGLISH

        /** The language of [locale]: Hindi in Latin script is Hinglish; another language the app does not speak, null. */
        fun of(locale: Locale?): AppLanguage? {
            locale ?: return null
            if (locale.language == "hi") return if (locale.script == "Latn") HINGLISH else HINDI
            return entries.firstOrNull { it.tag == locale.language }
        }

        fun fromTag(tag: String?): AppLanguage? = tag?.let { of(Locale.forLanguageTag(it)) }
    }
}

/**
 * The app's language: set through AppCompat's per-app language, which Android 13 and later keeps itself (and shows in
 * the phone's own settings) and older Android has AppCompat keep for the app's screens. Strings made outside a screen
 * (notifications, the channels' names) go through [localized], which on older Android puts them in the same
 * language; [Prefs.LANGUAGE] keeps the pick for that.
 */
object Languages {
    /** The language picked, or Hinglish until one is. */
    fun current(context: Context): AppLanguage =
        AppLanguage.of(AppCompatDelegate.getApplicationLocales()[0])
            ?: AppLanguage.fromTag(prefs(context).getString(Prefs.LANGUAGE, null))
            ?: AppLanguage.DEFAULT

    /** Switches the app to [language]; the open screen is re-created in it. */
    fun pick(context: Context, language: AppLanguage) {
        prefs(context).edit { putString(Prefs.LANGUAGE, language.tag) }
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language.tag))
    }

    /**
     * Makes Hinglish the app's language on its first start with languages, rather than the phone's, so a phone in
     * English or Bengali keeps the app as it was until another language is picked. The screen is then re-created once.
     */
    fun settle(context: Context) {
        val prefs = prefs(context)
        if (prefs.contains(Prefs.LANGUAGE)) return
        val set = AppLanguage.of(AppCompatDelegate.getApplicationLocales()[0])
        if (set != null) {
            prefs.edit { putString(Prefs.LANGUAGE, set.tag) }
        } else {
            pick(context, AppLanguage.DEFAULT)
        }
    }

    /**
     * [context] in the app's language, for strings made outside its screens. Android 13 and later already gives every
     * part of the app its language; before that only the screens have it, so the rest gets it here.
     */
    fun localized(context: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return context
        val language = AppLanguage.fromTag(prefs(context).getString(Prefs.LANGUAGE, null)) ?: AppLanguage.DEFAULT
        val locale = Locale.forLanguageTag(language.tag)
        if (context.resources.configuration.locales[0] == locale) return context
        val config = Configuration(context.resources.configuration)
        config.setLocales(LocaleList(locale))
        return context.createConfigurationContext(config)
    }

    /** The number formats' words for days, months and lakh ([StockFormat.words]) in [context]'s language. */
    fun applyWords(context: Context) {
        StockFormat.words = StockFormat.UnitWords(
            today = context.getString(R.string.unit_today),
            daysAgo = context.getString(R.string.unit_days_ago),
            days = context.getString(R.string.unit_days),
            month = context.getString(R.string.unit_month),
            months = context.getString(R.string.unit_months),
            week = context.getString(R.string.unit_week),
            weeks = context.getString(R.string.unit_weeks),
            lakh = context.getString(R.string.unit_lakh),
        )
    }

    private fun prefs(context: Context) = context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
}

/** The locale this context's dates and month names are written in, by the app's language. */
fun Context.dateLocale(): Locale =
    (AppLanguage.of(resources.configuration.locales[0]) ?: AppLanguage.DEFAULT).dateLocale

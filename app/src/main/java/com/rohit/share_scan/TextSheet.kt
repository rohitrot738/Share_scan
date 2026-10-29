package com.rohit.share_scan

import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import androidx.annotation.FontRes
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.rohit.share_scan.databinding.SheetTextBinding

/** The font's family resource; null for the phone's own. */
@get:FontRes
val AppFont.family: Int?
    get() = when (this) {
        AppFont.SYSTEM -> null
        AppFont.MUKTA -> R.font.mukta
        AppFont.HIND -> R.font.hind
        AppFont.POPPINS -> R.font.poppins
        AppFont.INTER -> R.font.inter
        AppFont.SPACE_GROTESK -> R.font.space_grotesk
        AppFont.JETBRAINS_MONO -> R.font.jetbrains_mono
    }

/** The theme overlay that sets the font ([R.attr.appFont]); null for the phone's own, which lays none. */
@get:StyleRes
val AppFont.overlay: Int?
    get() = when (this) {
        AppFont.SYSTEM -> null
        AppFont.MUKTA -> R.style.ThemeOverlay_ShareScan_Font_Mukta
        AppFont.HIND -> R.style.ThemeOverlay_ShareScan_Font_Hind
        AppFont.POPPINS -> R.style.ThemeOverlay_ShareScan_Font_Poppins
        AppFont.INTER -> R.style.ThemeOverlay_ShareScan_Font_Inter
        AppFont.SPACE_GROTESK -> R.style.ThemeOverlay_ShareScan_Font_SpaceGrotesk
        AppFont.JETBRAINS_MONO -> R.style.ThemeOverlay_ShareScan_Font_JetBrainsMono
    }

/** The font's own name; the phone's is named in the app's language. */
fun AppFont.label(activity: AppCompatActivity): String = when (this) {
    AppFont.SYSTEM -> activity.getString(R.string.font_system)
    AppFont.MUKTA -> "Mukta"
    AppFont.HIND -> "Hind"
    AppFont.POPPINS -> "Poppins"
    AppFont.INTER -> "Inter"
    AppFont.SPACE_GROTESK -> "Space Grotesk"
    AppFont.JETBRAINS_MONO -> "JetBrains Mono"
}

/**
 * The app's language and font. Each language names itself; each font chip is written in its own font, and a sample
 * line shows the picked one. A language applies at once (AppCompat re-creates the screen in it); a font through
 * [onFont], which re-creates the screen with it.
 */
class TextSheet(
    private val activity: AppCompatActivity,
    private val settings: AppSettings,
    private val onFont: (AppFont) -> Unit,
    private val onBack: () -> Unit,
) {
    fun show(): BottomSheetDialog {
        val ui = SheetTextBinding.inflate(activity.layoutInflater)
        lateinit var dialog: BottomSheetDialog
        val language = Languages.current(activity)
        for (choice in AppLanguage.entries) {
            val chip = chip(ui.textLanguages)
            chip.text = choice.nativeName
            chip.contentDescription = activity.getString(R.string.text_language_description, choice.nativeName)
            chip.isChecked = choice == language
            chip.setOnClickListener {
                if (choice == language) return@setOnClickListener
                dialog.dismiss()
                Languages.pick(activity, choice)
            }
            ui.textLanguages.addView(chip)
        }
        val font = settings.font
        for (choice in AppFont.entries) {
            val chip = chip(ui.textFonts)
            chip.text = choice.label(activity)
            choice.family?.let { typeface(it) }?.let { chip.typeface = it }
            chip.contentDescription = activity.getString(R.string.text_font_description, choice.label(activity))
            chip.isChecked = choice == font
            chip.setOnClickListener {
                if (choice == font) return@setOnClickListener
                dialog.dismiss()
                onFont(choice)
            }
            ui.textFonts.addView(chip)
        }
        font.family?.let { typeface(it) }?.let { ui.textSample.typeface = it }
        dialog = activity.showSheet(ui.root, ui.sheetTopBar, onBack)
        return dialog
    }

    private fun chip(group: ViewGroup): Chip =
        (activity.layoutInflater.inflate(R.layout.chip_choice, group, false) as Chip).apply {
            id = View.generateViewId()
            isCheckable = true
        }

    /** A bundled font, or null should it fail to load (the chip then keeps the theme's). */
    private fun typeface(@FontRes id: Int): Typeface? = runCatching { ResourcesCompat.getFont(activity, id) }.getOrNull()
}

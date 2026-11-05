package com.rohit.share_scan

import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.rohit.share_scan.databinding.SheetListBinding
import kotlin.math.roundToInt

/**
 * A company's brands in a window of their own, opened from "क्या बनाती है" in the stock's detail
 * ([StockDetailSheet]): how many and where the pictures are from, then for each product they are its name and the
 * brands two to a row, each its picture over its name (a brand known by name alone, its name on a plain tile), in the
 * order the company's website shows them ([ProductPhotos.Company.brands]).
 */
class BrandSheet(
    private val activity: AppCompatActivity,
    private val symbol: String,
    private val company: ProductPhotos.Company,
) {
    fun show() {
        val ui = SheetListBinding.inflate(activity.layoutInflater)
        val groups = company.brands
        ui.listTitle.text = activity.getString(R.string.brands_window_title, symbol, activity.getString(R.string.detail_brands_title))
        ui.listSubtitle.text = listOfNotNull(
            activity.getString(R.string.detail_brands_hint, groups.sumOf { it.brands.size }),
            company.brandSource?.let { activity.getString(R.string.detail_brands_credit, it) },
        ).joinToString(" · ")
        val dp = activity.resources.displayMetrics.density
        val gap = (GAP_DP * dp).roundToInt()
        // The window's width less the list's sides, shared by the columns and the gaps between them.
        val inner = activity.resources.displayMetrics.widthPixels - (2 * SIDE_DP * dp).roundToInt()
        val tileWidth = (inner - (COLUMNS - 1) * gap) / COLUMNS
        val tileHeight = (tileWidth / ASPECT).roundToInt()
        val shape = ShapeAppearanceModel.builder().setAllCornerSizes(CORNER_DP * dp).build()
        for (group in groups) {
            ui.listItems.addView(
                TextView(activity).apply {
                    text = activity.getString(R.string.detail_brands_group, ProductLabels.chip(activity, group.product), group.brands.size)
                    setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (18 * dp).roundToInt() },
            )
            for (row in group.brands.chunked(COLUMNS)) {
                val line = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                row.forEachIndexed { index, brand ->
                    val tile = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        contentDescription = brand.name
                    }
                    val file = brand.picture
                    if (file != null) {
                        val picture = ShapeableImageView(activity).apply {
                            shapeAppearanceModel = shape
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                            setBackgroundColor(ContextCompat.getColor(activity, R.color.neutral_container))
                        }
                        tile.addView(picture, LinearLayout.LayoutParams(tileWidth, tileHeight))
                        AssetImages.load(activity, picture, file, tileWidth) { picture.background = null }
                    } else {
                        // A brand known by name alone: its name, large, on a plain tile of the same size.
                        tile.addView(
                            TextView(activity).apply {
                                text = brand.name
                                textSize = 17f
                                gravity = Gravity.CENTER
                                maxLines = 2
                                ellipsize = TextUtils.TruncateAt.END
                                setTypeface(typeface, Typeface.BOLD)
                                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                                setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
                                setPadding((8 * dp).roundToInt(), 0, (8 * dp).roundToInt(), 0)
                                background = MaterialShapeDrawable(shape).apply {
                                    fillColor = ContextCompat.getColorStateList(activity, R.color.neutral_container)
                                }
                            },
                            LinearLayout.LayoutParams(tileWidth, tileHeight),
                        )
                    }
                    tile.addView(
                        TextView(activity).apply {
                            text = brand.name
                            textSize = 12f
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                            gravity = Gravity.CENTER
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                            setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
                            // The name is on the tile already; the line keeps the rows even.
                            if (file == null) visibility = View.INVISIBLE
                        },
                        LinearLayout.LayoutParams(tileWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (4 * dp).roundToInt() },
                    )
                    line.addView(tile, LinearLayout.LayoutParams(tileWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (index > 0) marginStart = gap })
                }
                ui.listItems.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = gap })
            }
        }
        activity.showSheet(ui.root, ui.sheetTopBar, onBack = {}, fullHeight = true)
    }

    private companion object {
        /** Two to a row, as wide as they are tall times [ASPECT] (the websites' tiles). */
        const val COLUMNS = 2
        const val ASPECT = 1.58
        const val GAP_DP = 12
        const val CORNER_DP = 12f
        /** The list's side padding (sheet_list.xml). */
        const val SIDE_DP = 16
    }
}
